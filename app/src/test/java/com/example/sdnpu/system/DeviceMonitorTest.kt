package com.example.sdnpu.system

import org.junit.Assert.*
import org.junit.Test

class DeviceMonitorTest {

    @Test
    fun testThermalStatusSeverity() {
        assertTrue(ThermalStatus.CRITICAL.isThrottlingSevere())
        assertTrue(ThermalStatus.SEVERE.isThrottlingSevere())
        assertTrue(ThermalStatus.EMERGENCY.isThrottlingSevere())
        assertTrue(ThermalStatus.SHUTDOWN.isThrottlingSevere())

        assertFalse(ThermalStatus.MODERATE.isThrottlingSevere())
        assertFalse(ThermalStatus.LIGHT.isThrottlingSevere())
        assertFalse(ThermalStatus.NONE.isThrottlingSevere())
    }

    @Test
    fun testLowBatteryThreshold() {
        assertTrue(DeviceMonitor.isBatteryCriticallyLow(level = 10, isCharging = false))
        assertTrue(DeviceMonitor.isBatteryCriticallyLow(level = 15, isCharging = false))
        assertTrue(DeviceMonitor.isBatteryCriticallyLow(level = 0, isCharging = false))

        assertFalse(DeviceMonitor.isBatteryCriticallyLow(level = 10, isCharging = true))
        assertFalse(DeviceMonitor.isBatteryCriticallyLow(level = 16, isCharging = false))
        assertFalse(DeviceMonitor.isBatteryCriticallyLow(level = 50, isCharging = false))
        assertFalse(DeviceMonitor.isBatteryCriticallyLow(level = -1, isCharging = false))
    }

    @Test
    fun testDeviceMonitorDefaultState() {
        val monitor = DeviceMonitor()
        assertEquals(ThermalStatus.NONE, monitor.thermalStatus.value)
        assertEquals(100, monitor.batteryLevel.value)
        assertTrue(monitor.isCharging.value)
        assertFalse(monitor.isLowBattery.value)
    }

    @Test
    fun testDeviceMonitorStateUpdates() {
        val monitor = DeviceMonitor()
        monitor.updateThermalStatus(ThermalStatus.SEVERE)
        assertEquals(ThermalStatus.SEVERE, monitor.thermalStatus.value)
        assertTrue(monitor.thermalStatus.value.isThrottlingSevere())

        monitor.updateBattery(level = 12, charging = false)
        assertEquals(12, monitor.batteryLevel.value)
        assertFalse(monitor.isCharging.value)
        assertTrue(monitor.isLowBattery.value)

        monitor.updateBattery(level = 12, charging = true)
        assertEquals(12, monitor.batteryLevel.value)
        assertTrue(monitor.isCharging.value)
        assertFalse(monitor.isLowBattery.value)
    }

    @Test
    fun testThermalStatusFromPowerManager() {
        assertEquals(ThermalStatus.NONE, ThermalStatus.fromInt(0))
        assertEquals(ThermalStatus.LIGHT, ThermalStatus.fromInt(1))
        assertEquals(ThermalStatus.MODERATE, ThermalStatus.fromInt(2))
        assertEquals(ThermalStatus.SEVERE, ThermalStatus.fromInt(3))
        assertEquals(ThermalStatus.CRITICAL, ThermalStatus.fromInt(4))
        assertEquals(ThermalStatus.EMERGENCY, ThermalStatus.fromInt(5))
        assertEquals(ThermalStatus.SHUTDOWN, ThermalStatus.fromInt(6))
        assertEquals(ThermalStatus.NONE, ThermalStatus.fromInt(99))
    }

    @Test
    fun testGenerationNotificationManagerConstantsAndFallback() {
        assertEquals("sd_npu_generation", GenerationNotificationManager.CHANNEL_ID)
        assertEquals("Image Generation", GenerationNotificationManager.CHANNEL_NAME)
        assertEquals(1001, GenerationNotificationManager.NOTIFICATION_ID)

        val notificationManager = GenerationNotificationManager(null)
        assertFalse(notificationManager.hasNotificationPermission())
        notificationManager.notifyGenerationProgress(5, 20, "cyberpunk cat")
        notificationManager.notifyGenerationCompleted("/tmp/fake_image.png", "cyberpunk cat")
        notificationManager.cancel()
    }
}
