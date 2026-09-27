package com.moting.linkgo.image

import android.graphics.Bitmap
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 屏幕取图后的二维码解码。
 *
 * 职责边界刻意收得很窄：**只做「从已有位图解码」这一件事**。
 * 截屏仍由无障碍的 `takeScreenshot` 负责（见 [ScreenshotCapturer]），
 * 因此这里既不引摄像头扫码 UI，也不申请任何新权限。
 *
 * 性能是这里的第一约束——解码挂在用户交互的关键路径上（松手 → 出结果），
 * 因此用三层收敛把代价压住：
 * 1. **两档降级**：快速档先跑（不做「更努力地找」），失败才上兜底档。
 *    能解出来的图绝大多数在第一档命中，代价只有几十毫秒。
 * 2. **硬超时**：两档合计超过 [TOTAL_TIMEOUT_MS] 就按「没有二维码」处理，
 *    宁可回落到图片流程，也不让用户干等。
 * 3. **调用方负责线程**：本对象内部不切线程，但必须在后台线程调用
 *    ——zxing 是纯 CPU 解码，放主线程会直接卡住交互。
 */
object QrCodeDecoder {

    private const val TAG = "LinkGo_QrCode"

    /**
     * 解码总超时。
     *
     * 取 600ms 的依据：常规尺寸的区域图两档跑完通常在 100ms 内，
     * 真正会触发超时的基本只有「2K 全屏取图 + 低端机」这一档，
     * 而继续等待的收益（多解出一个小码）已经低于用户干等的代价。
     */
    private const val TOTAL_TIMEOUT_MS = 600L

    /** 只认二维码系码制：一维条码不是本功能的场景 */
    private val FORMATS = listOf(
        BarcodeFormat.QR_CODE,
        BarcodeFormat.DATA_MATRIX,
        BarcodeFormat.AZTEC
    )

    /** 快速档：先把最常见的正立二维码用最低成本解掉 */
    private val FAST_HINTS: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to FORMATS
    )

    /**
     * 兜底档：`TRY_HARDER` 换更彻底的采样；
     * `ALSO_INVERTED` 覆盖反色码（浅码深底），深色模式下的屏幕二维码很常见。
     */
    private val THOROUGH_HINTS: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to FORMATS,
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.ALSO_INVERTED to true
    )

    /**
     * 解码执行器：单线程常驻。
     *
     * 与 [ScreenshotCapturer] 的回调执行器同一形态：每次新建线程池会留下
     * 没有关停时机的线程；而超时被放弃的那次解码也需要一个稳定线程自然跑完。
     */
    private val executor by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "LinkGo-QrDecode").apply { isDaemon = true }
        }
    }

    /**
     * 尝试从位图中解出一个二维码。
     *
     * **必须在后台线程调用**。超时与「没解出来」都返回 null，两者都不算错误，
     * 调用方按「这张图没有二维码」处理即可。
     *
     * @return 解出的文本内容；null 表示未识别
     */
    fun decode(bitmap: Bitmap): String? {
        if (bitmap.width <= 0 || bitmap.height <= 0) return null
        val future = executor.submit(Callable<String?> { decodeInternal(bitmap) })
        return try {
            future.get(TOTAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            // 放弃等待，让那次解码在后台自然结束：zxing 是同步计算，无法真正中断
            future.cancel(true)
            Log.w(TAG, "二维码解码超时(${TOTAL_TIMEOUT_MS}ms)，按未识别处理")
            null
        } catch (e: Exception) {
            Log.w(TAG, "二维码解码异常: ${e.message}")
            null
        }
    }

    private fun decodeInternal(bitmap: Bitmap): String? {
        val source = try {
            RGBLuminanceSource(bitmap.width, bitmap.height, getBitmapPixels(bitmap))
        } catch (e: Exception) {
            Log.w(TAG, "位图像素读取失败: ${e.message}")
            return null
        }

        decodeWith(FAST_HINTS, BinaryBitmap(HybridBinarizer(source)))?.let {
            Log.i(TAG, "二维码快速档命中")
            return it
        }
        return decodeWith(THOROUGH_HINTS, BinaryBitmap(GlobalHistogramBinarizer(source)))?.let {
            Log.i(TAG, "二维码兜底档命中")
            it
        }
    }

    /** 解一次；NotFoundException 是「这张图没有码」的正常结论，不当作异常上报 */
    private fun decodeWith(hints: Map<DecodeHintType, Any>, binary: BinaryBitmap): String? {
        return try {
            MultiFormatReader().decode(binary, hints).text?.takeIf { it.isNotBlank() }
        } catch (e: NotFoundException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun getBitmapPixels(bitmap: Bitmap): IntArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels
    }
}
