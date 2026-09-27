package com.moting.linkgo;

/**
 * 剪贴板变更回调接口。
 * 由主进程实现，传入 Shizuku 进程中的 UserService。
 * 当 Shizuku shell 进程检测到剪贴板变化后，通过该接口把文本回调给主进程。
 */
interface IClipboardCallback {
    void onClipboardChanged(String text, long timestamp);
}