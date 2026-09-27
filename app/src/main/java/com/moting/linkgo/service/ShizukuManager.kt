package com.moting.linkgo.service

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.moting.linkgo.IClipboardCallback
import com.moting.linkgo.IClipboardMonitor
import rikka.shizuku.Shizuku
import java.io.File

/**
 * Shizuku 连接管理器（复刻 ClipShare）。
 *
 * 作为 Root 模式的 fallback。当设备没有 root 但有 Shizuku 时使用。
 */
object ShizukuManager {

    private const val TAG = "ShizukuManager"
    private const val LISTENER_ZIP = "listener.zip"
    private const val REQUEST_CODE_PERMISSION_CENTER = 2001

    enum class State {
        UNAVAILABLE, PERMISSION_DENIED, READY, BINDING, BOUND, ERROR
    }

    private var currentState = State.UNAVAILABLE
    private var userServiceArgs: Shizuku.UserServiceArgs? = null
    private var monitorProxy: IClipboardMonitor? = null
    private var serviceConnection: ServiceConnection? = null
    private var onClipboardReceived: ((String, Long) -> Unit)? = null
    private var stateListener: ((State) -> Unit)? = null
    private var listenerThread: Thread? = null
    private var useHiddenApi: Boolean = true

    // 绑定延迟回调持有引用：unbind 时移除，避免匿名 Handler+Runnable 无法取消导致泄漏
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var bindDelayRunnable: Runnable? = null

    @Volatile
    private var isExplicitUnbound: Boolean = false
    private var rebindRunnable: Runnable? = null
    private var rebindAttempts: Int = 0
    private var lastRebindTime: Long = 0L

    fun isGranted(): Boolean {
        return runCatching {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    /**
     * 发起 Shizuku 授权请求（结果由 requestPermissionListener 回调驱动）。
     * 供权限中心统一调度使用。
     */
    fun requestShizukuPermission(): Boolean {
        return runCatching {
            if (!Shizuku.pingBinder()) {
                Log.w(TAG, "Shizuku 服务未运行，无法请求授权")
                return false
            }
            if (isGranted()) return true
            Shizuku.requestPermission(REQUEST_CODE_PERMISSION_CENTER)
            true
        }.getOrDefault(false)
    }

    /**
     * 通过 Shizuku 执行 Shell 命令
     */
    fun execShell(command: String): Boolean {
        if (!isGranted()) return false
        return runCatching {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as? Process
            process?.waitFor() == 0
        }.getOrDefault(false)
    }

    /**
     * 通过 Shizuku 执行 Shell 命令并返回 stdout（供诊断类命令读取输出）。
     * @return 命令成功(exit 0)时的输出文本（trim），失败或未授权返回 null。
     */
    fun execShellWithOutput(command: String): String? {
        if (!isGranted()) return null
        return runCatching {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as? Process
                ?: return null
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val ok = process.waitFor() == 0
            if (ok) output.trim() else null
        }.getOrNull()
    }

    private var onBinderReadyCallback: (() -> Unit)? = null
    private var onServiceBound: (() -> Unit)? = null

    fun setOnBinderReadyListener(listener: (() -> Unit)?) {
        this.onBinderReadyCallback = listener
    }

    fun setOnServiceBoundListener(listener: (() -> Unit)?) {
        this.onServiceBound = listener
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder died")
        updateState(State.UNAVAILABLE)
        monitorProxy = null
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.w(TAG, "Shizuku binder received, version=${Shizuku.getVersion()}")
        if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            updateState(State.READY)
            onBinderReadyCallback?.invoke()
        } else {
            updateState(State.PERMISSION_DENIED)
        }
    }

    private val requestPermissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Shizuku permission granted")
            updateState(State.READY)
            onBinderReadyCallback?.invoke()
        } else {
            Log.w(TAG, "Shizuku permission denied")
            updateState(State.PERMISSION_DENIED)
        }
    }

    private val clipboardCallback = object : IClipboardCallback.Stub() {
        override fun onClipboardChanged(text: String, timestamp: Long) {
            Log.w(TAG, "Clipboard callback from Shizuku: text len=${text.length}")
            onClipboardReceived?.invoke(text, timestamp)
        }
    }

    fun init(context: Context) {
        Log.w(TAG, "Initializing ShizukuManager")
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(requestPermissionListener)

        userServiceArgs = Shizuku.UserServiceArgs(
            ComponentName(context, ClipboardUserService::class.java)
        )
            .daemon(false)
            .processNameSuffix("clipboard")
            .tag("clipboard_monitor")

        Log.w(TAG, "ShizukuManager initialized, pingBinder=${Shizuku.pingBinder()}")
    }

    fun setOnClipboardReceivedListener(listener: (String, Long) -> Unit) {
        this.onClipboardReceived = listener
    }

    fun setStateListener(listener: (State) -> Unit) {
        this.stateListener = listener
        listener(currentState)
    }

    fun getState(): State = currentState

    fun bindAndStart(context: Context, useHiddenApi: Boolean = true) {
        this.useHiddenApi = useHiddenApi
        isExplicitUnbound = false
        rebindRunnable?.let { mainHandler.removeCallbacks(it) }
        rebindRunnable = null

        if (currentState == State.BOUND) {
            Log.w(TAG, "Already bound")
            return
        }

        if (!Shizuku.pingBinder()) {
            Log.w(TAG, "Shizuku not running")
            updateState(State.UNAVAILABLE)
            return
        }

        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Requesting Shizuku permission")
            Shizuku.requestPermission(0)
            return
        }

        val args = userServiceArgs ?: run {
            Log.w(TAG, "UserServiceArgs was null, auto-initializing...")
            init(context.applicationContext)
            userServiceArgs ?: run {
                Log.w(TAG, "UserServiceArgs initialize failed")
                return
            }
        }

        updateState(State.BINDING)

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder == null) {
                    Log.w(TAG, "onServiceConnected: binder is null")
                    updateState(State.ERROR)
                    return
                }
                Log.w(TAG, "UserService connected, starting clipboard listener")
                rebindAttempts = 0
                monitorProxy = IClipboardMonitor.Stub.asInterface(binder)
                // 注意：startListening 是同步阻塞的跨进程调用（Shizuku 进程内部是
                // readLine 死循环，永不返回），必须放到后台线程，绝不能在主线程调用，
                // 否则会阻塞主线程导致 ANR。
                listenerThread?.interrupt()
                listenerThread = Thread({
                    try {
                        startListeningInternal(context)
                        Log.w(TAG, "Clipboard monitoring started in Shizuku process")
                    } catch (e: RemoteException) {
                        Log.w(TAG, "Failed to start: ${e.message}")
                    }
                }, "ShizukuClipboardListener").also {
                    it.isDaemon = true
                    it.start()
                }
                updateState(State.BOUND)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Log.w(TAG, "UserService disconnected")
                monitorProxy = null
                updateState(State.READY)
                scheduleAutoRebind(context.applicationContext)
            }

            override fun onBindingDied(name: ComponentName?) {
                Log.w(TAG, "UserService binding died")
                monitorProxy = null
                updateState(State.ERROR)
                scheduleAutoRebind(context.applicationContext)
            }
        }

        serviceConnection = conn

        // 500ms delay like ClipShare reference（持有 Runnable 引用，unbind 时可移除，避免泄漏）
        bindDelayRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            bindDelayRunnable = null
            try {
                Shizuku.bindUserService(args, conn)
                Log.w(TAG, "bindUserService called")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to bind UserService: ${e.message}")
                updateState(State.ERROR)
            }
        }
        bindDelayRunnable = runnable
        mainHandler.postDelayed(runnable, 500)
    }

    private fun scheduleAutoRebind(appContext: Context) {
        if (isExplicitUnbound) return
        if (!ClipboardMonitorService.isRunning) return
        if (!com.moting.linkgo.data.SettingsCache.clipboardMonitorEnabled) return

        val now = System.currentTimeMillis()
        if (now - lastRebindTime > 30_000L) {
            rebindAttempts = 0
        }
        lastRebindTime = now
        if (rebindAttempts >= 5) {
            Log.w(TAG, "UserService 重连过于频繁（30秒内超过5次），暂停自动重连，等待下一次事件触发")
            return
        }
        rebindAttempts++

        rebindRunnable?.let { mainHandler.removeCallbacks(it) }
        val delay = (rebindAttempts * 1000L).coerceAtMost(5000L)
        Log.i(TAG, "安排 UserService 自动重连 (尝试次数: $rebindAttempts, 延时: ${delay}ms)")
        val r = Runnable {
            rebindRunnable = null
            if (!isExplicitUnbound && ClipboardMonitorService.isRunning) {
                Log.i(TAG, "执行 UserService 自动重连...")
                bindAndStart(appContext, useHiddenApi)
            }
        }
        rebindRunnable = r
        mainHandler.postDelayed(r, delay)
    }

    private fun startListeningInternal(context: Context) {
        val proxy = monitorProxy ?: return
        val listenerZipPath = getListenerZipPath(context)

        try {
            proxy.startListening(
                clipboardCallback,
                false, // useRoot = false (Shizuku mode)
                listenerZipPath ?: "",
                useHiddenApi
            )
        } catch (e: RemoteException) {
            Log.w(TAG, "startListening error: ${e.message}")
        }
    }

    private fun getListenerZipPath(context: Context): String? {
        val file = File(context.getExternalFilesDir(null), LISTENER_ZIP)
        return if (file.exists()) file.absolutePath else null
    }

    fun unbindAndStop() {
        isExplicitUnbound = true
        rebindRunnable?.let { mainHandler.removeCallbacks(it) }
        rebindRunnable = null
        rebindAttempts = 0

        val proxy = monitorProxy
        val conn = serviceConnection

        listenerThread?.interrupt()
        listenerThread = null

        // 移除尚未执行的延迟绑定回调，避免回调在解绑后仍然触发绑定
        bindDelayRunnable?.let { mainHandler.removeCallbacks(it) }
        bindDelayRunnable = null

        if (proxy != null) {
            try {
                proxy.stopListening()
            } catch (_: RemoteException) {
            }
        }
        monitorProxy = null

        if (conn != null && userServiceArgs != null) {
            try {
                Shizuku.unbindUserService(userServiceArgs!!, conn, true)
            } catch (_: Exception) {
            }
        }
        serviceConnection = null
        updateState(State.READY)
    }

    fun cleanup() {
        unbindAndStop()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(requestPermissionListener)
        onClipboardReceived = null
        onServiceBound = null
        stateListener = null
    }

    private fun updateState(newState: State) {
        if (currentState != newState) {
            Log.w(TAG, "State: $currentState -> $newState")
            currentState = newState
            stateListener?.invoke(newState)
            if (newState == State.BOUND) {
                onServiceBound?.invoke()
            }
        }
    }
}