package com.moting.linkgo

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.moting.linkgo.util.ShareOpenResultManager
import com.moting.linkgo.util.WindowRouter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * OriginOS 小窗代理跳板 Activity
 * 完全同步参考代码逻辑，增加包名快速启动降级与生命周期保护
 */
class ShareOpenAppProxyActivity : ComponentActivity() {
    private var requestId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        
        requestId = intent.getStringExtra("REQUEST_ID") ?: ""

        if (requestId.isNotEmpty()) {
            openIntent()
        }
    }

    override fun onResume() {
        super.onResume()
        // 原参考代码中有针对剪贴板的特定处理逻辑，此处保持空结构以符合原逻辑流
    }

    private fun openIntent(): Boolean {
        val pkg = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (!pkg.isNullOrEmpty()) {
            val targetIntent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }

            if (targetIntent != null) {
                // [核心优化]：在代理页内部处理非导出 Activity 的提权跳转
                val component = targetIntent.component
                if (component != null && !WindowRouter.isComponentExported(this, component)) {
                    Log.d("ShareOpenProxy", "检测到非导出 Activity，启动助手提权流: $component")
                    lifecycleScope.launch {
                        val success = WindowRouter.launchViaAssistant(this@ShareOpenAppProxyActivity, targetIntent)
                        finishWithResult(success = success)
                    }
                    return true
                }

                try {
                    startActivity(targetIntent)
                    finishWithResult(success = true)
                    return true
                } catch (e: Exception) {
                    Log.e("ShareOpenProxy", "Direct launch failed", e)
                    finishWithResult(success = false, data = e.message)
                    return false
                }
            } else {
                // 参考代码逻辑：尝试获取目标应用的启动入口（包名降级方案）
                val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        startActivity(launchIntent)
                        finishWithResult(success = true)
                        return true
                    } catch (e: Exception) {
                        Log.e("ShareOpenProxy", "Fallback launch failed", e)
                        finishWithResult(success = false, data = e.message)
                        return false
                    }
                } else {
                    // 目标应用未安装
                    Toast.makeText(this, "启动失败：未找到目标应用", Toast.LENGTH_SHORT).show()
                }
            }
        }

        finishWithResult(success = false, data = "Missing parameters or package")
        return false
    }

    private fun finishWithResult(success: Boolean, data: Any? = null) {
        if (requestId.isNotEmpty()) {
            ShareOpenResultManager.completeResult(requestId, success = success, data = data)
        }
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 如果 Activity 被意外销毁且 requestId 还在，尝试发送失败结果以释放主进程等待
        if (requestId.isNotEmpty() && !isFinishing) {
            ShareOpenResultManager.completeResult(requestId, success = false, data = "Activity Destroyed")
        }
    }
}
