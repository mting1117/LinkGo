package com.moting.linkgo.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast

/**
 * 即时响应且支持快速覆盖旧通知的 Toast 辅助工具。
 *
 * 通过维护单例 Toast 实例，并在新提示触发时立即执行 [Toast.cancel]，
 * 彻底避免 Android 系统原生 Toast 的队列排队堆积与延迟展示问题。
 */
object InstantToastHelper {

    private const val TAG = "LinkGo_InstantToast"
    private const val FIXED_COPIED_TEXT = "已复制"

    private var currentToast: Toast? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 弹出固定内容为“已复制”的即时 Toast 提示。
     * 若前一条 Toast 仍在屏幕上显示，将立即将其撤销并由新 Toast 覆盖。
     */
    fun showCopied(context: Context) {
        show(context, FIXED_COPIED_TEXT)
    }

    /**
     * 弹出指定文本的即时 Toast 提示（新通知即时覆盖旧通知）。
     */
    fun show(context: Context, text: String) {
        val appContext = context.applicationContext
        if (Looper.myLooper() == Looper.getMainLooper()) {
            showInternal(appContext, text)
        } else {
            mainHandler.post {
                showInternal(appContext, text)
            }
        }
    }

    private fun showInternal(context: Context, text: String) {
        try {
            // 核心：立即撤销旧 Toast，阻止系统队列堆叠延迟
            currentToast?.cancel()
            val toast = Toast.makeText(context, text, Toast.LENGTH_SHORT)
            currentToast = toast
            toast.show()
        } catch (e: Exception) {
            Log.w(TAG, "showInternal failed: ${e.message}")
        }
    }

    /**
     * 撤销当前正在显示的 Toast（在监听释放或关闭时调用）。
     */
    fun cancel() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            currentToast?.cancel()
            currentToast = null
        } else {
            mainHandler.post {
                currentToast?.cancel()
                currentToast = null
            }
        }
    }
}
