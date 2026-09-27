package com.moting.linkgo.overlay

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import com.google.android.accessibility.selecttospeak.SelectToSpeakService
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 全局输入法软键盘 (IME) 状态与屏幕物理位置管理器。
 * 权威对齐 OctoClip (app.octoclip.v1) 的 KeyboardMonitor 架构：
 * 1. 由无障碍服务事件作为唯一触发源；
 * 2. 50ms 硬件级事件节流，杜绝输入法动画期间的频繁多重重入；
 * 3. 权威以 WindowInsets.Type.ime() / TYPE_INPUT_METHOD 作为真值，输出稳定离散的 (isShown, imeHeight)；
 * 4. 单一事件流，彻底根除双触发源并发竞争与中间过渡帧导致的顿挫。
 */
object ImeInsetsManager {
    private const val TAG = "ImeInsetsManager"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(isShown: Boolean, imeHeight: Int) -> Unit>()

    @Volatile
    var isKeyboardShown: Boolean = false
        private set

    @Volatile
    var currentImeHeight: Int = 0
        private set

    private var lastEventTime: Long = 0L

    /**
     * 注册输入法变化监听。注册时若已有输入法状态，会在主线程派发一次当前状态。
     */
    fun addListener(listener: (isShown: Boolean, imeHeight: Int) -> Unit) {
        listeners.add(listener)
        mainHandler.post {
            listener(isKeyboardShown, currentImeHeight)
        }
    }

    /**
     * 移除输入法变化监听
     */
    fun removeListener(listener: (isShown: Boolean, imeHeight: Int) -> Unit) {
        listeners.remove(listener)
    }

    /**
     * 主动查询当前系统输入法在屏幕上的真实物理高度。
     * 对齐 OctoClip：优先 API 30+ 官方 WindowMetrics，降级无障碍 TYPE_INPUT_METHOD 窗口。
     */
    fun queryCurrentImeHeight(): Int {
        val service = SelectToSpeakService.getInstance()
        var detectedHeight = 0

        // 优先：API 30+ 通过 WindowManager 直接读取 IME Insets 与可见性
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && service != null) {
            try {
                val wm = service.getSystemService(WindowManager::class.java)
                val metrics = wm?.currentWindowMetrics
                val windowInsets = metrics?.windowInsets
                val isVisible = windowInsets?.isVisible(WindowInsets.Type.ime()) ?: false
                if (isVisible) {
                    val insets = windowInsets?.getInsets(WindowInsets.Type.ime())
                    val h = insets?.bottom ?: 0
                    if (h > 0) {
                        detectedHeight = h
                    }
                } else {
                    // 系统明确指示 IME 已关闭或不可见，直接归零，毫秒级响应回落
                    return 0
                }
            } catch (e: Exception) {
                Log.w(TAG, "WindowMetrics 查询 IME insets 异常: ${e.message}")
            }
        }

        // 次选/降级：遍历无障碍服务的 Window 列表
        if (detectedHeight <= 0 && service != null) {
            try {
                val windowList = service.windows ?: emptyList()
                val rect = Rect()
                val screenH = service.resources.displayMetrics.heightPixels
                for (window in windowList) {
                    if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        window.getBoundsInScreen(rect)
                        val h = rect.height()
                        // 排除已经滑出屏幕下方的关闭中残存窗口
                        if (h > 200 && rect.top < screenH) {
                            detectedHeight = h
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "无障碍 Window 查询 IME 异常: ${e.message}")
            }
        }

        // 阈值过滤：大于 200px 确认为键盘弹出，否则确认为收起 (0)
        return if (detectedHeight > 200) detectedHeight else 0
    }

    /**
     * 由无障碍服务事件通知触发（带 30ms 灵敏节流）
     */
    fun onAccessibilityWindowsChanged() {
        val now = SystemClock.uptimeMillis()
        if (now - lastEventTime < 30L) {
            return
        }
        lastEventTime = now

        val height = queryCurrentImeHeight()
        val shown = height > 0

        if (shown == isKeyboardShown && height == currentImeHeight) {
            return
        }

        isKeyboardShown = shown
        currentImeHeight = height
        Log.i(TAG, "[IME-STATE-CHANGED] 键盘状态跃迁: isShown=$shown, imeHeight=$height")

        if (listeners.isEmpty()) return
        mainHandler.post {
            listeners.forEach { listener ->
                try {
                    listener(shown, height)
                } catch (e: Exception) {
                    Log.e(TAG, "执行键盘变化监听异常", e)
                }
            }
        }
    }
}
