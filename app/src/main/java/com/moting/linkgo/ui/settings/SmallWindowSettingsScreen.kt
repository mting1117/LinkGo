package com.moting.linkgo.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moting.linkgo.R
import com.moting.linkgo.data.SettingsRepository
import com.moting.linkgo.data.WindowConfig
import com.moting.linkgo.ui.components.*
import com.moting.linkgo.ui.settings.components.FullScreenEditorOverlay
import kotlinx.coroutines.launch

/**
 * 小窗高级设置 (二级菜单)
 */
@Composable
fun SmallWindowSettingsScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { SettingsRepository(context) }
    val windowConfig by repository.windowConfig.collectAsState(initial = repository.currentWindowConfig)

    var isEditingSize by rememberSaveable { mutableStateOf(false) }
    var selectedOrientationTab by rememberSaveable { mutableIntStateOf(0) }
    var showModeDialog by remember { mutableStateOf(false) }

    DisposableEffect(selectedOrientationTab) {
        val activity = context as? android.app.Activity
        if (selectedOrientationTab == 1) {
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        onDispose { }
    }

    DisposableEffect(Unit) {
        val activity = context as? android.app.Activity
        val originalOrientation = activity?.requestedOrientation ?: android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose {
            activity?.requestedOrientation = originalOrientation
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        CollapsingTopBarScaffold(
            title = "自由窗口",
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

                // 0. 总开关
                item {
                    SettingsSection(topLabel = "基础状态") {
                        SettingItem(
                            headlineText = "启用小窗",
                            supportingText = "全局控制小窗分发功能",
                            trailingContent = {
                                PremiumSwitch(
                                    checked = windowConfig.isEnabled,
                                    onCheckedChange = { 
                                        scope.launch { repository.updateConfig(windowConfig.copy(isEnabled = it)) }
                                    }
                                )
                            }
                        )
                    }
                }

                // 1. 横竖屏预设切换
                item {
                    SettingsSection(topLabel = "方向模式") {
                        Box(modifier = Modifier.padding(14.dp)) {
                            PremiumSegmentedRow(
                                options = listOf(0, 1),
                                selectedOption = selectedOrientationTab,
                                onOptionSelected = { selectedOrientationTab = it },
                                labelProvider = { if (it == 0) "竖屏预览" else "横屏预览" }
                            )
                        }
                    }
                }

                // 2. 核心逻辑展示
                item {
                    SettingsSection(topLabel = "启动策略") {
                        SettingItem(
                            headlineText = "启动模式",
                            supportingText = windowConfig.getModeName(),
                            supportingTextColor = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { showModeDialog = true }
                        )

                        SettingsSectionDivider()

                        SettingItem(
                            headlineText = "调节小窗尺寸与位置",
                            supportingText = "实时预览并拖拽定义小窗",
                            modifier = Modifier.clickable { isEditingSize = true }
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }

        // 蒙层层级
        val currentW = if (selectedOrientationTab == 0) windowConfig.portWidthRatio else windowConfig.landWidthRatio
        val currentH = if (selectedOrientationTab == 0) windowConfig.portHeightRatio else windowConfig.landHeightRatio
        val currentX = if (selectedOrientationTab == 0) windowConfig.portXRatio else windowConfig.landXRatio
        val currentY = if (selectedOrientationTab == 0) windowConfig.portYRatio else windowConfig.landYRatio

        FullScreenEditorOverlay(
            isVisible = isEditingSize,
            widthRatio = currentW,
            heightRatio = currentH,
            xRatio = currentX,
            yRatio = currentY,
            onConfigChanged = { w, h, x, y ->
                scope.launch {
                    val newConfig = if (selectedOrientationTab == 0) {
                        windowConfig.copy(portWidthRatio = w, portHeightRatio = h, portXRatio = x, portYRatio = y)
                    } else {
                        windowConfig.copy(landWidthRatio = w, landHeightRatio = h, landXRatio = x, landYRatio = y)
                    }
                    repository.updateConfig(newConfig)
                }
            },
            onDismiss = { isEditingSize = false },
            onSave = { isEditingSize = false }
        )

        // 启动模式选择弹窗
        if (showModeDialog) {
            LaunchModeSelectionDialog(
                currentMode = windowConfig.windowingMode,
                onModeSelected = { mode ->
                    scope.launch {
                        repository.updateConfig(windowConfig.copy(windowingMode = mode))
                        showModeDialog = false
                    }
                },
                onDismiss = { showModeDialog = false }
            )
        }
    }
}

/**
 * 启动模式选择对话框 (Floatwidget Style)
 */
@Composable
fun LaunchModeSelectionDialog(
    currentMode: Int,
    onModeSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val modes = listOf(
        Triple(5, "自由窗口 (标准)", "Mode5，适用于 Hyperos 系统"),
        Triple(100, "自由窗口2 (ColorOS)", "Mode100，适用于 ColorOS 系统"),
        Triple(102, "自由窗口3 (MagicOS)", "Mode102，适用于 MagicOS 系统 "),
        Triple(4, "自由窗口4 (OriginOS)", "适用于 OriginOS 等具有小窗分享功能的系统"),
        Triple(11, "自由窗口5 (FlymeOS)", "Mode11，适用于 FlymeOS 系统"),
        Triple(6, "自由窗口6 (努比亚)", "适用于努比亚/红魔 MyOS 系统")
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "选择启动模式",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                modes.forEach { (mode, title, desc) ->
                    val isSelected = currentMode == mode
                    Surface(
                        onClick = { onModeSelected(mode) },
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(14.dp)
                                .fillMaxWidth(),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                )
                                Text(
                                    text = desc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_check),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
        shape = RoundedCornerShape(28.dp)
    )
}
