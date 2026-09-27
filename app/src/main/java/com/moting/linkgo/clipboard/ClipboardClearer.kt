package com.moting.linkgo.clipboard

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.moting.linkgo.ClipboardFocusActivity

/**
 * 跳转后清空剪贴板（防止目标应用读取剪贴板内容再次触发跳转）。
 *
 * 清空路径：
 * 1. LSPosed：有序广播给 system_server 中的 HookEntry 接收器，直接 clearPrimaryClip（最可靠）
 * 2. 提权通道（Shizuku / Root，统一走 PrivilegeEngine）：Shell 命令静默清空剪贴板
 * 3. 降级模式：启动透明 ClipboardFocusActivity 抢占焦点后清空
 *
 * 同时置「忽略下一次剪贴板变化」标记，避免清空动作被自身监听误判为新复制。
 */
object ClipboardClearer {
    private const val TAG = "ClipboardClearer"

    @Volatile
    private var ignoreNextChange: Boolean = false

    fun clearAfterJump(context: Context) {
        val appContext = context.applicationContext
        ignoreNextChange = true
        Log.w(TAG, "clearAfterJump fired")

        // 1) LSPosed 模式：向 system_server 发送有序广播清空
        runCatching {
            val intent = Intent(ClipboardHookContract.ACTION_CLEAR_CLIPBOARD).apply {
                setPackage("android")
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
            appContext.sendOrderedBroadcast(
                intent,
                ClipboardHookContract.PERMISSION_CLEAR_CLIPBOARD,
                object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context?, intent: Intent?) {
                        val ok = resultCode == Activity.RESULT_OK
                        Log.d(TAG, "LSPosed clear clipboard handshake result: $ok")
                    }
                },
                null,
                Activity.RESULT_CANCELED,
                null,
                null
            )
        }.onFailure { Log.w(TAG, "LSPosed clear broadcast failed: ${it.message}") }

        // 2) 提权通道模式（Shizuku / Root）：统一走 PrivilegeEngine，受全局特权总开关约束
        if (com.moting.linkgo.util.privilege.PrivilegeEngine.currentChannel() !=
            com.moting.linkgo.util.privilege.PrivilegeEngine.Channel.NONE
        ) {
            val ok = kotlinx.coroutines.runBlocking {
                com.moting.linkgo.util.privilege.PrivilegeEngine.exec(
                    "cmd clipboard set-primary-clip '' || service call clipboard 3"
                )
            }
            if (ok) {
                Log.d(TAG, "特权通道静默清空剪贴板已执行")
                return
            }
        }

        // 3) 降级模式：透明 Activity 抢焦点清空
        runCatching {
            appContext.startActivity(
                Intent(appContext, ClipboardFocusActivity::class.java).apply {
                    putExtra(ClipboardFocusActivity.EXTRA_CLEAR_CLIPBOARD, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }.onFailure { Log.w(TAG, "clear activity start failed: ${it.message}") }
    }

    /** 消费「忽略下一次剪贴板变化」标记；返回 true 表示本次变化应被忽略 */
    fun consumeIgnoreNextChange(): Boolean {
        val v = ignoreNextChange
        ignoreNextChange = false
        return v
    }
}