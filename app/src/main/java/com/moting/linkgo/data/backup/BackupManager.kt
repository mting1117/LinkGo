package com.moting.linkgo.data.backup

import android.content.Context
import com.moting.linkgo.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 备份编排器（与通道无关）：融合「备份双通道同时 + 恢复云优先/长按本地」。
 */
class BackupManager(private val context: Context) {

    private val config: BackupConfigStore by lazy { BackupConfigStore(context) }
    private val serializer: BackupSerializer by lazy { BackupSerializer(context) }
    private val restorer: BackupRestorer by lazy { BackupRestorer(context) }
    private val localStore: LocalBackupStore by lazy { LocalBackupStore(context) }
    private val webdav: WebDavBackupStore by lazy { WebDavBackupStore(context) }

    /** 备份：支持本地 / WebDAV / 同时 */
    suspend fun backup(target: BackupTarget = BackupTarget.BOTH): BackupResult = coroutineScope {
        val appVersion = BuildConfig.VERSION_NAME
        val snapshot = serializer.createSnapshot(appVersion)
        val json = serializer.toJson(snapshot)
        val fileName = BackupFormat.buildFileName(appVersion, snapshot.createdAt)

        val localDeferred = if (target == BackupTarget.LOCAL || target == BackupTarget.BOTH) {
            async { localStore.save(fileName, json) }
        } else {
            async { ChannelResult("local", ChannelStatus.SKIPPED, "未选择本地备份") }
        }

        val cloudDeferred = if (target == BackupTarget.WEBDAV || target == BackupTarget.BOTH) {
            async { webdav.save(fileName, json) }
        } else {
            async { ChannelResult("cloud", ChannelStatus.SKIPPED, "未选择云端备份") }
        }

        val result = BackupResult(fileName, localDeferred.await(), cloudDeferred.await())
        config.recordLastBackup(result)
        result
    }

    /** 云连通性测试 */
    suspend fun testCloud(): ChannelResult = webdav.testConnection()

    suspend fun listLocal(): List<BackupInfo> = localStore.list()
    suspend fun listCloud(): List<BackupInfo> = webdav.list()
    suspend fun deleteLocal(fileName: String): Boolean = localStore.delete(fileName)
    suspend fun deleteCloud(fileName: String): Boolean = webdav.delete(fileName)

    /** 解析快照（用于 SAF 选文件 / 预览） */
    fun parseSnapshot(json: String): BackupSnapshot? = serializer.fromJson(json)

    fun verifyChecksum(snapshot: BackupSnapshot): Boolean = serializer.verifyChecksum(snapshot)

    suspend fun loadLocalSnapshot(fileName: String): BackupSnapshot? =
        localStore.load(fileName)?.let { serializer.fromJson(it) }

    suspend fun loadCloudSnapshot(fileName: String): BackupSnapshot? =
        webdav.load(fileName)?.let { serializer.fromJson(it) }

    /**
     * 应用恢复（含恢复前保护快照 + 选择性分组）。
     */
    suspend fun applyRestore(snapshot: BackupSnapshot, groups: Set<RestoreGroup>): RestoreResult {
        if (snapshot.checksum.isNotBlank() && !serializer.verifyChecksum(snapshot)) {
            android.util.Log.w("BackupManager", "校验和不匹配（可能由排版差异引起），继续执行恢复")
        }
        if (snapshot.schemaVersion > BackupFormat.SCHEMA_VERSION) {
            android.util.Log.w("BackupManager", "备份来自更新版本 schema=${snapshot.schemaVersion}")
        }
        makeProtectionBackup()
        return restorer.restore(snapshot, groups)
    }

    /** 恢复前保护快照：固定写应用私有目录，保证可回滚 */
    private suspend fun makeProtectionBackup() = withContext(Dispatchers.IO) {
        try {
            val appVersion = BuildConfig.VERSION_NAME
            val snapshot = serializer.createSnapshot(appVersion)
            val json = serializer.toJson(snapshot)
            val dir = File(context.filesDir, BackupFormat.LOCAL_DIR)
            if (!dir.exists()) dir.mkdirs()
            val name = "Protect_${BackupFormat.buildFileName(appVersion, snapshot.createdAt)}"
            File(dir, name).writeText(json, Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.w("BackupManager", "保护快照失败", e)
        }
    }
}
