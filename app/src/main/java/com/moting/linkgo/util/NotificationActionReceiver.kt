package com.moting.linkgo.util

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * 实时活动通知的快捷操作广播接收器。
 */
class NotificationActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_COPY_URL = "com.moting.linkgo.action.COPY_URL"
        const val EXTRA_URL = "extra_url"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION_COPY_URL) {
            val url = intent.getStringExtra(EXTRA_URL)
            if (!url.isNullOrBlank()) {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("URL", url))
                val tip = if (url.contains("\n")) "已复制全部链接" else "链接已复制"
                Toast.makeText(context, tip, Toast.LENGTH_SHORT).show()
                NotificationHelper.dismiss(context)
            }
        }
    }
}
