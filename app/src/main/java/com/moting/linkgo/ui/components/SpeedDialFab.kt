package com.moting.linkgo.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Speed Dial 子动作项定义
 */
data class SpeedDialItem(
    val label: String,
    val icon: Painter? = null,
    val imageVector: ImageVector? = null,
    val iconTint: Color = Color.Unspecified,
    val containerColor: Color? = null,
    val contentColor: Color? = null,
    val onClick: () -> Unit
)

/**
 * 一体化扁平快捷展开悬浮按钮组 (Unified Flat Speed Dial FAB)
 *
 * 遵循 Material 3 与现代化扁平化设计准则：
 * 1. 消除分散零碎感：将所有动作收拢在单个一体化圆角卡片面板内；
 * 2. 纯粹扁平美学：消费 `surfaceContainer` 语义令牌，搭配精细边缘微描边与微底色图标，去除冗余重阴影；
 * 3. 弹簧锚点展开：以右下角主 FAB 为变换锚点（TransformOrigin(1f, 1f)），整体平滑弹射展开与收缩；
 * 4. 主 FAB 弹簧旋转 45° 转换为关闭“×”，展开时呈现轻量半透明暗色遮罩，支持点击空白或物理返回收起。
 */
@Composable
fun SpeedDialFloatingActionButton(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    items: List<SpeedDialItem>,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    bottomPadding: Dp = 16.dp,
    endPadding: Dp = 16.dp,
    contentDescription: String = "添加规则"
) {
    // 展开状态下拦截系统物理/手势返回键
    BackHandler(enabled = expanded) {
        onExpandedChange(false)
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 全屏半透明遮罩
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(animationSpec = tween(180)),
            exit = fadeOut(animationSpec = tween(180))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.28f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        onExpandedChange(false)
                    }
            )
        }

        // 按钮组区域（位于右下角）
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing)) +
                    scaleIn(initialScale = 0.92f, animationSpec = tween(220, easing = FastOutSlowInEasing)),
            exit = fadeOut(animationSpec = tween(180, easing = FastOutLinearInEasing)) +
                    scaleOut(targetScale = 0.92f, animationSpec = tween(180, easing = FastOutLinearInEasing)),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = bottomPadding, end = endPadding)
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 展开的一体化扁平面板 (平滑无弹簧展开)
                AnimatedVisibility(
                    visible = expanded,
                    enter = fadeIn(animationSpec = tween(200, easing = FastOutSlowInEasing)) +
                            scaleIn(
                                initialScale = 0.88f,
                                transformOrigin = TransformOrigin(1f, 1f),
                                animationSpec = tween(200, easing = FastOutSlowInEasing)
                            ) +
                            slideInVertically(
                                initialOffsetY = { it / 8 },
                                animationSpec = tween(200, easing = FastOutSlowInEasing)
                            ),
                    exit = fadeOut(animationSpec = tween(150, easing = FastOutLinearInEasing)) +
                            scaleOut(
                                targetScale = 0.9f,
                                transformOrigin = TransformOrigin(1f, 1f),
                                animationSpec = tween(150, easing = FastOutLinearInEasing)
                            ) +
                            slideOutVertically(
                                targetOffsetY = { it / 8 },
                                animationSpec = tween(150, easing = FastOutLinearInEasing)
                            )
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        tonalElevation = 2.dp,
                        shadowElevation = 4.dp,
                        border = BorderStroke(
                            width = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                        ),
                        modifier = Modifier
                            .width(IntrinsicSize.Max)
                            .widthIn(min = 168.dp, max = 220.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp)
                        ) {
                            items.forEachIndexed { index, item ->
                                SpeedDialMenuRow(
                                    item = item,
                                    onClick = {
                                        onExpandedChange(false)
                                        item.onClick()
                                    }
                                )
                                if (index < items.size - 1) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 12.dp),
                                        thickness = 0.5.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                                    )
                                }
                            }
                        }
                    }
                }

                // 主 FAB 旋转动画：0° -> 45° 平滑转换为关闭图标（无弹簧回弹）
                val fabRotation by animateFloatAsState(
                    targetValue = if (expanded) 45f else 0f,
                    animationSpec = tween(
                        durationMillis = 200,
                        easing = FastOutSlowInEasing
                    ),
                    label = "speed_dial_fab_rotation"
                )

                // 主 FAB
                AppFloatingActionButton(
                    onClick = { onExpandedChange(!expanded) },
                    iconRotation = fabRotation,
                    contentDescription = if (expanded) "收起" else contentDescription
                )
            }
        }
    }
}

/**
 * 一体化面板内的单行菜单项（扁平柔光微图标 + 标题文字）
 */
@Composable
private fun SpeedDialMenuRow(
    item: SpeedDialItem,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 扁平微图标容器（柔和浅彩底，不突兀）
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(item.iconTint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            if (item.imageVector != null) {
                Icon(
                    imageVector = item.imageVector,
                    contentDescription = null,
                    tint = item.iconTint,
                    modifier = Modifier.size(18.dp)
                )
            } else if (item.icon != null) {
                Icon(
                    painter = item.icon,
                    contentDescription = null,
                    tint = item.iconTint,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // 条目标题
        Text(
            text = item.label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

