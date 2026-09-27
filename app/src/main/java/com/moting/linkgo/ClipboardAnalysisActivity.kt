package com.moting.linkgo

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.moting.linkgo.util.UrlUtils
import com.moting.linkgo.util.WindowRouter
import com.moting.linkgo.model.ExtractPattern
import kotlinx.coroutines.launch

/**
 * 剪贴板分析 Activity
 * 
 * 作用：获取最新剪贴板内容，正则提取链接并分发。
 */
class ClipboardAnalysisActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 透明过渡
        overridePendingTransition(0, 0)
        
        val isFromTile = intent.getBooleanExtra("from_tile", false)
        val delayTime = if (isFromTile) 500L else 200L

        lifecycleScope.launch {
            // 在 Android 10+ 上，读取剪贴板需要 Activity 获得焦点
            // 稍作延迟，确保窗口已经进入前台并获得系统认可的焦点（从通知栏启动需要更长时间）
            kotlinx.coroutines.delay(delayTime)
            analyzeClipboard()
        }

    }

    /**
     * 分析剪贴板并分发。
     *
     * 文本与图片统一走 ClipboardReader 判定（与后台监听路径同一套判据），
     * 图片交给 ClipboardHandler.handleImage —— 与"复制图片后被监听捕获"完全同构，
     * 因此图片规则、胶囊、分享跳转在这里零成本复用。
     */
    private fun analyzeClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        // 检查是否有内容
        if (!clipboard.hasPrimaryClip()) {
            Toast.makeText(this, "剪贴板无内容，请检查权限或是否已复制", Toast.LENGTH_SHORT).show()
            finishAndRemoveTask()
            return
        }

        val clipData = clipboard.primaryClip

        if (clipData == null || clipData.itemCount == 0) {
            Toast.makeText(this, "无法读取剪贴板 (可能是权限被拒绝)", Toast.LENGTH_SHORT).show()
            finishAndRemoveTask()
            return
        }

        // 文本与图片的判定下沉到 ClipboardReader：此处不再自己判断"是不是文本"，
        // 否则这里与后台监听路径会各自演化出不一致的判据
        val payload = com.moting.linkgo.image.ClipboardReader.read(clipData)
        if (payload == null) {
            Toast.makeText(this, "剪贴板内容无法识别", Toast.LENGTH_SHORT).show()
            finishAndRemoveTask()
            return
        }

        when (payload) {
            is com.moting.linkgo.image.ClipPayload.ImageSource -> {
                lifecycleScope.launch {
                    val image = com.moting.linkgo.image.ClipboardReader.resolveImage(this@ClipboardAnalysisActivity, payload)
                    if (image == null) {
                        Toast.makeText(this@ClipboardAnalysisActivity, "已复制图片（内容不可读）", Toast.LENGTH_SHORT).show()
                    } else {
                        com.moting.linkgo.clipboard.ClipboardHandler.handleImage(this@ClipboardAnalysisActivity, image)
                    }
                    finishAndRemoveTask()
                }
            }

            is com.moting.linkgo.image.ClipPayload.Image -> {
                com.moting.linkgo.clipboard.ClipboardHandler.handleImage(this, payload)
                finishAndRemoveTask()
            }

            is com.moting.linkgo.image.ClipPayload.Text -> analyzeText(payload.text)
        }
    }

    /** 文本分支：保持原有行为不变（提取链接 -> 单条直达 / 多条进选择页） */
    private fun analyzeText(rawText: String) {
        val repo = com.moting.linkgo.data.SettingsRepository(applicationContext)
        val isNormEnabled = runBlocking { repo.normalizationEnabled.first() }
        val urls: List<String> = if (isNormEnabled) {
            val patterns: List<ExtractPattern> = runBlocking { repo.extractionPatterns.first() }
            UrlUtils.extractAllUrls(rawText, patterns)
        } else {
            listOf(rawText)
        }

        if (urls.isEmpty()) {
            Toast.makeText(this, "未从剪贴板解析到链接", Toast.LENGTH_SHORT).show()
            finishAndRemoveTask()
            return
        }

        lifecycleScope.launch {
            if (urls.size == 1) {
                // 单个链接：直接执行跳转
                val firstUrl = urls.first()
                val result = WindowRouter.handleUrl(this@ClipboardAnalysisActivity, firstUrl, WindowRouter.DispatchSource.SMART)
                if (result.status == WindowRouter.DispatchResult.NEED_SELECTOR) {
                    val intent = Intent(this@ClipboardAnalysisActivity, BrowserSelectorActivity::class.java).apply {
                        putExtra("URL", result.finalUrl)
                        putExtra("SOURCE", WindowRouter.DispatchSource.SMART.name)
                        putExtra("TRACE_ID", result.traceId)
                        putExtra("STEP_INDEX", result.stepIndex)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                }
                finishAndRemoveTask()
            } else {
                // 多个链接：启动选择界面
                val intent = Intent(this@ClipboardAnalysisActivity, LinkSelectionActivity::class.java).apply {
                    putStringArrayListExtra("URLS", ArrayList(urls))
                    putExtra("SOURCE", WindowRouter.DispatchSource.SMART.name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                finish()
            }
        }
    }
}
