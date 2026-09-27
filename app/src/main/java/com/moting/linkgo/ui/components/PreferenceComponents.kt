package com.moting.linkgo.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moting.linkgo.R

/**
 * 1:1 复刻自 Floatwidget 的 CollapsingTopBarScaffold
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollapsingTopBarScaffold(
    title: String,
    modifier: Modifier = Modifier,
    isMedium: Boolean = false,
    containerColor: Color = MaterialTheme.colorScheme.background, // 默认为背景色，与页面融为一体
    scrolledContainerColor: Color = MaterialTheme.colorScheme.background, // 滚动后保持与背景色一致，消除灰色断层
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable (PaddingValues, NestedScrollConnection) -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        rememberTopAppBarState()
    )

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (isMedium) {
                MediumTopAppBar(
                    colors = TopAppBarDefaults.mediumTopAppBarColors(
                        containerColor = containerColor,
                        scrolledContainerColor = scrolledContainerColor,
                        titleContentColor = titleColor,
                        navigationIconContentColor = titleColor,
                        actionIconContentColor = titleColor
                    ),
                    title = {
                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.padding(start = 0.dp)
                        )
                    },
                    navigationIcon = navigationIcon,
                    actions = actions,
                    scrollBehavior = scrollBehavior,
                    windowInsets = WindowInsets.statusBars
                )
            } else {
                LargeTopAppBar(
                    colors = TopAppBarDefaults.largeTopAppBarColors(
                        containerColor = containerColor,
                        scrolledContainerColor = scrolledContainerColor,
                        titleContentColor = titleColor,
                        navigationIconContentColor = titleColor,
                        actionIconContentColor = titleColor
                    ),
                    title = {
                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    },
                    navigationIcon = navigationIcon,
                    actions = actions,
                    scrollBehavior = scrollBehavior,
                    windowInsets = WindowInsets.statusBars
                )
            }
        },
        bottomBar = bottomBar,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = snackbarHost,
        content = { padding -> content(padding, scrollBehavior.nestedScrollConnection) }
    )
}

/**
 * 1:1 复刻自 Floatwidget 首页样式的常规 Scaffold (不折叠)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainPageScaffold(
    title: String = "",
    titleContent: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    if (titleContent != null) {
                        titleContent()
                    } else {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineLarge.copy(
                                fontWeight = FontWeight.Black,
                                letterSpacing = (-1.5).sp
                            ),
                            modifier = Modifier.offset(y = (-4).dp)
                        )
                    }
                },
                navigationIcon = navigationIcon,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                actions = actions
            )
        },
        bottomBar = bottomBar,
        content = { padding -> content(padding) }
    )
}

/**
 * SettingsSection (大圆角卡片容器)
 */
@Composable
fun SettingsSection(
    modifier: Modifier = Modifier,
    topLabel: String? = null,
    mainContent: Boolean = false,
    enabled: Boolean = false,
    customColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val radiusDimen = if (mainContent) 48.dp else 16.dp
    val containerColor = customColor
        ?: if (enabled && mainContent) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        topLabel?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        Surface(
            shape = MaterialTheme.shapes.large.copy(CornerSize(radiusDimen)),
            color = containerColor.copy(alpha = 0.6f)
        ) {
            Column(content = content)
        }
    }
}

/**
 * SettingItem (标准设置行)
 */
@Composable
fun SettingItem(
    headlineText: String,
    supportingText: String = "",
    modifier: Modifier = Modifier,
    supportingTextColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    supportingContent: @Composable (() -> Unit)? = null,
    leadingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = {
        // 1:1 复刻的箭头微容器
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                contentDescription = null
            )
        }
    },
    colors: ListItemColors = ListItemDefaults.colors(containerColor = Color.Transparent)
) = ListItem(
    modifier = modifier,
    headlineContent = {
        Text(
            text = headlineText,
            maxLines = 1,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
    },
    supportingContent = supportingContent ?: {
        if (supportingText.isNotEmpty()) {
            Text(
                text = supportingText,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = supportingTextColor.copy(alpha = 0.8f)
            )
        }
    },
    leadingContent = leadingContent,
    trailingContent = trailingContent,
    colors = colors
)

/**
 * 导航指示右箭头（纯净 Iconoir 风格，用于二级页面入口指示）
 */
@Composable
fun NavigationArrow(
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
) {
    Icon(
        imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_nav_arrow_right),
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(20.dp)
    )
}

/**
 * 胶囊风格即时搜索栏（44dp 高度、CircleShape 圆角，用于规则、记录等主页面的顶栏吸顶搜索）
 */
@Composable
fun CapsuleSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    onClear: (() -> Unit)? = null
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_search),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp)
            )

            Spacer(modifier = Modifier.width(10.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.CenterStart
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val textFieldModifier = if (focusRequester != null) {
                    Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                } else {
                    Modifier.fillMaxWidth()
                }
                BasicTextField(
                    value = query,
                    onValueChange = {
                        if (enabled) {
                            onQueryChange(it)
                        }
                    },
                    enabled = enabled,
                    modifier = textFieldModifier,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium
                    ),
                    singleLine = true,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Search
                    ),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            keyboardController?.hide()
                        }
                    )
                )
            }

            if (query.isNotEmpty() && enabled) {
                IconButton(
                    onClick = {
                        if (onClear != null) {
                            onClear()
                        } else {
                            onQueryChange("")
                        }
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_xmark),
                        contentDescription = "清空",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsSectionDivider(
    onContainer: Boolean = true
) = HorizontalDivider(
    thickness = 2.dp,
    color = Color.Transparent
)

/**
 * 极简高级滑块 (iOS风格，小白点阴影 + 加粗轨道)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PremiumSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isDragged by interactionSource.collectIsDraggedAsState()
    val isInteracting = isPressed || isDragged

    val thumbSize by animateDpAsState(
        targetValue = if (isInteracting) 22.dp else 20.dp,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f),
        label = "thumbSize"
    )

    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .disableTrackClick(value, valueRange),
        valueRange = valueRange,
        steps = steps,
        interactionSource = interactionSource,
        colors = SliderDefaults.colors(
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent,
            activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            thumbColor = Color.White
        ),
        thumb = {
            Box(
                modifier = Modifier
                    .size(thumbSize)
                    .shadow(elevation = 2.dp, shape = CircleShape, clip = false)
                    .background(Color.White, CircleShape)
            )
        },
        track = { sliderState ->
            val fraction = (sliderState.value - sliderState.valueRange.start) /
                (sliderState.valueRange.endInclusive - sliderState.valueRange.start).coerceAtLeast(0.01f)

            val activeColor = MaterialTheme.colorScheme.primary
            val inactiveColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            val trackHeight = 28.dp

            androidx.compose.foundation.Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(trackHeight)
            ) {
                val trackRadius = trackHeight.toPx() / 2f
                val trackStart = -trackRadius
                val trackWidth = size.width + trackRadius * 2

                // 绘制背景轨道 (未激活部分)
                drawRoundRect(
                    color = inactiveColor,
                    topLeft = androidx.compose.ui.geometry.Offset(x = trackStart, y = 0f),
                    size = androidx.compose.ui.geometry.Size(width = trackWidth, height = size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackRadius, trackRadius)
                )

                // 绘制激活部分，高亮区域跟随滑块中心，右侧弧度与滑块保持严格同心圆
                val thumbCenter = size.width * fraction
                val activeWidth = thumbCenter + trackRadius * 2

                drawRoundRect(
                    color = activeColor,
                    topLeft = androidx.compose.ui.geometry.Offset(x = trackStart, y = 0f),
                    size = androidx.compose.ui.geometry.Size(width = activeWidth, height = size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackRadius, trackRadius)
                )
            }
        }
    )
}

/**
 * 极简高级开关 (具备初次挂载瞬时就位保护，消除页面切换与冷启动时的滑块跳变动画)
 */
@Composable
fun PremiumSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val thumbSize = 20.dp
    val trackHeight = 28.dp
    val trackWidth = 52.dp

    val padding = (trackHeight - thumbSize) / 2 // 4.dp
    val targetOffset = if (checked) trackWidth - thumbSize - padding else padding

    var isInitialized by remember { mutableStateOf(false) }
    var lastChecked by remember { mutableStateOf(checked) }

    // 首次挂载或外部状态未变化重组时，直接 snap 瞬时就位，彻底消除页面切换时的滑块动画
    val thumbOffset by animateDpAsState(
        targetValue = targetOffset,
        animationSpec = if (!isInitialized || lastChecked == checked) {
            androidx.compose.animation.core.snap()
        } else {
            spring(dampingRatio = 0.76f, stiffness = 500f)
        },
        label = "switchOffset"
    )

    val targetTrackColor = if (checked) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }

    val trackColor by animateColorAsState(
        targetValue = targetTrackColor,
        animationSpec = if (!isInitialized || lastChecked == checked) {
            androidx.compose.animation.core.snap()
        } else {
            androidx.compose.animation.core.tween(durationMillis = 200)
        },
        label = "switchTrackColor"
    )

    LaunchedEffect(checked) {
        if (!isInitialized) {
            isInitialized = true
        }
        lastChecked = checked
    }

    Box(
        modifier = modifier
            .size(width = trackWidth, height = trackHeight)
            .clip(CircleShape)
            .background(trackColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = { onCheckedChange?.invoke(!checked) }
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(thumbSize)
                .shadow(2.dp, CircleShape)
                .background(Color.White, CircleShape)
        )
    }
}

/**
 * 拦截 Slider 轨道点击事件的 Modifier 扩展
 */
fun Modifier.disableTrackClick(
    currentValue: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    thumbHitRadius: Dp = 24.dp
): Modifier = composed {
    val trackWidthState = remember { mutableFloatStateOf(0f) }
    val thumbHitRadiusPx = with(LocalDensity.current) { thumbHitRadius.toPx() }

    val currentVal by rememberUpdatedState(currentValue)
    val currentRange by rememberUpdatedState(valueRange)

    this
        .onSizeChanged { trackWidthState.floatValue = it.width.toFloat() }
        .pointerInput(thumbHitRadiusPx) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val downChange = event.changes.firstOrNull { it.pressed && !it.previousPressed }

                    if (downChange != null && !downChange.isConsumed) {
                        val fraction = (currentVal - currentRange.start) /
                            (currentRange.endInclusive - currentRange.start).coerceAtLeast(0.01f)
                        val thumbX = fraction * trackWidthState.floatValue

                        if (Math.abs(downChange.position.x - thumbX) > thumbHitRadiusPx) {
                            downChange.consume()
                        }
                    }
                }
            }
        }
}

/**
 * 统一的右下角 FAB / 添加按钮样式 (1:1 复刻 Floatwidget 右下角按钮的材质、描边与加号图标，保持圆角矩形)
 */
@Composable
fun AppFloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconResId: Int = R.drawable.ic_add_custom,
    imageVector: ImageVector? = null,
    contentDescription: String = "添加",
    shape: Shape = RoundedCornerShape(16.dp),
    iconRotation: Float = 0f,
    content: (@Composable () -> Unit)? = null
) {
    val isDark = isSystemInDarkTheme()
    val fabBorderColor = if (isDark) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.1f)

    Box(
        modifier = modifier.padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        FloatingActionButton(
            onClick = onClick,
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.95f),
            contentColor = MaterialTheme.colorScheme.onPrimary,
            elevation = FloatingActionButtonDefaults.elevation(
                defaultElevation = 3.dp,
                pressedElevation = 6.dp
            ),
            shape = shape,
            modifier = Modifier.border(1.dp, fabBorderColor, shape)
        ) {
            if (content != null) {
                content()
            } else {
                val iconModifier = Modifier
                    .size(24.dp)
                    .graphicsLayer { rotationZ = iconRotation }

                if (imageVector != null) {
                    Icon(
                        imageVector = imageVector,
                        contentDescription = contentDescription,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = iconModifier
                    )
                } else {
                    Icon(
                        painter = painterResource(id = iconResId),
                        contentDescription = contentDescription,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = iconModifier
                    )
                }
            }
        }
    }
}

/**
 * 现代极简胶囊分段单选组件 (像素级复刻 HyperCopy / Miuix Contour 规范)
 * 包含：物理滑动药丸 (Sliding Pill Indicator)、微轮廓描边 (Contour Border)、动态未选中项分割线。
 */
@Composable
fun <T> PremiumSegmentedRow(
    options: List<T>,
    selectedOption: T,
    onOptionSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    labelProvider: (T) -> String = { it.toString() },
    iconProvider: (@Composable (T) -> Unit)? = null
) {
    if (options.isEmpty()) return

    val isDark = isSystemInDarkTheme()
    val selectedIndex = remember(options, selectedOption) {
        options.indexOf(selectedOption).coerceAtLeast(0)
    }

    // Contour 容器背景与描边颜色
    val containerBg = if (isDark) {
        Color(0xFF1E2024)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.45f)
    }
    val containerBorderColor = if (isDark) {
        Color(0x26FFFFFF)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    }

    // 选中药丸滑块背景与微描边
    val pillBg = if (isDark) Color(0xFF383A3F) else Color.White
    val pillBorderColor = if (isDark) Color(0x28FFFFFF) else Color(0x12000000)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(42.dp)
            .border(
                width = 1.dp,
                color = containerBorderColor,
                shape = RoundedCornerShape(14.dp)
            )
            .background(
                color = containerBg,
                shape = RoundedCornerShape(14.dp)
            )
            .padding(3.5.dp)
    ) {
        val totalWidth = maxWidth
        val tabWidth = totalWidth / options.size

        var isInitialized by remember(options) { mutableStateOf(false) }
        var lastSelectedIndex by remember(options) { mutableIntStateOf(selectedIndex) }

        val targetOffset = tabWidth * selectedIndex
        // 1. 物理阻尼平滑滑动药丸 (首次渲染时瞬时定位，点击切换时触发阻尼弹簧动画)
        val indicatorOffset by animateDpAsState(
            targetValue = targetOffset,
            animationSpec = if (!isInitialized || lastSelectedIndex == selectedIndex) {
                androidx.compose.animation.core.snap()
            } else {
                spring<Dp>(
                    dampingRatio = 0.84f,
                    stiffness = 420f
                )
            },
            label = "tabIndicatorOffset"
        )

        LaunchedEffect(selectedIndex) {
            if (!isInitialized) {
                isInitialized = true
            }
            lastSelectedIndex = selectedIndex
        }

        Box(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .width(tabWidth)
                .fillMaxHeight()
                .shadow(
                    elevation = if (isDark) 2.dp else 2.5.dp,
                    shape = RoundedCornerShape(10.5.dp),
                    spotColor = if (isDark) Color.Black.copy(alpha = 0.4f) else Color(0x28000000),
                    ambientColor = if (isDark) Color.Black.copy(alpha = 0.2f) else Color(0x18000000)
                )
                .border(
                    width = 0.5.dp,
                    color = pillBorderColor,
                    shape = RoundedCornerShape(10.5.dp)
                )
                .background(
                    color = pillBg,
                    shape = RoundedCornerShape(10.5.dp)
                )
        )

        // 2. 相邻未选中选项之间的微垂直分割线 (Subtle Dividers)
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            options.forEachIndexed { index, _ ->
                if (index > 0) {
                    val isNearSelected = (index == selectedIndex) || (index - 1 == selectedIndex)
                    val dividerAlpha by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = if (isNearSelected) 0f else 1f,
                        animationSpec = if (!isInitialized) androidx.compose.animation.core.snap() else androidx.compose.animation.core.tween(150),
                        label = "dividerAlpha"
                    )
                    val dividerBaseColor = if (isDark) Color(0x22FFFFFF) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    Box(
                        modifier = Modifier
                            .offset(x = (-0.5).dp)
                            .width(1.dp)
                            .height(13.dp)
                            .background(
                                color = dividerBaseColor.copy(alpha = (if (isDark) 0.22f else 0.4f) * dividerAlpha),
                                shape = CircleShape
                            )
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
            }
        }

        // 3. 上层文字与交互点击层 (Tab Content & Touch Targets)
        val selectedColor = if (isDark) Color.White else MaterialTheme.colorScheme.primary
        val unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)

        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            options.forEachIndexed { index, option ->
                val isSelected = index == selectedIndex
                val targetTextColor: Color = if (isSelected) selectedColor else unselectedColor
                val textColor by animateColorAsState(
                    targetValue = targetTextColor,
                    animationSpec = if (!isInitialized) androidx.compose.animation.core.snap() else androidx.compose.animation.core.tween(durationMillis = 200),
                    label = "tabTextColor"
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(10.5.dp))
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null
                        ) {
                            onOptionSelected(option)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        if (iconProvider != null) {
                            iconProvider(option)
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            text = labelProvider(option),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 13.sp
                            ),
                            color = textColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

