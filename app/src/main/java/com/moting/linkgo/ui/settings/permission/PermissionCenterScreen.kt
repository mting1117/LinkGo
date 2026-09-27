package com.moting.linkgo.ui.settings.permission

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.moting.linkgo.R
import com.moting.linkgo.data.SettingsRepository
import com.moting.linkgo.ui.components.*
import com.moting.linkgo.ui.settings.components.AdbGuideDialog
import com.moting.linkgo.util.privilege.CapabilityCenter
import com.moting.linkgo.util.privilege.PermissionCenter
import com.moting.linkgo.util.privilege.PermissionItem
import com.moting.linkgo.util.privilege.PrivilegeEngine
import kotlinx.coroutines.launch

/**
 * 权限中心（Permission Center）二级页面 —— 重构升级版。
 *
 * 彻底破除信息平均化与过度密集，分为 4 大梯次清晰分明的视觉结构：
 * 1. 顶部特权运行看板（全局调度开关 + 激活通道药丸 + 核心就绪度指示 + 预警胶囊）
 * 2. 核心运行特权（高优先级焦点卡片：无障碍、安全设置、后台弹出、自启动、悬浮窗、电池优化）
 *    - 内嵌 AdbGuideDialog 授权闭环，彻底解决写入安全设置 ADB 指引缺失问题
 * 3. 扩展系统能力（通知、清空剪贴板、LSPosed、超级岛断网旁路）
 * 4. 特权自动化微调（开机自愈、前台自愈、预热等能力开关）
 * 5. 基础清单权限（折叠弱化卡片：应用查询、开机广播）
 */
@Composable
fun PermissionCenterScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { SettingsRepository(context) }

    val globalEnabled by repository.globalPrivilegeEnabled.collectAsState(initial = repository.currentGlobalPrivilegeEnabled)
    val privilegeMode by repository.privilegeMode.collectAsState(initial = repository.currentPrivilegeMode)
    val states by PermissionCenter.states.collectAsState()
    val channel by PermissionCenter.privilegeChannel.collectAsState()
    val capabilities by repository.capabilities.collectAsState(initial = emptyMap())

    // 写入安全设置 ADB 指引弹窗显隐状态
    var showAdbGuideDialog by remember { mutableStateOf(false) }

    // 清单基础权限折叠状态
    var manifestExpanded by remember { mutableStateOf(false) }

    // 进入页面 / 通道变化 / 模式变化时刷新全量状态
    LaunchedEffect(channel, privilegeMode) {
        PrivilegeEngine.mode = PrivilegeEngine.PrivilegeMode.fromString(privilegeMode)
        PermissionCenter.refresh(context)
    }

    // 页面从后台重新聚焦（如在系统设置/Magisk/Shizuku授权完毕切回）时自动刷新全量状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                PermissionCenter.refresh(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // 核心运行特权（用户明确要求的 6 大关键项）
    val corePermissions = remember {
        listOf(
            PermissionItem.ACCESSIBILITY,
            PermissionItem.WRITE_SECURE_SETTINGS,
            PermissionItem.BACKGROUND_POPUP,
            PermissionItem.AUTO_START,
            PermissionItem.OVERLAY,
            PermissionItem.BATTERY_WHITELIST
        )
    }

    // 扩展系统能力
    val extendedPermissions = remember {
        listOf(
            PermissionItem.POST_NOTIFICATIONS,
            PermissionItem.CLIPBOARD_CLEAR,
            PermissionItem.LSPOSED_HOOK,
            PermissionItem.SUPER_ISLAND_BYPASS
        )
    }

    // 特权自动化能力开关
    val capabilityList = remember {
        listOf(
            CapabilityCenter.Capability.SHIZUKU_SILENT_GRANT,
            CapabilityCenter.Capability.BATTERY_SILENT_WHITELIST,
            CapabilityCenter.Capability.A11Y_HEAL_BOOT,
            CapabilityCenter.Capability.A11Y_HEAL_APP,
            CapabilityCenter.Capability.A11Y_PREWARM_CLIPBOARD
        )
    }

    // 核心就绪度统计
    val coreTotal = corePermissions.size
    val coreGrantedCount = corePermissions.count { states[it] == PermissionCenter.Status.GRANTED }
    val isCoreAllReady = coreGrantedCount == coreTotal

    Box(modifier = Modifier.fillMaxSize()) {
        CollapsingTopBarScaffold(
            title = "权限中心",
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
                // ── 梯次 0：特权运行看板 ─────────────────────────
                item {
                    SettingsSection(topLabel = "总开关") {
                        // 全局特权总调度开关（简洁无左端图标）
                        SettingItem(
                            headlineText = "后台守护",
                            supportingText = if (globalEnabled) "守护中 · $coreGrantedCount/$coreTotal 项已就绪"
                            else "已关闭 · 后台功能暂停工作",
                            trailingContent = {
                                PremiumSwitch(
                                    checked = globalEnabled,
                                    onCheckedChange = { enabled ->
                                        scope.launch {
                                            repository.updateGlobalPrivilegeEnabled(enabled)
                                            PermissionCenter.refresh(context)
                                            Toast.makeText(
                                                context,
                                                if (enabled) "后台守护已开启" else "后台守护已关闭",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                )
                            }
                        )

                        SettingsSectionDivider()

                        // 特权工作模式分段切换
                        val modeSupportingText = when (privilegeMode) {
                            "root" -> "仅走 Root 通道"
                            "shizuku" -> "仅走 Shizuku 通道"
                            else -> "优先 Root，未获取切换为 Shizuku"
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Text(
                                text = "提权模式",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = modeSupportingText,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                            )
                            Spacer(Modifier.height(10.dp))
                            PremiumSegmentedRow(
                                options = listOf("auto", "root", "shizuku"),
                                selectedOption = privilegeMode,
                                onOptionSelected = { selected ->
                                    scope.launch {
                                        repository.updatePrivilegeMode(selected)
                                        PrivilegeEngine.mode = PrivilegeEngine.PrivilegeMode.fromString(selected)
                                        PermissionCenter.refresh(context)
                                        val modeName = when (selected) {
                                            "root" -> "仅 Root"
                                            "shizuku" -> "仅 Shizuku"
                                            else -> "自动 (优先Root)"
                                        }
                                        Toast.makeText(context, "已切换为：$modeName", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                labelProvider = {
                                    when (it) {
                                        "root" -> "仅 Root"
                                        "shizuku" -> "仅 Shizuku"
                                        else -> "自动"
                                    }
                                }
                            )
                        }

                        SettingsSectionDivider()

                        // 看板指示条：通道状态 + 就绪度徽章
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 提权通道胶囊
                            PrivilegeChannelBadge(
                                channel = channel,
                                onChannelClick = {
                                    scope.launch {
                                        if (channel == PrivilegeEngine.Channel.NONE) {
                                            when (privilegeMode) {
                                                "root" -> {
                                                    val ok = PrivilegeEngine.testRoot()
                                                    PermissionCenter.refresh(context)
                                                    Toast.makeText(
                                                        context,
                                                        if (ok) "Root (su) 授权正常可用" else "未检测到 Root 权限，请在授权管理应用中授予",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                                "shizuku" -> {
                                                    PermissionCenter.grant(context, PermissionItem.SHIZUKU)
                                                    PermissionCenter.refresh(context)
                                                }
                                                else -> {
                                                    val ok = PrivilegeEngine.testRoot()
                                                    if (ok) {
                                                        PermissionCenter.refresh(context)
                                                        Toast.makeText(context, "Root 授权已就绪", Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        PermissionCenter.grant(context, PermissionItem.SHIZUKU)
                                                        PermissionCenter.refresh(context)
                                                    }
                                                }
                                            }
                                        } else {
                                            PermissionCenter.refresh(context)
                                            Toast.makeText(context, "已刷新特权通道状态", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            )

                            // 核心特权就绪度胶囊
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isCoreAllReady) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                else MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Icon(
                                        imageVector = ImageVector.vectorResource(
                                            if (isCoreAllReady) R.drawable.ic_iconoir_check_circle
                                            else R.drawable.ic_iconoir_warning_triangle
                                        ),
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = if (isCoreAllReady) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.tertiary
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = if (isCoreAllReady) "核心特权全部就绪" else "核心项 $coreGrantedCount/$coreTotal",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = if (isCoreAllReady) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.tertiary
                                    )
                                }
                            }
                        }

                        // 核心特权缺失温馨预警提示条
                        if (globalEnabled && !isCoreAllReady) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f))
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = ImageVector.vectorResource(R.drawable.ic_iconoir_warning_triangle),
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "存在尚未就绪的核心特权，可能影响后台识别与自启动",
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                    }
                }

                // ── 梯次 1：系统提权通道专区 ─────────────────────
                item {
                    SettingsSection(topLabel = "提权通道") {
                        val rootStatus = states[PermissionItem.ROOT] ?: PermissionCenter.Status.UNAVAILABLE
                        val shizukuStatus = states[PermissionItem.SHIZUKU] ?: PermissionCenter.Status.UNAVAILABLE

                        RootChannelRow(
                            status = rootStatus,
                            privilegeMode = privilegeMode,
                            globalEnabled = globalEnabled,
                            onTest = {
                                scope.launch {
                                    val ok = PrivilegeEngine.testRoot()
                                    PermissionCenter.refresh(context)
                                    Toast.makeText(
                                        context,
                                        if (ok) "Root (su) 授权正常可用" else "未检测到 Root 权限，请在授权管理应用中授予",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        )

                        SettingsSectionDivider()

                        ShizukuChannelRow(
                            status = shizukuStatus,
                            privilegeMode = privilegeMode,
                            globalEnabled = globalEnabled,
                            onRequestGrant = {
                                scope.launch {
                                    val result = PermissionCenter.grant(context, PermissionItem.SHIZUKU)
                                    PermissionCenter.refresh(context)
                                    showGrantResult(context, PermissionItem.SHIZUKU, result)
                                }
                            },
                            onOpenApp = {
                                PermissionCenter.openShizukuSettings(context)
                            }
                        )
                    }
                }

                // ── 梯次 2：核心运行特权 ─────────────────────────
                item {
                    SettingsSection(topLabel = "核心权限") {
                        corePermissions.forEachIndexed { index, item ->
                            val status = states[item] ?: PermissionCenter.Status.UNAVAILABLE
                            PrioritizedPermissionRow(
                                item = item,
                                status = status,
                                enabled = globalEnabled,
                                onGrant = {
                                    if (item == PermissionItem.WRITE_SECURE_SETTINGS && status != PermissionCenter.Status.GRANTED) {
                                        // 唤起 ADB 指引弹窗闭环
                                        showAdbGuideDialog = true
                                    } else {
                                        scope.launch {
                                            val result = PermissionCenter.grant(context, item)
                                            PermissionCenter.refresh(context)
                                            showGrantResult(context, item, result)
                                        }
                                    }
                                },
                                onRevoke = {
                                    scope.launch {
                                        val result = PermissionCenter.revoke(context, item)
                                        PermissionCenter.refresh(context)
                                        Toast.makeText(
                                            context,
                                            if (result == PermissionCenter.Result.Revoked) "已收回: ${item.label}"
                                            else "收回失败或需手动处理: ${item.label}",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                },
                                onItemClick = {
                                    if (item == PermissionItem.WRITE_SECURE_SETTINGS && status != PermissionCenter.Status.GRANTED) {
                                        showAdbGuideDialog = true
                                    }
                                }
                            )
                            if (index < corePermissions.lastIndex) {
                                SettingsSectionDivider()
                            }
                        }
                    }
                }

                // ── 梯次 2：扩展系统能力 ─────────────────────────
                item {
                    SettingsSection(topLabel = "扩展能力") {
                        extendedPermissions.forEachIndexed { index, item ->
                            val status = states[item] ?: PermissionCenter.Status.UNAVAILABLE
                            PrioritizedPermissionRow(
                                item = item,
                                status = status,
                                enabled = globalEnabled,
                                onGrant = {
                                    scope.launch {
                                        val result = PermissionCenter.grant(context, item)
                                        PermissionCenter.refresh(context)
                                        showGrantResult(context, item, result)
                                    }
                                },
                                onRevoke = {
                                    scope.launch {
                                        val result = PermissionCenter.revoke(context, item)
                                        PermissionCenter.refresh(context)
                                        Toast.makeText(
                                            context,
                                            if (result == PermissionCenter.Result.Revoked) "已收回: ${item.label}"
                                            else "收回失败或需手动处理: ${item.label}",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            )
                            if (index < extendedPermissions.lastIndex) {
                                SettingsSectionDivider()
                            }
                        }
                    }
                }

                // ── 梯次 3：特权自动化微调 ───────────────────────
                item {
                    SettingsSection(topLabel = "自动化") {
                        capabilityList.forEachIndexed { index, cap ->
                            val capEnabled = capabilities[cap] ?: true
                            CapabilityRow(
                                cap = cap,
                                enabled = capEnabled,
                                globalEnabled = globalEnabled,
                                onToggle = { value ->
                                    scope.launch {
                                        repository.updateCapability(cap, value)
                                    }
                                }
                            )
                            if (index < capabilityList.lastIndex) {
                                SettingsSectionDivider()
                            }
                        }
                    }
                }

                // ── 梯次 4：清单固有权限（轻量折叠收拢）─────────
                item {
                    SettingsSection(topLabel = "基础权限") {
                        ManifestDeclaredCard(
                            isExpanded = manifestExpanded,
                            onToggle = { manifestExpanded = !manifestExpanded }
                        )
                    }
                }

                // 底部安全边距
                item {
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    // 写入安全设置授权引导弹窗
    if (showAdbGuideDialog) {
        AdbGuideDialog(
            onDismissRequest = {
                showAdbGuideDialog = false
                PermissionCenter.refresh(context)
            },
            onCheckPermission = {
                PermissionCenter.refresh(context)
                if (PermissionCenter.statusOf(context, PermissionItem.WRITE_SECURE_SETTINGS) == PermissionCenter.Status.GRANTED) {
                    showAdbGuideDialog = false
                    Toast.makeText(context, "安全设置权限已激活！", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "尚未检测到安全设置权限，请按引导操作", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}

/**
 * 极简现代权限项行。
 * 标题行：标题 + 已就绪/状态标签；
 * 副标题行：精炼副标题 + 尾部旋转指示小箭头；
 * 右侧：授权 / 撤销操作按钮；
 * 点击整行动画展开详细用途说明（已精简移除冗余风险标签）。
 */
@Composable
private fun PrioritizedPermissionRow(
    item: PermissionItem,
    status: PermissionCenter.Status,
    enabled: Boolean,
    onGrant: () -> Unit,
    onRevoke: () -> Unit,
    onItemClick: (() -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    val subtitle = permissionSubtitle(item)
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        label = "arrowRotation"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    onItemClick?.invoke()
                    expanded = !expanded
                }
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 文本信息区（左侧无多余图标，直接左对齐）
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                // 标题行：标题 + 已就绪/状态标签
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = item.label,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    StatusBadge(status)
                }

                // 副标题行：精炼说明 + 尾部小箭头指示可展开
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Text(
                        text = subtitle,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(3.dp))
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_iconoir_nav_arrow_right),
                        contentDescription = null,
                        modifier = Modifier
                            .size(13.dp)
                            .graphicsLayer(rotationZ = arrowRotation),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }

            Spacer(Modifier.width(10.dp))

            // 右端操作区：未授权显示授权/指引，已就绪且支持撤销显示撤销按钮
            if (status != PermissionCenter.Status.GRANTED) {
                FilledTonalButton(
                    onClick = onGrant,
                    enabled = enabled,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text(
                        text = if (item == PermissionItem.WRITE_SECURE_SETTINGS) "指引" else "授权",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else if (isRevocable(item)) {
                OutlinedButton(
                    onClick = onRevoke,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("撤销", fontSize = 11.5.sp)
                }
            }
        }

        // 展开详情区：详细服务用途说明，左侧对齐
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
            exit = shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 清单固有基础权限折叠卡片。
 * 默认轻量化收拢为一行，点击展开查看。不与核心特权争夺视觉重心。
 */
@Composable
private fun ManifestDeclaredCard(
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    val arrowRotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        label = "arrowRotationManifest"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                // 标题行：标题 + 已就绪标签
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "清单固有权限",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    StatusBadge(PermissionCenter.Status.GRANTED)
                }

                // 副标题行：副标题 + 尾部小箭头指示可展开
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Text(
                        text = "包含应用查询与开机广播声明，系统自动赋予",
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(3.dp))
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_iconoir_nav_arrow_right),
                        contentDescription = null,
                        modifier = Modifier
                            .size(13.dp)
                            .graphicsLayer(rotationZ = arrowRotation),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }

            Spacer(Modifier.width(10.dp))

            Text(
                text = if (isExpanded) "收起" else "查看",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            )
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ManifestItemRow(
                    name = "查询所有应用",
                    desc = "读取已安装应用用于选择器与图标加载"
                )
                ManifestItemRow(
                    name = "开机广播声明",
                    desc = "清单注册接收开机广播资格，辅助快速预热"
                )
            }
        }
    }
}

@Composable
private fun ManifestItemRow(name: String, desc: String) {
    Column {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        )
        Text(
            text = desc,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 能力微调行 */
@Composable
private fun CapabilityRow(
    cap: CapabilityCenter.Capability,
    enabled: Boolean,
    globalEnabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        label = "arrowRotationCap"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = { expanded = !expanded }
            )
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = cap.label,
                    maxLines = 1,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Text(
                        text = if (expanded) "点击收起详情" else "点击展开说明",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(3.dp))
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_iconoir_nav_arrow_right),
                        contentDescription = null,
                        modifier = Modifier
                            .size(12.dp)
                            .graphicsLayer(rotationZ = arrowRotation),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            PremiumSwitch(
                checked = enabled,
                enabled = globalEnabled,
                onCheckedChange = onToggle
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Text(
                text = cap.description,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

/** 状态胶囊 */
@Composable
private fun StatusBadge(status: PermissionCenter.Status) {
    val (label, color) = when (status) {
        PermissionCenter.Status.GRANTED -> "已就绪" to Color(0xFF4CAF50)
        PermissionCenter.Status.UNAVAILABLE -> "未激活" to Color(0xFF9E9E9E)
        PermissionCenter.Status.ANR_NEEDED -> "待配置" to Color(0xFFFF9800)
        PermissionCenter.Status.ADB_NEEDED -> "需 ADB" to Color(0xFFF44336)
        PermissionCenter.Status.REVOKED -> "已停用" to Color(0xFF9E9E9E)
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.12f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

/** 提权通道状态胶囊 */
@Composable
private fun PrivilegeChannelBadge(
    channel: PrivilegeEngine.Channel,
    onChannelClick: () -> Unit
) {
    val (name, color) = when (channel) {
        PrivilegeEngine.Channel.SHIZUKU -> "Shizuku 激活" to MaterialTheme.colorScheme.primary
        PrivilegeEngine.Channel.ROOT -> "Root 激活" to MaterialTheme.colorScheme.primary
        PrivilegeEngine.Channel.NONE -> "无系统提权通道" to MaterialTheme.colorScheme.outline
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.12f),
        modifier = Modifier.clickable(onClick = onChannelClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_iconoir_terminal),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = color
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = color
            )
        }
    }
}

/** 权限副标题精炼（8~16字，杜绝截断与平均化） */
private fun permissionSubtitle(item: PermissionItem): String = when (item) {
    PermissionItem.SHIZUKU -> "无线调试免Root系统IPC通道"
    PermissionItem.ROOT -> "最高系统权限执行通道"
    PermissionItem.ACCESSIBILITY -> "屏幕识别与精准点击核心"
    PermissionItem.WRITE_SECURE_SETTINGS -> "静默自愈与后台剪贴板 (需ADB)"
    PermissionItem.BACKGROUND_POPUP -> "后台直接拉起跳转与选择界面"
    PermissionItem.AUTO_START -> "开机预热与后台防杀自愈"
    PermissionItem.OVERLAY -> "悬浮胶囊与屏幕取词浮层"
    PermissionItem.BATTERY_WHITELIST -> "加入白名单避免服务被杀死"
    PermissionItem.POST_NOTIFICATIONS -> "实时活动胶囊与跳转提醒"
    PermissionItem.CLIPBOARD_CLEAR -> "跳转后自动抹除敏感剪贴板"
    PermissionItem.LSPOSED_HOOK -> "系统级无感知监听与唤醒"
    PermissionItem.SUPER_ISLAND_BYPASS -> "独立通过 Shizuku 旁路 HyperOS 云端拦截"
    PermissionItem.QUERY_ALL_PACKAGES -> "已就绪 · 应用列表识别与图标查询"
    PermissionItem.BOOT_COMPLETED -> "已就绪 · 静态声明接收开机广播"
}

private fun isRevocable(item: PermissionItem): Boolean = when (item) {
    PermissionItem.OVERLAY, PermissionItem.POST_NOTIFICATIONS,
    PermissionItem.WRITE_SECURE_SETTINGS, PermissionItem.ACCESSIBILITY,
    PermissionItem.BACKGROUND_POPUP, PermissionItem.AUTO_START,
    PermissionItem.SHIZUKU -> true
    else -> false
}

private fun showGrantResult(context: android.content.Context, item: PermissionItem, result: PermissionCenter.Result) {
    val msg = when (result) {
        PermissionCenter.Result.Granted -> "已授予: ${item.label}"
        PermissionCenter.Result.Failed -> "授予失败: ${item.label}"
        PermissionCenter.Result.NeedsSystemSettings -> "请在弹出的系统设置中完成: ${item.label}"
        PermissionCenter.Result.NeedsAdb -> "需要 ADB 授权: ${item.label}"
        PermissionCenter.Result.NeedsRuntimeRequest -> "已发起授权请求: ${item.label}"
        PermissionCenter.Result.Revoked -> "已收回: ${item.label}"
    }
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}

/**
 * Root 权限专区卡片行
 */
@Composable
private fun RootChannelRow(
    status: PermissionCenter.Status,
    privilegeMode: String,
    globalEnabled: Boolean,
    onTest: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val isBypassed = privilegeMode.equals("shizuku", ignoreCase = true)
    val isGranted = status == PermissionCenter.Status.GRANTED

    val headline = "Root 权限"
    val subtitle = when {
        isBypassed -> "当前工作模式为仅 Shizuku · 此通道已停用"
        !globalEnabled -> "特权总调度已停用"
        isGranted -> "已获得最高权限 · 支持静默自愈与底层日志"
        else -> "未获得授权 · 点击测试或申请 su 权限"
    }

    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        label = "arrowRotationRoot"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 文本信息区（左侧无图标，直接左对齐）
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                // 标题行：标题 + 状态标签
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = headline,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    if (isBypassed) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = "已停用",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.5.sp),
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    } else {
                        StatusBadge(status)
                    }
                }

                // 副标题行：副标题 + 尾部小箭头指示可展开
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Text(
                        text = subtitle,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(3.dp))
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_iconoir_nav_arrow_right),
                        contentDescription = null,
                        modifier = Modifier
                            .size(13.dp)
                            .graphicsLayer(rotationZ = arrowRotation),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }

            Spacer(Modifier.width(10.dp))

            // 右端操作区
            if (!isBypassed) {
                if (isGranted) {
                    OutlinedButton(
                        onClick = onTest,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("测试", fontSize = 11.5.sp)
                    }
                } else {
                    FilledTonalButton(
                        onClick = onTest,
                        enabled = globalEnabled,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("测试授权", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
            exit = shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Text(
                    text = "通过 su 命令直接获取最高系统权限。用于静默自愈无障碍服务、后台日志监控与高级系统操作。支持 Magisk、KernelSU、APatch 等主流授权工具。",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!isBypassed) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onTest,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_iconoir_refresh),
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("检测 Root (su) 授权状态", fontSize = 11.5.sp)
                    }
                }
            }
        }
    }
}

/**
 * Shizuku 权限专区卡片行
 */
@Composable
private fun ShizukuChannelRow(
    status: PermissionCenter.Status,
    privilegeMode: String,
    globalEnabled: Boolean,
    onRequestGrant: () -> Unit,
    onOpenApp: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val isBypassedInGlobal = privilegeMode.equals("root", ignoreCase = true)
    val isGranted = status == PermissionCenter.Status.GRANTED

    val headline = "Shizuku 权限"
    val subtitle = when {
        isBypassedInGlobal -> "全局特权已旁路 · 超级岛断网仍可独立使用"
        !globalEnabled -> "特权总调度已停用"
        isGranted -> "服务运行正常 · 免 Root 系统级 IPC 通道"
        else -> "服务未运行或未授权 · 点击发起授权"
    }

    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        label = "arrowRotationShizuku"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 文本信息区（左侧无图标，直接左对齐）
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                // 标题行：标题 + 状态标签
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = headline,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    if (isBypassedInGlobal) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = "已旁路",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.5.sp),
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    } else {
                        StatusBadge(status)
                    }
                }

                // 副标题行：副标题 + 尾部小箭头指示可展开
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Text(
                        text = subtitle,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(3.dp))
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_iconoir_nav_arrow_right),
                        contentDescription = null,
                        modifier = Modifier
                            .size(13.dp)
                            .graphicsLayer(rotationZ = arrowRotation),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }

            Spacer(Modifier.width(10.dp))

            // 右端操作区
            if (isGranted) {
                OutlinedButton(
                    onClick = onOpenApp,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("打开", fontSize = 11.5.sp)
                }
            } else {
                FilledTonalButton(
                    onClick = onRequestGrant,
                    enabled = true,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("授权", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
            exit = shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Text(
                    text = "通过无线调试或 ADB 运行的特权服务，可免 Root 调用系统隐藏 API（如权限自动授予、剪贴板监听、超级岛断网旁路等）。",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!isGranted) {
                        OutlinedButton(
                            onClick = onRequestGrant,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text("发起授权请求", fontSize = 11.5.sp)
                        }
                    }
                    OutlinedButton(
                        onClick = onOpenApp,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_iconoir_open_new_window),
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("打开 Shizuku 应用", fontSize = 11.5.sp)
                    }
                }
            }
        }
    }
}
