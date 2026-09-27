package com.moting.linkgo.ui.settings

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.moting.linkgo.R
import com.moting.linkgo.data.NotificationStyle
import com.moting.linkgo.data.SettingsRepository
import com.moting.linkgo.ui.components.*
import com.moting.linkgo.util.HyperIslandHelper
import com.moting.linkgo.util.SuperIslandLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 通知设置 (二级菜单)
 * 包含跳转通知开关、通知样式 (标准通知 / 实时活动 / 小米超级岛)、通知自动清除时长滑轨、断网时长滑轨、超级岛高级定制项、触发提示 (Toast) 与权限诊断
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { SettingsRepository(context) }

    val showRichNotification by repository.showRichNotification.collectAsState(initial = repository.currentShowRichNotification)
    val notificationStyle by repository.notificationStyle.collectAsState(initial = repository.currentNotificationStyle)
    val notificationAutoDismissSeconds by repository.notificationAutoDismissSeconds.collectAsState(initial = repository.currentNotificationAutoDismissSeconds)
    val showTriggerToast by repository.showTriggerToast.collectAsState(initial = repository.currentShowTriggerToast)

    // 超级岛专属配置
    val superIslandBypassEnabled by repository.superIslandBypassEnabled.collectAsState(initial = repository.currentSuperIslandBypassEnabled)
    val superIslandBypassDurationMs by repository.superIslandBypassDurationMs.collectAsState(initial = repository.currentSuperIslandBypassDurationMs)
    val superIslandOuterGlow by repository.superIslandOuterGlow.collectAsState(initial = repository.currentSuperIslandOuterGlow)
    val superIslandDragShareEnabled by repository.superIslandDragShareEnabled.collectAsState(initial = repository.currentSuperIslandDragShareEnabled)
    val superIslandLoggingEnabled by repository.superIslandLoggingEnabled.collectAsState(initial = repository.currentSuperIslandLoggingEnabled)

    var logFileSizeText by remember { mutableStateOf(SuperIslandLogManager.getLogFileSizeText(context)) }
    var isTestingIsland by remember { mutableStateOf(false) }

    LaunchedEffect(superIslandLoggingEnabled) {
        logFileSizeText = SuperIslandLogManager.getLogFileSizeText(context)
    }

    var isNotificationGranted by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }

    // 周期检测通知权限状态
    LaunchedEffect(Unit) {
        while (true) {
            isNotificationGranted = NotificationManagerCompat.from(context).areNotificationsEnabled()
            delay(1500)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        isNotificationGranted = isGranted
        if (isGranted) {
            scope.launch { repository.updateShowRichNotification(true) }
        }
    }

    CollapsingTopBarScaffold(
        title = "通知与超级岛",
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

            // 1. 核心跳转通知
            item {
                SettingsSection(topLabel = "跳转感知") {
                    SettingItem(
                        headlineText = "启用跳转通知",
                        supportingText = "规则分发时发送通知卡片与快捷操作",
                        trailingContent = {
                            PremiumSwitch(
                                checked = showRichNotification,
                                onCheckedChange = { checked ->
                                    if (checked && Build.VERSION.SDK_INT >= 33) {
                                        val hasPermission = ContextCompat.checkSelfPermission(
                                            context, Manifest.permission.POST_NOTIFICATIONS
                                        ) == PackageManager.PERMISSION_GRANTED
                                        if (!hasPermission) {
                                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                            return@PremiumSwitch
                                        }
                                    }
                                    scope.launch { repository.updateShowRichNotification(checked) }
                                }
                            )
                        }
                    )
                }
            }

            // 2. 通知样式板块（内嵌滑轨与顺滑展开动画）
            item {
                SettingsSection(topLabel = "通知样式") {
                    Box(modifier = Modifier.padding(14.dp)) {
                        PremiumSegmentedRow(
                            options = listOf(
                                NotificationStyle.STANDARD,
                                NotificationStyle.LIVE_ACTIVITY,
                                NotificationStyle.SUPER_ISLAND
                            ),
                            selectedOption = notificationStyle,
                            onOptionSelected = { selected ->
                                scope.launch { repository.updateNotificationStyle(selected) }
                            },
                            labelProvider = { it.title }
                        )
                    }

                    val styleHint = when (notificationStyle) {
                        NotificationStyle.STANDARD -> "非常驻通知卡片，支持快捷操作"
                        NotificationStyle.LIVE_ACTIVITY -> if (Build.VERSION.SDK_INT >= 36) {
                            "Android 16+ 状态栏动态胶囊"
                        } else {
                            "系统版本低于 Android 16，将降级为标准通知"
                        }
                        NotificationStyle.SUPER_ISLAND -> if (HyperIslandHelper.isEligibleDevice()) {
                            "HyperOS 状态栏超级岛动态胶囊"
                        } else {
                            "非 HyperOS 设备，将降级为标准通知"
                        }
                    }

                    Text(
                        text = styleHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)
                    )

                    SettingsSectionDivider()

                    // 通用：自动清除通知时长滑轨
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        Text(
                            text = "自动清除通知时长: $notificationAutoDismissSeconds 秒",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "通知展示后自动撤回消除（统一管理标准通知、实时活动与超级岛）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                        Spacer(Modifier.height(12.dp))
                        PremiumSlider(
                            value = notificationAutoDismissSeconds.toFloat(),
                            onValueChange = { scope.launch { repository.updateNotificationAutoDismissSeconds(it.roundToInt()) } },
                            valueRange = 2f..30f,
                            steps = 27
                        )
                    }

                    // 小米超级岛：展开专属高级定制项
                    AnimatedVisibility(
                        visible = (notificationStyle == NotificationStyle.SUPER_ISLAND),
                        enter = expandVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                        exit = shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()
                    ) {
                        Column {
                            SettingsSectionDivider()

                            // 绕过超级岛限制 (仅 Shizuku)
                            SettingItem(
                                headlineText = "绕过超级岛限制 (仅 Shizuku)",
                                supportingText = "通过 Shizuku 阻断云端白名单校验",
                                trailingContent = {
                                    PremiumSwitch(
                                        checked = superIslandBypassEnabled,
                                        onCheckedChange = { enabled ->
                                            scope.launch { repository.updateSuperIslandBypassEnabled(enabled) }
                                        }
                                    )
                                }
                            )

                            // XMSF 断网时长滑轨
                            AnimatedVisibility(
                                visible = superIslandBypassEnabled,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                Column {
                                    SettingsSectionDivider()
                                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                                        Text(
                                            text = "XMSF 断网盲窗时长: $superIslandBypassDurationMs ms",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = "等待系统离线放行盲窗 (建议 100 ms)",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                        )
                                        Spacer(Modifier.height(12.dp))
                                        PremiumSlider(
                                            value = superIslandBypassDurationMs.toFloat(),
                                            onValueChange = { scope.launch { repository.updateSuperIslandBypassDurationMs(it.roundToInt()) } },
                                            valueRange = 50f..500f,
                                            steps = 8
                                        )
                                    }
                                }
                            }

                            SettingsSectionDivider()

                            // 外发光效果
                            SettingItem(
                                headlineText = "外发光效果",
                                supportingText = "超级岛胶囊边框外发光高亮",
                                trailingContent = {
                                    PremiumSwitch(
                                        checked = superIslandOuterGlow,
                                        onCheckedChange = { enabled ->
                                            scope.launch { repository.updateSuperIslandOuterGlow(enabled) }
                                        }
                                    )
                                }
                            )

                            SettingsSectionDivider()

                            // 支持拖曳分享链接
                            SettingItem(
                                headlineText = "支持拖曳分享链接",
                                supportingText = "长按超级岛卡片可直接拖曳分享纯文本链接",
                                trailingContent = {
                                    PremiumSwitch(
                                        checked = superIslandDragShareEnabled,
                                        onCheckedChange = { enabled ->
                                            scope.launch { repository.updateSuperIslandDragShareEnabled(enabled) }
                                        }
                                    )
                                }
                            )

                            SettingsSectionDivider()

                            // 测试超级岛通知
                            SettingItem(
                                headlineText = "测试超级岛通知",
                                supportingText = "发送测试通知并退至后台",
                                trailingContent = {
                                    Button(
                                        onClick = {
                                            if (isTestingIsland) return@Button
                                            scope.launch {
                                                if (!isNotificationGranted) {
                                                    Toast.makeText(context, "请先开启 LinkGo 通知权限", Toast.LENGTH_SHORT).show()
                                                    return@launch
                                                }
                                                if (superIslandBypassEnabled && !HyperIslandHelper.isShizukuAvailable) {
                                                    Toast.makeText(context, "请先启动并授权 Shizuku", Toast.LENGTH_SHORT).show()
                                                    return@launch
                                                }

                                                isTestingIsland = true
                                                // 将当前 Activity 退至后台，以便直观观察超级岛弹出
                                                context.findActivity()?.moveTaskToBack(true)
                                                delay(350)

                                                val result = withContext(Dispatchers.IO) {
                                                    HyperIslandHelper.sendTestIslandNotification(context)
                                                }
                                                isTestingIsland = false
                                            }
                                        },
                                        enabled = !isTestingIsland,
                                        shape = RoundedCornerShape(12.dp),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                    ) {
                                        if (isTestingIsland) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp,
                                                color = MaterialTheme.colorScheme.onPrimary
                                            )
                                        } else {
                                            Text("发送测试", style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }
                            )

                            SettingsSectionDivider()

                            // 超级岛诊断日志
                            SettingItem(
                                headlineText = "超级岛诊断日志",
                                supportingText = "后台持续记录通知构建、Shizuku 状态与系统响应",
                                trailingContent = {
                                    PremiumSwitch(
                                        checked = superIslandLoggingEnabled,
                                        onCheckedChange = { enabled ->
                                            scope.launch {
                                                repository.updateSuperIslandLoggingEnabled(enabled)
                                                logFileSizeText = SuperIslandLogManager.getLogFileSizeText(context)
                                            }
                                        }
                                    )
                                }
                            )

                            // 展开日志管理卡片
                            AnimatedVisibility(
                                visible = superIslandLoggingEnabled,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                Column {
                                    SettingsSectionDivider()
                                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                        Text(
                                            text = "诊断日志大小: $logFileSizeText",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(Modifier.height(10.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Button(
                                                onClick = {
                                                    val success = SuperIslandLogManager.shareLog(context)
                                                    if (!success) {
                                                        Toast.makeText(context, "暂无诊断日志记录或文件为空", Toast.LENGTH_SHORT).show()
                                                    }
                                                },
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(12.dp)
                                            ) {
                                                Text("分享诊断日志")
                                            }

                                            OutlinedButton(
                                                onClick = {
                                                    SuperIslandLogManager.clearLog(context)
                                                    logFileSizeText = SuperIslandLogManager.getLogFileSizeText(context)
                                                    Toast.makeText(context, "诊断日志已清空", Toast.LENGTH_SHORT).show()
                                                },
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(12.dp)
                                            ) {
                                                Text("清空日志")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. 触发提示 (Toast)
            item {
                SettingsSection(topLabel = "轻量提示") {
                    SettingItem(
                        headlineText = "触发提示",
                        supportingText = "分发跳转时弹出轻量 Toast 提示",
                        trailingContent = {
                            PremiumSwitch(
                                checked = showTriggerToast,
                                onCheckedChange = { scope.launch { repository.updateShowTriggerToast(it) } }
                            )
                        }
                    )
                }
            }


            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
