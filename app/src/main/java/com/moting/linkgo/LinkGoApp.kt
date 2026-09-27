package com.moting.linkgo

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.moting.linkgo.data.PackageRepository
import com.moting.linkgo.service.ShizukuManager
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * 自定义 Application 类，负责应用启动时的底层“解禁”逻辑。
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
class LinkGoApp : Application(), XposedServiceHelper.OnServiceListener {

    private val appScope = MainScope()

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        // 在 attachBaseContext 中最早的时机尝试解除 Hidden API 限制
        bypassHiddenApiRestrictions()
    }

    override fun onCreate() {
        super.onCreate()
        // libxposed 应用侧服务绑定（复刻 HyperCopy App.kt）
        XposedServiceHelper.registerListener(this)
        Log.d("LinkGoApp", "LinkGo 应用已启动，底层解禁逻辑已执行。")
        
        // 注册应用安装卸载监听
        registerPackageReceiver()
        
        // 极速同步快照：0ms 立即就绪 SettingsCache
        com.moting.linkgo.data.SettingsRepository.fastSyncCache(this)

        // 【临时诊断探针，验证结论后立即删除】向应用专属外部目录写探测文件，
        // 供 system_server 侧验证「Hook 能否直接读取应用侧落盘的配置」
        runCatching {
            val dir = getExternalFilesDir(null) ?: return@runCatching
            val probe = java.io.File(dir, "linkgo_probe.txt")
            probe.writeText("probe-from-app pid=${android.os.Process.myPid()} at=${System.currentTimeMillis()}")
            Log.i("LinkGoApp", "[DIAG-EXT-WRITE] 探测文件已写入: ${probe.absolutePath} len=${probe.length()}")
        }.onFailure { Log.w("LinkGoApp", "[DIAG-EXT-WRITE] 探测文件写入失败: ${it.message}") }

        // 应用内链接捕获：启动时同步捕获配置到 system_server 的 HookEntry
        // （主进程可能被广播冷拉起，需保证 HookEntry 侧的捕获模式与豁免域名与配置一致）
        com.moting.linkgo.applink.LinkIntentReceiver.syncConfigToHook(
            this,
            com.moting.linkgo.data.SettingsCache.appLinkCaptureMode
        )

        // 全局特权总开关同步进 PrivilegeEngine（权限中心统一调度前提）
        com.moting.linkgo.util.privilege.PrivilegeEngine.globalEnabled =
            com.moting.linkgo.data.SettingsCache.globalPrivilegeEnabled
        // 注入本应用包名（用于能力拦截识别"静默授权自身权限"类命令）
        com.moting.linkgo.util.privilege.PrivilegeEngine.appPackageName = packageName
        
        // 初始化并清理通知渠道（自动清除旧渠道并就绪新渠道）
        com.moting.linkgo.util.NotificationHelper.ensureChannels(this)
        
        // 超级岛焦点鉴权与状态栏 Provider 静默预热（解决新安装后首次复制由于缺少系统鉴权缓存导致通知被过滤的问题）
        com.moting.linkgo.util.HyperIslandHelper.prewarmIslandAuth(this)
        
        // 背景预加载应用列表
        appScope.launch {
            kotlinx.coroutines.delay(1000)
            PackageRepository.loadAllApps(this@LinkGoApp)
        }

        // 检查并清理过期的临时保存图片（防漏删对账保底）
        appScope.launch(Dispatchers.IO) {
            com.moting.linkgo.image.TemporaryImageCleaner.checkAndCleanExpired(this@LinkGoApp)
        }

        // 初始化 Shizuku 管理器（后台剪贴板监听的基础，0ms 立即就绪参数与监听）
        try {
            ShizukuManager.init(this@LinkGoApp)
            Log.i("LinkGoApp", "ShizukuManager initialized synchronously")
        } catch (e: Exception) {
            Log.e("LinkGoApp", "Failed to initialize ShizukuManager: ${e.message}", e)
        }

        // 剪贴板监听自愈：应用启动或 Shizuku 就绪时自动恢复剪贴板后台监听服务
        val restoreClipboardMonitor = { force: Boolean ->
            appScope.launch {
                try {
                    val repo = com.moting.linkgo.data.SettingsRepository(this@LinkGoApp)
                    repo.preloadAll()
                    val isEnabled = com.moting.linkgo.data.SettingsCache.clipboardMonitorEnabled
                    val backend = com.moting.linkgo.data.SettingsCache.clipboardMonitorBackend
                    if (isEnabled) {
                        Log.i("LinkGoApp", "Auto-restoring clipboard monitor service: backend=$backend, force=$force")
                        com.moting.linkgo.clipboard.ClipboardMonitorController.apply(this@LinkGoApp, true, backend, force = force)
                    }
                } catch (e: Exception) {
                    Log.w("LinkGoApp", "Failed to auto-restore clipboard monitor: ${e.message}")
                }
            }
        }

        // 静默守护无障碍服务（受能力开关「自愈·应用内自愈」约束，完全独立协程，互不阻塞）
        val healAccessibilityService = {
            if (com.moting.linkgo.util.privilege.CapabilityCenter.isEnabled(
                    com.moting.linkgo.util.privilege.CapabilityCenter.Capability.A11Y_HEAL_APP
                )
            ) {
                appScope.launch {
                    try {
                        com.moting.linkgo.util.AccessibilityUtils.autoHealService(this@LinkGoApp)
                    } catch (e: Exception) {
                        Log.w("LinkGoApp", "Failed to auto-heal accessibility service: ${e.message}")
                    }
                }
            }
        }

        ShizukuManager.setOnBinderReadyListener {
            Log.i("LinkGoApp", "Shizuku Binder ready, triggering monitor auto-restore (forced)")
            // 通道状态变化 → 权限中心刷新
            com.moting.linkgo.util.privilege.PrivilegeEngine.onShizukuStateChanged()
            com.moting.linkgo.util.privilege.PermissionCenter.refresh(this@LinkGoApp)
            restoreClipboardMonitor(true)
            healAccessibilityService()
        }

        restoreClipboardMonitor(false)
        healAccessibilityService()

        // 恢复自动备份调度
        com.moting.linkgo.receiver.BackupAlarmReceiver.schedule(this@LinkGoApp)

        // 「变更后自动备份」监听：数据变化 → 60s 防抖 → 满足条件则静默备份
        appScope.launch {
            try {
                val backupConfig = com.moting.linkgo.data.backup.BackupConfigStore(this@LinkGoApp)
                com.moting.linkgo.data.SettingsRepository(this@LinkGoApp).dataChanges
                    .drop(1)
                    .debounce(60_000)
                    .collect {
                        if (backupConfig.changeAutoBackupEnabled &&
                            com.moting.linkgo.receiver.BackupConditions.allowed(this@LinkGoApp)
                        ) {
                            withContext(Dispatchers.IO) {
                                com.moting.linkgo.data.backup.BackupManager(this@LinkGoApp).backup()
                            }
                        }
                    }
            } catch (e: Exception) {
                Log.w("LinkGoApp", "变更自动备份监听异常: ${e.message}")
            }
        }
    }

    override fun onServiceBind(service: XposedService) {
        xposedService = service
        listeners.forEach { it(service) }
    }

    override fun onServiceDied(service: XposedService) {
        if (xposedService == service) {
            xposedService = null
            listeners.forEach { it(null) }
        }
    }

    companion object {
        @Volatile
        var xposedService: XposedService? = null
            private set

        private val listeners = CopyOnWriteArraySet<(XposedService?) -> Unit>()

        fun addServiceListener(listener: (XposedService?) -> Unit) {
            listeners.add(listener)
            listener(xposedService)
        }

        fun removeServiceListener(listener: (XposedService?) -> Unit) {
            listeners.remove(listener)
        }
    }

    private fun registerPackageReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                Log.d("LinkGoApp", "监听到应用变更: ${intent?.action}")
                appScope.launch {
                    PackageRepository.invalidateCache()
                    PackageRepository.loadAllApps(this@LinkGoApp, force = true)
                }
            }
        }
        registerReceiver(receiver, filter)
    }

    /**
     * 实现 Hidden API 解禁 (使用 HiddenApiBypass 库)
     * 绕过 Android 9+ 对非 SDK 接口（@hide API）的反射拦截。
     */
    private fun bypassHiddenApiRestrictions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                // 全局豁免所有非 SDK 接口限制（对齐 signaldock App.kt）
                HiddenApiBypass.addHiddenApiExemptions("")
                Log.i("LinkGoApp", "成功通过 HiddenApiBypass 全局解除所有 Hidden API 访问限制。")
            } catch (e: Exception) {
                Log.e("LinkGoApp", "HiddenApiBypass 解除限制失败: ${e.message}", e)
            }
        }
    }
}
