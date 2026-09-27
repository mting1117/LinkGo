package com.moting.linkgo.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moting.linkgo.model.LinkRegion
import com.moting.linkgo.util.WindowRouter

// 布局模型：将所有内容拆分为不同的"块"
sealed class LayoutBlock {
    data class StaticRow(val items: List<Any>) : LayoutBlock()
    data class ScrollableDoubleRow(val items: List<List<Pair<LinkRegion, Int>>>, val totalLinks: Int) : LayoutBlock()
}

@Composable
fun NumberSelectionBar(
    links: List<LinkRegion>,
    primaryColor: ComposeColor,
    scrollDirection: Int = 0,
    modifier: Modifier = Modifier,
    onSelected: (LinkRegion) -> Unit,
    onLongClick: (LinkRegion) -> Unit
) {
    Surface(
        modifier = modifier
            .padding(bottom = 48.dp)
            .width(324.dp), // 锁定宽度：300(内容) + 12*2(边距)
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
        shadowElevation = 2.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    ) {
        AnimatedContent(
            targetState = links,
            transitionSpec = {
                if (scrollDirection == 0) {
                    // 默认点击进入动画：淡入+半高滑入，退出淡出缩放
                    (fadeIn(animationSpec = tween(200)) + 
                     slideInVertically(animationSpec = tween(200, easing = FastOutSlowInEasing)) { it / 2 })
                    .togetherWith(
                        fadeOut(animationSpec = tween(200)) + 
                        scaleOut(targetScale = 0.85f, animationSpec = tween(200))
                    )
                } else {
                    // 滑动切换动画：方向同步
                    val enterSlide = if (scrollDirection > 0) {
                        slideInVertically(animationSpec = tween(250)) { -it } // 从上进
                    } else {
                        slideInVertically(animationSpec = tween(250)) { it } // 从下进
                    }
                    
                    val exitSlide = if (scrollDirection > 0) {
                        slideOutVertically(animationSpec = tween(250)) { it } // 从下出
                    } else {
                        slideOutVertically(animationSpec = tween(250)) { -it } // 从上出
                    }

                    (fadeIn(animationSpec = tween(200)) + enterSlide)
                    .togetherWith(fadeOut(animationSpec = tween(200)) + exitSlide)
                }.using(
                    SizeTransform(clip = false) { _, _ ->
                        tween(durationMillis = 200, easing = FastOutSlowInEasing)
                    }
                )
            },
            label = "IslandContent"
        ) { currentLinks ->
            val groups = currentLinks.groupBy { it.groupId }.values.sortedBy { group -> 
                group.minOf { it.rects.firstOrNull()?.top ?: Int.MAX_VALUE } 
            }

            // 布局模型：将所有内容拆分为不同的"块"
            val blocks = mutableListOf<LayoutBlock>()
            var currentRowItems = mutableListOf<Any>()
            var currentRowLinkCount = 0

            groups.forEach { group ->
                val groupSize = group.size
                if (groupSize > 6) {
                    // 1. 如果当前行有东西，先结算当前行
                    if (currentRowItems.isNotEmpty()) {
                        blocks.add(LayoutBlock.StaticRow(currentRowItems))
                        currentRowItems = mutableListOf()
                        currentRowLinkCount = 0
                    }

                    // 2. 处理大组 (>6)
                    if (groupSize <= 12) {
                        // 拆分为两个静态行 (7-12个)
                        val sortedGroup = group.sortedWith(compareBy({ it.rects.minOfOrNull { r -> r.top } ?: Int.MAX_VALUE }, { it.rects.minOfOrNull { r -> r.left } ?: Int.MAX_VALUE }))
                        val firstHalfCount = (groupSize + 1) / 2
                        blocks.add(LayoutBlock.StaticRow(sortedGroup.take(firstHalfCount).mapIndexed { i, l -> l to i }))
                        blocks.add(LayoutBlock.StaticRow(sortedGroup.drop(firstHalfCount).mapIndexed { i, l -> l to (i + firstHalfCount) }))
                    } else {
                        // 拆分为双行滚动块 (>12个)
                        val sortedGroup = group.sortedWith(compareBy({ it.rects.minOfOrNull { r -> r.top } ?: Int.MAX_VALUE }, { it.rects.minOfOrNull { r -> r.left } ?: Int.MAX_VALUE }))
                        val firstHalfCount = (groupSize + 1) / 2
                        val topRow = sortedGroup.take(firstHalfCount).mapIndexed { i, l -> l to i }
                        val bottomRow = sortedGroup.drop(firstHalfCount).mapIndexed { i, l -> l to (i + firstHalfCount) }
                        blocks.add(LayoutBlock.ScrollableDoubleRow(listOf(topRow, bottomRow), groupSize))
                    }
                } else {
                    // 3. 处理小组 (<=6) -> 贪婪合并
                    if (currentRowLinkCount + groupSize <= 6) {
                        if (currentRowItems.isNotEmpty()) currentRowItems.add("separator")
                        val sortedGroup = group.sortedWith(compareBy({ it.rects.minOfOrNull { r -> r.top } ?: Int.MAX_VALUE }, { it.rects.minOfOrNull { r -> r.left } ?: Int.MAX_VALUE }))
                        currentRowItems.addAll(sortedGroup.mapIndexed { i, l -> l to i })
                        currentRowLinkCount += groupSize
                    } else {
                        // 放不下，结算旧行，起新行
                        blocks.add(LayoutBlock.StaticRow(currentRowItems))
                        val sortedGroup = group.sortedWith(compareBy({ it.rects.minOfOrNull { r -> r.top } ?: Int.MAX_VALUE }, { it.rects.minOfOrNull { r -> r.left } ?: Int.MAX_VALUE }))
                        currentRowItems = sortedGroup.mapIndexed { i, l -> l to i }.toMutableList()
                        currentRowLinkCount = groupSize
                    }
                }
            }
            if (currentRowItems.isNotEmpty()) blocks.add(LayoutBlock.StaticRow(currentRowItems))

            // 渲染块，最高 5 行
            Column(
                modifier = Modifier
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                blocks.forEach { block ->
                    when (block) {
                        is LayoutBlock.StaticRow -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                block.items.forEachIndexed { index, item ->
                                    if (item is Pair<*, *>) {
                                        val pair = item as Pair<LinkRegion, Int>
                                        SelectionItem(pair.second, pair.first.url, primaryColor, { onSelected(pair.first) }, { onLongClick(pair.first) })
                                    }
                                    
                                    // 处理间距和分隔符
                                    if (index < block.items.size - 1) {
                                        val nextItem = block.items[index + 1]
                                        if (item is Pair<*, *> && nextItem is Pair<*, *>) {
                                            // 两个图标之间：普通 12dp 间距
                                            Spacer(Modifier.width(12.dp))
                                        } else if (item is String || nextItem is String) {
                                            // 涉及到分隔符：在 12dp 空间内居中显示
                                            // 12dp 总宽 = 5.25dp + 1.5dp + 5.25dp
                                            if (item is Pair<*, *>) {
                                                Spacer(Modifier.width(5.25.dp))
                                                Box(Modifier.width(1.5.dp).height(24.dp).background(primaryColor.copy(alpha = 0.5f), RoundedCornerShape(1.dp)))
                                                Spacer(Modifier.width(5.25.dp))
                                            }
                                            // 如果当前是 separator，我们已经在上一个 item 处理过了，这里跳过
                                        }
                                    }
                                }
                            }
                        }
                        is LayoutBlock.ScrollableDoubleRow -> {
                            val scrollState = rememberScrollState()
                            Column(
                                modifier = Modifier.horizontalScroll(scrollState),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                block.items.forEach { rowItems ->
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        rowItems.forEach { (link, index) ->
                                            SelectionItem(index, link.url, primaryColor, { onSelected(link) }, { onLongClick(link) })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SelectionItem(
    index: Int,
    url: String,
    primaryColor: ComposeColor,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    var target by remember { mutableStateOf<WindowRouter.PredictedTarget?>(null) }
    
    LaunchedEffect(url) {
        WindowRouter.predictTargetFlow(context, url).collect {
            target = it
        }
    }

    Box(
        modifier = Modifier
            .width(40.dp) // 压缩宽度，实现 8dp 精确水平视觉间距
            .height(40.dp) // 压缩高度，消除内部留白
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    onLongClick()
                },
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ),
        contentAlignment = Alignment.Center
    ) {
        // 图标与角标容器：统一约束在 40dp 内
        Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.BottomEnd) {
            com.moting.linkgo.ui.components.RuleIcon(
                isReDispatch = target?.isReDispatch == true,
                iconPath = target?.iconPath,
                targetPackage = target?.packageName,
                size = 40.dp,
                shape = RoundedCornerShape(8.dp)
            )

            // 序号角标 (内嵌式：不使用 offset 或使用负 offset 确保在圆角内)
            Surface(
                modifier = Modifier
                    .size(16.dp), // 稍微缩小一点
                shape = CircleShape,
                color = primaryColor,
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = (index + 1).toString(),
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 8.sp, // 同比例缩小
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center,
                        lineHeight = 8.sp
                    )
                }
            }
        }
    }
}
