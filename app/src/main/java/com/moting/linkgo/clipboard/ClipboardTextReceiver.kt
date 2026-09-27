package com.moting.linkgo.clipboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.moting.linkgo.data.SettingsCache
import com.moting.linkgo.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.coroutines.resume

/**
 * LSPosed 后端专用广播接收器。
 *
 * 由 system_server 中的 HookEntry 在截获剪贴板写入后发出广播，
 * 此接收器校验当前后端为 LSPosed 后，把文本交给 ClipboardHandler 处理。
 * 该后端不需要前台服务，进程可被广播随时拉起。
 */
class ClipboardTextReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "LinkGo_LSP"

        /**
         * 把剪贴板图片载荷交由统一图片链路。
         *
         * 通道 1（首选）：在 Hook isDefaultIme 赋予的白名单特权下，直接通过 ClipboardManager 读取 PrimaryClip，
         * 系统会自动为 LinkGo 授予私有图片 URI 的临时访问权限（对齐 clipboardwhitelist 原生架构）。
         * 通道 2：若通道 1 尚未拿到，尝试通过广播传递的 URI 解析落盘。
         * 通道 3：兼容直接传递的本地文件路径。
         * 通道 4：若处于 LSPosed 模块刚更新尚未重启生效的过渡阶段，尝试通过 1x1 悬浮窗抢占焦点兜底读取。
         */
        private suspend fun dispatchImage(
            context: Context,
            path: String?,
            uriString: String?,
            intent: Intent,
            sourcePkg: String?,
            timestamp: Long
        ) {
            val mimeType = intent.getStringExtra(ClipboardHookContract.EXTRA_CLIPBOARD_IMAGE_MIME) ?: "image/*"
            var imagePayload: com.moting.linkgo.image.ClipPayload.Image? = null

            // 通道 1：官方原生特权读取（在 isDefaultIme 白名单特权加持下，后台直接通过 ClipboardManager 获取并由系统自动授权）
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            try {
                val clip = cm?.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val payload = com.moting.linkgo.image.ClipboardReader.read(clip)
                    if (payload is com.moting.linkgo.image.ClipPayload.ImageSource) {
                        Log.i(TAG, "[LSP-RCV-IMAGE-IME] 通过 isDefaultIme 特权成功获取剪贴板图片 URI: ${payload.uri}")
                        imagePayload = kotlinx.coroutines.withContext(Dispatchers.IO) {
                            com.moting.linkgo.image.ImageStore.ingest(
                                context = context,
                                sourceUri = payload.uri,
                                declaredPath = payload.declaredPath,
                                mimeType = payload.mimeType,
                                sourcePackage = sourcePkg,
                                timestamp = timestamp
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "[LSP-RCV-CLIPBOARD-FAIL] 直接从 ClipboardManager 获取剪贴板失败: ${e.message}")
            }

            // 通道 2：如果通道 1 尚未拿到，尝试通过广播传递的 URI 摄入
            if (imagePayload == null && !uriString.isNullOrBlank()) {
                val uri = android.net.Uri.parse(uriString)
                imagePayload = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    com.moting.linkgo.image.ImageStore.ingest(
                        context = context,
                        sourceUri = uri,
                        declaredPath = null,
                        mimeType = mimeType,
                        sourcePackage = sourcePkg,
                        timestamp = timestamp
                    )
                }
            }

            // 通道 3：兼容本地文件路径
            if (imagePayload == null && !path.isNullOrBlank()) {
                val file = java.io.File(path)
                if (file.isFile && file.length() > 0L) {
                    imagePayload = com.moting.linkgo.image.ClipPayload.Image(
                        file = file,
                        mimeType = mimeType,
                        width = intent.getIntExtra(ClipboardHookContract.EXTRA_CLIPBOARD_IMAGE_WIDTH, 0),
                        height = intent.getIntExtra(ClipboardHookContract.EXTRA_CLIPBOARD_IMAGE_HEIGHT, 0),
                        byteSize = file.length(),
                        fingerprint = com.moting.linkgo.image.ImageStore.computeFingerprint(file),
                        sourcePackage = sourcePkg?.takeIf { it.isNotBlank() },
                        timestamp = timestamp
                    )
                }
            }

            // 通道 4：悬浮窗焦点提权兜底（应对 LSPosed 刚更新尚未重启 system_server 的过渡状态）
            if (imagePayload == null && android.provider.Settings.canDrawOverlays(context)) {
                try {
                    imagePayload = stealFocusAndReadImage(context, sourcePkg, timestamp)
                } catch (e: Exception) {
                    Log.w(TAG, "[LSP-RCV-FOCUS-STEAL-FAIL] 悬浮窗焦点提权兜底失败: ${e.message}")
                }
            }

            if (imagePayload != null) {
                Log.i(TAG, "[LSP-RCV-IMAGE] 图片载荷就绪: ${imagePayload.width}x${imagePayload.height}, ${imagePayload.byteSize}B")
                ClipboardHandler.handleImage(context, imagePayload)
            } else {
                Log.w(TAG, "[LSP-RCV-IMAGE-FAILED] 图片载荷摄入失败(URI=$uriString, path=$path)")
                android.widget.Toast.makeText(context, "已复制图片（内容不可读）", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        /**
         * 悬浮窗焦点提权兜底读取剪贴板（仅在 isDefaultIme 未生效且具有悬浮窗权限时触发）：
         * 添加一个 1x1 透明窗口临时抢占焦点，读取 cm.primaryClip 后立即移除。
         */
        private suspend fun stealFocusAndReadImage(
            context: Context,
            sourcePkg: String?,
            timestamp: Long
        ): com.moting.linkgo.image.ClipPayload.Image? = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
            if (wm == null) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            val view = android.view.View(context)
            val params = android.view.WindowManager.LayoutParams().apply {
                type = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    android.view.WindowManager.LayoutParams.TYPE_PHONE
                }
                flags = android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                width = 1
                height = 1
                alpha = 0f
                gravity = android.view.Gravity.START or android.view.Gravity.TOP
            }
            try {
                wm.addView(view, params)
                cont.invokeOnCancellation {
                    try {
                        wm.removeViewImmediate(view)
                    } catch (_: Throwable) {}
                }
                view.postDelayed({
                    try {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                        val clip = cm?.primaryClip
                        val payload = com.moting.linkgo.image.ClipboardReader.read(clip)
                        if (payload is com.moting.linkgo.image.ClipPayload.ImageSource) {
                            val img = com.moting.linkgo.image.ImageStore.ingest(
                                context = context,
                                sourceUri = payload.uri,
                                declaredPath = payload.declaredPath,
                                mimeType = payload.mimeType,
                                sourcePackage = sourcePkg,
                                timestamp = timestamp
                            )
                            if (cont.isActive) cont.resume(img)
                        } else {
                            if (cont.isActive) cont.resume(null)
                        }
                    } catch (t: Throwable) {
                        if (cont.isActive) cont.resume(null)
                    } finally {
                        try {
                            wm.removeViewImmediate(view)
                        } catch (_: Throwable) {}
                    }
                }, 60L)
            } catch (t: Throwable) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "[LSP-RCV-ENTRY] 收到广播: action=$action, pid=${android.os.Process.myPid()}, thread=${Thread.currentThread().name}")

        if (action != ClipboardHookContract.ACTION_HANDLE_CLIPBOARD_TEXT) {
            Log.w(TAG, "[LSP-RCV-IGNORE] 忽略非预期广播 action=$action")
            return
        }

        // 冷启动瞬间提升线程调度优先级至前台交互级别，确保毫秒级抢占 CPU 执行
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
        } catch (_: Throwable) {
        }

        val text = intent.getStringExtra(ClipboardHookContract.EXTRA_CLIPBOARD_TEXT)
        val imagePath = intent.getStringExtra(ClipboardHookContract.EXTRA_CLIPBOARD_IMAGE_PATH)
        val imageUriString = intent.getStringExtra(ClipboardHookContract.EXTRA_CLIPBOARD_IMAGE_URI)
            ?: intent.dataString
        val hasSharedMemory = intent.hasExtra(ClipboardHookContract.EXTRA_CLIPBOARD_IMAGE_SHARED_MEMORY)
        val isExplicitImage = intent.getBooleanExtra(ClipboardHookContract.EXTRA_IS_IMAGE, false)
        val sourcePkg = intent.getStringExtra(ClipboardHookContract.EXTRA_CLIPBOARD_SOURCE)
        val timestamp = intent.getLongExtra("clipboard_timestamp", 0L)

        // 优先使用明确的图片标识；或在无文本时若包含 URI/文件路径也判定为图片
        val isImage = isExplicitImage || (text.isNullOrBlank() && (!imagePath.isNullOrBlank() || !imageUriString.isNullOrBlank() || hasSharedMemory))
        if (!isImage && text.isNullOrBlank()) {
            Log.w(TAG, "[LSP-RCV-EMPTY] 广播内既无文本也无图片，放弃处理")
            return
        }

        val now = System.currentTimeMillis()
        if (timestamp > 0 && (now - timestamp) > 8000L) {
            Log.w(TAG, "[LSP-RCV-STALE] 广播在系统队列或冻结中积压已超过8秒(age=${now - timestamp}ms)，放弃陈旧广播")
            return
        }

        Log.i(
            TAG,
            "[LSP-RCV-DATA] 成功解析广播数据: " + if (isImage) {
                "图片 uri=$imageUriString, shm=$hasSharedMemory, path=$imagePath, sourcePkg=$sourcePkg"
            } else {
                "文本 len=${text!!.length}, prefix=${text.take(30)}, sourcePkg=$sourcePkg"
            } + ", age=${if (timestamp > 0) now - timestamp else -1}ms"
        )

        // 申请最长 3 秒短时 WakeLock，防止冷启动瞬间被系统电源管理冻结
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = runCatching {
            pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LinkGo:LspColdStartWakeLock")?.apply {
                setReferenceCounted(false)
                acquire(3000L)
            }
        }.getOrNull()

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                // 确保 0ms 快照已填充
                SettingsRepository.fastSyncCache(context.applicationContext)
                if (!SettingsCache.isLoaded) {
                    val repo = SettingsRepository(context.applicationContext)
                    repo.preloadAll()
                }
                
                // 直接走内存快照，0ms 零等待
                val isEnabled = SettingsCache.clipboardMonitorEnabled
                val backend = SettingsCache.clipboardMonitorBackend

                Log.i(TAG, "[LSP-RCV-CHECK] 配置检查 (快照): isEnabled=$isEnabled, backend=$backend")

                if (!isEnabled) {
                    Log.w(TAG, "[LSP-RCV-DISABLED] 剪贴板后台监听总开关未开启，跳过胶囊展示")
                    return@launch
                }

                if (backend != ClipboardBackend.LSPOSED) {
                    Log.w(TAG, "[LSP-RCV-MODE-MISMATCH] 当前监听后端非 LSPosed (当前为 $backend)，跳过")
                    return@launch
                }

                // 立即极速分发挂载胶囊
                Log.i(TAG, "[LSP-RCV-DISPATCH] 配置匹配成功，进入直接处理分发...")
                if (isImage) {
                    dispatchImage(context, imagePath, imageUriString, intent, sourcePkg, timestamp)
                } else {
                    ClipboardHandler.handleDirect(context, text!!)
                }
                Log.i(TAG, "[LSP-RCV-DONE] 剪贴板处理流程已成功完成挂载")

                // 顺便静默守护无障碍服务（若被强停或掉线则立即自愈）
                // 受能力开关「自愈·应用内自愈」约束：关闭后即使 LSPosed 广播拉起进程也不再自动点亮无障碍
                if (com.moting.linkgo.util.privilege.CapabilityCenter.isEnabled(
                        com.moting.linkgo.util.privilege.CapabilityCenter.Capability.A11Y_HEAL_APP
                    )
                ) {
                    com.moting.linkgo.util.AccessibilityUtils.autoHealService(context)
                }
            } catch (e: Exception) {
                Log.e(TAG, "[LSP-RCV-ERROR] 广播处理协程发生异常: ${e.message}", e)
            } finally {
                try {
                    wakeLock?.release()
                } catch (_: Exception) {}
                pendingResult.finish()
                Log.d(TAG, "[LSP-RCV-FINISH] pendingResult.finish() 已调用")
            }
        }
    }
}
