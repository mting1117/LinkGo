package com.moting.linkgo.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.moting.linkgo.IClipboardCallback
import com.moting.linkgo.MainActivity
import com.moting.linkgo.R
import com.moting.linkgo.clipboard.ClipboardBackend
import com.moting.linkgo.clipboard.ClipboardClearer
import com.moting.linkgo.clipboard.ClipboardHandler
import com.moting.linkgo.image.ClipPayload
import com.moting.linkgo.image.ClipboardReader
import com.moting.linkgo.util.NotificationHelper
import com.moting.linkgo.util.SuperIslandLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference

/**
 * 剪贴板后台监听前台服务。
 *
 * 仅负责「检测通知 + 抢焦点读 + 前台持活」；分发逻辑统一交给 ClipboardHandler。
 * 支持 5 种需要前台服务的后端（Shizuku/Root × hidden_api/logs，以及不处理）。
 * LSPosed 后端不启动此服务，由 ClipboardTextReceiver 直接处理。
 */
class ClipboardMonitorService : Service() {

    companion object {
        private const val TAG = "ClipboardMonitor"
        private const val NOTIFICATION_ID = 2001
        const val CHANNEL_ID = NotificationHelper.CHANNEL_BACKGROUND_SERVICE
        private const val LISTENER_ZIP = "listener.zip"
        private const val COLD_START_WINDOW_MS = 5000L

        /** 无障碍保活心跳间隔：仅做内存判断，只有发现实例缺失才会真正触发自愈 */
        private const val A11Y_WATCHDOG_INTERVAL_MS = 5 * 60 * 1000L

        @Volatile
        var isRunning: Boolean = false
            private set

        /** 当前生效的后端：供常驻通知文案与状态刷新使用 */
        @Volatile
        private var activeBackend: String = ClipboardBackend.DEFAULT

        /**
         * 刷新常驻通知副标题（保留当前后端描述，并附带快捷手势引擎的健康状态）。
         * 由无障碍自愈巡检后调用，复用已在状态栏的通知作为健康展示位，不新增通知与唤醒。
         *
         * @param hint 自动恢复失败、需要用户手动处理时的提示，会覆盖健康状态文案
         */
        fun refreshNotification(hint: String? = null) {
            instance?.notifyForegroundFor(activeBackend, hint)
        }

        /** 当前 Service 实例引用：仅在存活期间赋值，供静态刷新入口使用 */
        @Volatile
        private var instance: ClipboardMonitorService? = null

        /** 上一次已提交的通知副标题：内容相同则跳过刷新，避免无意义重绘 */
        @Volatile
        private var lastNotifiedContent: String? = null

        fun start(context: Context, backend: String = ClipboardBackend.DEFAULT) {
            val intent = Intent(context, ClipboardMonitorService::class.java).apply {
                putExtra("backend", backend)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ClipboardMonitorService::class.java))
        }
    }

    // --- Clipboard ---
    private var cm: ClipboardManager? = null
    private var clipListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    // --- Listener Service (root mode) ---
    private var listenerService: ClipboardUserService? = null
    private var listenerThread: Thread? = null

    // --- Float Focus View ---
    private lateinit var windowManager: WindowManager
    private lateinit var mainParams: WindowManager.LayoutParams
    private lateinit var floatView: ViewGroup
    @Volatile
    private var isShowFloatView: Boolean = false
    private val viewLock = Any()
    private var lastChangedTime: Long = 0
    private val changedMinIntervalTime: Long = 200

    // --- 微防抖抑制：记录上一次真正处理剪贴板变化的时间与文本哈希，仅拦截机器级微抖动 ---
    private var lastHandledChangeTime: Long = 0
    private var lastHandledText: String? = null
    private val microDebounceIntervalMs: Long = 50
    private var hasColdStartCompensated: Boolean = false

    private val mainHandler = MyHandler(this)

    /**
     * 无障碍保活心跳：本服务常驻前台，用它承载一个极低成本的空闲巡检。
     * 只处理「服务实例已消失」这一确定状态（零跨进程开销），
     * 覆盖自愈触发点的场景空白——用户在其他应用里发现手势失效时，
     * 不会有任何 App 生命周期事件去触发自愈。
     */
    private val a11yWatchdog = object : Runnable {
        override fun run() {
            try {
                checkA11yAliveByWatchdog()
            } catch (e: Exception) {
                Log.w(TAG, "保活心跳异常: ${e.message}")
            } finally {
                mainHandler.postDelayed(this, A11Y_WATCHDOG_INTERVAL_MS)
            }
        }
    }

    /**
     * 心跳主体：全部为内存判断，健康态下零跨进程开销。
     * 仅在「手势已启用 + 能力开关开启 + 服务实例缺失 + 设备近期活跃」四项同时满足时才触发自愈；
     * 长时间无窗口事件（息屏/深度待机）直接跳过，避免无意义的唤醒。
     */
    private fun checkA11yAliveByWatchdog() {
        if (!com.moting.linkgo.data.SettingsCache.edgeGestureConfig.enabled) return
        if (com.google.android.accessibility.selecttospeak.SelectToSpeakService.getInstance() != null) return
        if (!com.moting.linkgo.util.privilege.CapabilityCenter.isEnabled(
                com.moting.linkgo.util.privilege.CapabilityCenter.Capability.A11Y_HEAL_APP
            )
        ) {
            return
        }
        if (!com.moting.linkgo.util.AccessibilityUtils.wasRecentlyActive()) {
            Log.i(TAG, "保活心跳：设备长期无窗口活动，跳过本轮检查")
            return
        }
        Log.w(TAG, "保活心跳：检测到无障碍服务实例缺失，触发一次自愈")
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { com.moting.linkgo.util.AccessibilityUtils.autoHealService(applicationContext) }
        }
    }

    class MyHandler(service: ClipboardMonitorService) : Handler(Looper.getMainLooper()) {
        private val mOuter: WeakReference<ClipboardMonitorService> = WeakReference(service)
        override fun handleMessage(msg: Message) {
            mOuter.get()?.let {
                Log.w(TAG, "onChanged from listener")
                synchronized(it.viewLock) {
                    it.showFloatFocusView()
                }
            }
        }
    }

    // 同步防抖判据。
    // 参数是载荷的同步可算去重键（文本内容 / 图片 URI），不是内容本身：
    // 图片的内容指纹要落盘后才知道，而落盘是异步的，拿不到参与这里的同步判重。
    // 真正的图片内容级微防抖由处理链路的指纹比对负责。
    @Synchronized
    private fun shouldHandleClipboardChange(dedupKey: String? = null): Boolean {
        // 主动清空剪贴板后，忽略随之产生的这一次变化，避免自身误触发
        if (ClipboardClearer.consumeIgnoreNextChange()) {
            Log.w(TAG, "Ignoring self-clear clipboard change")
            return false
        }
        val now = System.currentTimeMillis()
        if (dedupKey != null && dedupKey == lastHandledText && (now - lastHandledChangeTime) < microDebounceIntervalMs) {
            Log.w(TAG, "Suppressing micro-debounce callback for identical payload within ${microDebounceIntervalMs}ms")
            return false
        }
        return true
    }

    private fun markClipboardHandled(text: String?) {
        lastHandledChangeTime = System.currentTimeMillis()
        lastHandledText = text
    }

    // 读取剪贴板载荷：文本判据与重构前逐条一致，判据本身已下沉到 ClipboardReader，此处只做转发。
    // 图片只产出源元数据，不做解码与落盘（原因见 dispatchPayload）。
    private fun readClipPayload(clip: ClipData?): ClipPayload? = ClipboardReader.read(clip)

    // 统一分发入口：三条触发路径（公开监听器、Shizuku/Root 回调、冷启动补偿）都汇到这里，
    // 保证文本与图片走同一套去重与后续处理。
    private fun dispatchPayload(payload: ClipPayload) {
        when (payload) {
            is ClipPayload.Text -> {
                markClipboardHandled(payload.text)
                ClipboardHandler.handle(this, payload.text)
            }

            is ClipPayload.ImageSource -> {
                // 去重键用 URI 而非内容：内容指纹要落盘后才知道，而落盘是异步的，无法参与同步判重。
                // 真正的图片内容级微防抖由后续链路的指纹比对负责。
                markClipboardHandled(payload.uri?.toString())
                // 解码 + 落盘是重 IO，必须离开主线程：调用点在抢焦点的同步窗口内，
                // 阻塞会拉长 16x16 焦点窗的持有时间，直接损害后续跳转的流畅度。
                CoroutineScope(Dispatchers.IO).launch {
                    resolveAndDispatchImage(payload)
                }
            }

            is ClipPayload.Image -> Unit // 已解析态只在链路内部流转，不会出现在此
        }
    }

    // 解析图片源并交给统一链路。落盘后按内容指纹进入图片展示与跳转链路。
    private suspend fun resolveAndDispatchImage(source: ClipPayload.ImageSource) {
        val resolved = ClipboardReader.resolveImage(this, source)
        if (resolved == null) {
            // 内容不可读时不静默失败：留一条可供用户排查的痕，并给出可见提示
            Log.w(TAG, "[IMAGE-UNREADABLE] 剪贴板图片内容不可读: uri=${source.uri}")
            SuperIslandLogManager.log(this, "IMAGE_INGEST_FAILED", "剪贴板图片内容不可读: uri=${source.uri}")
            withContext(Dispatchers.Main) {
                com.moting.linkgo.util.InstantToastHelper.show(this@ClipboardMonitorService, "已复制图片（内容不可读）")
            }
            return
        }
        Log.i(TAG, "[IMAGE-READY] 剪贴板图片已就绪: ${resolved.width}x${resolved.height}, ${resolved.byteSize}B")
        SuperIslandLogManager.log(
            this, "CLIP_IMAGE_DETECTED",
            "剪贴板图片: ${resolved.width}x${resolved.height}, ${resolved.byteSize}B, ${resolved.mimeType}"
        )
        withContext(Dispatchers.Main) {
            ClipboardHandler.handleImage(this@ClipboardMonitorService, resolved)
        }
    }

    // 载荷的同步去重键：文本用内容，图片用 URI
    private fun ClipPayload.syncDedupKey(): String? = when (this) {
        is ClipPayload.Text -> text
        is ClipPayload.ImageSource -> uri?.toString()
        is ClipPayload.Image -> fingerprint
    }

    /**
     * AIDL callback: 当 Shizuku/Root 进程检测到剪贴板变化时回调
     */
    private val clipboardCallback = object : IClipboardCallback.Stub() {
        override fun onClipboardChanged(text: String, timestamp: Long) {
            Log.w(TAG, "Clipboard callback from listener: text len=${text.length}")
            if (shouldHandleClipboardChange()) {
                mainHandler.sendMessage(Message())
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        instance = this
        Log.w(TAG, "ClipboardMonitorService created")

        // Init float window
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        mainParams = WindowManager.LayoutParams(
            16, 16,
            type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.RGBA_8888
        )
        mainParams.gravity = Gravity.START or Gravity.TOP
        mainParams.x = 0
        mainParams.y = 0
        val layoutInflater = baseContext.getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        floatView = layoutInflater.inflate(R.layout.float_focus, null) as ViewGroup

        // Create notification
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        // 启动无障碍保活心跳（幂等：重复进入先移除旧回调）
        mainHandler.removeCallbacks(a11yWatchdog)
        mainHandler.postDelayed(a11yWatchdog, A11Y_WATCHDOG_INTERVAL_MS)

        // 注册公开剪贴板监听：前台直接命中；「不处理」后端也依赖它
        cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipListener = ClipboardManager.OnPrimaryClipChangedListener {
            Log.w(TAG, "Primary clip changed (public API)")
            try {
                val payload = readClipPayload(cm?.primaryClip)
                if (payload != null && shouldHandleClipboardChange(payload.syncDedupKey())) {
                    dispatchPayload(payload)
                }
            } catch (e: Exception) {
                Log.w(TAG, "公开监听器处理剪贴板变动失败: ${e.message}")
            }
        }
        cm?.addPrimaryClipChangedListener(clipListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.w(TAG, "onStartCommand")

        // START_STICKY 重建时 intent 为 null：必须还原用户真实选择的后端，
        // 否则会回落到默认（Shizuku+隐藏 API），与 Root / 系统日志方式错配。
        // 先取内存快照（Application 启动即已填充，无 IO、无主线程阻塞），再异步用真实配置纠正。
        val restoredFromIntent = intent?.getStringExtra("backend")
        val backend = restoredFromIntent ?: com.moting.linkgo.data.SettingsCache.clipboardMonitorBackend
        activeBackend = backend
        if (restoredFromIntent == null) {
            Log.w(TAG, "intent 缺失（系统重建），暂用快照后端: $backend")
            CoroutineScope(Dispatchers.IO).launch {
                val (enabled, real) = com.moting.linkgo.data.SettingsRepository(this@ClipboardMonitorService)
                    .currentClipboardMonitorConfig()
                if (real != backend) {
                    Log.w(TAG, "校正重建后端: 快照=$backend → 实际=$real")
                    activeBackend = real
                    notifyForegroundFor(real)
                }
                // 用户在服务被杀期间关闭了总开关：直接停服务，不再空转
                if (!enabled) {
                    Log.w(TAG, "重建后发现监听总开关已关闭，停止服务")
                    stopSelf()
                }
            }
        }
        val useRoot = ClipboardBackend.isRoot(backend)
        val useHiddenApi = ClipboardBackend.isHiddenApi(backend)
        Log.w(TAG, "backend=$backend, useRoot=$useRoot, useHiddenApi=$useHiddenApi")

        // Stop previous listener if any
        try {
            listenerService?.stopListening()
        } catch (_: Exception) {
        }
        listenerService = null
        listenerThread?.interrupt()
        listenerThread = null

        // 仅隐藏 API 需要 DEX 文件
        var effectiveUseHiddenApi = useHiddenApi
        val listenerZipPath = if (effectiveUseHiddenApi) copyAssetToExternalFiles(LISTENER_ZIP) else null
        if (effectiveUseHiddenApi && listenerZipPath == null) {
            Log.w(TAG, "Cannot copy listener.zip, hidden API will fall back to logs")
            effectiveUseHiddenApi = false
        }

        when {
            backend == ClipboardBackend.NONE -> {
                // 不处理：仅公开监听（已在 onCreate 注册），不做任何提权
                notifyForeground("LinkGo 剪贴板监听中", backendSummary(backend))
            }

            useRoot -> {
                // Root 模式：直接实例化（复刻 ClipShare）
                listenerService = ClipboardUserService()
                notifyForeground("LinkGo 剪贴板监听中", backendSummary(backend))
                startListeningInternal(listenerZipPath, useRoot = true, useHiddenApi = effectiveUseHiddenApi)
            }

            else -> {
                // Shizuku 模式：绑定独立进程 UserService
                ShizukuManager.setOnClipboardReceivedListener { _, _ ->
                    if (shouldHandleClipboardChange()) {
                        mainHandler.sendMessage(Message())
                    }
                }
                ShizukuManager.setOnServiceBoundListener {
                    if (!hasColdStartCompensated) {
                        hasColdStartCompensated = true
                        Log.i(TAG, "Shizuku BOUND: scheduling cold start clipboard compensation")
                        mainHandler.postDelayed({
                            performColdStartCompensation()
                        }, 50)
                    }
                }
                if (!hasColdStartCompensated && ShizukuManager.getState() == ShizukuManager.State.BOUND) {
                    hasColdStartCompensated = true
                    Log.i(TAG, "Shizuku already BOUND: scheduling cold start clipboard compensation")
                    mainHandler.postDelayed({
                        performColdStartCompensation()
                    }, 50)
                }
                ShizukuManager.bindAndStart(this, effectiveUseHiddenApi)
                notifyForeground("LinkGo 剪贴板监听中", backendSummary(backend))
            }
        }

        return START_STICKY
    }

    /**
     * 直接启动 ClipboardUserService（Root 模式，复刻 ClipShare）。
     */
    private fun startListeningInternal(listenerZipPath: String?, useRoot: Boolean, useHiddenApi: Boolean) {
        val service = listenerService ?: return

        listenerThread = Thread({
            try {
                Log.w(TAG, "startListening: useRoot=$useRoot, useHiddenApi=$useHiddenApi, path=$listenerZipPath")
                service.startListening(
                    clipboardCallback,
                    useRoot,
                    listenerZipPath ?: "",
                    useHiddenApi
                )
            } catch (e: Exception) {
                Log.w(TAG, "startListening error: ${e.message}")
            }
            Log.w(TAG, "startListening stopped")
            notifyForeground("LinkGo", "剪贴板监听已停止")
        }, "ClipboardListener").also {
            it.isDaemon = true
            it.start()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 从最近任务划掉 App 时，部分 ROM 不会执行 START_STICKY 重建。
     * 此处按当前配置主动重发一次启动，保证后台监听与同进程内的快捷手势引擎不被一起带走。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.w(TAG, "onTaskRemoved: 任务被移除，检查是否需要恢复")
        val appContext = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val (enabled, backend) = com.moting.linkgo.data.SettingsRepository(appContext)
                    .currentClipboardMonitorConfig()
                if (!enabled || !ClipboardBackend.needsForegroundService(backend)) {
                    Log.i(TAG, "onTaskRemoved: 监听已关闭或无需前台服务，不恢复")
                    return@launch
                }
                Log.i(TAG, "onTaskRemoved: 重新拉起监听服务 (backend=$backend)")
                try {
                    start(appContext, backend)
                } catch (e: Exception) {
                    // Android 12+ 后台启动前台服务可能被系统拦截，此时交由 START_STICKY 兜底
                    Log.w(TAG, "onTaskRemoved 拉起前台服务被系统拒绝: ${e.message}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "onTaskRemoved 恢复失败: ${e.message}")
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isRunning = false
        instance = null
        mainHandler.removeCallbacks(a11yWatchdog)
        Log.w(TAG, "ClipboardMonitorService destroyed")
        clipListener?.let { cm?.removePrimaryClipChangedListener(it) }

        // Stop listener
        val t = listenerThread
        listenerThread = null
        t?.interrupt()
        try {
            listenerService?.exit()
        } catch (_: Exception) {
        }
        listenerService = null

        // Unbind Shizuku
        ShizukuManager.setOnServiceBoundListener(null)
        ShizukuManager.unbindAndStop()
        hasColdStartCompensated = false

        removeFloatFocusView()
        ClipboardHandler.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    //region Float Focus View

    private fun showFloatFocusView() {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "canDrawOverlays: false")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastChangedTime < changedMinIntervalTime) {
            return
        }
        lastChangedTime = now

        if (!isShowFloatView) {
            try {
                windowManager.addView(floatView, mainParams)
                isShowFloatView = true
            } catch (e: Exception) {
                Log.w(TAG, "addView failed", e)
                return
            }
        }

        mainHandler.post {
            readClipboardAndDispatch()
            removeFloatFocusView()
        }
        mainHandler.postDelayed({
            removeFloatFocusView()
        }, 100)
    }

    @Synchronized
    private fun removeFloatFocusView() {
        try {
            if (isShowFloatView) {
                windowManager.removeViewImmediate(floatView)
            }
        } catch (_: Exception) {
        } finally {
            isShowFloatView = false
        }
    }

    private fun readClipboardAndDispatch() {
        try {
            val payload = readClipPayload(cm?.primaryClip)
            if (payload != null && shouldHandleClipboardChange(payload.syncDedupKey())) {
                Log.i(TAG, "[CLIP-PAYLOAD] 焦点提权读取到载荷: ${ClipboardReader.describe(payload)}")
                dispatchPayload(payload)
            }
        } catch (e: Exception) {
            Log.w(TAG, "readClipboardAndDispatch failed: ${e.message}")
        }
    }

    private fun performColdStartCompensation() {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "performColdStartCompensation: cannot draw overlays")
            return
        }
        synchronized(viewLock) {
            if (!isShowFloatView) {
                try {
                    windowManager.addView(floatView, mainParams)
                    isShowFloatView = true
                } catch (e: Exception) {
                    Log.w(TAG, "addView failed for cold start compensation", e)
                    return
                }
            }
            mainHandler.postDelayed({
                try {
                    checkAndDispatchColdStartClip()
                } finally {
                    synchronized(viewLock) {
                        removeFloatFocusView()
                    }
                }
            }, 50)
            mainHandler.postDelayed({
                synchronized(viewLock) {
                    removeFloatFocusView()
                }
            }, 150)
        }
    }

    private fun checkAndDispatchColdStartClip() {
        try {
            val clip = cm?.primaryClip ?: run {
                Log.w(TAG, "Cold start check: primaryClip is null")
                return
            }
            val payload = readClipPayload(clip) ?: run {
                Log.w(TAG, "Cold start check: no dispatchable payload in clip")
                return
            }

            val timestamp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                clip.description?.timestamp ?: 0L
            } else {
                0L
            }
            val now = System.currentTimeMillis()
            val ageMs = if (timestamp > 0) now - timestamp else Long.MAX_VALUE

            Log.i(TAG, "Cold start clip check: timestamp=$timestamp, age=${ageMs}ms, payload=${ClipboardReader.describe(payload)}")

            if (ageMs in -1000..COLD_START_WINDOW_MS) {
                if (shouldHandleClipboardChange(payload.syncDedupKey())) {
                    Log.w(TAG, "Cold start clip compensated (age: ${ageMs}ms <= ${COLD_START_WINDOW_MS}ms)")
                    dispatchPayload(payload)
                }
            } else {
                Log.i(TAG, "Cold start clip ignored (historical content, age: ${ageMs}ms)")
                // 历史内容仅记录指纹基准，防止后续重复触发，坚决不弹出胶囊打扰用户
                markClipboardHandled(payload.syncDedupKey())
            }
        } catch (e: Exception) {
            Log.w(TAG, "checkAndDispatchColdStartClip failed: ${e.message}")
        }
    }

    //endregion

    //region Asset Copy

    private fun copyAssetToExternalFiles(fileName: String): String? {
        val destFile = File(getExternalFilesDir(null), fileName)
        if (destFile.exists()) {
            Log.w(TAG, "$fileName already exists at ${destFile.absolutePath}")
            return destFile.absolutePath
        }
        try {
            assets.open(fileName).use { input ->
                java.io.FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.w(TAG, "Copied $fileName to ${destFile.absolutePath}")
            return destFile.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "Failed to copy $fileName: ${e.message}")
            return null
        }
    }

    //endregion

    //region Notification

    private fun createNotificationChannel() {
        NotificationHelper.ensureChannels(this)
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LinkGo 剪贴板监听中")
            .setContentText("等待启动...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    /**
     * 刷新常驻通知；当存在自愈失败提示时，把点击目标改为无障碍设置页，
     * 让用户一次点击即可完成「关闭再打开」开关的手动恢复，无需逐层翻菜单。
     */
    private fun notifyForeground(title: String, content: String, openA11ySettings: Boolean = false) {
        val target = if (openA11ySettings) {
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        } else {
            Intent(this, MainActivity::class.java)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            target,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val updatedBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setOngoing(true)
            .setSilent(true)
            .setContentText(content)
            .setContentIntent(pendingIntent)
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, updatedBuilder.build())
    }

    /**
     * 按后端刷新常驻通知，副标题附带快捷手势引擎的健康状态。
     * 这条通知本就常驻状态栏，用它承载健康信息不新增通知、不新增唤醒。
     * 内容未变化时跳过刷新，避免每次切前台都触发无意义的重绘。
     *
     * @param hint 自动恢复失败时的用户指引，优先于健康状态展示（例如需去系统设置重开无障碍开关）
     */
    private fun notifyForegroundFor(backend: String, hint: String? = null) {
        val content = if (hint != null) "${backendSummary(backend)} · $hint" else backendSummary(backend)
        if (content == lastNotifiedContent) return
        lastNotifiedContent = content
        // 有提示时说明需要用户手动介入，点击直达无障碍设置页
        notifyForeground("LinkGo 剪贴板监听中", content, openA11ySettings = hint != null)
    }

    /** 组装副标题：后端描述 + 快捷手势服务实况 */
    private fun backendSummary(backend: String): String {
        val backendText = when (backend) {
            ClipboardBackend.NONE -> "公开监听模式"
            ClipboardBackend.ROOT_LOGS -> "系统日志 (Root) 运行中"
            ClipboardBackend.SHIZUKU_LOGS -> "系统日志 (Shizuku) 运行中"
            ClipboardBackend.ROOT_HIDDEN_API -> "Root 模式运行中"
            else -> "Shizuku 模式运行中"
        }
        return "$backendText · ${gestureHealthSummary()}"
    }

    /**
     * 快捷手势引擎实况：手势未启用时无需报告；启用后如实反映无障碍服务的可达性。
     * 实例缺失/假死都应让用户看到「未就绪」，而不是通知一直显示运行正常。
     */
    private fun gestureHealthSummary(): String {
        if (!com.moting.linkgo.data.SettingsCache.edgeGestureConfig.enabled) return "快捷手势已关闭"
        val service = com.google.android.accessibility.selecttospeak.SelectToSpeakService.getInstance()
            ?: return "快捷手势未就绪"
        return if (service.isActuallyAlive()) "快捷手势已就绪" else "快捷手势未就绪"
    }

    //endregion
}