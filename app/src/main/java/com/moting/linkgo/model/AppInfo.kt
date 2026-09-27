package com.moting.linkgo.model

import android.graphics.drawable.Drawable

/**
 * 应用信息实体类
 * 
 * @param label 应用名称
 * @param packageName 包名
 * @param icon 应用图标
 */
data class AppInfo(
    val label: String,
    val packageName: String,
    val className: String? = null,
    val isSystemApp: Boolean = false
)
