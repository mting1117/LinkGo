package com.moting.linkgo.model

import java.util.UUID

/**
 * 图片跳转规则。
 *
 * 与 [DispatchRule]（文本规则）的分工：**匹配部分整体取消**。
 * 已定稿的设计前提是"是图片就行"，不做 MIME / 尺寸 / 体积 / 比例 / 来源等过滤条件，
 * 因此本模型里没有正则、没有模板、没有短链、没有桥接分发——那些对图片没有作用对象。
 *
 * 由此产生一个必须记住的后果：**多条图片规则之间，列表顺序是唯一的仲裁依据**。
 * 靠前者永远优先，所以顺序必须一路贯通到规则列表、手动选择器与悬浮胶囊三处。
 *
 * 跳转动作部分与文本规则同构（窗口模式、不留后台卡片），
 * 因为这些能力由 `WindowRouter` 统一提供，图片规则零成本复用。
 */
data class ImageRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",

    /** 目标应用包名，需支持接收图片分享（ACTION_SEND + image 类型） */
    val targetPackage: String = "",

    /** 可选的目标 Activity 类名，用于定向到具体分享面板而不是应用入口 */
    val targetClass: String = "",

    /** -1 全局 / 5 小窗 / 1 全屏，语义与 [DispatchRule.ruleLaunchMode] 一致 */
    val ruleLaunchMode: Int = -1,

    /** 本规则跳转的应用不保留最近任务卡片 */
    val excludeFromRecents: Boolean = false,

    val isEnabled: Boolean = true,

    /** 自定义规则图标；为空时按 [targetPackage] 联动应用图标 */
    val iconPath: String? = null
)
