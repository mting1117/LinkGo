package com.moting.linkgo.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import com.google.android.accessibility.selecttospeak.SelectToSpeakService
import com.moting.linkgo.service.ShizukuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 无障碍服务工具类，提供 TouchTool 级别的全自动静默自愈与保活引擎。
 */
object AccessibilityUtils {

    private const val TAG = "A11yHeal"
    const val SERVICE_NAME = "com.moting.linkgo/com.google.android.accessibility.selecttospeak.SelectToSpeakService"

    /** 被动自愈限流窗口：避免 onUnbind 与重启流程互相打架造成反复摘挂 */
    private const val PASSIVE_HEAL_MIN_INTERVAL_MS = 15_000L

    /** 摘除登记到重新登记之间的等待：系统需时间完成解绑与旧连接清理，过短会被忽略 */
    private const val RESTART_PUBLISH_MS = 5_000L

    /** 重挂后等待实例重连的轮询窗口（35 × 200ms） */
    private const val RESTART_POLL_WINDOW = 35

    /** 跨设备通用失败后重试前的等待：让系统从上次摘挂中恢复，再走一轮完整流程 */
    private const val RESTART_RETRY_BACKOFF_MS = 5_000L

    /** 主动自愈进行中标记：并发调用时只等待，不重复摘挂服务 */
    @Volatile
    private var healingInFlight: Boolean = false

    @Volatile
    private var lastPassiveHealAt: Long = 0L

    /**
     * 检查服务是否已在系统设置中启用
     */
    fun isServiceEnabled(context: Context): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (componentName.equals(SERVICE_NAME, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    /**
     * 检查是否拥有 WRITE_SECURE_SETTINGS 权限
     */
    fun hasWriteSecureSettingsPermission(context: Context): Boolean {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 通过 WRITE_SECURE_SETTINGS 权限直接开启无障碍服务
     */
    fun enableServiceBySecureSettings(context: Context): Boolean {
        if (!hasWriteSecureSettingsPermission(context)) return false
        return try {
            val resolver = context.contentResolver
            val currentServices = Settings.Secure.getString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            val services = currentServices.split(":")
                .filter { it.isNotBlank() }
                .toMutableSet()
            services.add(SERVICE_NAME)
            val finalServices = services.joinToString(":")
            Settings.Secure.putString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                finalServices
            )
            Settings.Secure.putInt(
                resolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                1
            )
            Log.i(TAG, "[HEAL-SUCCESS] 已通过 SecureSettings 成功静默激活无障碍服务")
            true
        } catch (e: Exception) {
            Log.w(TAG, "[HEAL-FAIL] 通过 SecureSettings 激活失败: ${e.message}")
            false
        }
    }

    /**
     * 读取当前已启用的无障碍服务串（诊断与内部判断用）
     */
    fun enabledServicesOf(context: Context): String = runCatching {
        Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
    }.getOrDefault("")

    /**
     * 服务实例是否真正可用（含假死判定）。
     * 实例存在但已与系统失联（假死）时返回 false，供权限状态展示与自愈判断。
     */
    fun isActuallyAlive(): Boolean = SelectToSpeakService.getInstance()?.isActuallyAlive() ?: false

    /**
     * 用户动作路径专用的可用性判定：结论缺失或过期时补一次真实探测，
     * 避免"很久没探测"被当成"服务不可用"而向用户弹出无意义的未就绪提示。
     * 仅在用户主动触发手势/磁贴时调用，不在后台轮询中调用。
     */
    fun isServiceUsableNow(): Boolean {
        val instance = SelectToSpeakService.getInstance() ?: return false
        if (instance.isActuallyAlive()) return true
        return instance.probeAlive()
    }

    /**
     * 设备近期是否处于活跃使用状态（依据无障碍窗口事件）。
     * 服务实例不存在时也能回答（此刻问的是"上次活动有多近"），
     * 供保活心跳跳过息屏/深度待机下的无效检查。
     */
    fun wasRecentlyActive(windowMs: Long = 10 * 60 * 1000L): Boolean =
        SelectToSpeakService.wasRecentlyActive(windowMs)

    /**
     * 服务假死检测：实例存在、系统设置显示已启用，但实例已失联。
     * 探测失败后等待 150ms 复测一次，抑制瞬时抖动误判；
     * 若从未探测或探测结论已过期，则补一次真实探测而不是直接判死。
     */
    suspend fun isServiceDead(context: Context): Boolean {
        val instance = SelectToSpeakService.getInstance() ?: return false
        if (!isServiceEnabled(context)) return false
        // 缓存结论健康则无需重复探测；结论缺失或过期时补探一次
        if (instance.isActuallyAlive()) return false
        if (instance.probeAlive()) return false
        // 首次探测失败后复测一次，抑制瞬时抖动误判
        delay(150)
        if (instance.probeAlive()) return false
        Log.w(TAG, "[HEAL-DEAD] 检测到无障碍服务假死（实例存在但窗口能力已失效）")
        return true
    }

    /**
     * 被动自愈入口（供服务 onUnbind 与手势失效时调用）。
     * 带 15 秒限流，避免与主动重启流程互相打架；
     * 且仅当服务仍登记在系统启用列表时才自愈——用户在系统设置里主动关闭后不再擅自点亮。
     */
    fun requestPassiveSelfHeal(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastPassiveHealAt < PASSIVE_HEAL_MIN_INTERVAL_MS) return
        lastPassiveHealAt = now
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                delay(800)
                if (!isServiceEnabled(appContext)) {
                    Log.d(TAG, "[HEAL-SKIP] 服务已不在系统启用列表中（用户主动关闭），跳过自愈")
                    return@runCatching
                }
                autoHealService(appContext)
            }
        }
    }

    /**
     * 重启无障碍服务 (需要 WRITE_SECURE_SETTINGS 权限或可用特权通道)
     * 流程：摘除登记 → 等待系统完成解绑与旧连接清理 → 重新登记 → 轮询确认实例重连。
     * 关键点：仅重写同一串登记值不会触发重绑，必须先摘除，且摘除到重挂之间要留足沉淀时间。
     */
    suspend fun restartService(context: Context): Boolean = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val resolver = appContext.contentResolver

        // 0. 仅当旧实例「还存在但已失联」（假死）时才调用 disableSelf 强拆。
        //    disableSelf 是「运行时禁用服务」，会顺带把无障碍框架的总开关状态关掉，
        //    因此不能无条件调用——常规摘挂（实例已为空）时调用反而可能让随后的重挂失效。
        val stale = SelectToSpeakService.getInstance()
        if (stale != null && !stale.probeAlive()) {
            runCatching {
                stale.disableSelf()
                Log.i(TAG, "[HEAL-RESTART] 假死实例存在，已调用 disableSelf 主动解绑")
            }
            // 等它真正断开：仅靠 disableSelf 在部分机型上不会立即回收实例
            for (i in 0 until 10) {
                if (SelectToSpeakService.getInstance() == null) break
                delay(100)
            }
            Log.i(TAG, "[HEAL-RESTART] 解绑后实例状态: ${if (SelectToSpeakService.getInstance() == null) "已清空" else "仍存在"}")
        }

        // 1. 摘除登记：让系统彻底放弃旧连接（等价于用户在系统设置里关闭该服务）
        val currentServices = Settings.Secure.getString(
            resolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""

        val remaining = currentServices.split(":")
            .filter { it.isNotBlank() && !it.equals(SERVICE_NAME, ignoreCase = true) }
            .joinToString(":")

        val removedBySettings = remaining != currentServices
        if (removedBySettings) {
            Settings.Secure.putString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                remaining
            )
            Log.i(TAG, "[HEAL-RESTART] 已摘除无障碍登记，剩余: ${remaining.ifBlank { "(空)" }}")
        } else {
            Log.i(TAG, "[HEAL-RESTART] 登记中未包含本服务，无需摘除")
        }

        // 摘除后留出状态沉淀时间：系统需要完成解绑、清理旧连接后才接受重挂。
        // 实测过短（1~2 秒）时系统会忽略随后的重挂，5 秒为稳定通过的取值。
        delay(RESTART_PUBLISH_MS)

        // 2. 重新登记并拉起（优先直接写安全设置，其次走特权通道）
        if (!enableServiceBySecureSettings(appContext)) {
            val target = if (remaining.isBlank()) SERVICE_NAME else "$remaining:$SERVICE_NAME"
            val ok = com.moting.linkgo.util.privilege.PrivilegeEngine.exec(
                "settings put secure enabled_accessibility_services '$target' && settings put secure accessibility_enabled 1"
            )
            Log.i(TAG, "[HEAL-RESTART] 特权通道重新登记结果: $ok")
            if (!ok) {
                ensureAccessibilityFrameworkOn(resolver)
                return@withContext false
            }
        }

        // 3. 轮询等待实例重连并确认活性（约 7 秒：系统重新绑定并回调 onServiceConnected 需要时间）
        var tries = 0
        while (tries < RESTART_POLL_WINDOW) {
            delay(200)
            val instance = SelectToSpeakService.getInstance()
            if (instance != null && instance.probeAlive()) {
                Log.i(TAG, "[HEAL-RESTART] 无障碍服务重启成功（第 ${tries + 1} 次轮询确认）")
                return@withContext true
            }
            tries++
        }
        Log.w(TAG, "[HEAL-RESTART] 无障碍服务重启超时，实例未在 7 秒内恢复")
        // 重挂失败也必须把框架总开关恢复为开启：否则 disableSelf 留下的关闭状态会放大影响面
        ensureAccessibilityFrameworkOn(resolver)
        false
    }

    /**
     * 兜底恢复无障碍框架总开关。
     * disableSelf 会顺带关闭它；若后续重挂失败还留着关闭状态，会影响到所有无障碍服务的可用性。
     */
    private fun ensureAccessibilityFrameworkOn(resolver: android.content.ContentResolver) {
        runCatching {
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        }
    }

    /**
     * 通过可用特权通道（统一走 PrivilegeEngine）为本应用授予 WRITE_SECURE_SETTINGS 权限
     */
    suspend fun grantSecureSettings(context: Context): Boolean {
        return com.moting.linkgo.util.privilege.PrivilegeEngine.exec(
            "pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
        )
    }

    /**
     * 兼容旧调用：通过 Shizuku 授予（统一走 PrivilegeEngine 调度）
     */
    fun grantSecureSettingsByShizuku(context: Context): Boolean {
        val pkg = context.packageName
        return kotlinx.coroutines.runBlocking {
            com.moting.linkgo.util.privilege.PrivilegeEngine.exec("pm grant $pkg android.permission.WRITE_SECURE_SETTINGS")
        }
    }

    /**
     * 兼容旧调用：通过 Root 授予（统一走 PrivilegeEngine 调度）
     */
    suspend fun grantSecureSettingsByRoot(context: Context): Boolean = withContext(Dispatchers.IO) {
        grantSecureSettings(context)
    }

    /**
     * 全自动静默自愈引擎（Auto-Heal Engine）。
     * 在 App 启动、切前台、开机广播到达或特权就绪时触发。
     * 若检测到无障碍已掉线，自动利用可用特权（Secure Settings / 特权通道）在后台拉起。
     *
     * 能力守卫：非用户手动授权（force=false）时，若能力开关「自愈·应用内自愈」已关闭，
     * 则任何调用路径都直接短路，不再点亮无障碍（纵深防御，不依赖调用点自觉）。
     *
     * @param force true=用户手动授权（权限中心"授予"按钮），绕过能力开关。
     */
    suspend fun autoHealService(context: Context, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        // ── 能力守卫：关闭「自愈·应用内自愈」后，所有自动路径全部短路 ──
        if (!force && !com.moting.linkgo.util.privilege.CapabilityCenter.isEnabled(
                com.moting.linkgo.util.privilege.CapabilityCenter.Capability.A11Y_HEAL_APP
            )
        ) {
            Log.d(TAG, "[HEAL-CAP-BLOCKED] 能力「自愈·应用内自愈」已关闭，跳过无障碍自愈")
            return@withContext false
        }

        val appContext = context.applicationContext

        // 1. 已有实例且系统登记存在：进一步判定是否假死（实例存在但窗口能力已失效）
        val instance = SelectToSpeakService.getInstance()
        if (instance != null && isServiceEnabled(appContext)) {
            if (!isServiceDead(appContext)) {
                // 健康路径同样刷新一次：把「已就绪」如实落到常驻通知
                refreshMonitorNotification()
                return@withContext true
            }
            Log.w(TAG, "[HEAL-DEAD-RESTART] 无障碍服务假死，先摘除登记再重新拉起...")
            healingInFlight = true
            return@withContext try {
                restartService(appContext)
            } finally {
                healingInFlight = false
                refreshMonitorNotification()
            }
        }

        // 已有其他调用正在重启服务：等待其完成，不重复摘挂
        if (healingInFlight) {
            Log.d(TAG, "[HEAL-WAIT] 已有自愈流程进行中，等待其完成")
            for (i in 0 until 25) {
                delay(200)
                val s = SelectToSpeakService.getInstance()
                if (s != null && s.probeAlive()) return@withContext true
            }
            // 等待超时不代表失败：对方流程可能已成功拉起，以实际活性为准
            return@withContext SelectToSpeakService.getInstance()?.isActuallyAlive() ?: false
        }

        healingInFlight = true
        try {
            // 2. 系统登记在册但实例已丢失：登记值重写不会触发重绑，必须先摘除再重挂
            if (instance == null && isServiceEnabled(appContext)) {
                Log.w(TAG, "[HEAL-REBIND] 登记存在但服务实例已丢失，执行摘除重挂...")
                if (restartService(appContext)) return@withContext true
            }

            Log.d(TAG, "[HEAL-CHECK] 巡检检测到无障碍服务未处于运行状态，尝试自动自愈...")

            // 3. 第一优先级：拥有系统安全设置权限 (WRITE_SECURE_SETTINGS)
            if (hasWriteSecureSettingsPermission(appContext)) {
                val ok = enableServiceBySecureSettings(appContext)
                if (ok && awaitServiceAlive()) return@withContext true
            }

            // 4. 第二优先级：若具备可用特权通道（Root/Shizuku 统一调度），尝试自动赋权或直接写回设置
            if (com.moting.linkgo.util.privilege.PrivilegeEngine.hasAnyPrivilege()) {
                if (!hasWriteSecureSettingsPermission(appContext)) {
                    grantSecureSettings(appContext)
                }
                if (hasWriteSecureSettingsPermission(appContext) && enableServiceBySecureSettings(appContext)) {
                    if (awaitServiceAlive()) return@withContext true
                }
                // 直接通过 shell 写 secure settings（统一走 PrivilegeEngine），并先剔除重复登记
                val target = appendServiceUnique(enabledServicesOf(appContext))
                val ok = com.moting.linkgo.util.privilege.PrivilegeEngine.exec(
                    "settings put secure enabled_accessibility_services '$target' && settings put secure accessibility_enabled 1"
                )
                Log.i(TAG, "[PRIV-SHELL-HEAL] 通过特权通道激活无障碍: $ok")
                if (ok && awaitServiceAlive()) return@withContext true
            }
        } finally {
            healingInFlight = false
        }

        // 走到这里说明自动恢复的几次尝试都没等到实例回来。
        // 系统重新绑定慢于轮询窗口是常见的（实测存在数十秒才重绑的情况），
        // 因此先等一轮退避，再完整重跑一次摘挂——单一轮次失败不足以判定终局。
        if (isServiceEnabled(appContext) && SelectToSpeakService.getInstance() == null) {
            Log.w(TAG, "[HEAL-RETRY] 首轮未等到重绑，退避后重跑一轮摘挂")
            delay(RESTART_RETRY_BACKOFF_MS)
            if (SelectToSpeakService.getInstance() == null && restartService(appContext)) {
                refreshMonitorNotification()
                return@withContext true
            }
        }

        // 重试后仍未回来：通知作为兜底提示，真实状态以实例活性为准。
        val blockedBySystem = isServiceEnabled(appContext) && SelectToSpeakService.getInstance() == null
        if (blockedBySystem) {
            Log.w(TAG, "[HEAL-PENDING] 登记已写入但系统尚未完成重新绑定；" +
                "若长期如此，可在系统设置中关闭再打开无障碍开关手动清一次")
        } else {
            Log.d(TAG, "[HEAL-SKIP] 缺少特权，无法静默拉起无障碍服务")
        }
        refreshMonitorNotification(if (blockedBySystem) "可在系统设置中重开无障碍开关" else null)
        false
    }

    /**
     * 把本服务追加到已启用串末尾，并剔除重复项（系统对重复登记会判为异常）。
     */
    private fun appendServiceUnique(current: String): String {
        val list = current.split(":")
            .filter { it.isNotBlank() && !it.equals(SERVICE_NAME, ignoreCase = true) }
            .toMutableList()
        list.add(SERVICE_NAME)
        return list.joinToString(":")
    }

    /**
     * 让剪贴板前台服务的常驻通知刷新一次副标题，如实反映手势引擎当前可达性。
     * 复用已在状态栏的通知作为健康展示位，不新增通知、不新增唤醒；服务未运行时静默跳过。
     *
     * @param hint 自动恢复失败时需要用户手动处理时的提示，例如引导去系统设置重开无障碍开关
     */
    private fun refreshMonitorNotification(hint: String? = null) {
        runCatching { com.moting.linkgo.service.ClipboardMonitorService.refreshNotification(hint) }
    }

    /**
     * 拉起登记后轮询确认实例真正连接且可用（最长约 3 秒）。
     * 仅写入登记值不代表服务已就绪，必须以实例活性为最终判据。
     */
    private suspend fun awaitServiceAlive(): Boolean {
        for (i in 0 until 15) {
            delay(200)
            val s = SelectToSpeakService.getInstance()
            if (s != null && s.probeAlive()) return true
        }
        return false
    }
}
