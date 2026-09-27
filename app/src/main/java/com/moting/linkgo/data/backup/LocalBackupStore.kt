package com.moting.linkgo.data.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 本地备份通道：
 *  - 未自定义目录：写应用私有 `filesDir/backups`（免权限）
 *  - 已自定义目录：写 SAF `OpenDocumentTree` 所选文件夹（持久化 URI 权限）
 */
class LocalBackupStore(private val context: Context) : BackupStore {

    private val config: BackupConfigStore by lazy { BackupConfigStore(context) }
    private val privateDir: File get() = File(context.filesDir, BackupFormat.LOCAL_DIR)

    override suspend fun save(fileName: String, json: String): ChannelResult = withContext(Dispatchers.IO) {
        try {
            val tree = customTree()
            if (tree != null) {
                val file = tree.createFile(BackupFormat.MIME, fileName)
                    ?: return@withContext ChannelResult("本地", ChannelStatus.FAILED, "无法在所选目录创建文件")
                context.contentResolver.openOutputStream(file.uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    ?: return@withContext ChannelResult("本地", ChannelStatus.FAILED, "无法写入文件")
            } else {
                if (!privateDir.exists()) privateDir.mkdirs()
                File(privateDir, fileName).writeText(json, Charsets.UTF_8)
            }
            ChannelResult("本地", ChannelStatus.SUCCESS)
        } catch (e: Exception) {
            android.util.Log.w("LocalBackupStore", "本地备份失败", e)
            ChannelResult("本地", ChannelStatus.FAILED, e.message ?: e.toString())
        }
    }

    override suspend fun load(fileName: String): String? = withContext(Dispatchers.IO) {
        try {
            val tree = customTree()
            if (tree != null) {
                tree.findFile(fileName)?.uri?.let { uri ->
                    context.contentResolver.openInputStream(uri)?.use {
                        it.bufferedReader(Charsets.UTF_8).use { r -> r.readText() }
                    }
                }?.let { return@withContext it }
            }
            val f = File(privateDir, fileName)
            if (f.exists()) f.readText(Charsets.UTF_8) else null
        } catch (e: Exception) {
            android.util.Log.w("LocalBackupStore", "本地读取失败", e)
            null
        }
    }

    override suspend fun list(): List<BackupInfo> = withContext(Dispatchers.IO) {
        val result = mutableListOf<BackupInfo>()
        try {
            // 私有目录（含保护快照）
            if (privateDir.exists()) {
                privateDir.listFiles { f -> f.isFile && f.name.endsWith(BackupFormat.FILE_EXT) }
                    ?.forEach { f -> result.add(BackupInfo(f.name, f.lastModified(), f.length(), source = "local")) }
            }
            // 自定义目录
            customTree()?.listFiles()?.forEach { doc ->
                if (doc.isFile && doc.name?.endsWith(BackupFormat.FILE_EXT) == true) {
                    result.add(BackupInfo(doc.name ?: "", doc.lastModified(), doc.length(), source = "local"))
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("LocalBackupStore", "本地列举失败", e)
        }
        result.distinctBy { it.fileName }.sortedByDescending { it.createdAt }
    }

    override suspend fun delete(fileName: String): Boolean = withContext(Dispatchers.IO) {
        var deleted = false
        try {
            customTree()?.findFile(fileName)?.let { deleted = it.delete() || deleted }
        } catch (e: Exception) {
            android.util.Log.w("LocalBackupStore", "本地删除失败", e)
        }
        try {
            val f = File(privateDir, fileName)
            if (f.exists()) deleted = f.delete() || deleted
        } catch (e: Exception) {
            android.util.Log.w("LocalBackupStore", "本地删除失败", e)
        }
        deleted
    }

    private fun customTree(): DocumentFile? {
        val uriStr = config.localDirUri ?: return null
        return try {
            DocumentFile.fromTreeUri(context, Uri.parse(uriStr))
        } catch (e: Exception) {
            null
        }
    }
}
