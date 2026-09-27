package com.moting.linkgo.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 底部选择弹窗的宿主 Activity 需要提供的模糊能力 */
interface BlurCapableHost {
    fun updateBlurProgress(progress: Float, blurEnabled: Boolean)
}

/**
 * 底部选择弹窗外壳：**链接选择与图片选择共用**。
 *
 * 抽出原因：此前图片选择页是我另写的一个简陋全屏层（只做了 280ms 透明度渐显），
 * 而链接选择页有"毛玻璃 + 遮罩 + 底部滑入 + 标题角标 + 底部按钮"这一整套动效。
 * 两个功能形态相同、用户预期相同，动效却不同，观感上就是"不是一个 App 的东西"。
 *
 * 这里把容器（遮罩、模糊联动、进出场动画、玻璃高光描边、Header、底部按钮栏）
 * 完整收敛到一处，调用方只提供列表内容；标题、角标、快捷动作与按钮文案全部参数化。
 *
 * @param title 标题，如"选择链接"/"选择发送目标"
 * @param badgeText 标题右侧的数量角标文案；null 表示不显示
 * @param trailingAction 标题右侧的快捷动作（如"全选"）；null 表示不显示
 * @param content 列表区域
 * @param confirmLabel 主按钮文案
 * @param confirmEnabled 主按钮是否可用
 * @param onConfirm 主按钮回调
 */
@Composable
fun SelectionSheetScreen(
    title: String,
    badgeText: String? = null,
    trailingAction: SelectionSheetAction? = null,
    blurEnabled: Boolean = true,
    /**
     * 底部动作栏。为 null 时完全不渲染底部两个按钮——
     * 图片选择页就是这种情况：点网格单元即发送，不需要"取消/确认"这一步。
     */
    confirmLabel: String? = null,
    confirmEnabled: Boolean = true,
    onConfirm: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    // 进场动画控制：首次加载后触发
    var showSheet by remember { mutableStateOf(false) }

    val blurProgress by animateFloatAsState(
        targetValue = if (showSheet) 1f else 0f,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "blurProgress"
    )

    val context = LocalContext.current
    LaunchedEffect(blurProgress, blurEnabled) {
        (context as? BlurCapableHost)?.updateBlurProgress(blurProgress, blurEnabled)
    }

    LaunchedEffect(Unit) {
        showSheet = true
    }

    // 处理关闭动作：先触发退场动画，待消失后再回调
    val closeWithAnimation = remember {
        {
            if (showSheet) {
                showSheet = false
            }
        }
    }

    // 拦截物理返回键
    BackHandler(enabled = showSheet) {
        closeWithAnimation()
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        // 1. 背景遮罩：渐变淡入淡出（模糊开启时降低纯黑透明度，透显底层真实全屏高斯模糊）
        val maskAlpha = if (blurEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 0.15f else 0.45f
        AnimatedVisibility(
            visible = showSheet,
            enter = fadeIn(animationSpec = tween(durationMillis = 300)),
            exit = fadeOut(animationSpec = tween(durationMillis = 300))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = maskAlpha))
                    .clickable(enabled = showSheet, onClick = closeWithAnimation)
            )
        }

        // 2. 主体卡片：底部滑入 + 毛玻璃半透明与高光描边
        AnimatedVisibility(
            visible = showSheet,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)
            ) + fadeIn(animationSpec = tween(200)),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(durationMillis = 250)
            ) + fadeOut()
        ) {
            DisposableEffect(Unit) {
                onDispose {
                    if (!showSheet) {
                        onDismiss()
                    }
                }
            }

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
                    .clickable(enabled = false) {},
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = cardColor,
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = borderStroke,
                tonalElevation = 0.dp
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // 纯净沉浸单行 Header：标题 + 数量胶囊角标 + 快捷操作
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (badgeText != null) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                                ) {
                                    Text(
                                        text = badgeText,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        if (trailingAction != null) {
                            Text(
                                text = trailingAction.label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(enabled = showSheet) { trailingAction.onClick() }
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    content()

                    // 底部操作栏：仅在有确认动作时渲染
                    if (confirmLabel != null && onConfirm != null) {
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp)
                            .padding(bottom = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = closeWithAnimation,
                            modifier = Modifier.weight(1f),
                            enabled = showSheet,
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(vertical = 12.dp)
                        ) {
                            Text("取消")
                        }

                        Button(
                            onClick = {
                                if (showSheet && confirmEnabled) {
                                    onConfirm()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = showSheet && confirmEnabled,
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(vertical = 12.dp)
                        ) {
                            Text(confirmLabel)
                        }
                    }
                    } else {
                        // 无底部按钮时补一段底部留白，避免内容贴住导航栏
                        Spacer(modifier = Modifier.height(20.dp))
                    }
                }
            }
        }
    }
}

/** 弹窗 Header 右侧的快捷动作 */
data class SelectionSheetAction(
    val label: String,
    val onClick: () -> Unit
)
