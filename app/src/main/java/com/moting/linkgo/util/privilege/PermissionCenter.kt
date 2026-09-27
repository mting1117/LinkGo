package com.moting.linkgo.util.privilege

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.moting.linkgo.LinkGoApp
import com.moting.linkgo.data.SettingsCache
import com.moting.linkgo.service.ShizukuManager
import com.moting.linkgo.util.AccessibilityUtils
import com.moting.linkgo.util.AppShell
import com.moting.linkgo.util.HyperIslandHelper
import com.moting.linkgo.util.KeepAliveHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 权限中心（Permission Center 的第三层）。
 *
 * 全库唯一的权限状态事实来源：
 * - 统一查询各权限当前状态（[refresh] / [statusOf] / [states]）；
 * - 统一授予与收回（[grant] / [revoke]），内部按权限类型自动选择：
 *   提权 Shell（Shizuku→Root）→ 系统设置页 → 运行时请求 → ADB 提示；
 * - 全局特权总开关（[setGlobalEnabled]），一键停用所有后台特权；
 * - 状态以 [StateFlow] 暴露，UI 一处订阅，消灭各处 remember 里重复计算。
 *
 * ⚠️ 注意：此对象只做「调度与状态」，不持有任何 su/Shizuku 原生实现——
 * 底层执行统一走 [PrivilegeEngine]，保证权限动作全链路可控可审计。
 */
object PermissionCenter {
    private const val TAG = "PermissionCenter"

    /** 权限状态 */
    enum class Status { UNAVAILABLE, GRANTED, ANR_NEEDED, ADB_NEEDED, REVOKED }

    /** 授予/收回结果 */
    sealed class Result {
        data object Granted : Result()
        data object Failed : Result()
        data object NeedsSystemSettings : Result()     // 已跳转系统页，等待用户操作
        data object NeedsAdb : Result()                // 需要 ADB 或更高授权
        data object NeedsRuntimeRequest : Result()     // 已发起运行时请求（异步结果另行刷新）
        data object Revoked : Result()
    }

    private val _states = MutableStateFlow<Map<PermissionItem, Status>>(emptyMap())
    val states: StateFlow<Map<PermissionItem, Status>> = _states.asStateFlow()

    private val _privilegeChannel = MutableStateFlow(PrivilegeEngine.Channel.NONE)
    val privilegeChannel: StateFlow<PrivilegeEngine.Channel> = _privilegeChannel.asStateFlow()

    // 内部协程作用域：refresh 涉及 su 探测等阻塞操作，必须脱离主线程
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 当前各权限状态（调用方拿到后可直接渲染） */
    fun statusOf(context: Context, item: PermissionItem): Status = when (item) {
        PermissionItem.SHIZUKU ->
            if (ShizukuManager.isGranted() || AppShell.isShizukuAvailable) Status.GRANTED
            else Status.UNAVAILABLE

        PermissionItem.ROOT ->
            if (PrivilegeEngine.canExecSu()) Status.GRANTED
            else Status.UNAVAILABLE

        PermissionItem.OVERLAY ->
            if (Settings.canDrawOverlays(context)) Status.GRANTED else Status.ANR_NEEDED

        PermissionItem.POST_NOTIFICATIONS ->
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED
            ) Status.GRANTED else Status.ANR_NEEDED

        PermissionItem.WRITE_SECURE_SETTINGS ->
            if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED
            ) Status.GRANTED
            else if (PrivilegeEngine.hasAnyPrivilege()) Status.ANR_NEEDED
            else Status.ADB_NEEDED

        PermissionItem.BATTERY_WHITELIST ->
            if (KeepAliveHelper.isBatteryOptimizationsIgnored(context)) Status.GRANTED
            else Status.ANR_NEEDED

        PermissionItem.ACCESSIBILITY ->
            // 系统设置显示已启用仅代表登记存在；实例假死时必须报异常，否则用户会看到「已就绪」却取不到词
            if (!AccessibilityUtils.isServiceEnabled(context)) Status.ANR_NEEDED
            else if (AccessibilityUtils.isActuallyAlive()) Status.GRANTED
            else Status.ANR_NEEDED

        PermissionItem.BACKGROUND_POPUP ->
            if (KeepAliveHelper.isBackgroundStartActivityAllowed(context)) Status.GRANTED
            else Status.ANR_NEEDED

        PermissionItem.AUTO_START ->
            if (KeepAliveHelper.isAutoStartAllowed(context)) Status.GRANTED
            else Status.ANR_NEEDED

        PermissionItem.BOOT_COMPLETED -> Status.GRANTED // 静态广播，清单声明即授予

        PermissionItem.QUERY_ALL_PACKAGES -> Status.GRANTED // 清单声明

        PermissionItem.LSPOSED_HOOK ->
            if (LinkGoApp.xposedService != null) Status.GRANTED
            else Status.UNAVAILABLE

        PermissionItem.CLIPBOARD_CLEAR ->
            if (PrivilegeEngine.hasAnyPrivilege()) Status.GRANTED else Status.UNAVAILABLE

        PermissionItem.SUPER_ISLAND_BYPASS ->
            if (ShizukuManager.isGranted() || AppShell.isShizukuAvailable) Status.GRANTED
            else Status.UNAVAILABLE
    }

    /**
     * 刷新全量状态（UI 进入页面 / 任何授予收回后调用 / 低频兜底轮询）。
     * 内部在 IO 线程执行（su 探测可能阻塞最长 3 秒），完成后通过 [states] 发布。
     * 并发保护：上一轮刷新未结束时跳过，避免低频轮询堆积。
     */
    fun refresh(context: Context) {
        if (!refreshInFlight.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        refreshScope.launch {
            try {
                val ch = PrivilegeEngine.currentChannel()
                val computed = PermissionItem.entries.associateWith { statusOf(appContext, it) }
                _privilegeChannel.value = ch
                _states.value = computed
            } finally {
                refreshInFlight.set(false)
            }
        }
    }

    /** 授予权限：按权限类型自动选择通道 */
    suspend fun grant(context: Context, item: PermissionItem): Result = when (item) {
        PermissionItem.SHIZUKU -> {
            // Shizuku 授权走标准 requestPermission，结果由 ShizukuManager 回调驱动刷新
            ShizukuManager.requestShizukuPermission()
            Result.NeedsRuntimeRequest
        }

        PermissionItem.ROOT -> {
            // Root 由系统授权（su 首次弹窗），此处仅刷新探测缓存
            PrivilegeEngine.invalidateSuProbe()
            if (PrivilegeEngine.canExecSu()) Result.Granted else Result.Failed
        }

        PermissionItem.OVERLAY -> {
            val ok = PrivilegeEngine.exec(
                "appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow"
            )
            if (ok) Result.Granted
            else {
                openOverlaySettings(context)
                Result.NeedsSystemSettings
            }
        }

        PermissionItem.POST_NOTIFICATIONS -> {
            val ok = PrivilegeEngine.exec(
                "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS"
            )
            if (ok) Result.Granted
            else {
                openNotificationSettings(context)
                Result.NeedsSystemSettings
            }
        }

        PermissionItem.WRITE_SECURE_SETTINGS -> {
            val ok = PrivilegeEngine.exec(
                "pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
            )
            if (ok) Result.Granted else Result.NeedsAdb
        }

        PermissionItem.BATTERY_WHITELIST -> {
            val ok = PrivilegeEngine.exec("dumpsys deviceidle whitelist +${context.packageName}")
            if (ok) Result.Granted
            else {
                KeepAliveHelper.requestIgnoreBatteryOptimizations(context)
                Result.NeedsSystemSettings
            }
        }

        PermissionItem.ACCESSIBILITY -> {
            // 用户手动授权：force=true 绕过能力开关（其余自动路径已被 autoHealService 内部守卫拦下）
            val healed = AccessibilityUtils.autoHealService(context, force = true)
            if (healed) Result.Granted
            else {
                openAccessibilitySettings(context)
                Result.NeedsSystemSettings
            }
        }

        PermissionItem.BACKGROUND_POPUP -> {
            val ok = PrivilegeEngine.exec("appops set ${context.packageName} 10021 allow") ||
                     PrivilegeEngine.exec("appops set ${context.packageName} BACKGROUND_START_ACTIVITY allow")
            if (ok) Result.Granted
            else {
                KeepAliveHelper.openBackgroundStartActivitySetting(context)
                Result.NeedsSystemSettings
            }
        }

        PermissionItem.AUTO_START -> {
            val ok = PrivilegeEngine.exec("appops set ${context.packageName} 10008 allow") ||
                     PrivilegeEngine.exec("appops set ${context.packageName} AUTO_START allow")
            if (ok) Result.Granted
            else {
                KeepAliveHelper.openAutoStartSetting(context)
                Result.NeedsSystemSettings
            }
        }

        PermissionItem.BOOT_COMPLETED -> Result.Granted // 清单级，无需授予
        PermissionItem.QUERY_ALL_PACKAGES -> Result.Granted

        PermissionItem.LSPOSED_HOOK -> {
            // 只能在 LSPosed 框架中激活模块，此处引导
            Result.NeedsSystemSettings
        }

        PermissionItem.CLIPBOARD_CLEAR -> {
            // 依赖 Shizuku/Root 通道存在；存在即视为可用
            if (PrivilegeEngine.hasAnyPrivilege()) Result.Granted else Result.Failed
        }

        PermissionItem.SUPER_ISLAND_BYPASS -> {
            if (PrivilegeEngine.currentChannel() != PrivilegeEngine.Channel.NONE) Result.Granted
            else Result.Failed
        }
    }

    /** 收回权限：反向撤销（谨慎用户核心诉求） */
    suspend fun revoke(context: Context, item: PermissionItem): Result = when (item) {
        PermissionItem.OVERLAY -> {
            val ok = PrivilegeEngine.exec(
                "appops set ${context.packageName} SYSTEM_ALERT_WINDOW deny"
            )
            if (ok) Result.Revoked else Result.Failed
        }

        PermissionItem.POST_NOTIFICATIONS -> {
            val ok = PrivilegeEngine.exec(
                "pm revoke ${context.packageName} android.permission.POST_NOTIFICATIONS"
            )
            if (ok) Result.Revoked else Result.Failed
        }

        PermissionItem.WRITE_SECURE_SETTINGS -> {
            val ok = PrivilegeEngine.exec(
                "pm revoke ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
            )
            if (ok) Result.Revoked else Result.Failed
        }

        PermissionItem.ACCESSIBILITY -> {
            // 移除无障碍服务（等待系统生效）
            val ok = removeAccessibilityService(context)
            if (ok) Result.Revoked else Result.Failed
        }

        PermissionItem.BACKGROUND_POPUP -> {
            val ok = PrivilegeEngine.exec("appops set ${context.packageName} 10021 deny")
            if (ok) Result.Revoked else Result.Failed
        }

        PermissionItem.AUTO_START -> {
            val ok = PrivilegeEngine.exec("appops set ${context.packageName} 10008 deny")
            if (ok) Result.Revoked else Result.Failed
        }

        PermissionItem.SHIZUKU -> {
            // Shizuku 授权无法由应用自行撤销，引导到系统/Shizuku 应用
            openShizukuSettings(context)
            Result.NeedsSystemSettings
        }

        PermissionItem.ROOT -> Result.Failed // Root 由系统级管理，无法应用内撤销

        else -> Result.Failed
    }

    /** 全局特权总开关：关闭后所有 PrivilegeEngine 命令被拒绝执行 */
    fun setGlobalEnabled(enabled: Boolean) {
        PrivilegeEngine.globalEnabled = enabled
        SettingsCache.globalPrivilegeEnabled = enabled
        Log.i(TAG, "全局特权总开关: $enabled")
    }

    // ── 系统页跳转辅助 ────────────────────────────────────────

    fun openOverlaySettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun openNotificationSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    fun openAccessibilitySettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun openShizukuSettings(context: Context) {
        runCatching {
            context.startActivity(
                context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** 从安全设置中移除无障碍服务 */
    private fun removeAccessibilityService(context: Context): Boolean {
        return try {
            val resolver = context.contentResolver
            val current = Settings.Secure.getString(
                resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            val remaining = current.split(":")
                .filter { it.isNotBlank() && !it.equals(AccessibilityUtils.SERVICE_NAME, ignoreCase = true) }
                .joinToString(":")
            Settings.Secure.putString(
                resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, remaining
            )
            if (remaining.isBlank()) {
                Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "移除无障碍服务失败: ${e.message}")
            false
        }
    }
}
