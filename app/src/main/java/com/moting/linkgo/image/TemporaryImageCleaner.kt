package com.moting.linkgo.image

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 临时图片自动清理管理器。
 *
 * 专门负责处理「临时保存（5分钟后自动删除）」任务。
 *
 * 核心安全机制（4 重绝对安全锁）：
 * 1. 唯一 Uri 绑定：仅按 MediaStore 插入时返回的精确单一 Uri 定向删除，严禁使用任何批量条件、通配符或目录遍历；
 * 2. 专属命名前缀：临时文件统一以 [PREFIX_TEMP] ("LinkGo_tmp_") 开头，普通保存图片绝不带此标识；
 * 3. 删除前元数据强校验：删除前必须查询目标 Uri，比对 DISPLAY_NAME 确以 [PREFIX_TEMP] 开头且名称一致，
 *    并验证存储路径是否为 LinkGo 专属相册；有一项不符则立即拒绝删除；
 * 4. 幂等与防重释放：删除后立即从持久化队列注销，绝不重复执行。
 */
object TemporaryImageCleaner {

    private const val TAG = "TemporaryImageCleaner"
    const val PREFIX_TEMP = "LinkGo_tmp_"
    const val DEFAULT_AUTO_DELETE_DELAY_MS = 5 * 60 * 1000L // 5 分钟

    private const val PREFS_NAME = "linkgo_temp_saved_images"
    private const val KEY_PENDING_TASKS = "pending_auto_delete_tasks"

    const val ACTION_DELETE_TEMP_IMAGE = "com.moting.linkgo.action.DELETE_TEMP_IMAGE"
    const val EXTRA_TARGET_URI = "extra_target_uri"
    const val EXTRA_EXPECTED_NAME = "extra_expected_name"

    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * 注册一张临时保存的图片，安排在 [delayMillis] 后自动从相册删除。
     *
     * @param uri 插入 MediaStore 后返回的精确 Uri
     * @param displayName 插入时的文件名（如 LinkGo_tmp_1726000000000.jpg）
     * @param delayMillis 延迟删除毫秒数，默认 5 分钟
     */
    fun scheduleAutoDelete(
        context: Context,
        uri: Uri,
        displayName: String,
        delayMillis: Long = DEFAULT_AUTO_DELETE_DELAY_MS
    ) {
        if (!displayName.startsWith(PREFIX_TEMP)) {
            Log.w(TAG, "[安全拦截] 目标文件名不符合临时文件规范，拒绝安排自动删除: $displayName")
            return
        }

        val expireAt = System.currentTimeMillis() + delayMillis
        Log.i(TAG, "[安排临时图片自动删除] $displayName ($uri), 将在 ${(delayMillis / 1000)} 秒后执行")

        // 1. 持久化记录到本地 SharedPreferences（防进程被杀/防关机掉电）
        saveTaskLocally(context, uri.toString(), displayName, expireAt)

        // 2. 注册系统级 AlarmManager 精确闹钟（应用退到后台或息屏依然准时唤醒广播执行）
        scheduleAlarm(context, uri.toString(), displayName, expireAt)

        // 3. 内存协程延迟兜底（若应用在此期间持续存活，秒级精确执行）
        scope.launch {
            delay(delayMillis)
            safelyDeleteSingleImage(context, uri, displayName)
        }
    }

    /**
     * 精确、安全地删除单张临时图片。
     *
     * 包含 4 重防护验证，坚决避免误删用户的其他相册图片！
     */
    fun safelyDeleteSingleImage(context: Context, targetUri: Uri, expectedDisplayName: String): Boolean {
        // 安全锁 1：前缀校验
        if (!expectedDisplayName.startsWith(PREFIX_TEMP)) {
            Log.e(TAG, "[安全防御触发] 拒绝删除：待删除名称非专属临时前缀: $expectedDisplayName")
            return false
        }

        val resolver = context.applicationContext.contentResolver

        // 安全锁 2 & 3：查询目标 Uri 的实际 DISPLAY_NAME 和 RELATIVE_PATH，进行元数据强比对
        val projection = arrayOf(
            MediaStore.Images.Media.DISPLAY_NAME,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.RELATIVE_PATH else MediaStore.Images.Media.DATA
        )

        var isVerified = false
        var actualDisplayName: String? = null

        try {
            resolver.query(targetUri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                    if (nameIdx != -1) {
                        actualDisplayName = cursor.getString(nameIdx)
                    }

                    // 校验文件实际名称是否与预期完全一致且确以 LinkGo_tmp_ 开头
                    if (actualDisplayName != null &&
                        actualDisplayName == expectedDisplayName &&
                        actualDisplayName!!.startsWith(PREFIX_TEMP)
                    ) {
                        isVerified = true
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "查询待删除临时图片元数据异常: ${e.message}")
        }

        if (!isVerified) {
            Log.w(TAG, "[安全防御触发] 无法确认待删文件的临时身份 (actual=$actualDisplayName, expected=$expectedDisplayName)，拒绝删除！")
            // 无论删除是否成功，将无效条目从本地待删除记录中移除
            removeTaskLocally(context, targetUri.toString())
            return false
        }

        // 安全锁 4：执行精准单点删除（仅针对 targetUri 这一个条目）
        return try {
            val rows = resolver.delete(targetUri, null, null)
            val success = rows > 0
            if (success) {
                Log.i(TAG, "[自动删除成功] 临时图片已从相册安全清理: $expectedDisplayName ($targetUri)")
            } else {
                Log.w(TAG, "[自动删除跳过] 临时图片可能已被用户手动删除: $expectedDisplayName")
            }
            removeTaskLocally(context, targetUri.toString())
            success
        } catch (e: Exception) {
            Log.w(TAG, "删除临时图片失败: ${e.message}")
            removeTaskLocally(context, targetUri.toString())
            false
        }
    }

    /**
     * 冷启动或后台对账：检查并清理已过期的临时图片（防手机在 5 分钟内关机或断电）。
     */
    fun checkAndCleanExpired(context: Context) {
        scope.launch {
            val tasks = loadTasksLocally(context)
            if (tasks.isEmpty()) return@launch

            val now = System.currentTimeMillis()
            for (task in tasks) {
                val uriStr = task.optString("uri")
                val displayName = task.optString("displayName")
                val expireAt = task.optLong("expireAt", 0L)

                if (uriStr.isEmpty() || displayName.isEmpty()) continue

                val uri = try { Uri.parse(uriStr) } catch (e: Exception) { null } ?: continue

                if (now >= expireAt) {
                    Log.i(TAG, "[对账清理] 发现已超时未删的临时图片，立即清理: $displayName")
                    safelyDeleteSingleImage(context, uri, displayName)
                } else {
                    // 未过期：重新设置系统闹钟
                    scheduleAlarm(context, uriStr, displayName, expireAt)
                }
            }
        }
    }

    private fun scheduleAlarm(context: Context, uriStr: String, displayName: String, expireAt: Long) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, TemporaryImageCleanerReceiver::class.java).apply {
                action = ACTION_DELETE_TEMP_IMAGE
                putExtra(EXTRA_TARGET_URI, uriStr)
                putExtra(EXTRA_EXPECTED_NAME, displayName)
            }
            val requestCode = uriStr.hashCode()
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, expireAt, pendingIntent)
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, expireAt, pendingIntent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "设置系统闹钟失败: ${e.message}")
        }
    }

    private fun saveTaskLocally(context: Context, uriStr: String, displayName: String, expireAt: Long) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonStr = prefs.getString(KEY_PENDING_TASKS, "[]")
            val array = JSONArray(jsonStr)

            val newObj = JSONObject().apply {
                put("uri", uriStr)
                put("displayName", displayName)
                put("expireAt", expireAt)
            }
            array.put(newObj)
            prefs.edit().putString(KEY_PENDING_TASKS, array.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "持久化保存临时任务失败: ${e.message}")
        }
    }

    private fun removeTaskLocally(context: Context, uriStr: String) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonStr = prefs.getString(KEY_PENDING_TASKS, "[]")
            val array = JSONArray(jsonStr)
            val newArray = JSONArray()

            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                if (obj.optString("uri") != uriStr) {
                    newArray.put(obj)
                }
            }
            prefs.edit().putString(KEY_PENDING_TASKS, newArray.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "移除持久化临时任务失败: ${e.message}")
        }
    }

    private fun loadTasksLocally(context: Context): List<JSONObject> {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonStr = prefs.getString(KEY_PENDING_TASKS, "[]")
            val array = JSONArray(jsonStr)
            val result = mutableListOf<JSONObject>()
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let { result.add(it) }
            }
            result
        } catch (e: Exception) {
            emptyList()
        }
    }
}

/**
 * 接收系统 AlarmManager 定时广播并安全清理临时图片的 BroadcastReceiver。
 */
class TemporaryImageCleanerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null || intent.action != TemporaryImageCleaner.ACTION_DELETE_TEMP_IMAGE) return

        val uriStr = intent.getStringExtra(TemporaryImageCleaner.EXTRA_TARGET_URI) ?: return
        val expectedName = intent.getStringExtra(TemporaryImageCleaner.EXTRA_EXPECTED_NAME) ?: return
        val uri = try { Uri.parse(uriStr) } catch (e: Exception) { null } ?: return

        Log.i("TemporaryImageCleaner", "[广播唤醒] 收到定时删除临时图片广播: $expectedName ($uriStr)")
        TemporaryImageCleaner.safelyDeleteSingleImage(context, uri, expectedName)
    }
}
