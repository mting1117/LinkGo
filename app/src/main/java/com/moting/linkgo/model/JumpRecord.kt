package com.moting.linkgo.model

import java.util.UUID

/**
 * 跳转记录信息。
 *
 * 记录按 [kind] 区分两类来源：文本链接跳转与图片规则派发。
 * 两者共用同一条历史列表与存储，仅渲染副标题不同——
 * 图片记录没有 URL、没有匹配条件，`originalUrl` 等字段对它无意义，故置空。
 */
data class JumpRecord(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val ruleName: String,     // 规则名称或“备选浏览器”
    val ruleId: String?,      // 对应 DispatchRule 的 ID，若为备选跳转则为 null
    val originalUrl: String,  // 原始跳转链接
    val targetPackage: String, // 目标包名
    val rulePattern: String? = null, // 匹配该记录的规则模式
    val matchTypeName: String? = null, // 匹配该记录的模式名称（如：包含、正则、精确）
    val executionStatus: Int = 0, // 执行状态：0-成功，1-失败
    val errorMessage: String? = null, // 错误信息，仅在失败时记录
    val traceId: String? = null,      // 用于关联同一次匹配跳转的唯一 ID
    val resultUrl: String? = null,    // 该步骤处理后生成的 URL
    val stepIndex: Int = 0,           // 匹配步骤序号
    /** 记录类型：TEXT=文本链接跳转（默认，兼容旧数据），IMAGE=图片规则派发 */
    val kind: String = KIND_TEXT
) {
    companion object {
        const val KIND_TEXT = "TEXT"
        const val KIND_IMAGE = "IMAGE"
    }
}

