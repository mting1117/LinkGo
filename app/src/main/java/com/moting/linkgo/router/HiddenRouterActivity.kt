package com.moting.linkgo.router

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.moting.linkgo.util.ShareOpenResultManager
import com.moting.linkgo.util.WindowRouter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class HiddenRouterActivity : ComponentActivity() {
    private var hasLaunched = false
    private var routingFinished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!hasLaunched) {
            hasLaunched = true
            executeRouting()
        }
    }

    override fun onResume() {
        super.onResume()
        // 只有在分发流程彻底结束（发出了最终链接）后，才允许在返回时销毁
        if (hasLaunched && routingFinished) {
            finishAndRemoveTask()
        }
    }

    private fun executeRouting() {
        val preheatIntent = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra("EXTRA_PREHEAT_INTENT", Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra("EXTRA_PREHEAT_INTENT")
        }
        val finalIntent = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
        val preheatDelayMillis = intent.getLongExtra("EXTRA_PREHEAT_DELAY", 0L)
        val optionsBundle = intent.getBundleExtra("EXTRA_OPTIONS_BUNDLE")
        val usePendingIntent = intent.getBooleanExtra("EXTRA_USE_PENDING_INTENT", false)
        val finalUrl = intent.getStringExtra("EXTRA_FINAL_URL") ?: ""
        val isMode4 = intent.getBooleanExtra("EXTRA_IS_MODE_4", false)
        val requestId = intent.getStringExtra("EXTRA_REQUEST_ID")

        if (finalIntent == null) {
            finishAndRemoveTask()
            return
        }

        lifecycleScope.launch {
            try {
                if (preheatIntent != null) {
                    if (isMode4) {
                        WindowRouter.shareOpenIntent(this@HiddenRouterActivity, preheatIntent, addNewTask = false)
                    } else {
                        startActivity(preheatIntent, optionsBundle)
                    }
                    if (preheatDelayMillis > 0) delay(preheatDelayMillis)
                }
                
                if (isMode4) {
                    val shareResult = WindowRouter.shareOpenIntent(this@HiddenRouterActivity, finalIntent, addNewTask = false)
                    requestId?.let { ShareOpenResultManager.completeResult(it, shareResult.success) }
                } else if (usePendingIntent && android.os.Build.VERSION.SDK_INT >= 34) {
                    val requestCode = finalUrl.hashCode()
                    val pi = android.app.PendingIntent.getActivity(
                        this@HiddenRouterActivity, 
                        requestCode, 
                        finalIntent, 
                        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_ONE_SHOT or android.app.PendingIntent.FLAG_CANCEL_CURRENT
                    )
                    try {
                        pi.send(this@HiddenRouterActivity, 0, null, null, null, null, optionsBundle)
                        requestId?.let { ShareOpenResultManager.completeResult(it, true) }
                    } catch (e: Exception) {
                        startActivity(finalIntent, optionsBundle)
                        requestId?.let { ShareOpenResultManager.completeResult(it, true) }
                    }
                } else {
                    startActivity(finalIntent, optionsBundle)
                    requestId?.let { ShareOpenResultManager.completeResult(it, true) }
                }
            } catch (e: Exception) {
                Log.e("HiddenRouter", "隐身跳转失败", e)
                requestId?.let { ShareOpenResultManager.completeResult(it, false) }
                finishAndRemoveTask()
            } finally {
                // 标记分发结束
                routingFinished = true
                // 【核心优化】立即从 Recents 移除，解决透明栈残留问题
                finishAndRemoveTask()
            }
        }
    }
}
