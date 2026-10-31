package com.clearview.player

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

/**
 * Keeps the phone from overheating during long AI playback.
 *
 * Reads the battery temperature and Android's own thermal status (the public API; the SoC junction sensors that
 * HardwarePropertiesManager exposes are not available to normal apps) and sets [AiSettings.thermalMode]:
 *   0 normal            - AI runs as chosen
 *   1 warm (>= 40 C)    - AI forced to the Fast size
 *   2 hot  (>= 42 C)    - AI paused, plain filters only (cheap, on the GPU) until the phone cools
 * Stepping back down only happens once the battery is below 38 C and the system reports no thermal pressure.
 */
object ThermalGuard {
    @Volatile var tempC = 0f
    @Volatile var statusName = "n/a"
    private var mode = 0

    fun update(ctx: Context) {
        val bat = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val t = (bat?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        tempC = t

        var status = 0
        if (Build.VERSION.SDK_INT >= 29) {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            status = pm.currentThermalStatus
        }
        statusName = when (status) {
            0 -> "none"
            1 -> "light"
            2 -> "moderate"
            3 -> "severe"
            else -> "critical"
        }

        val target = when {
            t >= 42f || status >= 3 -> 2
            t >= 40f || status >= 2 -> 1
            else -> 0
        }
        val cool = t < 38f && status < 2
        mode = if (target > mode) target else if (cool) target else mode
        AiSettings.thermalMode = mode
    }

    fun summary(): String =
        String.format(java.util.Locale.US, "Battery %.1f C, thermal %s, AI %s", tempC, statusName,
            when (AiSettings.thermalMode) { 0 -> "full"; 1 -> "reduced (warm)"; else -> "paused (hot)" })
}
