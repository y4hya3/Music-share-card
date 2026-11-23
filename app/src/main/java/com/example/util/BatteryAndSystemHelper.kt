package com.example.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Policy-compliant Battery Optimization, Power Saver telemetry, and OEM background settings helper.
 * Uses standard Settings intents (never restricted REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).
 */
object BatteryAndSystemHelper {

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return try {
            val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        } catch (_: Exception) {
            false
        }
    }

    fun isPowerSaveMode(context: Context): Boolean {
        return try {
            val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isPowerSaveMode == true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Reads current battery percentage (1..100) and charging status from sticky ACTION_BATTERY_CHANGED.
     */
    fun readBatteryState(context: Context): Pair<Int, Boolean> {
        return try {
            val sticky = context.applicationContext.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.coerceAtLeast(1) ?: 100
            val pct = if (level >= 0) ((level * 100f) / scale).toInt().coerceIn(0, 100) else 100
            val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            pct to isCharging
        } catch (_: Exception) {
            100 to false
        }
    }

    fun shouldPauseCanvasForPowerSave(context: Context, pauseWhenSavingEnabled: Boolean): Boolean {
        if (!pauseWhenSavingEnabled) return false
        if (isPowerSaveMode(context)) return true
        val (pct, charging) = readBatteryState(context)
        return pct in 1..15 && !charging
    }

    fun openBatteryOptimizationSettings(context: Context): Boolean {
        val candidates = listOf(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            appDetailsIntent(context)
        )
        return launchFirstAvailable(context, candidates)
    }

    fun openOemBackgroundSettings(context: Context): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val intents = buildList {
            when {
                manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> {
                    add(
                        Intent().setComponent(
                            ComponentName(
                                "com.miui.securitycenter",
                                "com.miui.permcenter.autostart.AutoStartManagementActivity"
                            )
                        )
                    )
                    add(
                        Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT)
                    )
                    add(
                        Intent().setComponent(
                            ComponentName(
                                "com.miui.powerkeeper",
                                "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                            )
                        ).putExtra("package_name", context.packageName)
                    )
                }
                manufacturer.contains("samsung") -> {
                    add(
                        Intent().setComponent(
                            ComponentName(
                                "com.samsung.android.lool",
                                "com.samsung.android.sm.battery.ui.BatteryActivity"
                            )
                        )
                    )
                    add(
                        Intent().setComponent(
                            ComponentName(
                                "com.samsung.android.sm",
                                "com.samsung.android.sm.battery.ui.BatteryActivity"
                            )
                        )
                    )
                }
                manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus") -> {
                    add(
                        Intent().setComponent(
                            ComponentName(
                                "com.coloros.safecenter",
                                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                            )
                        )
                    )
                    add(
                        Intent().setComponent(
                            ComponentName(
                                "com.oplus.battery",
                                "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"
                            )
                        )
                    )
                }
                manufacturer.contains("vivo") -> {
                    add(
                        Intent().setComponent(
                            ComponentName(
                                "com.iqoo.secure",
                                "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"
                            )
                        )
                    )
                }
            }
            add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            add(appDetailsIntent(context))
        }
        return launchFirstAvailable(context, intents)
    }

    private fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }

    private fun launchFirstAvailable(context: Context, intents: List<Intent>): Boolean {
        for (raw in intents) {
            try {
                val intent = Intent(raw).apply {
                    if (context !is android.app.Activity) {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    return true
                }
            } catch (_: Exception) {
            }
        }
        return false
    }
}
