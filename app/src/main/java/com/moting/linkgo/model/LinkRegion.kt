package com.moting.linkgo.model

import android.graphics.Rect

/**
 * 屏幕上的链接区域信息
 */
data class LinkRegion(
    val url: String,
    val rects: List<Rect>,
    val isFromDescription: Boolean = false,
    val groupId: Int = 0,
    val isPrecise: Boolean = false,
    val textOffset: Int = 0,
    val nodeBounds: Rect? = null
)
