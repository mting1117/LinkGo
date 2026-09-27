package com.moting.linkgo.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import com.moting.linkgo.MainActivity
import com.moting.linkgo.R
import com.moting.linkgo.data.SettingsCache
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * 小米 HyperOS (澎湃OS) 超级岛与焦点通知管理器
 * 
 * 深度适配 LinkGo 链接跳转业务：
 * 1. 摘要态：左侧目标应用图标 + 右侧规则名称（对齐实时活动通知）；
 * 2. 展开态：目标应用大图 + 规则大标题 + 链接正文摘要 + 链接跳转标签 + 双按钮（复制链接 + 直接跳转）；
 * 3. 采用纯净 Template 10 / Template 2 与 Shizuku 旁路断网原子事务。
 */
object HyperIslandHelper {

    private const val TAG = "HyperIslandHelper"
    private const val XMSF_PKG = "com.xiaomi.xmsf"
    private const val CHANNEL_ID = NotificationHelper.CHANNEL_LINK_DISPATCH
    private const val BLIND_WINDOW_MS = 100L
    private const val MAX_NETWORK_RETRIES = 2
    private const val NETWORK_RETRY_DELAY_MS = 50L
    private const val ISLAND_NOTIFICATION_ID = 20000
    private const val MAX_TEST_NOTIFICATION_ID = 20050

    private const val ACTION_KEY_COPY = "miui.focus.action_copy"
    private const val ACTION_KEY_JUMP = "miui.focus.action_jump"

    private val lastTestNotificationId = AtomicInteger(-1)
    private val nextTestNotificationId = AtomicInteger(ISLAND_NOTIFICATION_ID)

    private val stateLock = Any()
    @Volatile
    private var isNetworkBlocked = false
    @Volatile
    private var currentLeaseDeadlineMs = 0L
    @Volatile
    private var restoreJob: Job? = null

    private val bypassScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var xmsfUid: Int = -1

    // ── 设备与环境检测 ────────────────────────────────────────

    val isShizukuAvailable: Boolean
        get() = AppShell.isShizukuAvailable

    fun isXiaomiDevice(): Boolean {
        val brand = Build.BRAND.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()
        return brand in listOf("xiaomi", "redmi", "poco") ||
                manufacturer in listOf("xiaomi", "redmi", "poco")
    }

    fun isHyperOS(): Boolean {
        return getProp("ro.mi.os.version.name").isNotBlank() ||
                getProp("ro.miui.ui.version.name").isNotBlank()
    }

    fun isEligibleDevice(): Boolean = isXiaomiDevice() && isHyperOS()

    fun isSupportIsland(): Boolean {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getDeclaredMethod(
                "getBoolean",
                String::class.java,
                Boolean::class.javaPrimitiveType
            )
            method.invoke(null, "persist.sys.feature.island", false) as Boolean
        } catch (e: Exception) {
            Log.w(TAG, "isSupportIsland check failed: ${e.message}")
            false
        }
    }

    fun getFocusProtocolVersion(context: Context): Int {
        return try {
            Settings.System.getInt(
                context.contentResolver,
                "notification_focus_protocol",
                0
            )
        } catch (e: Exception) {
            Log.w(TAG, "getFocusProtocolVersion failed: ${e.message}")
            0
        }
    }

    fun hasFocusPermission(context: Context): Boolean {
        return try {
            val uri = Uri.parse("content://miui.statusbar.notification.public")
            val extras = Bundle().apply {
                putString("package", context.packageName)
            }
            val bundle = context.contentResolver.call(uri, "getFocusPermission", null, extras)
            bundle?.getInt("result", -1) == 1
        } catch (e: Exception) {
            Log.w(TAG, "hasFocusPermission check failed: ${e.message}")
            false
        }
    }

    /**
     * 探测当前通知渠道在系统设置中是否开启了「悬浮通知」
     * 小米 MIUI / HyperOS 在 NotificationChannel 内部提供了 canShowFloat() 隐藏方法；
     * 若未检测到该隐藏方法，则根据 Android 原生渠道重要性 >= IMPORTANCE_HIGH 兜底。
     */
    fun canChannelShowFloat(context: Context): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        val channel = nm.getNotificationChannel(CHANNEL_ID) ?: return false
        val canFloat = runCatching {
            val method = channel.javaClass.getMethod("canShowFloat")
            method.invoke(channel) as? Boolean
        }.getOrNull()
        return canFloat ?: (channel.importance >= NotificationManager.IMPORTANCE_HIGH)
    }

    /**
     * 超级岛焦点鉴权预热：
     * 小米 HyperOS 在新安装应用后，首次收到焦点通知时会异步向 XMSF 发送白名单鉴权（耗时约 10~20ms）。
     * 在应用启动时在后台静默触发一次鉴权探测与权限握手，提前预热 SystemUI 的 SignatureUtils 鉴权缓存，
     * 确保用户后续首次复制链接时，通知到达 SystemUI 瞬间直接命中本地鉴权缓存，实现零延迟秒弹。
     */
    fun prewarmIslandAuth(context: Context) {
        if (!isEligibleDevice()) return
        bypassScope.launch {
            try {
                // 1. 触发小米状态栏 Provider 的焦点权限探测，预热跨进程 IPC
                hasFocusPermission(context)
                // 2. 预热 XMSF UID 解析
                val uid = getXmsfUid(context)

                // 3. 若具备通知权限且开启了断网旁路，在后台静默发射一条微型探测通知提前触发 SystemUI 鉴权暖机
                val nm = context.getSystemService(NotificationManager::class.java)
                if (nm != null && (Build.VERSION.SDK_INT < 33 || nm.areNotificationsEnabled()) && uid > 0 && SettingsCache.superIslandBypassEnabled) {
                    val probeId = 9998
                    val notification = buildPrewarmProbeNotification(context)
                    withBypassBlocking(context, 400L) {
                        nm.notify(probeId, notification)
                        bypassScope.launch {
                            delay(60L)
                            runCatching { nm.cancel(probeId) }
                            NotificationHelper.markIslandAuthWarmed()
                            Log.i(TAG, "[ISLAND-PREWARM] 超级岛静默预热探测完成并撤回")
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "prewarmIslandAuth error: ${e.message}")
            }
        }
    }

    private fun buildPrewarmProbeNotification(context: Context): Notification {
        ensureChannels(context)
        val channelId = NotificationHelper.CHANNEL_LINK_DISPATCH
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        val icon = Icon.createWithResource(context, R.mipmap.ic_launcher)
        builder.apply {
            setOnlyAlertOnce(true)
            setSmallIcon(icon)
            setContentTitle("LinkGo")
            setOngoing(true)
            setShowWhen(false)
            val paramJson = JSONObject().apply {
                put("param_v2", JSONObject().apply {
                    put("protocol", 1)
                    put("enableFloat", false)
                    put("islandFirstFloat", false)
                    put("isShowNotification", false)
                    put("timeout", 1)
                })
            }
            addExtras(Bundle().apply {
                putString("miui.focus.param", paramJson.toString())
            })
        }
        return builder.build()
    }

    fun getXmsfUid(context: Context): Int {
        if (xmsfUid > 0) return xmsfUid
        xmsfUid = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageUid(
                    XMSF_PKG,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageUid(XMSF_PKG, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get xmsf UID: ${e.message}")
            -1
        }
        return xmsfUid
    }

    // ── XMSF 离线放行租约顺延防火墙逻辑 (Lease-Extension Bypass Engine) ──────

    private fun acquireBypassLease(context: Context, durationMs: Long): Boolean {
        val uid = getXmsfUid(context)
        if (uid <= 0) return false

        synchronized(stateLock) {
            val now = System.currentTimeMillis()
            val newDeadline = now + durationMs.coerceAtLeast(10L)
            currentLeaseDeadlineMs = newDeadline

            // 取消上一个待执行的恢复 Job，准备重新调度续期
            restoreJob?.cancel()

            if (!isNetworkBlocked) {
                val success = AppShell.blockUidNetworking(uid)
                isNetworkBlocked = success
                SuperIslandLogManager.logBypassStart(
                    context = context,
                    durationMs = durationMs,
                    xmsfUid = uid,
                    success = success
                )
            } else {
                // 已经在断网盲窗保护期内，无缝顺延保护期，无需重复调用底层系统防火墙
                SuperIslandLogManager.log(
                    context = context,
                    tag = "BYPASS_EXTEND",
                    message = "XMSF 断网保护期无缝顺延 +${durationMs}ms (保护截止至: +${newDeadline - now}ms)"
                )
            }

            // 调度到期恢复 Job (基于非阻塞协程 delay)
            restoreJob = bypassScope.launch {
                delay(durationMs.coerceAtLeast(10L))
                synchronized(stateLock) {
                    if (System.currentTimeMillis() >= currentLeaseDeadlineMs && isNetworkBlocked) {
                        var restored = false
                        for (attempt in 0..MAX_NETWORK_RETRIES) {
                            if (AppShell.restoreUidNetworking(uid)) {
                                restored = true
                                break
                            }
                        }
                        if (restored) {
                            isNetworkBlocked = false
                            SuperIslandLogManager.logBypassRestored(context, uid)
                        } else {
                            Log.e(TAG, "Failed to restore XMSF networking for uid=$uid")
                        }
                    }
                }
            }
            return isNetworkBlocked
        }
    }

    /**
     * 单独执行旁路断网（无通知发送联动）
     */
    fun executeBypass(context: Context, durationMs: Long = BLIND_WINDOW_MS) {
        if (!SettingsCache.superIslandBypassEnabled) return
        bypassScope.launch {
            acquireBypassLease(context, durationMs)
        }
    }

    /**
     * 关键断网事务：在断网租约保护下原子发送通知。
     * 1. 若开启断网旁路，在当前调用中确保断网生效（已生效则立即顺延）；
     * 2. 物理零延迟立即在调用方上下文执行 block() 发射通知；
     * 3. 彻底消除旧架构中 Mutex 串行持锁挂起 delay 造成的第二帧堵塞与时序倒挂。
     */
    fun withBypassBlocking(
        context: Context,
        durationMs: Long = BLIND_WINDOW_MS,
        block: () -> Unit
    ) {
        if (!SettingsCache.superIslandBypassEnabled) {
            block()
            return
        }

        val uid = getXmsfUid(context)
        if (uid <= 0) {
            block()
            return
        }

        // 获取或顺延断网租约
        acquireBypassLease(context, durationMs)
        // 断网确立后零物理间隔立即发射通知
        try {
            block()
        } catch (e: Throwable) {
            Log.e(TAG, "Error executing island notification block", e)
        }
    }

    // ── 通知渠道管理 ──────────────────────────────────────────
    fun ensureChannels(context: Context) {
        NotificationHelper.ensureChannels(context)
    }

    // ── 超级岛通知参数组装 (LinkGo 链接跳转双按钮 + 摘要态规则名) ──

    fun applySuperIslandExtras(
        builder: Notification.Builder,
        context: Context,
        title: String,
        content: String,
        bigTitle: String? = null,
        specialTag: String? = "链接跳转",
        ticker: String = "",
        ongoing: Boolean = false,
        updatable: Boolean = true,
        islandTimeout: Int = 60,
        expandedTime: Int = 5,
        outerGlow: Boolean = false,
        dragShareEnabled: Boolean = true,
        dragShareContent: String = "",
        copyLabel: String? = null,
        copyPendingIntent: PendingIntent? = null,
        jumpLabel: String? = null,
        jumpPendingIntent: PendingIntent? = null,
        sourceIcon: Icon? = null
    ) {
        try {
            // 加速系统分发：跳过MIUI底层复杂的NotificationVibratorHelper震动波形运算
            builder.setOnlyAlertOnce(true)
            ensureChannels(context)

            val displayTicker = ticker.ifBlank { "LinkGo · $title" }
            val appIcon = sourceIcon ?: Icon.createWithResource(context, R.mipmap.ic_launcher)

            // 1. 图标 Bundle（目标应用图标 + LinkGo 应用标识）
            val appIconKey = "key_app_icon"
            val tickerPicKey = "key_ticker_pic"
            val linkgoLogoKey = "key_linkgo_logo"
            val linkgoIcon = Icon.createWithResource(context, R.mipmap.ic_launcher)

            val picsBundle = Bundle().apply {
                putParcelable(appIconKey, appIcon)
                putParcelable(tickerPicKey, appIcon)
                putParcelable(linkgoLogoKey, linkgoIcon)
            }

            // 2. 展开态：标准图文组件 (iconTextInfo) —— 左侧展示目标应用大图标
            // 主标题展示规则名，副标题展示完整 URL 链接，支持自然开阔排版
            val displayMainTitle = (bigTitle ?: title).trim()
            val displayContent = content.trim()

            val iconTextInfo = JSONObject().apply {
                put("title", displayMainTitle)
                put("content", displayContent.ifEmpty { " " })
                put("animIconInfo", JSONObject().apply {
                    put("type", 0)
                    put("src", appIconKey)
                    put("loop", false)
                    put("autoplay", false)
                })
            }

            // 3. 展开态：右上角应用标识小图标 (picInfo) —— 官方模版18规范
            val topRightPicInfo = JSONObject().apply {
                put("type", 1)
                put("pic", linkgoLogoKey)
                put("picDark", linkgoLogoKey)
            }

            // 3. 展开态：双按钮组件 (textButton) —— 1:1 对齐 InstallerX
            val mainJumpLabel = jumpLabel ?: "直接跳转"
            val textButtonArray = JSONArray().apply {
                if (copyPendingIntent != null) {
                    put(JSONObject().apply {
                        put("type", 2)
                        put("action", ACTION_KEY_COPY)
                        put("actionTitle", copyLabel ?: "复制链接")
                        put("actionIntentType", 1)
                    })
                }
                if (jumpPendingIntent != null) {
                    put(JSONObject().apply {
                        put("type", 2)
                        put("action", ACTION_KEY_JUMP)
                        put("actionTitle", mainJumpLabel)
                        put("actionBgColor", "#006EFF")
                        put("actionBgColorDark", "#006EFF")
                        put("actionTitleColor", "#FFFFFF")
                        put("actionTitleColorDark", "#FFFFFF")
                        put("actionIntentType", 1)
                    })
                }
            }

            // 4. 超级岛摘要态胶囊 (param_island)
            val smallIslandArea = JSONObject().apply {
                put("picInfo", JSONObject().apply {
                    put("type", 1)
                    put("pic", appIconKey)
                })
            }

            val imageTextInfoLeft = JSONObject().apply {
                put("type", 1) // 1 = 左边组件（目标应用图标）
                put("picInfo", JSONObject().apply {
                    put("type", 1)
                    put("pic", appIconKey)
                })
            }

            // 摘要态右侧：规则名称（对齐 InstallerX 官方标准纯文本组件 type=3，仅显示规则名称或应用名）
            val cleanCapsuleTitle = if (title.startsWith("由 ") && title.endsWith(" 分发")) {
                title.removePrefix("由 ").removeSuffix(" 分发").trim()
            } else {
                title.trim()
            }
            val capsuleRuleTitle = cleanCapsuleTitle + "\u2009"
            val imageTextInfoRight = JSONObject().apply {
                put("type", 3)
                put("textInfo", JSONObject().apply {
                    put("title", capsuleRuleTitle)
                })
            }

            val bigIslandArea = JSONObject().apply {
                put("imageTextInfoLeft", imageTextInfoLeft)
                put("imageTextInfoRight", imageTextInfoRight)
            }

            // 探测系统通知类别是否开启了「悬浮通知」：开启则允许首帧展开大岛卡片，关闭则静默保持小胶囊
            val canFloat = canChannelShowFloat(context)
            val effectiveExpandedTime = if (canFloat) (if (expandedTime > 0) expandedTime else 5) else 0

            val paramIsland = JSONObject().apply {
                put("islandProperty", 1)
                put("islandPriority", 2)
                put("islandTimeout", islandTimeout)
                put("dismissIsland", false)
                put("expandedTime", effectiveExpandedTime)
                put("maxSize", false)
                put("needCloseAnimation", true)
                put("bigIslandArea", bigIslandArea)
                put("smallIslandArea", smallIslandArea)

                val shareUrl = dragShareContent.ifBlank { content }
                if (dragShareEnabled && shareUrl.isNotBlank()) {
                    // 建议1：【规则名称】链接地址 —— 100% 保持纯文本填入微信输入框，防卡片化
                    val combinedShareContent = if (displayMainTitle.isNotBlank() && !shareUrl.startsWith("【$displayMainTitle】")) {
                        "【$displayMainTitle】$shareUrl"
                    } else {
                        shareUrl
                    }
                    put("shareData", JSONObject().apply {
                        put("title", displayMainTitle.take(32))
                        put("content", displayContent.take(80))
                        put("shareContent", combinedShareContent.take(500))
                    })
                }
            }

            // 5. 主 param_v2 (enableFloat 与 islandFirstFloat 跟随系统悬浮通知开关)
            val systemProtocol = getFocusProtocolVersion(context).let { if (it > 0) it else 1 }
            val paramV2 = JSONObject().apply {
                put("protocol", systemProtocol)
                put("enableFloat", canFloat)
                put("updatable", updatable)
                put("islandFirstFloat", canFloat)
                put("ticker", displayTicker)
                put("tickerPic", tickerPicKey)
                if (outerGlow) {
                    put("outEffectSrc", "outer_glow")
                }
                put("isShowNotification", true)
                put("timeout", islandTimeout)
                put("iconTextInfo", iconTextInfo)
                put("picInfo", topRightPicInfo)
                if (textButtonArray.length() > 0) {
                    put("textButton", textButtonArray)
                }
                put("param_island", paramIsland)
            }

            val param = JSONObject().apply {
                put("param_v2", paramV2)
            }

            // 6. 组装 extras（挂载 Action，必须带有有效 Icon 以保证 SystemUI 响应点击）
            val extras = Bundle().apply {
                putString("miui.focus.param", param.toString())
                putBundle("miui.focus.pics", picsBundle)

                val actionsBundle = Bundle()
                if (copyPendingIntent != null) {
                    actionsBundle.putParcelable(
                        ACTION_KEY_COPY,
                        Notification.Action.Builder(
                            appIcon,
                            copyLabel ?: "复制链接",
                            copyPendingIntent
                        ).build()
                    )
                }
                if (jumpPendingIntent != null) {
                    actionsBundle.putParcelable(
                        ACTION_KEY_JUMP,
                        Notification.Action.Builder(
                            appIcon,
                            mainJumpLabel,
                            jumpPendingIntent
                        ).build()
                    )
                }
                if (actionsBundle.size() > 0) {
                    putBundle("miui.focus.actions", actionsBundle)
                }
            }

            builder.addExtras(extras)
            Log.i(TAG, "成功注入超级岛双按钮参数: title=$title, glow=$outerGlow")
            SuperIslandLogManager.logNotificationPayload(
                context = context,
                notificationId = -1,
                title = title,
                content = content,
                paramJson = param.toString()
            )
        } catch (e: Exception) {
            Log.e(TAG, "注入超级岛参数异常: ${e.message}", e)
            SuperIslandLogManager.log(context, "EXTRA_BUILD_ERROR", "注入超级岛参数异常: ${e.message}", e)
        }
    }

    // ── 完整构建超级岛 Notification ──────────────────────────

    fun buildIslandNotification(
        context: Context,
        title: String,
        content: String,
        bigTitle: String? = null,
        specialTag: String? = "链接跳转",
        ticker: String = "",
        ongoing: Boolean = false,
        updatable: Boolean = true,
        islandTimeout: Int = 60,
        expandedTime: Int = 5,
        outerGlow: Boolean = false,
        dragShareEnabled: Boolean = true,
        dragShareContent: String = "",
        copyLabel: String? = null,
        copyPendingIntent: PendingIntent? = null,
        jumpLabel: String? = "直接跳转",
        jumpPendingIntent: PendingIntent? = null,
        contentPendingIntent: PendingIntent? = null,
        sourceIcon: Icon? = null
    ): Notification {
        ensureChannels(context)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        builder.apply {
            setSmallIcon(R.drawable.ic_notification)
            setContentTitle(title)
            setContentText(content)
            setOngoing(ongoing)
            setAutoCancel(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setTimeoutAfter(islandTimeout * 1000L)
            }
            if (contentPendingIntent != null) {
                setContentIntent(contentPendingIntent)
            }
        }

        applySuperIslandExtras(
            builder = builder,
            context = context,
            title = title,
            content = content,
            bigTitle = bigTitle,
            specialTag = specialTag,
            ticker = ticker,
            ongoing = ongoing,
            updatable = updatable,
            islandTimeout = islandTimeout,
            expandedTime = expandedTime,
            outerGlow = outerGlow,
            dragShareEnabled = dragShareEnabled,
            dragShareContent = dragShareContent,
            copyLabel = copyLabel,
            copyPendingIntent = copyPendingIntent,
            jumpLabel = jumpLabel,
            jumpPendingIntent = jumpPendingIntent,
            sourceIcon = sourceIcon
        )

        return builder.build()
    }

    // ── 发送测试超级岛通知 (双按钮完整示例) ───────────────────

    fun sendTestIslandNotification(context: Context): String {
        SuperIslandLogManager.logEnvironmentSnapshot(context, "测试超级岛通知")
        val bypassEnabled = SettingsCache.superIslandBypassEnabled
        if (bypassEnabled && !AppShell.isShizukuAvailable) {
            SuperIslandLogManager.log(context, "TEST_NOTIFY", "Shizuku 未连接，测试终止")
            return "Shizuku 未连接，请先授权 Shizuku"
        }

        val uid = if (bypassEnabled) getXmsfUid(context) else -1
        if (bypassEnabled && uid == -1) {
            SuperIslandLogManager.log(context, "TEST_NOTIFY", "无法获取 xmsf UID，测试终止")
            return "无法获取 xmsf UID"
        }

        val notificationId = nextTestNotificationId.updateAndGet { current ->
            if (current >= MAX_TEST_NOTIFICATION_ID) ISLAND_NOTIFICATION_ID else current + 1
        }

        return try {
            val durationMs = SettingsCache.superIslandBypassDurationMs.toLong()
            withBypassBlocking(context, durationMs) {
                val nm = context.getSystemService(NotificationManager::class.java)
                lastTestNotificationId.getAndSet(notificationId)
                    .takeIf { it != -1 && it != notificationId }
                    ?.let(nm::cancel)

                val jumpIntent = PendingIntent.getActivity(
                    context,
                    notificationId,
                    Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )

                val testCopyUrl = "https://example.com/test"
                val copyIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                    action = NotificationActionReceiver.ACTION_COPY_URL
                    putExtra(NotificationActionReceiver.EXTRA_URL, testCopyUrl)
                }
                val copyPi = PendingIntent.getBroadcast(
                    context,
                    notificationId + 1000,
                    copyIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val notification = buildIslandNotification(
                    context = context,
                    title = "测试",
                    content = testCopyUrl,
                    bigTitle = "测试",
                    specialTag = "链接跳转",
                    ticker = "LinkGo · 规则测试",
                    ongoing = false,
                    islandTimeout = SettingsCache.notificationAutoDismissSeconds.coerceIn(2, 60),
                    expandedTime = 5,
                    outerGlow = SettingsCache.superIslandOuterGlow,
                    dragShareEnabled = SettingsCache.superIslandDragShareEnabled,
                    copyLabel = "复制链接",
                    copyPendingIntent = copyPi,
                    jumpLabel = "直接跳转",
                    jumpPendingIntent = jumpIntent,
                    contentPendingIntent = jumpIntent
                )

                nm.notify(notificationId, notification)
                Log.i(TAG, "Test island notification sent with fresh id=$notificationId")
                SuperIslandLogManager.logNotifySubmitted(context, notificationId)
                SuperIslandLogManager.captureShizukuLogcatAsync(context)
            }

            "超级岛双按钮通知已发送 (id=$notificationId)"
        } catch (e: Exception) {
            Log.e(TAG, "sendTestIslandNotification failed", e)
            SuperIslandLogManager.log(context, "TEST_NOTIFY_ERROR", "sendTestIslandNotification 异常: ${e.message}", e)
            "发送失败: ${e.message}"
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
