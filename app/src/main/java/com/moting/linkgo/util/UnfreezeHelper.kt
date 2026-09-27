package com.moting.linkgo.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.moting.linkgo.util.privilege.PrivilegeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 目标应用冻结检测与解冻工具。
 *
 * 支持两种"冻结"场景：
 * 1. 禁用/停用型（pm disable、系统停用）：通过 PackageManager enabled 状态预检，pm enable 解冻；
 * 2. 进程冻结型（Android 12+ 冻结器 / MIUI greezer / OPPO quick_freeze 等）：无公开 API 可检，
 *    靠"启动失败 → 解冻 → 重试"兜底（am unfreeze）。
 *
 * 提权方式：统一走 [PrivilegeEngine]（Shizuku 优先，Root 兜底），由权限中心统一调度。
 */
object UnfreezeHelper {
    private const val TAG = "UnfreezeHelper"

    private val cachedUserIds = java.util.concurrent.CopyOnWriteArrayList<Int>()
    private var lastUserQueryTime = 0L
    private const val USER_CACHE_TTL_MS = 60_000L

    /**
     * 获取系统当前存在的多用户列表（如 User 0 主空间、User 999 分身空间、User 10 工作空间等）。
     * 具备 60 秒缓存，避免频繁跨进程查询。
     */
    suspend fun getAvailableUserIds(): List<Int> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (cachedUserIds.isNotEmpty() && now - lastUserQueryTime < USER_CACHE_TTL_MS) {
            return@withContext cachedUserIds
        }
        val users = mutableListOf(0)
        try {
            val output = PrivilegeEngine.execWithOutput("pm list users")
            if (!output.isNullOrBlank()) {
                val matcher = java.util.regex.Pattern.compile("""UserInfo\{(\d+):""").matcher(output)
                while (matcher.find()) {
                    matcher.group(1)?.toIntOrNull()?.let { uid ->
                        if (!users.contains(uid)) users.add(uid)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[GET-USERS] 获取多用户失败: ${e.message}")
        }
        cachedUserIds.clear()
        cachedUserIds.addAll(users)
        lastUserQueryTime = now
        users
    }

    /**
     * 检测包是否处于禁用/停用状态（包级 + 组件级）。
     */
    fun isDisabled(pm: PackageManager, pkg: String, component: ComponentName? = null): Boolean {
        return try {
            val setting = pm.getApplicationEnabledSetting(pkg)
            if (setting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                setting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
            ) {
                true
            } else {
                component?.let {
                    val cs = pm.getComponentEnabledSetting(it)
                    cs == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                        cs == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                } ?: false
            }
        } catch (e: Exception) {
            Log.w(TAG, "[IS-DISABLED] 查询异常 pkg=$pkg: ${e.message}")
            false
        }
    }

    /**
     * 事前主动预检：检测目标应用是否处于冻结/停用状态（覆盖主空间与分身空间）。
     *
     * 1. 主空间（User 0）：通过本地 PackageManager 极速预检（耗时 < 1ms）；
     * 2. 分身空间（User 999 等）：若存在分身且有特权，检测分身空间下是否处于 disabled 状态；
     *
     * 只要任一空间处于停用/冻结，即返回 true。
     */
    suspend fun checkNeedsUnfreeze(context: Context, pkg: String): Boolean = withContext(Dispatchers.IO) {
        if (PrivilegeEngine.currentChannel() == PrivilegeEngine.Channel.NONE) {
            return@withContext false
        }

        // 1. 主空间极速检测
        val pm = context.packageManager
        var mainDisabled = false
        try {
            val setting = pm.getApplicationEnabledSetting(pkg)
            mainDisabled = (setting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                setting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER)
        } catch (e: PackageManager.NameNotFoundException) {
            // 主空间未找到该包，可能仅安装在分身空间
            mainDisabled = true
        } catch (e: Exception) {
            Log.w(TAG, "[CHECK-PRE-FLIGHT] 主空间查询异常 $pkg: ${e.message}")
        }

        if (mainDisabled) {
            Log.d(TAG, "[CHECK-PRE-FLIGHT] 目标包 $pkg 在主空间处于禁用状态")
            return@withContext true
        }

        // 2. 检测是否存在分身/多用户空间（如 999、10 等）
        val users = getAvailableUserIds()
        val secondaryUsers = (users.filter { it != 0 } + 999).distinct()

        // 3. 针对分身空间快速检测 disabled 包名
        for (uid in secondaryUsers) {
            try {
                val output = PrivilegeEngine.execWithOutput("pm list packages -d --user $uid $pkg")
                if (!output.isNullOrBlank() && output.lines().any { it.trim() == "package:$pkg" }) {
                    Log.d(TAG, "[CHECK-PRE-FLIGHT] 目标包 $pkg 在用户空间 $uid 处于禁用状态")
                    return@withContext true
                }
            } catch (e: Exception) {
                Log.w(TAG, "[CHECK-PRE-FLIGHT] 检测用户空间 $uid 异常: ${e.message}")
            }
        }

        false
    }

    /**
     * 解冻目标包。统一走 [PrivilegeEngine] 提权通道。
     * 全域覆盖主空间（User 0）与各分身空间（User 999 等），合并单次 Shell 执行减少耗时。
     */
    suspend fun unfreeze(pkg: String): Boolean = withContext(Dispatchers.IO) {
        if (PrivilegeEngine.currentChannel() == PrivilegeEngine.Channel.NONE) {
            Log.w(TAG, "[UNFREEZE] 无 Shizuku/Root 授权，无法解冻 $pkg")
            return@withContext false
        }

        val users = getAvailableUserIds()
        val targetUsers = (users + 999).distinct()

        val cmds = mutableListOf<String>()
        // 1. pm enable 解冻停用型
        cmds.add("pm enable $pkg")
        for (uid in targetUsers) {
            cmds.add("pm enable --user $uid $pkg")
        }

        // 2. am unfreeze 解冻 Android 12+ 进程冻结型
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            cmds.add("am unfreeze $pkg")
            for (uid in targetUsers) {
                cmds.add("am unfreeze --user $uid $pkg")
            }
        }

        val mergedCmd = cmds.joinToString("; ")
        val ok = PrivilegeEngine.exec(mergedCmd)
        Log.i(TAG, "[UNFREEZE] 多用户全域解冻 $pkg (users=$targetUsers) result=$ok")
        ok
    }
}
