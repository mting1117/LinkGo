package com.moting.linkgo.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P0 单测：只覆盖不依赖 Android 框架的纯 JVM 逻辑。
 *
 * 落盘、去重、TTL 清理涉及 Context 与 BitmapFactory，本项目未引入 Robolectric，
 * 这部分走真机验证（见《图片识别方案》第六节 P1 阶段的验证方式）。
 */
class ImageStoreTest {

    @Test
    fun `相同长度与相同采样内容产出相同指纹`() {
        val a = ImageStore.fingerprintOf(1024L, byteArrayOf(1, 2, 3, 4))
        val b = ImageStore.fingerprintOf(1024L, byteArrayOf(1, 2, 3, 4))
        assertEquals(a, b)
    }

    @Test
    fun `长度不同则指纹不同`() {
        val a = ImageStore.fingerprintOf(1024L, byteArrayOf(1, 2, 3, 4))
        val b = ImageStore.fingerprintOf(2048L, byteArrayOf(1, 2, 3, 4))
        assertNotEquals(a, b)
    }

    @Test
    fun `采样内容不同则指纹不同`() {
        val a = ImageStore.fingerprintOf(1024L, byteArrayOf(1, 2, 3, 4))
        val b = ImageStore.fingerprintOf(1024L, byteArrayOf(1, 2, 3, 5))
        assertNotEquals(a, b)
    }

    @Test
    fun `空采样不应崩溃且与同长度非空采样不同`() {
        val empty = ImageStore.fingerprintOf(0L, ByteArray(0))
        val nonEmpty = ImageStore.fingerprintOf(0L, byteArrayOf(0))
        assertNotEquals(empty, nonEmpty)
    }

    @Test
    fun `指纹固定为 64 位十六进制小写`() {
        val hex = ImageStore.fingerprintOf(42L, byteArrayOf(9))
        assertEquals(64, hex.length)
        assertEquals(true, hex.all { it in "0123456789abcdef" })
    }

    @Test
    fun `仅尾部不同但长度相同时会判为同一张`() {
        // 刻意的取舍：指纹只采样前 64KB，用途仅是 2 秒窗口内的内存去重
        val head = ByteArray(128) { it.toByte() }
        val a = ImageStore.fingerprintOf(10_000L, head)
        val b = ImageStore.fingerprintOf(10_000L, head)
        assertEquals(a, b)
    }

    @Test
    fun `明确声明 image 前缀时直接采用`() {
        assertEquals("image/png", detectImageMime(arrayOf("image/png"), null))
        assertEquals("image/jpeg", detectImageMime(arrayOf("text/plain", "image/jpeg"), null))
    }

    @Test
    fun `MIME 大小写归一`() {
        assertEquals("image/webp", detectImageMime(arrayOf("IMAGE/WEBP"), null))
    }

    @Test
    fun `泛型 image 通配保持原样`() {
        assertEquals("image/*", detectImageMime(arrayOf("image/*"), null))
    }

    @Test
    fun `纯文本且无 URI 时不判为图片`() {
        assertNull(detectImageMime(arrayOf("text/plain", "text/html"), null))
        assertNull(detectImageMime(arrayOf("text/plain"), "http"))
    }

    @Test
    fun `无 MIME 信息时凭 content 或 file scheme 泛化判定`() {
        assertEquals("image/*", detectImageMime(null, "content"))
        assertEquals("image/*", detectImageMime(null, "file"))
    }

    @Test
    fun `仅通配 MIME 时仍走泛化兜底`() {
        // `*/*` 是「任意类型」通配，等于没给类型信息，不能据此否定图片可能
        assertEquals("image/*", detectImageMime(arrayOf("*/*"), "content"))
        assertEquals("image/*", detectImageMime(arrayOf("*/*"), "file"))
    }

    @Test
    fun `无 MIME 且 scheme 非本地时判为非图片`() {
        assertNull(detectImageMime(null, "http"))
        assertNull(detectImageMime(null, null))
        assertNull(detectImageMime(emptyArray(), null))
    }

    @Test
    fun `声明了非图片 MIME 时即使挂 content URI 也不判为图片`() {
        // 修复：来源明确声明了非图片类型（如 APK 的 application/vnd.android.package-archive），
        // 是文件而非图片，不得再凭 scheme 泛化误判，否则会走图片链路解码失败。
        assertNull(detectImageMime(arrayOf("text/plain"), "content"))
        assertNull(detectImageMime(arrayOf("application/vnd.android.package-archive"), "content"))
        assertNull(detectImageMime(arrayOf("application/zip"), "file"))
        assertNull(detectImageMime(arrayOf("application/octet-stream"), "content"))
    }
}