package com.moting.linkgo;

import com.moting.linkgo.IClipboardCallback;

/**
 * Shizuku 剪贴板监听服务接口（复刻 ClipShare）。
 *
 * 主进程绑定到 Shizuku 进程中的 UserService 后，通过该接口启动/停止监听。
 * UserService 通过 app_process + DEX（hiddenApi）或 logcat 检测剪贴板变化，
 * 并通过 IClipboardCallback 回调给主进程。
 */
interface IClipboardMonitor {
    void destroy() = 16777114; // Destroy method defined by Shizuku server
    void exit() = 1;
    void startListening(IClipboardCallback callback, boolean useRoot, String filePath, boolean useHiddenApi) = 2;
    void stopListening() = 3;
}