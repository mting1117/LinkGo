package com.moting.linkgo.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import com.moting.linkgo.data.NotificationStyle
import com.moting.linkgo.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 实时活动与跳转通知中枢（Notification Architecture 2.0）。
 *
 * 特性：
 * 1. 采用原地覆写（In-Place Update）机制：多链接汇总/手动选择 -> 具体目标跳转无缝平滑刷新；
 * 2. 基于全局协程 Job 管理自动撤回生命周期，新通知自动取消上一个撤回 Job，彻底杜绝误杀；
 * 3. 支持三种通知样式：标准通知 (Android 8.0+)、实时活动通知 (Android 16+)、小米超级岛 (HyperOS)。
 */
object NotificationHelper {
    private const val TAG = "LinkGo_Notify"
    const val CHANNEL_LINK_DISPATCH = "link_dispatch"
    const val CHANNEL_BACKGROUND_SERVICE = "background_service"
    const val CHANNEL_ID = CHANNEL_LINK_DISPATCH
    private const val NOTIFICATION_ID = 1001
    private const val DEFAULT_DISMISS_MS = 5000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var dismissJob: Job? = null

    @Volatile
    private var currentDismissMs = DEFAULT_DISMISS_MS

    /** 实时活动通知内容（对照悬浮胶囊的 icon/label/badge 语义） */
    data class LiveActivityData(
        val icon: Drawable,
        val title: String,
        val bigTitle: String? = null,
        val body: String? = null,
        val fullText: String? = null,
        val rawUrl: String? = null,
        val bigIcon: Drawable? = null,
        val contentIntent: PendingIntent? = null,
        val actionLabel: String? = null,
        val actionIconRes: Int? = null,
        val actionIntent: PendingIntent? = null
    )

    @Volatile
    private var lastData: LiveActivityData? = null

    @Volatile
    private var lastNotifyTime: Long = 0L

    @Volatile
    private var lastNotifyKey: String? = null

    @Volatile
    private var isIslandAuthWarmed = false

    fun markIslandAuthWarmed() {
        isIslandAuthWarmed = true
    }

    /**
     * 显示一条实时活动/跳转通知，并安排自动撤回。
     */
    fun show(context: Context, data: LiveActivityData): Boolean {
        val appContext = context.applicationContext
        val contentKey = data.rawUrl ?: data.fullText ?: data.body ?: ""
        val notifyKey = "$contentKey:${data.title}"
        val now = System.currentTimeMillis()

        // 判定是否属于两阶段预测的目标变更平滑刷新（例如从「手动选择」刷新为「哔哩哔哩」）
        val isTargetRefreshed = (lastData != null && lastData?.title != data.title)

        // 轻量防抖：300ms 内完全相同的标题与内容抑制瞬时抖动；连续复制或重定向刷新立即放行
        if (!isTargetRefreshed && notifyKey == lastNotifyKey && (now - lastNotifyTime) < 300L) {
            Log.w(TAG, "[SUPPRESS-KEY] 300ms 内完全相同内容 key=$notifyKey 抑制抖动")
            return false
        }

        lastNotifyTime = now
        lastNotifyKey = notifyKey
        lastData = data

        val sec = com.moting.linkgo.data.SettingsCache.notificationAutoDismissSeconds.coerceIn(2, 30)
        currentDismissMs = sec * 1000L

        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= 33 && !nm.areNotificationsEnabled()) {
            Log.w(TAG, "[PERMISSION-DENIED] 无通知权限，放弃发送")
            return false
        }
        ensureChannels(nm)

        // 1. 立即取消上一次的撤回 Job，防止旧任务倒计时结束误杀新通知
        dismissJob?.cancel()

        val style = com.moting.linkgo.data.SettingsCache.notificationStyle
        Log.w(TAG, "[NOTIFY-EXEC] 发送/刷新通知: id=$NOTIFICATION_ID, style=${style.name}, title=${data.title}, body=${data.body?.take(40)}")
        val notification = buildNotification(appContext, data, style, sec)

        if (style == NotificationStyle.SUPER_ISLAND) {
            SuperIslandLogManager.logEnvironmentSnapshot(appContext, "链接分发触发超级岛")
            val bypassEnabled = com.moting.linkgo.data.SettingsCache.superIslandBypassEnabled
            val isFirstPost = !isIslandAuthWarmed
            if (bypassEnabled) {
                val durationMs = com.moting.linkgo.data.SettingsCache.superIslandBypassDurationMs.toLong()
                HyperIslandHelper.withBypassBlocking(appContext, durationMs) {
                    nm.notify(NOTIFICATION_ID, notification)
                    SuperIslandLogManager.logNotifySubmitted(appContext, NOTIFICATION_ID)
                    if (isFirstPost) {
                        // 首次发射：为 XMSF 离线鉴权与 SystemUI 建立 SignatureUtils 缓存留出 40ms 极微小窗口，
                        // 随后原地提交一次更新，确保首帧在鉴权写入后立即由超级岛展开，杜绝降级为普通通知
                        scope.launch {
                            delay(40L)
                            try {
                                nm.notify(NOTIFICATION_ID, notification)
                                isIslandAuthWarmed = true
                                Log.i(TAG, "[ISLAND-PROMOTION] 首次焦点通知鉴权完成，原地自愈晋升成功")
                            } catch (e: Throwable) {
                                Log.w(TAG, "[ISLAND-PROMOTION] 原地自愈更新异常: ${e.message}")
                            }
                        }
                    }
                }
            } else {
                nm.notify(NOTIFICATION_ID, notification)
                SuperIslandLogManager.logNotifySubmitted(appContext, NOTIFICATION_ID)
                if (isFirstPost) {
                    scope.launch {
                        delay(40L)
                        try {
                            nm.notify(NOTIFICATION_ID, notification)
                            isIslandAuthWarmed = true
                        } catch (_: Throwable) {}
                    }
                }
            }
            SuperIslandLogManager.captureShizukuLogcatAsync(appContext)
        } else {
            nm.notify(NOTIFICATION_ID, notification)
        }

        // 2. 调度新的自动撤回任务
        scheduleDismiss(appContext, nm)
        return true
    }

    /**
     * 立即撤回（对外暴露）。
     */
    fun dismiss(context: Context) {
        val appContext = context.applicationContext
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        dismissJob?.cancel()
        doDismiss(appContext, nm)
    }

    private fun buildNotification(
        appContext: Context,
        data: LiveActivityData,
        style: NotificationStyle,
        timeoutSeconds: Int
    ): Notification {
        val icon = try {
            createIconFromDrawable(data.icon)
        } catch (e: Exception) {
            Log.w(TAG, "icon convert failed: ${e.message}")
            Icon.createWithResource(appContext, com.moting.linkgo.R.mipmap.ic_launcher)
        }

        val isNight = (appContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val titleColor = if (isNight) Color.WHITE else 0xFF1F1F1F.toInt()
        val textColor = if (isNight) 0xFFD0D0D0.toInt() else 0xFF444746.toInt()

        val rawBigTitle = data.bigTitle ?: data.title
        val bigTitleSpanned = SpannableStringBuilder(rawBigTitle).apply {
            setSpan(ForegroundColorSpan(titleColor), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        val rawFullText = data.fullText ?: data.body ?: data.title
        val fullTextSpanned = SpannableStringBuilder(rawFullText).apply {
            setSpan(ForegroundColorSpan(textColor), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        ensureChannels(appContext)
        val targetChannel = CHANNEL_LINK_DISPATCH

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(appContext, targetChannel)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(appContext)
        }
        builder.apply {
            // 加速系统跨进程分发：跳过MIUI系统底层震动与蜂鸣波形计算
            setOnlyAlertOnce(true)
            setSmallIcon(icon)
            setContentTitle(data.title)
            data.body?.let { setContentText(it) }

            // 全局设置 large icon：提供给系统通知右侧、拖拽分享阴影与流转中心使用
            setLargeIcon(drawableToBitmap(data.icon))

            if (style != NotificationStyle.SUPER_ISLAND) {
                // 展开面板：原生 BigTextStyle（纯粹黑白字色大标题 + 全文）
                setStyle(
                    Notification.BigTextStyle()
                        .setBigContentTitle(bigTitleSpanned)
                        .bigText(fullTextSpanned)
                )
            }

            data.contentIntent?.let { setContentIntent(it) }

            // 1. 快捷按钮 1：【复制链接 / 复制全部 / 复制内容】
            val copyUrl = data.rawUrl ?: data.fullText ?: data.body
            var copyPi: PendingIntent? = null
            var copyLabel: String? = null
            if (!copyUrl.isNullOrBlank()) {
                val isLink = copyUrl.startsWith("http://") || copyUrl.startsWith("https://") || copyUrl.contains("://")
                val copyIntent = android.content.Intent(appContext, NotificationActionReceiver::class.java).apply {
                    action = NotificationActionReceiver.ACTION_COPY_URL
                    putExtra(NotificationActionReceiver.EXTRA_URL, copyUrl)
                }
                copyPi = PendingIntent.getBroadcast(
                    appContext,
                    101,
                    copyIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                copyLabel = when {
                    !isLink -> "复制内容"
                    copyUrl.contains("\n") -> "复制全部"
                    else -> "复制链接"
                }
                addAction(android.R.drawable.ic_menu_save, copyLabel, copyPi)
            }

            // 2. 快捷按钮 2：【打开跳转】
            val jumpIntent = data.actionIntent ?: data.contentIntent
            val jumpLabel = data.actionLabel ?: "直接跳转"
            if (jumpIntent != null) {
                val jumpIcon = data.actionIconRes ?: android.R.drawable.ic_menu_send
                addAction(jumpIcon, jumpLabel, jumpIntent)
            }

            // Android 16 实时活动与小米超级岛使用 ongoing 常驻通知：
            // 1. 超级岛自带独立生命周期 (islandTimeout 与 scheduleDismiss 自动撤回)；
            // 2. 新安装无鉴权缓存的首次发射瞬间，ongoing 属性使得未授权应用通知被 SystemUI 静默拦截，
            //    绝对不会弹出普通横幅通知！配合首帧 40ms 原地自愈更新，在鉴权写入后直接以超级岛胶囊展开弹出。
            val isOngoing = (style == NotificationStyle.LIVE_ACTIVITY || style == NotificationStyle.SUPER_ISLAND)
            setOngoing(isOngoing)
            setShowWhen(false)

            // 根据通知样式分支处理
            when (style) {
                NotificationStyle.STANDARD -> {
                    // 标准通知：无需额外协议
                    Log.d(TAG, "[NOTIFY-BUILD] 采用标准通知样式 (ongoing=false)")
                }
                NotificationStyle.LIVE_ACTIVITY -> {
                    // 安卓 16：晋升为实时活动（request，最终是否晋升由系统决定）
                    if (Build.VERSION.SDK_INT >= 36) {
                        try {
                            val m = this::class.java.getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType)
                            m.invoke(this, true)
                            Log.w(TAG, "setRequestPromotedOngoing(true) called ok")
                        } catch (e: Exception) {
                            Log.w(TAG, "setRequestPromotedOngoing failed: ${e.message}")
                        }
                    }
                }
                NotificationStyle.SUPER_ISLAND -> {
                    // 小米超级岛 (HyperOS) —— 多链接与单链接自适应文案与按钮
                    val isImageMulti = data.actionLabel == "图片分享"
                    val isMulti = data.actionLabel == "进入选择" || isImageMulti
                    val islandContent = if (isMulti) {
                        data.body ?: data.fullText ?: if (isImageMulti) "图片分享" else "检测到多条链接"
                    } else {
                        data.rawUrl ?: data.fullText ?: data.body ?: ""
                    }
                    val actualJumpLabel = data.actionLabel ?: jumpLabel ?: "直接跳转"

                    HyperIslandHelper.applySuperIslandExtras(
                        builder = this,
                        context = appContext,
                        title = data.title,
                        content = islandContent,
                        bigTitle = data.bigTitle ?: data.title,
                        specialTag = if (isImageMulti) "图片分享" else if (isMulti) "批量分发" else "链接跳转",
                        ticker = data.title,
                        islandTimeout = timeoutSeconds,
                        expandedTime = 5,
                        outerGlow = com.moting.linkgo.data.SettingsCache.superIslandOuterGlow,
                        dragShareEnabled = com.moting.linkgo.data.SettingsCache.superIslandDragShareEnabled,
                        dragShareContent = data.rawUrl ?: "",
                        copyLabel = if (isImageMulti) copyLabel else (if (isMulti) "复制全部" else copyLabel),
                        copyPendingIntent = copyPi,
                        jumpLabel = actualJumpLabel,
                        jumpPendingIntent = jumpIntent,
                        sourceIcon = icon
                    )
                }
            }
        }
        return builder.build()
    }

    private fun scheduleDismiss(appContext: Context, nm: NotificationManager) {
        dismissJob?.cancel()
        dismissJob = scope.launch {
            val seconds = runCatching {
                SettingsRepository(appContext).notificationAutoDismissSeconds.first()
            }.getOrDefault(5).coerceIn(2, 30)
            val timeoutMs = seconds * 1000L
            currentDismissMs = timeoutMs
            delay(timeoutMs)
            doDismiss(appContext, nm)
        }
    }

    /**
     * 自动撤回：直接取消通知
     */
    private fun doDismiss(appContext: Context, nm: NotificationManager) {
        Log.w(TAG, "[DISMISS-BEGIN] 执行撤回 id=$NOTIFICATION_ID at ${System.currentTimeMillis()}")

        try {
            nm.cancel(NOTIFICATION_ID)
            Log.w(TAG, "[DISMISS-SUCCESS] nm.cancel(id=$NOTIFICATION_ID) 成功执行")
        } catch (e: Exception) {
            Log.w(TAG, "[DISMISS-ERROR] cancel failed: ${e.message}")
        }

        lastData = null
        lastNotifyKey = null
        lastNotifyTime = 0L
    }

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        ensureChannels(nm)
    }

    fun ensureChannels(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val expectedChannels = setOf(
                CHANNEL_LINK_DISPATCH,
                CHANNEL_BACKGROUND_SERVICE
            )

            // 1. 白名单终态对齐：动态遍历系统已有渠道，非白名单一律物理抹除
            try {
                val existingChannels = nm.notificationChannels ?: emptyList()
                for (ch in existingChannels) {
                    if (ch.id !in expectedChannels) {
                        nm.deleteNotificationChannel(ch.id)
                        Log.i(TAG, "已清理历史非白名单通知渠道: id=${ch.id}, name=${ch.name}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "清理非白名单通知渠道失败", e)
            }

            // 2. 规范化注册/更新核心渠道：链接跳转
            val dispatchChannel = NotificationChannel(
                CHANNEL_LINK_DISPATCH,
                "链接跳转",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "展示链接识别卡片、实时活动与超级岛胶囊"
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            // 3. 规范化注册/更新核心渠道：后台常驻服务
            val serviceChannel = NotificationChannel(
                CHANNEL_BACKGROUND_SERVICE,
                "后台常驻服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保证剪贴板后台监听与快速响应服务在后台正常运行"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }

            nm.createNotificationChannels(listOf(dispatchChannel, serviceChannel))
        }
    }

    private fun createIconFromDrawable(drawable: Drawable): Icon {
        return if (drawable is BitmapDrawable) {
            Icon.createWithBitmap(drawable.bitmap)
        } else {
            Icon.createWithBitmap(drawableToBitmap(drawable))
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) return drawable.bitmap
        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }
}