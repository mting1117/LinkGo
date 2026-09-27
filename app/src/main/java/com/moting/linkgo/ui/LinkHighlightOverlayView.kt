package com.moting.linkgo.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.GestureDetector
import android.app.Activity
import android.animation.ValueAnimator
import android.graphics.drawable.Drawable
import com.moting.linkgo.R
import com.moting.linkgo.model.LinkRegion
import com.moting.linkgo.util.WindowRouter
import kotlinx.coroutines.*
import kotlin.math.roundToInt

class LinkHighlightOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val viewScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    internal val links = mutableListOf<LinkRegion>()

    /**
     * 用户当前指认到的区域（尚未取图）。
     *
     * 只有一块，且只在"点击指认"到"退场截屏"之间存活：屏幕识别不再预先扫描图片，
     * 因此这里没有"图片列表"，只有用户刚点中的那一块。
     *
     * 存拷贝而不是直接持有传入的 Rect：矩形会被无障碍树复用改写。
     */
    internal var pickedRegion: Rect? = null
        private set

    /**
     * 指认框颜色（橙）。
     *
     * 刻意做成可替换的属性而不是写死的常量：《地址识别方案》落地时也要用橙色分档，
     * 届时在那一处统一调整即可，不必回来改这里的绘制逻辑。
     */
    var imageBorderColor: Int = Color.parseColor("#FFF57C00")
        set(value) {
            field = value
            imageBorderPaint.color = value
            invalidate()
        }

    private val viewLocation = IntArray(2)
    private val path = Path()
    private val tempPath = Path()
    internal var isFadingOut = false
    
    // 背景压暗画笔
    private val maskPaint = Paint().apply {
        color = Color.parseColor("#B3000000")
        style = Paint.Style.FILL
    }
    
    fun setMaskAlpha(alpha: Int) {
        maskPaint.color = (alpha shl 24) or (maskPaint.color and 0x00FFFFFF)
        invalidate()
    }
    
    // 掏空画笔 (清除背景遮罩)
    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        isAntiAlias = true
        style = Paint.Style.FILL_AND_STROKE
        strokeWidth = 5f
        pathEffect = CornerPathEffect(12f)
    }
    
    // 霓虹边框画笔
    private val borderPaint = Paint().apply {
        color = Color.parseColor("#FF007AFF")
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
        pathEffect = CornerPathEffect(12f)
    }

    /** 图片框画笔：与链接框同粗细同圆角，仅颜色不同（一眼可辨，且不引入新的视觉语言） */
    private val imageBorderPaint = Paint().apply {
        color = Color.parseColor("#FFF57C00")
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
        pathEffect = CornerPathEffect(12f)
    }

    // --- Material 3 胶囊角标专属画笔 ---
    private val pillBgPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
        // 阴影配置将在 setThemeColors 中根据深浅模式动态调整
    }

    private val pillStrokePaint = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeWidth = 2f
    }

    private val pillTextPaint = Paint().apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        isFakeBoldText = true
        isAntiAlias = true
    }

    private var primaryColor: Int = Color.BLUE
    private var surfaceColor: Int = Color.WHITE
    private var onPrimaryColor: Int = Color.WHITE
    private var primaryContainerColor: Int = Color.LTGRAY
    private var onPrimaryContainerColor: Int = Color.BLACK
    private var scrimColor: Int = Color.BLACK
    private var outlineColor: Int = Color.GRAY
    private var isDarkMode: Boolean = true

    fun setIsDarkMode(isDark: Boolean) {
        this.isDarkMode = isDark
        invalidate()
    }

    fun setThemeColors(
        primary: Int, 
        surface: Int, 
        onPrimary: Int,
        primaryContainer: Int = Color.LTGRAY,
        onPrimaryContainer: Int = Color.BLACK,
        scrim: Int = Color.BLACK,
        outline: Int = Color.GRAY
    ) {
        this.primaryColor = primary
        this.surfaceColor = surface
        this.onPrimaryColor = onPrimary
        this.primaryContainerColor = primaryContainer
        this.onPrimaryContainerColor = onPrimaryContainer
        this.scrimColor = scrim
        this.outlineColor = outline
        
        borderPaint.color = primary

        // 遮罩自适应：深色模式使用 scrim (黑)，浅色模式使用 surface (白)
        val currentAlpha = Color.alpha(maskPaint.color)
        val baseMaskColor = if (isDarkMode) scrim else surface
        maskPaint.color = (currentAlpha shl 24) or (baseMaskColor and 0x00FFFFFF)
        
        borderPaint.strokeWidth = if (isDarkMode) 4.5f else 5.0f
        clearPaint.strokeWidth = borderPaint.strokeWidth
        
        // 胶囊阴影：深色模式下使用更透明的纯黑阴影，浅色模式下使用稍重的阴影
        val shadowAlpha = if (isDarkMode) 0.15f else 0.25f
        val shadowColor = Color.argb((255 * shadowAlpha).toInt(), 0, 0, 0)
        pillBgPaint.setShadowLayer(8f, 0f, 4f, shadowColor)
        
        invalidate()
    }

    private var highlightIndices = emptyList<Int>()
    private var pulseValue = 0f
    private val pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1200
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            pulseValue = it.animatedValue as Float
            postInvalidateOnAnimation()
        }
    }

    fun setHighlightIndices(indices: List<Int>) {
        this.highlightIndices = indices
        if (indices.isNotEmpty()) {
            if (!pulseAnimator.isRunning) pulseAnimator.start()
        } else {
            pulseAnimator.cancel()
            pulseValue = 0f
        }
        invalidate()
    }

    var onConflictDetected: ((List<LinkRegion>, List<Int>) -> Unit)? = null
    var onLongClick: ((List<LinkRegion>) -> Unit)? = null
    var onDismiss: (() -> Unit)? = null
    var onFadeOutStarted: (() -> Unit)? = null
    var onFadeOutFinished: (() -> Unit)? = null

    /**
     * 点击到没有链接的地方：上报落点，由调用方去无障碍树里指认这一块并取图。
     *
     * 屏幕识别的图片入口就是它——不再预先显示图片框，用户点哪块取哪块。
     */
    var onPickRegion: ((x: Int, y: Int) -> Unit)? = null
    var onConfirmRegion: ((Rect) -> Unit)? = null
    var onCaptureFullScreen: (() -> Unit)? = null

    /**
     * 是否保留取图入口（点空白指认 / 滑动框选 / 右下角截全屏）。
     *
     * 由调用方按 [com.moting.linkgo.data.SettingsCache.screenCaptureEnabled] 下发：
     * 取图没有任何去向时（未开「屏幕二维码识别」且无启用中的图片规则），点击空白一律退场、
     * 滑动不再划框、截全屏按钮也不再绘制——否则用户点下去只会走一趟取不到东西的流程。
     */
    var captureEnabled: Boolean = true

    /** 是否处于交互式选框编辑模式（包含拖拽边界手柄与右下角按钮展示） */
    var isInteractiveCrop = false
        private set

    private enum class DragHandle {
        NONE, LEFT, TOP, RIGHT, BOTTOM, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, NEW_BOX, MOVE
    }
    private var activeHandle = DragHandle.NONE
    private var downX = 0f
    private var downY = 0f
    private val initialCropRect = Rect()
    private val confirmBtnRect = RectF()
    private val cancelBtnRect = RectF()
    private var pendingConfirm = false
    private var pendingCancel = false
    private var isCandidateForMove = false
    private val touchSlop get() = 8f * density
    private var dragGuideRect: Rect? = null
    private var liveSnapJob: Job? = null

    /** 方案 B：窄选物理引导虚线画笔 */
    private val guideLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
        color = Color.argb(170, 255, 255, 255)
        pathEffect = DashPathEffect(floatArrayOf(5f * density, 3.5f * density), 0f)
    }

    private val density get() = resources.displayMetrics.density
    private val handleTolerance get() = 24f * density

    private val actionBtnBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val actionBtnStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }
    private val actionIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        strokeCap = Paint.Cap.ROUND
    }

    // --- 顶部操作提示胶囊（与 RadarFinderOverlay 胶囊 100% 视觉对齐，入场后 3 秒自动淡出） ---
    private var hintAlpha = 0f
    private val hintShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val hintBgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val hintBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val hintTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFakeBoldText = true
    }
    private val hintAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFakeBoldText = true
    }
    private var hintIconBmp: Bitmap? = null
    private var fabExpandDrawable: Drawable? = null
    private var hintFadeAnimator: android.animation.ValueAnimator? = null
    private val hintFadeRunnable = Runnable { startHintFadeOut() }

    private fun startHintFadeOut() {
        hintFadeAnimator?.cancel()
        hintFadeAnimator = android.animation.ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 600
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                hintAlpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    // --- 悬浮截全屏按钮（FAB）：可拖动，初始位置右下角 ---
    /** FAB 圆心 X，初始化在首次 onSizeChanged 时设置 */
    private var fabX = -1f
    private var fabY = -1f
    private val fabRadius get() = 24f * density
    /** 按下时记录的 FAB 圆心，用于判断是点击还是拖动 */
    private var fabDownCenterX = 0f
    private var fabDownCenterY = 0f
    /** 按下时手指坐标 */
    private var fabTouchDownX = 0f
    private var fabTouchDownY = 0f
    private var isFabTouching = false
    /** 拖动判定阈值（超过则视为拖动，否则松手触发截全屏） */
    private val fabDragThresholdSq get() = (8f * density) * (8f * density)

    private val fabBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(230, 20, 20, 24)
    }
    private val fabRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = Color.argb(100, 255, 255, 255)
    }
    private val fabIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 首次（或旋转后）确定视图尺寸，将 FAB 定位到右下角
        if (fabX < 0 || fabX > w || fabY > h) {
            val margin = 24f * density
            fabX = w - fabRadius - margin
            fabY = h - fabRadius - margin - 80f * density  // 留出底部导航栏空间
        }
    }

    /** 复原原有框选范围，并直接进入交互式编辑状态（展示手柄与右下角确认/退出按钮） */
    fun restoreRegion(bounds: Rect) {
        pickedRegion = Rect(bounds)
        isInteractiveCrop = true
        invalidate()
    }

    /**
     * 点空白处的统一处置：有取图去向时交给外部指认区域，否则直接退场。
     */
    private fun pickOrDismiss(screenX: Int, screenY: Int) {
        if (captureEnabled) onPickRegion?.invoke(screenX, screenY) else onDismiss?.invoke()
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        // onDoubleTap 不再处理截全屏（已改为右下角悬浮按钮），返回 false 不拦截

        override fun onLongPress(e: MotionEvent) {
            val screenX = e.rawX.toInt()
            val screenY = e.rawY.toInt()
            val clickedLinks = links.filter { link ->
                link.rects.any { rect ->
                    val hitRect = Rect(rect).apply { inset(-15, -15) }
                    hitRect.contains(screenX, screenY)
                }
            }
            if (clickedLinks.isNotEmpty()) {
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                onLongClick?.invoke(clickedLinks)
            } else {
                // 长按空白处 = 退出。
                // 点击空白现在被"取图"占用了，必须另留一条明确的退出路径，
                // 否则用户点哪都在取图，没法取消这次屏幕识别。
                onDismiss?.invoke()
            }
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val screenX = e.rawX.toInt()
            val screenY = e.rawY.toInt()
            
            // 核心修复：精准优先点击检测
            val preciseHits = links.filter { it.isPrecise }.filter { link ->
                link.rects.any { rect ->
                    val hitRect = Rect(rect).apply { inset(-10, -10) }
                    hitRect.contains(screenX, screenY)
                }
            }

            // 宿主大框命中检测（有精准链接命中大框也可以）
            val nodeBoundsHits = links.filter { it.isPrecise && it.nodeBounds != null }.filter { link ->
                link.nodeBounds!!.contains(screenX, screenY)
            }
            
            val fallbackHits = links.filter { !it.isPrecise }.filter { link ->
                link.rects.any { rect ->
                    val hitRect = Rect(rect).apply { inset(-10, -10) }
                    hitRect.contains(screenX, screenY)
                }
            }

            // 链接都没命中：这一下就是"指认取图"，落点交给调用方去无障碍树里取最内层区域
            if (preciseHits.size == 1) {
                // 1. 直接点中精准文字：直接跳转
                setHighlightIndices(emptyList())
                fadeOutAndFinish { WindowRouter.openBrowser(context, preciseHits[0].url) }
            } else if (preciseHits.size > 1) {
                // 2. 命中了多个精准链接（坐标重叠）：交互裁剪模式下直接打开首个，普通模式弹出选择框
                if (isInteractiveCrop) {
                    fadeOutAndFinish { WindowRouter.openBrowser(context, preciseHits[0].url) }
                } else {
                    val indices = preciseHits.map { links.indexOf(it) }
                    setHighlightIndices(indices)
                    onConflictDetected?.invoke(preciseHits, indices)
                }
            } else if (nodeBoundsHits.isNotEmpty()) {
                // 3. 未直接点中文字，但点中了精准链接的宿主大框
                val firstNode = nodeBoundsHits.first()
                val groupPrecise = links.filter { it.groupId == firstNode.groupId && it.isPrecise }
                if (groupPrecise.size >= 2) {
                    if (isInteractiveCrop) {
                        fadeOutAndFinish { WindowRouter.openBrowser(context, groupPrecise[0].url) }
                    } else {
                        val indices = groupPrecise.map { links.indexOf(it) }
                        setHighlightIndices(indices)
                        onConflictDetected?.invoke(groupPrecise, indices)
                    }
                } else if (groupPrecise.isNotEmpty()) {
                    setHighlightIndices(emptyList())
                    fadeOutAndFinish { WindowRouter.openBrowser(context, groupPrecise[0].url) }
                } else {
                    setHighlightIndices(emptyList())
                    pickOrDismiss(screenX, screenY)
                }
            } else if (fallbackHits.isNotEmpty()) {
                // 4. 仅命中了回退大框区域
                val firstFallback = fallbackHits.first()
                val groupAllUnprecise = links.filter { it.groupId == firstFallback.groupId && !it.isPrecise }
                if (groupAllUnprecise.size >= 2) {
                    if (isInteractiveCrop) {
                        fadeOutAndFinish { WindowRouter.openBrowser(context, groupAllUnprecise[0].url) }
                    } else {
                        val indices = groupAllUnprecise.map { links.indexOf(it) }
                        setHighlightIndices(indices)
                        onConflictDetected?.invoke(groupAllUnprecise, indices)
                    }
                } else if (groupAllUnprecise.size == 1) {
                    setHighlightIndices(emptyList())
                    fadeOutAndFinish { WindowRouter.openBrowser(context, groupAllUnprecise[0].url) }
                } else {
                    setHighlightIndices(emptyList())
                    pickOrDismiss(screenX, screenY)
                }
            } else {
                // 5. 什么也没点到：有取图去向时按落点指认取图，否则直接退场
                setHighlightIndices(emptyList())
                pickOrDismiss(screenX, screenY)
            }
            return true
        }
    })

    init {
        alpha = 0f
    }

    fun startFadeIn(showHint: Boolean = true) {
        hintFadeAnimator?.cancel()
        removeCallbacks(hintFadeRunnable)
        if (showHint) {
            hintAlpha = 1f
            // 蒙版淡入完成后 3 秒，提示胶囊自动淡出
            postDelayed(hintFadeRunnable, 3000L)
        } else {
            hintAlpha = 0f
        }
        animate().alpha(1f).setDuration(200).start()
    }

    internal fun fadeOutAndFinish(action: (() -> Unit)? = null) {
        if (isFadingOut) return
        isFadingOut = true
        onFadeOutStarted?.invoke()
        animate().alpha(0f).setDuration(250).withEndAction {
            action?.invoke()
            onFadeOutFinished?.invoke()
        }.start()
    }

    fun setLinks(newLinks: List<LinkRegion>) {
        links.clear()
        links.addAll(newLinks)
        invalidate()
    }

    /**
     * 亮出用户刚指认的区域，让他在退场截屏前确认"取的是不是这一块"。
     *
     * 这里只存一份拷贝：传入的矩形可能来自无障碍树，那类实例会被复用改写。
     */
    fun showPickedRegion(bounds: Rect) {
        pickedRegion = Rect(bounds)
        invalidate()
    }

    /** 取图失败时把指认框收回去，别让用户以为已经取到了 */
    fun clearPickedRegion() {
        pickedRegion = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 遮罩**始终**绘制，不用"有没有内容"来决定。
        //
        // 那层半透压暗是"已进入屏幕识别"的唯一视觉标志：之前靠"扫描到链接或图片"
        // 来判断要不要画，图片改为点击指认之后，没有链接的页面上就没有任何标志了，
        // 用户会以为屏幕识别没生效。没有命中区域时它就是整屏均匀压暗，
        // 指认到哪一块就把哪一块掏空亮出来。

        getLocationOnScreen(viewLocation)
        
        val sc = canvas.saveLayer(-1000f, -1000f, width + 1000f, height + 1000f, null)
        canvas.drawRect(-1000f, -1000f, width + 1000f, height + 1000f, maskPaint)
        
        canvas.save()
        canvas.translate(-viewLocation[0].toFloat(), -viewLocation[1].toFloat())

        // --- 第零步：指认框（与链接同一套"掏空 + 描边"画法，仅换色与圆角更大）---
        // 先画它：指认框通常比其中的文字链接大，后画的链接会叠在上面，视觉层级正确
        pickedRegion?.let { picked ->
            val rectF = RectF(picked)
            val pickedPath = Path().apply { addRect(rectF, Path.Direction.CW) }
            canvas.drawPath(pickedPath, clearPaint)
            canvas.drawPath(pickedPath, imageBorderPaint)
        }

        // 方案 B 虚实双层对照：若当前处于窄选划线，绘制半透明虚线引导框指示手势物理轨迹
        dragGuideRect?.let { guide ->
            val guideRectF = RectF(guide)
            val cornerRadius = 3f * density
            tempPath.reset()
            tempPath.addRoundRect(guideRectF, cornerRadius, cornerRadius, Path.Direction.CW)
            canvas.drawPath(tempPath, guideLinePaint)
        }
        
        if (!isInteractiveCrop) {
            val nodeGroups = links.groupBy { it.groupId }
            
            // --- 第一步：掏空背景 ---
            for ((_, groupLinks) in nodeGroups) {
                val preciseLinks = groupLinks.filter { it.isPrecise }
                val unpreciseLinks = groupLinks.filter { !it.isPrecise }

                if (preciseLinks.isNotEmpty()) {
                    val combinedPath = Path()
                    for (link in preciseLinks) {
                        val processed = processRects(link.rects)
                        for (r in processed) {
                            val rectF = RectF(r).apply { inset(-6f, -3f) }
                            tempPath.reset()
                            tempPath.addRect(rectF, Path.Direction.CW)
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                                combinedPath.op(tempPath, Path.Op.UNION)
                            } else {
                                combinedPath.addRect(rectF, Path.Direction.CW)
                            }
                        }
                    }
                    canvas.drawPath(combinedPath, clearPaint)
                }

                if (unpreciseLinks.isNotEmpty()) {
                    val fallbackRect = unpreciseLinks.first().rects.firstOrNull() ?: continue
                    val donutPath = Path()
                    donutPath.addRect(RectF(fallbackRect).apply { inset(-6f, -3f) }, Path.Direction.CW)
                    for (pl in preciseLinks) {
                        for (pr in pl.rects) {
                            val innerRect = Path().apply { addRect(RectF(pr).apply { inset(-8f, -4f) }, Path.Direction.CW) }
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                                donutPath.op(innerRect, Path.Op.DIFFERENCE)
                            }
                        }
                    }
                    canvas.drawPath(donutPath, clearPaint)
                }
            }

            // --- 第二步：绘制边框和动画 ---
            for ((_, groupLinks) in nodeGroups) {
                val preciseLinks = groupLinks.filter { it.isPrecise }
                val unpreciseLinks = groupLinks.filter { !it.isPrecise }

                for (link in preciseLinks) {
                    val globalIndex = links.indexOf(link)
                    val processed = processRects(link.rects)
                    
                    val combinedPath = Path()
                    for (r in processed) {
                        val rectF = RectF(r).apply { inset(-6f, -3f) }
                        tempPath.reset()
                        tempPath.addRect(rectF, Path.Direction.CW)
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                            combinedPath.op(tempPath, Path.Op.UNION)
                        } else {
                            combinedPath.addRect(rectF, Path.Direction.CW)
                        }
                    }
                    
                    canvas.drawPath(combinedPath, borderPaint)
                    
                    if (highlightIndices.contains(globalIndex) && pulseValue > 0) {
                        drawPulse(canvas, processed, pulseValue)
                    }
                }

                if (unpreciseLinks.isNotEmpty()) {
                    val fallbackRect = unpreciseLinks.first().rects.firstOrNull() ?: continue
                    val donutPath = Path().apply { addRect(RectF(fallbackRect).apply { inset(-6f, -3f) }, Path.Direction.CW) }
                    for (pl in preciseLinks) {
                        for (pr in pl.rects) {
                            val innerRect = Path().apply { addRect(RectF(pr).apply { inset(-8f, -4f) }, Path.Direction.CW) }
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                                donutPath.op(innerRect, Path.Op.DIFFERENCE)
                            }
                        }
                    }
                    canvas.drawPath(donutPath, borderPaint)
                    
                    if (unpreciseLinks.any { highlightIndices.contains(links.indexOf(it)) } && pulseValue > 0) {
                        drawPulse(canvas, listOf(fallbackRect), pulseValue, isFallbackGroup = true, donutPath = donutPath)
                    }
                }
            }

            // --- 第三步：绘制所有角标 (Modern Pill) ---
            for ((_, groupLinks) in nodeGroups) {
                val preciseLinks = groupLinks.filter { it.isPrecise }
                val unpreciseLinks = groupLinks.filter { !it.isPrecise }

                for (link in preciseLinks) {
                    val globalIndex = links.indexOf(link)
                    drawModernPill(canvas, link.rects.first(), (globalIndex + 1).toString(), isConflict = false)
                }

                if (unpreciseLinks.isNotEmpty()) {
                    val fallbackRect = unpreciseLinks.first().rects.firstOrNull() ?: continue
                    // 移除沉底逻辑，统一靠左上
                    drawModernPill(canvas, fallbackRect, unpreciseLinks.size.toString(), isConflict = true)
                }
            }
        }
        
        canvas.restore()
        canvas.restoreToCount(sc)

        // --- 第四步：交互式框选手柄与操作按钮（在最上层绘制，按屏幕绝对坐标绘制到平移画布） ---
        if (pickedRegion != null && isInteractiveCrop) {
            val region = pickedRegion!!
            val rLeft = region.left.toFloat()
            val rTop = region.top.toFloat()
            val rRight = region.right.toFloat()
            val rBottom = region.bottom.toFloat()

            canvas.save()
            canvas.translate(-viewLocation[0].toFloat(), -viewLocation[1].toFloat())

            // 1. 绘制四个角的拉伸把手
            handlePaint.color = imageBorderColor
            val hLen = 14f * density
            // 左上角
            canvas.drawLine(rLeft, rTop, rLeft + hLen, rTop, handlePaint)
            canvas.drawLine(rLeft, rTop, rLeft, rTop + hLen, handlePaint)
            // 右上角
            canvas.drawLine(rRight, rTop, rRight - hLen, rTop, handlePaint)
            canvas.drawLine(rRight, rTop, rRight, rTop + hLen, handlePaint)
            // 左下角
            canvas.drawLine(rLeft, rBottom, rLeft + hLen, rBottom, handlePaint)
            canvas.drawLine(rLeft, rBottom, rLeft, rBottom - hLen, handlePaint)
            // 右下角
            canvas.drawLine(rRight, rBottom, rRight - hLen, rBottom, handlePaint)
            canvas.drawLine(rRight, rBottom, rRight, rBottom - hLen, handlePaint)

            // 2. 右下角操作按钮：退出（叉号） + 确认（勾号）
            val btnW = 42f * density
            val btnH = 34f * density
            val spacing = 8f * density
            val totalW = btnW * 2 + spacing

            val dm = resources.displayMetrics
            val screenW = dm.widthPixels.toFloat()
            val screenH = dm.heightPixels.toFloat()

            // 水平位置：尽量与选框右边缘对齐，并保持屏幕左右边距
            var bRight = kotlin.math.min(screenW - 16f * density, rRight)
            bRight = kotlin.math.max(bRight, totalW + 16f * density)
            val bLeft = bRight - totalW

            // 垂直位置：优先在选框下方，若触底则翻转到选框上方或框内贴底
            var bTop = rBottom + 12f * density
            if (bTop + btnH > screenH - 24f * density) {
                bTop = rTop - btnH - 12f * density
                if (bTop < 24f * density) {
                    bTop = rBottom - btnH - 8f * density
                }
            }

            cancelBtnRect.set(bLeft, bTop, bLeft + btnW, bTop + btnH)
            confirmBtnRect.set(bLeft + btnW + spacing, bTop, bLeft + totalW, bTop + btnH)

            val btnRadius = btnH / 2f

            // 绘制取消按钮（半透黑底 + 灰白描边 + 叉号）
            actionBtnBgPaint.color = Color.argb(220, 36, 36, 40)
            canvas.drawRoundRect(cancelBtnRect, btnRadius, btnRadius, actionBtnBgPaint)
            actionBtnStrokePaint.color = Color.argb(80, 255, 255, 255)
            canvas.drawRoundRect(cancelBtnRect, btnRadius, btnRadius, actionBtnStrokePaint)

            val cx1 = cancelBtnRect.centerX()
            val cy1 = cancelBtnRect.centerY()
            val xHalf = 5f * density
            canvas.drawLine(cx1 - xHalf, cy1 - xHalf, cx1 + xHalf, cy1 + xHalf, actionIconPaint)
            canvas.drawLine(cx1 + xHalf, cy1 - xHalf, cx1 - xHalf, cy1 + xHalf, actionIconPaint)

            // 绘制确认按钮（主色底 + 勾号）
            actionBtnBgPaint.color = primaryColor
            canvas.drawRoundRect(confirmBtnRect, btnRadius, btnRadius, actionBtnBgPaint)
            actionBtnStrokePaint.color = Color.argb(120, 255, 255, 255)
            canvas.drawRoundRect(confirmBtnRect, btnRadius, btnRadius, actionBtnStrokePaint)

            val cx2 = confirmBtnRect.centerX()
            val cy2 = confirmBtnRect.centerY()
            val checkPath = Path().apply {
                moveTo(cx2 - 5.5f * density, cy2)
                lineTo(cx2 - 1.5f * density, cy2 + 4f * density)
                lineTo(cx2 + 5.5f * density, cy2 - 4f * density)
            }
            canvas.drawPath(checkPath, actionIconPaint)

            canvas.restore()
        }

        // --- 第五步：截全屏悬浮按钮（始终在最顶层，坐标已是 View 本地坐标） ---
        // 没有取图去向时不画：它一点就取图，画出来等于摆一个走不通的入口
        if (captureEnabled && fabX > 0 && fabY > 0) {
            drawFab(canvas)
        }

        // --- 第六步：顶部操作提示胶囊 ---
        if (hintAlpha > 0.01f) {
            drawHintCapsule(canvas)
        }
    }

    /** 绘制截全屏悬浮按钮（采用标准 Iconoir expand 矢量图标，杜绝手搓画线） */
    private fun drawFab(canvas: Canvas) {
        val r = fabRadius
        // 背景圆：按下时加深
        fabBgPaint.color = if (isFabTouching) Color.argb(255, 50, 50, 60) else Color.argb(230, 20, 20, 24)
        canvas.drawCircle(fabX, fabY, r, fabBgPaint)
        canvas.drawCircle(fabX, fabY, r, fabRingPaint)

        // 使用标准 Iconoir expand 图标 (24dp 视口，居中绘制 22dp 大小)
        if (fabExpandDrawable == null) {
            fabExpandDrawable = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_iconoir_expand)?.mutate()?.apply {
                setTint(Color.WHITE)
            }
        }
        val iconSize = 22f * density
        val left = (fabX - iconSize / 2f).roundToInt()
        val top = (fabY - iconSize / 2f).roundToInt()
        val right = (fabX + iconSize / 2f).roundToInt()
        val bottom = (fabY + iconSize / 2f).roundToInt()
        fabExpandDrawable?.let { d ->
            d.setBounds(left, top, right, bottom)
            d.draw(canvas)
        }
    }

    /** 顶部操作提示胶囊：与 RadarFinderOverlay 的 Modern Pill 胶囊 100% 视觉对齐 */
    private fun drawHintCapsule(canvas: Canvas) {
        val darkBg = 0xF4202124.toInt()
        val lightBg = 0xF8FFFFFF.toInt()
        val darkTextColor = 0xFFB0B3B8.toInt()
        val lightTextColor = 0xFF5F6368.toInt()

        val textColor = if (isDarkMode) darkTextColor else lightTextColor
        val accentColor = primaryColor

        hintTextPaint.textSize = 13.5f * density
        hintAccentPaint.textSize = 13.5f * density
        hintTextPaint.color = textColor
        hintAccentPaint.color = accentColor

        // 文本分段排版：有取图去向时给出取图手势，否则只提示链接操作
        // (文本, 是否强调)
        val segments: List<Pair<String, Boolean>> = if (captureEnabled) {
            listOf("轻触 " to false, "指认" to true, " · 滑动 " to false, "框选" to true, " · 长按退出" to false)
        } else {
            listOf("轻触 " to false, "跳转" to true, " · 长按退出" to false)
        }

        var totalTextW = 0f
        val segmentWidths = segments.map { (text, accent) ->
            val paint = if (accent) hintAccentPaint else hintTextPaint
            paint.measureText(text).also { totalTextW += it }
        }

        val padH = 14f * density
        val padV = 9.5f * density
        val iconSize = 20f * density
        val iconGap = 8f * density

        val capsuleW = iconSize + iconGap + totalTextW + padH * 2f
        val capsuleH = iconSize + padV * 2f
        val capsuleRadius = capsuleH / 2f

        val topMargin = 52f * density
        val capsuleCenterX = width / 2f
        val capsuleCenterY = topMargin + capsuleH / 2f

        canvas.save()
        canvas.translate(capsuleCenterX, capsuleCenterY)

        val rectLeft = -capsuleW / 2f
        val rectTop = -capsuleH / 2f
        val rectRight = capsuleW / 2f
        val rectBottom = capsuleH / 2f

        val shadowPad = 24f * density
        val overallAlpha = (hintAlpha * 255).roundToInt().coerceIn(0, 255)
        val layerSaveCount = canvas.saveLayerAlpha(
            rectLeft - shadowPad,
            rectTop - shadowPad,
            rectRight + shadowPad,
            rectBottom + shadowPad + 6f * density,
            overallAlpha
        )

        // 1. 弥散立体阴影 (内缩 2.5dp 消除边缘黑边)
        val shadowInset = 2.5f * density
        val shadowRect = RectF(rectLeft + shadowInset, rectTop + shadowInset, rectRight - shadowInset, rectBottom - shadowInset)
        val shadowRadius = (capsuleH - shadowInset * 2f) / 2f
        val shadowColor = if (isDarkMode) Color.argb(120, 0, 0, 0) else Color.argb(35, 0, 0, 0)
        hintShadowPaint.color = shadowColor
        hintShadowPaint.setShadowLayer(8f * density, 0f, 2.5f * density, shadowColor)
        val shadowPath = Path().apply {
            addRoundRect(shadowRect, shadowRadius, shadowRadius, Path.Direction.CW)
        }
        canvas.drawPath(shadowPath, hintShadowPaint)

        // 2. 实体胶囊平滑背景面板 (100% 饱满实心面板，完全遮挡住底层阴影)
        val capsulePath = Path().apply {
            addRoundRect(RectF(rectLeft, rectTop, rectRight, rectBottom), capsuleRadius, capsuleRadius, Path.Direction.CW)
        }
        hintBgPaint.color = if (isDarkMode) darkBg else lightBg
        canvas.drawPath(capsulePath, hintBgPaint)

        // 3. 深浅色微轮廓描边
        hintBorderPaint.strokeWidth = 1f * density
        hintBorderPaint.color = if (isDarkMode) Color.argb(0x28, 255, 255, 255) else Color.argb(0x18, 0, 0, 0)
        canvas.drawPath(capsulePath, hintBorderPaint)

        // 4. 左侧 20dp 屏幕识别专属指示图标 (加载并绘制 ic_iconoir_flash)
        val iconLeft = rectLeft + padH
        val iconTop = rectTop + padV
        if (hintIconBmp == null) {
            val d = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_iconoir_flash)?.mutate()
            if (d != null) {
                val tint = if (isDarkMode) Color.WHITE else darkTextColor
                d.setTint(tint)
                val bmp = Bitmap.createBitmap((20 * density).roundToInt(), (20 * density).roundToInt(), Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                d.setBounds(0, 0, bmp.width, bmp.height)
                d.draw(c)
                hintIconBmp = bmp
            }
        }
        hintIconBmp?.let { bmp ->
            val src = Rect(0, 0, bmp.width, bmp.height)
            val dst = RectF(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
            canvas.drawBitmap(bmp, src, dst, null)
        }

        // 5. 绘制文本排版
        var curX = iconLeft + iconSize + iconGap
        val fm = hintTextPaint.fontMetrics
        val baselineY = (rectTop + padV) + iconSize / 2f - (fm.ascent + fm.descent) / 2f

        segments.forEachIndexed { i, (text, accent) ->
            canvas.drawText(text, curX, baselineY, if (accent) hintAccentPaint else hintTextPaint)
            curX += segmentWidths[i]
        }

        canvas.restoreToCount(layerSaveCount)
        canvas.restore()
    }

    private fun processRects(rects: List<Rect>): List<Rect> {
        val processed = mutableListOf<Rect>()
        val sorted = rects.sortedWith(compareBy({ it.top }, { it.left }))
        for (rect in sorted) {
            if (processed.isEmpty()) { processed.add(Rect(rect)); continue }
            val last = processed.last()
            
            // 使用中心点高度差来判断行对齐，比 Top/Bottom 交叉更鲁棒
            val lastCenterY = (last.top + last.bottom) / 2f
            val currentCenterY = (rect.top + rect.bottom) / 2f
            val isSameLine = Math.abs(lastCenterY - currentCenterY) < (last.height() * 0.4f)
            
            // 如果在同一行且水平间距小于 50 像素（稍微放宽），则合并
            if (isSameLine && Math.abs(rect.left - last.right) < 50) {
                last.right = Math.max(last.right, rect.right)
                last.top = Math.min(last.top, rect.top)
                last.bottom = Math.max(last.bottom, rect.bottom)
            } else {
                processed.add(Rect(rect))
            }
        }
        return processed
    }

    private fun drawModernPill(
        canvas: Canvas, 
        rect: Rect, 
        text: String, 
        isConflict: Boolean = false
    ) {
        // 1. 统一颜色方案：不透明的 Primary 背景 + OnPrimary 文字
        pillBgPaint.color = primaryColor
        pillBgPaint.alpha = 255 // 确保完全不透明
        pillTextPaint.color = onPrimaryColor
        
        // 描边颜色：统一使用 surface 颜色
        pillStrokePaint.color = surfaceColor
        pillStrokePaint.alpha = 255
        pillStrokePaint.strokeWidth = 3f // 增加描边宽度

        // 2. 测量尺寸 (根据竖向排版调整)
        val textSize = if (isConflict) 24f else 28f // 竖排时数字稍小一点
        val padding = if (isConflict) 16f else 18f // 竖排内边距
        
        pillTextPaint.textSize = textSize
        val textWidth = pillTextPaint.measureText(text)
        
        // 尺寸计算：
        // 精确角标(横向)：高度固定，宽度随数字变化
        // 冲突角标(竖向)：宽度固定，高度包含图标、间距和数字
        val iconSize = if (isConflict) 24f else 0f
        val verticalMargin = if (isConflict) 6f else 0f
        
        val fm = pillTextPaint.fontMetrics
        val textHeight = fm.descent - fm.ascent
        
        val totalWidth = if (isConflict) {
            Math.max(textWidth, iconSize) + (padding * 2)
        } else {
            textWidth + (padding * 2)
        }
        
        val height = if (isConflict) {
            padding + iconSize + verticalMargin + textHeight + padding - 8f // 减去一些多余的底部间隙
        } else {
            44f
        }
        
        val radius = if (isConflict) totalWidth / 2f else height / 2f
        
        // 3. 确定位置：默认放在外部左侧，顶部对齐，遇边缘向右吸附
        // Y 轴：与框的上边缘对齐
        val pillTop = Math.max(rect.top.toFloat(), 10f)

        // X 轴：默认悬浮在框的外部左侧，留 6f 间隙
        val targetX = rect.left.toFloat() - totalWidth - 6f
        // 防溢出：如果在屏幕左侧画不下（悬浮空间不足），就向内翻转到框内左侧
        val pillLeft = if (targetX < 10f) rect.left.toFloat() + 6f else targetX

        val pillRect = RectF(pillLeft, pillTop, pillLeft + totalWidth, pillTop + height)

        // 4. 绘制阴影和背景
        canvas.drawRoundRect(pillRect, radius, radius, pillBgPaint)
        // 4.1 绘制描边
        canvas.drawRoundRect(pillRect, radius, radius, pillStrokePaint)

        // 5. 绘制内容
        if (isConflict) {
            // 绘制“图标在上，数字在下”的竖向排版
            val centerX = pillRect.centerX()
            var currentY = pillRect.top + padding
            
            // 绘制简易链接图标 (🔗)
            val iconPaint = Paint(pillStrokePaint).apply { 
                strokeWidth = 3f 
                color = onPrimaryColor
                style = Paint.Style.STROKE
            }
            val iconCenterY = currentY + iconSize / 2f
            
            // 缩小并调整图标位置以适应竖向排版
            val leftRing = RectF(centerX - 10f, iconCenterY - 5f, centerX + 2f, iconCenterY + 5f)
            val rightRing = RectF(centerX - 2f, iconCenterY - 5f, centerX + 10f, iconCenterY + 5f)
            
            canvas.drawArc(leftRing, 90f, 270f, false, iconPaint)
            canvas.drawArc(rightRing, -90f, 270f, false, iconPaint)
            canvas.drawLine(centerX - 2f, iconCenterY, centerX + 2f, iconCenterY, iconPaint)
            
            currentY += iconSize + verticalMargin
            
            // 绘制数字
            val textCenterY = currentY - fm.ascent
            canvas.drawText(text, centerX, textCenterY, pillTextPaint)
        } else {
            // 单链接：直接居中绘制数字
            val textCenterY = pillRect.centerY() - (fm.ascent + fm.descent) / 2
            canvas.drawText(text, pillRect.centerX(), textCenterY, pillTextPaint)
        }
    }

    private fun drawPulse(canvas: Canvas, rects: List<Rect>, value: Float, 
                          isFallbackGroup: Boolean = false, donutPath: Path? = null) {
        val expansion = value * 40f
        val pulsePaint = Paint(borderPaint).apply {
            alpha = ((1f - value) * 160).toInt()
            strokeWidth = borderPaint.strokeWidth * 0.6f
            pathEffect = CornerPathEffect(12f + expansion)
        }
        
        if (isFallbackGroup && donutPath != null) {
            val matrix = Matrix()
            val bounds = RectF()
            donutPath.computeBounds(bounds, true)
            matrix.setScale(1f + expansion/bounds.width(), 1f + expansion/bounds.height(), bounds.centerX(), bounds.centerY())
            val pulsePath = Path()
            donutPath.transform(matrix, pulsePath)
            canvas.drawPath(pulsePath, pulsePaint)
        } else {
            val pulsePath = Path()
            for (rect in rects) {
                val rectF = RectF(rect).apply { inset(-6f - expansion, -3f - expansion) }
                tempPath.reset()
                tempPath.addRect(rectF, Path.Direction.CW)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                    pulsePath.op(tempPath, Path.Op.UNION)
                }
            }
            canvas.drawPath(pulsePath, pulsePaint)
        }
    }

    private fun hitTestHandle(r: Rect, x: Float, y: Float): DragHandle {
        val tol = handleTolerance
        val l = r.left.toFloat()
        val t = r.top.toFloat()
        val rt = r.right.toFloat()
        val b = r.bottom.toFloat()

        val nearL = kotlin.math.abs(x - l) <= tol
        val nearR = kotlin.math.abs(x - rt) <= tol
        val nearT = kotlin.math.abs(y - t) <= tol
        val nearB = kotlin.math.abs(y - b) <= tol

        if (nearL && nearT) return DragHandle.TOP_LEFT
        if (nearR && nearT) return DragHandle.TOP_RIGHT
        if (nearL && nearB) return DragHandle.BOTTOM_LEFT
        if (nearR && nearB) return DragHandle.BOTTOM_RIGHT

        if (nearL && y in (t - tol)..(b + tol)) return DragHandle.LEFT
        if (nearR && y in (t - tol)..(b + tol)) return DragHandle.RIGHT
        if (nearT && x in (l - tol)..(rt + tol)) return DragHandle.TOP
        if (nearB && x in (l - tol)..(rt + tol)) return DragHandle.BOTTOM

        return DragHandle.NONE
    }

    private fun updateCropHandle(r: Rect, handle: DragHandle, curX: Float, curY: Float) {
        val minSize = (24f * density).toInt()
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val ix = curX.toInt().coerceIn(0, w)
        val iy = curY.toInt().coerceIn(0, h)

        when (handle) {
            DragHandle.LEFT -> r.left = kotlin.math.min(ix, r.right - minSize)
            DragHandle.RIGHT -> r.right = kotlin.math.max(ix, r.left + minSize)
            DragHandle.TOP -> r.top = kotlin.math.min(iy, r.bottom - minSize)
            DragHandle.BOTTOM -> r.bottom = kotlin.math.max(iy, r.top + minSize)
            DragHandle.TOP_LEFT -> {
                r.left = kotlin.math.min(ix, r.right - minSize)
                r.top = kotlin.math.min(iy, r.bottom - minSize)
            }
            DragHandle.TOP_RIGHT -> {
                r.right = kotlin.math.max(ix, r.left + minSize)
                r.top = kotlin.math.min(iy, r.bottom - minSize)
            }
            DragHandle.BOTTOM_LEFT -> {
                r.left = kotlin.math.min(ix, r.right - minSize)
                r.bottom = kotlin.math.max(iy, r.top + minSize)
            }
            DragHandle.BOTTOM_RIGHT -> {
                r.right = kotlin.math.max(ix, r.left + minSize)
                r.bottom = kotlin.math.max(iy, r.top + minSize)
            }
            else -> {}
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isFadingOut) return true

        val x = event.x
        val y = event.y
        val screenX = event.rawX
        val screenY = event.rawY

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = screenX
                downY = screenY
                pendingConfirm = false
                pendingCancel = false

                // 0. 优先命中截全屏悬浮按钮 (FAB 是 View 本地坐标)
                val fabDx = x - fabX
                val fabDy = y - fabY
                if (captureEnabled && fabDx * fabDx + fabDy * fabDy <= fabRadius * fabRadius) {
                    isFabTouching = true
                    fabTouchDownX = x
                    fabTouchDownY = y
                    fabDownCenterX = fabX
                    fabDownCenterY = fabY
                    invalidate()
                    return true
                }

                // 1. 检查是否点击了操作按钮 (confirmBtnRect 和 cancelBtnRect 均在屏幕绝对坐标系下)
                if (pickedRegion != null && isInteractiveCrop) {
                    if (confirmBtnRect.contains(screenX, screenY)) {
                        pendingConfirm = true
                        return true
                    }
                    if (cancelBtnRect.contains(screenX, screenY)) {
                        pendingCancel = true
                        return true
                    }

                    // 2. 检查是否命中 4 边或 4 角把手 (使用屏幕绝对坐标)
                    val handle = hitTestHandle(pickedRegion!!, screenX, screenY)
                    if (handle != DragHandle.NONE) {
                        activeHandle = handle
                        initialCropRect.set(pickedRegion!!)
                        return true
                    }

                    // 3. 按下点在框内部（非把手）→ 标记为候选平移，但先不拦截长按与点击检测
                    if (pickedRegion!!.contains(screenX.toInt(), screenY.toInt())) {
                        isCandidateForMove = true
                        initialCropRect.set(pickedRegion!!)
                    } else {
                        isCandidateForMove = false
                    }
                } else {
                    isCandidateForMove = false
                }

                activeHandle = DragHandle.NONE
                gestureDetector.onTouchEvent(event)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                // 0. 拖动截全屏悬浮按钮
                if (isFabTouching) {
                    val newCx = (fabDownCenterX + (x - fabTouchDownX))
                        .coerceIn(fabRadius, width - fabRadius)
                    val newCy = (fabDownCenterY + (y - fabTouchDownY))
                        .coerceIn(fabRadius, height - fabRadius)
                    fabX = newCx
                    fabY = newCy
                    invalidate()
                    return true
                }

                // 1. 空白处滑动重划框或框内滑动平移判定 (使用屏幕绝对坐标)
                val dx = screenX - downX
                val dy = screenY - downY
                val distSq = dx * dx + dy * dy
                val touchSlopSq = touchSlop * touchSlop

                if (activeHandle == DragHandle.NONE && distSq > touchSlopSq) {
                    // 一旦手指移动超过 touchSlop，立即取消 GestureDetector 长按计时，防止长按退出打断滑动框选
                    val cancelEv = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    gestureDetector.onTouchEvent(cancelEv)
                    cancelEv.recycle()

                    if (isCandidateForMove) {
                        activeHandle = DragHandle.MOVE
                    } else if (captureEnabled) {
                        activeHandle = DragHandle.NEW_BOX
                        isInteractiveCrop = true
                    }
                }

                // 2. 整体移动选框 (在屏幕绝对坐标系下平移并限幅)
                if (pickedRegion != null && activeHandle == DragHandle.MOVE) {
                    val dm = resources.displayMetrics
                    val newLeft = (initialCropRect.left + dx.toInt()).coerceIn(0, dm.widthPixels - initialCropRect.width())
                    val newTop = (initialCropRect.top + dy.toInt()).coerceIn(0, dm.heightPixels - initialCropRect.height())
                    pickedRegion = Rect(
                        newLeft,
                        newTop,
                        newLeft + initialCropRect.width(),
                        newTop + initialCropRect.height()
                    )
                    invalidate()
                    return true
                }

                // 3. 拖拽已有选框的边界把手微调 (使用屏幕绝对坐标)
                if (pickedRegion != null && activeHandle != DragHandle.NONE && activeHandle != DragHandle.NEW_BOX) {
                    val cancelEv = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    gestureDetector.onTouchEvent(cancelEv)
                    cancelEv.recycle()
                    updateCropHandle(pickedRegion!!, activeHandle, screenX, screenY)
                    invalidate()
                    return true
                }

                if (activeHandle == DragHandle.NEW_BOX) {
                    val dm = resources.displayMetrics
                    val l = kotlin.math.min(downX, screenX).toInt().coerceIn(0, dm.widthPixels)
                    val t = kotlin.math.min(downY, screenY).toInt().coerceIn(0, dm.heightPixels)
                    val r = kotlin.math.max(downX, screenX).toInt().coerceIn(0, dm.widthPixels)
                    val b = kotlin.math.max(downY, screenY).toInt().coerceIn(0, dm.heightPixels)
                    val currentRawRect = Rect(l, t, r, b)

                    val isNarrow = com.moting.linkgo.image.ScreenRegionPicker.isNarrowSelection(currentRawRect, density)
                    if (isNarrow) {
                        dragGuideRect = currentRawRect
                        // 方案 B：窄选时实时异步计算相交联合大矩形，橙色高亮框实时吸附相交控件
                        val svc = com.google.android.accessibility.selecttospeak.SelectToSpeakService.getInstance()
                        if (svc != null) {
                            liveSnapJob?.cancel()
                            liveSnapJob = viewScope.launch {
                                val union = withContext(Dispatchers.Default) {
                                    com.moting.linkgo.image.ScreenRegionPicker.pickIntersectingUnion(svc, currentRawRect)
                                }
                                if (activeHandle == DragHandle.NEW_BOX) {
                                    pickedRegion = union ?: currentRawRect
                                    invalidate()
                                }
                            }
                        } else {
                            pickedRegion = currentRawRect
                        }
                    } else {
                        dragGuideRect = null
                        liveSnapJob?.cancel()
                        pickedRegion = currentRawRect
                    }
                    invalidate()
                    return true
                }

                gestureDetector.onTouchEvent(event)
                return true
            }

            MotionEvent.ACTION_UP -> {
                liveSnapJob?.cancel()
                liveSnapJob = null
                val guide = dragGuideRect
                dragGuideRect = null

                // 0. 截全屏悬浮按钮松手
                if (isFabTouching) {
                    isFabTouching = false
                    val moveDx = x - fabTouchDownX
                    val moveDy = y - fabTouchDownY
                    if (moveDx * moveDx + moveDy * moveDy <= fabDragThresholdSq) {
                        // 移动距离小 → 视为点击，触发截全屏
                        performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                        onCaptureFullScreen?.invoke()
                    }
                    // 否则为拖动，FAB 已停在新位置，无需额外操作
                    invalidate()
                    return true
                }

                // 1. 确认按钮点击
                if (pendingConfirm) {
                    pendingConfirm = false
                    if (confirmBtnRect.contains(screenX, screenY) && pickedRegion != null) {
                        performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                        val confirmed = Rect(pickedRegion!!)
                        onConfirmRegion?.invoke(confirmed)
                        return true
                    }
                }

                // 2. 取消按钮点击
                if (pendingCancel) {
                    pendingCancel = false
                    if (cancelBtnRect.contains(screenX, screenY)) {
                        performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                        onDismiss?.invoke()
                        return true
                    }
                }

                // 3. 划出新框结束：方案 B 虚实结合直发（若为窄选截取相交大矩形，否则截取实际框）
                if (activeHandle == DragHandle.NEW_BOX) {
                    activeHandle = DragHandle.NONE
                    val region = pickedRegion
                    val minSize = 16f * density
                    val isNarrow = (guide != null) || (region != null && com.moting.linkgo.image.ScreenRegionPicker.isNarrowSelection(region, density))
                    val isValid = region != null && ((region.width() >= minSize && region.height() >= minSize) || isNarrow)
                    if (region != null && isValid) {
                        performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                        val svc = com.google.android.accessibility.selecttospeak.SelectToSpeakService.getInstance()
                        val finalRegion = if (isNarrow && svc != null) {
                            if (pickedRegion != null && !com.moting.linkgo.image.ScreenRegionPicker.isNarrowSelection(pickedRegion!!, density)) {
                                pickedRegion!!
                            } else {
                                com.moting.linkgo.image.ScreenRegionPicker.snapIfNarrow(svc, guide ?: region, density)
                            }
                        } else {
                            region
                        }
                        pickedRegion = Rect(finalRegion)
                        invalidate()
                        onConfirmRegion?.invoke(Rect(finalRegion))
                    } else {
                        // 太小的框丢弃
                        pickedRegion = null
                        isInteractiveCrop = false
                        invalidate()
                    }
                    return true
                }

                // 4. 移动或边界把手调整结束：保持交互式状态供继续微调
                if (activeHandle != DragHandle.NONE) {
                    activeHandle = DragHandle.NONE
                    isCandidateForMove = false
                    invalidate()
                    return true
                }

                isCandidateForMove = false
                gestureDetector.onTouchEvent(event)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                liveSnapJob?.cancel()
                liveSnapJob = null
                dragGuideRect = null
                isFabTouching = false
                pendingConfirm = false
                pendingCancel = false
                activeHandle = DragHandle.NONE
                isCandidateForMove = false
                invalidate()
                return true
            }
        }

        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        liveSnapJob?.cancel()
        liveSnapJob = null
        dragGuideRect = null
        viewScope.cancel()
        removeCallbacks(hintFadeRunnable)
    }
}
