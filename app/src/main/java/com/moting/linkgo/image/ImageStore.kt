package com.moting.linkgo.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.moting.linkgo.util.SuperIslandLogManager
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * 剪贴板图片仓库：落盘、去重、TTL 清理、对外授权 URI 生成。
 *
 * 三条设计铁律（对应《图片识别方案》§2.3）：
 * 1. **源 `content://` 绝不外传**：源 URI 属于别的应用，既无授权也未导出，
 *    目标分享应用打不开它。对外一律经 [shareUri] 生成带授权的 FileProvider URI。
 * 2. **内容指纹用「长度 + 前 64KB」，且只服务于内存去重**：调用方拿到 [ClipPayload.Image.fingerprint]
 *    与上一次比对即可挡住重复触发，**不做基于文件名的内容寻址**——那会为了算名字多读一遍磁盘。
 * 3. **临时文件必须有界**：图片是缓存不是资产，靠 [cleanupExpired] 按 TTL 与数量上限收敛。
 *
 * 多进程安全性：本类只在主进程使用，无需跨进程同步（提权后端与 LSPosed 阶段见方案 §2.4）。
 */
object ImageStore {

    private const val TAG = "LinkGo_ImageStore"

    private const val DIR_NAME = "clip_images"
    private const val TEMP_PREFIX = ".tmp_"

    /** 内容指纹采样长度：64KB */
    private const val FINGERPRINT_SAMPLE_BYTES = 64 * 1024

    /** 超限降采样后的最长边 */
    private const val MAX_EDGE = 1600

    /** 降采样触发阈值：原图不超过 8MB 时逐字节保真落盘，不做任何重编码 */
    private const val MAX_INLINE_BYTES = 8L * 1024 * 1024

    /** 降采样 JPEG 质量 */
    private const val DOWNSCALE_QUALITY = 85

    /** 留存时长上限：24 小时 */
    private const val TTL_MILLIS = 24L * 60 * 60 * 1000

    /** 数量上限：超出时淘汰最旧 */
    private const val MAX_ENTRIES = 20

    /** 未收编临时文件的容忍时长：超过即视为上次摄入中途失败 */
    private const val TEMP_GRACE_MILLIS = 60_000L

    /** 编辑页预览图的目标最长边（像素） */
    private const val PREVIEW_EDGE_PX = 512

    /**
     * 落盘一次图片载荷。
     *
     * 通道优先级：
     * 1. [sourceUri] 可解析 → 零拷贝落盘（小图逐字节保真，超 [MAX_INLINE_BYTES] 才重编码）
     * 2. [sourceUri] 失败且 [declaredPath] 是本地文件 → 直接收编
     * 3. 都失败 → 返回 null，调用方**必须**给出占位提示，不得静默失败
     *
     * 落盘后统一执行 [cleanupExpired]，保证目录始终有界。
     *
     * @param sourceUri 剪贴板给出的源 URI；不可解析时配合 [declaredPath] 兜底
     * @param declaredPath 已知本地文件路径（如 `file://` scheme）
     * @param mimeType [detectImageMime] 的判定结果
     * @param sourcePackage 来源应用包名，公开 API 下常为空
     * @param timestamp 剪贴板变动时刻，可为 0
     */
    fun ingest(
        context: Context,
        sourceUri: Uri?,
        declaredPath: String? = null,
        mimeType: String,
        sourcePackage: String?,
        timestamp: Long = 0L
    ): ClipPayload.Image? {
        val appContext = context.applicationContext
        val dir = ensureDir(appContext)

        val result = ingestViaUri(appContext, dir, sourceUri, mimeType, sourcePackage, timestamp)
            ?: ingestViaPath(dir, declaredPath, mimeType, sourcePackage, timestamp)

        if (result == null) {
            Log.w(TAG, "[摄入失败] 源 URI 无法解析: uri=$sourceUri, declaredPath=$declaredPath, mime=$mimeType")
            SuperIslandLogManager.log(
                appContext, "IMAGE_INGEST_FAILED",
                "剪贴板图片内容不可读: uri=$sourceUri, declaredPath=$declaredPath, mime=$mimeType"
            )
            return null
        }

        // 复用已有条目时刷新 mtime，避免刚被复用的图片立刻到期
        result.file.setLastModified(System.currentTimeMillis())
        cleanupExpired(appContext)
        Log.i(
            TAG,
            "[摄入成功] ${result.file.name}, ${result.width}x${result.height}, " +
                "${result.byteSize}B, mime=${result.mimeType}, 来源=${result.sourcePackage ?: "未知"}"
        )
        return result
    }

    /**
     * 通过输入流直接摄入图片数据（供 LSPosed 共享内存或流式传输使用）。
     */
    fun ingestStream(
        context: Context,
        input: InputStream,
        mimeType: String,
        sourcePackage: String?,
        timestamp: Long = 0L
    ): ClipPayload.Image? {
        val appContext = context.applicationContext
        val dir = ensureDir(appContext)
        val temp = writeTemp(dir, input) ?: return null
        val result = finalizeEntry(temp, mimeType, sourcePackage, timestamp)
        if (result != null) {
            result.file.setLastModified(System.currentTimeMillis())
            cleanupExpired(appContext)
            Log.i(
                TAG,
                "[摄入成功(流)] ${result.file.name}, ${result.width}x${result.height}, " +
                    "${result.byteSize}B, mime=${result.mimeType}, 来源=${result.sourcePackage ?: "未知"}"
            )
        }
        return result
    }

    /**
     * 生成对外分享用的 URI：**永远指向本仓库内的文件**，并由 FileProvider 授权。
     *
     * `file_paths.xml` 已声明 `<files-path path="." />`，本仓库目录天然落在授权范围内，
     * 因此无需新增路径条目（重复路径会让 FileProvider 解析时抛异常）。
     */
    fun shareUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /**
     * 内容指纹：文件长度 + 前 64KB 的 SHA-256。
     *
     * 用途**仅是内存去重**（调用方与上一次的指纹比对），不作为文件名，
     * 因此这里多读一遍文件是可接受的：图片载荷的摄入本就是一次 IO 密集操作。
     *
     * **已知取舍**：长度相同且前 64KB 相同、仅尾部不同的两份图会被判为同一张。
     * 在实际剪贴板场景（同一张图被重复复制）中不会出现，换取的是"不必多读一遍整个文件"。
     */
    fun computeFingerprint(file: File): String {
        val sample = file.inputStream().use { input ->
            val buffer = ByteArray(FINGERPRINT_SAMPLE_BYTES)
            val read = input.read(buffer)
            if (read > 0) buffer.copyOf(read) else ByteArray(0)
        }
        return fingerprintOf(file.length(), sample)
    }

    /**
     * 指纹算法的唯一实现，仅依赖 JVM 类型，便于本地单元测试直接验证。
     *
     * 哈希顺序固定为「先长度、后采样字节」；改动此顺序会让所有内存中的历史指纹失去可比性。
     */
    internal fun fingerprintOf(length: Long, sample: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(length.toString().toByteArray())
        if (sample.isNotEmpty()) digest.update(sample)
        return digest.digest().toHex()
    }

    /**
     * 清理过期与超量条目。可在任意线程调用；目录不存在时直接返回。
     *
     * @return 被删除的文件数
     */
    fun cleanupExpired(context: Context): Int {
        val dir = File(context.applicationContext.filesDir, DIR_NAME)
        val entries = dir.listFiles()?.filter { it.isFile } ?: return 0
        if (entries.isEmpty()) return 0

        val now = System.currentTimeMillis()
        var removed = 0

        val (temps, formalAll) = entries.partition { it.name.startsWith(TEMP_PREFIX) }

        // 1. 未收编的临时文件：超过容忍时长仍未收编，说明上次摄入中途失败
        temps.forEach { temp ->
            if (now - temp.lastModified() > TEMP_GRACE_MILLIS && temp.delete()) removed++
        }

        // 2. 正式条目：按 TTL 淘汰
        val (expired, alive) = formalAll.partition { now - it.lastModified() > TTL_MILLIS }
        expired.forEach { if (it.delete()) removed++ }

        // 3. 正式条目：按数量上限淘汰最旧
        val sorted = alive.sortedByDescending { it.lastModified() }
        if (sorted.size > MAX_ENTRIES) {
            sorted.drop(MAX_ENTRIES).forEach { if (it.delete()) removed++ }
        }

        if (removed > 0) Log.i(TAG, "已清理 $removed 个图片缓存文件")
        return removed
    }

    /**
     * 把图片保存到系统相册（MediaStore）。
     *
     * 两条实现要点：
     * 1. **API 30+ 不需要任何权限**：Scoped Storage 下往 MediaStore 写自己的图无需授权，
     *    因此这里不申请 WRITE_EXTERNAL_STORAGE，避免触发多余的系统弹窗；
     *    API 29 需要该权限，已在清单里以 maxSdkVersion="29" 限定声明。
     * 2. 用 IS_PENDING 标记写入过程，写完再置 0：避免相册在文件还没拷完时就索引到半张图。
     *
     * @return 相册条目的 uri；失败返回 null（调用方须给出可见提示）
     */
    fun saveToGallery(
        context: Context,
        image: ClipPayload.Image,
        isTemporary: Boolean = false
    ): Uri? {
        val resolver = context.applicationContext.contentResolver
        // 保留原始格式：来源可能是 PNG / WebP（截图、剪贴板、相册各不相同），
        // 统一按 jpg 命名会让相册文件名与实际内容不符
        val extension = extensionFor(image.mimeType)
        val mime = if (image.mimeType.startsWith("image/")) image.mimeType else "image/jpeg"
        val prefix = if (isTemporary) TemporaryImageCleaner.PREFIX_TEMP else "LinkGo_"
        val displayName = "${prefix}${System.currentTimeMillis()}.$extension"
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, mime)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(
                    android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_PICTURES + "/LinkGo"
                )
                put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        return try {
            val uri = resolver.insert(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            ) ?: return null

            val copied = resolver.openOutputStream(uri)?.use { output ->
                image.file.inputStream().use { input -> input.copyTo(output) }
                true
            } ?: false
            if (!copied) {
                resolver.delete(uri, null, null)
                return null
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                resolver.update(
                    uri,
                    android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                    },
                    null,
                    null
                )
            }
            if (isTemporary) {
                TemporaryImageCleaner.scheduleAutoDelete(context, uri, displayName)
                Log.i(TAG, "[临时保存相册成功] $displayName, 5分钟后自动清理")
            } else {
                Log.i(TAG, "[保存相册成功] $displayName")
            }
            uri
        } catch (e: Exception) {
            Log.w(TAG, "保存到相册失败: ${e.message}")
            SuperIslandLogManager.log(context, "IMAGE_SAVE_FAILED", "保存图片到相册失败: ${e.message}")
            null
        }
    }

    /** 仅供调试与诊断：按最近使用时间倒序列出当前缓存条目 */
    fun listEntries(context: Context): List<File> =
        File(context.applicationContext.filesDir, DIR_NAME)
            .listFiles()
            ?.filter { it.isFile && !it.name.startsWith(TEMP_PREFIX) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /**
     * 按目标尺寸采样解码缩略图，供胶囊与通知展示。
     *
     * 绝不整图解码：剪贴板图片可能是十几 MB 的巨图，
     * 直接解到内存会让悬浮窗首帧卡顿甚至 OOM（比对文本载荷的代价高一个数量级）。
     *
     * @param targetEdge 目标最长边（像素），默认 96dp 量级
     * @return 缩略图；解码失败返回 null（调用方回落到默认图标）
     */
    fun loadThumbnail(file: File, targetEdge: Int): Bitmap? {
        if (!file.isFile || targetEdge <= 0) return null
        return try {
            // 第一遍只读尺寸，供采样率计算
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, targetEdge)
            }
            BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (e: Exception) {
            Log.w(TAG, "缩略图解码失败: ${e.message}")
            null
        }
    }

    /**
     * 按路径加载缩略图。
     *
     * 供图片规则编辑页的测试区使用：那里的图片来源是用户从相册挑的 URI，
     * 需要先读尺寸与 MIME 才能组装出一个可用于试跳的 [ClipPayload.Image]。
     * 该载荷**不落盘**（只是预览与试跳用），因此与 [ingest] 分开。
     *
     * @return （缩略图, 宽, 高, MIME）；无法解码时返回 null
     */
    fun loadPreview(context: Context, uri: android.net.Uri): PreviewImage? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, PREVIEW_EDGE_PX)
            }
            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return null

            PreviewImage(
                bitmap = bitmap,
                width = bounds.outWidth,
                height = bounds.outHeight,
                mimeType = context.contentResolver.getType(uri) ?: "image/*"
            )
        } catch (e: Exception) {
            Log.w(TAG, "预览解码失败: ${e.message}")
            null
        }
    }

    /** 预览图（不落盘，仅用于编辑页展示与试跳） */
    data class PreviewImage(
        val bitmap: Bitmap,
        val width: Int,
        val height: Int,
        val mimeType: String
    )

    //region 摄入通道

    private fun ingestViaUri(
        context: Context,
        dir: File,
        uri: Uri?,
        mimeType: String,
        sourcePackage: String?,
        timestamp: Long
    ): ClipPayload.Image? {
        if (uri == null) return null
        return try {
            // 第一遍：只读头部拿尺寸，不解码像素
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Log.w(TAG, "无法解析图片尺寸: $uri")
                return null
            }

            // 第二遍：边读边写临时文件（指纹留到收编阶段统一算，避免重复哈希）
            val temp = context.contentResolver.openInputStream(uri)?.use { input ->
                writeTemp(dir, input)
            } ?: return null

            if (temp.length() <= MAX_INLINE_BYTES) {
                return finalizeEntry(temp, mimeType, sourcePackage, timestamp)
            }

            Log.i(TAG, "原图 ${temp.length()}B 超过 ${MAX_INLINE_BYTES}B，执行降采样重编码")
            val scaled = downscale(context, dir, uri, bounds.outWidth, bounds.outHeight)
            temp.delete()
            return scaled?.let { finalizeEntry(it, "image/jpeg", sourcePackage, timestamp) }
        } catch (e: Exception) {
            // SecurityException 是主进程解析第三方 provider 的常见结果，属预期分支
            Log.w(TAG, "源 URI 摄入失败: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun ingestViaPath(
        dir: File,
        declaredPath: String?,
        mimeType: String,
        sourcePackage: String?,
        timestamp: Long
    ): ClipPayload.Image? {
        if (declaredPath.isNullOrBlank()) return null
        val source = File(declaredPath)
        if (!source.isFile) return null
        if (source.parentFile?.absolutePath == dir.absolutePath) {
            // 已在仓库内（复用路径），直接收编，不做二次拷贝
            return finalizeEntry(source, mimeType, sourcePackage, timestamp)
        }
        return try {
            val temp = source.inputStream().use { writeTemp(dir, it) }
            finalizeEntry(temp, mimeType, sourcePackage, timestamp)
        } catch (e: Exception) {
            Log.w(TAG, "本地路径摄入失败: ${e.message}")
            null
        }
    }

    /**
     * 把临时文件收编为正式条目：重命名为带时间戳的正式名，并复读尺寸。
     *
     * 文件名不含指纹：指纹的用途只是给调用方做**内存级**去重，
     * 做成文件名会为了"算出名字"而多读一遍整个文件，收益不抵成本。
     */
    private fun finalizeEntry(
        source: File,
        mimeType: String,
        sourcePackage: String?,
        timestamp: Long
    ): ClipPayload.Image? {
        val dir = source.parentFile ?: return null
        val target = File(dir, "clip_${System.currentTimeMillis()}.${extensionFor(mimeType)}")

        if (source.absolutePath != target.absolutePath && !source.renameTo(target)) {
            try {
                source.copyTo(target, overwrite = true)
            } catch (e: Exception) {
                Log.w(TAG, "收编图片失败: ${e.message}")
                source.delete()
                target.delete()
                return null
            }
            source.delete()
        }

        // 用 inJustDecodeBounds 复读尺寸：比在内存里传 Bitmap 省一个数量级的内存
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(target.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.w(TAG, "收编后的图片无法解码，丢弃: ${target.name}")
            target.delete()
            return null
        }

        return ClipPayload.Image(
            file = target,
            mimeType = mimeType,
            width = bounds.outWidth,
            height = bounds.outHeight,
            byteSize = target.length(),
            fingerprint = computeFingerprint(target),
            sourcePackage = sourcePackage,
            timestamp = timestamp
        )
    }

    //endregion

    //region 降采样

    /** 按最长边约束生成降采样副本；失败返回 null（调用方放弃本条载荷，而非落盘超大原图） */
    private fun downscale(context: Context, dir: File, uri: Uri, srcWidth: Int, srcHeight: Int): File? {
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(srcWidth, srcHeight, MAX_EDGE)
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        val longest = maxOf(decoded.width, decoded.height)
        val scaled = if (longest > MAX_EDGE) {
            val ratio = MAX_EDGE.toFloat() / longest
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * ratio).toInt().coerceAtLeast(1),
                (decoded.height * ratio).toInt().coerceAtLeast(1),
                true
            )
        } else {
            decoded
        }

        val temp = File(dir, TEMP_PREFIX + "scaled_" + System.nanoTime())
        return try {
            temp.outputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, DOWNSCALE_QUALITY, out)
            }
            if (scaled !== decoded) decoded.recycle()
            scaled.recycle()
            temp
        } catch (e: Exception) {
            Log.w(TAG, "降采样落盘失败: ${e.message}")
            temp.delete()
            null
        }
    }

    /** 计算 2 的幂次采样率，让解码后的最长边落在 (maxEdge, maxEdge*2] 区间内（压住峰值内存） */
    private fun calculateInSampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        while (maxOf(width / sample, height / sample) > maxEdge * 2) {
            sample *= 2
        }
        return sample
    }

    //endregion

    //region 文件工具

    /**
     * 边读边写临时文件，指纹由 [computeFingerprint] 在收编阶段统一计算。
     *
     * 文件名带 `System.nanoTime()` 而非指纹：同一内容可能被并发摄入两次，
     * 若临时文件名即指纹，两次写入会互相覆盖读写出错。
     */
    private fun writeTemp(dir: File, input: InputStream): File {
        val temp = File(dir, TEMP_PREFIX + System.nanoTime())
        try {
            temp.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                }
            }
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
        return temp
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun ensureDir(context: Context): File {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 由 MIME 推导扩展名；仅为了文件可读，功能上不依赖它（FileProvider 按 mime 判定） */
    private fun extensionFor(mimeType: String): String = when (mimeType.lowercase()) {
        "image/png" -> "png"
        "image/jpeg", "image/jpg" -> "jpg"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/bmp" -> "bmp"
        "image/heic", "image/heif" -> "heic"
        else -> "img"
    }

    //endregion
}
