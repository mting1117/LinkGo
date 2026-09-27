package com.moting.linkgo.ui.settings.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * 全屏 1:1 编辑蒙层 (高精度旋转感知版)
 * 核心逻辑：只展示用户期望的 1.0 倍原始比例，1.42 补偿由后台 WindowConfig 执行。
 */
@Composable
fun FullScreenEditorOverlay(
    isVisible: Boolean,
    widthRatio: Float,
    heightRatio: Float,
    xRatio: Float,
    yRatio: Float,
    onConfigChanged: (w: Float, h: Float, x: Float, y: Float) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    val density = LocalDensity.current
    
    // 动态感知旋转后的屏幕尺寸 (Px)
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val parentWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val parentHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    
    // --- 本地状态拦截层 ---
    var curW by remember { mutableFloatStateOf(widthRatio) }
    var curH by remember { mutableFloatStateOf(heightRatio) }
    var curX by remember { mutableFloatStateOf(xRatio) }
    var curY by remember { mutableFloatStateOf(yRatio) }

    // 同步外部初值 (在显示或屏幕旋转时)
    LaunchedEffect(isVisible, widthRatio, heightRatio, xRatio, yRatio, configuration.orientation) {
        if (isVisible) {
            curW = widthRatio
            curH = heightRatio
            curX = xRatio
            curY = yRatio
        }
    }

    val currentOnConfigChanged by rememberUpdatedState(onConfigChanged)

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
        ) {
            // --- 关键修正：预览层不再乘以 1.42，直接展现用户期望的物理比例 ---
            val displayWidth = (curW * parentWidthPx).coerceAtMost(parentWidthPx)
            val displayHeight = (curH * parentHeightPx).coerceAtMost(parentHeightPx)
            val boxX = (curX * parentWidthPx).coerceIn(0f, (parentWidthPx - displayWidth).coerceAtLeast(0f))
            val boxY = (curY * parentHeightPx).coerceIn(0f, (parentHeightPx - displayHeight).coerceAtLeast(0f))

            // 操作主体
            Box(
                modifier = Modifier
                    .offset { IntOffset(boxX.roundToInt(), boxY.roundToInt()) }
                    .size(
                        width = with(density) { displayWidth.toDp() },
                        height = with(density) { displayHeight.toDp() }
                    )
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                    .border(2.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                    .pointerInput(configuration.orientation) {
                        detectDragGestures(
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val deltaX = dragAmount.x / parentWidthPx
                                val deltaY = dragAmount.y / parentHeightPx
                                
                                val limitX = (1f - curW).coerceAtLeast(0f)
                                val limitY = (1f - curH).coerceAtLeast(0f)
                                
                                curX = (curX + deltaX).coerceIn(0f, limitX)
                                curY = (curY + deltaY).coerceIn(0f, limitY)
                            },
                            onDragEnd = {
                                currentOnConfigChanged(curW, curH, curX, curY)
                            }
                        )
                    }
                    .clickable(enabled = true, onClick = {}) 
            ) {
                // 中心比例数值显示
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "${(curW * 100).roundToInt()}% × ${(curH * 100).roundToInt()}%",
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }

                // 缩放手柄 (右下角)
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .align(Alignment.BottomEnd)
                        .pointerInput(configuration.orientation) {
                            detectDragGestures(
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val minRatio = 0.1f
                                    
                                    val deltaW = dragAmount.x / parentWidthPx
                                    val deltaH = dragAmount.y / parentHeightPx
                                    
                                    val maxW = 1f - curX
                                    val maxH = 1f - curY
                                    
                                    curW = (curW + deltaW).coerceIn(minRatio, maxW.coerceAtLeast(minRatio))
                                    curH = (curH + deltaH).coerceIn(minRatio, maxH.coerceAtLeast(minRatio))
                                },
                                onDragEnd = {
                                    currentOnConfigChanged(curW, curH, curX, curY)
                                }
                            )
                        }
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp)
                            .size(16.dp)
                            .border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(bottomEnd = 4.dp))
                    )
                }
            }

            // 完成按钮
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 80.dp),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.primary,
                tonalElevation = 8.dp,
                shadowElevation = 12.dp,
                onClick = onSave
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "完成预览并保存",
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
    }
}
