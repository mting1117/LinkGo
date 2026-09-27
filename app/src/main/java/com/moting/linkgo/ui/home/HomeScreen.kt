package com.moting.linkgo.ui.home

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moting.linkgo.R
import com.moting.linkgo.model.AppInfo
import com.moting.linkgo.ui.components.*
import com.moting.linkgo.ui.home.components.EngineHealthBadge
import com.moting.linkgo.viewmodel.HomeViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(
    ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)
@Composable
fun HomeScreen(
    bottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    onNavigateToRules: () -> Unit,
    onNavigateToEditRule: (String?, String?, String?, String?) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToHelp: () -> Unit,
    onNavigateToClipboardMonitor: () -> Unit = { onNavigateToSettings() },
    onNavigateToAppLinkCapture: () -> Unit = { onNavigateToSettings() },
    onNavigateToEdgeGesture: () -> Unit = { onNavigateToSettings() },
    viewModel: HomeViewModel = viewModel()
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val lazyListState = rememberLazyListState()
    var isInputFocused by remember { mutableStateOf(false) }
    val isImeVisible = WindowInsets.isImeVisible

    // 平滑避让动画：当软键盘升起且输入框获得焦点时，以自然流畅的物理缓动曲线平滑平移至键盘正上方
    LaunchedEffect(isImeVisible, isInputFocused) {
        if (isImeVisible && isInputFocused) {
            val layoutInfo = lazyListState.layoutInfo
            val totalCount = layoutInfo.totalItemsCount
            if (totalCount > 0) {
                val targetItem = layoutInfo.visibleItemsInfo.lastOrNull { it.index == totalCount - 1 }
                val targetPaddingPx = with(density) { 16.dp.roundToPx() }
                val scrollOffset = if (targetItem != null) {
                    // 基于卡片真实尺寸与视口可用边界精确计算，杜绝超大 offset 触发的 Compose Snap 闪现
                    (targetItem.size - layoutInfo.viewportSize.height + targetPaddingPx).coerceAtLeast(0)
                } else {
                    with(density) { 120.dp.roundToPx() }
                }
                lazyListState.animateScrollToItem(
                    index = totalCount - 1,
                    scrollOffset = scrollOffset
                )
            }
            bringIntoViewRequester.bringIntoView()
        }
    }

    val isDefault by viewModel.isDefaultBrowser.collectAsState()
    val clipboardEnabled by viewModel.clipboardMonitorEnabled.collectAsState()
    val clipboardBackend by viewModel.clipboardMonitorBackend.collectAsState()
    val appLinkCaptureMode by viewModel.appLinkCaptureMode.collectAsState()
    val appLinkCapturedAppsCount by viewModel.appLinkCapturedAppsCount.collectAsState()
    val privilegeMode by viewModel.privilegeMode.collectAsState()
    val edgeGestureConfig by viewModel.edgeGestureConfig.collectAsState()

    val fallbackApp by viewModel.fallbackAppInfo.collectAsState()
    val fallbackBrowser by viewModel.fallbackBrowser.collectAsState()
    val fallbackWindowMode by viewModel.fallbackWindowMode.collectAsState()
    val fallbackPreheatEnabled by viewModel.fallbackPreheatEnabled.collectAsState()
    val fallbackPreheatDelay by viewModel.fallbackPreheatDelay.collectAsState()
    val fallbackExcludeFromRecents by viewModel.fallbackExcludeFromRecents.collectAsState()
    val appList by viewModel.appList.collectAsState()

    val totalJumpCount by viewModel.totalJumpCount.collectAsState()
    val todayJumpCount by viewModel.todayJumpCount.collectAsState()
    val totalRulesCount by viewModel.totalRulesCount.collectAsState()
    val enabledRulesCount by viewModel.enabledRulesCount.collectAsState()
    val latestJumpInfo by viewModel.latestJumpInfo.collectAsState()

    val urlInput by viewModel.urlInput.collectAsState()
    val matchingApps by viewModel.matchingApps.collectAsState()
    val recommendedPackages by viewModel.recommendedPackages.collectAsState()

    var showSelector by remember { mutableStateOf(false) }
    var showGuideDialog by remember { mutableStateOf(false) }

    val roleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshAllStatus(context)
    }

    fun handleBrowserActivation() {
        if (!isDefault) {
            showGuideDialog = true
        } else {
            val defaultAppsIntent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(defaultAppsIntent)
            } catch (e: Exception) {
                try {
                    val miuiIntent = Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(miuiIntent)
                } catch (e2: Exception) {
                    try {
                        context.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    } catch (_: Exception) {}
                }
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshAllStatus(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.init(context)
        viewModel.loadInstalledApps(context.packageManager, context)
    }

    MainPageScaffold(
        title = "LinkGo",
        actions = {
            IconButton(onClick = onNavigateToHelp) {
                Icon(
                    imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_help_circle),
                    contentDescription = "帮助文档",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    ) { innerPadding ->
        val animatedBottomPadding by animateDpAsState(
            targetValue = if (isImeVisible) {
                16.dp
            } else {
                bottomPadding + 32.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            },
            animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
            label = "HomeScreenBottomPadding"
        )

        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
                .imePadding(),
            contentPadding = PaddingValues(
                bottom = animatedBottomPadding
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
            }

            item(key = "service_status") {
                EngineHealthBadge(
                    isDefaultBrowser = isDefault,
                    clipboardEnabled = clipboardEnabled,
                    clipboardBackend = clipboardBackend,
                    appLinkCaptureMode = appLinkCaptureMode,
                    appLinkCapturedAppsCount = appLinkCapturedAppsCount,
                    edgeGestureEnabled = edgeGestureConfig.enabled,
                    privilegeMode = privilegeMode,
                    onBrowserClick = { handleBrowserActivation() },
                    onClipboardClick = onNavigateToClipboardMonitor,
                    onAppLinkClick = onNavigateToAppLinkCapture,
                    onEdgeGestureClick = onNavigateToEdgeGesture
                )
            }

            item(key = "runtime_data") {
                SettingsSection(topLabel = "运行数据") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            MetricStatCard(
                                value = todayJumpCount.toString(),
                                label = "今日分发",
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                                contentColor = MaterialTheme.colorScheme.primary,
                                onClick = onNavigateToHistory,
                                modifier = Modifier.weight(1f)
                            )
                            MetricStatCard(
                                value = totalJumpCount.toString(),
                                label = "累计分发",
                                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                                contentColor = MaterialTheme.colorScheme.secondary,
                                onClick = onNavigateToHistory,
                                modifier = Modifier.weight(1f)
                            )
                            MetricStatCard(
                                value = "$enabledRulesCount/$totalRulesCount",
                                label = "生效规则",
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.8f),
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                onClick = onNavigateToRules,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Surface(
                            onClick = onNavigateToHistory,
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_clock),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = latestJumpInfo?.let { "最近: $it" } ?: "暂无分发记录，点击查看记录",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.Medium
                                        ),
                                        color = if (latestJumpInfo != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Icon(
                                    imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_nav_arrow_right),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }

            item(key = "fallback_settings") {
                SettingsSection(topLabel = "兜底设置") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showSelector = true }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (fallbackApp != null) {
                            AsyncAppIcon(
                                packageName = fallbackApp!!.packageName,
                                size = 40.dp,
                                shape = RoundedCornerShape(10.dp)
                            )
                        } else {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_open_new_window),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "备选浏览器",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = fallbackApp?.label ?: "未配置（未命中规则时手动选择）",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (fallbackApp != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        FilledTonalButton(
                            onClick = { showSelector = true },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            modifier = Modifier.height(32.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = if (fallbackApp != null) "更换" else "去选择",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                    SettingsSectionDivider()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "备选启动模式",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )
                        PremiumSegmentedRow(
                            options = listOf(-1, 5, 1),
                            selectedOption = fallbackWindowMode,
                            onOptionSelected = { viewModel.updateFallbackWindowMode(it) },
                            labelProvider = { mode ->
                                when (mode) {
                                    -1 -> "全局默认"
                                    5 -> "小窗打开"
                                    1 -> "全屏打开"
                                    else -> "默认"
                                }
                            }
                        )
                    }
                    SettingsSectionDivider()
                    SettingItem(
                        headlineText = "任务列表可见性",
                        supportingText = "备选跳转后隐藏目标应用卡片",
                        trailingContent = {
                            PremiumSwitch(
                                checked = fallbackExcludeFromRecents,
                                onCheckedChange = { viewModel.updateFallbackExcludeFromRecents(it) }
                            )
                        }
                    )
                    SettingsSectionDivider()
                    SettingItem(
                        headlineText = "应用小窗预热 (实验性)",
                        supportingText = "提前唤醒浏览器后再触发跳转",
                        trailingContent = {
                            PremiumSwitch(
                                checked = fallbackPreheatEnabled,
                                onCheckedChange = { viewModel.updateFallbackPreheatEnabled(it) }
                            )
                        }
                    )
                    AnimatedVisibility(
                        visible = fallbackPreheatEnabled,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column {
                            SettingsSectionDivider()
                            Row(
                                modifier = Modifier
                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                                    .fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = "预热延迟",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                NativeOutlinedTextField(
                                    value = fallbackPreheatDelay.toString(),
                                    onValueChange = {
                                        if (it.all { char -> char.isDigit() }) {
                                            viewModel.updateFallbackPreheatDelay(it.toLongOrNull() ?: 0L)
                                        }
                                    },
                                    hint = "建议 300-3000",
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    inputType = android.text.InputType.TYPE_CLASS_NUMBER,
                                    trailingIcon = {
                                        Text(
                                            "ms",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(end = 12.dp)
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }

            item(key = "link_test") {
                val displayApps = remember(matchingApps, recommendedPackages) {
                    if (recommendedPackages.isEmpty()) {
                        matchingApps
                    } else {
                        // 倒序布局规范：通用备选浏览器排在网格上方，专属推荐应用排在网格底部（紧挨操作按钮，倒序就近触达）
                        matchingApps.sortedBy { it.packageName in recommendedPackages }
                    }
                }

                SettingsSection(topLabel = "链接测试") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        // 1. 可用浏览器与匹配应用（统一网格不分栏，带重组平滑过渡）
                        if (displayApps.isNotEmpty()) {
                            Text(
                                text = if (urlInput.isBlank()) "可用浏览器应用" else "支持响应此链接的应用",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            AnimatedContent(
                                targetState = displayApps,
                                transitionSpec = {
                                    (fadeIn(animationSpec = tween(200, delayMillis = 30)) +
                                     scaleIn(initialScale = 0.97f, animationSpec = tween(200, delayMillis = 30)))
                                        .togetherWith(
                                            fadeOut(animationSpec = tween(150)) +
                                            scaleOut(targetScale = 0.97f, animationSpec = tween(150))
                                        )
                                },
                                label = "AppGridTransition"
                            ) { apps ->
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    apps.chunked(4).forEach { rowApps ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            rowApps.forEach { app ->
                                                HomeAppGridItem(
                                                    app = app,
                                                    isPreferred = app.packageName in recommendedPackages,
                                                    onClick = {
                                                        viewModel.addRuleForApp(app) { name, pattern, packageInfo ->
                                                            onNavigateToEditRule(null, name, pattern, packageInfo)
                                                        }
                                                    },
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }
                                            repeat(4 - rowApps.size) {
                                                Spacer(modifier = Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                        }

                        // 2. 两个按钮（清空内容 / 粘贴最新）
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.onUrlChange(context, "") },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 8.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(
                                    imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_trash),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("清空内容", style = MaterialTheme.typography.labelMedium)
                            }
                            Button(
                                onClick = {
                                    clipboardManager.getText()?.text?.let {
                                        viewModel.onUrlChange(context, it)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 8.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(
                                    imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_paste_clipboard),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("粘贴最新", style = MaterialTheme.typography.labelMedium)
                            }
                        }

                        // 3. 输入框（最底部，挂载 bringIntoViewRequester）
                        Spacer(modifier = Modifier.height(10.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .bringIntoViewRequester(bringIntoViewRequester)
                        ) {
                            NativeOutlinedTextField(
                                value = urlInput,
                                onValueChange = { viewModel.onUrlChange(context, it) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 120.dp),
                                hint = "输入或粘贴内容",
                                cornerRadius = 14.dp,
                                maxLines = 4,
                                singleLine = false,
                                onFocusChange = { focused ->
                                    isInputFocused = focused
                                }
                            )
                        }
                    }
                }
            }
        }

        if (showSelector) {
            AppSelectorDialog(
                apps = appList,
                currentSelected = fallbackBrowser,
                onDismiss = { showSelector = false },
                onSelect = { pkg: String ->
                    viewModel.toggleFallbackBrowser(pkg)
                    showSelector = false
                }
            )
        }

        if (showGuideDialog) {
            ActivationGuideDialog(
                onDismiss = { showGuideDialog = false },
                onConfirm = {
                    showGuideDialog = false
                    if (isDefault) {
                        try {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                        } catch (e: Exception) {
                            context.startActivity(Intent(Settings.ACTION_SETTINGS))
                        }
                    } else {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val roleManager = context.getSystemService(RoleManager::class.java)
                            val intent = roleManager?.createRequestRoleIntent(RoleManager.ROLE_BROWSER)
                            if (intent != null) {
                                roleLauncher.launch(intent)
                            }
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun MetricStatCard(
    value: String,
    label: String,
    containerColor: Color,
    contentColor: Color,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick ?: {},
        enabled = onClick != null,
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Black,
                    fontSize = 20.sp
                ),
                color = contentColor,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun HomeAppGridItem(
    app: AppInfo,
    isPreferred: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val animatedBgColor by animateColorAsState(
        targetValue = if (isPreferred) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        },
        animationSpec = tween(durationMillis = 220),
        label = "AppGridItemBg"
    )
    val animatedBorderColor by animateColorAsState(
        targetValue = if (isPreferred) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        } else {
            androidx.compose.ui.graphics.Color.Transparent
        },
        animationSpec = tween(durationMillis = 220),
        label = "AppGridItemBorder"
    )

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = animatedBgColor,
        border = BorderStroke(1.dp, animatedBorderColor),
        modifier = modifier.padding(vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(contentAlignment = Alignment.TopEnd) {
                AsyncAppIcon(
                    packageName = app.packageName,
                    size = 36.dp,
                    shape = RoundedCornerShape(10.dp)
                )
                PreferredBadge(visible = isPreferred)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = app.label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun PreferredBadge(visible: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.4f, animationSpec = tween(200, easing = FastOutSlowInEasing)),
        exit = fadeOut(tween(140)) + scaleOut(targetScale = 0.4f, animationSpec = tween(140))
    ) {
        Box(
            modifier = Modifier
                .offset(x = 4.dp, y = (-2).dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.primary)
                .padding(horizontal = 3.dp, vertical = 1.dp)
        ) {
            Text(
                text = "荐",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

@Composable
fun ActivationGuideDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val context = LocalContext.current
    var countdown by remember { mutableIntStateOf(5) }

    LaunchedEffect(Unit) {
        while (countdown > 0) {
            delay(1000L)
            countdown--
        }
    }

    val appIcon = remember(context) {
        try {
            context.packageManager.getApplicationIcon(context.packageName)
        } catch (e: Exception) {
            null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = "设置默认浏览器",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "请在接下来的系统弹窗中手动选中",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(20.dp))

                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.5.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (appIcon != null) {
                            Image(
                                bitmap = appIcon.toBitmap().asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                            )
                        } else {
                            Icon(
                                imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_home),
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "LinkGo",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "只有设为默认浏览器后\n微信、QQ等外部链接才能正常被接管分发",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = countdown == 0,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = if (countdown > 0) "请阅读指引 (${countdown}s)" else "我知道了，去设置",
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = null,
        shape = RoundedCornerShape(24.dp)
    )
}

@Composable
fun AppSelectorDialog(
    apps: List<AppInfo>,
    currentSelected: String?,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val sortedApps = remember(apps, currentSelected) {
        apps.sortedWith(compareByDescending { it.packageName == currentSelected })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "选择备选浏览器",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(sortedApps.size) { index ->
                    val app = sortedApps[index]
                    val isSelected = app.packageName == currentSelected
                    Surface(
                        onClick = { onSelect(app.packageName) },
                        shape = RoundedCornerShape(14.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(12.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncAppIcon(
                                packageName = app.packageName,
                                size = 40.dp,
                                shape = RoundedCornerShape(10.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = app.label,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                )
                                Text(
                                    text = app.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            if (isSelected) {
                                Icon(
                                    imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_check),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSelect("") }) {
                Text("清空选择", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surface
    )
}

