package com.moting.linkgo.image

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.moting.linkgo.util.SuperIslandLogManager
import java.io.File

/**
 * 图片发送跳板：从通知点击进入，负责弹出系统分享选择器后立即退出。
 *
 * 为什么要一个空 Activity 而不是直接给通知挂 PendingIntent：
 * 通知的 contentIntent 必须是可序列化的 Intent，而"分享哪张图"是运行时才知道的，
 * 因此只传文件路径，由这个不可见的跳板在收到后组装 `ACTION_SEND`。
 * 这与工程内既有的 trampoline 用法一致（如 `LinkDispatcherActivity`）。
 */
class ImageSendActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_IMAGE_PATH)
        val mime = intent.getStringExtra(EXTRA_IMAGE_MIME) ?: "image/*"

        if (path.isNullOrBlank()) {
            SuperIslandLogManager.log(this, "IMAGE_SEND_INVALID", "图片跳板收到空路径，放弃发送")
            finish()
            return
        }

        val file = File(path)
        if (!file.isFile) {
            // 文件可能已被 TTL 清理删除，属预期情况：给出可见提示而非静默退出
            SuperIslandLogManager.log(this, "IMAGE_SEND_EXPIRED", "图片已过期或被清理: $path")
            com.moting.linkgo.util.InstantToastHelper.show(this, "图片已过期")
            finish()
            return
        }

        val image = ClipPayload.Image(
            file = file,
            mimeType = mime,
            width = intent.getIntExtra(EXTRA_IMAGE_WIDTH, 0),
            height = intent.getIntExtra(EXTRA_IMAGE_HEIGHT, 0),
            byteSize = file.length(),
            fingerprint = intent.getStringExtra(EXTRA_IMAGE_FINGERPRINT) ?: "",
            sourcePackage = null
        )
        ImageRouter.sendViaChooser(this, image)
        finish()
    }

    override fun finish() {
        super.finish()
        // 移除 Activity 切换动画，保证点击通知时无视觉闪动
        overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_IMAGE_PATH = "IMAGE_PATH"
        const val EXTRA_IMAGE_MIME = "IMAGE_MIME"
        const val EXTRA_IMAGE_WIDTH = "IMAGE_WIDTH"
        const val EXTRA_IMAGE_HEIGHT = "IMAGE_HEIGHT"
        const val EXTRA_IMAGE_FINGERPRINT = "IMAGE_FINGERPRINT"

        /** 组装指向本跳板的 Intent；各处只需调用此方法，避免 extra 名散落 */
        fun buildIntent(context: android.content.Context, image: ClipPayload.Image): Intent =
            Intent(context, ImageSendActivity::class.java).apply {
                putExtra(EXTRA_IMAGE_PATH, image.file.absolutePath)
                putExtra(EXTRA_IMAGE_MIME, image.mimeType)
                putExtra(EXTRA_IMAGE_WIDTH, image.width)
                putExtra(EXTRA_IMAGE_HEIGHT, image.height)
                putExtra(EXTRA_IMAGE_FINGERPRINT, image.fingerprint)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}
