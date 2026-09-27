package com.google.android.accessibility.selecttospeak

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.moting.linkgo.model.LinkRegion
import com.moting.linkgo.util.UrlUtils
import com.moting.linkgo.data.SettingsRepository
import com.moting.linkgo.util.AccessibilityUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.regex.Pattern
import kotlin.math.abs
import com.moting.linkgo.overlay.OverlayManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.ui.viewinterop.AndroidView
import com.moting.linkgo.ui.LinkHighlightOverlayView
import com.moting.linkgo.ui.NumberSelectionBar
import com.moting.linkgo.util.WindowRouter
import android.content.Context
import android.content.ClipboardManager
import android.content.ClipData
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.foundation.shape.RoundedCornerShape
import android.content.Intent

/**
 * 伪装成 Google Select-to-Speak 的辅助功能服务
 * 用于绕过应用（如微信）的辅助功能限制，获取精确的字符坐标
 */
class SelectToSpeakService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var compiledPatterns: List<Pattern> = listOf(Pattern.compile(UrlUtils.DEFAULT_REGEX, Pattern.CASE_INSENSITIVE))
    
    // 当前正在执行的屏幕扫描任务：新扫描会取消旧扫描，服务销毁/中断时一并取消
    private var scanJob: Job? = null

    // 单次扫描的递归深度上限与总节点数上限（防止极端页面无界递归导致内存尖峰）
    private var scannedNodeCount: Int = 0
    private val MAX_SCAN_DEPTH = 64
    private val MAX_SCAN_NODES = 3000
    // 精确坐标递归查找的深度上限
    private val MAX_RECT_DEPTH = 32
    
    private var overlayManager: OverlayManager? = null
    private var edgeTriggerManager: com.moting.linkgo.overlay.EdgeTriggerManager? = null

    // 假死探针状态：由自愈检查驱动刷新，初值为健康以杜绝误判导致的误重启
    @Volatile
    private var probeAliveFlag: Boolean = true

    @Volatile
    private var lastProbeAt: Long = System.currentTimeMillis()

    @Volatile
    private var lastConnectedAt: Long = 0L

    // --- 屏幕识别核心状态 ---
    private val linksState = mutableStateListOf<LinkRegion>()
    private val scannedLinks = mutableStateListOf<LinkRegion>()
    /** 重选待复原的区域矩形 */
    private var pendingRestoredRegion: android.graphics.Rect? = null

    /**
     * 图片高亮层实例：点中图片后需要主动淡出，而不是等用户点空白处。
     *
     * 高亮层现在只承载链接；图片改为点击时按落点指认（见 [pickRegionAndDispatch]），
     * 不再预先收集图片区域——自动判据在实测里既误判（状态栏图标、空输入框、导航栏背景），
     * 又漏判（类名被混淆的图片）。
     */
    private var overlayViewRef: LinkHighlightOverlayView? = null
    private var scrollDirection by mutableIntStateOf(0)
    private var isExiting by mutableStateOf(false)
    private var isDarkTheme by mutableStateOf(false)
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        setInstance(this)
        lastConnectedAt = System.currentTimeMillis()
        overlayManager = OverlayManager(this)
        edgeTriggerManager = com.moting.linkgo.overlay.EdgeTriggerManager(this).apply {
            start()
        }
        Log.d("LinkScanner", "SelectToSpeakService 已连接")

        // 连接成功即视为活性成立：避免「尚未探测」被 [isActuallyAlive] 误判为未就绪
        lastProbeAt = System.currentTimeMillis()
        probeAliveFlag = true

        // 服务自行连回时主动刷新常驻通知：自愈巡检可能尚未再次运行，
        // 若不刷新，通知会一直停留在「快捷手势未就绪」，与实际状态不符。
        runCatching { com.moting.linkgo.service.ClipboardMonitorService.refreshNotification() }
        
        // 预热并监听链接提取规则列表
        serviceScope.launch {
            try {
                val repo = com.moting.linkgo.data.SettingsRepository(this@SelectToSpeakService)
                repo.extractionPatterns.collect { patterns ->
                    val enabled = patterns.filter { it.isEnabled && it.pattern.isNotBlank() }
                    val compiled = mutableListOf<java.util.regex.Pattern>()
                    for (ep in enabled) {
                        try {
                            compiled.add(java.util.regex.Pattern.compile(ep.pattern, java.util.regex.Pattern.CASE_INSENSITIVE or java.util.regex.Pattern.DOTALL))
                        } catch (e: Exception) {
                            Log.e("LinkScanner", "正则编译失败: ${ep.name} -> ${ep.pattern}", e)
                        }
                    }
                    compiledPatterns = if (compiled.isNotEmpty()) compiled
                        else listOf(java.util.regex.Pattern.compile(com.moting.linkgo.util.UrlUtils.DEFAULT_REGEX, java.util.regex.Pattern.CASE_INSENSITIVE))
                    Log.d("LinkScanner", "已更新屏幕识别正则: ${compiledPatterns.size} 条规则")
                }
            } catch (e: Exception) {
                Log.e("LinkScanner", "监听正则配置失败", e)
            }
        }

        // 无障碍连接时自动预热剪贴板后台监听服务
        // 受能力开关「连接时预热剪贴板监听」约束：关闭后需在剪贴板设置页手动开启监听
        serviceScope.launch {
            try {
                if (!com.moting.linkgo.util.privilege.CapabilityCenter.isEnabled(
                        com.moting.linkgo.util.privilege.CapabilityCenter.Capability.A11Y_PREWARM_CLIPBOARD
                    )
                ) {
                    return@launch
                }
                val repo = com.moting.linkgo.data.SettingsRepository(this@SelectToSpeakService)
                repo.preloadAll()
                val isEnabled = com.moting.linkgo.data.SettingsCache.clipboardMonitorEnabled
                val backend = com.moting.linkgo.data.SettingsCache.clipboardMonitorBackend
                if (isEnabled) {
                    com.moting.linkgo.clipboard.ClipboardMonitorController.apply(this@SelectToSpeakService, true, backend)
                }
            } catch (e: Exception) {
                Log.w("LinkScanner", "无障碍连接时预热剪贴板失败: ${e.message}")
            }
        }
    }

    /**
     * 假死自检：无障碍服务实例存在不等于可用。
     * 系统会把已连接的服务标记为启用，但进程被冻结或能力被系统回收时会「假死」——
     * 此时实例非空、系统设置也显示已启用，却再也拿不到任何窗口内容。
     * 读取最近一次探测结论，并带有效期，避免「从未探测」被当成「健康」而长期误报。
     */
    fun isActuallyAlive(): Boolean =
        probeAliveFlag && (System.currentTimeMillis() - lastProbeAt) <= PROBE_VALID_WINDOW_MS

    /**
     * 单次活性探测：窗口列表可读即视为健康（列表为空属正常，例如息屏或锁屏）。
     * 读窗口抛异常通常意味着服务已与系统失联，即假死。
     */
    fun probeAlive(): Boolean {
        lastProbeAt = System.currentTimeMillis()
        val alive = try {
            @Suppress("UNUSED_EXPRESSION")
            windows
            true
        } catch (e: Exception) {
            Log.w("LinkScanner", "活性探测失败，判定为假死: ${e.message}")
            false
        }
        probeAliveFlag = alive
        return alive
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        val eventType = event?.eventType ?: return
        lastEventAt = System.currentTimeMillis()
        if (eventType == android.view.accessibility.AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            eventType == android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            com.moting.linkgo.overlay.ImeInsetsManager.onAccessibilityWindowsChanged()
        }
        if (eventType == android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (!pkg.isNullOrBlank() && pkg != "com.moting.linkgo") {
                edgeTriggerManager?.onForegroundPackageChanged(pkg)
            }
        }
    }

    /**
     * 获取当前活动窗口所属的应用包名（带异常防护）
     */
    fun getActiveWindowPackage(): String? {
        return try {
            rootInActiveWindow?.packageName?.toString()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 执行全屏扫描：纯无障碍引擎
     */
    suspend fun scanCurrentScreen(): List<LinkRegion> {
        nodeCoordCache.clear()
        nodeParentBoundsCache.clear()
        scannedNodeCount = 0
        
        Log.d("LinkScanner", "==== 开始 scanCurrentScreen ====")
        val finalResults = mutableListOf<LinkRegion>()
        val currentWindows = try { windows } catch (e: Exception) { emptyList() }
        
        Log.d("LinkScanner", "获取到 windows 数量: ${currentWindows.size}")
        currentWindows.forEachIndexed { index, window ->
            val root = try { window.root } catch (e: Exception) { null }
            val pkgName = root?.packageName?.toString() ?: "Unknown"
            
            // 排除自身窗口，避免干扰扫描
            if (pkgName == "com.moting.linkgo") {
                Log.d("LinkScanner", "  [Window #$index] 跳过自身窗口: $pkgName")
                return@forEachIndexed
            }
            
            Log.d("LinkScanner", "  [Window #$index] Type: ${window.type}, Title: ${window.title}, Pkg: $pkgName, Focused: ${window.isFocused}, HasRoot: ${root != null}")
            
            if (root != null) {
                collectA11yCandidates(root, finalResults, 0)
            }
        }
        
        if (finalResults.isEmpty()) {
            val rootActive = try { rootInActiveWindow } catch (e: Exception) { null }
            val pkgActive = rootActive?.packageName?.toString() ?: "Unknown"
            
            if (pkgActive != "com.moting.linkgo") {
                Log.d("LinkScanner", "!! Windows 列表扫描结果为空，尝试回退至 rootInActiveWindow: Pkg=$pkgActive, HasRoot=${rootActive != null}")
                rootActive?.let { collectA11yCandidates(it, finalResults, 0) }
            } else {
                Log.d("LinkScanner", "!! Windows 列表为空且 rootInActiveWindow 也是自身，跳过回退扫描")
            }
        }
        
        Log.d("LinkScanner", "无障碍树扫描完成，共发现链接: ${finalResults.size}")

        // 最终排序与去重
        return sortAndFilterResults(finalResults)
    }

    /**
     * 深度优先遍历无障碍树并提取链接。
     * 带深度与节点规模上限，防止极端复杂页面（节点数千+）时递归过深/对象堆积导致内存尖峰。
     */
    private fun collectA11yCandidates(
        node: android.view.accessibility.AccessibilityNodeInfo?, 
        results: MutableList<LinkRegion>,
        depth: Int
    ) {
        if (node == null) return
        // 递归深度上限与整树扫描节点数上限
        if (depth > MAX_SCAN_DEPTH || scannedNodeCount >= MAX_SCAN_NODES) return
        scannedNodeCount++
        
        // 1. 先递归子节点 (深度优先)
        val childResults = mutableListOf<LinkRegion>()
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (e: Exception) { null }
            if (child != null) {
                collectA11yCandidates(child, childResults, depth + 1)
            }
        }
        
        // 2. 提取当前节点
        val currentResults = mutableListOf<LinkRegion>()
        extractLinksFromNode(node, currentResults)
        
        // 3. 层次剪枝：如果子节点已经包含了同样的 URL，父节点不再保留
        val filteredCurrent = currentResults.filter { cur ->
            childResults.none { it.url == cur.url }
        }
        
        results.addAll(childResults)
        results.addAll(filteredCurrent)
    }

    private fun sortAndFilterResults(results: List<LinkRegion>): List<LinkRegion> {
        if (results.isEmpty()) return emptyList()
        
        // 1. 去重：URL + 第一个矩形位置
        val unique = results.distinctBy { it.url + it.rects.firstOrNull()?.toShortString() }
        
        // 2. 按 groupId (Node) 分组，这是解决“裁剪/回退”干扰排序的关键
        val nodeGroups = unique.groupBy { it.groupId }
        
        // 3. 组间排序：基于控件在屏幕上的视觉位置 (使用 10px 容差的 Top 桶，解决微小偏移)
        val sortedGroups = nodeGroups.values.sortedWith(compareBy(
            { (it.minOf { l -> l.rects.firstOrNull()?.top ?: 0 } / 10) }, 
            { it.minOf { l -> l.rects.firstOrNull()?.left ?: 0 } }
        ))
        
        // 4. 组内排序：严格遵循文本顺序 (textOffset)
        return sortedGroups.flatMap { group ->
            group.sortedBy { it.textOffset }
        }
    }

    override fun onInterrupt() {
        scanJob?.cancel()
        overlayManager?.removeOverlay()
        edgeTriggerManager?.stop()
        edgeTriggerManager = null
        setInstance(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        scanJob?.cancel()
        overlayManager?.removeOverlay()
        edgeTriggerManager?.stop()
        edgeTriggerManager = null
        setInstance(null)
        serviceScope.cancel()
    }

    /**
     * 核心流程：启动屏幕识别
     */
    fun runIdentificationFlow(fromTile: Boolean = false) {
        // 重置状态
        linksState.clear()
        scannedLinks.clear()
        isExiting = false
        scrollDirection = 0
        
        // 检测深色模式
        isDarkTheme = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == 
                     android.content.res.Configuration.UI_MODE_NIGHT_YES

        // 取消上一次可能仍在进行的扫描，避免多次触发时扫描任务叠加占用
        scanJob?.cancel()
        scanJob = serviceScope.launch {
            try {
                var links: List<LinkRegion> = emptyList()
                if (fromTile) {
                    delay(100)
                    for (i in 0 until 12) {
                        links = scanCurrentScreen()
                        if (links.isNotEmpty()) break
                        delay(100)
                    }
                } else {
                    delay(50) 
                    for (i in 0 until 4) {
                        links = scanCurrentScreen()
                        if (links.isNotEmpty()) break
                        delay(150)
                    }
                }

                // 图片不再预扫描：屏幕上的图片改为"点击哪块取哪块"（见 pickRegionAndDispatch），
                // 自动判据在实测里既误判（状态栏图标、空输入框、导航栏背景）又漏判（类名混淆的图片）
                Log.i("LinkScanner", "识别完成：链接 ${links.size} 个")

                withContext(Dispatchers.Main) {
                    when {
                        // 只有一个链接：直接打开，保留原有极速路径
                        links.size == 1 ->
                            WindowRouter.openBrowser(this@SelectToSpeakService, links[0].url)

                        // 没有链接、又没有取图去向（未开「屏幕二维码识别」且无启用中的图片规则）：
                        // 直接退出，不进高亮层。停在这里既没有可点的链接，也取不到图
                        links.isEmpty() && !com.moting.linkgo.data.SettingsCache.screenCaptureEnabled ->
                            Log.i("LinkScanner", "无链接且无取图去向，屏幕识别直接退出")

                        else -> {
                            // 没有链接但有取图去向时仍要进高亮层：用户可以点屏幕上任意一块区域取图，
                            // 这正是"不猜图片"的入口，不能因为"没识别到链接"就退出
                            scannedLinks.addAll(links)
                            showHighlighterOverlay()
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 扫描被新任务/服务销毁主动取消：静默退出，不当作异常提示
                Log.d("LinkScanner", "识别流程已取消")
            } catch (e: Exception) {
                Log.e("LinkScanner", "识别流程异常", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SelectToSpeakService, "识别异常", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * 按用户落点指认区域并取图。
     *
     * 这是屏幕识别里唯一的图片入口：不再预扫描"哪里像图片"，而是取落点处最内层的节点区域。
     * 用户点到哪一层就取哪一层，粒度完全由落点决定——程序不再做第二次猜测，
     * 也就不会出现"把导航栏背景当图片"这类误判。
     *
     * 高亮层会先显示这块区域再退场，让用户确认"取的是不是这一块"。
     *
     * @return true 表示已命中区域并进入取图流程；false 表示落点处没有可用节点
     */
    fun pickRegionAndDispatch(x: Int, y: Int): Boolean {
        val bounds = com.moting.linkgo.image.ScreenRegionPicker.pick(this, x, y)
        if (bounds == null) {
            Toast.makeText(this, "这里没有可取的区域", Toast.LENGTH_SHORT).show()
            return false
        }

        // 先把指认到的区域亮出来供视觉反馈（80ms），然后统一走纯净退场截屏管线
        overlayViewRef?.showPickedRegion(bounds)
        com.moting.linkgo.image.ImageSelectionSession.saveLastRegion(bounds)

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            captureAndDispatchImage(bounds)
        }, PICKED_REGION_PREVIEW_MS)
        return true
    }

    /**
     * 若给定选区属于横向或竖向窄选，则自动吸附并扩展为相交控件的最左上角和最右下角包围矩形。
     */
    fun snapRegionIfNarrow(region: android.graphics.Rect): android.graphics.Rect {
        val density = resources.displayMetrics.density
        return com.moting.linkgo.image.ScreenRegionPicker.snapIfNarrow(this, region, density)
    }

    /**
     * 截取指定区域并交给对应的去向链路。
     *
     * 可在任意线程调用：截屏那一步由 `ScreenCapturer.captureRegion` 内部切回主线程
     * （无障碍截屏的契约），裁剪、二维码解码与落盘都留在后台。
     *
     * 去向按"区域里是什么"分两种：
     * - **解出二维码** → 内容交回链接提取链路并**直达**（`ClipboardHandler.handleQrCode`）：
     *   单条直达、多条进选择页，不弹胶囊——与"屏幕识别命中链接"同构；
     * - **没解出** → 原来的图片直发链路（`handleCapturedImage`）：屏幕取图是用户的明确动作，
     *   同样不弹胶囊做确认，行为与点击链接对齐——单引擎直达，多引擎进手动选择页。
     *
     * 供滑动直达（RadarFinderOverlay）在指针停留后调用，与屏幕识别的点击指认共用同一条链路。
     */
    fun captureAndDispatchImage(bounds: android.graphics.Rect) {
        val targetBounds = snapRegionIfNarrow(bounds)
        com.moting.linkgo.image.ImageSelectionSession.saveLastRegion(targetBounds)
        isExiting = true
        val overlay = overlayViewRef
        // 关键防护：截图前立即清空高亮选区并将覆盖视图设为 INVISIBLE，彻底消灭高亮框残影
        overlay?.clearPickedRegion()
        overlay?.visibility = android.view.View.INVISIBLE
        overlayManager?.removeOverlay()

        // 留出 80ms 确保 SurfaceFlinger 完成帧合成同步（Window 彻底脱离渲染队列）后再截屏
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            doCapture(targetBounds)
        }, 80L)
    }

    private fun doCapture(bounds: android.graphics.Rect) {
        // 取图挂到服务作用域：截屏内部会切回主线程，裁剪/解码/落盘留在后台，
        // 避免在 UI 线程上跑 zxing 解码
        serviceScope.launch {
            val result = com.moting.linkgo.image.ScreenCapturer.captureRegion(
                this@SelectToSpeakService, this@SelectToSpeakService, bounds
            )
            withContext(Dispatchers.Main) {
                when (result) {
                    is com.moting.linkgo.image.ScreenCapturer.CaptureResult.Success ->
                        com.moting.linkgo.clipboard.ClipboardHandler.handleCapturedImage(
                            this@SelectToSpeakService, result.image
                        )

                    // 区域里是二维码：内容交回文本链路，与复制了一段文本同构
                    is com.moting.linkgo.image.ScreenCapturer.CaptureResult.QrCode ->
                        com.moting.linkgo.clipboard.ClipboardHandler.handleQrCode(
                            this@SelectToSpeakService, result.content
                        )

                    is com.moting.linkgo.image.ScreenCapturer.CaptureResult.Failure ->
                        Toast.makeText(
                            this@SelectToSpeakService,
                            com.moting.linkgo.image.ImageCaptureSaver.describeError(result.error),
                            Toast.LENGTH_SHORT
                        ).show()
                }
            }
        }
    }

    /**
     * 重选统一入口：拉起屏幕识别蒙版并 100% 复原原有框选范围，支持拖曳边界微调与重新框选。
     * 采用【方案① 纯粹专注态】：隐藏底部数字条、全选按钮与顶部胶囊提示，但保留点击选取与长按退出。
     */
    fun showWithRestoredRegion(bounds: android.graphics.Rect) {
        isExiting = false
        // 方案①：清空数字选择条状态，避免底部弹出 NumberSelectionBar 挤占操作区
        linksState.clear()

        val overlay = overlayViewRef
        if (overlay != null && overlay.isAttachedToWindow) {
            overlay.restoreRegion(bounds)
        } else {
            pendingRestoredRegion = android.graphics.Rect(bounds)
            showHighlighterOverlay()
        }
    }

    private fun showHighlighterOverlay() {
        val composeView = ComposeView(this).apply {
            setContent {
                val repository = remember { SettingsRepository(this@SelectToSpeakService) }
                val dynamicColorEnabled by repository.dynamicColorEnabled.collectAsState(initial = true)

                com.moting.linkgo.ui.theme.链接跳转Theme(dynamicColor = dynamicColorEnabled) {
                    HighlighterOverlayScreen()
                }
            }
        }
        overlayManager?.showOverlay(composeView, focusable = true)
    }

    @Composable
    private fun HighlighterOverlayScreen() {
        val primary = MaterialTheme.colorScheme.primary
        val surface = MaterialTheme.colorScheme.surface
        val onPrimary = MaterialTheme.colorScheme.onPrimary
        val primaryContainer = MaterialTheme.colorScheme.primaryContainer
        val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer
        val scrim = MaterialTheme.colorScheme.scrim
        val outline = MaterialTheme.colorScheme.outline
        
        var barHeightPx by remember { mutableIntStateOf(0) }
        val density = LocalDensity.current
        
        // 避让平移高度
        val targetPadding = if (barHeightPx > 0) {
            with(density) { barHeightPx.toDp() } + 12.dp
        } else {
            48.dp
        }
        
        val animatedPadding by animateDpAsState(
            targetValue = targetPadding,
            animationSpec = tween(300),
            label = "SelectAllButtonPadding"
        )


        Box(modifier = Modifier.fillMaxSize()) {
            // 1. Canvas 绘制层
            AndroidView(
                factory = { ctx ->
                    LinkHighlightOverlayView(ctx).apply {
                        overlayViewRef = this
                        onDismiss = {
                            isExiting = true
                            overlayViewRef?.fadeOutAndFinish { overlayManager?.removeOverlay() }
                        }
                        onFadeOutFinished = {
                            overlayManager?.removeOverlay()
                        }
                        // 点中橙色图片框：截取该区域并走剪贴板图片链路（与复制图片完全同构）
                        onPickRegion = { x, y -> pickRegionAndDispatch(x, y) }
                        // 确认调整后的选框截图
                        onConfirmRegion = { rect ->
                            captureAndDispatchImage(rect)
                        }
                        // 右下角悬浮按钮点击：截全屏
                        onCaptureFullScreen = {
                            val dm = resources.displayMetrics
                            captureAndDispatchImage(android.graphics.Rect(0, 0, dm.widthPixels, dm.heightPixels))
                        }
                        onLongClick = { clickedLinks ->
                            clickedLinks.firstOrNull()?.let { 
                                copyToClipboard(it.url)
                            }
                        }
                        onConflictDetected = { conflictLinks, _ ->
                            val oldTop = linksState.firstOrNull()?.rects?.firstOrNull()?.top ?: 0
                            val newTop = conflictLinks.firstOrNull()?.rects?.firstOrNull()?.top ?: 0
                            scrollDirection = if (oldTop > 0 && newTop < oldTop) 1 else if (oldTop > 0 && newTop > oldTop) -1 else 0
                            
                            linksState.clear()
                            linksState.addAll(conflictLinks)
                        }
                        // 方案①：检查是否有待复原的重选选区
                        val restored = pendingRestoredRegion
                        pendingRestoredRegion = null
                        // 取图入口是否保留由设置决定：没有去向时点击空白一律退场（见 captureEnabled）
                        captureEnabled = com.moting.linkgo.data.SettingsCache.screenCaptureEnabled
                        setLinks(scannedLinks)
                        if (restored != null) {
                            restoreRegion(restored)
                        }
                        startFadeIn(showHint = true)
                    }
                },
                update = { view ->
                    view.setIsDarkMode(isDarkTheme)
                    view.setThemeColors(
                        primary = primary.toArgb(),
                        surface = surface.toArgb(),
                        onPrimary = onPrimary.toArgb(),
                        primaryContainer = primaryContainer.toArgb(),
                        onPrimaryContainer = onPrimaryContainer.toArgb(),
                        scrim = scrim.toArgb(),
                        outline = outline.toArgb()
                    )
                    view.setLinks(scannedLinks)
                },
                modifier = Modifier.fillMaxSize()
            )

            // 2. 数字选择条
            var dragAccumulator by remember { mutableFloatStateOf(0f) }
            val dragThreshold = 50f
            val view = androidx.compose.ui.platform.LocalView.current

            AnimatedVisibility(
                visible = linksState.isNotEmpty() && !isExiting,
                enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 2 },
                exit = fadeOut(tween(250)) + slideOutVertically(tween(250)) { it / 2 },
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                NumberSelectionBar(
                    modifier = Modifier
                        .onGloballyPositioned { coordinates ->
                            barHeightPx = coordinates.size.height
                        }
                        .pointerInput(Unit) {
                            var isSwipedInThisGesture = false
                            detectVerticalDragGestures(
                                onDragStart = {
                                    dragAccumulator = 0f
                                    isSwipedInThisGesture = false
                                },
                                onDragEnd = { 
                                    dragAccumulator = 0f 
                                    isSwipedInThisGesture = false
                                },
                                onDragCancel = {
                                    dragAccumulator = 0f
                                    isSwipedInThisGesture = false
                                },
                                onVerticalDrag = { change, dragAmount ->
                                    change.consume()
                                    if (isSwipedInThisGesture) return@detectVerticalDragGestures
                                    
                                    dragAccumulator += dragAmount
                                    if (kotlin.math.abs(dragAccumulator) > dragThreshold) {
                                        isSwipedInThisGesture = true // 锁定本次手势，直到抬起手指
                                        val isSwipeDown = dragAccumulator > 0
                                        dragAccumulator = 0f
                                        val conflictGroups = scannedLinks
                                            .filter { !it.isPrecise }
                                            .groupBy { it.groupId }
                                            .values
                                            .filter { it.size >= 2 }
                                            .sortedBy { g -> g.firstOrNull()?.rects?.firstOrNull()?.top ?: 0 }
                                        if (conflictGroups.isNotEmpty()) {
                                            // 判定是否是全选状态（选中的项目是否跨越了不同的组）
                                            val isAllSelected = linksState.distinctBy { it.groupId }.size > 1 || linksState.size == scannedLinks.size
                                            
                                            val nextIndex = if (isAllSelected) {
                                                if (isSwipeDown) 0 else conflictGroups.lastIndex
                                            } else {
                                                val currentGroupId = linksState.firstOrNull()?.groupId
                                                val currentIndex = conflictGroups.indexOfFirst { it.firstOrNull()?.groupId == currentGroupId }
                                                if (currentIndex != -1) {
                                                    if (isSwipeDown) {
                                                        if (currentIndex - 1 < 0) conflictGroups.lastIndex else currentIndex - 1
                                                    } else {
                                                        if (currentIndex + 1 > conflictGroups.lastIndex) 0 else currentIndex + 1
                                                    }
                                                } else {
                                                    0
                                                }
                                            }
                                            
                                            val targetGroup = conflictGroups[nextIndex]
                                            
                                            // 强制动画方向与手势绑定：
                                            // 下滑手指(isSwipeDown == true)，看上方内容，老内容往下跑，新内容从上方进
                                            // 上滑手指(isSwipeDown == false)，看下方内容，老内容往上跑，新内容从下方进
                                            scrollDirection = if (isSwipeDown) 1 else -1
                                            
                                            val indices = targetGroup.map { scannedLinks.indexOf(it) }
                                            overlayViewRef?.setHighlightIndices(indices)
                                            linksState.clear()
                                            linksState.addAll(targetGroup)
                                            view.performHapticFeedback(android.view.HapticFeedbackConstants.TEXT_HANDLE_MOVE)
                                        }
                                    }
                                }
                            )
                        },
                    links = linksState.toList(), // 关键修复：生成新集合，触发 AnimatedContent 切换动画
                    primaryColor = primary,
                    scrollDirection = scrollDirection,
                    onSelected = { link ->
                        isExiting = true
                        overlayViewRef?.fadeOutAndFinish {
                            WindowRouter.openBrowser(this@SelectToSpeakService, link.url)
                        }
                    },
                    onLongClick = { link ->
                        copyToClipboard(link.url)
                    }
                )
            }
            
            // 3. 全选按钮
            AnimatedVisibility(
                visible = scannedLinks.size > 1 && linksState.size < scannedLinks.size && !isExiting && overlayViewRef?.isInteractiveCrop != true,
                enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 2 },
                exit = fadeOut(tween(250)) + slideOutVertically(tween(250)) { it / 2 },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 24.dp, bottom = animatedPadding)
            ) {
                Button(
                    onClick = {
                        val allIndices = scannedLinks.indices.toList()
                        overlayViewRef?.setHighlightIndices(allIndices)
                        scrollDirection = 0
                        linksState.clear()
                        linksState.addAll(scannedLinks)
                    },
                    modifier = Modifier.size(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = primary.copy(alpha = 0.95f),
                        contentColor = onPrimary
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(imageVector = Icons.Default.SelectAll, contentDescription = null)
                }
            }
        }
        
        // 辅助逻辑
        if (linksState.isEmpty()) {
            SideEffect { if (barHeightPx != 0) barHeightPx = 0 }
        }
    }

    private fun Color.toArgb(): Int {
        return android.graphics.Color.argb(
            (alpha * 255).toInt(),
            (red * 255).toInt(),
            (green * 255).toInt(),
            (blue * 255).toInt()
        )
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Link", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "链接已复制", Toast.LENGTH_SHORT).show()
    }

    /**
     * 之前的测试方法（可保留或删除）
     */
    fun showTestOverlay() {
        runIdentificationFlow(false)
    }

    private fun extractLinksFromNode(node: AccessibilityNodeInfo, results: MutableList<LinkRegion>) {
        val parent = try { node.parent } catch (e: Exception) { null }
        if (parent != null) {
            val pBounds = Rect()
            parent.getBoundsInScreen(pBounds)
            nodeParentBoundsCache[node.hashCode()] = pBounds
        }
        
        // 允许精准与非精准共存
        processContent(node, node.text?.toString(), false, results)
        processContent(node, node.contentDescription?.toString(), true, results)
    }

    private fun Rect.normalize(): Rect {
        if (top > bottom) {
            val t = top
            top = bottom
            bottom = t
        }
        if (left > right) {
            val l = left
            left = right
            right = l
        }
        return this
    }

    private fun processContent(node: AccessibilityNodeInfo, content: String?, isDesc: Boolean, results: MutableList<LinkRegion>) {
        if (content.isNullOrBlank()) return

        val nodeInfo = "${node.className}@${Integer.toHexString(node.hashCode())}"
        val nodeBounds = Rect().also {
            node.getBoundsInScreen(it)
            it.normalize()
        }

        // 优先处理 Span
        if (!isDesc && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val text = node.text
            if (text is android.text.Spanned) {
                val urlSpans = text.getSpans(0, text.length, android.text.style.URLSpan::class.java)
                if (!urlSpans.isNullOrEmpty()) {
                    for (span in urlSpans) {
                        val url = span.url
                        val start = text.getSpanStart(span)
                        val end = text.getSpanEnd(span)
                        val rects = getPreciseRects(node, start, end - start, false)
                        if (rects.isNotEmpty()) {
                            results.add(LinkRegion(url, rects.map { it.normalize() }, isDesc, node.hashCode(), isPrecise = true, textOffset = start, nodeBounds = nodeBounds))
                        } else {
                            results.add(LinkRegion(url, listOf(nodeBounds), isDesc, node.hashCode(), isPrecise = false, textOffset = start, nodeBounds = nodeBounds))
                        }
                    }
                    if (results.isNotEmpty()) return
                }
            }
        }

        // 正则解析：多规则遍历 + 贪心区间去重
        data class RawMatch(val url: String, val start: Int, val end: Int)
        val allMatches = mutableListOf<RawMatch>()

        for (pattern in compiledPatterns) {
            try {
                val matcher = pattern.matcher(content)
                while (matcher.find()) {
                    val url = if (matcher.groupCount() > 0) matcher.group(1) else matcher.group() ?: continue
                    allMatches.add(RawMatch(url, matcher.start(), matcher.end()))
                }
            } catch (e: Exception) {
                Log.e("LinkScanner", "正则解析失败: ${e.message}")
            }
        }

        if (allMatches.isEmpty()) return

        // 贪心区间去重：按长度降序，有交集的丢弃短的
        val sorted = allMatches.sortedByDescending { it.end - it.start }
        val selected = mutableListOf<RawMatch>()
        for (m in sorted) {
            val hasOverlap = selected.any { s -> m.start < s.end && m.end > s.start }
            if (!hasOverlap) selected.add(m)
        }

        // 按位置排序后生成 LinkRegion
        for (m in selected.sortedBy { it.start }) {
            val rects = getPreciseRects(node, m.start, m.end - m.start, isDesc)
            if (rects.isNotEmpty()) {
                Log.d("LinkScanner", "  [Regex-Precise] $nodeInfo 成功获取精准坐标: ${m.url}")
                results.add(LinkRegion(m.url, rects.map { it.normalize() }, isDesc, node.hashCode(), isPrecise = true, textOffset = m.start, nodeBounds = nodeBounds))
            } else {
                Log.d("LinkScanner", "  [Regex-Fallback] $nodeInfo 无法精确定位，回退到控件边界: ${m.url}")
                results.add(LinkRegion(m.url, listOf(nodeBounds), isDesc, node.hashCode(), isPrecise = false, textOffset = m.start, nodeBounds = nodeBounds))
            }
        }
    }

    private val nodeCoordCache = mutableMapOf<String, List<Rect>>()
    private val nodeParentBoundsCache = mutableMapOf<Int, Rect>()

    private fun getPreciseRects(node: AccessibilityNodeInfo, start: Int, length: Int, isDesc: Boolean): List<Rect> {
        val source = if (isDesc) node.contentDescription else node.text
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        // Key 加入内容哈希 + 控件 ID + 当前物理位置，三重验证
        val nodeKey = "${node.viewIdResourceName}_${source?.hashCode()}_${bounds.hashCode()}"
        nodeCoordCache[nodeKey]?.let { return sliceAndFilterRects(it, start, length, bounds) }

        if (isDesc && node.text.isNullOrEmpty()) return emptyList()

        val rects = findRectsDeeply(node, start, length, isDesc)
        return rects
    }

    private fun findRectsDeeply(node: AccessibilityNodeInfo, start: Int, length: Int, isDesc: Boolean, depth: Int = 0): List<Rect> {
        // 递归深度上限，防止控件层级极深时无界递归
        if (depth > MAX_RECT_DEPTH) return emptyList()
        val fromSelf = tryGetRects(node, start, length, isDesc)
        if (fromSelf.isNotEmpty()) return fromSelf

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val fromChild = findRectsDeeply(child, start, length, isDesc, depth + 1)
            if (fromChild.isNotEmpty()) return fromChild
        }
        return emptyList()
    }

    private fun tryGetRects(node: AccessibilityNodeInfo, start: Int, length: Int, isDesc: Boolean): List<Rect> {
        val nodeText = (if (isDesc) node.contentDescription else node.text)?.toString() ?: ""
        if (nodeText.isEmpty()) return emptyList()
        if (isDesc && node.text.isNullOrEmpty()) return emptyList()

        try {
            // 1. 获取控件当前的物理显示区域
            // 对于被父容器截断的控件，getBoundsInScreen 返回的是实际可见的部分
            val nodeScreen = Rect()
            node.getBoundsInScreen(nodeScreen)
            
            val arguments = Bundle()
            arguments.putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0)
            arguments.putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, nodeText.length)
            
            var success = node.refreshWithExtraData(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, arguments)
            if (!success || node.extras.get(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY) == null) {
                // 首次未取到：请求无障碍焦点后立即重试一次。
                // 注意：不再使用 Thread.sleep 阻塞（会占满 IO 线程池、放大扫描内存尖峰），
                // 重试仍失败则由调用方回退到控件边界框。
                node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
                success = node.refreshWithExtraData(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, arguments)
            }

            if (success) {
                val rawData = node.extras.get(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY)
                val items = when (rawData) {
                    is Array<*> -> rawData.toList()
                    is List<*> -> rawData
                    else -> null
                }
                if (!items.isNullOrEmpty()) {
                    // 存储原始的字符坐标，不再此处做边界裁剪，以便后续行组合时计算真实高度
                    val allRects = items.map { it ->
                        val r = it as? Rect ?: (it as? android.graphics.RectF)?.let { f -> val res = Rect(); f.roundOut(res); res }
                        r ?: Rect()
                    }
                    if (allRects.any { !it.isEmpty }) {
                        nodeCoordCache["${node.viewIdResourceName}_${nodeText.hashCode()}"] = allRects
                        return sliceAndFilterRects(allRects, start, length, nodeScreen)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("LinkScanner", "获取精准坐标异常", e)
        }
        return emptyList()
    }

    /**
     * 获取链接的有效标注框（支持多行链接，并应用 70% 可见度阈值过滤）
     */
    private fun sliceAndFilterRects(fullList: List<Rect>, start: Int, length: Int, parentScreenRect: Rect, threshold: Float = 0.7f): List<Rect> {
        val result = mutableListOf<Rect>()
        val end = Math.min(start + length, fullList.size)
        
        // 【关键修复】找到全体字符中的最大高度，作为标准的“真实行高”
        // 因为被裁剪的行，其 charRect 高度已经被系统提前压缩，如果只用单行高度，比例永远是 1.0
        var globalMaxCharHeight = 0f
        for (rect in fullList) {
            if (!rect.isEmpty) {
                globalMaxCharHeight = Math.max(globalMaxCharHeight, rect.height().toFloat())
            }
        }

        var currentLineBox: Rect? = null

        for (i in start until end) {
            val charRect = fullList[i]
            if (charRect.isEmpty) continue

            val charHeight = charRect.height().toFloat()
            if (charHeight <= 0) continue

            // 判断是否换行（如果当前字符的 top 与当前行的 top 差距超过标准行高的一半，认定为换行）
            val isNewLine = currentLineBox != null && 
                            Math.abs(charRect.top - currentLineBox.top) > (globalMaxCharHeight / 2f)

            if (currentLineBox == null || isNewLine) {
                // 1. 结算并判定上一行的可见度
                if (currentLineBox != null) {
                    processAndFilterLineBox(currentLineBox, globalMaxCharHeight, parentScreenRect, threshold)?.let { result.add(it) }
                }
                
                // 2. 初始化新的一行
                currentLineBox = Rect(charRect)
            } else {
                // 3. 还在同一行，向右水平扩展当前行的包围盒
                currentLineBox.union(charRect)
            }
        }

        // 结算最后一行
        if (currentLineBox != null) {
            processAndFilterLineBox(currentLineBox, globalMaxCharHeight, parentScreenRect, threshold)?.let { result.add(it) }
        }

        return result
    }

    /**
     * 处理单行包围盒：计算可见度占比，如果不达标则过滤，达标则返回裁剪后的绘制框
     */
    private fun processAndFilterLineBox(lineBox: Rect, originalHeight: Float, parentScreenRect: Rect, threshold: Float): Rect? {
        // 1. 计算与父容器的 Y 轴交集（可见部分的 Top 和 Bottom）
        val visibleTop = Math.max(lineBox.top, parentScreenRect.top)
        val visibleBottom = Math.min(lineBox.bottom, parentScreenRect.bottom)
        
        // 2. 计算可见区域的真实高度
        val visibleHeight = Math.max(0, visibleBottom - visibleTop).toFloat()

        val ratio = if (originalHeight > 0) visibleHeight / originalHeight else 0f
        Log.d("LinkScannerDiag", "LineBox: $lineBox, origH=$originalHeight, visH=$visibleHeight, ratio=$ratio, parent=$parentScreenRect")

        // 3. 阈值判定
        if (originalHeight > 0 && ratio >= threshold) {
            // 达标！返回最终需要绘制的 Rect，Y 轴强制钳制在父容器范围内
            return Rect(lineBox.left, visibleTop, lineBox.right, visibleBottom)
        }
        
        // 不达标，直接丢弃（返回 null）
        Log.d("LinkScannerDiag", "  -> 被 70% 阈值过滤弃用")
        return null
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Log.w("LinkScanner", "SelectToSpeakService onUnbind: 服务已被系统解绑断开")
        setInstance(null)
        // 被系统解绑后尝试一次静默自愈（带限流，避免与主动重启流程互相打架）
        AccessibilityUtils.requestPassiveSelfHeal(this)
        return super.onUnbind(intent)
    }

    companion object {
        /** 探测结论有效期：超过该窗口未重新探测则视为「未就绪」，避免陈旧结论长期误报 */
        private const val PROBE_VALID_WINDOW_MS = 60_000L

        /**
         * 指认区域的预览时长。
         *
         * 点击后先把这块区域亮出来再退场截屏，让用户看到"取的是这一块"；
         * 太短来不及看清，太长会让取图显得迟钝。
         */
        private const val PICKED_REGION_PREVIEW_MS = 260L

        /** 最近一次无障碍窗口事件时间：静态保存，服务实例缺失时仍可回答「设备是否在用」 */
        @Volatile
        private var lastEventAt: Long = 0L

        /**
         * 设备是否处于近期活跃状态（最近有无障碍窗口事件）。
         * 供常驻服务的保活心跳判断「现在是否值得检查」：
         * 长时间无窗口事件通常意味着息屏或深度待机，此时无需浪费任何检查开销。
         */
        fun wasRecentlyActive(windowMs: Long = 10 * 60 * 1000L): Boolean =
            System.currentTimeMillis() - lastEventAt <= windowMs

        @Volatile
        private var _instance: SelectToSpeakService? = null

        fun getInstance(): SelectToSpeakService? = _instance

        // 内部设置方法，供 Service 生命周期调用
        internal fun setInstance(service: SelectToSpeakService?) {
            _instance = service
        }
    }
}


