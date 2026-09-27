package com.moting.linkgo.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import com.moting.linkgo.data.backup.BackupConfigStore
import com.moting.linkgo.data.backup.BackupManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 自动备份调度：AlarmManager 周期唤醒（无新增依赖），并承担 Wi-Fi 条件判断。
 */
class BackupAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_AUTO_BACKUP = "com.moting.linkgo.action.AUTO_BACKUP"
        private const val REQUEST_CODE = 4001
        private const val TAG = "LinkGo_BackupAlarm"

        fun schedule(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = pendingIntent(context)
            am.cancel(pi)

            val config = BackupConfigStore(context)
            if (!config.autoBackupEnabled) return
            val interval = BackupConfigStore.freqMillis(config.backupFrequency)
            if (interval <= 0L) return

            val triggerAtMillis = calculateNextTriggerTime()
            am.setInexactRepeating(AlarmManager.RTC_WAKEUP, triggerAtMillis, interval, pi)
            val timeStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(triggerAtMillis))
            Log.i(TAG, "自动备份已调度，下次闲时触发时间: $timeStr (周期: ${interval / (1000 * 60 * 60)}小时)")
        }

        /**
         * 计算下一个闲时触发时间（锚定在每日凌晨 03:30）
         */
        fun calculateNextTriggerTime(): Long {
            val now = System.currentTimeMillis()
            val calendar = java.util.Calendar.getInstance().apply {
                timeInMillis = now
                set(java.util.Calendar.HOUR_OF_DAY, 3)
                set(java.util.Calendar.MINUTE, 30)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            // 若今日凌晨 03:30 已过，则从明日凌晨 03:30 开始
            if (calendar.timeInMillis <= now) {
                calendar.add(java.util.Calendar.DAY_OF_YEAR, 1)
            }
            return calendar.timeInMillis
        }

        fun getNextTriggerSummary(context: Context): String? {
            val config = BackupConfigStore(context)
            if (!config.autoBackupEnabled) return null
            val interval = BackupConfigStore.freqMillis(config.backupFrequency)
            if (interval <= 0L) return null
            val t = calculateNextTriggerTime()
            return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t))
        }

        fun cancel(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pendingIntent(context))
        }

        private fun pendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, BackupAlarmReceiver::class.java).apply {
                action = ACTION_AUTO_BACKUP
            }
            return PendingIntent.getBroadcast(
                context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_AUTO_BACKUP) return
        Log.i(TAG, "收到自动备份闹钟")
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (BackupConditions.allowed(appContext)) {
                    BackupManager(appContext).backup()
                    Log.i(TAG, "自动备份完成")
                } else {
                    Log.i(TAG, "自动备份跳过：条件不满足（非 Wi-Fi）")
                }
            } catch (e: Exception) {
                Log.w(TAG, "自动备份异常: ${e.message}", e)
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * 自动备份前置条件：仅 Wi-Fi。
 */
object BackupConditions {
    fun allowed(context: Context): Boolean {
        val c = BackupConfigStore(context)
        if (c.wifiOnly && !isWifi(context)) return false
        return true
    }

    private fun isWifi(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (e: Exception) {
            false
        }
    }
}
