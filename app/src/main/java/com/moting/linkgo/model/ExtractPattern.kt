package com.moting.linkgo.model

import java.util.UUID

/**
 * 链接提取规则
 * 每条规则定义一个正则表达式，用于从文本中发现特定类型的内容（链接、手机号、提取码等）
 */
data class ExtractPattern(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val pattern: String,
    val isEnabled: Boolean = true,
    val isBuiltin: Boolean = false
) {
    companion object {
        /** 内置规则的固定 ID */
        const val BUILTIN_HTTP_ID = "builtin_http"
        const val BUILTIN_MAGNET_ID = "builtin_magnet"
        const val BUILTIN_CODE_ID = "builtin_code"

        /** 返回出厂默认预置规则列表 */
        fun builtinDefaults(): List<ExtractPattern> = listOf(
            ExtractPattern(
                id = BUILTIN_HTTP_ID,
                name = "标准链接",
                pattern = "(?i)(?:[a-zA-Z0-9+.-]+://|[a-zA-Z0-9+.-]+%3A%2F%2F)[^\\s\\u4e00-\\u9fa5]+(?<![.,!?])",
                isBuiltin = false
            ),
            ExtractPattern(
                id = BUILTIN_MAGNET_ID,
                name = "磁力链接",
                pattern = "(?i)(magnet:\\?xt=urn:btih:[a-zA-Z0-9]+[^\\s\\u4e00-\\u9fa5<>\\[\\]{}|^]*)",
                isBuiltin = false
            ),
            ExtractPattern(
                id = BUILTIN_CODE_ID,
                name = "带提取码链接",
                pattern = "(?i)https?://[^\\s\\u4e00-\\u9fa5<>\\[\\]{}|^]+(?:\\s*(?:提取码|访问码)[：:\\s]\\s*[a-zA-Z0-9]+|\\?pwd=[a-zA-Z0-9]+)",
                isBuiltin = false
            )
        )
    }
}
