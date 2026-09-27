package com.moting.linkgo.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.IConnectivityManager
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * 严格对齐 signaldock 的 AppShell 单例代理
 * 负责通过 ShizukuBinderWrapper 与系统 ConnectivityService 通信控制 XMSF 防火墙
 */
@SuppressLint("BlockedPrivateApi")
object AppShell {
    private const val TAG = "AppShell"

    /** 检查 Shizuku 是否已授权可用 */
    val isShizukuAvailable: Boolean
        get() = runCatching {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    /**
     * 懒加载系统 ConnectivityService 的 Binder 代理
     */
    @Volatile
    private var cachedConnectivityManager: IConnectivityManager? = null

    /**
     * 获取系统 ConnectivityService 的 Binder 代理。
     * 若 Binder 死亡或不存在，则自动自愈重新构建。
     */
    @Synchronized
    private fun getConnectivityManager(): IConnectivityManager? {
        val cached = cachedConnectivityManager
        if (cached != null && cached.asBinder().isBinderAlive && cached.asBinder().pingBinder()) {
            return cached
        }
        return try {
            val originalBinder = SystemServiceHelper.getSystemService(Context.CONNECTIVITY_SERVICE)
                ?: return null
            val originalService = IConnectivityManager.Stub.asInterface(originalBinder)
                ?: return null
            val wrapper = ShizukuBinderWrapper(originalService.asBinder())
            val cm = IConnectivityManager.Stub.asInterface(wrapper)
            cachedConnectivityManager = cm
            cm
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to get IConnectivityManager", e)
            null
        }
    }

    /**
     * 清理失效的 Binder 缓存
     */
    @Synchronized
    private fun invalidateBinder() {
        cachedConnectivityManager = null
    }

    /**
     * 通过 IConnectivityManager Binder IPC 控制 UID 的网络
     * 使用 FIREWALL_CHAIN_OEM_DENY_3 (chain 9)
     * 若 Binder 途径失败，则自动降级为 cmd connectivity 命令行兜底
     */
    fun setPackageNetworkingEnabled(uid: Int, enabled: Boolean, pkgName: String = "com.xiaomi.xmsf"): String? {
        if (!isShizukuAvailable) {
            return "Shizuku is not available or permission not granted"
        }

        // 优先通道 1：Binder 直接 IPC
        val binderErr = trySetNetworkingViaBinder(uid, enabled)
        if (binderErr == null) {
            Log.d(TAG, "Network ${if (enabled) "RESTORED" else "BLOCKED"} for uid=$uid via Binder IPC")
            return null
        }

        Log.w(TAG, "Binder IPC failed ($binderErr), attempting fallback via cmd connectivity")
        invalidateBinder()

        // 兜底通道 2：cmd connectivity 命令行
        val cmdErr = trySetNetworkingViaCommand(pkgName, enabled)
        if (cmdErr == null) {
            Log.d(TAG, "Network ${if (enabled) "RESTORED" else "BLOCKED"} for pkg=$pkgName via cmd connectivity")
            return null
        }

        return "Both Binder IPC and Command fallback failed: binder=$binderErr, cmd=$cmdErr"
    }

    private fun trySetNetworkingViaBinder(uid: Int, enabled: Boolean): String? {
        val oldPolicy = android.os.StrictMode.getThreadPolicy()
        return try {
            android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.LAX)
            for (attempt in 0..1) {
                try {
                    val cm = getConnectivityManager() ?: run {
                        invalidateBinder()
                        return "ConnectivityManager is null"
                    }
                    val chain = 9 // FIREWALL_CHAIN_OEM_DENY_3
                    val rule = if (enabled) 0 else 2 // FIREWALL_RULE_DEFAULT : FIREWALL_RULE_DENY

                    if (!enabled) {
                        cm.setFirewallChainEnabled(chain, true)
                        cm.setUidFirewallRule(chain, uid, rule)
                    } else {
                        cm.setUidFirewallRule(chain, uid, rule)
                    }
                    return null
                } catch (t: Throwable) {
                    invalidateBinder()
                    if (attempt == 1) {
                        return t.message ?: t.javaClass.simpleName
                    }
                }
            }
            "Unknown error"
        } finally {
            android.os.StrictMode.setThreadPolicy(oldPolicy)
        }
    }

    private fun trySetNetworkingViaCommand(pkgName: String, enabled: Boolean): String? {
        return try {
            val cmd = "cmd connectivity set-package-networking-enabled $enabled $pkgName"
            val success = com.moting.linkgo.util.privilege.PrivilegeEngine.execBlocking(cmd)
            if (success) null else "Command execution returned false"
        } catch (t: Throwable) {
            t.message ?: t.javaClass.simpleName
        }
    }

    fun blockUidNetworking(uid: Int, pkgName: String = "com.xiaomi.xmsf"): Boolean =
        setPackageNetworkingEnabled(uid, false, pkgName) == null

    fun restoreUidNetworking(uid: Int, pkgName: String = "com.xiaomi.xmsf"): Boolean =
        setPackageNetworkingEnabled(uid, true, pkgName) == null
}

