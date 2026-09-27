package com.moting.linkgo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.moting.linkgo.util.WindowRouter
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first

/**
 * 隐形分发器 Activity
 * 
 * 采用 Theme.Transparent 主题，不加载任何 UI。
 * 专门负责接收系统 VIEW 意图并进行静默跳转。
 */
class LinkDispatcherActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val action = intent?.action
        val dataString = intent?.dataString
        
        // 分支处理：ACTION_VIEW（浏览器模式）直接透传完整 URL，不走正则截断
        val urls = if (action == Intent.ACTION_VIEW && !dataString.isNullOrBlank()) {
            android.util.Log.d("LinkDispatcher", "浏览器模式：直接透传原始数据")
            listOf(dataString)
        } else {
            // 分享模式：从 rawText 中提取链接（需剥离多余文字）
            val rawText = if (action == Intent.ACTION_SEND && intent.type == "text/plain") {
                intent.getStringExtra(Intent.EXTRA_TEXT)
            } else {
                dataString
            }
            
            if (rawText.isNullOrBlank()) {
                finishAndRemoveTask()
                return
            }
            // 读取用户配置的提取规则列表
            val repo = com.moting.linkgo.data.SettingsRepository(applicationContext)
            val isNormEnabled = runBlocking { repo.normalizationEnabled.first() }
            if (isNormEnabled) {
                val patterns = runBlocking { repo.extractionPatterns.first() }
                com.moting.linkgo.util.UrlUtils.extractAllUrls(rawText, patterns)
            } else {
                listOf(rawText)
            }
        }

        android.util.Log.d("LinkDispatcher", "收到 Intent: action=$action, 提取出的 URL 数=${urls.size}")

        if (urls.isEmpty()) {
            finishAndRemoveTask()
            return
        }

        // 获取分发来源与强制窗口模式
        val source = if (intent?.action == Intent.ACTION_VIEW) {
            WindowRouter.DispatchSource.DIRECT
        } else {
            WindowRouter.DispatchSource.SMART
        }
        val rawForceMode = intent?.getIntExtra("FORCE_WINDOW_MODE", -1)
        val forceWindowMode = if (rawForceMode != null && rawForceMode != -1) rawForceMode else null

        lifecycleScope.launch {
            if (urls.size == 1) {
                // 单个链接：直接执行静默跳转
                android.util.Log.d("LinkDispatcher", "准备跳转单个 URL: ${urls[0]}, forceMode=$forceWindowMode")
                val result = WindowRouter.handleUrl(this@LinkDispatcherActivity, urls[0], source, forceWindowMode = forceWindowMode)
                if (result.status == WindowRouter.DispatchResult.NEED_SELECTOR) {
                    val intent = Intent(this@LinkDispatcherActivity, BrowserSelectorActivity::class.java).apply {
                        putExtra("URL", result.finalUrl)
                        putExtra("SOURCE", source.name)
                        putExtra("TRACE_ID", result.traceId)
                        putExtra("STEP_INDEX", result.stepIndex)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                }
                android.util.Log.d("LinkDispatcher", "WindowRouter.handleUrl 调用完成，正在销毁分发器")
                finishAndRemoveTask()
                overridePendingTransition(0, 0)
            } else {
                // 多个链接：启动选择界面
                val intent = Intent(this@LinkDispatcherActivity, LinkSelectionActivity::class.java).apply {
                    putStringArrayListExtra("URLS", ArrayList(urls))
                    putExtra("SOURCE", source.name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                finish() // 结束跳板，由选择 Activity 接管后续
            }
        }
    }
}
