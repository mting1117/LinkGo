package com.moting.linkgo.clipboard

import android.content.Context
import com.moting.linkgo.service.ClipboardMonitorService
import com.moting.linkgo.service.ShizukuManager

/**
 * 剪贴板后台监听的统一启停控制器。
 *
 * 根据「总开关 + 后端」决定：
 *  - 关闭 → 停服务 + 解绑 Shizuku + 释放 handler
 *  - Shizuku/Root/不处理 → 启动前台服务（服务内按 backend 参数化）
 *  - LSPosed → 不启动前台服务，交给 ClipboardTextReceiver
 */
object ClipboardMonitorController {

    @Volatile
    private var lastAppliedEnabled: Boolean? = null
    @Volatile
    private var lastAppliedBackend: String? = null

    @Synchronized
    fun apply(context: Context, enabled: Boolean, backend: String, force: Boolean = false) {
        val appContext = context.applicationContext

        // 状态去重：仅当目标配置与当前一致、且底层真实运行状态健康时，才安全跳过并发调用
        val isShizuku = backend == ClipboardBackend.SHIZUKU_HIDDEN_API || backend == ClipboardBackend.SHIZUKU_LOGS
        val isShizukuHealthy = !isShizuku || ShizukuManager.getState() == ShizukuManager.State.BOUND

        if (!force && lastAppliedEnabled == enabled && lastAppliedBackend == backend && isShizukuHealthy) {
            if (enabled && ClipboardBackend.needsForegroundService(backend) && ClipboardMonitorService.isRunning) {
                return
            } else if (!enabled || !ClipboardBackend.needsForegroundService(backend)) {
                return
            }
        }
        lastAppliedEnabled = enabled
        lastAppliedBackend = backend

        if (!enabled) {
            stop(appContext)
            return
        }
        if (ClipboardBackend.needsForegroundService(backend)) {
            // onStartCommand 会先停旧的监听再按新 backend 启动
            ClipboardMonitorService.start(appContext, backend)
        } else {
            // LSPosed：无需前台服务，仅需广播接收器（已在 Manifest 常驻）
            stop(appContext)
        }
    }

    @Synchronized
    fun stop(context: Context) {
        val appContext = context.applicationContext
        lastAppliedEnabled = false
        lastAppliedBackend = null
        ClipboardMonitorService.stop(appContext)
        ShizukuManager.unbindAndStop()
        ClipboardHandler.release()
    }

    /**
     * 当全局特权通道变更时，若处于系统监听模式且正在运行，平滑热重启切换至最新通道
     */
    @Synchronized
    fun onPrivilegeChannelChanged(context: Context) {
        val appContext = context.applicationContext
        val isEnabled = com.moting.linkgo.data.SettingsCache.clipboardMonitorEnabled
        val currentBackend = com.moting.linkgo.data.SettingsCache.clipboardMonitorBackend
        if (isEnabled && ClipboardBackend.topLevel(currentBackend) == "system" && ClipboardMonitorService.isRunning) {
            val way = com.moting.linkgo.data.SettingsCache.lastSystemWay
            val newBackend = ClipboardBackend.deriveSystemBackend(
                com.moting.linkgo.util.privilege.PrivilegeEngine.currentChannel(),
                way
            )
            if (newBackend != currentBackend) {
                com.moting.linkgo.data.SettingsCache.clipboardMonitorBackend = newBackend
                apply(appContext, true, newBackend, force = true)
            }
        }
    }
}