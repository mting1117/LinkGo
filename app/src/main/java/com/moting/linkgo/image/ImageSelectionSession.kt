package com.moting.linkgo.image

import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.google.android.accessibility.selecttospeak.SelectToSpeakService
import com.moting.linkgo.util.AccessibilityUtils

/**
 * 屏幕截图/选区会话管理器。
 *
 * 负责在滑动直达或屏幕识别截图完成后暂存框选范围，并在用户在 ImageSelectorActivity 点击「重选」时，
 * 统一拉起屏幕识别半透明蒙版界面，并 100% 复原该选框供用户拖拽边界微调或重新框选。
 */
object ImageSelectionSession {
    /** 上次截图的屏幕矩形区域（控件矩形、框选矩形或全屏矩形） */
    var lastBounds: Rect? = null
        private set

    /** 保存最新截取的区域 */
    fun saveLastRegion(bounds: Rect) {
        lastBounds = Rect(bounds)
    }

    /** 是否具备可重选的上下文 */
    fun canReselect(): Boolean = lastBounds != null

    /**
     * 触发重新选择：关闭选择页后统一唤醒屏幕识别覆盖层并复原选框。
     */
    fun triggerReselect(context: Context) {
        val bounds = lastBounds ?: return
        val service = SelectToSpeakService.getInstance()
        if (service != null && AccessibilityUtils.isServiceUsableNow()) {
            Handler(Looper.getMainLooper()).post {
                service.showWithRestoredRegion(bounds)
            }
        } else {
            Toast.makeText(context, "无障碍服务未就绪，正在尝试恢复...", Toast.LENGTH_SHORT).show()
            AccessibilityUtils.requestPassiveSelfHeal(context)
        }
    }

    /** 清理会话状态 */
    fun clear() {
        lastBounds = null
    }
}
