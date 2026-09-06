package com.example.sdnpu.system

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.sdnpu.MainActivity
import java.io.File

/**
 * Manages notification lifecycle for Stable Diffusion image generation.
 * Posts ongoing progress notifications during sampling/upscaling and completion
 * alerts with image preview. Safe for Android API 26 through 35.
 */
class GenerationNotificationManager(private val context: Context? = null) {

    private val appContext: Context? = context?.applicationContext ?: context

    private val notificationManager: NotificationManager? by lazy {
        appContext?.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && appContext != null) {
            try {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = CHANNEL_DESC
                    setShowBadge(false)
                }
                notificationManager?.createNotificationChannel(channel)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to create notification channel: ${e.message}")
            }
        }
    }

    /**
     * Checks if the app has permission to post notifications (required on Android 13+).
     */
    fun hasNotificationPermission(): Boolean {
        val ctx = appContext ?: return false
        val enabled = notificationManager?.areNotificationsEnabled() != false
        if (!enabled) return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                ctx,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun createContentIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getActivity(ctx, 0, intent, flags)
    }

    /**
     * Shows or updates an ongoing notification with generation progress.
     */
    fun notifyGenerationProgress(step: Int, total: Int, prompt: String = "") {
        val ctx = appContext ?: return
        if (!hasNotificationPermission()) return

        try {
            val title = if (prompt.isNotBlank()) {
                val preview = if (prompt.length > 35) prompt.take(35) + "..." else prompt
                "Generating: $preview"
            } else {
                "Generating Image..."
            }
            val text = "Step $step of $total"

            val maxProgress = total.coerceAtLeast(1)
            val currentProgress = step.coerceIn(0, maxProgress)

            val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(createContentIntent(ctx))
                .setProgress(maxProgress, currentProgress, false)

            notificationManager?.notify(NOTIFICATION_ID, builder.build())
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to display progress notification: ${e.message}")
        }
    }

    /**
     * Shows a completion notification with image details and optional preview thumbnail.
     */
    fun notifyGenerationCompleted(imagePath: String, prompt: String = "") {
        val ctx = appContext ?: return
        if (!hasNotificationPermission()) return

        try {
            val title = "Generation Complete"
            val text = if (prompt.isNotBlank()) prompt else "Image generated successfully"

            val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_gallery)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setOngoing(false)
                .setAutoCancel(true)
                .setContentIntent(createContentIntent(ctx))

            val file = File(imagePath)
            if (file.exists() && file.isFile) {
                try {
                    val bitmap = decodeDownsampledBitmap(file, maxDimension = 512)
                    if (bitmap != null) {
                        builder.setLargeIcon(bitmap)
                        builder.setStyle(
                            NotificationCompat.BigPictureStyle()
                                .bigPicture(bitmap)
                                .setSummaryText(text)
                        )
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed to decode notification preview bitmap: ${e.message}")
                }
            }

            notificationManager?.notify(NOTIFICATION_ID, builder.build())
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to display completion notification: ${e.message}")
        }
    }

    private fun decodeDownsampledBitmap(file: File, maxDimension: Int): android.graphics.Bitmap? {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, boundsOptions)
        var sampleSize = 1
        val maxSide = maxOf(boundsOptions.outWidth, boundsOptions.outHeight)
        while (maxSide / (sampleSize * 2) >= maxDimension) {
            sampleSize *= 2
        }
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
    }

    /**
     * Cancels any active generation notification.
     */
    fun cancel() {
        try {
            notificationManager?.cancel(NOTIFICATION_ID)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to cancel notification: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "GenNotificationMgr"
        const val CHANNEL_ID = "sd_npu_generation"
        const val CHANNEL_NAME = "Image Generation"
        private const val CHANNEL_DESC = "Notifications for image generation progress and completion"
        const val NOTIFICATION_ID = 1001
    }
}
