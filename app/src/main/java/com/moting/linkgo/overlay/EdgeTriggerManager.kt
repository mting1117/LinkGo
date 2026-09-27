package com.moting.linkgo.overlay

import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import com.google.android.accessibility.selecttospeak.SelectToSpeakService
import com.moting.linkgo.ClipboardAnalysisActivity
import com.moting.linkgo.data.SettingsRepository
import com.moting.linkgo.model.EdgeGestureConfig
import com.moting.linkgo.model.GestureAction
import com.moting.linkgo.util.HapticHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 快捷手势边缘触发热区与触控路由管理器
 * 负责在无障碍特权下挂载左右边缘触发热区，并进行单击与滑动物理手势分流
 */
class EdgeTriggerManager(private val service: Context) {

    companion object {
        @Volatile
        var isPreviewOpaque: Boolean = false
            private set

        @Volatile
        var isScreenOff: Boolean = false
            private set

        private var activeManager: EdgeTriggerManager? = null

        fun getActiveManager(): EdgeTriggerManager? = activeManager

        /**
         * 当进入快捷手势设置页面时设置为 true（热区强制不透明高亮以供调整），离开时设为 false
         */
        fun setPreviewOpaque(opaque: Boolean) {
            if (isPreviewOpaque != opaque) {
                isPreviewOpaque = opaque
                activeManager?.refreshTriggers()
            }
        }
    }

    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var configJob: Job? = null

    private var leftTriggerView: EdgeTriggerView? = null
    private var rightTriggerView: EdgeTriggerView? = null

    private val radarFinderOverlay = RadarFinderOverlay(service)
    private var currentConfig: EdgeGestureConfig = EdgeGestureConfig()

    @Volatile
    private var currentForegroundPackage: String? = null

    /**
     * 前台焦点应用包名变更通知（由 SelectToSpeakService 的 TYPE_WINDOW_STATE_CHANGED 驱动）
     */
    fun onForegroundPackageChanged(packageName: String) {
        if (currentForegroundPackage != packageName) {
            currentForegroundPackage = packageName
            updateTriggersVisibility()
        }
    }

    /**
     * 检查当前屏幕方向是否满足生效范围
     */
    private fun isOrientationAllowed(): Boolean {
        val orientation = service.resources.configuration.orientation
        return when (currentConfig.orientationScope) {
            com.moting.linkgo.model.OrientationScope.ALL -> true
            com.moting.linkgo.model.OrientationScope.PORTRAIT_ONLY -> orientation == Configuration.ORIENTATION_PORTRAIT
            com.moting.linkgo.model.OrientationScope.LANDSCAPE_ONLY -> orientation == Configuration.ORIENTATION_LANDSCAPE
        }
    }

    /**
     * 检查目标应用包名是否满足黑白名单生效范围
     */
    private fun isAppScopeAllowed(pkg: String?): Boolean {
        val packages = currentConfig.appScopePackages
        return when (currentConfig.appScopeMode) {
            com.moting.linkgo.model.AppScopeMode.BLACKLIST -> {
                // 黑名单模式：列表内的应用禁用，其他应用正常生效
                if (packages.isEmpty() || pkg == null) true
                else !packages.contains(pkg)
            }
            com.moting.linkgo.model.AppScopeMode.WHITELIST -> {
                // 白名单模式：仅列表内的应用生效，其他应用禁用
                if (packages.isEmpty()) false
                else if (pkg == null) true
                else packages.contains(pkg)
            }
        }
    }

    /**
     * 综合判定当前环境是否允许触发手势
     */
    private fun isScopeAllowed(): Boolean {
        // 在设置页面高亮预览状态下，优先允许预览显示，便于用户调整热区尺寸与位置
        if (isPreviewOpaque) return true
        if (!isOrientationAllowed()) return false
        val pkg = currentForegroundPackage
            ?: (service as? SelectToSpeakService)?.getActiveWindowPackage()
            ?: SelectToSpeakService.getInstance()?.getActiveWindowPackage()
        return isAppScopeAllowed(pkg)
    }

    /**
     * 根据生效范围动态更新热区 View 的可见性（不符合条件时 View.GONE，杜绝阻挡底层触摸）
     */
    private fun updateTriggersVisibility() {
        val allowed = currentConfig.enabled && isScopeAllowed()
        val targetVisibility = if (allowed) View.VISIBLE else View.GONE
        if (leftTriggerView?.visibility != targetVisibility) {
            leftTriggerView?.visibility = targetVisibility
        }
        if (rightTriggerView?.visibility != targetVisibility) {
            rightTriggerView?.visibility = targetVisibility
        }
    }

    // 屏幕方向/分屏变化监听：低频事件，仅在用户旋转屏幕时重新计算最新屏幕边缘尺寸
    private val componentCallbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) {
            if (currentConfig.enabled) {
                applyConfig(currentConfig)
            }
        }
        override fun onLowMemory() {}
        override fun onTrimMemory(level: Int) {}
    }

    // 系统息屏/亮屏/AOD 广播监听：保证在熄屏与常显下绝不残留不透明高亮，杜绝 AOD 烧屏与视觉打扰
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOff = true
                    isPreviewOpaque = false // 息屏时刻坚决恢复正常透明度
                    refreshTriggers()
                }
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT -> {
                    isScreenOff = false
                    // 仅读取内存布尔值 isAttachedToWindow，0 耗电、0 IPC，仅在脱落时才自愈补挂
                    if (currentConfig.enabled) {
                        val rightAttached = rightTriggerView?.isAttachedToWindow == true
                        val leftAttached = if (currentConfig.mirrorEnabled) leftTriggerView?.isAttachedToWindow == true else true
                        if (!rightAttached || !leftAttached) {
                            applyConfig(currentConfig)
                        } else {
                            refreshTriggers()
                        }
                    } else {
                        refreshTriggers()
                    }
                }
            }
        }
    }

    /**
     * 启动管理器并监听配置变化
     */
    fun start() {
        activeManager = this
        val repo = SettingsRepository(service)

        // 注册低频屏幕旋转与配置变化监听
        try {
            service.registerComponentCallbacks(componentCallbacks)
        } catch (_: Exception) {}

        // 注册系统息屏广播
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            service.registerReceiver(screenReceiver, filter)
        } catch (e: Exception) {
            Log.w("EdgeTrigger", "注册息屏广播失败: ${e.message}")
        }

        configJob?.cancel()
        configJob = scope.launch {
            repo.edgeGestureConfigFlow.collect { config ->
                currentConfig = config
                applyConfig(config)
            }
        }
        scope.launch {
            repo.dynamicColorEnabled.collect {
                refreshTriggers()
            }
        }
    }

    /**
     * 停止并安全移除所有边缘视图
     */
    fun stop() {
        if (activeManager == this) {
            activeManager = null
        }
        try {
            service.unregisterComponentCallbacks(componentCallbacks)
        } catch (_: Exception) {}
        try {
            service.unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}

        configJob?.cancel()
        configJob = null
        removeTriggers()
        radarFinderOverlay.dismissImmediate()
        scope.cancel()
    }

    /**
     * 极轻量内存级自愈检测
     */
    fun selfHeal() {
        if (!currentConfig.enabled) return
        val rightAttached = rightTriggerView?.isAttachedToWindow == true
        val leftAttached = if (currentConfig.mirrorEnabled) leftTriggerView?.isAttachedToWindow == true else true
        if (!rightAttached || !leftAttached) {
            applyConfig(currentConfig)
        } else {
            updateTriggersVisibility()
        }
    }

    private fun refreshTriggers() {
        updateTriggersVisibility()
        leftTriggerView?.postInvalidate()
        rightTriggerView?.postInvalidate()
    }

    private fun applyConfig(config: EdgeGestureConfig) {
        if (!config.enabled) {
            removeTriggers()
            return
        }

        val dm = service.resources.displayMetrics
        val density = dm.density
        val screenWidth = dm.widthPixels
        val screenHeight = dm.heightPixels

        // 基于固定 dp 换算热区像素大小，确保横竖屏下触发区域的物理尺寸绝对一致
        val widthPx = (config.widthDp * density).roundToInt().coerceAtLeast(0)
        val heightPx = (config.heightDp * density).roundToInt().coerceAtLeast(0)
        val marginPx = (screenWidth * config.horizontalRatio).roundToInt()
        val topY = (screenHeight * config.verticalRatio - heightPx / 2f).roundToInt()

        // 1. 右侧触发条 (默认始终存在)
        if (rightTriggerView == null) {
            val rightView = EdgeTriggerView(service, isRightEdge = true, { currentConfig }, radarFinderOverlay, { isScopeAllowed() })
            val rightParams = createLayoutParams(Gravity.TOP or Gravity.END, widthPx, heightPx, marginPx, topY)
            try {
                windowManager.addView(rightView, rightParams)
                rightTriggerView = rightView
            } catch (e: Exception) {
                Log.e("EdgeTrigger", "添加右侧触发热区失败", e)
            }
        } else {
            val rightParams = createLayoutParams(Gravity.TOP or Gravity.END, widthPx, heightPx, marginPx, topY)
            try {
                windowManager.updateViewLayout(rightTriggerView, rightParams)
                rightTriggerView?.invalidate()
            } catch (e: Exception) {
                Log.w("EdgeTrigger", "更新右侧触发热区失败: ${e.message}")
            }
        }

        // 2. 左侧镜像触发条 (根据 mirrorEnabled 动态创建或移除)
        if (config.mirrorEnabled) {
            if (leftTriggerView == null) {
                val leftView = EdgeTriggerView(service, isRightEdge = false, { currentConfig }, radarFinderOverlay, { isScopeAllowed() })
                val leftParams = createLayoutParams(Gravity.TOP or Gravity.START, widthPx, heightPx, marginPx, topY)
                try {
                    windowManager.addView(leftView, leftParams)
                    leftTriggerView = leftView
                } catch (e: Exception) {
                    Log.e("EdgeTrigger", "添加左侧触发热区失败", e)
                }
            } else {
                val leftParams = createLayoutParams(Gravity.TOP or Gravity.START, widthPx, heightPx, marginPx, topY)
                try {
                    windowManager.updateViewLayout(leftTriggerView, leftParams)
                    leftTriggerView?.invalidate()
                } catch (e: Exception) {
                    Log.w("EdgeTrigger", "更新左侧触发热区失败: ${e.message}")
                }
            }
        } else {
            leftTriggerView?.let { view ->
                try {
                    windowManager.removeView(view)
                } catch (e: Exception) {
                    Log.w("EdgeTrigger", "移除左侧触发热区失败: ${e.message}")
                }
                leftTriggerView = null
            }
        }

        updateTriggersVisibility()
    }

    private fun createLayoutParams(gravity: Int, widthPx: Int, heightPx: Int, marginX: Int, topY: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            this.gravity = gravity
            this.width = widthPx
            this.height = heightPx
            this.x = marginX
            this.y = topY
        }
    }

    private fun removeTriggers() {
        rightTriggerView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            rightTriggerView = null
        }
        leftTriggerView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            leftTriggerView = null
        }
    }

    /**
     * 单个边缘触发条视图与触控检测
     */
    private class EdgeTriggerView(
        context: Context,
        private val isRightEdge: Boolean,
        private val configProvider: () -> EdgeGestureConfig,
        private val radarFinderOverlay: RadarFinderOverlay,
        private val scopeAllowedProvider: () -> Boolean
    ) : View(context) {

        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private val density = resources.displayMetrics.density

        private var downX = 0f
        private var downY = 0f
        private var downTime = 0L
        private var isSwiping = false
        private var isLongPressed = false
        private var isPressedState = false

        private val longPressRunnable = Runnable {
            val config = configProvider()
            if (isPressedState && !isSwiping && config.longPressAction != GestureAction.NONE) {
                isLongPressed = true
                if (config.gestureHapticEnabled) {
                    HapticHelper.click(context, this)
                }
                dispatchAction(config.longPressAction, downX, downY, config)
            }
        }

        private val slotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val spinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private fun dispatchAction(
            action: GestureAction,
            rawX: Float,
            rawY: Float,
            config: EdgeGestureConfig,
            initialEvent: MotionEvent? = null
        ) {
            when (action) {
                GestureAction.RADAR_DIRECT -> {
                    radarFinderOverlay.show(rawX, rawY, config, isRightEdge)
                    if (initialEvent != null) {
                        radarFinderOverlay.dispatchTouchEvent(initialEvent)
                    }
                }
                GestureAction.SCREEN_RECOGNITION -> {
                    // 实例可能已被回收或处于假死状态，此时静默吞掉动作会让用户误以为手势失效。
                    // 走「按需探测」判定，避免探测结论过期导致的误报。
                    runWithUsableA11y { it.runIdentificationFlow(false) }
                }
                GestureAction.CLIPBOARD_ANALYSIS -> {
                    val intent = Intent(context, ClipboardAnalysisActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
                GestureAction.NONE -> {}
            }
        }

        /**
         * 取得可用的无障碍实例后执行动作；实例缺失或假死时给出可见提示并触发自愈。
         *
         * 抽出是为了让「屏幕识别」与「屏幕取图」共用同一套可用性判定与自愈入口——
         * 两者对无障碍的依赖完全相同，各写一份必然演化出不一致。
         */
        private inline fun runWithUsableA11y(action: (SelectToSpeakService) -> Unit) {
            val a11y = SelectToSpeakService.getInstance()?.takeIf {
                com.moting.linkgo.util.AccessibilityUtils.isServiceUsableNow()
            }
            if (a11y != null) {
                action(a11y)
            } else {
                Log.w("EdgeTrigger", "无障碍不可用（缺失或假死），触发自愈")
                android.widget.Toast.makeText(
                    context,
                    "无障碍服务未就绪，正在自动恢复，请稍后重试手势",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                com.moting.linkgo.util.AccessibilityUtils.requestPassiveSelfHeal(context)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!scopeAllowedProvider()) {
                return false
            }
            val config = configProvider()

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    downTime = System.currentTimeMillis()
                    isSwiping = false
                    isLongPressed = false
                    isPressedState = true
                    invalidate()

                    if (config.longPressAction != GestureAction.NONE) {
                        postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong().coerceAtLeast(400L))
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - downX
                    val deltaY = event.rawY - downY
                    val swipeInDist = if (isRightEdge) -deltaX else deltaX

                    if (Math.hypot(deltaX.toDouble(), deltaY.toDouble()) > touchSlop * 0.8f) {
                        removeCallbacks(longPressRunnable)
                    }

                    // 适当扩大手势的可滑动范围：优化向内启动门槛，并支持最高 ±60° 的扇区斜划入 (abs(deltaY) < swipeInDist * 1.75f)
                    // 依然保持防列表垂直滚动误触能力 (纯上下滑时 swipeInDist 极小甚至为负，不会误触)
                    val minSwipeInDist = (touchSlop * 0.70f).coerceAtLeast(8f * density)
                    val isSwipeInValid = swipeInDist >= minSwipeInDist && (abs(deltaY) < swipeInDist * 1.75f)

                    if (!isSwiping && !isLongPressed && isSwipeInValid) {
                        removeCallbacks(longPressRunnable)
                        isSwiping = true
                        // 手势确认激活：唯一一次轻快清脆触感反馈，杜绝与按下粘连
                        if (config.gestureHapticEnabled) {
                            HapticHelper.tick(context, this)
                        }

                        // 派发滑动动作
                        dispatchAction(config.swipeAction, event.rawX, event.rawY, config, event)
                    } else if (isSwiping && config.swipeAction == GestureAction.RADAR_DIRECT) {
                        // 持续将滑动坐标传递给全屏探照 Overlay
                        radarFinderOverlay.dispatchTouchEvent(event)
                    } else if (isLongPressed && config.longPressAction == GestureAction.RADAR_DIRECT) {
                        radarFinderOverlay.dispatchTouchEvent(event)
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(longPressRunnable)
                    isPressedState = false
                    invalidate()

                    if (isSwiping) {
                        if (config.swipeAction == GestureAction.RADAR_DIRECT) {
                            radarFinderOverlay.dispatchTouchEvent(event)
                        }
                        isSwiping = false
                    } else if (isLongPressed) {
                        if (config.longPressAction == GestureAction.RADAR_DIRECT) {
                            radarFinderOverlay.dispatchTouchEvent(event)
                        }
                        isLongPressed = false
                    } else {
                        // 单击判定：时间在 350ms 内且位移极小
                        val duration = System.currentTimeMillis() - downTime
                        val moveDist = Math.hypot((event.rawX - downX).toDouble(), (event.rawY - downY).toDouble())
                        if (duration < 350 && moveDist < touchSlop * 1.5) {
                            if (config.gestureHapticEnabled) {
                                HapticHelper.click(context, this)
                            }
                            // 派发单击动作
                            dispatchAction(config.clickAction, event.rawX, event.rawY, config)
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(longPressRunnable)
                    isPressedState = false
                    if (isSwiping && config.swipeAction == GestureAction.RADAR_DIRECT) {
                        radarFinderOverlay.dispatchTouchEvent(event)
                    } else if (isLongPressed && config.longPressAction == GestureAction.RADAR_DIRECT) {
                        radarFinderOverlay.dispatchTouchEvent(event)
                    }
                    isSwiping = false
                    isLongPressed = false
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // 屏幕关闭 (包括熄屏与 AOD 常显) 状态下彻底不绘制，0 耗电、0 视觉残留、0 烧屏风险
            if (isScreenOff) return

            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0 || h <= 0) return

            val isDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES

            val accentColor = com.moting.linkgo.util.ThemeColorHelper.getAccentColor(context, isDark)
            val primaryColor = accentColor
            val spineColor = accentColor

            val isOpaque = isPreviewOpaque
            val config = configProvider()

            // 1. 外层热区槽位：仅在快捷手势设置页内显示以辅助对齐位置，设置页面外彻底不显示！
            if (isOpaque) {
                slotPaint.color = primaryColor
                slotPaint.alpha = 140
                val slotRadius = 8 * density
                val slotRect = RectF(0f, 0f, w, h)
                canvas.drawRoundRect(slotRect, slotRadius, slotRadius, slotPaint)
            }

            // 2. 贴边实体指示条：日常唯一可见的交互形态，透明度调节直接作用于指示条本身
            val userAlpha = config.edgeAlpha
            val targetAlpha = if (isOpaque) {
                255 // 设置页内指示条保持纯亮，方便对比与定位
            } else {
                val base = if (isPressedState) (userAlpha * 1.35f).coerceAtMost(1.0f) else userAlpha
                (base * 255).roundToInt().coerceIn(0, 255)
            }

            if (targetAlpha > 0) {
                spinePaint.color = spineColor
                spinePaint.alpha = targetAlpha

                val barThickness = 5.0f * density
                val barHeight = h * 0.70f
                val top = (h - barHeight) / 2f
                val bottom = top + barHeight

                // 指示条与屏幕物理边缘保持 3dp 的精致悬浮间隙，避免紧贴屏幕边框
                val edgeGap = (3.0f * density).coerceAtMost(((w - barThickness) / 2f).coerceAtLeast(0f))
                val left: Float
                val right: Float
                if (isRightEdge) {
                    right = w - edgeGap
                    left = right - barThickness
                } else {
                    left = edgeGap
                    right = left + barThickness
                }

                val spineRect = RectF(left, top, right, bottom)
                val spineRadius = barThickness / 2f
                canvas.drawRoundRect(spineRect, spineRadius, spineRadius, spinePaint)
            }
        }
    }
}

