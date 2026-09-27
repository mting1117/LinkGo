package com.moting.linkgo.image

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context

/**
 * 剪贴板读取：把 `ClipData` 归一为 [ClipPayload]。
 *
 * 与旧的 `extractPlainText()` 的关系：**文本分支逐条保持原判据**，只是搬了个家，
 * 好让图片分支与文本分支在同一处决策，避免两条链路各自演化。
 *
 * 关于图片：这里**不解码、不落盘**，只产出 URI/MIME 元数据。
 * 原因是本读取必然发生在抢焦点的那一小段窗口内（主线程），
 * 图片解码是重 IO，必须留给 [ImageStore.ingest] 在后台线程执行。
 */
object ClipboardReader {

    /**
     * 读取剪贴板并归一为载荷。
     *
     * 文本判据（与重构前完全一致，不得放宽）：
     * 1. 必须声明了 `text/plain` 或 `text/html`；
     * 2. Item 不得带 URI 或 Intent，避免 `coerceToText` 把 `content://` 强转成字符串。
     *
     * @return 文本载荷、图片源元数据、或无法识别时的 null
     */
    fun read(clip: ClipData?): ClipPayload? {
        if (clip == null || clip.itemCount == 0) return null
        val description = clip.description ?: return null
        val item = clip.getItemAt(0) ?: return null

        readText(description, item)?.let { return it }

        return readImageSource(description, item)
    }

    /** 严格提取纯文本/HTML 文本内容；非文本返回 null */
    private fun readText(description: ClipDescription, item: ClipData.Item): ClipPayload.Text? {
        val hasTextMime = description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
            description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
        if (!hasTextMime) return null

        if (item.uri != null || item.intent != null) return null

        val text = (item.text ?: item.htmlText)?.toString()?.trim()
        if (text.isNullOrBlank()) return null

        return ClipPayload.Text(text = text, timestamp = descriptionTimestamp(description))
    }

    /**
     * 识别图片源。
     *
     * 只有拿到可解析的引用才算图片：内联图片（既无 URI 也无本地路径）本方案不解码，
     * 因为那需要把整个 Bitmap 塞进 ClipData，实际来源几乎不会出现。
     */
    private fun readImageSource(
        description: ClipDescription,
        item: ClipData.Item
    ): ClipPayload.ImageSource? {
        val uri = item.uri
        val mime = detectImageMime(descriptionMimeTypes(description), uri?.scheme) ?: return null

        // file:// scheme 时本地路径就是 URI 自身，交给 ImageStore 作为兜底通道
        val declaredPath = uri?.takeIf { it.scheme.equals("file", ignoreCase = true) }?.path

        return ClipPayload.ImageSource(
            uri = uri,
            declaredPath = declaredPath,
            mimeType = mime,
            timestamp = descriptionTimestamp(description)
        )
    }

    /** `ClipDescription.timestamp` 需 API 24+；本项目 minSdk 29，恒可用 */
    private fun descriptionTimestamp(description: ClipDescription): Long = description.timestamp

    /**
     * 取出 mime 声明数组。
     *
     * `ClipDescription.getMimeType(index)` 是唯一可用的取值方式（没有 `mimeTypes` 属性），
     * 因此此处按 `mimeTypeCount` 逐个取；绝大多数剪贴板只有 1~2 个类型，开销可忽略。
     */
    private fun descriptionMimeTypes(description: ClipDescription): Array<String> =
        Array(description.mimeTypeCount) { description.getMimeType(it) }

    /**
     * 解析剪贴板图片并落盘。
     *
     * **必须在后台线程调用**：内部含解码与文件 IO。
     * 抢焦点窗口只负责 [read] 的元数据读取，重活留到这里。
     *
     * @return 落盘完成的载荷；内容不可读时返回 null（调用方须给出占位提示，不得静默失败）
     */
    fun resolveImage(context: Context, source: ClipPayload.ImageSource): ClipPayload.Image? =
        ImageStore.ingest(
            context = context,
            sourceUri = source.uri,
            declaredPath = source.declaredPath,
            mimeType = source.mimeType,
            sourcePackage = null,
            timestamp = source.timestamp
        )

    /** 供诊断日志使用的一句话摘要 */
    fun describe(payload: ClipPayload): String = when (payload) {
        is ClipPayload.Text -> "文本(len=${payload.text.length})"
        is ClipPayload.ImageSource -> "图片源(uri=${payload.uri}, mime=${payload.mimeType})"
        is ClipPayload.Image -> "图片(${payload.width}x${payload.height}, ${payload.byteSize}B)"
    }
}
