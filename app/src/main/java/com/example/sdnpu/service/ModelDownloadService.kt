package com.example.sdnpu.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.sdnpu.MainActivity
import com.example.sdnpu.model.DownloadStatus
import com.example.sdnpu.model.ModelManager
import com.example.sdnpu.model.ModelManifest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Foreground Service for downloading Stable Diffusion models in the background.
 * Holds a partial WakeLock to prevent socket closure on lock screen/idle, and provides
 * an ongoing notification with pause, resume, and cancel actions.
 */
class ModelDownloadService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private var downloadJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var currentUrl: String? = null
    private var currentModelId: String? = null
    private var currentManifest: ModelManifest? = null

    private var lastNotificationTime = 0L
    private var lastNotificationPercent = -1

    private val defaultModelManager by lazy {
        ModelManager(
            baseStorageDir = getExternalFilesDir(null)?.let { File(it, "models") } ?: File(filesDir, "models"),
            secondaryStorageDir = File(filesDir, "models"),
            fallbackStorageDir = File(cacheDir, "models")
        )
    }

    private val modelManager: ModelManager
        get() = modelManagerOverride ?: defaultModelManager

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: run {
            val shutdownNotification = buildNotification(
                title = "Model Download",
                content = "Service started without action",
                progress = 0,
                indeterminate = false,
                isOngoing = false,
                actions = emptyList()
            )
            startServiceForeground(shutdownNotification)
            stopServiceForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        when (action) {
            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: run {
                    val shutdownNotification = buildNotification(
                        title = "Model Download",
                        content = "Missing download URL",
                        progress = 0,
                        indeterminate = false,
                        isOngoing = false,
                        actions = emptyList()
                    )
                    startServiceForeground(shutdownNotification)
                    stopServiceForeground(true)
                    stopSelf()
                    return START_NOT_STICKY
                }
                val modelId = intent.getStringExtra(EXTRA_MODEL_ID)
                handleStart(url, modelId)
            }
            ACTION_PAUSE -> {
                handlePause()
            }
            ACTION_RESUME -> {
                val url = intent.getStringExtra(EXTRA_URL)
                val modelId = intent.getStringExtra(EXTRA_MODEL_ID)
                handleResume(url, modelId)
            }
            ACTION_CANCEL -> {
                handleCancel()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadJob?.cancel()
        downloadJob = null
        releaseWakeLock()
        serviceJob.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = CHANNEL_DESC
                    setShowBadge(false)
                }
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.createNotificationChannel(channel)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to create notification channel: ${e.message}")
            }
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                // 30 minute safety timeout to protect battery
                wakeLock?.acquire(30 * 60 * 1000L)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to acquire wake lock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to release wake lock: ${e.message}")
        }
    }

    private fun startServiceForeground(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        )
    }

    private fun stopServiceForeground(removeNotification: Boolean) {
        if (removeNotification) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(NOTIFICATION_ID)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } else {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        }
    }

    private fun handleStart(url: String, modelId: String?) {
        modelManager.resetPause()
        currentUrl = url
        currentModelId = modelId ?: run {
            when {
                url.contains("qcs8550", ignoreCase = true) || url.contains("sd15_qnn_npu", ignoreCase = true) -> "sd15_qnn_npu"
                url.contains("sd-turbo", ignoreCase = true) || url.contains("sdturbo", ignoreCase = true) -> "sdturbo"
                url.contains("dreamshaper", ignoreCase = true) -> "dreamshaper_v8_base"
                url.contains("realesrgan", ignoreCase = true) -> {
                    if (url.contains("x4", ignoreCase = true) || url.contains("4plus", ignoreCase = true)) {
                        "realesrgan_x4plus"
                    } else {
                        "realesrgan_x2plus"
                    }
                }
                url.contains("anime", ignoreCase = true) -> "dreamshaper_v8_anime"
                url.contains("realistic", ignoreCase = true) -> "dreamshaper_v8_realistic"
                else -> "sd15_qnn_npu"
            }
        }

        val initialNotification = buildNotification(
            title = "Downloading ${currentModelId ?: "Model"}",
            content = "Preparing download...",
            progress = 0,
            indeterminate = true,
            isOngoing = true,
            actions = listOf(createCancelAction())
        )
        startServiceForeground(initialNotification)
        acquireWakeLock()

        downloadJob?.cancel()
        downloadJob = serviceScope.launch {
            val manifestStatus = DownloadStatus.FetchingManifest(url)
            handleStatusUpdate(manifestStatus)

            val manifestRes = modelManager.fetchManifest(url)
            val manifest = if (manifestRes.isSuccess) {
                manifestRes.getOrThrow()
            } else {
                when (currentModelId) {
                    "sd15_qnn_npu" -> ModelManifest.sd15QnnNpu()
                    "sdturbo" -> ModelManifest.sdturbo()
                    "dreamshaper_v8_base" -> ModelManifest.dreamshaper_v8_base()
                    "dreamshaper_v8_anime" -> ModelManifest.dreamshaper_v8_anime()
                    "dreamshaper_v8_realistic" -> ModelManifest.dreamshaper_v8_realistic()
                    "realesrgan_x2plus" -> ModelManifest.realesrgan_x2plus()
                    "realesrgan_x4plus" -> ModelManifest.realesrgan_x4plus()
                    else -> {
                        val err = "Cannot fetch manifest: ${manifestRes.exceptionOrNull()?.message}"
                        handleStatusUpdate(DownloadStatus.Failed(err))
                        return@launch
                    }
                }
            }
            currentManifest = manifest
            currentModelId = manifest.modelId

            modelManager.downloadModel(manifest, url).collect { status ->
                handleStatusUpdate(status)
            }
        }
    }

    private fun handlePause() {
        modelManager.pauseDownload()
    }

    private fun handleResume(url: String?, modelId: String?) {
        if (url != null) currentUrl = url
        if (modelId != null) {
            currentModelId = modelId
        } else if (currentModelId == null && currentUrl != null) {
            val u = currentUrl!!
            currentModelId = when {
                u.contains("qcs8550", ignoreCase = true) || u.contains("sd15_qnn_npu", ignoreCase = true) -> "sd15_qnn_npu"
                u.contains("sd-turbo", ignoreCase = true) || u.contains("sdturbo", ignoreCase = true) -> "sdturbo"
                u.contains("dreamshaper", ignoreCase = true) -> "dreamshaper_v8_base"
                u.contains("realesrgan", ignoreCase = true) -> {
                    if (u.contains("x4", ignoreCase = true) || u.contains("4plus", ignoreCase = true)) {
                        "realesrgan_x4plus"
                    } else {
                        "realesrgan_x2plus"
                    }
                }
                u.contains("anime", ignoreCase = true) -> "dreamshaper_v8_anime"
                u.contains("realistic", ignoreCase = true) -> "dreamshaper_v8_realistic"
                else -> "sd15_qnn_npu"
            }
        }

        val resumeUrl = currentUrl
        if (resumeUrl == null) {
            val shutdownNotification = buildNotification(
                title = "Model Download",
                content = "No active download to resume",
                progress = 0,
                indeterminate = false,
                isOngoing = false,
                actions = emptyList()
            )
            startServiceForeground(shutdownNotification)
            stopServiceForeground(true)
            stopSelf()
            return
        }

        val initialNotification = buildNotification(
            title = "Downloading ${currentModelId ?: "Model"}",
            content = "Resuming download...",
            progress = 0,
            indeterminate = true,
            isOngoing = true,
            actions = listOf(createPauseAction(), createCancelAction())
        )
        startServiceForeground(initialNotification)
        acquireWakeLock()

        downloadJob?.cancel()
        downloadJob = serviceScope.launch {
            val manifest = currentManifest ?: run {
                val manifestRes = modelManager.fetchManifest(resumeUrl)
                if (manifestRes.isSuccess) {
                    manifestRes.getOrThrow()
                } else {
                    when (currentModelId) {
                        "sd15_qnn_npu" -> ModelManifest.sd15QnnNpu()
                        "sdturbo" -> ModelManifest.sdturbo()
                        "dreamshaper_v8_base" -> ModelManifest.dreamshaper_v8_base()
                        "dreamshaper_v8_anime" -> ModelManifest.dreamshaper_v8_anime()
                        "dreamshaper_v8_realistic" -> ModelManifest.dreamshaper_v8_realistic()
                        "realesrgan_x2plus" -> ModelManifest.realesrgan_x2plus()
                        "realesrgan_x4plus" -> ModelManifest.realesrgan_x4plus()
                        else -> {
                            val err = "Cannot fetch manifest: ${manifestRes.exceptionOrNull()?.message}"
                            handleStatusUpdate(DownloadStatus.Failed(err))
                            return@launch
                        }
                    }
                }
            }
            currentManifest = manifest
            currentModelId = manifest.modelId

            modelManager.resumeDownload(manifest, resumeUrl).collect { status ->
                handleStatusUpdate(status)
            }
        }
    }

    private fun handleCancel() {
        downloadJob?.cancel()
        downloadJob = null
        modelManager.pauseDownload()
        releaseWakeLock()
        downloadStatus.value = DownloadStatus.Idle
        stopServiceForeground(removeNotification = true)
        stopSelf()
    }

    private fun handleStatusUpdate(status: DownloadStatus) {
        downloadStatus.value = status
        when (status) {
            is DownloadStatus.FetchingManifest -> {
                acquireWakeLock()
                updateNotification(status)
            }
            is DownloadStatus.DownloadingComponent -> {
                if (!shouldThrottleNotification(status.progressPercent)) {
                    updateNotification(status)
                }
            }
            is DownloadStatus.Paused -> {
                releaseWakeLock()
                updateNotification(status)
            }
            is DownloadStatus.VerifyingChecksum -> {
                updateNotification(status)
            }
            is DownloadStatus.Completed -> {
                releaseWakeLock()
                updateNotification(status)
                stopServiceForeground(removeNotification = false)
                stopSelf()
            }
            is DownloadStatus.Failed -> {
                releaseWakeLock()
                updateNotification(status)
                stopServiceForeground(removeNotification = false)
                stopSelf()
            }
            is DownloadStatus.Idle -> {
                releaseWakeLock()
            }
        }
    }

    private fun shouldThrottleNotification(percent: Int): Boolean {
        val now = System.currentTimeMillis()
        if (percent == 100 || percent == 0 || (percent != lastNotificationPercent && (percent - lastNotificationPercent >= 2 || now - lastNotificationTime >= 500L))) {
            lastNotificationTime = now
            lastNotificationPercent = percent
            return false
        }
        return true
    }

    private fun updateNotification(status: DownloadStatus) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val notification = when (status) {
            is DownloadStatus.Idle -> null
            is DownloadStatus.FetchingManifest -> {
                buildNotification(
                    title = "Downloading ${currentModelId ?: "Model"}",
                    content = "Fetching manifest...",
                    progress = 0,
                    indeterminate = true,
                    isOngoing = true,
                    actions = listOf(createCancelAction())
                )
            }
            is DownloadStatus.DownloadingComponent -> {
                buildNotification(
                    title = "Downloading ${currentModelId ?: "Model"}",
                    content = "${status.componentName}: ${status.progressPercent}%",
                    progress = status.progressPercent,
                    indeterminate = false,
                    isOngoing = true,
                    actions = listOf(createPauseAction(), createCancelAction())
                )
            }
            is DownloadStatus.Paused -> {
                lastNotificationPercent = -1
                buildNotification(
                    title = "Paused: ${status.modelId.ifEmpty { currentModelId ?: "Model" }}",
                    content = "${status.componentName} (${status.percent}%)",
                    progress = status.percent,
                    indeterminate = false,
                    isOngoing = false,
                    actions = listOf(createResumeAction(), createCancelAction())
                )
            }
            is DownloadStatus.VerifyingChecksum -> {
                buildNotification(
                    title = "Downloading ${currentModelId ?: "Model"}",
                    content = "Verifying ${status.componentName}...",
                    progress = 0,
                    indeterminate = true,
                    isOngoing = true,
                    actions = listOf(createCancelAction())
                )
            }
            is DownloadStatus.Completed -> {
                lastNotificationPercent = -1
                buildNotification(
                    title = "Download Complete: ${status.modelId}",
                    content = "Model is ready for generation",
                    progress = 0,
                    indeterminate = false,
                    isOngoing = false,
                    actions = emptyList(),
                    iconRes = android.R.drawable.stat_sys_download_done
                )
            }
            is DownloadStatus.Failed -> {
                lastNotificationPercent = -1
                buildNotification(
                    title = "Download Failed: ${currentModelId ?: "Model"}",
                    content = status.reason,
                    progress = 0,
                    indeterminate = false,
                    isOngoing = false,
                    actions = emptyList(),
                    iconRes = android.R.drawable.stat_notify_error
                )
            }
        }
        if (notification != null) {
            nm.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(
        title: String,
        content: String,
        progress: Int = 0,
        indeterminate: Boolean = false,
        isOngoing: Boolean = true,
        actions: List<NotificationCompat.Action> = emptyList(),
        iconRes: Int = android.R.drawable.stat_sys_download
    ): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(iconRes)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(if (isOngoing) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(isOngoing)
            .setAutoCancel(!isOngoing)
            .setOnlyAlertOnce(true)
            .setContentIntent(createContentIntent())

        if (isOngoing || progress > 0 || indeterminate) {
            builder.setProgress(100, progress.coerceIn(0, 100), indeterminate)
        }

        for (action in actions) {
            builder.addAction(action)
        }

        return builder.build()
    }

    private fun createContentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getActivity(this, REQUEST_CODE_CONTENT, intent, flags)
    }

    private fun createPauseAction(): NotificationCompat.Action {
        val intent = Intent(this, ModelDownloadService::class.java).apply {
            action = ACTION_PAUSE
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getService(this, REQUEST_CODE_PAUSE, intent, flags)
        return NotificationCompat.Action.Builder(
            android.R.drawable.ic_media_pause,
            "Pause",
            pendingIntent
        ).build()
    }

    private fun createResumeAction(): NotificationCompat.Action {
        val intent = Intent(this, ModelDownloadService::class.java).apply {
            action = ACTION_RESUME
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getService(this, REQUEST_CODE_RESUME, intent, flags)
        return NotificationCompat.Action.Builder(
            android.R.drawable.ic_media_play,
            "Resume",
            pendingIntent
        ).build()
    }

    private fun createCancelAction(): NotificationCompat.Action {
        val intent = Intent(this, ModelDownloadService::class.java).apply {
            action = ACTION_CANCEL
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getService(this, REQUEST_CODE_CANCEL, intent, flags)
        return NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Cancel",
            pendingIntent
        ).build()
    }

    companion object {
        private const val TAG = "ModelDownloadService"
        private const val WAKELOCK_TAG = "sdnpu:ModelDownloadWakeLock"

        const val CHANNEL_ID = "sd_npu_downloads"
        const val CHANNEL_NAME = "Model Downloads"
        private const val CHANNEL_DESC = "Notifications for model downloads progress and status"
        const val NOTIFICATION_ID = 2002

        const val ACTION_START = "com.example.sdnpu.service.ACTION_START"
        const val ACTION_PAUSE = "com.example.sdnpu.service.ACTION_PAUSE"
        const val ACTION_RESUME = "com.example.sdnpu.service.ACTION_RESUME"
        const val ACTION_CANCEL = "com.example.sdnpu.service.ACTION_CANCEL"

        const val EXTRA_URL = "com.example.sdnpu.service.EXTRA_URL"
        const val EXTRA_MODEL_ID = "com.example.sdnpu.service.EXTRA_MODEL_ID"

        private const val REQUEST_CODE_CONTENT = 100
        private const val REQUEST_CODE_PAUSE = 101
        private const val REQUEST_CODE_RESUME = 102
        private const val REQUEST_CODE_CANCEL = 103

        val downloadStatus = MutableStateFlow<DownloadStatus>(DownloadStatus.Idle)

        @Volatile
        var modelManagerOverride: ModelManager? = null

        fun startDownload(context: Context, url: String, modelId: String? = null) {
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_MODEL_ID, modelId)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun pauseDownload(context: Context) {
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_PAUSE
            }
            context.startService(intent)
        }

        fun resumeDownload(context: Context, url: String? = null, modelId: String? = null) {
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_RESUME
                if (url != null) putExtra(EXTRA_URL, url)
                if (modelId != null) putExtra(EXTRA_MODEL_ID, modelId)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancelDownload(context: Context) {
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }
    }
}
