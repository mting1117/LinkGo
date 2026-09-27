package com.moting.linkgo.clipboard

import android.content.Context
import android.content.Intent
import android.util.Log
import com.moting.linkgo.data.SettingsCache

/**
 * 剪贴板变动广播发布器：把「剪贴板变了」这一事件对外广播，供其他应用接收。
 *
 * 由 [ClipboardHandler] 的两个入口调用，因此无论走公开 API、隐藏 API 还是 LSPosed 通道，
 * 只要被感知到一次文本变动就会走到这里，无需在每个后端各写一份。
 *
 * 行为约定：
 * - **只发信号，不发内容**：广播不含文本/链接/来源包名，避免剪贴板内容外泄；
 * - **默认关闭**：需用户在设置页手动开启，且受剪贴板监听总开关约束（不监听自然无变动）；
 * - **自身清空不广播**：跳转后清空剪贴板属应用自身行为，不计为「用户复制了内容」。
 */
object ClipboardChangePublisher {

    private const val TAG = "LinkGo_ClipBroadcast"

    /**
     * 广播去重窗口。同一次复制可能同时被 LSPosed 通道与公开 API 感知到，
     * 且 [ClipboardHandler] 的 50ms 微防抖对两次不同入口的调用并不兜底，
     * 因此这里用更宽的窗口收敛为一次广播。
     */
    private const val DEDUPE_WINDOW_MS = 200L

    private var lastBroadcastText: String? = null
    private var lastBroadcastTime = 0L

    /**
     * 发布一次剪贴板变动事件。
     *
     * 可在任意线程调用（sendBroadcast 为异步投递，不阻塞调用方）。
     *
     * @param text 本次感知到的剪贴板文本，仅用于去重比对，**不会被放进广播**
     */
    fun publish(context: Context, text: String) {
        if (!SettingsCache.clipboardBroadcastEnabled) return

        // 主动清空剪贴板产生的变动由自身触发，不计为用户复制
        if (ClipboardClearer.consumeIgnoreNextChange()) {
            Log.i(TAG, "跳过广播：本次变动源于应用自身清空剪贴板")
            return
        }

        val now = System.currentTimeMillis()
        if (text == lastBroadcastText && (now - lastBroadcastTime) < DEDUPE_WINDOW_MS) {
            Log.i(TAG, "抑制重复广播：${DEDUPE_WINDOW_MS}ms 内相同文本(len=${text.length})")
            return
        }
        lastBroadcastText = text
        lastBroadcastTime = now

        val intent = Intent(ClipboardChangeContract.ACTION_CLIPBOARD_CHANGED).apply {
            putExtra(ClipboardChangeContract.EXTRA_CLIPBOARD_TIME, now)
            // 前台广播优先级，避免后台队列积压导致接收方延迟收到
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }

        runCatching { context.applicationContext.sendBroadcast(intent) }
            .onSuccess { Log.i(TAG, "已发布剪贴板变动广播: action=${ClipboardChangeContract.ACTION_CLIPBOARD_CHANGED}") }
            .onFailure { Log.w(TAG, "剪贴板变动广播发送失败: ${it.message}") }
    }
}
