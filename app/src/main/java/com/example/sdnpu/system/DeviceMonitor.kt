package com.example.sdnpu.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor

/**
 * Thermal status levels corresponding to Android PowerManager thermal throttling states.
 */
enum class ThermalStatus {
    NONE,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL,
    EMERGENCY,
    SHUTDOWN;

    fun isThrottlingSevere(): Boolean {
        return this == SEVERE || this == CRITICAL || this == EMERGENCY || this == SHUTDOWN
    }

    companion object {
        fun fromInt(status: Int): ThermalStatus {
            return when (status) {
                0 -> NONE
                1 -> LIGHT
                2 -> MODERATE
                3 -> SEVERE
                4 -> CRITICAL
                5 -> EMERGENCY
                6 -> SHUTDOWN
                else -> NONE
            }
        }
    }
}

/**
 * Monitors device thermal status, battery level, and charging state.
 * Safe for execution across Android API 26-35 and fallback safe in JVM test environments.
 */
class DeviceMonitor(private val context: Context? = null) {

    private val _thermalStatus = MutableStateFlow(ThermalStatus.NONE)
    val thermalStatus: StateFlow<ThermalStatus> = _thermalStatus.asStateFlow()

    private val _batteryLevel = MutableStateFlow(100)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    private val _isCharging = MutableStateFlow(true)
    val isCharging: StateFlow<Boolean> = _isCharging.asStateFlow()

    private val _isLowBattery = MutableStateFlow(false)
    val isLowBattery: StateFlow<Boolean> = _isLowBattery.asStateFlow()

    private val appContext: Context? = context?.applicationContext ?: context

    private var batteryReceiver: BroadcastReceiver? = null
    private var thermalHelper: Any? = null

    init {
        appContext?.let { ctx ->
            initBatteryMonitoring(ctx)
            initThermalMonitoring(ctx)
        }
    }

    private fun initBatteryMonitoring(ctx: Context) {
        try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val stickyIntent = ctx.registerReceiver(null, filter)
            if (stickyIntent != null) {
                updateBatteryFromIntent(stickyIntent)
            }

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent != null && intent.action == Intent.ACTION_BATTERY_CHANGED) {
                        updateBatteryFromIntent(intent)
                    }
                }
            }
            batteryReceiver = receiver
            ctx.registerReceiver(receiver, filter)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to register battery receiver: ${e.message}")
        }
    }

    private fun updateBatteryFromIntent(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else 100

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        updateBattery(pct, charging)
    }

    private fun initThermalMonitoring(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val powerManager = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
                if (powerManager != null) {
                    val initialStatus = powerManager.currentThermalStatus
                    _thermalStatus.value = ThermalStatus.fromInt(initialStatus)

                    val helper = ThermalListenerHelper(powerManager) { status ->
                        _thermalStatus.value = status
                    }
                    val executor = ContextCompat.getMainExecutor(ctx)
                    helper.register(executor)
                    thermalHelper = helper
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to register thermal status listener: ${e.message}")
            }
        }
    }

    fun updateBattery(level: Int, charging: Boolean) {
        _batteryLevel.value = level
        _isCharging.value = charging
        _isLowBattery.value = isBatteryCriticallyLow(level, charging)
    }

    fun updateThermalStatus(status: ThermalStatus) {
        _thermalStatus.value = status
    }

    fun unregister() {
        try {
            batteryReceiver?.let {
                appContext?.unregisterReceiver(it)
                batteryReceiver = null
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to unregister battery receiver: ${e.message}")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                (thermalHelper as? ThermalListenerHelper)?.unregister()
                thermalHelper = null
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to unregister thermal listener: ${e.message}")
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private class ThermalListenerHelper(
        private val powerManager: PowerManager,
        private val onStatusChanged: (ThermalStatus) -> Unit
    ) {
        private val listener = PowerManager.OnThermalStatusChangedListener { status ->
            onStatusChanged(ThermalStatus.fromInt(status))
        }

        fun register(executor: Executor) {
            powerManager.addThermalStatusListener(executor, listener)
        }

        fun unregister() {
            powerManager.removeThermalStatusListener(listener)
        }
    }

    companion object {
        private const val TAG = "DeviceMonitor"

        /**
         * Returns true if battery level is 15% or lower and device is not actively charging.
         */
        fun isBatteryCriticallyLow(level: Int, isCharging: Boolean): Boolean {
            return (level in 0..15) && !isCharging
        }

        @Volatile
        private var INSTANCE: DeviceMonitor? = null

        fun getInstance(context: Context): DeviceMonitor {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DeviceMonitor(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
