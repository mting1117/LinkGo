package com.moting.linkgo.util

import android.content.Context
import android.os.Build
import com.moting.linkgo.data.SettingsCache

/**
 * 全局主题色彩与动态取色统一适配辅助
 */
object ThemeColorHelper {
    // 默认 Google 蓝标准强调色 (关闭动态取色或 Android 11 及以下系统时使用)
    const val GOOGLE_BLUE_LIGHT = 0xFF1B73E8.toInt()
    const val GOOGLE_BLUE_DARK = 0xFF8AB4F8.toInt()

    /**
     * 获取当前环境强调色 (支持 Monet 动态取色开关与系统深浅色切换)
     */
    fun getAccentColor(context: Context, isDark: Boolean): Int {
        val useDynamic = SettingsCache.dynamicColorEnabled
        if (useDynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val resId = if (isDark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600
            val dynamicColor = runCatching { context.getColor(resId) }.getOrNull()
            if (dynamicColor != null && dynamicColor != 0) return dynamicColor
        }
        return if (isDark) GOOGLE_BLUE_DARK else GOOGLE_BLUE_LIGHT
    }
}
