package com.moting.linkgo.clipboard

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.moting.linkgo.BrowserSelectorActivity
import com.moting.linkgo.ImageSelectorActivity
import com.moting.linkgo.LinkDispatcherActivity
import com.moting.linkgo.LinkSelectionActivity
import com.moting.linkgo.R
import com.moting.linkgo.data.SettingsRepository
import com.moting.linkgo.image.ClipPayload
import com.moting.linkgo.image.ImageRouter
import com.moting.linkgo.image.ImageStore
import com.moting.linkgo.overlay.ClipboardCapsuleOverlay
import com.moting.linkgo.util.NotificationActionReceiver
import com.moting.linkgo.util.NotificationHelper
import com.moting.linkgo.util.SuperIslandLogManager
import com.moting.linkgo.util.UrlUtils
import com.moting.linkgo.util.WindowRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 剪贴板文本的统一处理链路。
 *
 * 前台服务（Shizuku/Root/不处理）和 LSPosed 广播接收器（ClipboardTextReceiver）
 * 检测到剪贴板变化后，都把文本交给这里：去重 → 提取 URL → 悬浮胶囊反馈 → 点击跳转。
 * 这样「分发」逻辑只有一份，不会随检测后端增多而重复。
 */
object ClipboardHandler {

    private const val TAG = "LinkGo_Clip"
    private const val MICRO_DEBOUNCE_WINDOW_MS = 50L

    /**
     * 图片去重窗口。比文本的 50ms 宽得多：图片落盘 + 解码比文本慢一个数量级，
     * 且同一次复制可能被公开监听器与后端检测各感知一次，需要更大的兜底窗口收敛为一次。
     */
    private const val IMAGE_DEBOUNCE_WINDOW_MS = 2000L


    private var capsuleOverlay: ClipboardCapsuleOverlay? = null
    private var lastText: String? = null
    private var lastTime: Long = 0L
    private var lastImageFingerprint: String? = null
    private var lastImageTime: Long = 0L
    private var debounceJob: Job? = null
    private var suppressClearJob: Job? = null

    /**
     * 统一协程作用域：所有内部异步任务（防抖、深度解析、点击跳转、抑制复位）
     * 全部挂载于此作用域，避免每次操作新建临时作用域导致的“任务不可取消、随进程长存”。
     * [release] 时整体 cancel 并重建，确保停止监听后不再残留后台任务。
     */
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * 剪贴板复制触发的跳转置位标记：检测（复制）时已发过实时通知，
     * 之后的 executeLaunchFlow 跳转执行时不再重复发（由 WindowRouter 消费并复位）。
     */
    @Volatile
    var suppressJumpNotification: Boolean = false

    /**
     * 处理一次剪贴板文本（可能在任意线程调用，内部切主线程延时防抖）。
     */
    fun handle(context: Context, text: String) {
        if (text.isBlank()) return
        ClipboardChangePublisher.publish(context, text)
        val now = System.currentTimeMillis()
        if (text == lastText && (now - lastTime) < MICRO_DEBOUNCE_WINDOW_MS) {
            Log.w(TAG, "[CLIP-SUPPRESSED] 重复剪贴板文本在 50ms 微防抖窗口内被拦截: len=${text.length}")
            SuperIslandLogManager.log(context, "CLIP_SUPPRESSED", "50ms微防抖拦截相同文本(len=${text.length}): ${text.take(30)}")
            return
        }
        lastText = text
        lastTime = now

        Log.w(TAG, "[CLIP-RECEIVED] 捕获到剪贴板变动: len=${text.length}, textPrefix=${text.take(30)}")
        if (com.moting.linkgo.data.SettingsCache.clipboardChangeToastEnabled) {
            com.moting.linkgo.util.InstantToastHelper.showCopied(context)
        }
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(30)
            process(context.applicationContext, text)
        }
    }

    /**
     * LSPosed 广播专用直接挂起处理入口：不走 delay 防抖，立即在当前挂起上下文完成 URL 提取与首帧胶囊挂载。
     */
    suspend fun handleDirect(context: Context, text: String, isClipboardChange: Boolean = true) {
        if (text.isBlank()) return
        ClipboardChangePublisher.publish(context, text)
        val now = System.currentTimeMillis()
        if (text == lastText && (now - lastTime) < MICRO_DEBOUNCE_WINDOW_MS) {
            Log.w(TAG, "[CLIP-SUPPRESSED-DIRECT] 重复剪贴板文本在 50ms 微防抖窗口内被拦截: len=${text.length}")
            SuperIslandLogManager.log(context, "CLIP_SUPPRESSED", "50ms微防抖拦截相同文本(LSP通道)(len=${text.length}): ${text.take(30)}")
            return
        }
        lastText = text
        lastTime = now

        Log.i(TAG, "[CLIP-RECEIVED-DIRECT] 直接同步处理剪贴板文本 (LSP广播通道): len=${text.length}, textPrefix=${text.take(30)}")
        if (isClipboardChange && com.moting.linkgo.data.SettingsCache.clipboardChangeToastEnabled) {
            com.moting.linkgo.util.InstantToastHelper.showCopied(context)
        }
        process(context.applicationContext, text)
    }

    /**
     * 屏幕二维码专用入口：内容当作一段文本，走与剪贴板**相同**的链接提取链路，
     * 但**略过胶囊确认，直接决定去向**。
     *
     * 为什么略过胶囊：用户是主动点/框住那块二维码的，意图已经明确——
     * 这与 [handleCapturedImage]（屏幕取图）同属"明确动作"，不该再要一次点击确认，
     * 正是图片那条链路既定的原则。
     *
     * 去向与"屏幕识别命中链接"完全一致：
     * - 提取到 1 条 → 直达（[WindowRouter.openBrowser]，内部含规则匹配与选择器回落）
     * - 提取到 N 条 → [LinkSelectionActivity] 让用户挑，不由我们替他选一条
     * - 0 条 → 静默，与"复制了一段没有链接的文本"完全相同
     *
     * 对外广播同样不发：[ClipboardChangePublisher] 的契约是"剪贴板内容发生了变动"，
     * 而这条内容来自屏幕，剪贴板根本没有动。
     */
    fun handleQrCode(context: Context, content: String) {
        if (content.isBlank()) return
        Log.w(TAG, "[QR-RECEIVED] 屏幕二维码内容进入链接提取: len=${content.length}, textPrefix=${content.take(30)}")
        SuperIslandLogManager.log(
            context,
            "QR_RECOGNIZED",
            "屏幕二维码已识别(len=${content.length}): ${content.take(30)}"
        )
        // 提示与剪贴板共用同一个开关，不为一条提示单立设置项
        if (com.moting.linkgo.data.SettingsCache.clipboardChangeToastEnabled) {
            com.moting.linkgo.util.InstantToastHelper.show(context, "已识别二维码内容")
        }
        scope.launch {
            val urls = extractUrls(content)
            if (urls.isEmpty()) {
                Log.w(TAG, "[QR-URL-NONE] 二维码内容里没有可跳转的链接, 原始内容: ${content.take(80)}")
                return@launch
            }
            val appContext = context.applicationContext
            if (urls.size == 1) {
                Log.i(TAG, "[QR-DIRECT] 二维码直达: ${urls.first().take(60)}")
                WindowRouter.openBrowser(appContext, urls.first())
            } else {
                Log.i(TAG, "[QR-SELECT] 二维码提取到 ${urls.size} 条链接，交给选择页: ${urls.first().take(60)}")
                appContext.startActivity(Intent(appContext, LinkSelectionActivity::class.java).apply {
                    putStringArrayListExtra("URLS", ArrayList(urls))
                    putExtra("SOURCE", WindowRouter.DispatchSource.SMART.name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        }
    }

    /**
     * 文本 → 链接列表。
     *
     * 剪贴板文本与屏幕二维码**共用这一条提取链路**：同一个"链接提取/规范化"开关、
     * 同一套用户自定义 ExtractPattern。抽成一处，是为了让两条入口的语义不会各自漂移。
     */
    private suspend fun extractUrls(text: String): List<String> = withContext(Dispatchers.Default) {
        val isNormEnabled = com.moting.linkgo.data.SettingsCache.normalizationEnabled
        val urls = if (isNormEnabled) {
            val patterns = com.moting.linkgo.data.SettingsCache.extractionPatterns
            UrlUtils.extractAllUrls(text, patterns)
        } else {
            listOf(text)
        }
        urls.distinct()
    }

    private suspend fun process(appContext: Context, text: String) {
        val distinctUrls = extractUrls(text)
        if (distinctUrls.isEmpty()) {
            Log.w(TAG, "[URL-NONE] 未在剪贴板文本中提取到有效链接, 原始内容: ${text.take(80)}")
            return
        }

        Log.i(TAG, "[URL-EXTRACTED] 成功提取到 ${distinctUrls.size} 条有效链接: ${distinctUrls.first().take(60)}")
        if (distinctUrls.size == 1) {
            showSingleUrlCapsule(appContext, distinctUrls.first())
        } else {
            showMultiUrlCapsule(appContext, distinctUrls)
        }
    }

    private fun overlay(appContext: Context): ClipboardCapsuleOverlay {
        var o = capsuleOverlay
        if (o == null) {
            o = ClipboardCapsuleOverlay(appContext)
            capsuleOverlay = o
        }
        return o
    }

    /**
     * 检测到剪贴板复制：置位抑制标记（有 60s 兜底清除），避免跳转执行时重复发通知。
     */
    private fun markJumpNotificationSuppressed() {
        suppressJumpNotification = true
        suppressClearJob?.cancel()
        suppressClearJob = scope.launch {
            delay(60_000)
            suppressJumpNotification = false
        }
    }

    /**
     * 单链接：两阶段极速秒弹架构。
     * 阶段 1：纯本地规则匹配（0ms 网络阻塞），< 50ms 立即秒弹首帧胶囊与通知；
     * 阶段 2：后台异步协程深度解析短链，若目标变更则原地平滑刷新。
     */
    private suspend fun showSingleUrlCapsule(appContext: Context, url: String) {
        try {
            // 1. 首帧秒弹：纯本地极速预测
            val fastPredicted = WindowRouter.predictTargetFastLocal(appContext, url)
            val fastIcon = WindowRouter.resolvePredictedIcon(appContext, fastPredicted)
            val isFastReDispatch = fastPredicted.isReDispatch
            val fastManual = fastPredicted.packageName.isNullOrBlank() && !isFastReDispatch
            val fastLabel = when {
                isFastReDispatch -> "由 ${fastPredicted.label} 分发"
                fastManual -> "手动选择"
                else -> fastPredicted.label
            }
            val fastNotifyTitle = when {
                fastManual -> "手动选择"
                else -> fastPredicted.label
            }

            Log.i(TAG, "[CAPSULE-STEP1] 本地首帧预测: label=$fastLabel, notifyTitle=$fastNotifyTitle, manual=$fastManual, rawUrl=${url.take(40)}")

            val showRich = com.moting.linkgo.data.SettingsCache.showRichNotification

            val updateNotification = { targetIcon: Drawable?, targetLabel: String, isManual: Boolean ->
                if (showRich) {
                    runCatching {
                        val fallbackIcon = appContext.applicationInfo.loadIcon(appContext.packageManager)
                        val safeIcon = targetIcon ?: fallbackIcon
                        val dropdownPi = if (isManual) {
                            PendingIntent.getActivity(
                                appContext, 10,
                                Intent(appContext, BrowserSelectorActivity::class.java).apply {
                                    putExtra("URL", url)
                                    putExtra("FROM_NOTIFICATION", true)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                },
                                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                            )
                        } else {
                            PendingIntent.getActivity(
                                appContext, 10,
                                Intent(appContext, LinkDispatcherActivity::class.java).apply {
                                    action = Intent.ACTION_VIEW
                                    data = android.net.Uri.parse(url)
                                    putExtra("FROM_NOTIFICATION", true)
                                    putExtra("FORCE_WINDOW_MODE", 5) // 5 = 小窗模式（下拉小横条）
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                },
                                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                            )
                        }

                        val jumpButtonPi = if (isManual) {
                            dropdownPi
                        } else {
                            PendingIntent.getActivity(
                                appContext, 20,
                                Intent(appContext, LinkDispatcherActivity::class.java).apply {
                                    action = Intent.ACTION_VIEW
                                    data = android.net.Uri.parse(url)
                                    putExtra("FROM_NOTIFICATION", true)
                                    putExtra("FORCE_WINDOW_MODE", 1) // 1 = 全屏模式（直接跳转按钮）
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                },
                                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                            )
                        }

                        NotificationHelper.show(appContext, NotificationHelper.LiveActivityData(
                            icon = safeIcon,
                            title = targetLabel,
                            bigTitle = targetLabel,
                            body = url.take(96),
                            fullText = url,
                            rawUrl = url,
                            bigIcon = if (isManual) null else safeIcon,
                            contentIntent = dropdownPi,
                            actionLabel = if (isManual) "选择应用" else "直接跳转",
                            actionIntent = jumpButtonPi
                        ))
                    }.onFailure { Log.w(TAG, "[NOTIFY-ERROR] 实时通知发送失败: ${it.message}", it) }
                }
            }

            // 立即发射首帧通知（标题对齐其他格式：纯规则名称或目标应用名）
            updateNotification(fastIcon, fastNotifyTitle, fastManual)

            // 悬浮胶囊：检查悬浮窗权限并立即秒弹首帧
            val hasOverlay = Settings.canDrawOverlays(appContext)
            Log.i(TAG, "[CAPSULE-CHECK] 悬浮窗权限检查: Settings.canDrawOverlays=$hasOverlay")

            val capsule = if (hasOverlay) {
                overlay(appContext)
            } else {
                Log.w(TAG, "[CAPSULE-NO-PERMISSION] 缺少系统悬浮窗权限 Settings.canDrawOverlays=false，无法弹出悬浮胶囊！请在设置页通过 Root/Shizuku 授权。")
                null
            }

            if (capsule != null) {
                Log.i(TAG, "[CAPSULE-RENDER] 准备调用 capsule.show: label=$fastLabel, isManual=$fastManual")
                capsule.show(fastIcon, fastLabel, null) { isReverse ->
                    markJumpNotificationSuppressed()
                    if (com.moting.linkgo.data.SettingsCache.appLinkAskAutoFinish) {
                        com.moting.linkgo.applink.LinkIntentReceiver.finishTargetActivity(appContext, url)
                    }
                    scope.launch {
                        Log.i(TAG, "[CAPSULE-CLICK] 用户点击悬浮胶囊触发跳转: isReverse=$isReverse")
                        val result = WindowRouter.handleUrl(appContext, url, WindowRouter.DispatchSource.SMART, isReverse = isReverse)
                        if (result.status == WindowRouter.DispatchResult.NEED_SELECTOR) {
                            appContext.startActivity(Intent(appContext, BrowserSelectorActivity::class.java).apply {
                                putExtra("URL", result.finalUrl)
                                putExtra("SOURCE", WindowRouter.DispatchSource.SMART.name)
                                putExtra("TRACE_ID", result.traceId)
                                putExtra("STEP_INDEX", result.stepIndex)
                                putExtra("IS_REVERSE", isReverse)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        }
                    }
                }
                Log.i(TAG, "[CAPSULE-RENDER-OK] capsule.show 调用成功")
            }

            // 2. 后台异步协程：执行网络短链 302 探测与深度解析（挂载统一作用域，可随 release 整体取消）
            scope.launch {
                try {
                    val deepPredicted = withContext(Dispatchers.IO) {
                        WindowRouter.predictTargetDeepResolve(appContext, url)
                    }
                    if (deepPredicted != fastPredicted) {
                        val deepIcon = WindowRouter.resolvePredictedIcon(appContext, deepPredicted)
                        val isDeepReDispatch = deepPredicted.isReDispatch
                        val deepManual = deepPredicted.packageName.isNullOrBlank() && !isDeepReDispatch
                        val deepLabel = when {
                            isDeepReDispatch -> "由 ${deepPredicted.label} 分发"
                            deepManual -> "手动选择"
                            else -> deepPredicted.label
                        }
                        val deepNotifyTitle = when {
                            deepManual -> "手动选择"
                            else -> deepPredicted.label
                        }

                        Log.i(TAG, "[CAPSULE-DEEP-REFRESH] 单链接深度预测完成，原地刷新胶囊与实时活动通知: $fastLabel -> $deepLabel (notifyTitle=$deepNotifyTitle)")
                        SuperIslandLogManager.log(appContext, "URL_RESOLVE_UPDATE", "短链302深度解析完成，触发次帧平滑更新: $fastLabel -> $deepLabel")
                        capsule?.updateFirstItemTarget(deepIcon, deepLabel)
                        updateNotification(deepIcon, deepNotifyTitle, deepManual)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "[CAPSULE-DEEP-ERROR] 后台深度解析任务异常: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[CAPSULE-ERROR] showSingleUrlCapsule 发生异常: ${e.message}", e)
        }
    }

    /**
     * 多链接：弹胶囊「由 链接选择 打开」+ 数量角标；点击才打开选择页。
     */
    private suspend fun showMultiUrlCapsule(appContext: Context, urls: List<String>) {
        // 实时活动通知：多链接 → 链接选择（参照悬浮胶囊，且不依赖悬浮窗权限，支持 Android 8.0+ 全版本）
        val showRich = com.moting.linkgo.data.SettingsCache.showRichNotification
        if (showRich && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                val notifyIcon = appContext.getDrawable(R.drawable.ic_capsule_multi)
                    ?: appContext.applicationInfo.loadIcon(appContext.packageManager)
                val reqCode = (System.currentTimeMillis() and 0xFFFF).toInt()
                val contentIntent = PendingIntent.getActivity(
                    appContext, reqCode,
                    Intent(appContext, LinkSelectionActivity::class.java).apply {
                        data = android.net.Uri.parse("linkgo://selection/${System.currentTimeMillis()}")
                        putStringArrayListExtra("URLS", ArrayList(urls))
                        putExtra("SOURCE", WindowRouter.DispatchSource.SMART.name)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                // 结构化展开排版：优雅展示前 3 条链接清单与剩余条数
                val maxDisplay = 3
                val displayUrls = urls.take(maxDisplay)
                val remaining = urls.size - maxDisplay
                val sb = StringBuilder()
                displayUrls.forEachIndexed { index, u ->
                    val prefix = when(index) {
                        0 -> "① "
                        1 -> "② "
                        2 -> "③ "
                        3 -> "④ "
                        else -> "${index + 1}. "
                    }
                    val cleanUrl = u.trim().replace("\n", "").replace("\r", "")
                    sb.append(prefix).append(cleanUrl)
                    if (index < displayUrls.size - 1 || remaining > 0) {
                        sb.append("\n")
                    }
                }
                if (remaining > 0) {
                    sb.append("… 以及其他 $remaining 条链接")
                }
                val fullContent = sb.toString()
                val allUrlsRaw = urls.joinToString("\n")
                NotificationHelper.show(appContext, NotificationHelper.LiveActivityData(
                    icon = notifyIcon,
                    title = "链接选择",
                    bigTitle = "链接选择",
                    body = "检测到 ${urls.size} 条链接",
                    fullText = "检测到 ${urls.size} 条链接",
                    rawUrl = allUrlsRaw,
                    contentIntent = contentIntent,
                    actionLabel = "进入选择",
                    actionIntent = contentIntent
                ))
            }.onFailure { Log.w(TAG, "[NOTIFY-ERROR] multi live notification failed: ${it.message}") }
        }

        if (!Settings.canDrawOverlays(appContext)) {
            Log.w(TAG, "[CAPSULE-SKIP] 缺少悬浮窗权限，跳过多链接胶囊展示")
            return
        }

        val maxCount = withContext(Dispatchers.IO) {
            runCatching { SettingsRepository(appContext).maxMultiCapsuleCount.first() }.getOrDefault(3).coerceIn(1, 10)
        }

        val multiIcon = appContext.getDrawable(R.drawable.ic_capsule_multi)
            ?: appContext.applicationInfo.loadIcon(appContext.packageManager)
        val badge = if (urls.size > 9) "9+" else urls.size.toString()
        val capsule = overlay(appContext)

        val summaryItem = ClipboardCapsuleOverlay.CapsuleItemData(
            icon = multiIcon,
            label = "链接选择",
            badge = badge,
            onTap = { _ ->
                capsule.dismiss()
                NotificationHelper.dismiss(appContext)
                Log.w(TAG, "[CAPSULE-CLICK] 点击多链接汇总胶囊，打开链接选择界面")
                appContext.startActivity(Intent(appContext, LinkSelectionActivity::class.java).apply {
                    putStringArrayListExtra("URLS", ArrayList(urls))
                    putExtra("SOURCE", WindowRouter.DispatchSource.SMART.name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        )

        val items = mutableListOf<ClipboardCapsuleOverlay.CapsuleItemData>()
        val fastPredictedList = mutableListOf<WindowRouter.PredictedTarget>()

        if (maxCount >= urls.size) {
            // 设置数量 >= 识别到的链接数量：所有链接均可直达展示，无需汇总胶囊
            for (i in 0 until urls.size) {
                val u = urls[i]
                val predicted = WindowRouter.predictTargetFastLocal(appContext, u)
                fastPredictedList.add(predicted)
                val icon = WindowRouter.resolvePredictedIcon(appContext, predicted)
                val isReDispatch = predicted.isReDispatch
                val manualSelect = predicted.packageName.isNullOrBlank() && !isReDispatch
                val label = when {
                    isReDispatch -> "由 ${predicted.label} 分发"
                    manualSelect -> "手动选择"
                    else -> predicted.label
                }

                items.add(
                    ClipboardCapsuleOverlay.CapsuleItemData(
                        icon = icon,
                        label = label,
                        badge = null,
                        onTap = { isReverse ->
                            capsule.dismiss()
                            NotificationHelper.dismiss(appContext)
                            Log.w(TAG, "[CAPSULE-CLICK] 点击阵列具体链接胶囊 ($i): label=$label, url=${u.take(60)}")
                            scope.launch {
                                val result = WindowRouter.handleUrl(appContext, u, WindowRouter.DispatchSource.DIRECT, isReverse = isReverse)
                                if (result.status == WindowRouter.DispatchResult.NEED_SELECTOR) {
                                    val intent = Intent(appContext, BrowserSelectorActivity::class.java).apply {
                                        putExtra("URL", result.finalUrl)
                                        putExtra("SOURCE", WindowRouter.DispatchSource.DIRECT.name)
                                        putExtra("TRACE_ID", result.traceId)
                                        putExtra("STEP_INDEX", result.stepIndex)
                                        putExtra("IS_REVERSE", isReverse)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    appContext.startActivity(intent)
                                }
                            }
                        }
                    )
                )
            }
        } else {
            // 设置数量 < 识别到的链接数量：前 maxCount - 1 个展示具体直达项，最后一个展示汇总项
            val directCount = (maxCount - 1).coerceAtLeast(0)
            for (i in 0 until directCount) {
                val u = urls[i]
                val predicted = WindowRouter.predictTargetFastLocal(appContext, u)
                fastPredictedList.add(predicted)
                val icon = WindowRouter.resolvePredictedIcon(appContext, predicted)
                val isReDispatch = predicted.isReDispatch
                val manualSelect = predicted.packageName.isNullOrBlank() && !isReDispatch
                val label = when {
                    isReDispatch -> "由 ${predicted.label} 分发"
                    manualSelect -> "手动选择"
                    else -> predicted.label
                }

                items.add(
                    ClipboardCapsuleOverlay.CapsuleItemData(
                        icon = icon,
                        label = label,
                        badge = null,
                        onTap = { isReverse ->
                            capsule.dismiss()
                            NotificationHelper.dismiss(appContext)
                            Log.w(TAG, "[CAPSULE-CLICK] 点击阵列具体链接胶囊 ($i): label=$label, url=${u.take(60)}")
                            scope.launch {
                                val result = WindowRouter.handleUrl(appContext, u, WindowRouter.DispatchSource.DIRECT, isReverse = isReverse)
                                if (result.status == WindowRouter.DispatchResult.NEED_SELECTOR) {
                                    val intent = Intent(appContext, BrowserSelectorActivity::class.java).apply {
                                        putExtra("URL", result.finalUrl)
                                        putExtra("SOURCE", WindowRouter.DispatchSource.DIRECT.name)
                                        putExtra("TRACE_ID", result.traceId)
                                        putExtra("STEP_INDEX", result.stepIndex)
                                        putExtra("IS_REVERSE", isReverse)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    appContext.startActivity(intent)
                                }
                            }
                        }
                    )
                )
            }

            items.add(summaryItem)
        }

        // 首帧秒弹多链接胶囊
        capsule.showArray(items)

        // 2. 后台异步协程：并发执行短链深度探测与原地逐项平滑刷新（挂载统一作用域，可随 release 整体取消）
        scope.launch {
            val directItemsCount = if (maxCount >= urls.size) urls.size else (maxCount - 1).coerceAtLeast(0)
            for (i in 0 until directItemsCount) {
                val u = urls[i]
                val fastPred = fastPredictedList.getOrNull(i)
                try {
                    val deepPred = withContext(Dispatchers.IO) {
                        WindowRouter.predictTargetDeepResolve(appContext, u)
                    }
                    if (fastPred != null && deepPred != fastPred) {
                        val deepIcon = WindowRouter.resolvePredictedIcon(appContext, deepPred)
                        val deepManual = deepPred.packageName.isNullOrBlank()
                        val deepLabel = if (deepManual) "手动选择" else deepPred.label
                        Log.i(TAG, "[MULTI-CAPSULE-DEEP-REFRESH] 多链接项($i)深度预测完成，原地刷新: ${fastPred.label} -> $deepLabel")
                        capsule.updateItemTarget(i, deepIcon, deepLabel)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "[MULTI-CAPSULE-DEEP-ERROR] 多链接项($i)深度解析异常: ${e.message}")
                }
            }
        }
    }

    /**
     * 处理一次剪贴板图片（可能在任意线程调用，内部切主线程延时防抖）。
     *
     * 与文本入口的分工：[ClipPayload.Image] 已由读取侧落盘，这里只管展示与跳转。
     * 去重按**内容指纹**而非文本内容——图片的"相同一次复制"只能靠内容判定。
     */
    /**
     * 屏幕取图专用入口：**不弹悬浮胶囊**。
     *
     * 与剪贴板路径的差异在于用户意图的确定性：复制是"顺手复制了东西"，
     * 用户未必想让 LinkGo 插手，所以剪贴板侧用胶囊做确认；
     * 而屏幕取图是用户主动点了那张图，意图已经明确，再弹一个胶囊只是多一次无谓点击。
     *
     * 行为与链接侧完全对齐：单个引擎直接跳转，多个引擎弹手动选择。
     */
    fun handleCapturedImage(context: Context, image: ClipPayload.Image) {
        Log.i(TAG, "[CAPTURE-IMAGE] 屏幕取图进入直发链路: ${image.width}x${image.height}, ${image.byteSize}B")
        scope.launch {
            dispatchCapturedImage(context.applicationContext, image)
        }
    }

    /**
     * 屏幕取图的去向决策：单引擎直达，多引擎进手动选择页。
     *
     * 与剪贴板路径刻意分开实现：那边要在胶囊上展示目标应用、点击后才跳，
     * 这边拿到就该决定，两者的"展示时机"不同，强行合并会让胶囊逻辑长出一堆条件分支。
     */
    private suspend fun dispatchCapturedImage(appContext: Context, image: ClipPayload.Image) {
        // 顺序即优先级：取启用中的规则
        val matchedRules = com.moting.linkgo.data.SettingsCache.imageRules.filter { it.isEnabled }
        val detail = ImageRouter.describe(image)

        // 单图片规则直达开关开启，且恰好命中一条可用规则时：直接发往目标应用，不再弹选择页
        val directSingle = com.moting.linkgo.data.SettingsCache.edgeGestureConfig.directSingleImageRule
        if (directSingle && matchedRules.size == 1) {
            val pickedRule = matchedRules.first()
            val ruleUsable = pickedRule.targetPackage.isNotBlank() &&
                withContext(Dispatchers.IO) {
                    ImageRouter.canReceiveImage(appContext, pickedRule.targetPackage)
                }
            if (ruleUsable) {
                val sent = ImageRouter.sendTo(
                    appContext, image, pickedRule.targetPackage,
                    pickedRule.targetClass, pickedRule.excludeFromRecents,
                    ruleLaunchMode = pickedRule.ruleLaunchMode,
                    ruleName = pickedRule.name, ruleId = pickedRule.id,
                    isReverse = false
                )
                if (sent) {
                    Log.i(TAG, "[CAPTURE-IMAGE-DIRECT] 单图片规则直达成功: ${pickedRule.name} (${pickedRule.targetPackage})")
                    return
                }
            }
        }

        Log.i(TAG, "[CAPTURE-IMAGE-POPUP] 屏幕截图完成，弹出图片选择页供预览与重选: $detail")
        appContext.startActivity(
            ImageSelectorActivity.buildIntent(appContext, image, matchedRules.map { it.id })
        )
    }

    fun handleImage(context: Context, image: ClipPayload.Image) {
        val now = System.currentTimeMillis()
        if (image.fingerprint == lastImageFingerprint && (now - lastImageTime) < IMAGE_DEBOUNCE_WINDOW_MS) {
            Log.w(TAG, "[CLIP-SUPPRESSED] 重复剪贴板图片在 ${IMAGE_DEBOUNCE_WINDOW_MS}ms 窗口内被拦截: fp=${image.fingerprint.take(12)}")
            SuperIslandLogManager.log(context, "CLIP_SUPPRESSED", "微防抖拦截相同图片(fp=${image.fingerprint.take(12)})")
            return
        }
        lastImageFingerprint = image.fingerprint
        lastImageTime = now

        Log.w(TAG, "[CLIP-IMAGE-RECEIVED] 捕获到剪贴板图片: ${image.width}x${image.height}, ${image.byteSize}B, ${image.mimeType}")
        if (com.moting.linkgo.data.SettingsCache.clipboardChangeToastEnabled) {
            com.moting.linkgo.util.InstantToastHelper.show(context, "已复制图片")
        }
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(30)
            processImage(context.applicationContext, image)
        }
    }

    /**
     * 图片展示链路。
     *
     * 命中顺序：**取第一条启用中的图片规则**（图片规则没有匹配条件，"顺序"是唯一仲裁依据，
     * 见 ImageRule 的说明）→ 胶囊上直接显示目标应用 → 点击直达；
     * 未配置规则或目标失效时回落系统分享选择器。
     */
    private suspend fun processImage(appContext: Context, image: ClipPayload.Image) {
        // 与文本链路不同：图片没有"提取链接失败就静默返回"这一说，
        // 能走到这里就一定能展示，因此不设提前返回分支。
        // 不再加载缩略图：胶囊与通知的图标位改用规则图标（见下方 capsuleIcon 的说明），
        // 为一张不会显示的缩略图做解码是纯粹的浪费——大图采样解码并不便宜。
        val fallbackIcon: Drawable = appContext.getDrawable(R.drawable.ic_capsule_multi)
            ?: appContext.applicationInfo.loadIcon(appContext.packageManager)

        // 图片规则与跳转规则同权：命中多条时不该由我们替用户挑一条，而应平铺候选让他选。
        // 顺序仍然是优先级——单条命中走直达，多条命中由选择页按同样的顺序展示。
        val matchedRules = com.moting.linkgo.data.SettingsCache.imageRules.filter { it.isEnabled }
        val pickedRule = matchedRules.firstOrNull()
        val isMulti = matchedRules.size > 1

        // 单条命中才预先校验目标：多条命中时由选择页在用户点选后再各自校验，
        // 这里提前判定反而会把"某一条目标失效"误当成整体不可用
        val ruleUsable = !isMulti && pickedRule != null &&
            pickedRule.targetPackage.isNotBlank() &&
            withContext(Dispatchers.IO) {
                ImageRouter.canReceiveImage(appContext, pickedRule.targetPackage)
            }

        // 单条命中：标签直接显示目标应用（告诉用户"点一下就发到这里"）
        // 多条命中：标签显示"选择发送目标"，与链接侧多链接的"链接选择"文案对等
        val targetLabel = when {
            isMulti -> ImageRouter.LABEL_PICK_TARGET
            ruleUsable -> {
                val label = withContext(Dispatchers.IO) {
                    com.moting.linkgo.data.PackageRepository.getAppLabel(appContext, pickedRule!!.targetPackage)
                }
                label.ifBlank { pickedRule!!.targetPackage }
            }
            else -> ImageRouter.LABEL_MANUAL_PICK
        }

        // 图标一律用「规则图标」：命中规则时优先取规则自定义图标，其次取目标应用图标，
        // 都没有才回落到通用图标。
        // 刻意不用图片缩略图：胶囊/通知是"要跳到哪里"的提示，用图片本身当图标会让用户
        // 以为那是可点的图片预览；规则图标才正确表达"点一下会去这个应用"。
        val ruleIcon: Drawable? = pickedRule?.iconPath?.let { path ->
            withContext(Dispatchers.IO) {
                WindowRouter.loadCustomIconAsDrawable(appContext, path)
            }
        }
        val appIcon: Drawable? = pickedRule?.targetPackage
            ?.takeIf { it.isNotBlank() }
            ?.let { pkg ->
                withContext(Dispatchers.IO) {
                    runCatching { appContext.packageManager.getApplicationIcon(pkg) }.getOrNull()
                }
            }
        val capsuleIcon: Drawable = if (isMulti) {
            appContext.getDrawable(R.drawable.ic_capsule_multi) ?: fallbackIcon
        } else {
            ruleIcon ?: appIcon ?: fallbackIcon
        }

        val detail = ImageRouter.describe(image)
        Log.i(
            TAG,
            "[IMAGE-CAPSULE] 图片胶囊: $detail, 命中规则=${matchedRules.size} 条, " +
                "首选=${pickedRule?.name ?: "无"}, 可用=$ruleUsable, 标签=$targetLabel"
        )

        // 胶囊与通知点击后的统一入口：多条命中进选择页，单条命中走原路径
        val pickIntent = if (isMulti) {
            ImageSelectorActivity.buildIntent(appContext, image, matchedRules.map { it.id })
        } else {
            com.moting.linkgo.image.ImageSendActivity.buildIntent(appContext, image)
        }

        // 1. 实时活动通知（不依赖悬浮窗权限）
        if (com.moting.linkgo.data.SettingsCache.showRichNotification) {
            runCatching {
                val contentPi = PendingIntent.getActivity(
                    appContext, 30,
                    pickIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                NotificationHelper.show(appContext, NotificationHelper.LiveActivityData(
                    icon = capsuleIcon,
                    title = targetLabel,
                    bigTitle = targetLabel,
                    body = detail,
                    fullText = detail,
                    rawUrl = null,
                    bigIcon = capsuleIcon,
                    contentIntent = contentPi,
                    actionLabel = targetLabel,
                    actionIntent = contentPi
                ))
            }.onFailure { Log.w(TAG, "[NOTIFY-ERROR] 图片通知发送失败: ${it.message}") }
        }

        // 2. 悬浮胶囊
        if (!Settings.canDrawOverlays(appContext)) {
            Log.w(TAG, "[CAPSULE-SKIP] 缺少悬浮窗权限，跳过图片胶囊展示")
            return
        }
        overlay(appContext).show(capsuleIcon, targetLabel, badge = if (isMulti) matchedRules.size.toString() else null) { isReverse ->
            Log.i(TAG, "[CAPSULE-CLICK] 点击图片胶囊: 命中=${matchedRules.size}, 首选=${pickedRule?.name ?: "无"}, isReverse=$isReverse")
            dismiss()
            NotificationHelper.dismiss(appContext)
            if (isMulti) {
                // 多条命中：交给选择页，用户点哪条就走哪条
                val intent = pickIntent.apply {
                    putExtra("IS_REVERSE", isReverse)
                }
                appContext.startActivity(intent)
            } else {
                val sent = if (ruleUsable) {
                    ImageRouter.sendTo(
                        appContext, image, pickedRule!!.targetPackage,
                        pickedRule.targetClass, pickedRule.excludeFromRecents,
                        ruleLaunchMode = pickedRule.ruleLaunchMode,
                        ruleName = pickedRule.name, ruleId = pickedRule.id,
                        isReverse = isReverse
                    )
                } else {
                    false
                }
                if (!sent) {
                    // 目标失效（规则被删/应用卸载/不再支持收图）时回落选择器，
                    // 而不是静默失败——用户点了就该有反应
                    Log.w(TAG, "[IMAGE-FALLBACK] 规则目标不可用，回落系统分享选择器")
                    ImageRouter.sendViaChooser(appContext, image)
                }
            }
        }
    }
    /** 立即隐藏胶囊。 */
    fun dismiss() {
        capsuleOverlay?.dismiss()
    }

    /** 释放资源（监听关闭时调用）：取消全部挂载于统一作用域的后台任务，并销毁胶囊。 */
    fun release() {
        debounceJob?.cancel()
        debounceJob = null
        suppressClearJob?.cancel()
        suppressClearJob = null
        suppressJumpNotification = false
        capsuleOverlay?.destroy()
        capsuleOverlay = null
        // 取消统一作用域内所有残留任务（深度解析/点击跳转/抑制复位），并重建以便下次复用
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        com.moting.linkgo.util.InstantToastHelper.cancel()
        lastText = null
        lastTime = 0L
        lastImageFingerprint = null
        lastImageTime = 0L
    }
}
