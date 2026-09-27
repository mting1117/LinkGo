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

class NormalRouterActivity : ComponentActivity() {
    private var routingFinished = false
    private var hasLaunched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!hasLaunched) {
            hasLaunched = true
            executeRouting()
        }
    }

    override fun onResume() {
        super.onResume()
        // 保底逻辑：如果由于某种原因生命周期异常导致未在 finally 中销毁，此处作为二次清理
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
                        WindowRouter.shareOpenIntent(this@NormalRouterActivity, preheatIntent, addNewTask = true)
                    } else {
                        startActivity(preheatIntent, optionsBundle)
                    }
                    if (preheatDelayMillis > 0) delay(preheatDelayMillis)
                }
                
                if (isMode4) {
                    val shareResult = WindowRouter.shareOpenIntent(this@NormalRouterActivity, finalIntent, addNewTask = true)
                    requestId?.let { ShareOpenResultManager.completeResult(it, shareResult.success) }
                } else if (usePendingIntent && android.os.Build.VERSION.SDK_INT >= 34) {
                    val requestCode = finalUrl.hashCode()
                    val pi = android.app.PendingIntent.getActivity(
                        this@NormalRouterActivity, 
                        requestCode, 
                        finalIntent, 
                        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_ONE_SHOT or android.app.PendingIntent.FLAG_CANCEL_CURRENT
                    )
                    try {
                        pi.send(this@NormalRouterActivity, 0, null, null, null, null, optionsBundle)
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
                Log.e("NormalRouter", "跳转失败", e)
                requestId?.let { ShareOpenResultManager.completeResult(it, false) }
            } finally {
                routingFinished = true
                // 【核心优化】过河拆桥：指令一旦发出（或由于异常中断），立即从后台任务栈中彻底移除
                // 由于目标应用已通过 NEW_TASK 启动，此处 finish 不会影响目标运行，且能消除 Recents 残留
                finishAndRemoveTask()
            }
        }
    }
    
    override fun finish() {
        super.finish()
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(android.app.Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
            overrideActivityTransition(android.app.Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
