package com.moting.linkgo.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.moting.linkgo.R
import com.moting.linkgo.data.SettingsRepository
import com.moting.linkgo.model.EdgeGestureConfig
import com.moting.linkgo.model.GestureAction
import com.moting.linkgo.model.PointerControlMode
import com.moting.linkgo.overlay.EdgeTriggerManager
import com.moting.linkgo.ui.components.*
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 快捷手势高级设置（二级菜单）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EdgeGestureSettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToAppPicker: (Set<String>) -> Unit = {},
    selectedAppsFromPicker: List<String>? = null,
    onClearPickerResult: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { SettingsRepository(context) }
    val config by repository.edgeGestureConfigFlow.collectAsState(initial = repository.currentEdgeGestureConfig)

    // 监听应用选择器返回结果并更新到配置中
    LaunchedEffect(selectedAppsFromPicker) {
        if (selectedAppsFromPicker != null) {
            val newConfig = config.copy(appScopePackages = selectedAppsFromPicker.toSet())
            scope.launch {
                repository.updateEdgeGestureConfig(newConfig)
            }
            onClearPickerResult()
        }
    }

    // 智能生命周期感知：仅在设置页处于前台活跃状态时强制 100% 不透明高亮；切后台、息屏锁屏或离开页面时彻底恢复用户透明度
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    EdgeTriggerManager.setPreviewOpaque(true)
                }
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP,
                Lifecycle.Event.ON_DESTROY -> {
                    EdgeTriggerManager.setPreviewOpaque(false)
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            EdgeTriggerManager.setPreviewOpaque(false)
        }
    }

    fun update(transform: EdgeGestureConfig.() -> EdgeGestureConfig) {
        val newConfig = config.transform()
        scope.launch {
            repository.updateEdgeGestureConfig(newConfig)
        }
    }

    CollapsingTopBarScaffold(
        title = "快捷手势",
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

            // 1. 基础开关与生效范围
            item {
                SettingsSection(topLabel = "基础状态") {
                    SettingItem(
                        headlineText = "启用快捷手势",
                        supportingText = "边缘滑动或点击触发功能",
                        trailingContent = {
                            PremiumSwitch(
                                checked = config.enabled,
                                onCheckedChange = { update { copy(enabled = it) } }
                            )
                        }
                    )

                    AnimatedVisibility(
                        visible = config.enabled,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column {
                            SettingsSectionDivider()
                            SettingItem(
                                headlineText = "双侧对称镜像",
                                supportingText = "屏幕左右两侧同时生效",
                                trailingContent = {
                                    PremiumSwitch(
                                        checked = config.mirrorEnabled,
                                        onCheckedChange = { update { copy(mirrorEnabled = it) } }
                                    )
                                }
                            )

                            SettingsSectionDivider()

                            // 生效屏幕方向 (横竖屏)
                            DropdownOrientationScopeItem(
                                title = "屏幕方向",
                                supportingText = "选择手势生效的屏幕方向",
                                selectedScope = config.orientationScope,
                                onScopeSelected = { update { copy(orientationScope = it) } }
                            )

                            SettingsSectionDivider()

                            // 应用名单模式
                            DropdownAppScopeModeItem(
                                title = "名单模式",
                                supportingText = config.appScopeMode.description,
                                selectedMode = config.appScopeMode,
                                onModeSelected = { update { copy(appScopeMode = it) } }
                            )

                            SettingsSectionDivider()

                            // 应用名单
                            val appCount = config.appScopePackages.size
                            val appSummary = if (appCount == 0) {
                                if (config.appScopeMode == com.moting.linkgo.model.AppScopeMode.BLACKLIST) {
                                    "未排除任何应用（全域应用生效）"
                                } else {
                                    "未指定生效应用（手势全域停用）"
                                }
                            } else {
                                "已选 $appCount 个应用"
                            }
                            SettingItem(
                                headlineText = "应用名单",
                                supportingText = appSummary,
                                trailingContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = if (appCount > 0) "修改" else "添加",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Icon(
                                            imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_nav_arrow_right),
                                            contentDescription = "选择应用",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                },
                                modifier = Modifier.clickable {
                                    onNavigateToAppPicker(config.appScopePackages)
                                }
                            )
                        }
                    }
                }
            }

            if (config.enabled) {
                // 2. 动作绑定 (动作选择与独立触感反馈)
                item {
                    SettingsSection(topLabel = "动作绑定") {
                        // 滑动触发动作
                        DropdownActionItem(
                            title = "滑动触发动作",
                            supportingText = "沿屏幕边缘向内滑入",
                            selectedAction = config.swipeAction,
                            onActionSelected = { update { copy(swipeAction = it) } }
                        )

                        SettingsSectionDivider()

                        // 单击触发动作
                        DropdownActionItem(
                            title = "单击触发动作",
                            supportingText = "轻点屏幕边缘热区",
                            selectedAction = config.clickAction,
                            onActionSelected = { update { copy(clickAction = it) } }
                        )

                        SettingsSectionDivider()

                        // 长按触发动作
                        DropdownActionItem(
                            title = "长按触发动作",
                            supportingText = "长按屏幕边缘热区",
                            selectedAction = config.longPressAction,
                            onActionSelected = { update { copy(longPressAction = it) } }
                        )

                        SettingsSectionDivider()

                        // 屏幕二维码识别：决定"取图入口"是否保留，因此排在单图片规则直达之前
                        SettingItem(
                            headlineText = "屏幕二维码识别",
                            supportingText = "取图时尝试解码二维码；关闭且未配置图片规则时，屏幕识别与滑动直达不再停留取图",
                            trailingContent = {
                                PremiumSwitch(
                                    checked = config.screenQrEnabled,
                                    onCheckedChange = { update { copy(screenQrEnabled = it) } }
                                )
                            }
                        )

                        SettingsSectionDivider()

                        // 单图片规则直接直达
                        SettingItem(
                            headlineText = "单图片规则直接直达",
                            supportingText = "滑动直达与屏幕识别命中单条规则时直接跳转",
                            trailingContent = {
                                PremiumSwitch(
                                    checked = config.directSingleImageRule,
                                    onCheckedChange = { update { copy(directSingleImageRule = it) } }
                                )
                            }
                        )

                        SettingsSectionDivider()

                        // 手势触感反馈
                        SettingItem(
                            headlineText = "手势触感反馈",
                            supportingText = "触发侧边手势时提供轻震反馈",
                            trailingContent = {
                                PremiumSwitch(
                                    checked = config.gestureHapticEnabled,
                                    onCheckedChange = { update { copy(gestureHapticEnabled = it) } }
                                )
                            }
                        )

                        SettingsSectionDivider()

                        // 碰撞触感反馈
                        SettingItem(
                            headlineText = "碰撞触感反馈",
                            supportingText = "探照碰撞链接时提供轻震反馈",
                            trailingContent = {
                                PremiumSwitch(
                                    checked = config.collisionHapticEnabled,
                                    onCheckedChange = { update { copy(collisionHapticEnabled = it) } }
                                )
                            }
                        )
                    }
                }

                // 3. 边缘触发热区参数 (严格名称：宽度、高度、垂直位置、水平位置，纯百分比显示)
                item {
                    SettingsSection(topLabel = "触发热区布局") {
                        // 宽度
                        SliderSettingItem(
                            headlineText = "宽度",
                            supportingText = "感应热区横向厚度",
                            valueText = "${config.widthDp} dp",
                            value = config.widthDp.toFloat(),
                            onValueChange = { update { copy(widthDp = it.roundToInt()) } },
                            valueRange = 6f..60f
                        )

                        SettingsSectionDivider()

                        // 高度
                        SliderSettingItem(
                            headlineText = "高度",
                            supportingText = "感应热区垂直覆盖高度",
                            valueText = "${config.heightDp} dp",
                            value = config.heightDp.toFloat(),
                            onValueChange = { update { copy(heightDp = it.roundToInt()) } },
                            valueRange = 40f..400f
                        )

                        SettingsSectionDivider()

                        // 垂直位置
                        SliderSettingItem(
                            headlineText = "垂直位置",
                            supportingText = "热区在侧边的上下位置",
                            valueText = formatPercentText(config.verticalRatio),
                            value = config.verticalRatio,
                            onValueChange = { update { copy(verticalRatio = roundToMille(it)) } },
                            valueRange = 0.000f..1.000f
                        )

                        SettingsSectionDivider()

                        // 水平位置
                        SliderSettingItem(
                            headlineText = "水平位置",
                            supportingText = "热区距屏幕边框间距",
                            valueText = formatPercentText(config.horizontalRatio),
                            value = config.horizontalRatio,
                            onValueChange = { update { copy(horizontalRatio = roundToMille(it)) } },
                            valueRange = 0.000f..0.100f
                        )

                        SettingsSectionDivider()

                        // 指示条透明度 (按 1% 为步长单位)
                        SliderSettingItem(
                            headlineText = "指示条透明度",
                            supportingText = "贴边指示条显示透明度",
                            valueText = "${(config.edgeAlpha * 100).roundToInt()}%",
                            value = config.edgeAlpha,
                            onValueChange = { update { copy(edgeAlpha = (it * 100).roundToInt() / 100f) } },
                            valueRange = 0.00f..1.00f
                        )
                    }
                }

                // 4. 操控方式 (单手指针 vs 触控跟随及动态杠杆灵敏度、初始距离、防遮挡)
                item {
                    SettingsSection(topLabel = "操控方式") {
                        // 探照圆球操控模式选择 (单手指针 vs 触控跟随)
                        DropdownControlModeItem(
                            selectedMode = config.controlMode,
                            onModeSelected = { mode -> update { copy(controlMode = mode) } }
                        )

                        SettingsSectionDivider()

                        if (config.controlMode == PointerControlMode.REMOTE_POINTER) {
                            // 单手指针模式：动态杠杆放大倍率 / 灵敏度
                            SliderSettingItem(
                                headlineText = "指针放大灵敏度",
                                supportingText = "大屏单手操控放大倍率",
                                valueText = String.format(java.util.Locale.US, "%.1fx", config.pointerSensitivity),
                                value = config.pointerSensitivity,
                                onValueChange = { update { copy(pointerSensitivity = (Math.round(it * 10f) / 10f)) } },
                                valueRange = 1.2f..3.2f
                            )

                            SettingsSectionDivider()

                            // 单手指针模式：指针初始距离 (40dp ~ 150dp)
                            SliderSettingItem(
                                headlineText = "指针初始距离",
                                supportingText = "圆球出现时与手指的间距",
                                valueText = "${config.pointerInitialDistanceDp} dp",
                                value = config.pointerInitialDistanceDp.toFloat(),
                                onValueChange = { update { copy(pointerInitialDistanceDp = it.roundToInt()) } },
                                valueRange = 40f..150f
                            )
                        } else {
                            // 触控跟随模式：防手指遮挡垂直偏移 (支持 0dp 完全贴合触点中心，范围 0~150dp)
                            SliderSettingItem(
                                headlineText = "手指防遮挡偏移",
                                supportingText = "圆球避让手指向上偏移",
                                valueText = "${config.fingerOffsetYDp} dp",
                                value = config.fingerOffsetYDp.toFloat(),
                                onValueChange = { update { copy(fingerOffsetYDp = it.roundToInt()) } },
                                valueRange = 0f..150f
                            )
                        }

                        SettingsSectionDivider()

                        // 指针停留时长 (400ms ~ 3000ms，步长 50ms)
                        SliderSettingItem(
                            headlineText = "指针停留时长",
                            supportingText = "探照光标悬停选定目标的触发时长",
                            valueText = "${config.stayDurationMs} ms",
                            value = config.stayDurationMs.toFloat(),
                            onValueChange = {
                                val rounded = (Math.round(it / 50f) * 50).coerceIn(400, 3000)
                                update { copy(stayDurationMs = rounded) }
                            },
                            valueRange = 400f..3000f
                        )
                    }
                }

                // 5. 探照圆球 (外观与尺寸属性)
                item {
                    SettingsSection(topLabel = "探照圆球") {
                        // 弹性形变开关
                        SettingItem(
                            headlineText = "弹性形变",
                            supportingText = "跟随手指滑动时呈现水泡拉伸与回弹动效",
                            trailingContent = {
                                PremiumSwitch(
                                    checked = config.bubbleSquishEnabled,
                                    onCheckedChange = { update { copy(bubbleSquishEnabled = it) } }
                                )
                            }
                        )

                        SettingsSectionDivider()

                        // 圆球尺寸 (支持 10dp ~ 60dp)
                        SliderSettingItem(
                            headlineText = "圆球半径",
                            supportingText = "探照光圈的感应尺寸",
                            valueText = "${config.ballRadiusDp} dp",
                            value = config.ballRadiusDp.toFloat(),
                            onValueChange = { update { copy(ballRadiusDp = it.roundToInt()) } },
                            valueRange = 10f..60f
                        )

                        SettingsSectionDivider()

                        // 描边粗细
                        SliderSettingItem(
                            headlineText = "描边粗细",
                            supportingText = "光圈外轮廓线宽度",
                            valueText = String.format(java.util.Locale.US, "%.1f dp", config.ballStrokeWidthDp),
                            value = config.ballStrokeWidthDp,
                            onValueChange = { update { copy(ballStrokeWidthDp = (it * 10).roundToInt() / 10f) } },
                            valueRange = 1.0f..6.0f
                        )

                        SettingsSectionDivider()

                        // 圆球通透度 (按 1% 为步长单位)
                        SliderSettingItem(
                            headlineText = "圆球通透度",
                            supportingText = "光圈整体显示透明度",
                            valueText = "${(config.ballAlpha * 100).roundToInt()}%",
                            value = config.ballAlpha,
                            onValueChange = { update { copy(ballAlpha = (it * 100).roundToInt() / 100f) } },
                            valueRange = 0.10f..1.00f
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

/**
 * 统一滑轨设置行（主标题、副标题、数值指示与精致滑块）
 */
@Composable
private fun SliderSettingItem(
    headlineText: String,
    supportingText: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    text = headlineText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (supportingText.isNotEmpty()) {
                    Text(
                        text = supportingText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        PremiumSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange
        )
    }
}

/**
 * Material 3 风格动作下拉菜单项（无底色文字 + UnfoldMore 图标，纯白大圆角卡片下拉弹窗）
 */
@Composable
private fun DropdownActionItem(
    title: String,
    supportingText: String,
    selectedAction: GestureAction,
    onActionSelected: (GestureAction) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    SettingItem(
        headlineText = title,
        supportingText = supportingText,
        trailingContent = {
            Box {
                // 触发器：去除胶囊背景，文字适当淡化 + 上下展开箭头
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { expanded = true }
                        .padding(horizontal = 4.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = selectedAction.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Rounded.UnfoldMore,
                        contentDescription = "选择动作",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                // 下拉弹窗：大圆角纯白卡片，无底色条目，选中项主题色加勾
                MaterialTheme(
                    shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(22.dp))
                ) {
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier
                            .widthIn(min = 160.dp, max = 220.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(
                                width = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                                shape = RoundedCornerShape(22.dp)
                            )
                            .padding(vertical = 6.dp)
                    ) {
                        GestureAction.entries.forEach { action ->
                            val isSelected = action == selectedAction
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onActionSelected(action)
                                        expanded = false
                                    }
                                    .padding(horizontal = 16.dp, vertical = 11.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = action.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                if (isSelected) {
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    )
}

private fun formatPercentText(ratio: Float): String {
    return String.format(java.util.Locale.US, "%.1f%%", ratio * 100)
}

private fun roundToMille(value: Float): Float {
    return (value * 1000).roundToInt() / 1000f
}

/**
 * Material 3 风格操控模式下拉菜单项（单手指针 vs 触控跟随）
 */
@Composable
private fun DropdownControlModeItem(
    selectedMode: PointerControlMode,
    onModeSelected: (PointerControlMode) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    SettingItem(
        headlineText = "操控模式",
        supportingText = "单手指针或触控跟随",
        trailingContent = {
            Box {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { expanded = true }
                        .padding(horizontal = 4.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = selectedMode.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Rounded.UnfoldMore,
                        contentDescription = "选择模式",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                MaterialTheme(
                    shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(22.dp))
                ) {
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier
                            .widthIn(min = 160.dp, max = 220.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(
                                width = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                                shape = RoundedCornerShape(22.dp)
                            )
                            .padding(vertical = 6.dp)
                    ) {
                        PointerControlMode.entries.forEach { mode ->
                            val isSelected = mode == selectedMode
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onModeSelected(mode)
                                        expanded = false
                                    }
                                    .padding(horizontal = 16.dp, vertical = 11.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = mode.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                if (isSelected) {
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun DropdownOrientationScopeItem(
    title: String,
    supportingText: String,
    selectedScope: com.moting.linkgo.model.OrientationScope,
    onScopeSelected: (com.moting.linkgo.model.OrientationScope) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    SettingItem(
        headlineText = title,
        supportingText = supportingText,
        trailingContent = {
            Box {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { expanded = true }
                        .padding(horizontal = 4.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = selectedScope.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Rounded.UnfoldMore,
                        contentDescription = "选择屏幕方向",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                MaterialTheme(
                    shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(22.dp))
                ) {
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier
                            .widthIn(min = 160.dp, max = 220.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(
                                width = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                                shape = RoundedCornerShape(22.dp)
                            )
                            .padding(vertical = 6.dp)
                    ) {
                        com.moting.linkgo.model.OrientationScope.entries.forEach { scope ->
                            val isSelected = scope == selectedScope
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onScopeSelected(scope)
                                        expanded = false
                                    }
                                    .padding(horizontal = 16.dp, vertical = 11.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = scope.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                if (isSelected) {
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun DropdownAppScopeModeItem(
    title: String,
    supportingText: String,
    selectedMode: com.moting.linkgo.model.AppScopeMode,
    onModeSelected: (com.moting.linkgo.model.AppScopeMode) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    SettingItem(
        headlineText = title,
        supportingText = supportingText,
        trailingContent = {
            Box {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { expanded = true }
                        .padding(horizontal = 4.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = selectedMode.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Rounded.UnfoldMore,
                        contentDescription = "选择名单模式",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                MaterialTheme(
                    shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(22.dp))
                ) {
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier
                            .widthIn(min = 160.dp, max = 220.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(
                                width = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                                shape = RoundedCornerShape(22.dp)
                            )
                            .padding(vertical = 6.dp)
                    ) {
                        com.moting.linkgo.model.AppScopeMode.entries.forEach { mode ->
                            val isSelected = mode == selectedMode
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onModeSelected(mode)
                                        expanded = false
                                    }
                                    .padding(horizontal = 16.dp, vertical = 11.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = mode.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                if (isSelected) {
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    )
}


