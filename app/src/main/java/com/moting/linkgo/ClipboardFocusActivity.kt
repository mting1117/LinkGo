package com.moting.linkgo

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.moting.linkgo.data.SettingsRepository
import com.moting.linkgo.util.UrlUtils
import com.moting.linkgo.util.WindowRouter
import com.moting.linkgo.model.ExtractPattern
import kotlinx.coroutines.flow.first

/**
 * 透明 Activity，用于短暂获取系统焦点后读取剪贴板内容。
 *
 * 当 Shizuku 进程通过 logcat 检测到剪贴板变化（主进程后台读取被拒绝）时，
 * 主进程启动此 Activity 获得焦点，然后读取剪贴板并分发。
 * 完成后立即结束，对用户透明。
 */
class ClipboardFocusActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ClipboardFocusActivity"
        const val EXTRA_CLEAR_CLIPBOARD = "com.moting.linkgo.extra.CLEAR_CLIPBOARD"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 无过渡动画
        overridePendingTransition(0, 0)

        // 清空模式：跳转后清空剪贴板用（防目标应用再次触发跳转）
        if (intent.getBooleanExtra(EXTRA_CLEAR_CLIPBOARD, false)) {
            clearClipboard()
            return
        }

        lifecycleScope.launch {
            // 稍等确保窗口获得焦点
            kotlinx.coroutines.delay(300)
            readAndDispatch()
        }
    }

    private fun clearClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        runCatching { clipboard?.clearPrimaryClip() }
            .onSuccess { Log.d(TAG, "clipboard cleared") }
            .onFailure { Log.w(TAG, "clear clipboard failed: ${it.message}") }
        finishAndRemoveTask()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    private fun readAndDispatch() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null || !clipboard.hasPrimaryClip()) {
            Log.d(TAG, "No clipboard content")
            finishAndRemoveTask()
            return
        }

        val clip = clipboard.primaryClip
        if (clip == null || clip.itemCount == 0) {
            Log.d(TAG, "Empty clipboard")
            finishAndRemoveTask()
            return
        }

        val rawText = clip.getItemAt(0).text?.toString()
        if (rawText.isNullOrBlank()) {
            Log.d(TAG, "No text in clipboard")
            finishAndRemoveTask()
            return
        }

        lifecycleScope.launch {
            val repo = SettingsRepository(applicationContext)
            val isNormEnabled = withContext(Dispatchers.IO) { repo.normalizationEnabled.first() }
            val urls: List<String> = if (isNormEnabled) {
                val patterns: List<ExtractPattern> = withContext(Dispatchers.IO) { repo.extractionPatterns.first() }
                UrlUtils.extractAllUrls(rawText, patterns)
            } else {
                listOf(rawText)
            }

            if (urls.isEmpty()) {
                Log.d(TAG, "No URL extracted from clipboard")
                finishAndRemoveTask()
                return@launch
            }

            Log.d(TAG, "Extracted ${urls.size} URL(s) from clipboard focus read")

            if (urls.size == 1) {
                val result = WindowRouter.handleUrl(
                    this@ClipboardFocusActivity, urls.first(), WindowRouter.DispatchSource.SMART
                )
                if (result.status == WindowRouter.DispatchResult.NEED_SELECTOR) {
                    startActivity(Intent(this@ClipboardFocusActivity, BrowserSelectorActivity::class.java).apply {
                        putExtra("URL", result.finalUrl)
                        putExtra("SOURCE", WindowRouter.DispatchSource.SMART.name)
                        putExtra("TRACE_ID", result.traceId)
                        putExtra("STEP_INDEX", result.stepIndex)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
            } else {
                startActivity(Intent(this@ClipboardFocusActivity, LinkSelectionActivity::class.java).apply {
                    putStringArrayListExtra("URLS", ArrayList(urls))
                    putExtra("SOURCE", WindowRouter.DispatchSource.SMART.name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }

            finishAndRemoveTask()
        }
    }
}