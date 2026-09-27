package com.moting.linkgo.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moting.linkgo.data.backup.*
import com.moting.linkgo.receiver.BackupAlarmReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BackupViewModel : ViewModel() {

    enum class Op { Idle, Connecting, BackingUp, Restoring }

    private var manager: BackupManager? = null
    private var config: BackupConfigStore? = null
    private var appContext: Context? = null

    // ---- 云端配置 ----
    private val _serverUrl = MutableStateFlow("")
    val serverUrl = _serverUrl.asStateFlow()
    private val _username = MutableStateFlow("")
    val username = _username.asStateFlow()
    private val _basePath = MutableStateFlow("/LinkGo/")
    val basePath = _basePath.asStateFlow()
    private val _passwordSet = MutableStateFlow(false)
    val passwordSet = _passwordSet.asStateFlow()

    // ---- 自动备份 ----
    private val _autoBackupEnabled = MutableStateFlow(false)
    val autoBackupEnabled = _autoBackupEnabled.asStateFlow()
    private val _frequency = MutableStateFlow(BackupConfigStore.FREQ_MANUAL)
    val frequency = _frequency.asStateFlow()
    private val _wifiOnly = MutableStateFlow(true)
    val wifiOnly = _wifiOnly.asStateFlow()
    private val _changeAutoBackupEnabled = MutableStateFlow(false)
    val changeAutoBackupEnabled = _changeAutoBackupEnabled.asStateFlow()
    private val _nextBackupTime = MutableStateFlow<String?>(null)
    val nextBackupTime = _nextBackupTime.asStateFlow()

    // ---- 本地目录 ----
    private val _localDirUri = MutableStateFlow<String?>(null)
    val localDirUri = _localDirUri.asStateFlow()

    // ---- 运行状态 ----
    private val _op = MutableStateFlow(Op.Idle)
    val op = _op.asStateFlow()
    private val _lastLocalBackup = MutableStateFlow<String>("暂无备份记录")
    val lastLocalBackup = _lastLocalBackup.asStateFlow()
    private val _lastCloudBackup = MutableStateFlow<String>("未配置 WebDAV")
    val lastCloudBackup = _lastCloudBackup.asStateFlow()
    private val _localList = MutableStateFlow<List<BackupInfo>>(emptyList())
    val localList = _localList.asStateFlow()
    private val _cloudList = MutableStateFlow<List<BackupInfo>>(emptyList())
    val cloudList = _cloudList.asStateFlow()

    // ---- 恢复预览 ----
    private val _previewSnapshot = MutableStateFlow<BackupSnapshot?>(null)
    val previewSnapshot = _previewSnapshot.asStateFlow()
    private val _previewSource = MutableStateFlow("")
    val previewSource = _previewSource.asStateFlow()

    private val _uiEvent = MutableSharedFlow<String>()
    val uiEvent = _uiEvent.asSharedFlow()

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (manager == null) {
            manager = BackupManager(app)
            config = BackupConfigStore(app)
        }
        refreshState()
    }

    fun refreshState() {
        val c = config ?: return
        reloadConfig()
        _lastLocalBackup.value = c.lastLocalSummary()
        _lastCloudBackup.value = c.lastCloudSummary()
        refreshLocalList()
        if (c.hasCloudConfig()) {
            refreshCloudList()
        }
    }

    fun reloadConfig() {
        val c = config ?: return
        _serverUrl.value = c.serverUrl ?: ""
        _username.value = c.username ?: ""
        _basePath.value = c.basePath
        _passwordSet.value = c.loadPassword()?.isNotEmpty() == true
        _autoBackupEnabled.value = c.autoBackupEnabled
        _frequency.value = c.backupFrequency
        _wifiOnly.value = c.wifiOnly
        _changeAutoBackupEnabled.value = c.changeAutoBackupEnabled
        _localDirUri.value = c.localDirUri
        _nextBackupTime.value = appContext?.let { BackupAlarmReceiver.getNextTriggerSummary(it) }
    }

    enum class CloudTestStatus { IDLE, TESTING, SUCCESS, FAILED }

    // ---- 连通性测试状态 ----
    private val _testStatus = MutableStateFlow(CloudTestStatus.IDLE)
    val testStatus = _testStatus.asStateFlow()
    private val _testError = MutableStateFlow<String?>(null)
    val testError = _testError.asStateFlow()

    fun loadCurrentPassword(): String = config?.loadPassword().orEmpty()

    // ---- WebDAV 统一配置（固定路径 /LinkGo/） ----
    fun saveWebDavConfig(url: String, user: String, pass: String, path: String = "/LinkGo/") {
        val c = config ?: return
        c.serverUrl = url
        c.username = user
        if (pass.isNotEmpty()) {
            c.savePassword(pass)
        } else if (pass.isEmpty() && !_passwordSet.value) {
            c.clearPassword()
        }
        c.basePath = path.ifBlank { "/LinkGo/" }
        _testStatus.value = CloudTestStatus.IDLE
        _testError.value = null
        reloadConfig()
    }

    fun clearWebDavConfig() {
        val c = config ?: return
        c.serverUrl = null
        c.username = null
        c.basePath = "/LinkGo/"
        c.clearPassword()
        _testStatus.value = CloudTestStatus.IDLE
        _testError.value = null
        reloadConfig()
    }

    fun setAutoBackupEnabled(v: Boolean) {
        config?.autoBackupEnabled = v
        _autoBackupEnabled.value = v
        reschedule()
    }
    fun setFrequency(v: String) { config?.backupFrequency = v; _frequency.value = v; reschedule() }
    fun setWifiOnly(v: Boolean) { config?.wifiOnly = v; _wifiOnly.value = v }
    fun setChangeAutoBackupEnabled(v: Boolean) { config?.changeAutoBackupEnabled = v; _changeAutoBackupEnabled.value = v }
    fun setLocalDirUri(uri: String?) { config?.localDirUri = uri; _localDirUri.value = uri }

    private fun reschedule() {
        val c = appContext ?: return
        BackupAlarmReceiver.schedule(c)
        _nextBackupTime.value = BackupAlarmReceiver.getNextTriggerSummary(c)
    }

    // ---- 操作 ----
    fun backup(target: BackupTarget = BackupTarget.BOTH) {
        val m = manager ?: return
        if (_op.value != Op.Idle) return
        viewModelScope.launch {
            _op.value = Op.BackingUp
            try {
                val result = withContext(Dispatchers.IO) { m.backup(target) }
                _lastLocalBackup.value = config?.lastLocalSummary() ?: "暂无备份记录"
                _lastCloudBackup.value = config?.lastCloudSummary() ?: "未配置 WebDAV"
                val msg = when (target) {
                    BackupTarget.LOCAL -> if (result.local.status == ChannelStatus.SUCCESS) "本地备份完成 ✓" else "本地备份失败：${result.local.message ?: "未知错误"}"
                    BackupTarget.WEBDAV -> if (result.cloud.status == ChannelStatus.SUCCESS) "WebDAV 云端备份完成 ✓" else "WebDAV 备份失败：${result.cloud.message ?: "未知错误"}"
                    BackupTarget.BOTH -> {
                        val localTxt = if (result.local.status == ChannelStatus.SUCCESS) "本地 ✓" else "本地 ✗"
                        val cloudTxt = when (result.cloud.status) {
                            ChannelStatus.SUCCESS -> "云端 ✓"
                            ChannelStatus.SKIPPED -> "云端未配置"
                            else -> "云端 ✗"
                        }
                        "备份完成：$localTxt · $cloudTxt"
                    }
                }
                _uiEvent.emit(msg)
                if (target == BackupTarget.LOCAL || target == BackupTarget.BOTH) {
                    refreshLocalList()
                }
                if ((target == BackupTarget.WEBDAV || target == BackupTarget.BOTH) && config?.hasCloudConfig() == true) {
                    refreshCloudList()
                }
            } catch (e: Exception) {
                _uiEvent.emit("备份失败：${e.message ?: e.toString()}")
            } finally {
                _op.value = Op.Idle
            }
        }
    }

    fun testConnection(onComplete: ((Boolean, String?) -> Unit)? = null) {
        val m = manager ?: return
        viewModelScope.launch {
            _op.value = Op.Connecting
            _testStatus.value = CloudTestStatus.TESTING
            _testError.value = null
            try {
                val r = withContext(Dispatchers.IO) { m.testCloud() }
                val ok = r.status == ChannelStatus.SUCCESS
                val msg = if (ok) "云端连接成功" else "连接失败：${r.message ?: "未知错误"}"
                _testStatus.value = if (ok) CloudTestStatus.SUCCESS else CloudTestStatus.FAILED
                _testError.value = if (!ok) r.message ?: "连接失败" else null
                _uiEvent.emit(msg)
                onComplete?.invoke(ok, msg)
            } catch (e: Exception) {
                _testStatus.value = CloudTestStatus.FAILED
                _testError.value = e.message ?: "连接异常"
                _uiEvent.emit("连接异常：${e.message ?: ""}")
                onComplete?.invoke(false, e.message)
            } finally {
                _op.value = Op.Idle
            }
        }
    }

    fun refreshLocalList() {
        val m = manager ?: return
        viewModelScope.launch { _localList.value = withContext(Dispatchers.IO) { m.listLocal() } }
    }

    fun refreshCloudList() {
        val m = manager ?: return
        viewModelScope.launch { _cloudList.value = withContext(Dispatchers.IO) { m.listCloud() } }
    }

    fun requestCloudRestore(fileName: String) {
        val m = manager ?: return
        viewModelScope.launch {
            _op.value = Op.Restoring
            try {
                val s = withContext(Dispatchers.IO) { m.loadCloudSnapshot(fileName) }
                if (s == null) _uiEvent.emit("云端备份下载失败")
                else { _previewSnapshot.value = s; _previewSource.value = "云端" }
            } finally {
                _op.value = Op.Idle
            }
        }
    }

    fun requestLocalRestore(fileName: String) {
        val m = manager ?: return
        viewModelScope.launch {
            _op.value = Op.Restoring
            try {
                val s = withContext(Dispatchers.IO) { m.loadLocalSnapshot(fileName) }
                if (s == null) _uiEvent.emit("本地备份读取失败")
                else { _previewSnapshot.value = s; _previewSource.value = "本地" }
            } finally {
                _op.value = Op.Idle
            }
        }
    }

    fun requestContentRestore(content: String) {
        val m = manager ?: return
        val s = m.parseSnapshot(content)
        if (s == null) {
            viewModelScope.launch { _uiEvent.emit("备份文件格式无效") }
            return
        }
        _previewSnapshot.value = s
        _previewSource.value = "本地文件"
    }

    fun loadRawBackupJson(fileName: String, isCloud: Boolean, onResult: (String?) -> Unit) {
        val m = manager ?: return
        viewModelScope.launch {
            val json = withContext(Dispatchers.IO) {
                if (isCloud) {
                    m.loadCloudSnapshot(fileName)?.let { BackupSerializer(appContext!!).toJson(it) }
                } else {
                    LocalBackupStore(appContext!!).load(fileName)
                }
            }
            onResult(json)
        }
    }

    fun confirmRestore(groups: Set<RestoreGroup>) {
        val m = manager ?: return
        val snapshot = _previewSnapshot.value ?: return
        viewModelScope.launch {
            _op.value = Op.Restoring
            try {
                val r = withContext(Dispatchers.IO) { m.applyRestore(snapshot, groups) }
                _uiEvent.emit(r.message)
                _previewSnapshot.value = null
            } catch (e: Exception) {
                _uiEvent.emit("恢复失败：${e.message ?: e.toString()}")
            } finally {
                _op.value = Op.Idle
            }
        }
    }

    fun dismissPreview() { _previewSnapshot.value = null }

    fun deleteLocal(fileName: String) {
        val m = manager ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { m.deleteLocal(fileName) }
            _uiEvent.emit("已删除本地备份")
            refreshLocalList()
        }
    }

    fun deleteCloud(fileName: String) {
        val m = manager ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { m.deleteCloud(fileName) }
            _uiEvent.emit("已删除云端备份")
            refreshCloudList()
        }
    }
}
