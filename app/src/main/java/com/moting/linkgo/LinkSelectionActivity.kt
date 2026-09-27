package com.moting.linkgo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.moting.linkgo.ui.theme.链接跳转Theme
import com.moting.linkgo.util.WindowRouter
import com.moting.linkgo.util.NotificationHelper
import com.moting.linkgo.data.SettingsRepository
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.clip
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumInteractiveComponentEnforcement
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.isSystemInDarkTheme

/**
 * 多链接选择界面
 */
class LinkSelectionActivity : ComponentActivity(), com.moting.linkgo.ui.BlurCapableHost {

    private val urlsState = mutableStateOf<List<String>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        
        overridePendingTransition(0, 0)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply {
                blurBehindRadius = 0
            }
            window.setDimAmount(0f)
        }

        val initialUrls = intent.getStringArrayListExtra("URLS") ?: emptyList<String>()
        urlsState.value = initialUrls

        if (initialUrls.isEmpty()) {
            finish()
            return
        }

        setContent {
            val repository = remember { SettingsRepository(this) }
            val dynamicColorEnabled by repository.dynamicColorEnabled.collectAsState(initial = true)
            val backgroundBlurEnabled by repository.backgroundBlurEnabled.collectAsState(initial = repository.currentBackgroundBlurEnabled)

            链接跳转Theme(dynamicColor = dynamicColorEnabled) {
                LinkSelectionScreen(
                    urls = urlsState.value,
                    blurEnabled = backgroundBlurEnabled,
                    onOpenUrls = { selected ->
                        val appContext = applicationContext
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate).launch {
                            selected.forEach { url ->
                                val result = WindowRouter.handleUrl(appContext, url, WindowRouter.DispatchSource.DIRECT)
                                if (result.status == WindowRouter.DispatchResult.NEED_SELECTOR) {
                                    val intent = android.content.Intent(appContext, BrowserSelectorActivity::class.java).apply {
                                        putExtra("URL", result.finalUrl)
                                        putExtra("SOURCE", WindowRouter.DispatchSource.DIRECT.name)
                                        putExtra("TRACE_ID", result.traceId)
                                        putExtra("STEP_INDEX", result.stepIndex)
                                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    appContext.startActivity(intent)
                                }
                                kotlinx.coroutines.delay(100)
                            }
                        }
                        finish()
                    },
                    onDismiss = {
                        finish()
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newUrls = intent?.getStringArrayListExtra("URLS") ?: emptyList<String>()
        if (newUrls.isNotEmpty()) {
            urlsState.value = newUrls
        }
    }

    override fun updateBlurProgress(progress: Float, blurEnabled: Boolean) {
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
        overridePendingTransition(0, 0)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkSelectionScreen(
    urls: List<String>,
    blurEnabled: Boolean = true,
    onOpenUrls: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    // 多选状态
    var selectedUrls by remember { mutableStateOf(setOf<String>()) }
    val isAnySelected = selectedUrls.isNotEmpty()

    // 容器（遮罩 / 模糊 / 进出场动画 / 玻璃描边 / Header / 底部按钮）全部来自共用外壳，
    // 与图片选择页同一套动效；本函数只负责"链接列表"这份内容
    com.moting.linkgo.ui.SelectionSheetScreen(
        title = "选择链接",
        badgeText = "${urls.size} 条",
        trailingAction = com.moting.linkgo.ui.SelectionSheetAction(
            label = if (selectedUrls.size == urls.size) "取消全选" else "全选",
            onClick = {
                selectedUrls = if (selectedUrls.size == urls.size) emptySet() else urls.toSet()
            }
        ),
        blurEnabled = blurEnabled,
        confirmLabel = if (isAnySelected) "打开选中 (${selectedUrls.size})" else "全部打开",
        onConfirm = {
            val toOpen = if (isAnySelected) selectedUrls.toList() else urls
            onOpenUrls(toOpen)
        },
        onDismiss = onDismiss
    ) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(horizontal = 20.dp),
            modifier = Modifier.weight(weight = 1f, fill = false)
        ) {
            items(items = urls) { url ->
                val isSelected = selectedUrls.contains(url)
                LinkItem(
                    url = url,
                    isSelected = isSelected,
                    onOpen = { onOpenUrls(listOf(url)) },
                    onToggle = {
                        selectedUrls = if (isSelected) selectedUrls - url else selectedUrls + url
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LinkItem(
    url: String, 
    isSelected: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var target by remember { mutableStateOf<com.moting.linkgo.util.WindowRouter.PredictedTarget?>(null) }
    
    LaunchedEffect(url) {
        com.moting.linkgo.util.WindowRouter.predictTargetFlow(context, url).collect {
            target = it
        }
    }
    
    val haptic = LocalHapticFeedback.current
    
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) 
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f) 
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        border = if (isSelected) 
            androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline) 
            else null,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onOpen,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("URL", url)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "链接已复制", Toast.LENGTH_SHORT).show()
                }
            )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(contentAlignment = Alignment.BottomEnd) {
                com.moting.linkgo.ui.components.RuleIcon(
                    isReDispatch = target?.isReDispatch == true,
                    iconPath = target?.iconPath,
                    targetPackage = target?.packageName,
                    size = 28.dp,
                    shape = RoundedCornerShape(8.dp)
                )
                
                // 添加分发角标
                if (target?.wasDistributed == true && target?.isReDispatch == false) {
                    Surface(
                        modifier = Modifier
                            .size(14.dp)
                            .offset(x = 2.dp, y = 2.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondary,
                        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.surfaceContainerHighest)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_hub_primary),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondary,
                                modifier = Modifier.size(8.dp)
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.width(10.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                InteractiveMarqueeText(
                    text = url,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
                
                // 副标题：带有马克笔高亮效果
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "点击将由 ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                    
                    if (target != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Text(
                                text = target?.label ?: "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    } else {
                        Text(
                            text = "正在预测...",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                    }
                    
                    Text(
                        text = " 打开",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }

            // 右侧选择图标
            RadioButton(
                selected = isSelected,
                onClick = onToggle,
                modifier = Modifier.size(32.dp),
                colors = RadioButtonDefaults.colors(
                    selectedColor = MaterialTheme.colorScheme.primary,
                    unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            )
        }
    }
}

/**
 * 可交互的跑马灯文字组件
 * 
 * 支持：自动滚动、手动触摸滑动、边缘渐变消失。
 */
@Composable
fun InteractiveMarqueeText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    velocity: Int = 40, // 每秒滚动 40dp
    initialDelay: Long = 1500L
) {
    val scrollState = rememberScrollState()
    val isDragged by scrollState.interactionSource.collectIsDraggedAsState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    
    // 自动滚动控制
    LaunchedEffect(key1 = text, key2 = isDragged) {
        if (!isDragged) {
            delay(initialDelay)
            while (true) {
                val maxScroll = scrollState.maxValue
                if (maxScroll > 0) {
                    // 如果滚动到了末尾，等待一下再回起点
                    if (scrollState.value >= maxScroll) {
                        delay(2000L)
                        scrollState.scrollTo(0)
                        delay(initialDelay)
                    } else {
                        // 匀速前进：计算剩余像素和所需时间
                        val remaining = maxScroll - scrollState.value
                        val duration = (remaining * 1000L / with(density) { velocity.dp.toPx() }).toInt()
                        if (duration > 0) {
                            scrollState.animateScrollTo(
                                value = maxScroll,
                                animationSpec = tween(
                                    durationMillis = duration,
                                    easing = androidx.compose.animation.core.LinearEasing
                                )
                            )
                        }
                    }
                }
                delay(16L) // 帧同步检查
            }
        }
    }

    // 边缘渐变效果
    Box(
        modifier = modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                // 左侧渐变
                if (scrollState.value > 0) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            listOf(Color.Transparent, Color.Black),
                            startX = 0f,
                            endX = 20.dp.toPx()
                        ),
                        blendMode = BlendMode.DstIn
                    )
                }
                // 右侧渐变
                if (scrollState.value < scrollState.maxValue) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            listOf(Color.Black, Color.Transparent),
                            startX = size.width - 20.dp.toPx(),
                            endX = size.width
                        ),
                        blendMode = BlendMode.DstIn
                    )
                }
            }
    ) {
        Text(
            text = text,
            style = style,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.horizontalScroll(scrollState)
        )
    }
}
