package com.moting.linkgo.ui.history

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moting.linkgo.R
import com.moting.linkgo.ui.components.*
import com.moting.linkgo.viewmodel.HistoryFilter
import com.moting.linkgo.viewmodel.HistoryUiItem
import com.moting.linkgo.viewmodel.HistoryViewModel

/**
 * 记录页面（极简流水日志，无多选模式负担）：
 * 1. 顶部即时搜索框（复刻规则页面胶囊形态，毫秒级本地过滤 URL/规则名/包名）；
 * 2. 状态筛选标签（全部 / 仅失败 / 多步分发）；
 * 3. 预渲染极简卡片流（长链接主体，规则->应用链路图解）；
 * 4. 顶栏全量清空弹窗，单项点击进全屏详情。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    bottomPadding: Dp = 0.dp,
    onNavigateToDetail: (String) -> Unit = {},
    onNavigateBack: () -> Unit = {},
    viewModel: HistoryViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showClearDialog by remember { mutableStateOf(false) }
    var collapsedDateKeys by remember { mutableStateOf(setOf<String>()) }

    MainPageScaffold(
        title = "记录",
        actions = {
            if (!uiState.isEmpty) {
                IconButton(onClick = { showClearDialog = true }) {
                    Icon(
                        ImageVector.vectorResource(id = R.drawable.ic_iconoir_trash),
                        contentDescription = "清空记录",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    ) { innerPadding ->
        if (uiState.isEmpty) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding()),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(72.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                ImageVector.vectorResource(id = R.drawable.ic_iconoir_clock),
                                contentDescription = null,
                                modifier = Modifier.size(36.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "暂无跳转记录",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "触发分发跳转后将在此处记录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding())
            ) {
                // 常驻搜索栏：与规则页严格对齐，吸顶常驻
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
                ) {
                    CapsuleSearchBar(
                        query = uiState.searchQuery,
                        onQueryChange = { viewModel.setSearchQuery(it) },
                        placeholder = "搜索记录、链接或目标应用...",
                        onClear = { viewModel.setSearchQuery("") }
                    )
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = bottomPadding + 32.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 1. 状态过滤标签 Chip
                    item(key = "filter_header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HistoryFilterChip(
                            text = "全部 (${uiState.totalGroupsCount})",
                            isSelected = uiState.selectedFilter == HistoryFilter.ALL,
                            onClick = { viewModel.setFilter(HistoryFilter.ALL) }
                        )

                        HistoryFilterChip(
                            text = "仅失败 (${uiState.failCount})",
                            isSelected = uiState.selectedFilter == HistoryFilter.FAILED,
                            isHighlight = uiState.failCount > 0,
                            onClick = { viewModel.setFilter(HistoryFilter.FAILED) }
                        )

                        HistoryFilterChip(
                            text = "多步分发 (${uiState.multiCount})",
                            isSelected = uiState.selectedFilter == HistoryFilter.MULTI_STEP,
                            onClick = { viewModel.setFilter(HistoryFilter.MULTI_STEP) }
                        )
                    }
                }

                // 3. 列表结果或无匹配提示
                if (uiState.items.isEmpty()) {
                    item(key = "empty_filtered") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (uiState.searchQuery.isNotBlank()) {
                                    "未找到匹配「${uiState.searchQuery}」的历史记录"
                                } else when (uiState.selectedFilter) {
                                    HistoryFilter.FAILED -> "暂无失败记录"
                                    HistoryFilter.MULTI_STEP -> "暂无多步分发记录"
                                    else -> "暂无记录"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    uiState.dateGroups.forEach { dateGroup ->
                        val isCollapsed = dateGroup.dateKey in collapsedDateKeys

                        // 日期板块标题（支持点击折叠/展开）
                        item(key = "header_${dateGroup.dateKey}") {
                            HistoryDateSectionHeader(
                                title = dateGroup.dateTitle,
                                count = dateGroup.items.size,
                                isCollapsed = isCollapsed,
                                onToggleCollapse = {
                                    collapsedDateKeys = if (isCollapsed) {
                                        collapsedDateKeys - dateGroup.dateKey
                                    } else {
                                        collapsedDateKeys + dateGroup.dateKey
                                    }
                                }
                            )
                        }

                        // 该日期板块下的所有跳转记录卡片
                        if (!isCollapsed) {
                            items(
                                items = dateGroup.items,
                                key = { it.keyId }
                            ) { item ->
                                HistoryItemCard(
                                    item = item,
                                    onClick = { onNavigateToDetail(item.traceIdOrId) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

    if (showClearDialog) {
        ConfirmActionSheet(
            title = "清空历史记录",
            message = "确认要清空所有跳转记录吗？此操作无法撤销。",
            confirmLabel = "清空",
            destructive = true,
            onConfirm = {
                viewModel.clearHistory()
                showClearDialog = false
            },
            onDismiss = { showClearDialog = false }
        )
    }
}

/**
 * 极简筛选标签
 */
@Composable
private fun HistoryFilterChip(
    text: String,
    isSelected: Boolean,
    isHighlight: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = when {
            isSelected && isHighlight -> MaterialTheme.colorScheme.errorContainer
            isSelected -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f)
        }
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                fontSize = 12.sp
            ),
            color = when {
                isSelected && isHighlight -> MaterialTheme.colorScheme.onErrorContainer
                isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
                isHighlight -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

/**
 * 极简历史记录卡片（直接消费预处理的 HistoryUiItem 模型，零耗时渲染）
 */
@Composable
private fun HistoryItemCard(
    item: HistoryUiItem,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：目标应用图标
            if (item.finalTargetPkg.isNotBlank()) {
                AsyncAppIcon(
                    packageName = item.finalTargetPkg,
                    size = 44.dp,
                    shape = RoundedCornerShape(12.dp)
                )
            } else if (item.isImage) {
                // 图片记录：无目标包名时（回落选择器）用图片语义图标，区别于文本链接的 Link 图标
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            } else if (item.isMultiStep) {
                Box(
                    modifier = Modifier.size(44.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = com.moting.linkgo.R.drawable.ic_hub_primary),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(28.dp)
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_link),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            // 中间：长链接核心主体与流转链路
            Column(modifier = Modifier.weight(1f)) {
                // 第一行：长链接 URL（图片记录则显示「图片分享」语义，因为它没有 URL）
                Text(
                    text = if (item.isImage) "图片分享" else item.displayUrl,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 20.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(6.dp))

                // 第二行：规则名 → 目标应用
                Text(
                    text = item.flowDescription,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // 右侧：时间与状态指示
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = item.timeFormatted,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.hasError) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            Text(
                                text = "失败",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    if (item.isMultiStep) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            Text(
                                text = "多步",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Icon(
                        imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_nav_arrow_right),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

/**
 * 历史记录按天分板块的折叠标头
 */
@Composable
private fun HistoryDateSectionHeader(
    title: String,
    count: Int,
    isCollapsed: Boolean,
    onToggleCollapse: () -> Unit,
    modifier: Modifier = Modifier
) {
    val arrowRotation by animateFloatAsState(
        targetValue = if (isCollapsed) 0f else 90f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "HistoryDateCollapseArrow"
    )

    Surface(
        onClick = onToggleCollapse,
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp, start = 2.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(14.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    letterSpacing = 0.3.sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.width(6.dp))
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.8f)
            ) {
                Text(
                    text = "$count",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (isCollapsed) "展开" else "折叠",
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer { rotationZ = arrowRotation },
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}
