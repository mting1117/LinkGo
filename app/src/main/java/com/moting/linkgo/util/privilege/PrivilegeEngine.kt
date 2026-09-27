package com.moting.linkgo.util.privilege

import android.util.Log
import com.moting.linkgo.service.ShizukuManager
import com.moting.linkgo.util.AppShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 统一提权执行引擎（Permission Center 的第一层）。
 *
 * 全库唯一负责：
 * 1. 提权通道探测（Shizuku / Root / NONE）——消灭散落在 5+ 处的重复判断；
 * 2. 特权 Shell 命令执行——消灭散落在 6+ 处的 su 内联代码；
 * 3. 全局特权总开关（[globalEnabled]）——谨慎用户可一键停用所有后台特权。
 *
 * 设计原则：
 * - 所有需要 root/shell 权限的调用必须经过本引擎，禁止再直接 `Runtime.exec("su")`；
 * - Shizuku 通道底层仍委托 [ShizukuManager.execShell]（Binder 生命周期归属 ShizukuManager）；
 * - Root 通道的 su 实现只有这里一份；
 * - 通道探测结果短时缓存，Shizuku binder 事件驱动失效。
 */
object PrivilegeEngine {
    private const val TAG = "PrivilegeEngine"

    /** 提权通道 */
    enum class Channel { SHIZUKU, ROOT, NONE }

    /** 特权工作模式：通俗直白 */
    enum class PrivilegeMode(val key: String, val displayName: String) {
        AUTO("auto", "自动 (优先Root)"),
        ROOT("root", "仅 Root"),
        SHIZUKU("shizuku", "仅 Shizuku");

        companion object {
            fun fromString(key: String?): PrivilegeMode = when (key?.lowercase()) {
                "root" -> ROOT
                "shizuku" -> SHIZUKU
                else -> AUTO
            }
        }
    }

    // ── 全局特权总开关与工作模式（配合 SettingsRepository 持久化）────────
    @Volatile
    var globalEnabled: Boolean = true

    @Volatile
    var mode: PrivilegeMode = PrivilegeMode.AUTO

    /** 本应用包名（LinkGoApp 启动时注入，用于识别"静默授权自身权限"类命令） */
    @Volatile
    var appPackageName: String? = null

    /**
     * 能力拦截：判断命令是否属于「静默授权自身权限」类（pm grant/revoke、appops set 目标为自身包名），
     * 若能力 [CapabilityCenter.Capability.SHIZUKU_SILENT_GRANT] 已关闭则拒绝执行。
     */
    fun commandBlockedByCapability(command: String): Boolean {
        val pkg = appPackageName ?: return false
        val trimmed = command.trim()
        val isSelfGrant = trimmed.startsWith("pm grant $pkg ") ||
            trimmed.startsWith("pm revoke $pkg ") ||
            trimmed.startsWith("appops set $pkg ")
        if (isSelfGrant && !CapabilityCenter.isEnabled(CapabilityCenter.Capability.SHIZUKU_SILENT_GRANT)) {
            Log.w(TAG, "[CAP-BLOCKED] 能力「静默授权自身权限」已关闭，拒绝执行: $command")
            return true
        }
        return false
    }

    // ── Root 探测缓存（短 TTL，避免频繁弹 su 授权框）──────────────────────
    private val suProbeResult = AtomicLong(-1L) // 0=不可用, 1=可用
    private val suProbeAt = AtomicLong(0L)
    private const val SU_PROBE_TTL_MS = 60_000L
    private const val SU_TIMEOUT_MS = 5_000L

    /**
     * 当前可用提权通道：
     * - 总开关关闭时恒为 NONE；
     * - 模式为 ROOT：仅检测 Root；
     * - 模式为 SHIZUKU：仅检测 Shizuku；
     * - 模式为 AUTO：优先 Root，无 Root 则尝试 Shizuku。
     */
    fun currentChannel(): Channel {
        if (!globalEnabled) return Channel.NONE
        return when (mode) {
            PrivilegeMode.ROOT -> {
                if (canExecSu()) Channel.ROOT else Channel.NONE
            }
            PrivilegeMode.SHIZUKU -> {
                if (ShizukuManager.isGranted() || AppShell.isShizukuAvailable) Channel.SHIZUKU else Channel.NONE
            }
            PrivilegeMode.AUTO -> {
                if (canExecSu()) Channel.ROOT
                else if (ShizukuManager.isGranted() || AppShell.isShizukuAvailable) Channel.SHIZUKU
                else Channel.NONE
            }
        }
    }

    /** 是否拥有任意提权通道 */
    fun hasAnyPrivilege(): Boolean = currentChannel() != Channel.NONE

    /** 主动测试 Root 授权（强制清缓存重新探测） */
    fun testRoot(): Boolean {
        invalidateSuProbe()
        return canExecSu()
    }

    /** 主动测试 Shizuku 授权状态 */
    fun testShizuku(): Boolean {
        return ShizukuManager.isGranted() || AppShell.isShizukuAvailable
    }

    /**
     * 探测 su 是否可用（写入 id + exit，进程正常退出视为可用）。
     * 成功结果短时缓存，避免每次调用都弹 Magisk 授权框；失败则不长期缓存，允许即时重试。
     */
    fun canExecSu(): Boolean {
        val now = System.currentTimeMillis()
        val cachedAt = suProbeAt.get()
        if (now - cachedAt < SU_PROBE_TTL_MS && suProbeResult.get() == 1L) {
            return true
        }
        val ok = runCatching {
            val p = Runtime.getRuntime().exec("su")
            DataOutputStream(p.outputStream).use { os ->
                os.writeBytes("id\nexit\n")
                os.flush()
            }
            val success = p.waitFor(SU_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!success) {
                p.destroy()
                false
            } else {
                p.exitValue() == 0
            }
        }.getOrDefault(false)
        suProbeResult.set(if (ok) 1L else 0L)
        // 仅在探测成功时缓存，探测失败不锁死后续尝试
        suProbeAt.set(if (ok) now else 0L)
        Log.d(TAG, "[SU-PROBE] su 可用: $ok")
        return ok
    }

    /** 使 Root 探测缓存失效（例如用户刚在权限中心测试了 su） */
    fun invalidateSuProbe() {
        suProbeAt.set(0L)
    }

    /** 使 Shizuku 通道状态失效（binder 断连/重连时由监听器调用） */
    fun onShizukuStateChanged() {
        // ShizukuManager 内部状态是实时的，这里仅留作扩展点
    }

    /**
     * 用当前可用通道执行一条 Shell 命令。
     * @return true=执行成功(exit 0)；无可用通道或总开关关闭时返回 false。
     */
    suspend fun exec(command: String): Boolean = withContext(Dispatchers.IO) {
        if (commandBlockedByCapability(command)) {
            return@withContext false
        }
        if (!globalEnabled) {
            Log.w(TAG, "[EXEC-BLOCKED] 全局特权总开关已关闭，拒绝执行: $command")
            return@withContext false
        }
        when (currentChannel()) {
            Channel.SHIZUKU -> execShizuku(command)
            Channel.ROOT -> execRoot(command)
            Channel.NONE -> {
                Log.w(TAG, "[EXEC-NO-PRIV] 无 Shizuku/Root 授权，无法执行: $command")
                false
            }
        }
    }

    /** 按指定通道执行（供强制指定场景使用） */
    suspend fun execWith(channel: Channel, command: String): Boolean = withContext(Dispatchers.IO) {
        if (commandBlockedByCapability(command)) {
            return@withContext false
        }
        if (!globalEnabled) {
            Log.w(TAG, "[EXEC-BLOCKED] 全局特权总开关已关闭，拒绝执行: $command")
            return@withContext false
        }
        when (channel) {
            Channel.SHIZUKU -> execShizuku(command)
            Channel.ROOT -> execRoot(command)
            Channel.NONE -> false
        }
    }

    /** 尽力而为：ROOT 优先失败则回退 Shizuku（部分命令仅 root 可执行） */
    suspend fun execBestEffort(command: String): Boolean = withContext(Dispatchers.IO) {
        if (commandBlockedByCapability(command)) {
            return@withContext false
        }
        if (!globalEnabled) return@withContext false
        val ch = currentChannel()
        when (ch) {
            Channel.ROOT -> {
                if (execRoot(command)) true
                else execShizuku(command)
            }
            Channel.SHIZUKU -> execShizuku(command)
            Channel.NONE -> false
        }
    }

    /**
     * 用当前可用通道执行一条 Shell 命令（阻塞版本，供后台线程直接调用）。
     * @return true=执行成功(exit 0)；无可用通道或总开关关闭时返回 false。
     */
    fun execBlocking(command: String): Boolean {
        if (commandBlockedByCapability(command)) return false
        if (!globalEnabled) {
            Log.w(TAG, "[EXEC-BLOCKED] 全局特权总开关已关闭，拒绝执行: $command")
            return false
        }
        return when (currentChannel()) {
            Channel.SHIZUKU -> execShizuku(command)
            Channel.ROOT -> execRoot(command)
            Channel.NONE -> {
                Log.w(TAG, "[EXEC-NO-PRIV] 无 Shizuku/Root 授权，无法执行: $command")
                false
            }
        }
    }

    /**
     * 用当前可用通道执行命令并返回 stdout（供诊断类命令读取输出）。
     * 阻塞版本：必须在后台线程调用（不要在协程主线程直接调用）。
     * @return 命令成功(exit 0)时的输出文本（trim），失败/无权限/总开关关闭返回 null。
     */
    fun execWithOutputBlocking(command: String): String? {
        if (commandBlockedByCapability(command)) return null
        if (!globalEnabled) {
            Log.w(TAG, "[EXEC-BLOCKED] 全局特权总开关已关闭，拒绝执行: $command")
            return null
        }
        return when (currentChannel()) {
            Channel.SHIZUKU -> execShizukuWithOutput(command)
            Channel.ROOT -> execRootWithOutput(command)
            Channel.NONE -> {
                Log.w(TAG, "[EXEC-NO-PRIV] 无 Shizuku/Root 授权，无法执行: $command")
                null
            }
        }
    }

    /** 挂起版本：自动切到 IO 线程 */
    suspend fun execWithOutput(command: String): String? = withContext(Dispatchers.IO) {
        execWithOutputBlocking(command)
    }

    private fun execShizukuWithOutput(command: String): String? {
        val output = ShizukuManager.execShellWithOutput(command)
        Log.d(TAG, "[SHIZUKU-OUT] $command -> ${output?.length ?: -1} chars")
        return output
    }

    private fun execRootWithOutput(command: String): String? {
        return try {
            val p = Runtime.getRuntime().exec("su")
            DataOutputStream(p.outputStream).use { os ->
                os.writeBytes("$command 2>&1\nexit\n")
                os.flush()
            }
            val output = p.inputStream.bufferedReader().use { it.readText() }
            val ok = p.waitFor(5, TimeUnit.SECONDS)
            if (!ok) {
                p.destroy()
                null
            } else {
                Log.d(TAG, "[ROOT-OUT] $command -> exitCode=${p.exitValue()}, len=${output.length}")
                output.trim().ifEmpty { null }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[ROOT-OUT-FAIL] $command: ${e.message}")
            null
        }
    }

    private fun execShizuku(command: String): Boolean {
        val ok = ShizukuManager.execShell(command)
        Log.d(TAG, "[SHIZUKU] $command -> $ok")
        return ok
    }

    private fun execRoot(command: String): Boolean {
        return try {
            val p = Runtime.getRuntime().exec("su")
            DataOutputStream(p.outputStream).use { os ->
                os.writeBytes("$command 2>&1\nexit\n")
                os.flush()
            }
            val output = p.inputStream.bufferedReader().use { it.readText() }
            val ok = p.waitFor(5, TimeUnit.SECONDS)
            if (!ok) {
                p.destroy()
                false
            } else {
                val exitCode = p.exitValue()
                val success = exitCode == 0 && !output.contains("Error:", ignoreCase = true)
                Log.d(TAG, "[ROOT] $command -> exitCode=$exitCode, success=$success, out=$output")
                success
            }
        } catch (e: Exception) {
            Log.w(TAG, "[ROOT-FAIL] $command: ${e.message}")
            false
        }
    }
}
