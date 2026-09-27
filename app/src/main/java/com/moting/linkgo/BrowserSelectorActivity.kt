package com.moting.linkgo

import android.content.Intent
import android.app.PendingIntent
import android.os.Bundle
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.content.Context
import android.widget.Toast
import com.moting.linkgo.util.NotificationHelper
import android.content.pm.verify.domain.DomainVerificationManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.lifecycleScope
import com.moting.linkgo.model.AppInfo
import com.moting.linkgo.data.PackageRepository
import com.moting.linkgo.data.SettingsRepository
import com.moting.linkgo.ui.components.RuleIcon
import com.moting.linkgo.ui.components.SettingItem
import com.moting.linkgo.ui.theme.链接跳转Theme
import com.moting.linkgo.util.WindowRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import com.moting.linkgo.ui.components.reorderable.*

/**
 * 备选浏览器选择器 (半屏宫格版)
 */
class BrowserSelectorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(android.app.Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
            overrideActivityTransition(android.app.Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply {
                blurBehindRadius = 0
            }
            window.setDimAmount(0f)
        }

        val url = intent.getStringExtra("URL") ?: ""
        val sourceStr = intent.getStringExtra("SOURCE") ?: "SMART"
        val source = WindowRouter.DispatchSource.valueOf(sourceStr)
        val traceId = intent.getStringExtra("TRACE_ID")
        val stepIndex = intent.getIntExtra("STEP_INDEX", 0)

        if (url.isBlank()) {
            finish()
            return
        }

        setContent {
            val repository = remember { SettingsRepository(this) }
            val dynamicColorEnabled by repository.dynamicColorEnabled.collectAsState(initial = true)
            val backgroundBlurEnabled by repository.backgroundBlurEnabled.collectAsState(initial = repository.currentBackgroundBlurEnabled)

            链接跳转Theme(dynamicColor = dynamicColorEnabled) {
                BrowserSelectorScreen(
                    url = url,
                    traceId = traceId,
                    stepIndex = stepIndex,
                    blurEnabled = backgroundBlurEnabled,
                    onDismiss = { finish() }
                )
            }
        }
    }

    fun updateBlurProgress(progress: Float, blurEnabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (blurEnabled) {
                val currentRadius = (40 * progress).toInt().coerceAtLeast(0)
                window.attributes = window.attributes.apply {
                    blurBehindRadius = currentRadius
                }
                window.setDimAmount(0.28f * progress)
            } else {
                window.attributes = window.attributes.apply {
                    blurBehindRadius = 0
                }
                window.setDimAmount(0.48f * progress)
            }
        } else {
            window.setDimAmount(0.48f * progress)
        }
    }

    override fun finish() {
        super.finish()
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(android.app.Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
            overrideActivityTransition(android.app.Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}

enum class SelectorMode {
    JUMP, RULE
}

data class BrowserData(
    val sortedBrowsers: List<AppInfo>,
    val recommendedApps: List<AppInfo>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserSelectorScreen(
    url: String,
    traceId: String? = null,
    stepIndex: Int = 0,
    blurEnabled: Boolean = true,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { SettingsRepository(context) }
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    // --- 统一动画规范 (强制对齐所有弹簧节奏，消除抖动) ---
    val universalStiffness = Spring.StiffnessMediumLow
    val universalDamping = Spring.DampingRatioNoBouncy
    
    val heightSpringSpec = remember { spring<Dp>(stiffness = universalStiffness, dampingRatio = universalDamping) }
    val placementSpringSpec = remember { spring<IntOffset>(stiffness = universalStiffness, dampingRatio = universalDamping) }
    val fadeSpec = remember { tween<Float>(durationMillis = 300, easing = LinearOutSlowInEasing) }
    val slideSpec = remember { tween<IntOffset>(durationMillis = 300, easing = FastOutSlowInEasing) }
    
    // 扩展动画规范：用于 visibility
    val visibilitySpec = remember { spring<Float>(stiffness = universalStiffness, dampingRatio = universalDamping) }
    // -----------------

    // 状态
    var showSheet by remember { mutableStateOf(false) }
    var browsers by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var recommendedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var iconMap by remember { mutableStateOf<Map<String, android.graphics.drawable.Drawable>>(emptyMap()) } // 图标直传 Map
    val hiddenList by repository.browserHiddenList.collectAsState(initial = emptyList())
    val orderList by repository.browserOrderList.collectAsState(initial = emptyList())
    val timerSeconds by repository.browserSelectorTimer.collectAsState(initial = 5)
    var currentMode by remember { mutableStateOf(SelectorMode.JUMP) }
    var isEditing by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }

    // 进场动画与异步数据加载 (渐进式加载优化)
    val blurProgress by animateFloatAsState(
        targetValue = if (showSheet) 1f else 0f,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "blurProgress"
    )

    val currentAct = context as? BrowserSelectorActivity
    LaunchedEffect(blurProgress, blurEnabled) {
        currentAct?.updateBlurProgress(blurProgress, blurEnabled)
    }

    LaunchedEffect(Unit) {
        showSheet = true
        yield() // 释放一帧，确保进场动画首帧能立即渲染
        
        // 阶段 1：快速加载所有浏览器列表并开启预热
        val allBrowsers = withContext(Dispatchers.IO) { PackageRepository.getInstalledBrowsers(context) }
        val initialHidden = repository.browserHiddenList.first()
        val initialOrder = repository.browserOrderList.first()
        
        browsers = allBrowsers.sortedWith(compareBy<AppInfo> { it.packageName in initialHidden }.thenBy { pkg ->
            val index = initialOrder.indexOf(pkg.packageName)
            if (index == -1) 1000 else index
        })
        
        // --- 核心优化：后台全量预热并直接持有图标 ---
        launch(Dispatchers.IO) {
            val loadedMap = mutableMapOf<String, android.graphics.drawable.Drawable>()
            browsers.forEach { pkg ->
                PackageRepository.getAppIcon(context, pkg.packageName)?.let {
                    loadedMap[pkg.packageName] = it
                }
            }
            withContext(Dispatchers.Main) {
                iconMap = loadedMap
            }
        }
        // ------------------------------
        
        isLoading = false // 立即结束 Loading 状态
        
        // 阶段 2：异步探测推荐原生 App
        recommendedApps = loadRecommendedApps(context, url)
        
        // 预热推荐 App 的图标并更新 iconMap
        launch(Dispatchers.IO) {
            val updatedMap = iconMap.toMutableMap()
            recommendedApps.forEach { app ->
                PackageRepository.getAppIcon(context, app.packageName)?.let {
                    updatedMap[app.packageName] = it
                }
            }
            withContext(Dispatchers.Main) {
                iconMap = updatedMap
            }
        }
    }

    // 统一显示列表：包含所有应用，以便蒙版切换
    val displayList = remember(browsers, recommendedApps, isEditing) {
        if (isEditing) {
            browsers // 管理模式：不显示推荐 App
        } else {
            val filteredRecommended = recommendedApps.filter { rec ->
                browsers.none { it.packageName == rec.packageName }
            }
            browsers + filteredRecommended
        }
    }

    val recommendedPackageNames = remember(recommendedApps) { recommendedApps.map { it.packageName }.toSet() }

    // 倒计时逻辑
    var remainingTime by remember(timerSeconds) { mutableStateOf(timerSeconds.toFloat()) }
    val isTimerActive = currentMode == SelectorMode.JUMP && !isEditing && displayList.isNotEmpty() && !isLoading && timerSeconds > 0

    LaunchedEffect(isTimerActive, timerSeconds) {
        if (isTimerActive && timerSeconds > 0) {
            val totalMillis = timerSeconds * 1000L
            val startTime = System.nanoTime() / 1_000_000L
            
            while (true) {
                val currentTime = System.nanoTime() / 1_000_000L
                val elapsed = currentTime - startTime
                remainingTime = ((totalMillis - elapsed) / 1000f).coerceAtLeast(0f)
                
                if (remainingTime <= 0f) break
                withFrameMillis { } // 关键：同步显示刷新率（60/90/120Hz）
            }
            
            // 触发自动跳转
            if (displayList.isNotEmpty()) {
                scope.launch {
                    executeJump(context, url, displayList[0].packageName, false, traceId, stepIndex)
                    onDismiss()
                }
            }
        }
    }

    // 退出逻辑
    val closeWithAnimation = {
        showSheet = false
    }

    BackHandler(enabled = showSheet) {
        closeWithAnimation()
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        // 背景遮罩（模糊开启时降低纯黑透明度，透显底层全屏高斯模糊）
        val maskAlpha = if (blurEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 0.15f else 0.45f
        AnimatedVisibility(
            visible = showSheet,
            enter = fadeIn(animationSpec = fadeSpec),
            exit = fadeOut(animationSpec = fadeSpec)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = maskAlpha))
                    .clickable(onClick = closeWithAnimation)
            )
        }

        // 主体卡片
        AnimatedVisibility(
            visible = showSheet,
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = slideSpec) + fadeIn(animationSpec = fadeSpec),
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = slideSpec) + fadeOut(animationSpec = fadeSpec)
        ) {
            DisposableEffect(Unit) {
                onDispose {
                    if (!showSheet) onDismiss()
                }
            }

            // --- 统一高度管理 (动态计算目标高度) ---
            val navBarHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val hasDivider = (isEditing || currentMode == SelectorMode.RULE) && displayList.any { it.packageName in hiddenList }
            
            // 基础固定高度：纯净沉浸头部(52dp) + 链接卡片(if visible 68dp)
            val headerBase = 52.dp
            val cardHeight = if (!isEditing) 68.dp else 0.dp
            val gridTopPadding = 16.dp
            
            // 计算可见项对应的行数
            val visibleApps = if (isEditing || currentMode == SelectorMode.RULE) {
                displayList
            } else {
                displayList.filter { it.packageName !in hiddenList }
            }
            
            val columns = 4
            val rowHeight = 86.dp // 图标+文字+间距的实际高度
            
            val visibleAppCount = visibleApps.size
            val rows = if (visibleAppCount == 0) 1 else ((visibleAppCount + columns - 1) / columns)
            
            // 最终高度 = 头部 + 卡片 + 网格内间距 + 行高*行数 + (可选)分割线高度(52dp) + 导航栏
            val dividerHeight = if (hasDivider && currentMode != SelectorMode.JUMP) 52.dp else 0.dp
            val targetPanelHeight = (headerBase + cardHeight + gridTopPadding + (rowHeight * rows) + dividerHeight + navBarHeight + 16.dp)
                .coerceIn(300.dp, 800.dp)

            val animatedHeight by animateDpAsState(
                targetValue = targetPanelHeight,
                animationSpec = heightSpringSpec,
                label = "panelHeight"
            )

            val isDark = isSystemInDarkTheme()
            val cardColor = if (blurEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (isDark) {
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)
                } else {
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                }
            } else {
                MaterialTheme.colorScheme.surface
            }

            val borderStroke = if (blurEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BorderStroke(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        listOf(
                            if (isDark) Color.White.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.60f),
                            if (isDark) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.18f),
                            Color.Transparent
                        )
                    )
                )
            } else {
                null
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(animatedHeight),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = cardColor,
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = borderStroke,
                tonalElevation = 0.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                ) {
                    // Header: 纯净沉浸单行顶栏 (标题 + 模式切换 + 编辑按钮)
                    SelectorHeader(
                        isEditing = isEditing,
                        currentMode = currentMode,
                        timerSeconds = timerSeconds,
                        onModeChange = { currentMode = it },
                        onEditingChange = { editing ->
                            isEditing = editing
                            if (!editing) {
                                scope.launch {
                                    repository.updateBrowserOrderList(browsers.map { it.packageName })
                                }
                            }
                        },
                        onOpenSettings = {
                            val intent = Intent(context, MainActivity::class.java).apply {
                                action = "com.moting.linkgo.ACTION_OPEN_SETTINGS"
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            }
                            context.startActivity(intent)
                            onDismiss()
                        },
                        fadeSpec = fadeSpec
                    )

                    // 链接显示面板 (顶部内嵌版) - 管理模式下隐藏以释放空间
                    AnimatedVisibility(
                        visible = !isEditing,
                        enter = expandVertically(animationSpec = spring(stiffness = universalStiffness, dampingRatio = universalDamping)) + fadeIn(),
                        exit = shrinkVertically(animationSpec = spring(stiffness = universalStiffness, dampingRatio = universalDamping)) + fadeOut()
                    ) {
                        LinkInfoCard(
                            url = url,
                            onCopy = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("link", url)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "链接已复制", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }

                    // 宫格区域
                    if (isLoading) {
                        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        BrowserGrid(
                            modifier = Modifier.weight(1f), // 核心：占据剩余空间并支持滚动
                            displayList = displayList,
                            browsers = browsers,
                            hiddenList = hiddenList,
                            recommendedPackageNames = recommendedPackageNames,
                            isEditing = isEditing,
                            isTimerActive = isTimerActive,
                            timerSeconds = timerSeconds,
                            remainingTime = remainingTime,
                            currentMode = currentMode,
                            placementSpringSpec = placementSpringSpec,
                            onBrowsersChange = { browsers = it },
                            onToggleHidden = { app ->
                                if (app.packageName in recommendedPackageNames) return@BrowserGrid
                                val isNowHidden = app.packageName !in hiddenList
                                val newList = if (isNowHidden) hiddenList + app.packageName else hiddenList - app.packageName
                                scope.launch { repository.updateBrowserHiddenList(newList) }
                                
                                // 状态切换时自动触发重排序
                                val hiddenSet = if (isNowHidden) hiddenList + app.packageName else hiddenList - app.packageName
                                browsers = browsers.toMutableList().apply {
                                    val currentIndex = indexOfFirst { it.packageName == app.packageName }
                                    if (currentIndex != -1) {
                                        val item = removeAt(currentIndex)
                                        // 无论是隐藏还是取消隐藏，都移到可见项末尾（第一个隐藏项之前）
                                        val targetIndex = indexOfFirst { it.packageName in hiddenSet }.let { if (it == -1) size else it }
                                        add(targetIndex, item)
                                    }
                                }
                            },
                            onJump = { app, isReverse ->
                                val target = if (!app.className.isNullOrBlank()) "${app.packageName}/${app.className}" else app.packageName
                                scope.launch {
                                    executeJump(context, url, target, isReverse, traceId, stepIndex)
                                    onDismiss()
                                }
                            },
                            onCreateRule = { app ->
                                val target = if (!app.className.isNullOrBlank()) "${app.packageName}/${app.className}" else app.packageName
                                navigateToCreateRule(context, url, target)
                                onDismiss()
                            },
                            universalStiffness = universalStiffness,
                            universalDamping = universalDamping,
                            iconMap = iconMap
                        )
                    }
                }
            }
        }
    }
}
// 自定义 Painter 用于绘制 Drawable，避免依赖外部库
@Composable
fun rememberDrawablePainter(drawable: android.graphics.drawable.Drawable?): androidx.compose.ui.graphics.painter.Painter {
    return remember(drawable) {
        object : androidx.compose.ui.graphics.painter.Painter() {
            override val intrinsicSize: androidx.compose.ui.geometry.Size
                get() = drawable?.let { 
                    androidx.compose.ui.geometry.Size(it.intrinsicWidth.toFloat(), it.intrinsicHeight.toFloat()) 
                } ?: androidx.compose.ui.geometry.Size.Unspecified

            override fun androidx.compose.ui.graphics.drawscope.DrawScope.onDraw() {
                drawable?.let {
                    it.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                    it.draw(drawContext.canvas.nativeCanvas)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BrowserGridItem(
    app: AppInfo,
    isFirst: Boolean,
    isTimerActive: Boolean,
    progress: Float,
    currentMode: SelectorMode,
    isEditing: Boolean,
    isDragging: Boolean = false,
    isHidden: Boolean,
    isRecommended: Boolean = false,
    onToggleHidden: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    preloadedIcon: android.graphics.drawable.Drawable? = null, 
    maskProgress: Float = 1f, // 新增：用于文字延迟显现
    modifier: Modifier = Modifier
) {
    val scale by animateFloatAsState(if (isDragging) 1.15f else 1f, label = "dragScale")

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .width(80.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                shape = RoundedCornerShape(12.dp)
                clip = true
            }
            .alpha(if (isHidden && currentMode != SelectorMode.RULE) 0.5f else 1.0f)
            .clip(RoundedCornerShape(12.dp))
            .background(if (currentMode == SelectorMode.RULE && !isEditing) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f) else Color.Transparent)
            .then(
                if (!isEditing) {
                    Modifier.combinedClickable(
                        onClick = onClick,
                        onLongClick = onLongClick
                    )
                } else {
                    Modifier.clickable { onToggleHidden() }
                }
            )
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Box(
            modifier = Modifier.size(60.dp),
            contentAlignment = Alignment.Center
        ) {
            // 核心修复：使用预加载图标，消除异步加载抖动
            if (preloadedIcon != null) {
                androidx.compose.foundation.Image(
                    painter = rememberDrawablePainter(preloadedIcon),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                )
            } else {
                // 回退方案：如果没预加载好，显示占位
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                )
            }

            // 倒计时进度条 (圆角矩形风格，带呼吸间距)
            if (isFirst && isTimerActive && progress > 0f) {
                val primaryColor = MaterialTheme.colorScheme.primary
                val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                
                Canvas(modifier = Modifier.size(60.dp)) {
                    val sw = 3.dp.toPx()
                    val iconRadius = 12.dp.toPx()
                    
                    // 60dp 容器，48dp 图标，图标边缘在 6dp 处
                    // 我们想要 2dp 的间隙，则进度条内边缘在 4dp 处
                    // 进度条中线在 4dp - 1.5dp = 2.5dp 处
                    val inset = 2.5.dp.toPx()
                    val drawSize = size.width - inset * 2
                    
                    // 计算同心圆角：内圆角 12dp + (中线到图标边缘的距离 3.5dp) = 15.5dp
                    val drawRadius = iconRadius + 3.5.dp.toPx()
                    
                    val rect = Rect(Offset(inset, inset), Size(drawSize, drawSize))
                    val roundedRect = RoundRect(rect, CornerRadius(drawRadius))
                    
                    // 绘制底色轨道
                    drawRoundRect(
                        color = trackColor,
                        topLeft = rect.topLeft,
                        size = rect.size,
                        cornerRadius = CornerRadius(drawRadius),
                        style = Stroke(width = sw)
                    )
                    
                    // 绘制进度路径
                    val path = Path().apply {
                        addRoundRect(roundedRect)
                    }
                    
                    val pathMeasure = PathMeasure()
                    pathMeasure.setPath(path, false)
                    val pathLength = pathMeasure.length
                    
                    val resultPath = Path()
                    pathMeasure.getSegment(0f, pathLength * progress, resultPath, true)
                    
                    drawPath(
                        path = resultPath,
                        color = primaryColor,
                        style = Stroke(width = sw, cap = StrokeCap.Round)
                    )
                }
            }

            // 推荐角标
            if (isRecommended && !isEditing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(4.dp),
                    contentAlignment = Alignment.TopEnd
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.wrapContentSize(),
                        tonalElevation = 4.dp
                    ) {
                        Text(
                            text = "推荐",
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 0.dp),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // 延迟淡入优化：在动画接近完成时才显示文字，减少位移过程中的像素抖动
        val textAlpha = if (isHidden) {
            ((maskProgress - 0.7f) / 0.3f).coerceIn(0f, 1f)
        } else 1f

        Text(
            text = app.label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            fontWeight = if (isFirst && !isEditing) FontWeight.Bold else FontWeight.Normal,
            color = if (isFirst && !isEditing && currentMode == SelectorMode.JUMP) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(top = 4.dp)
                .graphicsLayer { alpha = textAlpha } // 性能优化：绘图层处理
        )
    }
}
/**
 * 执行跳转逻辑 (包含智能反转)
 */
suspend fun executeJump(context: android.content.Context, url: String, targetPackage: String, isReverse: Boolean, traceId: String? = null, stepIndex: Int = 0) {
    withContext(Dispatchers.IO) {
        val repository = SettingsRepository(context)
        val config = repository.windowConfig.first()
        val fallbackMode = repository.fallbackWindowMode.first()
        val isPreheatSettingEnabled = repository.fallbackPreheatEnabled.first()
        val preheatDelay = repository.fallbackPreheatDelay.first()
        val isExcludeFromRecents = repository.fallbackExcludeFromRecents.first()

        // 1. 计算默认模式 (支持 5, 100, 102 等厂商模式)
        val defaultMode = when (fallbackMode) {
            1 -> 1 // 强制普通
            5 -> config.windowingMode // 强制小窗
            else -> if (config.isEnabled) config.windowingMode else 1 // 跟随全局
        }

        // 2. 计算最终应用模式
        val finalMode = if (!isReverse) {
            defaultMode
        } else {
            // 反转逻辑：如果当前是小窗模式，则强制普通；反之则开小窗
            if (defaultMode != 1) 1 else config.windowingMode
        }

        // 3. 确定是否需要预热：仅当开启了预热设置且目标是小窗时才预热
        val shouldPreheat = isPreheatSettingEnabled && finalMode != 1

        // 获取应用名称作为展示名称
        val pm = context.packageManager
        val realPkg = if (targetPackage.contains("/")) targetPackage.substringBefore("/") else targetPackage
        val targetLabel = try {
            val info = pm.getApplicationInfo(realPkg, 0)
            info.loadLabel(pm).toString()
        } catch (e: Exception) {
            "备选浏览器"
        }

        val appContext = context.applicationContext
        val showRich = repository.showRichNotification.first()
        val showTriggerToast = repository.showTriggerToast.first()

        withContext(Dispatchers.Main) {
            var chipShown = false
            if (showRich && Build.VERSION.SDK_INT >= 36) {
                try {
                    val appIcon: android.graphics.drawable.Drawable = PackageRepository.getAppIcon(context, realPkg) 
                        ?: pm.getApplicationIcon(realPkg)
                    val jumpPi = PendingIntent.getActivity(
                        appContext, 0,
                        Intent(appContext, LinkDispatcherActivity::class.java).apply {
                            action = Intent.ACTION_VIEW
                            data = Uri.parse(url)
                            putExtra("FROM_NOTIFICATION", true)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                        PendingIntent.FLAG_IMMUTABLE
                    )
                    chipShown = NotificationHelper.show(appContext, NotificationHelper.LiveActivityData(
                        icon = appIcon,
                        title = targetLabel,
                        bigTitle = targetLabel,
                        body = url.take(96),
                        fullText = url,
                        rawUrl = url,
                        bigIcon = appIcon,
                        contentIntent = jumpPi,
                        actionLabel = "直接跳转",
                        actionIntent = jumpPi
                    ))
                } catch (e: Exception) {
                    Log.e("BrowserSelector", "手动选择跳转通知发送失败", e)
                }
            }
            if (!chipShown && showTriggerToast) {
                Toast.makeText(appContext, "跳转: $targetLabel", Toast.LENGTH_SHORT).show()
            }
        }

        var status = 0
        var errorMsg: String? = null
        
        try {
            // 4. 执行启动
            val launchOk = WindowRouter.performLaunchInternal(
                context = context,
                url = url,
                targetPackage = targetPackage,
                windowMode = finalMode,
                template = null,
                extractPattern = null,
                isFallback = true,
                isPreheatEnabled = shouldPreheat,
                preheatDelayMillis = if (shouldPreheat) preheatDelay else 0L,
                excludeFromRecents = isExcludeFromRecents,
                skipProcessing = true // 已经由上级处理过 URL
            )
            if (!launchOk) {
                status = 1
                val cleanPkg = if (targetPackage.contains("/")) targetPackage.substringBefore("/") else targetPackage
                val isInstalled = PackageRepository.isAppInstalled(context, cleanPkg)
                val failedMsg = if (!isInstalled) {
                    "未安装目标应用 $targetLabel，跳转失败"
                } else {
                    "目标应用已停用或冻结，跳转失败"
                }
                errorMsg = failedMsg
                Log.w("BrowserSelector", "手动选择跳转失败: $errorMsg")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, failedMsg, Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            status = 1
            errorMsg = e.localizedMessage ?: e.toString()
            Log.e("BrowserSelector", "启动失败", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "跳转失败", Toast.LENGTH_SHORT).show()
            }
        } finally {
            // 5. 记录历史
            repository.addJumpRecord(
                com.moting.linkgo.model.JumpRecord(
                    ruleName = "[手动选择] $targetLabel",
                    ruleId = null,
                    originalUrl = url,
                    targetPackage = targetPackage,
                    rulePattern = null,
                    matchTypeName = "备选",
                    executionStatus = status,
                    errorMessage = errorMsg,
                    traceId = traceId ?: java.util.UUID.randomUUID().toString(),
                    resultUrl = url,
                    stepIndex = stepIndex
                )
            )
        }
    }
}

/**
 * 跳转到规则创建页面
 */
fun navigateToCreateRule(context: android.content.Context, url: String, packageName: String) {
    val pm = context.packageManager
    val label = try {
        val info = if (packageName.contains("/")) {
            pm.getActivityInfo(android.content.ComponentName.unflattenFromString(packageName)!!, 0).loadLabel(pm)
        } else {
            pm.getApplicationInfo(packageName, 0).loadLabel(pm)
        }
        info.toString()
    } catch (e: Exception) {
        "新规则"
    }

    val intent = Intent(context, MainActivity::class.java).apply {
        action = "com.moting.linkgo.ACTION_CREATE_RULE"
        putExtra("EXTRA_URL", url)
        putExtra("EXTRA_PACKAGE", packageName)
        putExtra("EXTRA_NAME", label)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    context.startActivity(intent)
}

/**
 * 异步探测推荐的原生 App
 */
private suspend fun loadRecommendedApps(context: Context, url: String): List<AppInfo> = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    val uri = try { Uri.parse(url) } catch (e: Exception) { null }
    val host = uri?.host
    val domainDeclaredPackages = mutableSetOf<String>()
    
    // 1. 域名验证探测 (Android 12+)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !host.isNullOrBlank()) {
        try {
            val manager = context.getSystemService(DomainVerificationManager::class.java)
            pm.getInstalledPackages(0).forEach { pkg ->
                if (pkg.packageName != context.packageName) {
                    try {
                        val userState = manager.getDomainVerificationUserState(pkg.packageName)
                        if (userState?.hostToStateMap?.keys?.any { 
                            host.equals(it, true) || host.endsWith(".$it", true) 
                        } == true) {
                            domainDeclaredPackages.add(pkg.packageName)
                        }
                    } catch (e: Exception) {}
                }
            }
        } catch (e: Exception) {}
    }

    // 2. Intent 查询探测
    val queryIntent = Intent(Intent.ACTION_VIEW, uri)
    val queryInfos = if (uri != null) {
        pm.queryIntentActivities(queryIntent, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PackageManager.MATCH_ALL else 0)
    } else emptyList()

    // 提取原生 App 列表，尝试避开启动页
    val nativeApps = queryInfos
        .filter { it.activityInfo.packageName != context.packageName && it.activityInfo.packageName != "android" }
        .filter { (it.filter?.countDataAuthorities() ?: 0) > 0 || it.activityInfo.packageName in domainDeclaredPackages }
        .groupBy { it.activityInfo.packageName }
        .map { (pkg, resolves) ->
            val launcherIntent = pm.getLaunchIntentForPackage(pkg)
            val launcherCls = launcherIntent?.component?.className
            
            // 优先选择非启动页的 Activity
            val targetActivity = resolves.find { it.activityInfo.name != launcherCls } ?: resolves.first()
            
            AppInfo(
                label = targetActivity.loadLabel(pm).toString(),
                packageName = pkg,
                className = targetActivity.activityInfo.name
            )
        }

    // 3. 域名验证补充探测 (Android 12+ 深度探测)
    val additionalRecommended = domainDeclaredPackages
        .filter { pkg -> nativeApps.none { it.packageName == pkg } }
        .mapNotNull { pkg ->
            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                val label = appInfo.loadLabel(pm).toString()
                val launcherIntent = pm.getLaunchIntentForPackage(pkg)
                val launcherCls = launcherIntent?.component?.className
                var targetCls: String? = null

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val dvm = context.getSystemService(Context.DOMAIN_VERIFICATION_SERVICE) as DomainVerificationManager
                    val userState = dvm.getDomainVerificationUserState(pkg)
                    val domains = userState?.hostToStateMap?.keys ?: emptySet()
                    
                    val matchFlags = PackageManager.MATCH_ALL
                    for (domain in domains) {
                        val testIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://$domain"))
                        val resolves = pm.queryIntentActivities(testIntent, matchFlags)
                        val match = resolves.firstOrNull { it.activityInfo.packageName == pkg }
                        if (match != null) {
                            targetCls = match.activityInfo.name
                            if (targetCls != launcherCls) break
                        }
                    }
                }

                AppInfo(label = label, packageName = pkg, className = targetCls ?: launcherCls)
            } catch (e: Exception) { null }
        }
    
    (nativeApps + additionalRecommended).distinctBy { it.packageName }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectorHeader(
    isEditing: Boolean,
    currentMode: SelectorMode,
    timerSeconds: Int,
    onModeChange: (SelectorMode) -> Unit,
    onEditingChange: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    fadeSpec: FiniteAnimationSpec<Float>
) {
    AnimatedContent(
        targetState = isEditing,
        transitionSpec = {
            fadeIn(animationSpec = fadeSpec).togetherWith(
                fadeOut(animationSpec = fadeSpec)
            )
        },
        label = "topBarAnimation"
    ) { editing ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left Side
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (editing) "管理浏览器" else "选择浏览器",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                AnimatedContent(
                    targetState = currentMode,
                    transitionSpec = { fadeIn(animationSpec = fadeSpec).togetherWith(fadeOut(animationSpec = fadeSpec)) },
                    label = "subtitleAnimation"
                ) { mode ->
                    Text(
                        text = if (editing) "单击隐藏/显示，长按拖动排序" else if (mode == SelectorMode.JUMP) "单击跳转，长按反转模式" else "点击创建跳转规则",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Right Side
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (editing) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onOpenSettings() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (timerSeconds > 0) "${timerSeconds}s" else "已禁用",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (timerSeconds > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Box(modifier = Modifier.width(140.dp)) {
                        com.moting.linkgo.ui.components.PremiumSegmentedRow(
                            options = listOf(SelectorMode.JUMP, SelectorMode.RULE),
                            selectedOption = currentMode,
                            onOptionSelected = { onModeChange(it) },
                            labelProvider = { if (it == SelectorMode.JUMP) "跳转" else "规则" }
                        )
                    }
                }

                IconButton(
                    onClick = { onEditingChange(!isEditing) },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = Color.Transparent
                    )
                ) {
                    Icon(
                        imageVector = if (editing) Icons.Default.DoneAll else Icons.Default.Settings,
                        contentDescription = if (editing) "完成" else "管理",
                        tint = if (editing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun LinkInfoCard(
    url: String,
    onCopy: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Link,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            
            Text(
                text = url,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
            )
            
            IconButton(
                onClick = onCopy,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "复制",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun BrowserGrid(
    modifier: Modifier = Modifier,
    displayList: List<AppInfo>,
    browsers: List<AppInfo>,
    hiddenList: List<String>,
    recommendedPackageNames: Set<String>,
    isEditing: Boolean,
    isTimerActive: Boolean,
    timerSeconds: Int,
    remainingTime: Float,
    currentMode: SelectorMode,
    placementSpringSpec: FiniteAnimationSpec<IntOffset>,
    onBrowsersChange: (List<AppInfo>) -> Unit,
    onToggleHidden: (AppInfo) -> Unit,
    onJump: (AppInfo, Boolean) -> Unit,
    onCreateRule: (AppInfo) -> Unit,
    universalStiffness: Float,
    universalDamping: Float,
    iconMap: Map<String, android.graphics.drawable.Drawable>
) {
    val gridState = rememberLazyGridState()
    
    // --- 核心修复：构建始终全量的扁平化列表，避免切换模式时重布局 ---
    val flatList = remember(displayList, hiddenList) {
        val visible = displayList.filter { it.packageName !in hiddenList }
        val hidden = displayList.filter { it.packageName in hiddenList }
        
        val list = mutableListOf<GridItem>()
        visible.forEach { list.add(GridItem.App(it)) }
        
        // 始终保留分割线和隐藏项在列表中，通过外层蒙版控制可见度
        if (hidden.isNotEmpty()) {
            list.add(GridItem.Header("隐藏应用"))
            hidden.forEach { list.add(GridItem.App(it)) }
        }
        list
    }

    val reorderableState = rememberReorderableLazyGridState(gridState) { from, to ->
        if (currentMode == SelectorMode.RULE) return@rememberReorderableLazyGridState
        
        val fromItem = flatList.getOrNull(from.index) as? GridItem.App ?: return@rememberReorderableLazyGridState
        val toItem = flatList.getOrNull(to.index) ?: return@rememberReorderableLazyGridState
        
        // 场景 A：与分隔符交换位置 (跨栏)
        if (toItem is GridItem.Header) {
            // 触发状态切换
            onToggleHidden(fromItem.app)
            return@rememberReorderableLazyGridState
        }

        // 场景 B：同类 App 交换位置
        val toApp = (toItem as GridItem.App).app
        
        // 映射回原始 browsers 列表进行持久化
        val fromInBrowsers = browsers.indexOfFirst { it.packageName == fromItem.app.packageName }
        val toInBrowsers = browsers.indexOfFirst { it.packageName == toApp.packageName }
        
        if (fromInBrowsers != -1 && toInBrowsers != -1) {
            // 在执行排序的同时，检查是否发生了事实上的跨栏（比如快速划过导致错过了 Header 的触发）
            val fromIsVisible = fromItem.app.packageName !in hiddenList
            val toIsVisible = toApp.packageName !in hiddenList
            if (fromIsVisible != toIsVisible) {
                onToggleHidden(fromItem.app)
            }
            
            onBrowsersChange(browsers.toMutableList().apply {
                add(toInBrowsers, removeAt(fromInBrowsers))
            })
        }
    }

    // 计算蒙版裁剪比例 (JUMP 模式下只展示前半部分)
    val totalCount = flatList.size
    val visibleInJump = displayList.filter { it.packageName !in hiddenList }.size
    val jumpRows = if (visibleInJump == 0) 1 else ((visibleInJump + 3) / 4)
    val totalRows = if (totalCount == 0) 1 else {
        val apps = flatList.filterIsInstance<GridItem.App>().size
        val hasHeader = flatList.any { it is GridItem.Header }
        ((apps + 3) / 4) + (if (hasHeader) 1 else 0)
    }
    
    // 使用 animateFloatAsState 实现顺滑的蒙版开启/关闭
    val maskProgress by animateFloatAsState(
        targetValue = if (currentMode == SelectorMode.JUMP && !isEditing) 0f else 1f,
        animationSpec = spring(stiffness = universalStiffness, dampingRatio = universalDamping),
        label = "maskProgress"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                // 蒙版逻辑：通过裁剪控制可见区域
                // 我们不直接改变高度，而是确保内容不超出的同时，不触发重排
                clip = true
            }
    ) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(4),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 0.dp,
                end = 16.dp,
                bottom = 32.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            ),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            userScrollEnabled = currentMode != SelectorMode.JUMP || isEditing, // JUMP 模式下锁定滚动
            modifier = Modifier.fillMaxSize()
        ) {
        items(flatList, key = { item ->
            when (item) {
                is GridItem.App -> item.app.packageName
                is GridItem.Header -> "divider_section_header"
            }
        }, span = { item ->
            GridItemSpan(if (item is GridItem.Header) 4 else 1)
        }) { item ->
            when (item) {
                is GridItem.App -> {
                    ReorderableGridItemWrapper(
                        item.app, reorderableState, placementSpringSpec, displayList, 
                        isTimerActive, timerSeconds, remainingTime, currentMode, 
                        isEditing, hiddenList, recommendedPackageNames, maskProgress,
                        onToggleHidden, onJump, onCreateRule, iconMap[item.app.packageName]
                    )
                }
                is GridItem.Header -> {
                    // 延迟淡入优化：与应用图标文字同步
                    val headerAlpha = if (maskProgress < 1f) {
                        ((maskProgress - 0.7f) / 0.3f).coerceIn(0f, 1f)
                    } else 1f

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp, bottom = 12.dp)
                            .graphicsLayer { alpha = headerAlpha },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.weight(1f).height(0.5.dp).background(MaterialTheme.colorScheme.outlineVariant))
                        Text(
                            text = item.title,
                            modifier = Modifier.padding(horizontal = 12.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                            fontWeight = FontWeight.Bold
                        )
                        Box(modifier = Modifier.weight(1f).height(0.5.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    }
                }
            }
        }
    }
}
}

// 定义网格项密封类，用于扁平化列表
private sealed class GridItem {
    data class App(val app: AppInfo) : GridItem()
    data class Header(val title: String) : GridItem()
}

@Composable
private fun LazyGridItemScope.ReorderableGridItemWrapper(
    app: AppInfo,
    reorderableState: ReorderableLazyGridState,
    placementSpringSpec: FiniteAnimationSpec<IntOffset>,
    displayList: List<AppInfo>,
    isTimerActive: Boolean,
    timerSeconds: Int,
    remainingTime: Float,
    currentMode: SelectorMode,
    isEditing: Boolean,
    hiddenList: List<String>,
    recommendedPackageNames: Set<String>,
    maskProgress: Float, // 新增参数
    onToggleHidden: (AppInfo) -> Unit,
    onJump: (AppInfo, Boolean) -> Unit,
    onCreateRule: (AppInfo) -> Unit,
    preloadedIcon: android.graphics.drawable.Drawable? // 新增参数
) {
    ReorderableItem(reorderableState, key = app.packageName) { isDragging ->
        val haptic = LocalHapticFeedback.current
        val dragHandleModifier = if (isEditing && app.packageName !in recommendedPackageNames) {
            Modifier.longPressDraggableHandle(
                onDragStarted = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
            )
        } else Modifier

        Box(
            modifier = Modifier
                .graphicsLayer { 
                    alpha = if (app.packageName in hiddenList) maskProgress else 1f 
                } // 性能优化：隐藏项渐现不触发重组
                .animateItem(
                    fadeInSpec = null,
                    placementSpec = if (isDragging) null else placementSpringSpec,
                    fadeOutSpec = null
                )
        ) {
            BrowserGridItem(
                app = app,
                isFirst = displayList.firstOrNull() == app,
                isTimerActive = isTimerActive,
                progress = if (timerSeconds > 0) remainingTime / timerSeconds else 0f,
                currentMode = currentMode,
                isEditing = isEditing,
                isDragging = isDragging,
                isHidden = app.packageName in hiddenList,
                isRecommended = app.packageName in recommendedPackageNames,
                onToggleHidden = { onToggleHidden(app) },
                preloadedIcon = preloadedIcon, // 传入预加载图标
                maskProgress = maskProgress, // 传入蒙版进度控制文字延迟
                onClick = {
                    if (currentMode == SelectorMode.RULE) {
                        onCreateRule(app)
                    } else {
                        onJump(app, false)
                    }
                },
                onLongClick = { onJump(app, true) },
                modifier = dragHandleModifier
            )
        }
    }
}
