package com.moting.linkgo.model

import android.graphics.drawable.Drawable

/**
 * Activity 组件信息
 */
data class ActivityInfo(
    val name: String, // 全路径类名
    val label: String, // 属性标签
    val packageName: String,
    val isExported: Boolean,
    val isMainOrView: Boolean = false, // 是否是常见入口 (MAIN/VIEW)
    val isLauncher: Boolean = false // 是否是应用启动入口
)

/**
 * 应用选择项扩展
 */
// 复用 AppInfo.kt 中的定义，但为了 Picker 可能需要更轻量的数据
