package com.moting.linkgo.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import com.moting.linkgo.ui.LinkHighlighterActivity

class HighlighterTileService : TileService() {
    override fun onClick() {
        super.onClick()

        // 统一通过 Trampoline Activity 触发，确保通知栏能被 startActivityAndCollapse 正确收起
        val intent = Intent(this, LinkHighlighterActivity::class.java).apply {
            action = "com.moting.linkgo.action.HIGHLIGHT_LINKS"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("from_tile", true)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
