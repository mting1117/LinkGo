package com.moting.linkgo.service

import android.os.RemoteException
import android.util.Log
import com.moting.linkgo.IClipboardCallback
import com.moting.linkgo.IClipboardMonitor
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * 剪贴板监听服务（复刻 ClipShare ClipboardListenerService）。
 *
 * 支持两种实例化方式：
 * 1. Root 模式：主进程直接 `ClipboardUserService()` 实例化，调用 `startListening()`
 * 2. Shizuku 模式：Shizuku 通过反射实例化，通过 AIDL 调用
 *
 * 检测方式：
 * 1. hiddenApi：通过 app_process 运行 DEX，使用反射直接注册系统级 IClipboard 监听
 * 2. logs：读取 logcat ClipboardService:E 日志作为 fallback
 */
open class ClipboardUserService : IClipboardMonitor.Stub() {

    companion object {
        private const val TAG = "ClipboardUserService"
    }

    private var process: Process? = null
    private var stopped: Boolean = false
    private var isRootMode: Boolean = false
    private var useHiddenApi: Boolean = false

    private fun buildCommandsWithSpace(commands: Array<String>): String {
        val res = Array(commands.size) { "" }
        for (i in commands.indices) {
            if (commands[i].contains(" ")) {
                res[i] = "'" + commands[i] + "'"
            } else {
                res[i] = commands[i]
            }
        }
        return res.joinToString(" ")
    }

    override fun startListening(
        callback: IClipboardCallback?,
        useRoot: Boolean,
        filePath: String?,
        useHiddenApi: Boolean
    ) {
        if (callback == null) {
            Log.w(TAG, "startListening: callback is null")
            return
        }
        if (stopped) {
            Log.w(TAG, "startListening: already stopped")
            return
        }
        isRootMode = useRoot
        this.useHiddenApi = useHiddenApi

        var success = false
        if (useHiddenApi && filePath != null) {
            success = tryRunProcess(callback, useRoot, filePath)
        } else {
            success = tryReadLogs(callback, useRoot)
        }
        if (!success) {
            Log.w(TAG, "startListening failed, useHiddenApi=$useHiddenApi")
        }
    }

    private fun tryRunProcess(
        callback: IClipboardCallback,
        useRoot: Boolean,
        filePath: String
    ): Boolean {
        val dirPath = File(filePath).parent ?: return false
        val hostPid = android.os.Process.myPid()
        val commands = arrayOf(
            "app_process",
            "-Djava.class.path=$filePath",
            dirPath!!,
            "com.moting.linkgo.clipboard.dex.ClipboardListener",
            hostPid.toString()
        )
        Log.w(TAG, "tryRunProcess: ${buildCommandsWithSpace(commands)}")
        return tryRunAndWait(callback, useRoot, commands, false)
    }

    private fun tryReadLogs(callback: IClipboardCallback, useRoot: Boolean): Boolean {
        val fmt = "yyyy-MM-dd HH:mm:ss.SSS"
        val timeStamp = SimpleDateFormat(fmt, Locale.US).format(Date())
        val commands = arrayOf("logcat", "-T", timeStamp, "ClipboardService:E", "*:S")
        Log.w(TAG, "tryReadLogs: ${buildCommandsWithSpace(commands)}")
        return tryRunAndWait(callback, useRoot, commands, true)
    }

    private fun tryRunAndWait(
        callback: IClipboardCallback,
        useRoot: Boolean,
        commands: Array<String>,
        useLog: Boolean
    ): Boolean {
        if (stopped) return true
        var reader: BufferedReader? = null
        var error = false
        try {
            val processBuilder: ProcessBuilder
            if (useRoot) {
                processBuilder = ProcessBuilder(arrayOf("su").toList())
                processBuilder.redirectErrorStream(true)
                process = processBuilder.start()
                val os = DataOutputStream(process!!.outputStream)
                os.writeBytes("${buildCommandsWithSpace(commands)}\n")
                os.flush()
            } else {
                processBuilder = ProcessBuilder(commands.toList())
                processBuilder.redirectErrorStream(true)
                process = processBuilder.start()
            }
            reader = BufferedReader(InputStreamReader(process!!.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                Log.w(TAG, line!!)
                if (useLog) {
                    // logcat mode: any line with "com.moting.linkgo" is a denial
                    if (line!!.contains("com.moting.linkgo")) {
                        try {
                            callback.onClipboardChanged("", System.currentTimeMillis())
                        } catch (e: RemoteException) {
                            Log.w(TAG, "callback error: ${e.message}")
                        }
                    }
                } else {
                    // hiddenApi mode: parse EventEnum events
                    if (line!!.startsWith("onChanged:")) {
                        try {
                            callback.onClipboardChanged("", System.currentTimeMillis())
                        } catch (e: RemoteException) {
                            Log.w(TAG, "callback error: ${e.message}")
                        }
                    }
                    if (line!!.startsWith("fatal:")) {
                        error = true
                        break
                    }
                    if (line!!.startsWith("eof:")) {
                        error = false
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "read error: ${e.message}")
            error = true
        } finally {
            reader?.close()
        }
        return error
    }

    override fun stopListening() {
        Log.w(TAG, "stopListening")
        stopped = true
        stopProcess()
    }

    private fun stopProcess() {
        try {
            if (useHiddenApi && process != null) {
                val os = DataOutputStream(process!!.outputStream)
                os.writeBytes("exit\n")
                os.flush()
            }
            val processTemp = process
            process = null
            processTemp?.outputStream?.close()
            processTemp?.inputStream?.close()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                processTemp?.destroyForcibly()
            } else {
                processTemp?.destroy()
            }
        } catch (_: Exception) {
        }
    }

    override fun destroy() {
        Log.w(TAG, "destroy")
        stopProcess()
        if (!isRootMode) {
            exitProcess(0)
        }
    }

    override fun exit() {
        destroy()
    }
}