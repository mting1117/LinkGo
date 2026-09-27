package com.moting.linkgo.ui.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moting.linkgo.R
import com.moting.linkgo.clipboard.ClipboardBackend

/**
 * 首页顶部「服务状态」感知组件：
 * 直观呈现三大核心分发/捕获通道（系统浏览器、剪贴板监听、应用内捕获）的实时健康状态。
 * 点击异常或任意通道一键直达对应配置或授权页。
 */
@Composable
fun EngineHealthBadge(
    isDefaultBrowser: Boolean,
    clipboardEnabled: Boolean,
    clipboardBackend: String,
    appLinkCaptureMode: Int,
    appLinkCapturedAppsCount: Int,
    edgeGestureEnabled: Boolean,
    privilegeMode: String = "auto",
    onBrowserClick: () -> Unit,
    onClipboardClick: () -> Unit,
    onAppLinkClick: () -> Unit,
    onEdgeGestureClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val allActive = isDefaultBrowser && clipboardEnabled && (appLinkCaptureMode > 0) && edgeGestureEnabled
    val hasAlert = !isDefaultBrowser

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.7f),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // 头部标题与全局健康度胶囊
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    hasAlert -> MaterialTheme.colorScheme.error
                                    allActive -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.secondary
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "服务状态",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    // 工作模式胶囊
                    Spacer(modifier = Modifier.width(8.dp))
                    val (modeLabel, modeColor) = when (privilegeMode) {
                        "root" -> "Root" to MaterialTheme.colorScheme.tertiary
                        "shizuku" -> "Shizuku" to MaterialTheme.colorScheme.secondary
                        else -> "自动" to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = modeColor.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = modeLabel,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 10.sp
                            ),
                            color = modeColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                val (statusBadgeText, statusBadgeBgColor, statusBadgeTextColor) = when {
                    hasAlert -> Triple(
                        "待激活",
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                        MaterialTheme.colorScheme.onErrorContainer
                    )
                    allActive -> Triple(
                        "全通道就绪",
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                        MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    else -> Triple(
                        "核心已就绪",
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = statusBadgeBgColor
                ) {
                    Text(
                        text = statusBadgeText,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp
                        ),
                        color = statusBadgeTextColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 2x2 四大核心能力通道状态网格 (依次为：默认浏览器、快捷手势、剪贴板监听、应用内捕获)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 通道 1: 默认浏览器（核心入口）
                    ChannelStatusItem(
                        title = "默认浏览器",
                        icon = ImageVector.vectorResource(id = R.drawable.ic_iconoir_home),
                        statusText = if (isDefaultBrowser) "已激活" else "去激活",
                        isActive = isDefaultBrowser,
                        isAlert = !isDefaultBrowser,
                        onClick = onBrowserClick,
                        modifier = Modifier.weight(1f)
                    )

                    // 通道 2: 快捷手势
                    ChannelStatusItem(
                        title = "快捷手势",
                        icon = ImageVector.vectorResource(id = R.drawable.ic_iconoir_sparks),
                        statusText = if (edgeGestureEnabled) "已启用" else "已停用",
                        isActive = edgeGestureEnabled,
                        isAlert = false,
                        onClick = onEdgeGestureClick,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 通道 3: 剪贴板监听
                    val clipStatusText = if (clipboardEnabled) {
                        ClipboardBackend.displayName(clipboardBackend)
                    } else {
                        "已停用"
                    }
                    ChannelStatusItem(
                        title = "剪贴板监听",
                        icon = ImageVector.vectorResource(id = R.drawable.ic_iconoir_paste_clipboard),
                        statusText = clipStatusText,
                        isActive = clipboardEnabled,
                        isAlert = false,
                        onClick = onClipboardClick,
                        modifier = Modifier.weight(1f)
                    )

                    // 通道 4: 应用内捕获
                    val appLinkStatusText = when (appLinkCaptureMode) {
                        1 -> "拦截(${appLinkCapturedAppsCount})"
                        2 -> "询问(${appLinkCapturedAppsCount})"
                        else -> "已停用"
                    }
                    ChannelStatusItem(
                        title = "应用内捕获",
                        icon = ImageVector.vectorResource(id = R.drawable.ic_iconoir_link),
                        statusText = appLinkStatusText,
                        isActive = appLinkCaptureMode > 0,
                        isAlert = false,
                        onClick = onAppLinkClick,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/**
 * 单个通道状态卡片 (方案一：横向仪表盘磁贴式)
 */
@Composable
private fun ChannelStatusItem(
    title: String,
    icon: ImageVector,
    statusText: String,
    isActive: Boolean,
    isAlert: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = when {
            isAlert -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
            isActive -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f)
            else -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.40f)
        },
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 9.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. 左侧图标微容器 (Squircle Container) - 方案 A 纯净中性化
            val iconBgColor = when {
                isAlert -> MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                isActive -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
            }
            val iconTint = when {
                isAlert -> MaterialTheme.colorScheme.error
                isActive -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            }

            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(
                        color = iconBgColor,
                        shape = RoundedCornerShape(9.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(17.dp)
                )
            }

            Spacer(modifier = Modifier.width(7.dp))

            // 2. 中间自适应双行文案 (标题 + 状态)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(1.5.dp))

                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 10.5.sp
                    ),
                    color = when {
                        isAlert -> MaterialTheme.colorScheme.error
                        isActive -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(2.dp))

            // 3. 右侧极轻量跳转小指示箭头
            Icon(
                imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_nav_arrow_right),
                contentDescription = "跳转配置",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.size(13.dp)
            )
        }
    }
}

