package com.moting.linkgo.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 屏幕区域取图：把用户指认的那一块截下来，交给分享链路。
 *
 * 与"自动识别图片"的区别：这里**不做任何判定**，区域完全由用户落点决定
 * （见 [ScreenRegionPicker]）。原先那套自动判据（类名关键词 / 无文字区域 / 面积占比）
 * 在真机上既会把状态栏图标、空输入框、导航栏背景误判成图片，
 * 又会漏掉类名被混淆的图片，已整体移除。
 *
 * 坐标基准：`AccessibilityNodeInfo.getBoundsInScreen()` 与
 * `AccessibilityService.takeScreenshot()` 返回的位图都基于**当前显示方向**，
 * 因此两者可以直接套用同一套坐标，无需处理旋转矩阵。
 */
object ScreenCapturer {

    private const val TAG = "LinkGo_ScreenCapture"

    /**
     * 截屏 + 按区域裁剪 + 二维码解码 + 落盘。
     *
     * **线程模型**：截屏那一步必须回主线程（`takeScreenshot` 的硬契约），
     * 而裁剪、二维码解码、JPEG 落盘全是 CPU/IO 活，一律切到后台执行——
     * 把 zxing 解码留在主线程会直接卡住交互，这是引入二维码识别最容易踩的坑。
     *
     * **结果分两种**：区域里解出二维码内容时走 [CaptureResult.QrCode]（这条内容要进的是
     * 文本链路，不落盘）；没解出才是原来的 [CaptureResult.Success]（落盘后进图片分享链路）。
     *
     * @param bounds 用户指认的区域（来自 [ScreenRegionPicker.pick]）
     * @param service 无障碍服务实例，持有截屏能力
     * @return 二维码内容，或落盘完成的图片载荷；失败原因见 [ImageCaptureError]
     */
    suspend fun captureRegion(
        context: Context,
        service: android.accessibilityservice.AccessibilityService,
        bounds: Rect
    ): CaptureResult {
        // 截屏必须主线程（takeScreenshot 的契约），这一步不能挪到后台
        val full = withContext(Dispatchers.Main) { ScreenshotCapturer.capture(service) }
        return when (full) {
            is ScreenshotCapturer.Result.Failure -> CaptureResult.Failure(full.error)
            is ScreenshotCapturer.Result.Success -> {
                val captured = full.bitmap
                withContext(Dispatchers.Default) {
                    // 全屏截图时跳过裁剪，直接复用原图，避免双份内存分配导致 OOM
                    val isFullScreen = bounds.left <= 0 && bounds.top <= 0 &&
                            bounds.right >= captured.width && bounds.bottom >= captured.height
                    val target = if (isFullScreen) {
                        captured
                    } else {
                        val cropped = crop(captured, bounds)
                        // 无论裁剪成功与否，全屏原图都不再需要：createBitmap 已拷贝过像素
                        captured.recycle()
                        if (cropped == null) {
                            return@withContext CaptureResult.Failure(ImageCaptureError.CropFailed)
                        }
                        cropped
                    }
                    try {
                        // 先解码再决定去向：解出二维码就不再落盘——这条内容要进文本链路，
                        // 图片分享链路根本用不到这张图
                        val content = QrCodeDecoder.decode(target)
                        if (content != null) {
                            android.util.Log.i(
                                TAG,
                                "屏幕区域解出二维码: ${target.width}x${target.height}, 内容长度=${content.length}"
                            )
                            CaptureResult.QrCode(content)
                        } else {
                            val saved = ImageCaptureSaver.save(context, target)
                            if (saved == null) {
                                CaptureResult.Failure(ImageCaptureError.IoFailure("落盘失败"))
                            } else {
                                CaptureResult.Success(saved)
                            }
                        }
                    } finally {
                        target.recycle()
                    }
                }
            }
        }
    }

    /** 按屏幕坐标裁剪 */
    private fun crop(full: Bitmap, bounds: Rect): Bitmap? {
        val left = bounds.left.coerceIn(0, full.width - 1)
        val top = bounds.top.coerceIn(0, full.height - 1)
        val right = bounds.right.coerceIn(left + 1, full.width)
        val bottom = bounds.bottom.coerceIn(top + 1, full.height)
        if (right - left <= 0 || bottom - top <= 0) return null
        return try {
            Bitmap.createBitmap(full, left, top, right - left, bottom - top)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "裁剪失败: ${e.message}")
            null
        }
    }

    /** 取图结果 */
    sealed interface CaptureResult {
        data class Success(val image: ClipPayload.Image) : CaptureResult

        /**
         * 区域里是二维码：内容已解出，去向是文本链路。
         *
         * 刻意不落盘：这条路径不需要图片文件，识别到即止，
         * 也顺带避免在临时目录里留下一张用户没见过、随后还会被 TTL 清掉的图。
         */
        data class QrCode(val content: String) : CaptureResult

        data class Failure(val error: ImageCaptureError) : CaptureResult
    }
}

/**
 * 屏幕取图的失败原因。
 *
 * 每种都必须给出**不同的用户文案**：系统版本不支持与"截得太频繁"是完全不同的处理方式，
 * 合并成"取图失败"会让用户无从下手。
 */
sealed interface ImageCaptureError {
    /** 系统版本低于 API 30，不具备无障碍截屏能力 */
    data object UnsupportedApi : ImageCaptureError

    /** 距上次截屏过近，被本地节流拦下 */
    data object Throttled : ImageCaptureError

    /** 系统拒绝本次截屏（多为频率限制） */
    data object Rejected : ImageCaptureError

    /** 裁剪结果为空 */
    data object CropFailed : ImageCaptureError

    /** 解码或落盘失败 */
    data class IoFailure(val detail: String?) : ImageCaptureError
}

/**
 * 无障碍截屏封装。
 *
 * 三条约束必须在此集中处理，不能散落到调用方：
 * 1. **API 30+**：`takeScreenshot` 在更低版本不存在，`minSdk=29` 必须显式分支；
 * 2. **频率限制**：系统对截屏有节流，连续调用会被拒，因此本地再加一层节流；
 * 3. **缓冲释放**：结果是 `HardwareBuffer` 支撑的 Bitmap，调用方必须 `recycle()`。
 *
 * 项目 `accessibility_service_config.xml` 已声明 `android:canTakeScreenshot="true"`，
 * 因此不需要任何运行时授权，也**不需要 MediaProjection**（后者每次都会弹系统确认框）。
 */
object ScreenshotCapturer {

    private const val TAG = "LinkGo_Screenshot"

    /** 本地节流间隔：系统限频通常比这更宽，这里给一个保守下限 */
    private const val MIN_INTERVAL_MS = 1500L

    /** 回调超时兜底：防止极端情况下永久阻塞主线程 */
    private const val CALLBACK_TIMEOUT_MS = 1500L

    @Volatile
    private var lastCaptureAt = 0L

    /**
     * 截屏回调的执行器。
     *
     * 用单个常驻线程而不是每次 `newSingleThreadExecutor()`：后者每取一次图就新建一个线程池，
     * 而 `takeScreenshot` 的回调不受我们控制、线程池也没有关停时机，反复调用会稳定泄漏线程。
     */
    private val callbackExecutor: java.util.concurrent.ExecutorService by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "LinkGo-ScreenshotCallback").apply { isDaemon = true }
        }
    }

    sealed interface Result {
        data class Success(val bitmap: Bitmap) : Result
        data class Failure(val error: ImageCaptureError) : Result
    }

    /**
     * 截取当前屏幕。
     *
     * 必须是主线程调用（`takeScreenshot` 的契约）。
     *
     * 说明：无障碍截屏**没有按窗口截取的变体**，只能整屏取回再裁剪；
     * 调用方传入的矩形只用于裁剪，不参与截屏调用。
     */
    fun capture(service: android.accessibilityservice.AccessibilityService): Result {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            return Result.Failure(ImageCaptureError.UnsupportedApi)
        }

        val now = System.currentTimeMillis()
        if (now - lastCaptureAt < MIN_INTERVAL_MS) {
            android.util.Log.d(TAG, "截屏被本地节流拦下：距上次 ${now - lastCaptureAt}ms")
            return Result.Failure(ImageCaptureError.Throttled)
        }
        lastCaptureAt = now

        var result: Result? = null
        // 同步等待回调：takeScreenshot 的回调在系统线程投递，用 CountDownLatch 收敛成同步返回，
        // 避免调用方为了一个值层层嵌套回调。
        val latch = java.util.concurrent.CountDownLatch(1)

        val callback = object : android.accessibilityservice.AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(screenshot: android.accessibilityservice.AccessibilityService.ScreenshotResult) {
                try {
                    // wrapHardwareBuffer 返回的是硬件位图，先复制到 ARGB_8888 再关缓冲：
                    // 硬件位图一旦 close 缓冲就不可再读，必须先落地成软件位图
                    val bitmap = android.graphics.Bitmap.wrapHardwareBuffer(
                        screenshot.hardwareBuffer,
                        screenshot.colorSpace
                    )?.copy(Bitmap.Config.ARGB_8888, false)
                    result = if (bitmap == null) {
                        Result.Failure(ImageCaptureError.IoFailure("位图转换失败"))
                    } else {
                        Result.Success(bitmap)
                    }
                } catch (e: Exception) {
                    result = Result.Failure(ImageCaptureError.IoFailure(e.message))
                } finally {
                    // hardwareBuffer 必须显式关闭，否则每次截屏泄漏一块图形缓冲
                    runCatching { screenshot.hardwareBuffer.close() }
                    latch.countDown()
                }
            }

            override fun onFailure(errorCode: Int) {
                android.util.Log.w(TAG, "系统拒绝截屏: errorCode=$errorCode")
                result = Result.Failure(ImageCaptureError.Rejected)
                latch.countDown()
            }
        }

        try {
            service.takeScreenshot(android.view.Display.DEFAULT_DISPLAY, callbackExecutor, callback)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "截屏调用异常: ${e.message}")
            return Result.Failure(ImageCaptureError.IoFailure(e.message))
        }

        val finished = try {
            latch.await(CALLBACK_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            android.util.Log.w(TAG, "截屏超时未回调")
            return Result.Failure(ImageCaptureError.Rejected)
        }
        return result ?: Result.Failure(ImageCaptureError.Rejected)
    }
}

/**
 * 屏幕截图落盘。
 *
 * 复用 [ImageStore] 的目录与 TTL 清理：屏幕取图与剪贴板图片是同一类临时资产，
 * 生命周期策略必须一致，否则会出现"剪贴板的图会被清理、屏幕取的图永远留着"。
 */
object ImageCaptureSaver {

    private const val TAG = "LinkGo_ScreenCapture"

    /**
     * 把裁剪后的位图写成缓存文件。
     *
     * 用 JPEG 质量 90：屏幕截图本身有压缩损失，再压太狠会让文字发糊；
     * 屏幕区域的尺寸通常远小于原图，因此这里不做降采样。
     */
    fun save(context: Context, bitmap: Bitmap): ClipPayload.Image? {
        val dir = java.io.File(context.applicationContext.filesDir, "clip_images")
        if (!dir.exists()) dir.mkdirs()
        val file = java.io.File(dir, "screen_${System.currentTimeMillis()}.jpg")

        return try {
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            ClipPayload.Image(
                file = file,
                mimeType = "image/jpeg",
                width = bitmap.width,
                height = bitmap.height,
                byteSize = file.length(),
                fingerprint = ImageStore.computeFingerprint(file),
                sourcePackage = null
            )
        } catch (e: Exception) {
            android.util.Log.w(TAG, "屏幕图片落盘失败: ${e.message}")
            file.delete()
            null
        }
    }

    /** 失败原因的简体中文文案，供各入口统一使用 */
    fun describeError(error: ImageCaptureError): String = when (error) {
        ImageCaptureError.UnsupportedApi -> "当前系统版本不支持屏幕取图（需 Android 11 及以上）"
        ImageCaptureError.Throttled -> "截屏过于频繁，请稍后再试"
        ImageCaptureError.Rejected -> "系统拒绝了本次截屏，请稍后再试"
        ImageCaptureError.CropFailed -> "无法确定图片范围"
        is ImageCaptureError.IoFailure -> "图片读取失败${error.detail?.let { "：$it" } ?: ""}"
    }
}
