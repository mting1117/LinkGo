package com.moting.linkgo.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.moting.linkgo.clipboard.ClipboardMonitorController
import com.moting.linkgo.data.SettingsCache
import com.moting.linkgo.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 开机自启动与应用更新静态广播接收器。
 * 
 * 作用：
 * 1. 开机（BOOT_COMPLETED / LOCKED_BOOT_COMPLETED）时由系统在后台自动拉起 LinkGo 进程，完成预热（Pre-warm）；
 * 2. 避免用户开机后首次复制时因进程未启动而发生 5~7 秒的冷启动延迟；
 * 3. 预热核心配置至 SettingsCache，并自动恢复剪贴板后台监听服务。
 */
class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "LinkGo_Boot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "[BOOT-RCV] 收到系统预热广播: action=$action, pid=${android.os.Process.myPid()}")

        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val repo = SettingsRepository(appContext)
                repo.preloadAll()

                val isEnabled = SettingsCache.clipboardMonitorEnabled
                val backend = SettingsCache.clipboardMonitorBackend

                Log.i(TAG, "[BOOT-RESTORE] 预热完成: monitorEnabled=$isEnabled, backend=$backend")
                if (isEnabled) {
                    ClipboardMonitorController.apply(appContext, true, backend)
                }

                // 开机自愈无障碍服务（若具备特权则直接点亮常驻，防系统杀后台）
                // 受能力开关「自愈·开机自启」约束：关闭后开机不再自动点亮
                if (com.moting.linkgo.util.privilege.CapabilityCenter.isEnabled(
                        com.moting.linkgo.util.privilege.CapabilityCenter.Capability.A11Y_HEAL_BOOT
                    )
                ) {
                    com.moting.linkgo.util.AccessibilityUtils.autoHealService(appContext)
                }

                // 恢复自动备份调度
                com.moting.linkgo.receiver.BackupAlarmReceiver.schedule(appContext)
            } catch (e: Exception) {
                Log.w(TAG, "[BOOT-ERROR] 开机自启恢复异常: ${e.message}", e)
            } finally {
                pendingResult.finish()
                Log.d(TAG, "[BOOT-FINISH] pendingResult.finish() 完成")
            }
        }
    }
}
