package com.moting.linkgo.clipboard

/**
 * LSPosed 钩子与广播接收器之间共享的契约常量。
 */
object ClipboardHookContract {
    @JvmField val APPLICATION_ID = "com.moting.linkgo"
    @JvmField val ACTION_HANDLE_CLIPBOARD_TEXT = "com.moting.linkgo.action.HANDLE_CLIPBOARD_TEXT"
    @JvmField val EXTRA_CLIPBOARD_TEXT = "com.moting.linkgo.extra.CLIPBOARD_TEXT"
    @JvmField val EXTRA_CLIPBOARD_SOURCE = "com.moting.linkgo.extra.CLIPBOARD_SOURCE"
    @JvmField val ACTION_CLEAR_CLIPBOARD = "com.moting.linkgo.action.CLEAR_CLIPBOARD"
    @JvmField val PERMISSION_CLEAR_CLIPBOARD = "com.moting.linkgo.permission.CLEAR_CLIPBOARD"

    // ── 图片通道 ──
    // 图片只传元数据，绝不传字节：主进程在 isDefaultIme 特权下直接读取剪贴板并落盘。
    @JvmField val EXTRA_IS_IMAGE = "com.moting.linkgo.extra.IS_IMAGE"
    @JvmField val EXTRA_CLIPBOARD_IMAGE_PATH = "com.moting.linkgo.extra.CLIPBOARD_IMAGE_PATH"
    @JvmField val EXTRA_CLIPBOARD_IMAGE_MIME = "com.moting.linkgo.extra.CLIPBOARD_IMAGE_MIME"
    @JvmField val EXTRA_CLIPBOARD_IMAGE_WIDTH = "com.moting.linkgo.extra.CLIPBOARD_IMAGE_WIDTH"
    @JvmField val EXTRA_CLIPBOARD_IMAGE_HEIGHT = "com.moting.linkgo.extra.CLIPBOARD_IMAGE_HEIGHT"
    @JvmField val EXTRA_CLIPBOARD_IMAGE_URI = "com.moting.linkgo.extra.CLIPBOARD_IMAGE_URI"
    @JvmField val EXTRA_CLIPBOARD_IMAGE_SHARED_MEMORY = "com.moting.linkgo.extra.CLIPBOARD_IMAGE_SHARED_MEMORY"
}