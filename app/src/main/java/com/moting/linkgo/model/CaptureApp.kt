package com.moting.linkgo.model

/**
 * 应用内链接捕获接管应用模型。
 *
 * @param packageName 目标应用包名
 * @param isEnabled 是否开启接管（关闭后不拦截，但保留名下的放行规则）
 * @param addedAt 添加时间戳（用于界面上固定按添加时间稳定排序）
 */
data class CaptureApp(
    val packageName: String,
    val isEnabled: Boolean = true,
    val addedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val PKG_WECHAT = "com.tencent.mm"
        const val PKG_QQ = "com.tencent.mobileqq"

        /** 出厂默认接管应用列表：微信与 QQ */
        fun defaults(): List<CaptureApp> {
            val baseTime = 1700000000000L
            return listOf(
                CaptureApp(PKG_WECHAT, isEnabled = true, addedAt = baseTime),
                CaptureApp(PKG_QQ, isEnabled = true, addedAt = baseTime + 1000L)
            )
        }
    }
}
