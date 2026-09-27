package com.moting.linkgo.ui.settings

import android.content.ClipData
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moting.linkgo.LinkGoApp
import com.moting.linkgo.R
import com.moting.linkgo.clipboard.ClipboardBackend
import com.moting.linkgo.clipboard.ClipboardChangeContract
import com.moting.linkgo.clipboard.ClipboardMonitorController
import com.moting.linkgo.data.SettingsRepository
import com.moting.linkgo.service.ClipboardMonitorService
import com.moting.linkgo.ui.components.*
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * 剪贴板后台监听配置 (二级菜单)
 * 精确感知并反映【当前监听模式的真实运行状态】，展示技术链路原理与底层状态诊断
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipboardMonitorSettingsScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { SettingsRepository(context) }

    val enabled by repository.clipboardMonitorEnabled.collectAsState(initial = repository.currentClipboardMonitorEnabled)
    val backend by repository.clipboardMonitorBackend.collectAsState(initial = repository.currentClipboardMonitorBackend)
    val autoDismissSeconds by repository.clipboardAutoDismissSeconds.collectAsState(initial = 5)
    val maxMultiCapsuleCount by repository.maxMultiCapsuleCount.collectAsState(initial = repository.currentMaxMultiCapsuleCount)
    val clearAfterJump by repository.clearClipboardAfterJump.collectAsState(initial = false)
    val clipboardChangeToastEnabled by repository.clipboardChangeToastEnabled.collectAsState(initial = repository.currentClipboardChangeToastEnabled)
    val clipboardBroadcastEnabled by repository.clipboardBroadcastEnabled.collectAsState(initial = repository.currentClipboardBroadcastEnabled)
    val capsuleDismissOnTouchOutside by repository.capsuleDismissOnTouchOutside.collectAsState(initial = repository.currentCapsuleDismissOnTouchOutside)
    val lastPrivilege by repository.lastSystemPrivilege.collectAsState(initial = repository.currentLastSystemPrivilege)
    val lastWay by repository.lastSystemWay.collectAsState(initial = repository.currentLastSystemWay)

    // 底层真实状态与权限检测
    var isLsposedActive by remember { mutableStateOf(LinkGoApp.xposedService != null) }
    var isShizukuAlive by remember { mutableStateOf(runCatching { Shizuku.pingBinder() }.getOrDefault(false)) }
    var isShizukuGranted by remember {
        mutableStateOf(
            runCatching {
                Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
        )
    }
    var rootAvailable by remember { mutableStateOf<Boolean?>(null) }
    var isServiceRunning by remember { mutableStateOf(ClipboardMonitorService.isRunning) }
    var isOverlayGranted by remember { mutableStateOf(android.provider.Settings.canDrawOverlays(context)) }
    var isNotificationGranted by remember {
        mutableStateOf(androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    var isBatteryOptimized by remember {
        mutableStateOf(com.moting.linkgo.util.KeepAliveHelper.isBatteryOptimizationsIgnored(context))
    }

    // 权限状态统一订阅权限中心（单源；不再各自轮询计算）
    val permissionStates by com.moting.linkgo.util.privilege.PermissionCenter.states.collectAsState()
    LaunchedEffect(permissionStates) {
        isLsposedActive = permissionStates[com.moting.linkgo.util.privilege.PermissionItem.LSPOSED_HOOK] == com.moting.linkgo.util.privilege.PermissionCenter.Status.GRANTED
        isShizukuGranted = permissionStates[com.moting.linkgo.util.privilege.PermissionItem.SHIZUKU] == com.moting.linkgo.util.privilege.PermissionCenter.Status.GRANTED
        isOverlayGranted = permissionStates[com.moting.linkgo.util.privilege.PermissionItem.OVERLAY] == com.moting.linkgo.util.privilege.PermissionCenter.Status.GRANTED
        isNotificationGranted = permissionStates[com.moting.linkgo.util.privilege.PermissionItem.POST_NOTIFICATIONS] == com.moting.linkgo.util.privilege.PermissionCenter.Status.GRANTED
        isBatteryOptimized = permissionStates[com.moting.linkgo.util.privilege.PermissionItem.BATTERY_WHITELIST] == com.moting.linkgo.util.privilege.PermissionCenter.Status.GRANTED
        rootAvailable = when (permissionStates[com.moting.linkgo.util.privilege.PermissionItem.ROOT]) {
            com.moting.linkgo.util.privilege.PermissionCenter.Status.GRANTED -> true
            com.moting.linkgo.util.privilege.PermissionCenter.Status.UNAVAILABLE -> false
            else -> null
        }
    }

    // 低频兜底刷新（3s）：覆盖"用户跳到系统设置改了权限再回来"的外部变化 + Shizuku 活性探测
    LaunchedEffect(Unit) {
        while (true) {
            com.moting.linkgo.util.privilege.PermissionCenter.refresh(context)
            isShizukuAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
            isServiceRunning = ClipboardMonitorService.isRunning
            delay(3000)
        }
    }

    val isLsposed = backend == ClipboardBackend.LSPOSED
    val isNone = backend == ClipboardBackend.NONE
    val isSystem = !isLsposed && !isNone
    val effectiveChannel = com.moting.linkgo.util.privilege.PrivilegeEngine.currentChannel()
    val privilege = if (isSystem) {
        if (effectiveChannel == com.moting.linkgo.util.privilege.PrivilegeEngine.Channel.ROOT) "root" else "shizuku"
    } else lastPrivilege
    val way = if (isSystem) ClipboardBackend.wayOf(backend) else lastWay

    // 动态计算【当前模式的真实运行状态】
    val (statusTitle, statusBadge, statusColor, statusMessage, needShizukuAuth) = remember(
        enabled, backend, isLsposedActive, isShizukuAlive, isShizukuGranted, rootAvailable, isServiceRunning, effectiveChannel
    ) {
        if (!enabled) {
            Tuple5(
                "监听已停用",
                "已关闭",
                Color(0xFF9E9E9E),
                "后台剪贴板监听总开关处于关闭状态，不会监听或分发剪贴板中的链接。",
                false
            )
        } else {
            when {
                // 1. LSPosed 模式
                backend == ClipboardBackend.LSPOSED -> {
                    if (isLsposedActive) {
                        Tuple5(
                            "LSPosed · Hook 监听中",
                            "正常运行",
                            Color(0xFF4CAF50),
                            "系统层 Hook 已生效。当系统写入剪贴板时直接捕获数据与来源应用，无需前台常驻服务。",
                            false
                        )
                    } else {
                        Tuple5(
                            "LSPosed · 模块未激活",
                            "未生效",
                            Color(0xFFFF9800),
                            "尚未检测到系统层 Hook 服务。请在 LSPosed 管理器中启用 LinkGo 模块，勾选「系统框架 (Android Framework)」并重启手机。",
                            false
                        )
                    }
                }

                // 2. Shizuku 方案
                privilege == "shizuku" -> {
                    when {
                        !isShizukuAlive -> {
                            Tuple5(
                                "Shizuku · 服务未连接",
                                "服务离线",
                                Color(0xFFF44336),
                                "未检测到正在运行的 Shizuku 服务进程，请先在 Shizuku 应用中启动服务。",
                                false
                            )
                        }
                        !isShizukuGranted -> {
                            Tuple5(
                                "Shizuku · 尚未授权",
                                "缺少权限",
                                Color(0xFFFF9800),
                                "已连接 Shizuku 但尚未授予 LinkGo 访问权限，点击下方按钮完成授权。",
                                true
                            )
                        }
                        isServiceRunning -> {
                            Tuple5(
                                "Shizuku · 正常监听中",
                                "前台服务运行中",
                                Color(0xFF4CAF50),
                                if (way == "hidden_api")
                                    "已通过 Shizuku Binder 成功注册 IClipboard.IOnPrimaryClipChangedListener 隐藏监听器。"
                                else
                                    "已通过 Shizuku 启动 logcat 异步管道流正则过滤剪贴板变动事件。",
                                false
                            )
                        }
                        else -> {
                            Tuple5(
                                "Shizuku · 服务启动中",
                                "正在就绪",
                                Color(0xFFFF9800),
                                "Shizuku 权限已就绪，正在拉起前台保活服务...",
                                false
                            )
                        }
                    }
                }

                // 3. Root 方案
                privilege == "root" -> {
                    when {
                        rootAvailable == false -> {
                            Tuple5(
                                "Root · 权限不可用",
                                "无 Root 权限",
                                Color(0xFFF44336),
                                "无法执行 su 提权命令。请确认设备已获得 Root 且已授权给 LinkGo。",
                                false
                            )
                        }
                        isServiceRunning -> {
                            Tuple5(
                                "Root · 正常监听中",
                                "前台服务运行中",
                                Color(0xFF4CAF50),
                                if (way == "hidden_api")
                                    "已通过 app_process 派生 Root 守护进程反射绑定系统剪贴板服务。"
                                else
                                    "已通过 Root 管道流异步读取 logcat 过滤剪贴板变动事件。",
                                false
                            )
                        }
                        else -> {
                            Tuple5(
                                "Root · 服务启动中",
                                "正在拉起",
                                Color(0xFFFF9800),
                                "正在派生 Root 守护进程并绑定前台服务...",
                                false
                            )
                        }
                    }
                }

                // 4. 不处理 (None)
                else -> {
                    if (isServiceRunning) {
                        Tuple5(
                            "标准 SDK · 监听中",
                            "前台服务运行中",
                            Color(0xFF4CAF50),
                            "正在通过标准 ClipboardManager 监听公开剪贴板事件（需系统赋予后台读取特权）。",
                            false
                        )
                    } else {
                        Tuple5(
                            "标准 SDK · 未运行",
                            "服务未拉起",
                            Color(0xFF9E9E9E),
                            "标准监听服务未运行，请尝试重新打开总开关。",
                            false
                        )
                    }
                }
            }
        }
    }

    fun selectBackend(newBackend: String) {
        scope.launch {
            repository.updateClipboardMonitorBackend(newBackend)
            if (enabled) ClipboardMonitorController.apply(context, true, newBackend)
        }
    }

    fun toggleEnabled(checked: Boolean) {
        scope.launch {
            repository.updateClipboardMonitorEnabled(checked)
            ClipboardMonitorController.apply(context, checked, backend)
        }
    }

    Box(Modifier.fillMaxSize()) {
        CollapsingTopBarScaffold(
            title = "剪贴板后台监听",
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
                    Spacer(Modifier.height(4.dp))
                }

                // 1. 后台监听控制与工作状态（合并板块）
                item {
                    SettingsSection(topLabel = "后台监听状态") {
                        SettingItem(
                            headlineText = "启用后台监听",
                            supportingText = "复制链接后自动触发规则与分发",
                            trailingContent = {
                                PremiumSwitch(
                                    checked = enabled,
                                    onCheckedChange = { toggleEnabled(it) }
                                )
                            }
                        )

                        SettingsSectionDivider()

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                                .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(statusColor)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = statusTitle,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = statusColor.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = statusBadge,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp
                                        ),
                                        color = statusColor,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            Spacer(Modifier.height(6.dp))

                            Text(
                                text = statusMessage,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    lineHeight = 17.sp,
                                    fontSize = 12.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            if (needShizukuAuth) {
                                Spacer(Modifier.height(10.dp))
                                Button(
                                    onClick = {
                                        scope.launch {
                                            val result = com.moting.linkgo.util.privilege.PermissionCenter.grant(
                                                context,
                                                com.moting.linkgo.util.privilege.PermissionItem.SHIZUKU
                                            )
                                            com.moting.linkgo.util.privilege.PermissionCenter.refresh(context)
                                            Toast.makeText(
                                                context,
                                                if (result == com.moting.linkgo.util.privilege.PermissionCenter.Result.Granted ||
                                                    result == com.moting.linkgo.util.privilege.PermissionCenter.Result.NeedsRuntimeRequest
                                                ) "授权请求已发出，请在弹窗中确认" else "请求授权失败，请检查 Shizuku 服务",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Text("请求 Shizuku 授权", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // 2. 监听模式（4个平级选项）
                item {
                    SettingsSection(topLabel = "监听模式") {
                        val currentPrivChannel = com.moting.linkgo.util.privilege.PrivilegeEngine.currentChannel()
                        val channelStatusText = when (currentPrivChannel) {
                            com.moting.linkgo.util.privilege.PrivilegeEngine.Channel.ROOT -> "Root 模式 (su 提权)"
                            com.moting.linkgo.util.privilege.PrivilegeEngine.Channel.SHIZUKU -> "Shizuku 模式 (免Root)"
                            com.moting.linkgo.util.privilege.PrivilegeEngine.Channel.NONE -> "无可用通道 (请在权限中心开启)"
                        }

                        // 选项 1：LSPosed
                        SettingItem(
                            headlineText = "LSPosed（系统层 Hook）",
                            supportingText = "Hook ClipboardService 写入接口，零常驻",
                            trailingContent = {
                                RadioButton(
                                    selected = isLsposed,
                                    onClick = { selectBackend(ClipboardBackend.LSPOSED) },
                                    colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
                                )
                            },
                            modifier = Modifier.clickable { selectBackend(ClipboardBackend.LSPOSED) }
                        )

                        AnimatedVisibility(
                            visible = isLsposed,
                            enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                            exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                TechnicalDetailCard(
                                    title = "技术原理：系统服务注入 (Hook)",
                                    description = "基于 Xposed 框架在 system_server 进程中 Hook ClipboardService.setPrimaryClip 内部方法。在剪贴板发生写入时直接捕获数据与来源应用 UID，通过 IPC 广播即时通知 LinkGo，无需任何前台服务或常驻进程。"
                                )
                            }
                        }

                        SettingsSectionDivider()

                        // 选项 2：系统隐藏 API
                        val isHiddenApiSelected = ClipboardBackend.isHiddenApi(backend)
                        SettingItem(
                            headlineText = "系统隐藏 API（系统提权）",
                            supportingText = "注册系统底层隐藏监听器，首选推荐",
                            trailingContent = {
                                RadioButton(
                                    selected = isHiddenApiSelected,
                                    onClick = {
                                        val targetBackend = ClipboardBackend.deriveSystemBackend(currentPrivChannel, "hidden_api")
                                        selectBackend(targetBackend)
                                    },
                                    colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
                                )
                            },
                            modifier = Modifier.clickable {
                                val targetBackend = ClipboardBackend.deriveSystemBackend(currentPrivChannel, "hidden_api")
                                selectBackend(targetBackend)
                            }
                        )

                        AnimatedVisibility(
                            visible = isHiddenApiSelected,
                            enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                            exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (currentPrivChannel != com.moting.linkgo.util.privilege.PrivilegeEngine.Channel.NONE)
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                    else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = ImageVector.vectorResource(R.drawable.ic_iconoir_terminal),
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = if (currentPrivChannel != com.moting.linkgo.util.privilege.PrivilegeEngine.Channel.NONE)
                                                MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.error
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = "提权通道跟随全局设置：$channelStatusText",
                                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                            color = if (currentPrivChannel != com.moting.linkgo.util.privilege.PrivilegeEngine.Channel.NONE)
                                                MaterialTheme.colorScheme.onSurface
                                            else MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                }
                                TechnicalDetailCard(
                                    title = if (privilege == "root") "技术原理：Root 独立守护进程反射" else "技术原理：Shizuku 隐藏 API 反射",
                                    description = if (privilege == "root")
                                        "通过 app_process 启动 Root 守护进程反射监听系统剪贴板并回传，稳定性高，需前台服务保活。"
                                    else
                                        "通过 Shizuku Binder 接口反射注册系统剪贴板监听器。剪贴板变动时由系统直接回调，无需轮询，需前台服务保活。"
                                )
                            }
                        }

                        SettingsSectionDivider()

                        // 选项 3：系统日志读取
                        val isLogsSelected = !isLsposed && !isNone && !isHiddenApiSelected
                        SettingItem(
                            headlineText = "系统日志读取（系统提权）",
                            supportingText = "读取 logcat 过滤剪贴板变动，备用方案",
                            trailingContent = {
                                RadioButton(
                                    selected = isLogsSelected,
                                    onClick = {
                                        val targetBackend = ClipboardBackend.deriveSystemBackend(currentPrivChannel, "logs")
                                        selectBackend(targetBackend)
                                    },
                                    colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
                                )
                            },
                            modifier = Modifier.clickable {
                                val targetBackend = ClipboardBackend.deriveSystemBackend(currentPrivChannel, "logs")
                                selectBackend(targetBackend)
                            }
                        )

                        AnimatedVisibility(
                            visible = isLogsSelected,
                            enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                            exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (currentPrivChannel != com.moting.linkgo.util.privilege.PrivilegeEngine.Channel.NONE)
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                    else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = ImageVector.vectorResource(R.drawable.ic_iconoir_terminal),
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = if (currentPrivChannel != com.moting.linkgo.util.privilege.PrivilegeEngine.Channel.NONE)
                                                MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.error
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = "提权通道跟随全局设置：$channelStatusText",
                                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                            color = if (currentPrivChannel != com.moting.linkgo.util.privilege.PrivilegeEngine.Channel.NONE)
                                                MaterialTheme.colorScheme.onSurface
                                            else MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                }
                                TechnicalDetailCard(
                                    title = if (privilege == "root") "技术原理：Root 日志管道过滤" else "技术原理：Shizuku 日志管道过滤",
                                    description = if (privilege == "root")
                                        "通过 Root 实时读取系统日志过滤剪贴板事件，作为备用方案。"
                                    else
                                        "通过 Shizuku 读取系统日志实时匹配剪贴板事件，作为免反射的通用兼容方案。"
                                )
                            }
                        }

                        SettingsSectionDivider()

                        // 选项 4：标准公开监听（不提权）
                        SettingItem(
                            headlineText = "标准公开监听（免提权）",
                            supportingText = "仅调用标准 SDK ClipboardManager 监听接口",
                            trailingContent = {
                                RadioButton(
                                    selected = isNone,
                                    onClick = { selectBackend(ClipboardBackend.NONE) },
                                    colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
                                )
                            },
                            modifier = Modifier.clickable { selectBackend(ClipboardBackend.NONE) }
                        )

                        AnimatedVisibility(
                            visible = isNone,
                            enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                            exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                TechnicalDetailCard(
                                    title = "技术原理：标准 Android SDK 监听",
                                    description = "直接调用标准 Android SDK 的 ClipboardManager.OnPrimaryClipChangedListener 接口。在 Android 10+ 系统中受后台剪贴板限制，需系统或第三方 ROM 赋予 LinkGo 后台读取特权方可生效。"
                                )
                            }
                        }
                    }
                }

                // 3.5 运行环境与权限健康诊断
                item {
                    SettingsSection(topLabel = "运行环境与权限诊断") {
                        // 悬浮窗权限
                        SettingItem(
                            headlineText = "悬浮窗权限 (Display Overlays)",
                            supportingText = if (isOverlayGranted) "已授予：悬浮胶囊可极速渲染与显示" else "未授予：胶囊将无法展示（或降级至无障碍）",
                            trailingContent = {
                                if (isOverlayGranted) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color(0xFF4CAF50).copy(alpha = 0.15f)
                                    ) {
                                        Text(
                                            "已授权",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = Color(0xFF4CAF50),
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                } else {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (rootAvailable == true || isShizukuGranted) {
                                            FilledTonalButton(
                                                onClick = {
                                                    scope.launch {
                                                        val result = com.moting.linkgo.util.privilege.PermissionCenter.grant(
                                                            context,
                                                            com.moting.linkgo.util.privilege.PermissionItem.OVERLAY
                                                        )
                                                        when (result) {
                                                            com.moting.linkgo.util.privilege.PermissionCenter.Result.Granted -> {
                                                                isOverlayGranted = true
                                                                Toast.makeText(context, "已通过提权通道成功授予悬浮窗权限！", Toast.LENGTH_SHORT).show()
                                                            }
                                                            com.moting.linkgo.util.privilege.PermissionCenter.Result.NeedsSystemSettings ->
                                                                Toast.makeText(context, "请在弹出的系统设置中开启悬浮窗", Toast.LENGTH_SHORT).show()
                                                            else ->
                                                                Toast.makeText(context, "自动授权失败，请手动前往设置开启", Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                },
                                                shape = RoundedCornerShape(10.dp),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                modifier = Modifier.height(32.dp)
                                            ) {
                                                Text("⚡ 一键授权", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                runCatching {
                                                    val intent = android.content.Intent(
                                                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                        android.net.Uri.parse("package:${context.packageName}")
                                                    ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                                                    context.startActivity(intent)
                                                }
                                            },
                                            shape = RoundedCornerShape(10.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Text("去设置", fontSize = 11.5.sp)
                                        }
                                    }
                                }
                            }
                        )

                        SettingsSectionDivider()

                        // 通知权限
                        SettingItem(
                            headlineText = "通知权限 (Post Notifications)",
                            supportingText = if (isNotificationGranted) "已开启：支持实时活动与状态栏通知胶囊" else "未开启：无法接收实时状态栏跳转通知",
                            trailingContent = {
                                if (isNotificationGranted) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color(0xFF4CAF50).copy(alpha = 0.15f)
                                    ) {
                                        Text(
                                            "已开启",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = Color(0xFF4CAF50),
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                } else {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (rootAvailable == true || isShizukuGranted) {
                                            FilledTonalButton(
                                                onClick = {
                                                    scope.launch {
                                                        val result = com.moting.linkgo.util.privilege.PermissionCenter.grant(
                                                            context,
                                                            com.moting.linkgo.util.privilege.PermissionItem.POST_NOTIFICATIONS
                                                        )
                                                        when (result) {
                                                            com.moting.linkgo.util.privilege.PermissionCenter.Result.Granted -> {
                                                                isNotificationGranted = true
                                                                Toast.makeText(context, "已通过提权通道成功开启通知权限！", Toast.LENGTH_SHORT).show()
                                                            }
                                                            com.moting.linkgo.util.privilege.PermissionCenter.Result.NeedsSystemSettings ->
                                                                Toast.makeText(context, "请在弹出的系统设置中开启通知", Toast.LENGTH_SHORT).show()
                                                            else ->
                                                                Toast.makeText(context, "自动开启通知失败，请手动开启", Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                },
                                                shape = RoundedCornerShape(10.dp),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                modifier = Modifier.height(32.dp)
                                            ) {
                                                Text("⚡ 一键开启", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                runCatching {
                                                    val intent = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                                        putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                                                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    }
                                                    context.startActivity(intent)
                                                }
                                            },
                                            shape = RoundedCornerShape(10.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Text("去设置", fontSize = 11.5.sp)
                                        }
                                    }
                                }
                            }
                        )

                        SettingsSectionDivider()

                        // 电池优化白名单
                        SettingItem(
                            headlineText = "电池优化白名单 (Battery Whitelist)",
                            supportingText = if (isBatteryOptimized) "已加入白名单：息屏与低电量下防杀保活" else "未加入：系统息屏后后台前台服务可能被杀",
                            trailingContent = {
                                if (isBatteryOptimized) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color(0xFF4CAF50).copy(alpha = 0.15f)
                                    ) {
                                        Text(
                                            "已加入",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = Color(0xFF4CAF50),
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = {
                                            com.moting.linkgo.util.KeepAliveHelper.requestIgnoreBatteryOptimizations(context)
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Text("去设置", fontSize = 11.5.sp)
                                    }
                                }
                            }
                        )

                        SettingsSectionDivider()

                        // 厂商自启动与后台保护引导
                        SettingItem(
                            headlineText = "自启动与后台保护管理",
                            supportingText = "引导前往系统设置开启“自启动”与“无限制后台运行”",
                            trailingContent = {
                                OutlinedButton(
                                    onClick = {
                                        com.moting.linkgo.util.KeepAliveHelper.openAutoStartSetting(context)
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text("去设置", fontSize = 11.5.sp)
                                }
                            }
                        )
                    }
                }

                // 4. 后续流转行为
                item {
                    SettingsSection(topLabel = "后续流转行为") {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                            Text(
                                text = "悬浮胶囊自动消失时长: $autoDismissSeconds 秒",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "侧边悬浮胶囊弹出后无操作时自动收起消失的时长",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                            Spacer(Modifier.height(12.dp))
                            PremiumSlider(
                                value = autoDismissSeconds.toFloat(),
                                onValueChange = { scope.launch { repository.updateClipboardAutoDismissSeconds(it.roundToInt()) } },
                                valueRange = 2f..30f,
                                steps = 27
                            )
                        }

                        SettingsSectionDivider()

                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                            Text(
                                text = "多链接胶囊展示数量: $maxMultiCapsuleCount 个",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = if (maxMultiCapsuleCount == 1) {
                                    "仅展示单个汇总胶囊（点击打开链接选择弹窗）"
                                } else {
                                    "纵向阵列展示前 ${maxMultiCapsuleCount - 1} 条具体链接及末尾汇总胶囊"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                            Spacer(Modifier.height(12.dp))
                            PremiumSlider(
                                value = maxMultiCapsuleCount.toFloat(),
                                onValueChange = { scope.launch { repository.updateMaxMultiCapsuleCount(it.roundToInt()) } },
                                valueRange = 1f..10f,
                                steps = 8
                            )
                        }

                        SettingsSectionDivider()

                        SettingItem(
                            headlineText = "点击外部自动隐藏",
                            supportingText = "点击胶囊外的屏幕任意区域时胶囊立即退出",
                            trailingContent = {
                                PremiumSwitch(
                                    checked = capsuleDismissOnTouchOutside,
                                    onCheckedChange = { scope.launch { repository.updateCapsuleDismissOnTouchOutside(it) } }
                                )
                            }
                        )

                        SettingsSectionDivider()

                        SettingItem(
                            headlineText = "跳转后清空剪贴板",
                            supportingText = "成功匹配分发后自动抹除剪贴板内容",
                            trailingContent = {
                                PremiumSwitch(
                                    checked = clearAfterJump,
                                    onCheckedChange = { scope.launch { repository.updateClearClipboardAfterJump(it) } }
                                )
                            }
                        )

                        SettingsSectionDivider()

                        SettingItem(
                            headlineText = "剪贴板变动提示",
                            supportingText = "剪贴板变动时即时提示“已复制”",
                            trailingContent = {
                                PremiumSwitch(
                                    checked = clipboardChangeToastEnabled,
                                    onCheckedChange = { scope.launch { repository.updateClipboardChangeToastEnabled(it) } }
                                )
                            }
                        )

                        SettingsSectionDivider()

                        SettingItem(
                            headlineText = "剪贴板变动广播",
                            supportingText = "向其他应用广播剪贴板变动事件",
                            trailingContent = {
                                PremiumSwitch(
                                    checked = clipboardBroadcastEnabled,
                                    onCheckedChange = { scope.launch { repository.updateClipboardBroadcastEnabled(it) } }
                                )
                            }
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = ClipboardChangeContract.ACTION_CLIPBOARD_CHANGED,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = {
                                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                    cm?.setPrimaryClip(ClipData.newPlainText("广播动作", ClipboardChangeContract.ACTION_CLIPBOARD_CHANGED))
                                    Toast.makeText(context, "广播动作已复制", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Icon(
                                    imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_copy),
                                    contentDescription = "复制广播动作",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

/**
 * 状态元组
 */
private data class Tuple5<A, B, C, D, E>(
    val a: A,
    val b: B,
    val c: C,
    val d: D,
    val e: E
)

/**
 * 技术链路原理说明卡片
 */
@Composable
private fun TechnicalDetailCard(
    title: String,
    description: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.5.sp
                ),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}