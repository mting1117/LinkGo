package com.moting.linkgo.data.backup

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.StringWriter
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/**
 * WebDAV 云备份通道（OkHttp 实现）。
 *
 * 协议：PROPFIND（列举/连通测试）、MKCOL（建目录）、PUT（上传）、GET（下载）、DELETE（手动删除）。
 */
class WebDavBackupStore(private val context: Context) : BackupStore {

    private val config: BackupConfigStore by lazy { BackupConfigStore(context) }
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private val jsonMedia = "application/json".toMediaType()
    private val xmlMedia = "application/xml; charset=utf-8".toMediaType()

    /** 是否已配置云端（地址 + 账号） */
    fun isConfigured(): Boolean {
        val url = config.serverUrl
        return !url.isNullOrBlank()
    }

    /** 连通性测试 */
    suspend fun testConnection(): ChannelResult = withContext(Dispatchers.IO) {
        try {
            val url = config.serverUrl
            if (url.isNullOrBlank()) return@withContext ChannelResult("云端", ChannelStatus.FAILED, "未配置服务器地址")
            val resp = client.newCall(propfindRequest(url, 0)).execute()
            resp.close()
            if (resp.isSuccessful || resp.code == 207) {
                ChannelResult("云端", ChannelStatus.SUCCESS)
            } else if (resp.code == 401) {
                ChannelResult("云端", ChannelStatus.FAILED, "账号或密码错误 (401)")
            } else {
                ChannelResult("云端", ChannelStatus.FAILED, "连接失败 (${resp.code})")
            }
        } catch (e: Exception) {
            ChannelResult("云端", ChannelStatus.FAILED, e.message ?: e.toString())
        }
    }

    override suspend fun save(fileName: String, json: String): ChannelResult = withContext(Dispatchers.IO) {
        try {
            if (!isConfigured()) return@withContext ChannelResult("云端", ChannelStatus.SKIPPED, "未配置")
            val dirUrl = getFullDirPath()
            ensureDirs(dirUrl)
            val fileUrl = joinUrl(dirUrl, fileName)
            val body = json.toRequestBody(jsonMedia)
            val resp = client.newCall(auth(Request.Builder().url(fileUrl).put(body)).build()).execute()
            val code = resp.code
            val isSuccess = resp.isSuccessful
            val errorMsg = if (!isSuccess) resp.body?.string()?.take(200) else null
            resp.close()
            if (isSuccess) {
                ChannelResult("云端", ChannelStatus.SUCCESS)
            } else {
                ChannelResult("云端", ChannelStatus.FAILED, "上传失败 ($code: ${errorMsg ?: "服务端错误"})")
            }
        } catch (e: Exception) {
            ChannelResult("云端", ChannelStatus.FAILED, e.message ?: e.toString())
        }
    }

    override suspend fun load(fileName: String): String? = withContext(Dispatchers.IO) {
        try {
            if (!isConfigured()) return@withContext null
            val fileUrl = joinUrl(getFullDirPath(), fileName)
            val resp = client.newCall(auth(Request.Builder().url(fileUrl).get()).build()).execute()
            resp.use {
                if (it.isSuccessful) it.body?.string() else null
            }
        } catch (e: Exception) {
            android.util.Log.w("WebDavBackupStore", "下载失败", e)
            null
        }
    }

    override suspend fun list(): List<BackupInfo> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext emptyList()
        try {
            val dirUrl = getFullDirPath()
            val resp = client.newCall(propfindRequest(dirUrl, 1)).execute()
            resp.use {
                if (it.code == 404) return@withContext emptyList()
                if (!it.isSuccessful && it.code != 207) return@withContext emptyList()
                val xml = it.body?.string() ?: return@withContext emptyList()
                parseMultiStatus(xml)
            }
        } catch (e: Exception) {
            android.util.Log.w("WebDavBackupStore", "列举失败", e)
            emptyList()
        }
    }

    override suspend fun delete(fileName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!isConfigured()) return@withContext false
            val fileUrl = joinUrl(getFullDirPath(), fileName)
            val resp = client.newCall(auth(Request.Builder().url(fileUrl).delete()).build()).execute()
            resp.close()
            resp.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    // ---- 内部工具 ----

    /** 获取完整的备份目录 URL（serverUrl + basePath），如 https://dav.jianguoyun.com/dav/LinkGo/ */
    private fun getFullDirPath(): String {
        val server = config.serverUrl.orEmpty().trimEnd('/')
        val path = config.basePath.trim().let { if (it.startsWith("/")) it else "/$it" }
        return if (path.endsWith("/")) "$server$path" else "$server$path/"
    }

    /** 逐级创建目录，防止 409 Conflict */
    private fun ensureDirs(targetDirUrl: String) {
        val server = config.serverUrl.orEmpty().trimEnd('/')
        if (server.isBlank()) return
        val target = targetDirUrl.trimEnd('/')
        if (!target.startsWith(server)) return
        val relative = target.removePrefix(server).trim('/')
        if (relative.isBlank()) return

        val segments = relative.split('/').filter { it.isNotBlank() }
        var currentUrl = server
        for (segment in segments) {
            currentUrl += "/$segment"
            mkcol(currentUrl)
        }
    }

    private fun mkcol(url: String) {
        try {
            val resp = client.newCall(auth(Request.Builder().url(url).method("MKCOL", null)).build()).execute()
            // 201: 创建成功, 405: 已存在，均可视为正常
            resp.close()
        } catch (e: Exception) {
            android.util.Log.w("WebDavBackupStore", "MKCOL 失败: $url", e)
        }
    }

    private fun joinUrl(base: String, file: String): String {
        val b = if (base.endsWith("/")) base else "$base/"
        val f = file.trimStart('/')
        return "$b$f"
    }

    private fun auth(builder: Request.Builder): Request.Builder {
        val user = config.username.orEmpty()
        val pass = config.loadPassword().orEmpty()
        if (user.isNotEmpty()) builder.header("Authorization", Credentials.basic(user, pass))
        return builder
    }

    private fun propfindRequest(url: String, depth: Int): Request {
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\" ?>" +
            "<d:propfind xmlns:d=\"DAV:\"><d:prop>" +
            "<d:displayname/><d:getcontentlength/><d:getlastmodified/>" +
            "</d:prop></d:propfind>"
        return auth(Request.Builder().url(url).method("PROPFIND", body.toRequestBody(xmlMedia)))
            .header("Depth", depth.toString())
            .build()
    }

    private fun parseMultiStatus(xml: String): List<BackupInfo> {
        val result = mutableListOf<BackupInfo>()
        try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            val doc = factory.newDocumentBuilder().parse(xml.toByteArray(Charsets.UTF_8).inputStream())
            val responses = doc.getElementsByTagNameNS("DAV:", "response")
            for (i in 0 until responses.length) {
                val resp = responses.item(i) as? org.w3c.dom.Element ?: continue
                val href = childText(resp, "href")?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: continue
                if (href.endsWith("/")) continue // 跳过目录自身
                val fileName = href.substringAfterLast('/').ifBlank { continue }
                if (!fileName.endsWith(BackupFormat.FILE_EXT)) continue
                val size = childText(resp, "getcontentlength")?.toLongOrNull() ?: 0L
                val created = parseDate(childText(resp, "getlastmodified"))
                result.add(BackupInfo(fileName, created, size, source = "cloud"))
            }
        } catch (e: Exception) {
            android.util.Log.w("WebDavBackupStore", "解析 multistatus 失败", e)
        }
        return result.sortedByDescending { it.fileName }
    }

    private fun childText(parent: org.w3c.dom.Element, localName: String): String? {
        val nodes = parent.getElementsByTagNameNS("DAV:", localName)
        return if (nodes.length > 0) nodes.item(0)?.textContent else null
    }

    private fun parseDate(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        return try {
            java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US).parse(raw)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}
