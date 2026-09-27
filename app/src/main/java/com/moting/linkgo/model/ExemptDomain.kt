package com.moting.linkgo.model

import java.util.UUID

/**
 * 域名放行规则（白名单）：作用于「已接管应用」内部的链接过滤。
 *
 * 判定：来源应用匹配（[sourcePkg]，必填，只能选择已接管应用）且 URL 按 [matchType]（见 [MatchType]）匹配 [pattern] 时，
 * 该链接视为应用生态内内容，LinkGo 不进行捕获与拦截（内置浏览器照常打开）。
 * 匹配语义与分发规则一致：
 * - CONTAINS：host 等于/子域匹配 pattern，否则 URL 包含 pattern；
 * - REGEX：正则匹配 URL；
 * - EXACT：URL 精确等于 pattern（忽略大小写）。
 */
data class ExemptDomain(
    val id: String = UUID.randomUUID().toString(),
    /** 来源应用包名（必填：只能选择已接管应用） */
    val sourcePkg: String,
    /** 匹配内容（域名 / 包含文本 / 正则） */
    val pattern: String,
    /** 匹配模式（默认 CONTAINS：保留"域名子域匹配"语义） */
    val matchType: MatchType = MatchType.CONTAINS,
    val note: String = "",
    val isEnabled: Boolean = true
) {
    companion object {
        const val PKG_WECHAT = "com.tencent.mm"
        const val PKG_QQ = "com.tencent.mobileqq"

        /** 出厂默认放行规则（来源均为默认已接管应用） */
        fun defaults(): List<ExemptDomain> = listOf(
            ExemptDomain(UUID.randomUUID().toString(), PKG_WECHAT, "mp.weixin.qq.com", note = "公众号文章"),
            ExemptDomain(UUID.randomUUID().toString(), PKG_WECHAT, "weixin.qq.com", note = "微信官方页"),
            ExemptDomain(UUID.randomUUID().toString(), PKG_WECHAT, "work.weixin.qq.com", note = "企业微信"),
            ExemptDomain(UUID.randomUUID().toString(), PKG_QQ, "qzone.qq.com", note = "QQ 空间"),
            ExemptDomain(UUID.randomUUID().toString(), PKG_QQ, "kf.qq.com", note = "腾讯客服")
        )
    }
}
