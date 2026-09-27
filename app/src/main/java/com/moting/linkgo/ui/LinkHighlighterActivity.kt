package com.moting.linkgo.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.accessibility.selecttospeak.SelectToSpeakService
import com.moting.linkgo.util.AccessibilityUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 屏幕识别启动桩
 * 仅负责触发 SelectToSpeakService 的无障碍 Overlay 流程
 */
class LinkHighlighterActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startScanFlow()
    }

    private fun startScanFlow() {
        lifecycleScope.launch {
            // 第 1 步：获取服务实例并确认取词能力可用（实例存在 ≠ 可用，需排除假死；
            // 用按需探测判定，避免探测结论过期导致误判为不可用）
            var service = SelectToSpeakService.getInstance()
                ?.takeIf { AccessibilityUtils.isServiceUsableNow() }

            if (service == null) {
                // 分支 B：服务缺失或假死，触发静默重启（自救）
                Toast.makeText(this@LinkHighlighterActivity, "正在重启无障碍服务", Toast.LENGTH_SHORT).show()

                // 第 2 步：摘除登记 → 主动解绑 → 重新登记，并内部轮询确认重连
                try {
                    AccessibilityUtils.restartService(this@LinkHighlighterActivity)
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // 第 3 步：轮询等待系统拉起服务实例
                var retryCount = 0
                while (service == null && retryCount < 10) {
                    delay(200)
                    service = SelectToSpeakService.getInstance()
                        ?.takeIf { AccessibilityUtils.isServiceUsableNow() }
                    retryCount++
                }
            }

            if (service != null) {
                // 分支 A / 重启成功：正常情况
                // 第 4 步：UI 防抖动沉淀
                delay(150)
                val fromTile = intent.getBooleanExtra("from_tile", false)
                service.runIdentificationFlow(fromTile)
                finish()
            } else {
                Toast.makeText(this@LinkHighlighterActivity, "重启失败，检查权限", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun finish() {
        super.finish()
        // 移除 Activity 切换动画，确保启动 Service Overlay 时视觉无感
        overridePendingTransition(0, 0)
    }
}
