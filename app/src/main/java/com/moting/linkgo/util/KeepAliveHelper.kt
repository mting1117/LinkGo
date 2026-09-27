package com.moting.linkgo.util

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * 后台保活与系统优化配置辅助工具
 */
object KeepAliveHelper {
    private const val TAG = "KeepAliveHelper"

    /**
     * 检查当前是否已忽略电池优化（即已加入系统电池白名单）
     */
    fun isBatteryOptimizationsIgnored(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        } catch (e: Exception) {
            true
        }
    }

    /**
     * 标准系统弹窗：请求忽略电池优化
     */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // 降级跳转通用电池优化设置列表
            try {
                val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
            } catch (e2: Exception) {
                Log.w(TAG, "Failed to open battery optimization settings: ${e2.message}")
            }
        }
    }

    /**
     * 通过 Root 或 Shizuku 静默将本应用加入系统 Doze 电池白名单。
     * 统一走 [com.moting.linkgo.util.privilege.PrivilegeEngine] 提权通道。
     */
    suspend fun whitelistBatteryByPrivilege(context: Context, rootAvailable: Boolean, shizukuGranted: Boolean): Boolean {
        return whitelistBattery(context)
    }

    /**
     * 通过当前可用提权通道将本应用加入 Doze 电池白名单（权限中心统一调度入口）。
     * 受能力开关「电池白名单静默写入」约束：关闭后不再静默写入，返回 false。
     */
    suspend fun whitelistBattery(context: Context): Boolean {
        if (!com.moting.linkgo.util.privilege.CapabilityCenter.isEnabled(
                com.moting.linkgo.util.privilege.CapabilityCenter.Capability.BATTERY_SILENT_WHITELIST
            )
        ) {
            Log.i(TAG, "[BATTERY-WHITELIST] 能力「电池白名单静默写入」已关闭，跳过静默写入")
            return false
        }
        val pkg = context.packageName
        return com.moting.linkgo.util.privilege.PrivilegeEngine.exec("dumpsys deviceidle whitelist +$pkg")
    }

    /**
     * 跳转至主流厂商的后台管理 / 自启动管理页面
     */
    fun openAutoStartSetting(context: Context) {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val intentList = when {
            // 小米 / Redmi / HyperOS / MIUI
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> listOf(
                Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
                Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.powercenter.PowerSettings")),
                Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT)
            )
            // 华为 / 荣耀 (EMUI / HarmonyOS / MagicOS)
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> listOf(
                Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
                Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.bootstart.BootStartActivity")),
                Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"))
            )
            // OPPO / 一加 / Realme (ColorOS / OxygenOS / RealmeUI)
            manufacturer.contains("oppo") || manufacturer.contains("oneplus") || manufacturer.contains("realme") -> listOf(
                Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
                Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")),
                Intent().setComponent(ComponentName("com.oplus.battery", "com.oplus.battery.AppListActivity"))
            )
            // vivo / iQOO (OriginOS / FuntouchOS)
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> listOf(
                Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")),
                Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity")),
                Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.MainGuideActivity"))
            )
            // 三星 (OneUI)
            manufacturer.contains("samsung") -> listOf(
                Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")),
                Intent().setComponent(ComponentName("com.samsung.android.sm", "com.samsung.android.sm.battery.ui.BatteryActivity"))
            )
            else -> emptyList()
        }

        for (targetIntent in intentList) {
            try {
                targetIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(targetIntent)
                return
            } catch (_: Exception) {
            }
        }

        // 通用兜底：跳转本应用的应用详情设置页（用户可在其中设置省电策略和权限）
        try {
            val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(appDetails)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open app details: ${e.message}")
        }
    }

    /**
     * 检查当前是否允许“后台弹出界面”（如 HyperOS/MIUI 的 AppOp 10021）
     */
    fun isBackgroundStartActivityAllowed(context: Context): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        if (manufacturer.contains("xiaomi") || manufacturer.contains("redmi")) {
            try {
                val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? android.app.AppOpsManager
                val method = appOps?.javaClass?.getMethod(
                    "checkOpNoThrow",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java
                )
                // 10021: OP_BACKGROUND_START_ACTIVITY
                val result = method?.invoke(appOps, 10021, android.os.Process.myUid(), context.packageName) as? Int
                return result == android.app.AppOpsManager.MODE_ALLOWED
            } catch (_: Exception) {}
        }
        // 原生及通用设备：后台拉起受悬浮窗或系统前台策略控制，已开启悬浮窗亦视为具备
        return Settings.canDrawOverlays(context)
    }

    /**
     * 跳转主流厂商的“后台弹出界面”/应用权限设置页
     */
    fun openBackgroundStartActivitySetting(context: Context) {
        val manufacturer = Build.MANUFACTURER.lowercase()
        if (manufacturer.contains("xiaomi") || manufacturer.contains("redmi")) {
            try {
                val intent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                    setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                    putExtra("extra_pkgname", context.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return
            } catch (_: Exception) {}
        }
        // 兜底跳转应用详情页
        try {
            val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(appDetails)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open app details: ${e.message}")
        }
    }

    /**
     * 检查系统自启动权限是否已放行
     */
    fun isAutoStartAllowed(context: Context): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        if (manufacturer.contains("xiaomi") || manufacturer.contains("redmi")) {
            try {
                val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? android.app.AppOpsManager
                val method = appOps?.javaClass?.getMethod(
                    "checkOpNoThrow",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java
                )
                // 10008: OP_AUTO_START
                val result = method?.invoke(appOps, 10008, android.os.Process.myUid(), context.packageName) as? Int
                return result == android.app.AppOpsManager.MODE_ALLOWED
            } catch (_: Exception) {}
        }
        // 非小米厂商无标准公开 API 探测，综合电池优化白名单判断
        return isBatteryOptimizationsIgnored(context)
    }
}
