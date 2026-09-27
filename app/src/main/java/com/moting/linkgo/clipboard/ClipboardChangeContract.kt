package com.moting.linkgo.clipboard

/**
 * 对外发布的「剪贴板变动」广播契约。
 *
 * 与 [ClipboardHookContract]（Hook → 应用 的向内通道）方向相反，
 * 这里定义的是应用 → 外部应用的向外通知通道，供第三方按此契约被动接收。
 *
 * 隐私约定：该广播为公开隐式广播，**绝不携带剪贴板内容**（不含文本、不含链接、
 * 不含来源包名），仅告知「剪贴板发生了一次变动」，内容由接收方按需自行读取。
 */
object ClipboardChangeContract {

    /** 广播动作：剪贴板内容发生变动 */
    @JvmField val ACTION_CLIPBOARD_CHANGED = "com.moting.linkgo.action.CLIPBOARD_CHANGED"

    /** 变动发生时刻（long，System.currentTimeMillis） */
    @JvmField val EXTRA_CLIPBOARD_TIME = "com.moting.linkgo.extra.CLIPBOARD_TIME"
}
