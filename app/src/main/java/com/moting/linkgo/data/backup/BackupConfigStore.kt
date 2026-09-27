package com.moting.linkgo.data.backup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.security.KeyStore

/**
 * 备份配置存储：
 *  - WebDAV 连接（地址/账号/basePath 明文；密码经 Android Keystore AES-GCM 加密后存储）
 *  - 自动备份策略、本地目录 URI、最近备份摘要
 */
class BackupConfigStore(context: Context) {

    companion object {
        private const val PREFS = "linkgo_backup_config"

        const val FREQ_MANUAL = "manual"
        const val FREQ_DAILY = "daily"
        const val FREQ_THREE_DAYS = "three_days"
        const val FREQ_WEEKLY = "weekly"
        val FREQ_OPTIONS = listOf(FREQ_DAILY, FREQ_THREE_DAYS, FREQ_WEEKLY, FREQ_MANUAL)

        fun freqLabel(freq: String): String = when (freq) {
            FREQ_DAILY -> "每天"
            FREQ_THREE_DAYS -> "每 3 天"
            FREQ_WEEKLY -> "每周"
            else -> "仅手动"
        }

        fun freqMillis(freq: String): Long = when (freq) {
            FREQ_DAILY -> 24 * 60 * 60 * 1000L
            FREQ_THREE_DAYS -> 3 * 24 * 60 * 60 * 1000L
            FREQ_WEEKLY -> 7 * 24 * 60 * 60 * 1000L
            else -> 0L
        }
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- WebDAV ----
    var serverUrl: String?
        get() = prefs.getString("server_url", null)
        set(v) = prefs.edit().putString("server_url", v?.trim()?.ifBlank { null }).apply()

    var username: String?
        get() = prefs.getString("username", null)
        set(v) = prefs.edit().putString("username", v?.trim()?.ifBlank { null }).apply()

    var basePath: String
        get() = prefs.getString("base_path", "/LinkGo/") ?: "/LinkGo/"
        set(v) = prefs.edit().putString("base_path", v.ifBlank { "/LinkGo/" }).apply()

    fun savePassword(plain: String): Boolean {
        val enc = BackupKeystore.encrypt(plain) ?: return false
        prefs.edit().putString("password_enc", enc).apply()
        return true
    }

    fun loadPassword(): String? {
        val enc = prefs.getString("password_enc", null) ?: return null
        return BackupKeystore.decrypt(enc)
    }

    fun clearPassword() {
        prefs.edit().remove("password_enc").apply()
    }

    fun hasCloudConfig(): Boolean = !serverUrl.isNullOrBlank()

    // ---- 自动备份 ----
    var autoBackupEnabled: Boolean
        get() = prefs.getBoolean("auto_backup_enabled", false)
        set(v) = prefs.edit().putBoolean("auto_backup_enabled", v).apply()

    var backupFrequency: String
        get() = prefs.getString("backup_frequency", FREQ_MANUAL) ?: FREQ_MANUAL
        set(v) = prefs.edit().putString("backup_frequency", v).apply()

    var wifiOnly: Boolean
        get() = prefs.getBoolean("wifi_only", true)
        set(v) = prefs.edit().putBoolean("wifi_only", v).apply()

    var changeAutoBackupEnabled: Boolean
        get() = prefs.getBoolean("change_auto_backup_enabled", false)
        set(v) = prefs.edit().putBoolean("change_auto_backup_enabled", v).apply()

    // ---- 本地目录 ----
    var localDirUri: String?
        get() = prefs.getString("local_dir_uri", null)
        set(v) = prefs.edit().putString("local_dir_uri", v).apply()

    // ---- 最近备份摘要（分通道独立记录） ----
    var lastLocalBackupTime: Long
        get() = prefs.getLong("last_local_backup_time", 0L)
        set(v) = prefs.edit().putLong("last_local_backup_time", v).apply()

    var lastLocalBackupStatus: String
        get() = prefs.getString("last_local_backup_status", "") ?: ""
        set(v) = prefs.edit().putString("last_local_backup_status", v).apply()

    var lastLocalBackupMsg: String?
        get() = prefs.getString("last_local_backup_msg", null)
        set(v) = prefs.edit().putString("last_local_backup_msg", v).apply()

    var lastCloudBackupTime: Long
        get() = prefs.getLong("last_cloud_backup_time", 0L)
        set(v) = prefs.edit().putLong("last_cloud_backup_time", v).apply()

    var lastCloudBackupStatus: String
        get() = prefs.getString("last_cloud_backup_status", "") ?: ""
        set(v) = prefs.edit().putString("last_cloud_backup_status", v).apply()

    var lastCloudBackupMsg: String?
        get() = prefs.getString("last_cloud_backup_msg", null)
        set(v) = prefs.edit().putString("last_cloud_backup_msg", v).apply()

    fun recordLastBackup(result: BackupResult) {
        val now = System.currentTimeMillis()
        if (result.local.status != ChannelStatus.SKIPPED) {
            lastLocalBackupTime = now
            lastLocalBackupStatus = result.local.status.name
            lastLocalBackupMsg = result.local.message
        }
        if (result.cloud.status != ChannelStatus.SKIPPED) {
            lastCloudBackupTime = now
            lastCloudBackupStatus = result.cloud.status.name
            lastCloudBackupMsg = result.cloud.message
        }
    }

    /** 本地上次备份摘要文案 */
    fun lastLocalSummary(): String {
        val t = lastLocalBackupTime
        if (t == 0L) return "暂无备份记录"
        val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(t))
        return when (lastLocalBackupStatus) {
            ChannelStatus.SUCCESS.name -> "$time · 备份成功"
            ChannelStatus.FAILED.name -> "$time · 失败（${lastLocalBackupMsg ?: "未知错误"}）"
            else -> time
        }
    }

    /** 云端上次备份摘要文案 */
    fun lastCloudSummary(): String {
        if (!hasCloudConfig()) return "未配置 WebDAV"
        val t = lastCloudBackupTime
        if (t == 0L) return "暂无备份记录"
        val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(t))
        return when (lastCloudBackupStatus) {
            ChannelStatus.SUCCESS.name -> "$time · 备份成功"
            ChannelStatus.FAILED.name -> "$time · 失败（${lastCloudBackupMsg ?: "未知错误"}）"
            else -> time
        }
    }
}

/**
 * Android Keystore AES-GCM 加解密（仅用于 WebDAV 密码，不涉及备份正文加密）。
 */
private object BackupKeystore {
    private const val ALIAS = "linkgo_backup_webdav_pwd"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    } catch (e: Exception) {
        null
    }

    fun decrypt(data: String): String? = try {
        val parts = data.split(":")
        if (parts.size != 2) {
            null
        } else {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ct = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        }
    } catch (e: Exception) {
        null
    }
}
