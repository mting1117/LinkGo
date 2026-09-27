package com.moting.linkgo.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.moting.linkgo.util.SuperIslandLogManager

/**
 * 接收来自 LSPosed Hook (SystemUI 进程) 回传的跨进程超级岛诊断事件
 */
class SuperIslandLogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SuperIslandLogManager.ACTION_SUPER_ISLAND_DIAG_EVENT) return

        val tag = intent.getStringExtra(SuperIslandLogManager.EXTRA_DIAG_TAG) ?: "SYSTEM_UI"
        val message = intent.getStringExtra(SuperIslandLogManager.EXTRA_DIAG_MESSAGE) ?: return

        SuperIslandLogManager.logSystemUiEvent(context.applicationContext, tag, message)
    }
}
