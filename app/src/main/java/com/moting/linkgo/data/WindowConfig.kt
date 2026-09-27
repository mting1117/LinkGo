package com.moting.linkgo.data

import android.graphics.Rect

/**
 * 小窗配置数据模型
 * 所有坐标和尺寸均以屏幕百分比 (0.0 - 1.0) 存储，以实现跨设备适配。
 */
data class WindowConfig(
    // 竖屏配置
    val portWidthRatio: Float = 0.8f,
    val portHeightRatio: Float = 0.55f,
    val portXRatio: Float = 0.1f,
    val portYRatio: Float = 0.225f,

    // 横屏配置
    val landWidthRatio: Float = 0.68f,
    val landHeightRatio: Float = 0.4f,
    val landXRatio: Float = 0.16f,
    val landYRatio: Float = 0.05f,

    // 窗口模式 (默认 5: Freeform, 11: FlymeOS)
    val windowingMode: Int = 5,

    // 是否启用小窗选项 (总开关)
    val isEnabled: Boolean = true
) {
    /**
     * 根据当前屏幕尺寸计算 1:1 的 Bounds
     */
    fun calculateBounds(screenWidth: Int, screenHeight: Int, isPortrait: Boolean): Rect {
        val wRatio = if (isPortrait) portWidthRatio else landWidthRatio
        val hRatio = if (isPortrait) portHeightRatio else landHeightRatio
        val xRatio = if (isPortrait) portXRatio else landXRatio
        val yRatio = if (isPortrait) portYRatio else landYRatio

        // 系统补偿系数：抵消 Freeform 模式下的默认缩进 (MIUI 经验值)
        // 根据要求：竖屏 1.42，横屏调整为 1.8
        val compensation = if (isPortrait) 1.42f else 1.8f
        
        val left = (xRatio * screenWidth).toInt()
        val top = (yRatio * screenHeight).toInt()
        
        // --- 核心修正：执行“安全补正”，允许补正后溢出，但尽量保证在物理边缘 ---
        val width = (wRatio * screenWidth * compensation).toInt().coerceAtLeast(100)
        val height = (hRatio * screenHeight * compensation).toInt().coerceAtLeast(100)

        // 记录 Log 用于调试基准值是否正确
        android.util.Log.d("WindowConfig", "基准计算: Baseline=${screenWidth}x${screenHeight} | 申请大小=${width}x${height} | 方向=${if(isPortrait) "竖" else "横"} | 注入位置=($left, $top)")

        return Rect(left, top, left + width, top + height)
    }

    fun getModeName(): String = when (windowingMode) {
        5 -> "自由窗口1 (标准)"
        100 -> "自由窗口2 (ColorOS)"
        102 -> "自由窗口3 (MagicOS)"
        4 -> "自由窗口4 (OriginOS)"
        11 -> "自由窗口5 (FlymeOS)"
        6 -> "自由窗口6 (努比亚)"
        else -> "未知模式 ($windowingMode)"
    }
}
