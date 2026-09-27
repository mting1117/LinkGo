package com.moting.linkgo.util

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.moting.linkgo.data.SettingsCache
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 小米超级岛全链路诊断日志管理器
 * 负责收集：
 * 1. 硬件、系统版本、HyperOS 属性与焦点通知协议
 * 2. 通知渠道重要性、悬浮/横幅权限
 * 3. 实际构建发送的 miui.focus.param 原文字符串与 Actions
 * 4. Shizuku 断网旁路执行状态
 * 5. LSPosed (SystemUI) 截获的跨进程系统处理事件
 * 6. (可选) Shizuku 抓取的 FocusNotification / SystemUI 系统 Logcat 快照
 */
object SuperIslandLogManager {

    private const val TAG = "SuperIslandLog"
    const val ACTION_SUPER_ISLAND_DIAG_EVENT = "com.moting.linkgo.action.SUPER_ISLAND_DIAG_EVENT"
    const val EXTRA_DIAG_TAG = "extra_diag_tag"
    const val EXTRA_DIAG_MESSAGE = "extra_diag_message"

    private const val LOG_DIR_NAME = "logs"
    private const val LOG_FILE_NAME = "super_island.log"
    private const val MAX_LOG_SIZE_BYTES = 512 * 1024L // 512 KB
    private const val KEEP_LOG_SIZE_BYTES = 256 * 1024L // 超过截断保留后半部分

    private val writeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "LinkGo-IslandLogger").apply { priority = Thread.MIN_PRIORITY }
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    @Volatile
    private var lastBypassStartTimeMs: Long = 0L
    @Volatile
    private var lastBypassEndTimeMs: Long = 0L
    @Volatile
    private var lastNotifySubmitTimeMs: Long = 0L

    val isLoggingEnabled: Boolean
        get() = SettingsCache.superIslandLoggingEnabled

    fun getLogFile(context: Context): File {
        val dir = File(context.filesDir, LOG_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, LOG_FILE_NAME)
    }

    fun getLogFileSizeText(context: Context): String {
        val file = getLogFile(context)
        if (!file.exists() || file.length() == 0L) {
            return "0 KB"
        }
        val kb = file.length() / 1024.0
        return if (kb < 1024.0) {
            String.format(Locale.getDefault(), "%.1f KB", kb)
        } else {
            String.format(Locale.getDefault(), "%.2f MB", kb / 1024.0)
        }
    }

    fun clearLog(context: Context): Boolean {
        return try {
            val file = getLogFile(context)
            if (file.exists()) {
                file.delete()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "clearLog failed", e)
            false
        }
    }

    fun log(context: Context, tag: String, message: String, throwable: Throwable? = null) {
        if (!isLoggingEnabled) return

        val timestamp = synchronized(dateFormat) {
            dateFormat.format(Date())
        }
        val fullMessage = buildString {
            append("[$timestamp] [$tag] $message\n")
            if (throwable != null) {
                append(Log.getStackTraceString(throwable))
                append("\n")
            }
        }

        writeExecutor.execute {
            try {
                val file = getLogFile(context)
                // 检查文件体积，超过 512KB 进行截断防膨胀
                if (file.exists() && file.length() > MAX_LOG_SIZE_BYTES) {
                    val bytes = file.readBytes()
                    val keepFrom = (bytes.size - KEEP_LOG_SIZE_BYTES.toInt()).coerceAtLeast(0)
                    val trimmed = bytes.copyOfRange(keepFrom, bytes.size)
                    file.writeText("... [前置历史日志因超出大小自动滚动截断] ...\n")
                    file.appendBytes(trimmed)
                }
                file.appendText(fullMessage)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write diagnostic log", e)
            }
        }
    }

    /**
     * 写入当前设备与 HyperOS 环境诊断快照
     */
    fun logEnvironmentSnapshot(context: Context, triggerSource: String) {
        if (!isLoggingEnabled) return

        val brand = Build.BRAND
        val model = Build.MODEL
        val manufacturer = Build.MANUFACTURER
        val sdk = Build.VERSION.SDK_INT
        val hyperOsVer = getProp("ro.mi.os.version.name")
        val miuiVer = getProp("ro.miui.ui.version.name")
        val isIslandSupported = HyperIslandHelper.isSupportIsland()
        val protocolVersion = HyperIslandHelper.getFocusProtocolVersion(context)
        val hasFocusPerm = HyperIslandHelper.hasFocusPermission(context)
        val isShizukuAvail = HyperIslandHelper.isShizukuAvailable
        val xmsfUid = HyperIslandHelper.getXmsfUid(context)

        val nm = context.getSystemService(NotificationManager::class.java)
        val areNotificationsEnabled = nm?.areNotificationsEnabled() ?: false
        val channel = nm?.getNotificationChannel(NotificationHelper.CHANNEL_LINK_DISPATCH)
        val channelImportance = channel?.importance ?: -1
        val canBypassDnd = channel?.canBypassDnd() ?: false
        val canShowBadge = channel?.canShowBadge() ?: false
        val canFloat = runCatching {
            val method = channel?.javaClass?.getMethod("canShowFloat")
            method?.invoke(channel) as? Boolean
        }.getOrNull()
        val floatDesc = when (canFloat) {
            true -> "允许 (true)"
            false -> "已禁用 (false - 极可能阻止上岛横幅)"
            null -> "未检测到MIUI专属接口"
        }

        val sb = StringBuilder()
        sb.appendLine("══════════════════ [环境自检快照 - $triggerSource] ══════════════════")
        sb.appendLine("• 设备信息: $manufacturer $model (Brand: $brand, Android SDK: $sdk)")
        sb.appendLine("• HyperOS 版本名: ${hyperOsVer.ifBlank { "非HyperOS / 空" }} (MIUI UI: ${miuiVer.ifBlank { "无" }})")
        sb.appendLine("• persist.sys.feature.island: $isIslandSupported")
        sb.appendLine("• 焦点协议版本 (notification_focus_protocol): $protocolVersion")
        sb.appendLine("• 焦点权限 (getFocusPermission): $hasFocusPerm")
        sb.appendLine("• 通知总开关: $areNotificationsEnabled, 渠道重要性: $channelImportance (3=DEFAULT, 4=HIGH)")
        sb.appendLine("• 渠道绕过免打扰: $canBypassDnd, 显示角标: $canShowBadge, 悬浮/横幅通知: $floatDesc")
        sb.appendLine("• Shizuku 授权状态: $isShizukuAvail, XMSF UID: $xmsfUid")
        sb.appendLine("• 绕过限制开关: ${SettingsCache.superIslandBypassEnabled}, 盲窗时长: ${SettingsCache.superIslandBypassDurationMs}ms")
        sb.appendLine("• 外发光: ${SettingsCache.superIslandOuterGlow}, 拖曳分享: ${SettingsCache.superIslandDragShareEnabled}")
        if (!hasFocusPerm) {
            sb.appendLine("! 【诊断警示】系统未授予 LinkGo 焦点权限 (getFocusPermission=false)，HyperOS 极可能静默过滤丢弃！")
        }
        if (protocolVersion > 1) {
            sb.appendLine("i 【协议匹配】当前系统焦点协议版本为 v$protocolVersion，已动态注入 protocol=$protocolVersion 参数")
        }
        sb.appendLine("════════════════════════════════════════════════════════════════════")

        log(context, "ENV_SNAPSHOT", sb.toString().trimEnd())
    }

    /**
     * 写入构建的通知 Payload
     */
    fun logNotificationPayload(
        context: Context,
        notificationId: Int,
        title: String,
        content: String,
        paramJson: String
    ) {
        if (!isLoggingEnabled) return
        val logContent = buildString {
            appendLine("【准备发送超级岛通知】(id=$notificationId)")
            appendLine("Title: $title | Content: $content")
            appendLine("miui.focus.param JSON 原文:")
            appendLine(paramJson)
        }
        log(context, "NOTIFY_BUILD", logContent.trimEnd())
    }

    /**
     * 记录断网事务起止时序
     */
    fun logBypassStart(context: Context, durationMs: Long, xmsfUid: Int, success: Boolean) {
        if (!isLoggingEnabled) return
        val now = System.currentTimeMillis()
        lastBypassStartTimeMs = now
        lastBypassEndTimeMs = now + durationMs
        val msg = "XMSF 断网已生效: uid=$xmsfUid, 设定盲窗时长=${durationMs}ms, 预期保护截止至: +${durationMs}ms"
        log(context, "BYPASS_START", msg)
    }

    fun logNotifySubmitted(context: Context, id: Int) {
        if (!isLoggingEnabled) return
        val now = System.currentTimeMillis()
        lastNotifySubmitTimeMs = now
        val elapsedSinceBypass = if (lastBypassStartTimeMs > 0) now - lastBypassStartTimeMs else -1
        log(context, "NOTIFY_EXEC", "超级岛通知已提交至系统: id=$id (距断网启动 +${elapsedSinceBypass}ms)")
    }

    fun logBypassRestored(context: Context, xmsfUid: Int) {
        if (!isLoggingEnabled) return
        val now = System.currentTimeMillis()
        val totalBlockedMs = if (lastBypassStartTimeMs > 0) now - lastBypassStartTimeMs else 0
        lastBypassEndTimeMs = now
        log(context, "BYPASS_RESTORE", "XMSF 网络已恢复: uid=$xmsfUid, 实际断网驻留总时长=${totalBlockedMs}ms")
    }

    /**
     * 写入 SystemUI Hook 跨进程回传事件并进行精准时序交叉分析
     */
    fun logSystemUiEvent(context: Context, eventTag: String, details: String) {
        if (!isLoggingEnabled) return
        val now = System.currentTimeMillis()
        val timingReport = buildString {
            if (eventTag == "SYSTEM_UI_PARSE" || eventTag == "SYSTEM_UI_NOTIF_POSTED") {
                val delayFromSubmit = if (lastNotifySubmitTimeMs > 0) now - lastNotifySubmitTimeMs else -1
                val delayFromBypass = if (lastBypassStartTimeMs > 0) now - lastBypassStartTimeMs else -1
                append(" (系统分发延迟: +${delayFromSubmit}ms, 距断网: +${delayFromBypass}ms)")
                if (lastBypassEndTimeMs > 0 && now > lastBypassEndTimeMs) {
                    val overrun = now - lastBypassEndTimeMs
                    append("\n⚠️【时序倒挂警示】通知到达 SystemUI/XMSF 时，断网已在 ${overrun}ms 前提前结束！XMSF 网络已畅通，极易触发云端白名单拦截！")
                } else if (lastBypassEndTimeMs > 0) {
                    val remaining = lastBypassEndTimeMs - now
                    append("\n✅【断网保护中】当前仍在断网盲窗期内 (余量约 ${remaining}ms)，云端校验被有效阻断！")
                }
            } else if (eventTag.contains("METHOD") && details.contains("addDynamicIslandView")) {
                append("\n🎉【上岛成功】SystemUI 已成功调用 addDynamicIslandView 完成胶囊加载！")
            }
        }
        log(context, "SYSTEM_UI_HOOK", "[$eventTag] $details$timingReport")
    }

    /**
     * 异步抓取与超级岛相关的系统 Logcat 片段 (统一走 PrivilegeEngine，受全局特权总开关约束)
     */
    fun captureShizukuLogcatAsync(context: Context) {
        if (!isLoggingEnabled) return
        if (!HyperIslandHelper.isShizukuAvailable) return

        writeExecutor.execute {
            try {
                // 全局特权总开关关闭时明确提示（区别于"无日志"）
                if (!com.moting.linkgo.util.privilege.PrivilegeEngine.globalEnabled) {
                    log(context, "SHIZUKU_LOGCAT", "全局特权总开关已关闭，跳过 logcat 抓取")
                    return@execute
                }
                // 读取最近与 FocusNotification / SystemUI / xmsf 相关的系统日志
                val cmd = "logcat -d -v time -t 60 FocusNotification:V SystemUI:W xmsf:V *:S"
                val output = com.moting.linkgo.util.privilege.PrivilegeEngine.execWithOutputBlocking(cmd)

                if (!output.isNullOrBlank()) {
                    val count = output.lineSequence().count { it.isNotBlank() }
                    log(context, "SHIZUKU_LOGCAT", "捕获到 $count 条关键系统 Logcat:\n$output")
                } else {
                    log(context, "SHIZUKU_LOGCAT", "最近 60 行内未发现 FocusNotification/SystemUI 关键词日志")
                }
            } catch (e: Throwable) {
                log(context, "SHIZUKU_LOGCAT", "通过特权通道读取 logcat 失败: ${e.message}")
            }
        }
    }

    /**
     * 调用系统 Intent 分享导出的日志文件
     */
    fun shareLog(context: Context): Boolean {
        return try {
            val file = getLogFile(context)
            if (!file.exists() || file.length() == 0L) {
                return false
            }
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "LinkGo 超级岛诊断日志")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(intent, "分享超级岛诊断日志").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            true
        } catch (e: Exception) {
            Log.e(TAG, "shareLog failed", e)
            false
        }
    }

    private fun getProp(key: String): String {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getDeclaredMethod("get", String::class.java, String::class.java)
            method.invoke(null, key, "") as String
        } catch (_: Exception) {
            ""
        }
    }
}
