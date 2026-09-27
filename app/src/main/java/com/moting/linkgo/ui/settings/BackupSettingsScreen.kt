package com.moting.linkgo.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moting.linkgo.R
import com.moting.linkgo.data.backup.*
import com.moting.linkgo.ui.components.*
import com.moting.linkgo.viewmodel.BackupViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份与恢复页面：
 * 1. 核心操作置顶：一键备份（选择目标：本地/WebDAV/同时）与恢复数据（来源选择面板）
 * 2. 统合 WebDAV：合并配置项为单一卡片，固定路径为 /LinkGo/，右端实时展示连通性测试结果
 * 3. 完整路径展示：清晰呈现本地存储目录的完整路径
 * 4. 极简规范：二级页面不带左端图标，视觉干净纯粹
 * 5. 安全防灾历史管理：带二次确认的删除与恢复默认目录，规整优雅的备份历史列表
 * 6. 自动备份策略：主开关与子项联动
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSettingsScreen(
    onNavigateBack: () -> Unit
) {
    val viewModel: BackupViewModel = viewModel()
    val context = LocalContext.current

    val serverUrl by viewModel.serverUrl.collectAsState()
    val username by viewModel.username.collectAsState()
    val autoBackupEnabled by viewModel.autoBackupEnabled.collectAsState()
    val frequency by viewModel.frequency.collectAsState()
    val wifiOnly by viewModel.wifiOnly.collectAsState()
    val changeAutoBackupEnabled by viewModel.changeAutoBackupEnabled.collectAsState()
    val localDirUri by viewModel.localDirUri.collectAsState()
    val op by viewModel.op.collectAsState()
    val testStatus by viewModel.testStatus.collectAsState()
    val testError by viewModel.testError.collectAsState()
    val lastLocalBackup by viewModel.lastLocalBackup.collectAsState()
    val lastCloudBackup by viewModel.lastCloudBackup.collectAsState()
    val nextBackupTime by viewModel.nextBackupTime.collectAsState()
    val localList by viewModel.localList.collectAsState()
    val cloudList by viewModel.cloudList.collectAsState()
    val previewSnapshot by viewModel.previewSnapshot.collectAsState()
    val previewSource by viewModel.previewSource.collectAsState()

    // ---- 对话框状态 ----
    var showBackupTargetDialog by remember { mutableStateOf(false) }
    var showWebDavDialog by remember { mutableStateOf(false) }
    var showClearWebDavConfirmDialog by remember { mutableStateOf(false) }
    var showResetLocalDirConfirmDialog by remember { mutableStateOf(false) }
    var showRestoreSourceDialog by remember { mutableStateOf(false) }
    var showLocalListDialog by remember { mutableStateOf(false) }
    var showCloudListDialog by remember { mutableStateOf(false) }
    var deleteConfirmTarget by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // (fileName, isCloud)

    LaunchedEffect(Unit) {
        viewModel.init(context)
        viewModel.uiEvent.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // ---- SAF 目录选择（自定义本地备份目录） ----
    val dirPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            viewModel.setLocalDirUri(uri.toString())
        }
    }

    // ---- SAF 浏览文件（外部导入恢复） ----
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val content = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            }.getOrNull()?.removePrefix("\uFEFF")?.trim()
            if (content.isNullOrBlank()) {
                Toast.makeText(context, "文件读取失败", Toast.LENGTH_SHORT).show()
            } else {
                viewModel.requestContentRestore(content)
            }
        }
    }

    val localDirDisplayPath = remember(localDirUri) { getDisplayPath(context, localDirUri) }

    CollapsingTopBarScaffold(
        title = "备份与恢复",
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    ImageVector.vectorResource(id = R.drawable.ic_iconoir_arrow_left),
                    contentDescription = "返回"
                )
            }
        }
    ) { padding, nestedScrollConnection ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(nestedScrollConnection),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
            }

            // 1. 核心操作面板（置顶）
            item {
                SettingsSection(topLabel = "数据操作") {
                    // 立即备份
                    SettingItem(
                        headlineText = "立即备份",
                        supportingText = if (op == BackupViewModel.Op.BackingUp) "正在生成备份快照..." else "生成当前配置与数据的完整快照",
                        trailingContent = {
                            if (op == BackupViewModel.Op.BackingUp) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Icon(
                                    imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_nav_arrow_right),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        },
                        modifier = Modifier.clickable(enabled = op == BackupViewModel.Op.Idle) {
                            showBackupTargetDialog = true
                        }
                    )

                    SettingsSectionDivider()

                    // 恢复数据
                    SettingItem(
                        headlineText = "恢复数据",
                        supportingText = "从 WebDAV 云端、本地存储或文件导入",
                        trailingContent = {
                            Icon(
                                imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_nav_arrow_right),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        modifier = Modifier.clickable(enabled = op == BackupViewModel.Op.Idle) {
                            showRestoreSourceDialog = true
                        }
                    )

                    SettingsSectionDivider()

                    // 本地上次备份
                    SettingItem(
                        headlineText = "本地备份",
                        supportingText = lastLocalBackup,
                        trailingContent = null
                    )

                    SettingsSectionDivider()

                    // WebDAV 云端上次备份
                    SettingItem(
                        headlineText = "WebDAV 云端",
                        supportingText = lastCloudBackup,
                        trailingContent = null
                    )
                }
            }

            // 2. WebDAV 云端同步（合并为统一配置条目，去重，固定路径）
            item {
                val isCloudConfigured = serverUrl.isNotBlank()
                SettingsSection(topLabel = "WebDAV 云端同步") {
                    SettingItem(
                        headlineText = "WebDAV 云端配置",
                        supportingText = if (isCloudConfigured) {
                            username.ifBlank { serverUrl }
                        } else {
                            "未配置 · 点击一键配置坚果云/Nextcloud等"
                        },
                        trailingContent = {
                            Surface(
                                color = if (isCloudConfigured) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = if (isCloudConfigured) "已配置" else "去配置",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isCloudConfigured) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        },
                        modifier = Modifier.clickable { showWebDavDialog = true }
                    )

                    if (isCloudConfigured) {
                        SettingsSectionDivider()
                        SettingItem(
                            headlineText = "连接测试",
                            supportingText = when (testStatus) {
                                BackupViewModel.CloudTestStatus.FAILED -> testError ?: "连接失败"
                                BackupViewModel.CloudTestStatus.TESTING -> "正在测试云端连接..."
                                else -> "检测服务器与账号可用性"
                            },
                            trailingContent = {
                                when (testStatus) {
                                    BackupViewModel.CloudTestStatus.TESTING -> {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    }
                                    BackupViewModel.CloudTestStatus.SUCCESS -> {
                                        Surface(
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text(
                                                "连接正常",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                    BackupViewModel.CloudTestStatus.FAILED -> {
                                        Surface(
                                            color = MaterialTheme.colorScheme.errorContainer,
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text(
                                                "连接失败",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onErrorContainer,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                    BackupViewModel.CloudTestStatus.IDLE -> {
                                        Box(
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_nav_arrow_right),
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                contentDescription = "测试连接"
                                            )
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.clickable {
                                if (op == BackupViewModel.Op.Idle && testStatus != BackupViewModel.CloudTestStatus.TESTING) {
                                    viewModel.testConnection()
                                }
                            }
                        )
                        SettingsSectionDivider()
                        SettingItem(
                            headlineText = "清除云端配置",
                            supportingText = "移除已保存的 WebDAV 地址、账号与密码",
                            modifier = Modifier.clickable { showClearWebDavConfirmDialog = true }
                        )
                    }
                }
            }

            // 3. 本地存储（展示完整路径，带恢复默认二次确认）
            item {
                SettingsSection(topLabel = "本地存储") {
                    SettingItem(
                        headlineText = "备份存储目录",
                        supportingText = localDirDisplayPath,
                        trailingContent = {
                            TextButton(onClick = { dirPicker.launch(null) }) {
                                Text("更改")
                            }
                        },
                        modifier = Modifier.clickable { dirPicker.launch(null) }
                    )

                    if (localDirUri != null) {
                        SettingsSectionDivider()
                        SettingItem(
                            headlineText = "恢复默认私有目录",
                            supportingText = "切换回应用沙盒内部存储目录",
                            modifier = Modifier.clickable { showResetLocalDirConfirmDialog = true }
                        )
                    }
                }
            }

            // 4. 自动备份策略
            item {
                SettingsSection(topLabel = "自动备份策略") {
                    SettingItem(
                        headlineText = "自动备份",
                        supportingText = if (autoBackupEnabled && nextBackupTime != null) {
                            "下次备份预计：$nextBackupTime"
                        } else {
                            "按周期计划自动同步本地与云端"
                        },
                        trailingContent = {
                            PremiumSwitch(
                                checked = autoBackupEnabled,
                                onCheckedChange = { viewModel.setAutoBackupEnabled(it) }
                            )
                        }
                    )

                    // 子项联动：未开启自动备份时置灰展示
                    val subAlpha = if (autoBackupEnabled) 1f else 0.38f

                    SettingsSectionDivider()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(subAlpha)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        PremiumSegmentedRow(
                            options = BackupConfigStore.FREQ_OPTIONS,
                            selectedOption = frequency,
                            onOptionSelected = { if (autoBackupEnabled) viewModel.setFrequency(it) },
                            labelProvider = { BackupConfigStore.freqLabel(it) }
                        )
                    }

                    SettingsSectionDivider()
                    SettingItem(
                        headlineText = "仅 Wi-Fi",
                        supportingText = "非 Wi-Fi 网络环境下跳过自动备份",
                        trailingContent = {
                            PremiumSwitch(
                                checked = wifiOnly,
                                enabled = autoBackupEnabled,
                                onCheckedChange = { viewModel.setWifiOnly(it) }
                            )
                        },
                        modifier = Modifier.alpha(subAlpha)
                    )

                    SettingsSectionDivider()
                    SettingItem(
                        headlineText = "规则变更后自动备份",
                        supportingText = "检测到规则或配置变动 60 秒后静默备份",
                        trailingContent = {
                            PremiumSwitch(
                                checked = changeAutoBackupEnabled,
                                enabled = autoBackupEnabled,
                                onCheckedChange = { viewModel.setChangeAutoBackupEnabled(it) }
                            )
                        },
                        modifier = Modifier.alpha(subAlpha)
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }

    // ================= 对话框区域 =================

    // 0. 备份方式选择对话框（本地 / WebDAV / 同时）
    if (showBackupTargetDialog) {
        BackupTargetDialog(
            isCloudConfigured = serverUrl.isNotBlank(),
            onSelectLocal = {
                showBackupTargetDialog = false
                viewModel.backup(BackupTarget.LOCAL)
            },
            onSelectWebDav = {
                showBackupTargetDialog = false
                viewModel.backup(BackupTarget.WEBDAV)
            },
            onSelectBoth = {
                showBackupTargetDialog = false
                viewModel.backup(BackupTarget.BOTH)
            },
            onConfigureCloud = {
                showBackupTargetDialog = false
                showWebDavDialog = true
            },
            onDismiss = { showBackupTargetDialog = false }
        )
    }

    // 1. 恢复来源选择面板
    if (showRestoreSourceDialog) {
        RestoreSourceDialog(
            isCloudConfigured = serverUrl.isNotBlank(),
            onSelectCloud = {
                showRestoreSourceDialog = false
                viewModel.refreshCloudList()
                showCloudListDialog = true
            },
            onSelectLocal = {
                showRestoreSourceDialog = false
                viewModel.refreshLocalList()
                showLocalListDialog = true
            },
            onSelectFile = {
                showRestoreSourceDialog = false
                filePicker.launch(arrayOf(BackupFormat.MIME, "application/json", "*/*"))
            },
            onConfigureCloud = {
                showRestoreSourceDialog = false
                showWebDavDialog = true
            },
            onDismiss = { showRestoreSourceDialog = false }
        )
    }

    // 2. 统合 WebDAV 配置对话框（无自定义路径输入，去除内部测试连接按钮）
    if (showWebDavDialog) {
        WebDavConfigDialog(
            initialServerUrl = serverUrl,
            initialUsername = username,
            initialPassword = viewModel.loadCurrentPassword(),
            onSave = { url, user, pass ->
                viewModel.saveWebDavConfig(url, user, pass)
                showWebDavDialog = false
            },
            onDismiss = { showWebDavDialog = false }
        )
    }

    // 3. 清除 WebDAV 配置二次确认弹窗
    if (showClearWebDavConfirmDialog) {
        ConfirmActionSheet(
            title = "清除云端配置",
            message = "确定要移除已保存的 WebDAV 地址、账号与密码吗？清除后将停止云端同步。",
            confirmLabel = "确认清除",
            destructive = true,
            onConfirm = {
                viewModel.clearWebDavConfig()
                showClearWebDavConfirmDialog = false
            },
            onDismiss = { showClearWebDavConfirmDialog = false }
        )
    }

    // 4. 恢复默认私有目录二次确认弹窗
    if (showResetLocalDirConfirmDialog) {
        ConfirmActionSheet(
            title = "恢复默认目录",
            message = "确定要恢复默认的私有存储目录吗？后续自动与手动备份将保存至应用内部沙盒存储。",
            confirmLabel = "恢复默认",
            onConfirm = {
                viewModel.setLocalDirUri(null)
                showResetLocalDirConfirmDialog = false
            },
            onDismiss = { showResetLocalDirConfirmDialog = false }
        )
    }

    // 5. 云端备份记录列表
    if (showCloudListDialog) {
        BackupListDialog(
            title = "云端备份记录",
            source = "云端",
            items = cloudList,
            onRestore = { fileName ->
                showCloudListDialog = false
                viewModel.requestCloudRestore(fileName)
            },
            onShare = { fileName ->
                viewModel.loadRawBackupJson(fileName, isCloud = true) { json ->
                    if (json != null) shareBackupJson(context, fileName, json)
                    else Toast.makeText(context, "读取备份失败", Toast.LENGTH_SHORT).show()
                }
            },
            onDelete = { fileName -> deleteConfirmTarget = Pair(fileName, true) },
            onDismiss = { showCloudListDialog = false }
        )
    }

    // 6. 本地备份记录列表
    if (showLocalListDialog) {
        BackupListDialog(
            title = "本地备份记录",
            source = "本地",
            items = localList,
            onRestore = { fileName ->
                showLocalListDialog = false
                viewModel.requestLocalRestore(fileName)
            },
            onShare = { fileName ->
                viewModel.loadRawBackupJson(fileName, isCloud = false) { json ->
                    if (json != null) shareBackupJson(context, fileName, json)
                    else Toast.makeText(context, "读取备份失败", Toast.LENGTH_SHORT).show()
                }
            },
            onDelete = { fileName -> deleteConfirmTarget = Pair(fileName, false) },
            onDismiss = { showLocalListDialog = false }
        )
    }

    // 7. 删除备份二次确认对话框
    deleteConfirmTarget?.let { (fileName, isCloud) ->
        ConfirmActionSheet(
            title = "删除备份",
            message = "确定要删除${if (isCloud) "云端" else "本地"}备份「$fileName」吗？此操作无法撤销。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                if (isCloud) viewModel.deleteCloud(fileName) else viewModel.deleteLocal(fileName)
                deleteConfirmTarget = null
            },
            onDismiss = { deleteConfirmTarget = null }
        )
    }

    // 8. 恢复预览与分组选择对话框
    val preview = previewSnapshot
    if (preview != null) {
        RestorePreviewDialog(
            snapshot = preview,
            source = previewSource,
            onConfirm = { groups -> viewModel.confirmRestore(groups) },
            onDismiss = { viewModel.dismissPreview() }
        )
    }
}

/**
 * 解析本地备份目录的可读完整路径
 */
private fun getDisplayPath(context: Context, localDirUri: String?): String {
    if (localDirUri.isNullOrBlank()) {
        val defaultDir = File(context.filesDir, BackupFormat.LOCAL_DIR)
        return "默认私有目录 (${defaultDir.absolutePath})"
    }
    return try {
        val uri = Uri.parse(localDirUri)
        val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
        if (docId.startsWith("primary:")) {
            "内部存储/${docId.removePrefix("primary:")}"
        } else {
            val doc = DocumentFile.fromTreeUri(context, uri)
            doc?.name ?: Uri.decode(localDirUri)
        }
    } catch (e: Exception) {
        Uri.decode(localDirUri)
    }
}

/**
 * 备份目标选择对话框（本地 / WebDAV / 同时）
 */
@Composable
private fun BackupTargetDialog(
    isCloudConfigured: Boolean,
    onSelectLocal: () -> Unit,
    onSelectWebDav: () -> Unit,
    onSelectBoth: () -> Unit,
    onConfigureCloud: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "选择备份方式",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 1. 本地备份
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onSelectLocal() }
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("仅本地备份", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "备份至应用内部或自定义存储目录",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 2. WebDAV 云端备份
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable {
                            if (isCloudConfigured) onSelectWebDav() else onConfigureCloud()
                        }
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("仅 WebDAV 云端备份", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (isCloudConfigured) "上传快照至 WebDAV 服务器" else "未配置 WebDAV · 点击前往配置",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 3. 同时备份（推荐）
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable {
                            if (isCloudConfigured) onSelectBoth() else onConfigureCloud()
                        }
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("同时备份（本地 + WebDAV）", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (isCloudConfigured) "双通道同时写入，兼顾快速与云端安全" else "未配置 WebDAV · 点击前往配置",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
    )
}

/**
 * 恢复来源选择对话框
 */
@Composable
private fun RestoreSourceDialog(
    isCloudConfigured: Boolean,
    onSelectCloud: () -> Unit,
    onSelectLocal: () -> Unit,
    onSelectFile: () -> Unit,
    onConfigureCloud: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "选择恢复来源",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 1. 云端备份
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable {
                            if (isCloudConfigured) onSelectCloud() else onConfigureCloud()
                        }
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("WebDAV 云端备份", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (isCloudConfigured) "浏览并恢复已上传到云端的快照" else "未配置 WebDAV · 点击前往配置",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 2. 本地备份
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onSelectLocal() }
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("本地存储备份", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "从应用内部存储或自定义目录中选择快照恢复",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 3. 外部文件导入
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onSelectFile() }
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("从文件导入恢复", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "通过系统文件选择器选取本地 .json 备份文件",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
    )
}

/**
 * 统合 WebDAV 配置表单对话框（固定路径，去除自定义路径输入与测试连接按钮）
 */
@Composable
private fun WebDavConfigDialog(
    initialServerUrl: String,
    initialUsername: String,
    initialPassword: String,
    onSave: (url: String, user: String, pass: String) -> Unit,
    onDismiss: () -> Unit
) {
    var url by remember { mutableStateOf(initialServerUrl) }
    var username by remember { mutableStateOf(initialUsername) }
    var password by remember { mutableStateOf(initialPassword) }
    var passwordVisible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "WebDAV 云同步配置",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    label = { Text("服务器地址") },
                    placeholder = { Text("如 https://dav.jianguoyun.com/dav/") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    singleLine = true,
                    label = { Text("账号 / 邮箱") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    label = { Text("应用密码 / Token") },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                ImageVector.vectorResource(
                                    id = if (passwordVisible) R.drawable.ic_iconoir_eye else R.drawable.ic_iconoir_eye_closed
                                ),
                                contentDescription = if (passwordVisible) "隐藏密码" else "显示密码"
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(url.trim(), username.trim(), password.trim()) },
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
    )
}

/**
 * 备份记录列表对话框（深度优化排版：以时间与概览为主，整行点击进入恢复预览，操作区分明）
 */
@Composable
private fun BackupListDialog(
    title: String,
    source: String,
    items: List<BackupInfo>,
    onRestore: (String) -> Unit,
    onShare: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var showProtect by remember { mutableStateOf(false) }
    val userBackups = remember(items) { items.filter { !it.fileName.startsWith("Protect_") } }
    val protectBackups = remember(items) { items.filter { it.fileName.startsWith("Protect_") } }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .padding(vertical = 16.dp),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                if (userBackups.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            "${userBackups.size} 份快照",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        },
        text = {
            if (userBackups.isEmpty() && protectBackups.isEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 28.dp, horizontal = 16.dp)
                    ) {
                        Text(
                            "暂无备份记录",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "点击主界面「立即备份」即可生成当前快照",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 440.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (userBackups.isEmpty() && protectBackups.isNotEmpty()) {
                        item {
                            Text(
                                text = "暂无主动备份记录",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
                            )
                        }
                    }

                    items(userBackups, key = { it.fileName }) { info ->
                        BackupItemCard(info, source, onRestore, onShare, onDelete)
                    }

                    if (protectBackups.isNotEmpty()) {
                        item {
                            TextButton(
                                onClick = { showProtect = !showProtect },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                            ) {
                                Text(
                                    text = if (showProtect) "收起系统自动保护快照" else "查看 ${protectBackups.size} 条系统自动保护快照",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        if (showProtect) {
                            items(protectBackups, key = { it.fileName }) { info ->
                                BackupItemCard(info, source, onRestore, onShare, onDelete)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
    )
}

@Composable
private fun BackupItemCard(
    info: BackupInfo,
    source: String,
    onRestore: (String) -> Unit,
    onShare: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    val formattedTime = remember(info.createdAt) {
        if (info.createdAt <= 0L) "未知时间" else
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(info.createdAt))
    }
    val sizeTxt = remember(info.sizeBytes) { "%.1f KB".format(info.sizeBytes / 1024f) }
    val isProtect = remember(info.fileName) { info.fileName.startsWith("Protect_") }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onRestore(info.fileName) }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            // 核心信息区（主次分明：加粗完整时间 + 来源大小）
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = formattedTime,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    if (isProtect) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                "保护",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(3.dp))

                Text(
                    text = "$source · $sizeTxt",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.width(6.dp))

            // 右侧操作区：分享、删除、进入详情箭头
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                IconButton(
                    onClick = { onShare(info.fileName) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_share_android),
                        contentDescription = "分享",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(17.dp)
                    )
                }

                IconButton(
                    onClick = { onDelete(info.fileName) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_trash),
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                        modifier = Modifier.size(17.dp)
                    )
                }

                Icon(
                    imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_nav_arrow_right),
                    contentDescription = "恢复",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .padding(start = 2.dp, end = 2.dp)
                        .size(16.dp)
                )
            }
        }
    }
}

/**
 * 恢复预览对话框（支持全选/反选与安全覆盖提醒）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RestorePreviewDialog(
    snapshot: BackupSnapshot,
    source: String,
    onConfirm: (Set<RestoreGroup>) -> Unit,
    onDismiss: () -> Unit
) {
    // 顺序即展示顺序：与规则页的列表分组保持一致（设置 → 分发规则 → 图片规则 → 提取规则 → 历史）
    val allGroups = listOf(
        RestoreGroup.SETTINGS,
        RestoreGroup.RULES,
        RestoreGroup.IMAGE_RULES,
        RestoreGroup.EXTRACTION_PATTERNS,
        RestoreGroup.HISTORY
    )
    var selected by remember { mutableStateOf(allGroups.toSet()) }
    val time = remember(snapshot.createdAt) {
        if (snapshot.createdAt <= 0L) "" else
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(snapshot.createdAt))
    }

    val isAllSelected = selected.size == allGroups.size
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp, top = 0.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "恢复预览",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            // 顶部快照信息
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "来源：$source · 版本 ${snapshot.appVersion}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "备份时间：$time",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "规则 ${snapshot.meta.rulesCount} · 图片规则 ${snapshot.meta.imageRulesCount} · 提取规则 ${snapshot.meta.patternsCount} · 历史 ${snapshot.meta.historyCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
            }

            // 安全提示
            Surface(
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "⚠️ 恢复将覆盖当前所选分组数据。系统已自动生成本地保护快照，确保数据可溯源。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(10.dp)
                )
            }

            // 全选与反选快捷操作
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "选择恢复分组",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = {
                        selected = if (isAllSelected) emptySet() else allGroups.toSet()
                    }
                ) {
                    Text(if (isAllSelected) "取消全选" else "全选")
                }
            }

            // 各分组 Checkbox
            allGroups.forEach { group ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            selected = if (group in selected) selected - group else selected + group
                        }
                        .padding(vertical = 2.dp)
                ) {
                    Checkbox(
                        checked = group in selected,
                        onCheckedChange = {
                            selected = if (group in selected) selected - group else selected + group
                        }
                    )
                    Text(group.label, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Spacer(Modifier.height(8.dp))

            // 底部水平双按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("取消", style = MaterialTheme.typography.labelLarge)
                }
                Button(
                    onClick = { onConfirm(selected) },
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("确认恢复", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/**
 * 通过 FileProvider 将备份作为 .json 文件附件分享到外部应用（微信、QQ、网盘、文件管理器等）
 */
private fun shareBackupJson(context: Context, fileName: String, json: String) {
    try {
        val shareDir = File(context.cacheDir, "shared_backups").apply { if (!exists()) mkdirs() }
        val finalFileName = if (fileName.endsWith(".json")) fileName else "$fileName.json"
        val shareFile = File(shareDir, finalFileName)
        shareFile.writeText(json, Charsets.UTF_8)

        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            shareFile
        )

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, finalFileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val shareIntent = Intent.createChooser(sendIntent, "分享备份文件：$finalFileName")
        context.startActivity(shareIntent)
    } catch (e: Exception) {
        Toast.makeText(context, "分享失败：${e.message}", Toast.LENGTH_SHORT).show()
    }
}

