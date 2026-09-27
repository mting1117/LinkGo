package com.moting.linkgo.image

import java.io.File

/**
 * 剪贴板载荷统一抽象。
 *
 * 现有链路以裸 `String` 贯穿（`ClipboardHandler.handle(context, text)`），
 * 图片不是字符串，因此在此定义统一载荷类型，避免 `String?` 到处传、
 * 也避免为图片另建一条与文本平行的重复链路。
 *
 * 设计约定：
 * - [Text] 的语义与现有 `extractPlainText()` 的产物完全一致，保证文本链路行为不变；
 * - [Image.file] 指向**已落盘且必定可读**的本地文件，绝不携带原始 `content://`：
 *   源 URI 属于别的应用，既无授权也未导出，目标分享应用根本打不开它。
 */
sealed interface ClipPayload {

    /** 剪贴板变动的发生时刻（毫秒），用于冷启动补偿的新鲜度判定；0 表示系统未提供 */
    val timestamp: Long

    data class Text(
        val text: String,
        override val timestamp: Long = 0L
    ) : ClipPayload

    /**
     * 图片源元数据：只描述「从哪取」，不含像素。
     *
     * 刻意与 [Image] 分开的原因：元数据读取发生在抢焦点的主线程窗口内，必须极快；
     * 解码与落盘是重 IO，只能留给 [ImageStore.ingest] 在后台线程做。
     * 两者用不同类型区分，编译期就挡住"在主线程顺手解个码"的写法。
     */
    data class ImageSource(
        /** 剪贴板给出的内容引用；内联图片场景为 null */
        val uri: android.net.Uri?,
        /** 已知的本地文件路径，仅 `file://` scheme 时非空，作为 [uri] 失败后的兜底 */
        val declaredPath: String?,
        /** 归一化后的 MIME，必定以 `image/` 开头 */
        val mimeType: String,
        override val timestamp: Long = 0L
    ) : ClipPayload

    data class Image(
        /** 已落盘的本地文件，必定可读（见 [ImageStore.ingest]） */
        val file: File,
        /** 归一化后的 MIME，必定以 `image/` 开头 */
        val mimeType: String,
        val width: Int,
        val height: Int,
        val byteSize: Long,
        /** 内容指纹，供去重与文件名复用（见 [ImageStore.computeFingerprint]） */
        val fingerprint: String,
        /** 来源应用包名，公开 API 下常为空 */
        val sourcePackage: String?,
        override val timestamp: Long = 0L
    ) : ClipPayload
}

/**
 * 依据剪贴板的 mime 声明与 Item URI 判定图片 MIME。
 *
 * 两条判定路径：
 * 1. 明确带 `image/` 前缀的类型（`image/png` / `image/jpeg` / `image/*` …）；
 * 2. 少数来源只声明 `*/*`（或完全没有 mime 线索），但 `ClipData.Item` 上挂着图片 URI。
 *
 * 泛化兜底仅在「确实没有 mime 信息」时生效：一旦来源明确声明了非图片类型
 * （如安装包的 `application/vnd.android.package-archive`、压缩包的 `application/zip`），
 * 说明这是文件而非图片，不应再被误判为图片——否则会走图片链路解码失败，
 * 弹出「已复制图片（内容不可读）」的误导性提示。
 *
 * 参数刻意收成 `String?` 而非 `android.net.Uri?`：本函数的判定逻辑属纯 JVM 逻辑，
 * 用 Uri 会让它无法在本地单元测试中直接验证（`android.net.Uri` 在测试 classpath 上是空壳）。
 *
 * @param mimeTypes `ClipDescription.mimeTypes` 的副本，传 null 视为无信息
 * @param itemUriScheme `ClipData.Item.getUri()?.scheme`，仅用于第 2 条泛化判定
 * @return 归一化后的 `image/xxx`；非图片返回 null
 */
fun detectImageMime(mimeTypes: Array<String>?, itemUriScheme: String?): String? {
    mimeTypes?.firstOrNull { it.startsWith("image/", ignoreCase = true) }?.let { declared ->
        // 归一化大小写；泛型 image/* 保持原样交由 ACTION_SEND 处理
        return declared.lowercase()
    }

    // 来源声明了「具体」MIME、但其中没有任何图片类型 → 明确是文件（APK/压缩包等）而非图片，
    // 此时即便挂着 content:// 或 file:// URI 也不应误判为图片，直接放弃。
    // `*/*` 是「任意类型」通配，等于没给类型信息，不算具体声明，仍走下方泛化兜底。
    val hasConcreteMime = mimeTypes?.any { it != "*/*" } == true
    if (hasConcreteMime) return null

    // 泛化兜底：确实没有具体 mime 线索（空数组 / 仅 `*/*`）时，凭 URI scheme 猜一次
    return when (itemUriScheme?.lowercase()) {
        "content", "file" -> "image/*"
        else -> null
    }
}
