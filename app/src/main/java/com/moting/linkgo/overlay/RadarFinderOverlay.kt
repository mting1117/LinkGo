package com.moting.linkgo.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import com.google.android.accessibility.selecttospeak.SelectToSpeakService
import com.moting.linkgo.R
import com.moting.linkgo.model.EdgeGestureConfig
import com.moting.linkgo.model.LinkRegion
import com.moting.linkgo.LinkSelectionActivity
import com.moting.linkgo.util.HapticHelper
import com.moting.linkgo.util.WindowRouter
import kotlinx.coroutines.*
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 全屏透亮水波雷达扫掠与探照光圈 Overlay
 * 采用 100% 透明全屏 Canvas 高性能绘制：
 * - 方案 A：iOS 18 Siri 极光流体呼吸光弧 + 灵动气泡回弹环
 * - 链接预测逻辑 100% 对齐剪贴板 (WindowRouter.predictTargetFastLocal)
 * - 彻底消除胶囊边缘毛糙黑边 (阴影内缩隔离采样)
 * - 欧氏距离最近判定与多链接聚合识别 (支持多链接角标与直达 LinkSelectionActivity)
 * - 高亮框与 LinkHighlightOverlayView 1:1 像素级统一 (CornerPathEffect、processRects、Modern Pill 角标)
 */
class RadarFinderOverlay(private val service: Context) {

    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: RadarFinderView? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 缓存已扫描出来的屏幕链接列表
    private var scannedLinks: List<LinkRegion> = emptyList()


    /**
     * 启动雷达探照全屏交互
     * @param startX 手指滑入起点 X (屏幕绝对坐标)
     * @param startY 手指滑入起点 Y (屏幕绝对坐标)
     * @param config 手势与圆球配置
     * @param isRightEdge 是否从右边缘滑入
     * @param standby 待命态：外部调起时屏幕上并没有手指，先只显示提示，等用户按下再以其落点接管
     */
    fun show(startX: Float, startY: Float, config: EdgeGestureConfig, isRightEdge: Boolean = true, standby: Boolean = false) {
        if (overlayView != null) {
            dismissImmediate()
        }

        // 异步后台并行获取当前屏幕链接 (耗时约 20~30ms，调度到 Default 线程避免阻塞主线程导致出球卡顿)
        coroutineScope.launch(Dispatchers.Default) {
            try {
                // 实例可能已被回收或假死：此时扫描必然为空且静默失败，需显式提示并触发自愈
                val selectService = SelectToSpeakService.getInstance()?.takeIf {
                    com.moting.linkgo.util.AccessibilityUtils.isServiceUsableNow()
                }
                if (selectService == null) {
                    Log.w("RadarFinder", "无障碍服务不可用（缺失或假死），滑动直达无取词能力")
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            service,
                            "无障碍服务未就绪，正在自动恢复，请稍后重试手势",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    com.moting.linkgo.util.AccessibilityUtils.requestPassiveSelfHeal(service)
                    return@launch
                }

                // 只预扫描链接：图片不再预扫描，改由用户把指针停在某处指认
                // （程序猜"哪块像图片"实测既误判又漏判，见 ScreenRegionPicker）
                val links = selectService.scanCurrentScreen()

                Log.i("RadarFinder", "雷达扫描完成：链接 ${links.size} 个")

                withContext(Dispatchers.Main) {
                    scannedLinks = links
                    overlayView?.setLinks(links)
                }
            } catch (e: Exception) {
                Log.w("RadarFinder", "扫描屏幕链接异常: ${e.message}")
            }
        }

        val view = RadarFinderView(service, config, startX, startY, isRightEdge, standby, onDismiss = {
            dismissImmediate()
        })

        val params = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.FILL
        }

        @Suppress("DEPRECATION")
        view.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        try {
            windowManager.addView(view, params)
            overlayView = view
        } catch (e: Exception) {
            Log.e("RadarFinder", "挂载雷达探照全屏Overlay失败", e)
        }
    }

    /**
     * 向全屏 Overlay 派发连续触摸事件
     */
    fun dispatchTouchEvent(event: MotionEvent): Boolean {
        return overlayView?.dispatchTouchEvent(event) ?: false
    }

    /**
     * 立即关闭并销毁 Overlay
     */
    fun dismissImmediate() {
        overlayView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                Log.w("RadarFinder", "移除Overlay异常: ${e.message}")
            }
            overlayView = null
        }
    }

    /**
     * 自定义全屏绘制视图
     */
    private class RadarFinderView(
        context: Context,
        private val config: EdgeGestureConfig,
        private val startX: Float,
        private val startY: Float,
        private val isRightEdge: Boolean,
        isStandby: Boolean,
        private val onDismiss: () -> Unit
    ) : View(context) {

        private val viewScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        private val density = resources.displayMetrics.density
        private val isDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

        // 像素级尺寸配置
        private val ballRadiusPx = config.ballRadiusDp * density
        private val ballStrokeWidthPx = config.ballStrokeWidthDp * density
        private val fingerOffsetYPx = config.fingerOffsetYDp * density

        // 语义主题色 (跟随系统动态取色开关与深浅色模式)
        private val themeColor: Int
            get() = com.moting.linkgo.util.ThemeColorHelper.getAccentColor(context, isDark)
        private val surfaceColor = if (isDark) Color.parseColor("#202124") else Color.WHITE
        private val onPrimaryColor = if (isDark) Color.BLACK else Color.WHITE

        // 触摸与跟随状态
        // 待命态下杠杆基准要换成用户第一次按下的落点，因此不能是 val
        private var initialTouchX = startX
        private var initialTouchY = startY
        private var touchX = startX
        private var touchY = startY
        private var currentBallX = startX
        private var currentBallY = startY
        private var isTouching = !isStandby
        /** 待命态：此时屏幕上没有手指，先只挂提示，用户按下后转入正常交互 */
        private var standbyPending = isStandby
        private var links: List<LinkRegion> = emptyList()

        // 命中链接状态
        private var hitLinks: List<LinkRegion> = emptyList()

        /**
         * 指针停留后指认到的区域（最多一块）。
         *
         * 取代了原先"预扫描全屏图片区域、指针滑过即命中"的做法：图片位置改由用户
         * **停留指认**，指到哪一层就取哪一层，程序不再猜，因此不会再把状态栏图标、
         * 空输入框之类的东西当成图片，也不会漏掉类名被混淆的图片。
         */
        /** 是否正在执行截屏取图：一旦进入截屏，立即停止所有圆球与高亮绘制，避免截入残影 */
        private var isCapturing = false
        private var hitBounds: Rect? = null
        private var hitBoxRect: Rect? = null

        /** 指认成立时的指针位置，用于按位移距离判断是否撤销指认 */
        private var hitAnchorX = 0f
        private var hitAnchorY = 0f

        /** 停留指认的锚点：指针偏离超过阈值就重新计时，避免手抖把计时一直重置 */
        private var stayAnchorX = 0f
        private var stayAnchorY = 0f
        private var stayRunnable: Runnable? = null
        private var stayProgressAnimator: ValueAnimator? = null

        /** 停留进度 0..2 (0..1 为控件指认，1..2 为截全屏准备) */
        private var stayProgress = 0f
        private var isStayArmed = false
        private var isStayHitArmed = false
        private var isFullScreenArmed = false

        /** 区域框选状态：以停留点为起点拉动动态选框 */
        private var isBoxSelecting = false
        private var boxStartX = 0f
        private var boxStartY = 0f
        private var boxCurrentRect: Rect? = null
        private var snappedUnionRect: Rect? = null
        private var liveSnapJob: Job? = null

        // 胶囊展示信息 (100% 对齐 ClipboardCapsuleOverlay)
        private var currentAppIcon: Bitmap? = null
        private var prefixText: String = ""
        private var accentText: String = ""
        private var suffixText: String = ""
        private var badgeText: String? = null // 多链接角标 (例如 "2", "3", "9+")
        private var isManualSelect: Boolean = false

        // 剪贴板悬浮胶囊标准色彩体系 (100% 不透明纯正材质，杜绝切换时半透明透出深灰)
        private val darkBgColor = 0xFF202124.toInt()
        private val lightBgColor = 0xFFFFFFFF.toInt()
        private val darkTextColor = 0xFFE8EAED.toInt()
        private val lightTextColor = 0xFF202124.toInt()
        private val googleBlueLight = 0xFF1B73E8.toInt()
        private val googleBlueDark = 0xFF8AB4F8.toInt()
        // 多链接角标标准中性微胶囊色 (数字不采用主题色，遵循现代高质感微胶囊规范)
        private val darkCountBg = 0x26FFFFFF.toInt() // 15% 柔白中性底
        private val lightCountBg = 0x14000000.toInt() // 8% 浅灰中性底

        // 动效状态 (方案 A: 灵动气泡回弹环)
        private var bubbleScale = 0.25f
        private var bubbleAnimator: ValueAnimator? = null

        private var hudScale = 0f
        private var hudAlpha = 0f
        private var hudAnimator: ValueAnimator? = null
        private var isHudShowing = false
        private var ballHoverScale = 1.0f

        // 磁滞消退守护机制 (防抖宽限期：从原先迟缓的 320ms 压缩至 70ms，扫过行隙不闪退，离开链接瞬间利落收拢消退)
        private var activeTargetLinks: List<LinkRegion> = emptyList()
        private var dismissGraceRunnable: Runnable? = null
        private val DISMISS_GRACE_MS = 70L

        // 触感反馈状态机：
        // 1. lastHapticTime: 40ms 极短物理防抖，防止同一边界微颤，绝不吞噬正常滑动
        // 2. initialHapticSuppressedUntil: 80ms 唤起瞬时保护期，防止唤起时手势激活震动与指针初始落点粘连
        // 3. lastPhysicalTargetId: 当前物理命中的目标唯一标识，滑入空白时立即置空，再次移入时 100% 同步触发震动！
        private var lastHapticTime = 0L
        private val HAPTIC_DEBOUNCE_MS = 40L
        private val initialHapticSuppressedUntil = System.currentTimeMillis() + 80L
        private var lastPhysicalTargetId: String? = null

        /**
         * 停留指认时长。
         *
         * 指针不动满这么久，就把当前位置交给无障碍树指认区域。取图是高频动作，
         * 太久会显得迟钝，太短又会在正常滑动途中被误触发。
         */
        private val STAY_DURATION_MS = 800L

        /**
         * 停留计时的位移容差。
         *
         * 手指按住不动时仍会持续收到抖动 MOVE 事件，容差太小会让计时永远重置、
         * 用户等不到指认；容差太大则滑过一块图就可能被误判成"停下"。
         */
        private val STAY_TOLERANCE_PX = 24f * density

        /**
         * 指认成立后的保持范围：指针从指认点挪开超过这么多就撤销指认、交回链接判定。
         *
         * 按"位移距离"而不是"是否移出指认矩形"来判断。整块封面、整张卡片的矩形可能很大，
         * 要求移出区域才能切回链接会非常难用；按距离判定则挪开一截就够，
         * 同时又能容忍停留时手指的自然漂移——稍微动一下不会把指认抖掉。
         */
        private val STAY_HIT_RELEASE_PX = 32f * density

        // 单链接异步预测协程句柄与内存极速缓存 (0ms 秒弹首帧，消除展示延迟与并发竞态)
        private var predictionJob: Job? = null
        private val localTargetCache = mutableMapOf<String, Pair<Bitmap?, Triple<String, String, String>>>()

        // 基础绘制画笔
        private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ballGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ballFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        // 探照圆球高斯纯弥散立体阴影画笔 (使用 BlurMaskFilter 彻底杜绝 setShadowLayer 带来的硬边缘描边)
        private val ballShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.BLACK
            val blurRadius = 5.5f * density
            if (blurRadius > 0) {
                maskFilter = BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL)
            }
        }

        // 水泡流体动力学与形变物理状态 (变形程度翻倍：拉伸上限达 150%)
        private var bubbleAngle = 0f         // 瞬时运动方向角 (弧度)
        private var currentStretch = 0f      // 当前沿运动方向拉伸增量 (0.0f .. 1.50f)
        private val maxStretchRatio = 1.50f  // 最大拉伸形变比例翻倍至 150%，呈现极具张力的流体水滴拉丝态

        // 高亮框与角标画笔 (100% 同构 LinkHighlightOverlayView)
        private val highlightBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = if (isDark) 4.5f else 5.0f
            pathEffect = CornerPathEffect(12f)
            color = themeColor
        }
        private val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        /**
         * 图片高亮框画笔（橙）。
         *
         * 与屏幕识别的 LinkHighlightOverlayView 用同一个橙色值，保证「橙色 = 图片」这条语义
         * 在两个入口完全一致；链接仍用主题色。
         */
        private val imageBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = if (isDark) 4.5f else 5.0f
            pathEffect = CornerPathEffect(12f)
            color = Color.parseColor("#FFF57C00")
        }

        /** 方案 B：窄选物理引导虚线画笔 */
        private val guideLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.8f * density
            color = Color.argb(170, 255, 255, 255)
            pathEffect = DashPathEffect(floatArrayOf(5f * density, 3.5f * density), 0f)
        }
        private val pillStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }
        private val pillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            isFakeBoldText = true
        }

        // 悬浮胶囊画笔 (与 ClipboardCapsuleOverlay 完全同构，内缩消除边缘黑边)
        private val capsuleShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply { style = Paint.Style.FILL }
        private val capsuleBgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply { style = Paint.Style.FILL }
        private val capsuleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.0f * density
        }
        private val capsuleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 14f * density
            isFakeBoldText = true
        }
        private val capsuleAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 14f * density
            isFakeBoldText = true
        }
        private val capsuleBadgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val capsuleBadgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.8f * density
        }
        private val capsuleBadgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11.5f * density
            typeface = Typeface.DEFAULT_BOLD
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }

        private val tempPath = Path()

        init {
            // 启用软件绘制以获得最细腻平滑的高精度阴影
            setLayerType(LAYER_TYPE_SOFTWARE, null)

            // 配置胶囊材质色彩
            val darkBg = 0xF4202124.toInt()
            val lightBg = 0xF8FFFFFF.toInt()
            capsuleBgPaint.color = if (isDark) darkBg else lightBg

            val shadowColor = if (isDark) Color.argb(120, 0, 0, 0) else Color.argb(35, 0, 0, 0)
            val shadowRadius = 8f * density
            val shadowDy = 2.5f * density
            capsuleShadowPaint.color = shadowColor
            capsuleShadowPaint.setShadowLayer(shadowRadius, 0f, shadowDy, shadowColor)

            capsuleBorderPaint.color = Color.argb(0x28, 255, 255, 255)

            // 灵动气泡回弹环登场动画 (220ms 弹性气泡破茧涌现)
            bubbleAnimator = ValueAnimator.ofFloat(0.25f, 1.0f).apply {
                duration = 220
                interpolator = OvershootInterpolator(1.6f)
                addUpdateListener {
                    bubbleScale = it.animatedValue as Float
                    invalidate()
                }
                start()
            }

            // 初始化指针坐标
            updatePointerCoordinates(startX, startY, immediate = true)

            if (isStandby) {
                showStandbyHint()
            }
        }

        /**
         * 待命提示：外部调起时屏幕上没有手指，必须明说「按住即可接管」。
         *
         * 直接落到终态而不走 animateHud——待命层是在 addView 之前构造的，
         * 此处启动的动画会在挂载前就跑完，用户什么都看不到。
         */
        private fun showStandbyHint() {
            currentAppIcon = drawableToBitmap(
                context.applicationInfo.loadIcon(context.packageManager),
                (20 * density).roundToInt()
            )
            prefixText = ""
            accentText = "按住屏幕滑动"
            suffixText = ""
            badgeText = null
            isManualSelect = true
            hudAlpha = 1f
            hudScale = 1f
            isHudShowing = true
        }

        /**
         * 核心动力学：方案 A 单手指针动态杠杆投射 vs 触控跟随模式双模算法
         */
        private fun updatePointerCoordinates(tX: Float, tY: Float, immediate: Boolean = false) {
            val screenW = resources.displayMetrics.widthPixels.toFloat()
            val screenH = resources.displayMetrics.heightPixels.toFloat()

            val targetX: Float
            val targetY: Float

            when (config.controlMode) {
                com.moting.linkgo.model.PointerControlMode.REMOTE_POINTER -> {
                    // 方案 A：单手指针模式 (解耦输入输出空间，手指在舒适区滑动，指针通过动态杠杆全域投射)
                    val initDist = config.pointerInitialDistanceDp * density
                    val initOffsetH = (if (isRightEdge) -initDist * 0.58f else initDist * 0.58f)
                    val initOffsetV = -initDist // 初始浮起在手指视线上方舒适区
                    val anchorX = initialTouchX + initOffsetH
                    val anchorY = initialTouchY + initOffsetV

                    val deltaX = tX - initialTouchX
                    val deltaY = tY - initialTouchY

                    val sensY = config.pointerSensitivity.coerceIn(1.0f, 4.0f)
                    val sensX = sensY * 1.15f // 横向稍微增益，便于单手大拇指横跨对角屏幕

                    targetX = anchorX + deltaX * sensX
                    targetY = anchorY + deltaY * sensY
                }
                com.moting.linkgo.model.PointerControlMode.DIRECT_TOUCH -> {
                    // 触控跟随模式 (圆球跟随手指触摸点，支持 0dp 完全贴合触点中心)
                    if (config.fingerOffsetYDp == 0) {
                        targetX = tX
                        targetY = tY
                    } else {
                        val pointerLeadDist = fingerOffsetYPx
                        val leadDirX = if (isRightEdge) -0.65f else 0.65f
                        val leadDirY = -0.85f
                        targetX = tX + pointerLeadDist * leadDirX
                        targetY = tY + pointerLeadDist * leadDirY
                    }
                }
            }

            // 全屏安全边界限制（防止指针飞出屏幕视窗）
            val padH = ballRadiusPx + 8 * density
            val padV = ballRadiusPx + 16 * density
            val clampedX = targetX.coerceIn(padH, screenW - padH)
            val clampedY = targetY.coerceIn(padV, screenH - padV)

            if (immediate) {
                currentBallX = clampedX
                currentBallY = clampedY
                bubbleAngle = 0f
                currentStretch = 0f
            } else {
                // 水泡流体微滞后跟随：强化拉扯感，使形变更敏锐饱满
                val followFactor = when (config.controlMode) {
                    com.moting.linkgo.model.PointerControlMode.REMOTE_POINTER -> 0.65f
                    com.moting.linkgo.model.PointerControlMode.DIRECT_TOUCH -> if (config.fingerOffsetYDp == 0) 0.80f else 0.70f
                }
                val prevX = currentBallX
                val prevY = currentBallY

                currentBallX += (clampedX - currentBallX) * followFactor
                currentBallY += (clampedY - currentBallY) * followFactor

                val vx = currentBallX - prevX
                val vy = currentBallY - prevY
                val speed = hypot(vx, vy)

                if (config.bubbleSquishEnabled && speed > 1.0f * density) {
                    val targetAngle = Math.atan2(vy.toDouble(), vx.toDouble()).toFloat()
                    // 采用最短角差平滑旋转插值，杜绝边界翻滚抖动
                    var diff = targetAngle - bubbleAngle
                    while (diff > Math.PI) diff -= (Math.PI * 2).toFloat()
                    while (diff < -Math.PI) diff += (Math.PI * 2).toFloat()
                    bubbleAngle += diff * 0.48f

                    // 变形响应速度强化，饱和拉伸翻倍至 150%
                    val targetStretch = (speed / (14f * density)).coerceIn(0f, maxStretchRatio)
                    currentStretch += (targetStretch - currentStretch) * 0.55f
                } else {
                    currentStretch *= 0.75f
                }
            }
        }

        fun setLinks(newLinks: List<LinkRegion>) {
            links = newLinks
            checkCollision()
            postInvalidate()
        }

        private val stayDurationMs: Long get() = config.stayDurationMs.toLong().coerceIn(400L, 3000L)

        /**
         * 开始为"停留指认"计时。
         *
         * 每次指针明显挪动都重新计时：手指不动就不会有新的 MOVE 事件，
         * 计时自然走满；手抖（位移小于阈值）不该把计时重置掉，否则永远等不到。
         */
        private fun armStayTimer() {
            stayRunnable?.let { removeCallbacks(it) }
            stayProgressAnimator?.cancel()
            isStayArmed = true
            isStayHitArmed = false
            isFullScreenArmed = false
            stayProgress = 0f

            // 没有任何取图去向（未开「屏幕二维码识别」且无启用中的图片规则）时不做停留指认：
            // 指针停下也取不到东西，让计时走满只是给一个空承诺（见 SettingsCache.screenCaptureEnabled）
            if (!com.moting.linkgo.data.SettingsCache.screenCaptureEnabled) {
                isStayArmed = false
                return
            }

            val singleDuration = stayDurationMs
            stayProgressAnimator = ValueAnimator.ofFloat(0f, 2f).apply {
                this.duration = singleDuration * 2
                interpolator = LinearInterpolator()
                var phase1Triggered = false
                addUpdateListener { anim ->
                    val p = anim.animatedValue as Float
                    stayProgress = p
                    if (p >= 1f && !phase1Triggered) {
                        phase1Triggered = true
                        onStayPhase1Complete()
                    }
                    if (p >= 1.98f && !isFullScreenArmed && isTouching && !isBoxSelecting) {
                        isFullScreenArmed = true
                        onStayPhase2Complete()
                    }
                    invalidate()
                }
                start()
            }
        }

        /** 撤销停留计时与已指认/框选的区域 */
        private fun cancelStay(clearHit: Boolean) {
            isStayArmed = false
            isStayHitArmed = false
            isFullScreenArmed = false
            isBoxSelecting = false
            boxCurrentRect = null
            snappedUnionRect = null
            liveSnapJob?.cancel()
            liveSnapJob = null
            stayProgress = 0f
            stayRunnable?.let { removeCallbacks(it) }
            stayRunnable = null
            stayProgressAnimator?.cancel()
            stayProgressAnimator = null
            if (clearHit && hitBounds != null) {
                hitBounds = null
                postInvalidate()
            }
        }

        /** 视图被移除时把停留计时停掉，避免回调打到一个已经不在屏幕上的视图 */
        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            cancelStay(clearHit = false)
            viewScope.cancel()
        }

        /** 停留第 1 阶段走满（800ms）：把指针当前位置交给无障碍树指认控件区域 */
        private fun onStayPhase1Complete() {
            isStayHitArmed = true
            boxStartX = currentBallX
            boxStartY = currentBallY

            val svc = SelectToSpeakService.getInstance() ?: return
            val x = currentBallX.toInt()
            val y = currentBallY.toInt()

            viewScope.launch {
                val picked = withContext(Dispatchers.Default) {
                    com.moting.linkgo.image.ScreenRegionPicker.pick(svc, x, y)
                } ?: return@launch
                if (!isTouching || isBoxSelecting) return@launch
                applyStayHit(picked)
            }
        }

        /** 停留第 2 阶段走满（1600ms = 停留乘以 2）：锁定截全屏 */
        private fun onStayPhase2Complete() {
            if (config.collisionHapticEnabled) {
                HapticHelper.tick(context, this)
            }
            prefixText = "松手"
            accentText = "截取全屏"
            suffixText = ""
            badgeText = null
            isManualSelect = false
            currentAppIcon = getExpandIconBitmap()
            if (!isHudShowing) animateHud(show = true)
            postInvalidate()
        }

        /**
         * 应用一次指认结果：高亮该区域 + 触感 + 胶囊切换为"松手提取图片"。
         */
        private fun applyStayHit(bounds: Rect) {
            hitLinks = emptyList()
            hitBoxRect = null
            hitBounds = Rect(bounds)
            hitAnchorX = currentBallX
            hitAnchorY = currentBallY
            ballHoverScale = 1.16f

            val now = System.currentTimeMillis()
            if (config.collisionHapticEnabled && now > initialHapticSuppressedUntil &&
                (now - lastHapticTime >= HAPTIC_DEBOUNCE_MS)
            ) {
                lastHapticTime = now
                HapticHelper.tick(context, this)
            }

            switchToImageCapsule()
            postInvalidate()
        }

        /** 统一截图与分发，同时向全局会话管理器保存框选矩形，供重选时复原 */
        private fun triggerCaptureAndDispatch(targetBounds: Rect) {
            // 记录保存选区矩形，供后续可能的用户「重选」
            com.moting.linkgo.image.ImageSelectionSession.saveLastRegion(targetBounds)

            isCapturing = true
            hitBounds = null
            boxCurrentRect = null
            isBoxSelecting = false
            hitLinks = emptyList()
            visibility = View.INVISIBLE

            onDismiss()

            val svc = SelectToSpeakService.getInstance()
            if (svc == null) {
                Log.w("RadarFinder", "取图失败：无障碍服务已不可用")
                android.widget.Toast.makeText(
                    context, "无障碍服务已断开，无法取图", android.widget.Toast.LENGTH_SHORT
                ).show()
            } else {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    svc.captureAndDispatchImage(targetBounds)
                }, 60L)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            touchX = event.rawX
            touchY = event.rawY

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (standbyPending) {
                        standbyPending = false
                        isTouching = true
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        updatePointerCoordinates(event.rawX, event.rawY, immediate = true)
                        hudAnimator?.cancel()
                        hudAlpha = 0f
                        isHudShowing = false
                        activeTargetLinks = emptyList()
                    }

                    stayAnchorX = currentBallX
                    stayAnchorY = currentBallY
                    armStayTimer()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    updatePointerCoordinates(touchX, touchY, immediate = false)

                    val dxFromStay = currentBallX - boxStartX
                    val dyFromStay = currentBallY - boxStartY
                    val distStaySq = dxFromStay * dxFromStay + dyFromStay * dyFromStay
                    val toleranceSq = STAY_TOLERANCE_PX * STAY_TOLERANCE_PX

                    // 1. 若停留第 1 阶段已达成（控件指认成立）：
                    if (isStayHitArmed) {
                        if (!isBoxSelecting) {
                            // 离开停留点超过容差 -> 进入区域框选状态！
                            if (distStaySq > toleranceSq) {
                                isBoxSelecting = true
                                isFullScreenArmed = false
                                stayProgressAnimator?.cancel()
                                hitBounds = null // 控件高亮转为动态框选选区
                                activeTargetLinks = emptyList()
                                prefixText = "滑动"
                                accentText = "框选区域"
                                suffixText = "，松手截取"
                                badgeText = null
                                isManualSelect = false
                                currentAppIcon = getExpandIconBitmap()
                                if (!isHudShowing) animateHud(show = true)
                            }
                        } else {
                            // 正在框选滑动中：
                            if (distStaySq <= toleranceSq) {
                                // 滑回起点：取消框选回到初始状态！
                                isBoxSelecting = false
                                boxCurrentRect = null
                                cancelStay(clearHit = true)
                            } else {
                                // 持续以停留点为起点、当前点为终点更新选区
                                val l = kotlin.math.min(boxStartX, currentBallX).toInt()
                                val t = kotlin.math.min(boxStartY, currentBallY).toInt()
                                val r = kotlin.math.max(boxStartX, currentBallX).toInt()
                                val b = kotlin.math.max(boxStartY, currentBallY).toInt()
                                val newRect = Rect(l, t, r, b)
                                boxCurrentRect = newRect

                                // 方案 B：根据实时尺寸判断，窄选则异步计算相交大矩形，脱离窄选则清除吸附
                                val isNarrow = com.moting.linkgo.image.ScreenRegionPicker.isNarrowSelection(newRect, density)
                                if (isNarrow) {
                                    val svc = SelectToSpeakService.getInstance()
                                    if (svc != null) {
                                        liveSnapJob?.cancel()
                                        liveSnapJob = viewScope.launch {
                                            val union = withContext(Dispatchers.Default) {
                                                com.moting.linkgo.image.ScreenRegionPicker.pickIntersectingUnion(svc, newRect)
                                            }
                                            if (isBoxSelecting && isTouching) {
                                                snappedUnionRect = union
                                                invalidate()
                                            }
                                        }
                                    }
                                } else {
                                    liveSnapJob?.cancel()
                                    if (snappedUnionRect != null) {
                                        snappedUnionRect = null
                                    }
                                }
                            }
                        }
                    }

                    // 2. 若未处于框选状态，正常处理雷达探索与停留计时
                    if (!isBoxSelecting) {
                        if (hitBounds != null) {
                            val moveX = currentBallX - hitAnchorX
                            val moveY = currentBallY - hitAnchorY
                            if (moveX * moveX + moveY * moveY >
                                STAY_HIT_RELEASE_PX * STAY_HIT_RELEASE_PX
                            ) {
                                cancelStay(clearHit = true)
                            }
                        }
                        val dx = currentBallX - stayAnchorX
                        val dy = currentBallY - stayAnchorY
                        if (dx * dx + dy * dy > STAY_TOLERANCE_PX * STAY_TOLERANCE_PX) {
                            stayAnchorX = currentBallX
                            stayAnchorY = currentBallY
                            if (hitBounds == null) armStayTimer()
                        }
                        checkCollision()
                    }

                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    isTouching = false
                    liveSnapJob?.cancel()
                    liveSnapJob = null
                    dismissGraceRunnable?.let {
                        removeCallbacks(it)
                        dismissGraceRunnable = null
                    }

                    // 分支 1：停留乘以 2 截全屏
                    if (isFullScreenArmed) {
                        cancelStay(clearHit = false)
                        val screenBounds = Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
                        triggerCaptureAndDispatch(screenBounds)
                        return true
                    }

                    // 分支 2：停留后继续滑动区域框选截图（方案 B：窄选截取联合大矩形，否则截取实际框）
                    if (isBoxSelecting && boxCurrentRect != null) {
                        val crop = Rect(boxCurrentRect!!)
                        val minSize = 12f * density
                        val isNarrow = com.moting.linkgo.image.ScreenRegionPicker.isNarrowSelection(crop, density)
                        val isValid = (crop.width() > minSize && crop.height() > minSize) || isNarrow
                        if (isValid) {
                            cancelStay(clearHit = false)
                            val svc = SelectToSpeakService.getInstance()
                            val finalCrop = if (isNarrow && svc != null) {
                                snappedUnionRect ?: com.moting.linkgo.image.ScreenRegionPicker.snapIfNarrow(svc, crop, density)
                            } else {
                                crop
                            }
                            triggerCaptureAndDispatch(finalCrop)
                            return true
                        }
                    }

                    // 分支 3：停留指认控件松手截图（保持现状）
                    if (hitBounds != null) {
                        val targetBounds = Rect(hitBounds!!)
                        cancelStay(clearHit = false)
                        triggerCaptureAndDispatch(targetBounds)
                        return true
                    }

                    cancelStay(clearHit = false)

                    val currentHits = hitLinks
                    if (currentHits.isNotEmpty()) {
                        // 命中链接松手：硬件触觉反馈 (统一使用清脆一致的 tick 触感，告别突兀重震)
                        if (config.collisionHapticEnabled) {
                            HapticHelper.tick(context, this)
                        }

                        if (currentHits.size > 1) {
                            // 多链接聚合：100% 对齐剪贴板行为，调起 LinkSelectionActivity
                            val hitUrls = currentHits.map { it.url }
                            val intent = Intent(context, LinkSelectionActivity::class.java).apply {
                                putStringArrayListExtra("URLS", ArrayList(hitUrls))
                                putExtra("SOURCE", WindowRouter.DispatchSource.SMART.name)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        } else {
                            // 单链接直达：100% 对齐剪贴板/屏幕识别行为。
                            // 必须走 WindowRouter.openBrowser（内部 globalScope）：本视图随即退场销毁，
                            // viewScope 会被 cancel，挂在其上的跳转协程会在第一个挂起点被取消，
                            // 开启 Root 后跳转前的提权预检（su 往返数百毫秒）必超退场窗口 → 松手不跳转。
                            val targetUrl = currentHits.first().url
                            WindowRouter.openBrowser(context, targetUrl)
                        }

                        // 命中目标：全屏 Overlay 敏捷退场，立即释放触摸让路给目标应用
                        animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(80).withEndAction {
                            onDismiss()
                        }.start()
                    } else {
                        // 未命中（移到空白处放弃）：立即收拢胶囊，全屏极速淡出销毁，毫无拖沓
                        hudAnimator?.cancel()
                        hudAlpha = 0f
                        isHudShowing = false
                        animate().alpha(0f).setDuration(70).withEndAction {
                            onDismiss()
                        }.start()
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    isTouching = false
                    dismissGraceRunnable?.let {
                        removeCallbacks(it)
                        dismissGraceRunnable = null
                    }
                    onDismiss()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }


        /** 切换胶囊为「图片」形态：不预测应用，只说明松手即可取图 */
        private fun switchToImageCapsule() {
            val applyInfo = {
                predictionJob?.cancel()
                predictionJob = null
                val iconDrawable = context.getDrawable(R.drawable.ic_capsule_multi)
                    ?: context.applicationInfo.loadIcon(context.packageManager)
                currentAppIcon = drawableToBitmap(
                    iconDrawable,
                    (20 * density).roundToInt(),
                    tintColor = if (isDark) Color.WHITE else lightTextColor
                )
                prefixText = "松手提取"
                accentText = "图片"
                suffixText = ""
                badgeText = null
                isManualSelect = true
            }

            if (!isHudShowing || hudAlpha < 0.1f) {
                applyInfo()
                activeTargetLinks = emptyList()
                animateHud(show = true)
                return
            }

            // 与链接切换同一套「离屏合成」淡出淡入，避免两种形态切换时观感不一致
            isHudShowing = true
            hudAnimator?.cancel()
            val currentAlpha = hudAlpha
            var hasSwitchedData = false

            hudAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 110
                interpolator = LinearInterpolator()
                addUpdateListener { anim ->
                    val fraction = anim.animatedFraction
                    if (fraction < 0.35f) {
                        hudAlpha = currentAlpha + (0.05f - currentAlpha) * (fraction / 0.35f)
                    } else {
                        if (!hasSwitchedData) {
                            hasSwitchedData = true
                            applyInfo()
                            activeTargetLinks = emptyList()
                        }
                        hudAlpha = 0.05f + (1f - 0.05f) * ((fraction - 0.35f) / 0.65f)
                        hudScale = 1.0f
                    }
                    invalidate()
                }
                start()
            }
        }

        /**
         * 实时碰撞判定：最短欧氏几何距离排序 + 多链接组智能聚合 (精准基于指针当前物理中心)
         */
        private fun checkCollision() {
            // 已经指认到区域时链接一律让位：一次松手只有一个目标。
            // 不禁掉这一步的话，指针在指认区域内稍微一动就会重新命中链接，
            // 把刚指认的区域顶掉、胶囊变回「提取链接」——用户看到的就是指认一闪就没了。
            // 想切回链接只要把指针移出指认区域（MOVE 里按外扩容差撤销）。
            if (hitBounds != null) return

            // 这里只判链接：图片改由「指针停留」指认（见 onStayComplete），不走碰撞判定，
            // 因此没有链接时直接返回，不会影响取图——
            // 京东首页、B 站首页这类页面链接数为 0，指针停下照样能指认取图。
            if (links.isEmpty()) return

            val ballCenterX = currentBallX
            val ballCenterY = currentBallY
            val detectRadius = ballRadiusPx * 1.15f

            // 1. 扫描计算每个链接到圆心的最短欧氏距离（区分文字精准距离与宿主大框距离）
            var minDistanceToAnyLink = Float.MAX_VALUE
            // 精准文字直接命中的候选列表 (link to 文字距离)
            val preciseDirectCandidates = mutableListOf<Pair<LinkRegion, Float>>()
            // 大框命中的精准链接候选列表 (link to 文字距离)
            val boxCandidates = mutableListOf<Pair<LinkRegion, Float>>()
            // 纯回退大框候选列表 (link to 有效距离)
            val fallbackCandidates = mutableListOf<Pair<LinkRegion, Float>>()

            for (link in links) {
                // 计算文字矩形距离
                var distPrecise = Float.MAX_VALUE
                for (rect in link.rects) {
                    val cx = ballCenterX.coerceIn(rect.left.toFloat(), rect.right.toFloat())
                    val cy = ballCenterY.coerceIn(rect.top.toFloat(), rect.bottom.toFloat())
                    val d = hypot(ballCenterX - cx, ballCenterY - cy)
                    if (d < distPrecise) distPrecise = d
                }

                // 计算宿主大框距离 (nodeBounds)
                val box = link.nodeBounds
                val distBox = if (box != null) {
                    val cxBox = ballCenterX.coerceIn(box.left.toFloat(), box.right.toFloat())
                    val cyBox = ballCenterY.coerceIn(box.top.toFloat(), box.bottom.toFloat())
                    hypot(ballCenterX - cxBox, ballCenterY - cyBox)
                } else {
                    distPrecise
                }

                val effectiveDist = minOf(distPrecise, distBox)
                if (effectiveDist < minDistanceToAnyLink) {
                    minDistanceToAnyLink = effectiveDist
                }

                if (link.isPrecise) {
                    if (distPrecise <= detectRadius) {
                        preciseDirectCandidates.add(link to distPrecise)
                    } else if (distBox <= detectRadius) {
                        // 文字未直接碰到，但碰到了/滑入了宿主大框！
                        boxCandidates.add(link to distBox)
                    }
                } else {
                    if (effectiveDist <= detectRadius) {
                        fallbackCandidates.add(link to effectiveDist)
                    }
                }
            }

            // 2. 聚合判定处理
            val matchedList = mutableListOf<LinkRegion>()
            var targetBoxRect: Rect? = null

            if (preciseDirectCandidates.isNotEmpty()) {
                // === 优先级 1：圆球直接触碰到了精准文字（最高优先级）===
                preciseDirectCandidates.sortBy { it.second }

                // 磁滞防抖
                val currentTargetId = lastPhysicalTargetId
                var primaryLink = preciseDirectCandidates[0].first
                if (currentTargetId != null && preciseDirectCandidates.size > 1) {
                    val activeIndex = preciseDirectCandidates.indexOfFirst { it.first.url == currentTargetId }
                    if (activeIndex > 0) {
                        val firstDist = preciseDirectCandidates[0].second
                        val activeDist = preciseDirectCandidates[activeIndex].second
                        if (activeDist - firstDist < 5f * density) {
                            primaryLink = preciseDirectCandidates[activeIndex].first
                        }
                    }
                }

                val overlappingPrecise = preciseDirectCandidates.filter { candidate ->
                    candidate.first != primaryLink && isRectsOverlapping(candidate.first.rects, primaryLink.rects)
                }.map { it.first }

                if (overlappingPrecise.isNotEmpty()) {
                    matchedList.add(primaryLink)
                    matchedList.addAll(overlappingPrecise)
                } else {
                    matchedList.add(primaryLink)
                }
                // 精准文字触碰：高亮文字本身，不画大框
                targetBoxRect = null

            } else if (boxCandidates.isNotEmpty()) {
                // === 优先级 2：滑入精准链接的宿主大框空白处 ===
                // 按小球到大框的距离排序，取最近的大框
                boxCandidates.sortBy { it.second }
                val closestCandidate = boxCandidates.first().first
                val targetGroupId = closestCandidate.groupId
                targetBoxRect = closestCandidate.nodeBounds

                // 获取该宿主大框内的全部精准链接
                val groupPreciseLinks = links.filter { it.groupId == targetGroupId && it.isPrecise }
                if (groupPreciseLinks.size >= 2) {
                    // 大框内有多个精准链接：多链接聚合形态！弹出选择框！
                    matchedList.addAll(groupPreciseLinks)
                } else if (groupPreciseLinks.isNotEmpty()) {
                    // 大框内只有 1 个精准链接：单链接直达！
                    matchedList.add(groupPreciseLinks.first())
                } else {
                    matchedList.add(closestCandidate)
                }

            } else if (fallbackCandidates.isNotEmpty()) {
                // === 优先级 3：纯未定位回退大框 ===
                fallbackCandidates.sortBy { it.second }
                val firstFallback = fallbackCandidates.first().first
                val targetGroupId = firstFallback.groupId
                targetBoxRect = firstFallback.nodeBounds ?: firstFallback.rects.firstOrNull()

                val groupAllUnprecise = links.filter { it.groupId == targetGroupId && !it.isPrecise }
                if (groupAllUnprecise.size >= 2) {
                    matchedList.addAll(groupAllUnprecise)
                } else {
                    matchedList.add(firstFallback)
                }
            }

            // 3. 物理命中状态与触感反馈状态机
            hitBoxRect = targetBoxRect
            hitLinks = matchedList

            if (matchedList.isNotEmpty()) {
                // 扫到任何有效链接，立即取消消退延时！
                dismissGraceRunnable?.let {
                    removeCallbacks(it)
                    dismissGraceRunnable = null
                }

                ballHoverScale = 1.16f

                // A. 物理碰撞与即时震动判定：
                // 只要当前命中的主要目标与上次不同（从空白滑入链接，或在不同链接之间切换），立即同步触发震动！
                val primaryTarget = matchedList.first()
                val targetId = if (matchedList.size > 1) {
                    "group_${primaryTarget.groupId}_${matchedList.size}"
                } else {
                    primaryTarget.url
                }
                if (targetId != lastPhysicalTargetId) {
                    lastPhysicalTargetId = targetId
                    val now = System.currentTimeMillis()
                    if (config.collisionHapticEnabled && now > initialHapticSuppressedUntil && (now - lastHapticTime >= HAPTIC_DEBOUNCE_MS)) {
                        lastHapticTime = now
                        HapticHelper.tick(context, this)
                    }
                }

                // B. 胶囊视觉展示状态机（带磁滞平滑，整体离屏淡入淡出）：
                val isNewTarget = (matchedList != activeTargetLinks)
                if (isNewTarget) {
                    switchCapsuleWithFade(matchedList)
                }

                if (!isHudShowing) {
                    animateHud(show = true)
                }
            } else {
                // 指针离开所有有效链接滑入空白区域：
                ballHoverScale = 1.0f

                // 关键点：立即清空物理碰撞状态！用户再次移入任何链接（包括刚才移出的链接）时 100% 同步触发物理碰撞震动！
                lastPhysicalTargetId = null
                hitBoxRect = null

                // 智能空间距离感知极速消退机制：
                // 若指针距离最近链接已明显离开（超过 detectRadius * 1.35f）：0ms 延迟立即收拢淡出，彻底消灭消失延迟！
                // 只有当指针处于两行文字间的微隙临界区（距离 <= detectRadius * 1.35f）时，才给予 70ms 瞬态微缓冲，杜绝行隙抽搐
                val isFarAway = minDistanceToAnyLink > (detectRadius * 1.35f)

                if (isHudShowing) {
                    if (isFarAway) {
                        // 明确移开至空白区：立即取消延时计时，0ms 延迟极速收起淡出！
                        dismissGraceRunnable?.let {
                            removeCallbacks(it)
                            dismissGraceRunnable = null
                        }
                        activeTargetLinks = emptyList()
                        animateHud(show = false)
                    } else if (dismissGraceRunnable == null) {
                        // 行隙临界微动：保留仅 70ms 瞬态微缓冲
                        dismissGraceRunnable = Runnable {
                            if (hitLinks.isEmpty()) {
                                activeTargetLinks = emptyList()
                                animateHud(show = false)
                            }
                            dismissGraceRunnable = null
                        }
                        postDelayed(dismissGraceRunnable, DISMISS_GRACE_MS)
                    }
                }
            }
        }

        /**
         * 检查两个矩形列表是否存在真实的几何相交重叠
         */
        private fun isRectsOverlapping(rects1: List<Rect>, rects2: List<Rect>): Boolean {
            for (r1 in rects1) {
                for (r2 in rects2) {
                    if (Rect.intersects(r1, r2)) return true
                }
            }
            return false
        }

        /**
         * 胶囊整体（连同阴影）平滑淡出淡入切换新目标，离屏统一合成，彻底杜绝底层阴影穿透
         */
        private fun switchCapsuleWithFade(matchedList: List<LinkRegion>) {
            if (!isHudShowing || hudAlpha < 0.1f) {
                activeTargetLinks = matchedList
                updateCapsuleInfo(matchedList)
                animateHud(show = true)
                return
            }

            isHudShowing = true
            hudAnimator?.cancel()
            val currentAlpha = hudAlpha
            var hasSwitchedData = false

            hudAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 110
                interpolator = LinearInterpolator()
                addUpdateListener { anim ->
                    val fraction = anim.animatedFraction
                    if (fraction < 0.35f) {
                        // 前 35% 时间（约 38ms）：整体极速淡出至 0.05f
                        val outF = fraction / 0.35f
                        hudAlpha = currentAlpha + (0.05f - currentAlpha) * outF
                    } else {
                        // 达到切换点瞬间：更新目标数据与尺寸排版
                        if (!hasSwitchedData) {
                            hasSwitchedData = true
                            activeTargetLinks = matchedList
                            updateCapsuleInfo(matchedList)
                        }
                        // 后 65% 时间（约 72ms）：整体敏捷淡入至 1.0f
                        val inF = (fraction - 0.35f) / 0.65f
                        hudAlpha = 0.05f + (1.0f - 0.05f) * inF
                        hudScale = 1.0f
                    }
                    invalidate()
                }
                start()
            }
        }

        /**
         * 100% 对齐剪贴板胶囊预测逻辑与排版，严格修正深浅模式各个元素的颜色对比度
         */
        private fun updateCapsuleInfo(matchedList: List<LinkRegion>) {
            val isDarkTheme = isDark
            val themeTextColor = if (isDarkTheme) darkTextColor else lightTextColor

            if (matchedList.size > 1) {
                // 取消进行中的单链接预测，防止乱序回调覆盖
                predictionJob?.cancel()
                predictionJob = null

                // 多链接聚合形态：图标、"链接选择"、数字角标
                val multiIcon = context.getDrawable(R.drawable.ic_capsule_multi)
                    ?: context.applicationInfo.loadIcon(context.packageManager)
                // 关键修复：多链接矢量图标在深浅色模式下精准着色，浅色深灰黑、深色纯白，杜绝纯白隐形
                currentAppIcon = drawableToBitmap(
                    multiIcon,
                    (20 * density).roundToInt(),
                    tintColor = if (isDarkTheme) Color.WHITE else themeTextColor
                )

                prefixText = ""
                accentText = "链接选择"
                suffixText = ""
                badgeText = if (matchedList.size > 9) "9+" else matchedList.size.toString()
                isManualSelect = true
            } else {
                // 取消前一个可能仍在解析的单链接协程，确保仅最新目标生效
                predictionJob?.cancel()
                val link = matchedList.first()
                badgeText = null

                // 1. 极速内存缓存检查：若曾预测过该 URL，0ms 同步秒弹，彻底消灭展示延迟
                val cached = localTargetCache[link.url]
                if (cached != null) {
                    currentAppIcon = cached.first
                    prefixText = cached.second.first
                    accentText = cached.second.second
                    suffixText = cached.second.third
                    isManualSelect = (accentText == "手动选择")
                    invalidate()
                    return
                }

                // 2. 首次未命中缓存：先赋予安全即时默认排版，保证首帧 0 延迟秒弹，绝无空白等待
                if (accentText.isEmpty()) {
                    prefixText = "打开"
                    accentText = "链接"
                    suffixText = ""
                    invalidate()
                }

                predictionJob = viewScope.launch {
                    try {
                        val predicted = WindowRouter.predictTargetFastLocal(context, link.url)
                        val iconDrawable = WindowRouter.resolvePredictedIcon(context, predicted)
                        val iconBmp = drawableToBitmap(iconDrawable, (20 * density).roundToInt())
                        currentAppIcon = iconBmp

                        val isReDispatch = predicted.isReDispatch
                        isManualSelect = predicted.packageName.isNullOrBlank() && !isReDispatch
                        if (isReDispatch) {
                            prefixText = "由 "
                            accentText = predicted.label
                            suffixText = " 分发"
                        } else if (isManualSelect) {
                            prefixText = ""
                            accentText = "手动选择"
                            suffixText = ""
                        } else {
                            prefixText = "在"
                            accentText = predicted.label
                            suffixText = "中打开"
                        }
                        // 写入内存高速缓存
                        localTargetCache[link.url] = Pair(iconBmp, Triple(prefixText, accentText, suffixText))
                        invalidate()

                        // 2. 后台异步深度解析（短链302与多层分发）
                        val deepPredicted = withContext(Dispatchers.IO) {
                            WindowRouter.predictTargetDeepResolve(context, link.url)
                        }
                        if (deepPredicted != predicted) {
                            val deepIconDrawable = WindowRouter.resolvePredictedIcon(context, deepPredicted)
                            val deepIconBmp = drawableToBitmap(deepIconDrawable, (20 * density).roundToInt())
                            currentAppIcon = deepIconBmp

                            val deepIsReDispatch = deepPredicted.isReDispatch
                            isManualSelect = deepPredicted.packageName.isNullOrBlank() && !deepIsReDispatch
                            if (deepIsReDispatch) {
                                prefixText = "由 "
                                accentText = deepPredicted.label
                                suffixText = " 分发"
                            } else if (isManualSelect) {
                                prefixText = ""
                                accentText = "手动选择"
                                suffixText = ""
                            } else {
                                prefixText = "在"
                                accentText = deepPredicted.label
                                suffixText = "中打开"
                            }
                            localTargetCache[link.url] = Pair(deepIconBmp, Triple(prefixText, accentText, suffixText))
                            invalidate()
                        }
                    } catch (e: Exception) {
                        Log.w("RadarFinder", "预测失败: ${e.message}")
                    }
                }
            }
        }

        private fun animateHud(show: Boolean) {
            isHudShowing = show
            hudAnimator?.cancel()
            val startScale = hudScale
            val targetScale = if (show) 1.0f else 0.75f
            val startAlpha = hudAlpha
            val targetAlpha = if (show) 1.0f else 0.0f
            hudAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = if (show) 150 else 90
                interpolator = if (show) OvershootInterpolator(1.2f) else DecelerateInterpolator()
                addUpdateListener {
                    val f = it.animatedFraction
                    hudScale = startScale + (targetScale - startScale) * f
                    hudAlpha = startAlpha + (targetAlpha - startAlpha) * f
                    invalidate()
                }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            if (isCapturing) return
            super.onDraw(canvas)

            val screenW = width.toFloat()
            val screenH = height.toFloat()
            if (screenW <= 0 || screenH <= 0) return

            // --- 1. 高亮框绘制 (100% 同构移植 LinkHighlightOverlayView) ---
            // 先画图片/框选（橙色大框），再画链接（蓝色小框叠在上层）——与屏幕识别的层级一致
            // isFullScreenArmed 时已锁定截全屏，不再显示控件框以免误解
            if (!isFullScreenArmed) {
                hitBounds?.let { drawHighlightedRegion(canvas, it) }
            }
            boxCurrentRect?.let { curRect ->
                val isNarrow = com.moting.linkgo.image.ScreenRegionPicker.isNarrowSelection(curRect, density)
                if (isNarrow) {
                    // 方案 B 虚实双层对照：
                    // 1. 半透明虚线引导框：指示用户当前手指实际划出的窄线位置
                    val cornerRadius = 3f * density
                    tempPath.reset()
                    tempPath.addRoundRect(RectF(curRect), cornerRadius, cornerRadius, Path.Direction.CW)
                    canvas.drawPath(tempPath, guideLinePaint)

                    // 2. 橙色高亮实线大框：实时指示相交控件联合出的大矩形（若后台正在计算则先以当前框过渡）
                    val targetRect = snappedUnionRect ?: curRect
                    drawHighlightedRegion(canvas, targetRect)
                } else {
                    // 自由框选：直接绘制手指实际框选的大矩形
                    drawHighlightedRegion(canvas, curRect)
                }
            }
            if (hitLinks.isNotEmpty()) {
                highlightBorderPaint.color = themeColor
                drawHighlightedLinks(canvas)
            }

            // --- 3. 探照圆球绘制 (弥散立体阴影 + 弹性形变 + 纯净通透光环) ---
            if (isTouching) {
                val ballX = currentBallX
                val ballY = currentBallY
                val baseRadius = ballRadiusPx * bubbleScale * ballHoverScale
                val alphaInt = (config.ballAlpha * 255).roundToInt().coerceIn(0, 255)

                // 翻倍强化形变：长轴拉伸高达 150% (2.5x)，短轴根据物理体积守恒收缩挤压
                // 框选模式下禁止形变与旋转，十字标固定角度（水平/垂直严格对准屏幕轴线）
                val isSquish = config.bubbleSquishEnabled && !isBoxSelecting
                val stretch = if (isSquish) currentStretch else 0f
                val scaleLong = 1.0f + stretch
                val scaleShort = (1.0f - stretch * 0.32f).coerceAtLeast(0.48f)

                canvas.save()
                canvas.translate(ballX, ballY)
                if (isSquish && bubbleAngle != 0f) {
                    canvas.rotate(Math.toDegrees(bubbleAngle.toDouble()).toFloat())
                }
                canvas.scale(scaleLong, scaleShort)

                // 0. 探照圆球高斯纯弥散立体阴影 (纯模糊发散光晕，无任何实体描边，真实还原空间悬浮感)
                ballShadowPaint.strokeWidth = ballStrokeWidthPx + 1.0f * density
                val shadowAlphaFactor = if (isDark) 0.22f else 0.12f
                ballShadowPaint.alpha = (alphaInt * shadowAlphaFactor).roundToInt()
                canvas.drawCircle(0f, 0f, baseRadius, ballShadowPaint)

                // 仅在用户主动在设置中选择「半透明柔光圆球」时才填充底色，默认完全纯净通透
                if (config.ballStyle == com.moting.linkgo.model.BallStyle.FILLED_TRANSLUCENT) {
                    ballFillPaint.color = themeColor
                    ballFillPaint.alpha = (alphaInt * 0.28f).roundToInt()
                    canvas.drawCircle(0f, 0f, baseRadius, ballFillPaint)
                }

                // A. 探照气泡外柔光圈
                ballGlowPaint.style = Paint.Style.STROKE
                ballGlowPaint.color = themeColor
                ballGlowPaint.strokeWidth = ballStrokeWidthPx * 2.2f
                ballGlowPaint.alpha = (alphaInt * (if (isBoxSelecting) 0.20f else 0.35f)).roundToInt()
                canvas.drawCircle(0f, 0f, baseRadius, ballGlowPaint)

                // B. 探照气泡主膜精密描边环
                ballPaint.style = Paint.Style.STROKE
                ballPaint.color = themeColor
                ballPaint.strokeWidth = ballStrokeWidthPx
                ballPaint.alpha = if (isBoxSelecting) (alphaInt * 0.50f).roundToInt() else alphaInt
                canvas.drawCircle(0f, 0f, baseRadius, ballPaint)

                // C. 极光中心定位微光核 / 框选十字准星（固定水平与垂直，绝对不随速度旋转）
                if (isBoxSelecting) {
                    val crossLen = 9f * density
                    val strokeW = 2.2f * density

                    // 底层反差光晕描边：深色模式下用黑色底层，浅色模式下用白色底层，确保无论何种背景均清晰锐利
                    ballPaint.style = Paint.Style.STROKE
                    ballPaint.strokeWidth = strokeW + 2f * density
                    ballPaint.color = if (isDark) Color.BLACK else Color.WHITE
                    ballPaint.alpha = (alphaInt * 0.70f).roundToInt()
                    canvas.drawLine(-crossLen, 0f, crossLen, 0f, ballPaint)
                    canvas.drawLine(0f, -crossLen, 0f, crossLen, ballPaint)

                    // 顶层主题色正交精密十字标（固定 0° 绝对不旋转）
                    ballPaint.strokeWidth = strokeW
                    ballPaint.color = themeColor
                    ballPaint.alpha = alphaInt
                    canvas.drawLine(-crossLen, 0f, crossLen, 0f, ballPaint)
                    canvas.drawLine(0f, -crossLen, 0f, crossLen, ballPaint)

                    // 中心 2dp 精确定位核
                    ballPaint.style = Paint.Style.FILL
                    canvas.drawCircle(0f, 0f, 2f * density, ballPaint)
                } else {
                    ballPaint.style = Paint.Style.FILL
                    ballPaint.color = themeColor
                    ballPaint.alpha = (alphaInt * 0.85f).roundToInt()
                    canvas.drawCircle(0f, 0f, 3 * density, ballPaint)
                }

                // D. 停留指认 / 截全屏进度环
                if (isStayArmed && stayProgress > 0f) {
                    ballPaint.style = Paint.Style.STROKE
                    ballPaint.color = imageBorderPaint.color
                    ballPaint.strokeWidth = 3f * density
                    ballPaint.alpha = alphaInt
                    val ringRect = RectF(
                        -baseRadius - 6f * density,
                        -baseRadius - 6f * density,
                        baseRadius + 6f * density,
                        baseRadius + 6f * density
                    )
                    if (stayProgress <= 1f) {
                        canvas.drawArc(ringRect, -90f, 360f * stayProgress, false, ballPaint)
                    } else {
                        canvas.drawArc(ringRect, -90f, 360f, false, ballPaint)
                        ballPaint.color = themeColor
                        ballPaint.strokeWidth = 4.5f * density
                        canvas.drawArc(ringRect, -90f, 360f * (stayProgress - 1f), false, ballPaint)
                    }
                }

                canvas.restore()

                // 果冻弹性衰减：手指静止或微停滞时，以屏幕刷新率柔和收拢回正圆
                if (isSquish && currentStretch > 0.003f) {
                    currentStretch *= 0.78f
                    postInvalidateOnAnimation()
                } else if (!isSquish) {
                    currentStretch = 0f
                }
            }

            // --- 4. 悬浮胶囊绘制 (彻底消除边缘毛糙黑边，支持多链接角标) ---
            if (hudAlpha > 0.01f && accentText.isNotEmpty()) {
                drawCapsule(canvas, screenW, screenH)
            }
        }

        /**
         * 指认区域高亮框绘制。
         *
         * 与链接框刻意不同：**不做行内合并、不接宿主大框、不画角标**。
         * 这块区域来自用户停留指认，本身就是无障碍树里的一个完整矩形，
         * 套链接那套「多段文字合并 + 冲突角标」只会画蛇添足。
         */
        private fun drawHighlightedRegion(canvas: Canvas, bounds: Rect) {
            val rectF = RectF(bounds)
            // 圆角与屏幕识别保持一致（约 4dp，对应 CornerPathEffect(12f) 像素级）
            val cornerRadius = 4f * density
            tempPath.reset()
            tempPath.addRoundRect(rectF, cornerRadius, cornerRadius, Path.Direction.CW)
            canvas.drawPath(tempPath, imageBorderPaint)
        }

        /**
         * 高亮框与角标绘制：100% 像素级对齐 LinkHighlightOverlayView
         */
        private fun drawHighlightedLinks(canvas: Canvas) {
            if (hitLinks.isEmpty()) return

            val box = hitBoxRect
            if (box != null) {
                // === 绘制宿主大框 ===
                val rectF = RectF(box).apply { inset(-4f * density, -4f * density) }
                val cornerRadius = 12f * density
                tempPath.reset()
                tempPath.addRoundRect(rectF, cornerRadius, cornerRadius, Path.Direction.CW)
                canvas.drawPath(tempPath, highlightBorderPaint)

                // 绘制角标：位于大框左上角
                val isMulti = hitLinks.size > 1
                val badgeVal = if (isMulti) hitLinks.size.toString() else "1"
                drawModernPill(canvas, box, badgeVal, isConflict = isMulti)
            } else {
                // === 绘制精准文字小矩形 ===
                val allRects = mutableListOf<Rect>()
                for (link in hitLinks) {
                    allRects.addAll(link.rects)
                }
                if (allRects.isEmpty()) return

                val processed = processRects(allRects)
                val combinedPath = Path()
                for (r in processed) {
                    val rectF = RectF(r).apply { inset(-6f, -3f) }
                    tempPath.reset()
                    tempPath.addRect(rectF, Path.Direction.CW)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                        combinedPath.op(tempPath, Path.Op.UNION)
                    } else {
                        combinedPath.addRect(rectF, Path.Direction.CW)
                    }
                }

                // 仅绘制高亮边缘（圆角霓虹边框）
                canvas.drawPath(combinedPath, highlightBorderPaint)

                // 绘制 Modern Pill 角标
                val firstRect = processed.firstOrNull() ?: return
                val isMulti = hitLinks.size > 1
                val badgeVal = if (isMulti) hitLinks.size.toString() else "1"
                drawModernPill(canvas, firstRect, badgeVal, isConflict = isMulti)
            }
        }

        /**
         * 同构行内合并算法
         */
        private fun processRects(rects: List<Rect>): List<Rect> {
            val processed = mutableListOf<Rect>()
            val sorted = rects.sortedWith(compareBy({ it.top }, { it.left }))
            for (rect in sorted) {
                if (processed.isEmpty()) { processed.add(Rect(rect)); continue }
                val last = processed.last()
                val lastCenterY = (last.top + last.bottom) / 2f
                val currentCenterY = (rect.top + rect.bottom) / 2f
                val isSameLine = Math.abs(lastCenterY - currentCenterY) < (last.height() * 0.4f)
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

        /**
         * 绘制 Modern Pill 角标 (100% 对齐 LinkHighlightOverlayView)
         */
        private fun drawModernPill(canvas: Canvas, rect: Rect, text: String, isConflict: Boolean) {
            pillBgPaint.color = themeColor
            pillBgPaint.alpha = 255
            pillStrokePaint.color = surfaceColor
            pillStrokePaint.alpha = 255
            pillTextPaint.color = onPrimaryColor

            val textSize = if (isConflict) 24f else 28f
            val padding = if (isConflict) 16f else 18f
            pillTextPaint.textSize = textSize
            val textWidth = pillTextPaint.measureText(text)

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
                padding + iconSize + verticalMargin + textHeight + padding - 8f
            } else {
                44f
            }

            val radius = if (isConflict) totalWidth / 2f else height / 2f
            val pillTop = Math.max(rect.top.toFloat(), 10f)
            val targetX = rect.left.toFloat() - totalWidth - 6f
            val pillLeft = if (targetX < 10f) rect.left.toFloat() + 6f else targetX
            val pillRect = RectF(pillLeft, pillTop, pillLeft + totalWidth, pillTop + height)

            canvas.drawRoundRect(pillRect, radius, radius, pillBgPaint)
            canvas.drawRoundRect(pillRect, radius, radius, pillStrokePaint)

            if (isConflict) {
                val centerX = pillRect.centerX()
                var currentY = pillRect.top + padding
                val iconPaint = Paint(pillStrokePaint).apply {
                    strokeWidth = 3f
                    color = onPrimaryColor
                    style = Paint.Style.STROKE
                }
                val iconCenterY = currentY + iconSize / 2f
                val leftRing = RectF(centerX - 10f, iconCenterY - 5f, centerX + 2f, iconCenterY + 5f)
                val rightRing = RectF(centerX - 2f, iconCenterY - 5f, centerX + 10f, iconCenterY + 5f)
                canvas.drawArc(leftRing, 90f, 270f, false, iconPaint)
                canvas.drawArc(rightRing, -90f, 270f, false, iconPaint)
                canvas.drawLine(centerX - 2f, iconCenterY, centerX + 2f, iconCenterY, iconPaint)

                currentY += iconSize + verticalMargin
                val textCenterY = currentY - fm.ascent
                canvas.drawText(text, centerX, textCenterY, pillTextPaint)
            } else {
                val textCenterY = pillRect.centerY() - (fm.ascent + fm.descent) / 2
                canvas.drawText(text, pillRect.centerX(), textCenterY, pillTextPaint)
            }
        }

        /**
         * 悬浮胶囊绘制：阴影内缩隔离采样彻底消除黑边，内部元素支持随屏幕顺序上下微移与淡入淡出
         */
        private fun drawCapsule(canvas: Canvas, screenW: Float, screenH: Float) {
            val textColor = if (isDark) darkTextColor else lightTextColor
            val accentColor = themeColor
            val countBgColor = if (isDark) darkCountBg else lightCountBg

            capsuleTextPaint.color = textColor
            capsuleAccentPaint.color = accentColor

            // 计算文本排版尺寸
            val prefixW = if (prefixText.isNotEmpty()) capsuleTextPaint.measureText(prefixText) else 0f
            val accentW = capsuleAccentPaint.measureText(accentText)
            val suffixW = if (suffixText.isNotEmpty()) capsuleTextPaint.measureText(suffixText) else 0f
            var totalContentW = prefixW + accentW + suffixW

            // 多链接角标尺寸计算 (采用高精度 Micro-Pill Tag 规范)
            val badge = badgeText
            var badgeW = 0f
            val badgeH = 18f * density
            if (badge != null) {
                val badgePadH = 6f * density
                val textW = capsuleBadgeTextPaint.measureText(badge)
                badgeW = Math.max(badgeH, textW + badgePadH * 2f)
                totalContentW += (badgeW + 6 * density) // 6dp margin
            }

            val padH = 14f * density
            val padV = 10f * density
            val iconSize = 20f * density
            val iconGap = 8f * density

            val capsuleW = iconSize + iconGap + totalContentW + padH * 2f
            val capsuleH = iconSize + padV * 2f

            // 屏幕 18% 高度黄金舒适区
            val capsuleCenterX = screenW / 2f
            val capsuleCenterY = screenH * 0.18f

            canvas.save()
            canvas.translate(capsuleCenterX, capsuleCenterY)
            canvas.scale(hudScale, hudScale)

            val rectLeft = -capsuleW / 2f
            val rectTop = -capsuleH / 2f
            val rectRight = capsuleW / 2f
            val rectBottom = capsuleH / 2f
            val capsuleRadius = capsuleH / 2f

            // 整个胶囊（包含底层阴影、实体面板、图标、文字与角标）整体离屏合批渲染
            // 实体胶囊底板保持 100% 实心不透明 (alpha=255)，彻底遮挡住正下方的阴影
            // 由 saveLayerAlpha 统一掌管整体淡入淡出，杜绝底层阴影穿透导致的脏灰色！
            val shadowPad = 24f * density
            val overallAlpha = (hudAlpha * 255).roundToInt().coerceIn(0, 255)
            val layerSaveCount = canvas.saveLayerAlpha(
                rectLeft - shadowPad,
                rectTop - shadowPad,
                rectRight + shadowPad,
                rectBottom + shadowPad + 6f * density,
                overallAlpha
            )

            // 1. 底层柔和弥散阴影：内缩 2.5dp 消除边缘黑边
            val shadowInset = 2.5f * density
            val shadowRect = RectF(rectLeft + shadowInset, rectTop + shadowInset, rectRight - shadowInset, rectBottom - shadowInset)
            val shadowRadius = (capsuleH - shadowInset * 2f) / 2f
            val shadowPath = Path().apply {
                addRoundRect(shadowRect, shadowRadius, shadowRadius, Path.Direction.CW)
            }
            capsuleShadowPaint.alpha = 255
            canvas.drawPath(shadowPath, capsuleShadowPaint)

            // 2. 实体胶囊平滑背景面板 (100% 饱满纯实心面板，内部完全遮挡住底层阴影)
            val capsulePath = Path().apply {
                addRoundRect(RectF(rectLeft, rectTop, rectRight, rectBottom), capsuleRadius, capsuleRadius, Path.Direction.CW)
            }
            capsuleBgPaint.color = if (isDark) darkBgColor else lightBgColor
            capsuleBgPaint.alpha = 255
            canvas.drawPath(capsulePath, capsuleBgPaint)

            // 3. 深色模式下的精致微轮廓描边
            if (isDark) {
                capsuleBorderPaint.alpha = 0x28
                canvas.drawPath(capsulePath, capsuleBorderPaint)
            }

            // 4. 绘制 20dp 目标图标
            val iconLeft = rectLeft + padH
            val iconTop = rectTop + padV
            currentAppIcon?.let { iconBmp ->
                val src = Rect(0, 0, iconBmp.width, iconBmp.height)
                val dst = RectF(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
                val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = 255 }
                canvas.drawBitmap(iconBmp, src, dst, iconPaint)
            }

            // 5. 绘制文本排版
            var currentX = iconLeft + iconSize + iconGap
            val fontMetrics = capsuleTextPaint.fontMetrics
            val textBaselineY = (rectTop + padV) + iconSize / 2f - (fontMetrics.ascent + fontMetrics.descent) / 2f

            if (prefixText.isNotEmpty()) {
                capsuleTextPaint.alpha = 255
                canvas.drawText(prefixText, currentX, textBaselineY, capsuleTextPaint)
                currentX += prefixW
            }

            capsuleAccentPaint.alpha = 255
            canvas.drawText(accentText, currentX, textBaselineY, capsuleAccentPaint)
            currentX += accentW

            if (suffixText.isNotEmpty()) {
                capsuleTextPaint.alpha = 255
                canvas.drawText(suffixText, currentX, textBaselineY, capsuleTextPaint)
            }

            // 6. 绘制多链接角标 (采用清晰可读的 Material 3 次级 Tag 规范，杜绝死黑死白)
            if (badge != null) {
                currentX += 6 * density
                val badgeTop = (rectTop + padV) + (iconSize - badgeH) / 2f
                val badgeRect = RectF(currentX, badgeTop, currentX + badgeW, badgeTop + badgeH)

                val badgeBgColor = if (isDark) 0xFF35383B.toInt() else 0xFFF1F3F4.toInt()
                val badgeBorderColor = if (isDark) 0xFF4D5155.toInt() else 0xFFDADCE0.toInt()
                val badgeTextColor = if (isDark) 0xFFFFFFFF.toInt() else 0xFF1F1F1F.toInt()

                // A. 实体背景底板
                capsuleBadgeBgPaint.color = badgeBgColor
                capsuleBadgeBgPaint.alpha = 255
                canvas.drawRoundRect(badgeRect, badgeH / 2f, badgeH / 2f, capsuleBadgeBgPaint)

                // B. 微轮廓描边
                capsuleBadgeBorderPaint.color = badgeBorderColor
                capsuleBadgeBorderPaint.alpha = 255
                canvas.drawRoundRect(badgeRect, badgeH / 2f, badgeH / 2f, capsuleBadgeBorderPaint)

                // C. 加粗高对比度数字
                capsuleBadgeTextPaint.color = badgeTextColor
                capsuleBadgeTextPaint.alpha = 255
                val bFm = capsuleBadgeTextPaint.fontMetrics
                val bTextBaseline = badgeTop + badgeH / 2f - (bFm.ascent + bFm.descent) / 2f
                canvas.drawText(badge, badgeRect.centerX(), bTextBaseline, capsuleBadgeTextPaint)
            }

            canvas.restoreToCount(layerSaveCount)
            canvas.restore()
        }

        private fun drawableToBitmap(drawable: Drawable, size: Int, tintColor: Int? = null): Bitmap {
            val mutated = drawable.mutate()
            if (tintColor != null) {
                mutated.setTint(tintColor)
            } else if (drawable is android.graphics.drawable.VectorDrawable) {
                mutated.setTint(if (isDark) Color.WHITE else lightTextColor)
            }
            if (mutated is BitmapDrawable && mutated.bitmap != null && tintColor == null) {
                return Bitmap.createScaledBitmap(mutated.bitmap, size, size, true)
            }
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            mutated.setBounds(0, 0, size, size)
            mutated.draw(canvas)
            return bitmap
        }

        private fun getExpandIconBitmap(): Bitmap? {
            val d = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_iconoir_expand) ?: return null
            return drawableToBitmap(d, (20 * density).roundToInt())
        }
    }
}
