package com.moting.linkgo

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.accessibility.selecttospeak.SelectToSpeakService
import com.moting.linkgo.data.SettingsRepository
import com.moting.linkgo.model.EdgeGestureConfig
import com.moting.linkgo.overlay.RadarFinderOverlay
import com.moting.linkgo.util.AccessibilityUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 滑动直达启动桩
 *
 * 与屏幕识别（[com.moting.linkgo.ui.LinkHighlighterActivity]）、剪贴板分析
 * （[ClipboardAnalysisActivity]）对等的对外入口，供自动化工具或其它应用直接唤起。
 *
 * 手势路径是「手指从边缘划过」，外部调起没有手指事件，因此这里挂出的是**待命态**
 * 探照层：屏幕上先给出「按住屏幕滑动」提示，用户按下后再由 [RadarFinderOverlay]
 * 以其落点为杠杆基准接管，后续链路与手势触发完全一致。
 */
class RadarDirectActivity : ComponentActivity() {

    companion object {
        /**
         * 待命探照层的持有者。
         *
         * 必须静态持有：本 Activity 是 noHistory 的启动桩，onCreate 后立即 finish，
         * 实例若随它一起被回收，Overlay 就失去「再次唤起时先关掉旧的一层」的能力。
         */
        private var standbyOverlay: RadarFinderOverlay? = null

        fun showStandbyRadar(context: Context, config: EdgeGestureConfig) {
            standbyOverlay?.dismissImmediate()
            val overlay = RadarFinderOverlay(context)
            standbyOverlay = overlay
            val dm = context.resources.displayMetrics
            overlay.show(
                startX = dm.widthPixels * 0.5f,
                startY = dm.heightPixels * 0.5f,
                config = config,
                isRightEdge = true,
                standby = true
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startRadarFlow()
    }

    private fun startRadarFlow() {
        lifecycleScope.launch {
            // 第 1 步：获取服务实例并确认取词能力可用（实例存在 ≠ 可用，需排除假死）
            var service = SelectToSpeakService.getInstance()
                ?.takeIf { AccessibilityUtils.isServiceUsableNow() }

            if (service == null) {
                // 分支 B：服务缺失或假死，触发静默重启（自救）
                Toast.makeText(this@RadarDirectActivity, "正在重启无障碍服务", Toast.LENGTH_SHORT).show()

                // 第 2 步：摘除登记 → 主动解绑 → 重新登记，并内部轮询确认重连
                try {
                    AccessibilityUtils.restartService(this@RadarDirectActivity)
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

            if (service == null) {
                Toast.makeText(this@RadarDirectActivity, "重启失败，检查权限", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }

            // 第 4 步：UI 防抖动沉淀，与屏幕识别入口保持一致的取图时序
            delay(150)

            val config = SettingsRepository(applicationContext).edgeGestureConfigFlow.first()
            showStandbyRadar(service, config)
            finish()
        }
    }

    override fun finish() {
        super.finish()
        // 移除 Activity 切换动画，确保启动 Service Overlay 时视觉无感
        overridePendingTransition(0, 0)
    }
}
