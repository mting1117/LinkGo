package com.moting.linkgo.ui.rules

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import com.moting.linkgo.image.ImageRouter
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moting.linkgo.R
import com.moting.linkgo.data.PackageRepository
import com.moting.linkgo.model.DispatchRule
import com.moting.linkgo.model.ExtractPattern
import com.moting.linkgo.model.MatchType
import com.moting.linkgo.model.ResolutionStrategy
import com.moting.linkgo.ui.components.*
import com.moting.linkgo.viewmodel.RulesViewModel
import kotlinx.coroutines.launch
import com.moting.linkgo.ui.components.reorderable.ReorderableItem
import com.moting.linkgo.ui.components.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun RulesScreen(
    bottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    onNavigateToEditRule: (String?) -> Unit,
    onNavigateToExtractionSettings: () -> Unit,
    onNavigateToEditImageRule: (String?) -> Unit = {},
    viewModel: RulesViewModel = viewModel(),
    onModeChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val clipboardManager = LocalClipboardManager.current
    val rules by viewModel.rules.collectAsState()
    val imageRules by viewModel.imageRules.collectAsState()
    val isSelectionMode by viewModel.isSelectionMode.collectAsState()
    val selectedRuleIds by viewModel.selectedRuleIds.collectAsState()

    var searchQuery by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }

    val filteredRules = remember(rules, searchQuery) {
        if (searchQuery.isBlank()) rules
        else rules.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
            it.pattern.contains(searchQuery, ignoreCase = true) ||
            it.targetPackage.contains(searchQuery, ignoreCase = true)
        }
    }

    // 图片规则的搜索只按名称与目标应用：它没有 pattern 可比
    val filteredImageRules = remember(imageRules, searchQuery) {
        if (searchQuery.isBlank()) imageRules
        else imageRules.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
            it.targetPackage.contains(searchQuery, ignoreCase = true)
        }
    }

    // 合成唯一的列表数据源：文本规则在前、图片规则在后，各自组内保持原有顺序。
    // 之前这里是两条独立的 items()，那正是"长按两类规则表现不一样"的来源；
    // 现在只有这一条列表，分组退化成行内标题。
    val listEntries = remember(filteredRules, filteredImageRules) {
        buildList {
            filteredRules.forEach { add(RuleListEntry.Text(it)) }
            filteredImageRules.forEach { add(RuleListEntry.Image(it)) }
        }
    }


    // 检测已开启但目标应用未安装的规则（方案一智能提示）。
    // 两类规则一起统计：图片规则指向未安装的应用时同样永远跳不出去
    val uninstalledTextRuleIds = remember(rules) {
        rules.filter {
            it.isEnabled &&
            it.targetPackage.isNotBlank() &&
            !PackageRepository.isAppInstalled(context, it.targetPackage)
        }.map { it.id }
    }
    val uninstalledImageRuleIds = remember(imageRules) {
        imageRules.filter {
            it.isEnabled &&
            it.targetPackage.isNotBlank() &&
            !PackageRepository.isAppInstalled(context, it.targetPackage)
        }.map { it.id }
    }
    val uninstalledEnabledRuleIds = uninstalledTextRuleIds + uninstalledImageRuleIds
    var isAlertBannerDismissed by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

    // 分组折叠状态：按分组名记忆。刻意用逗号分隔字符串承载而不是 Set——
    // rememberSaveable 需要类型能被 Bundle 保存，字符串是最稳的载体，不必赌 Set 的序列化行为。
    var collapsedGroupsRaw by androidx.compose.runtime.saveable.rememberSaveable {
        mutableStateOf("")
    }
    val collapsedGroups = remember(collapsedGroupsRaw) {
        collapsedGroupsRaw.split(",").filter { it.isNotEmpty() }.toSet()
    }

    fun toggleGroupCollapse(group: RuleGroup) {
        val next = if (group.name in collapsedGroups) collapsedGroups - group.name
        else collapsedGroups + group.name
        collapsedGroupsRaw = next.joinToString(",")
    }

    // 可见列表项：由 Header 和未折叠的分组规则展平构建。
    // 整个 LazyColumn 拥有唯一连续线性数据源：
    // 1. 折叠时不产生带有 spacedBy 间距的空 item，彻底杜绝空白行；
    // 2. 拖拽时全列表坐标统一，配合组边界拦截彻底杜绝跨组闪现。
    val visibleItems = remember(listEntries, collapsedGroups) {
        val result = mutableListOf<RuleScreenItem>()
        for (group in RuleGroup.entries) {
            val groupEntries = listEntries.filter { it.group == group }
            if (groupEntries.isNotEmpty()) {
                val isCollapsed = group.name in collapsedGroups
                result.add(RuleScreenItem.Header(group, groupEntries.size, isCollapsed))
                if (!isCollapsed) {
                    groupEntries.forEach { entry ->
                        result.add(RuleScreenItem.Rule(entry))
                    }
                }
            }
        }
        result
    }

    LaunchedEffect(isSelectionMode) {
        onModeChange(isSelectionMode)
    }

    val pendingRules by viewModel.pendingImportRules.collectAsState()
    val pendingImportExtractPatterns by viewModel.pendingImportExtractPatterns.collectAsState()
    val pendingImportImageRules by viewModel.pendingImportImageRules.collectAsState()
    val pendingExportImageRules by viewModel.pendingExportImageRules.collectAsState()
    val showImportPreview by viewModel.showImportPreview.collectAsState()
    val extractionPatterns by viewModel.extractionPatterns.collectAsState()
    
    val pendingExportRules by viewModel.pendingExportRules.collectAsState()
    val pendingExportExtractPatterns by viewModel.pendingExportExtractPatterns.collectAsState()
    val showExportPreview by viewModel.showExportPreview.collectAsState()
    val exportTarget by viewModel.exportTarget.collectAsState()

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showManualImportDialog by remember { mutableStateOf(false) }

    val normalizationEnabled by viewModel.normalizationEnabled.collectAsState()
    
    var showDataManagementSheet by remember { mutableStateOf(false) }
    var showOrganizationSheet by remember { mutableStateOf(false) }

    // SAF 文件选择器
    val createDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: android.net.Uri? ->
        uri?.let { 
            val mode = if (isSelectionMode) "SELECTED" else "PREVIEW"
            viewModel.exportRulesToUri(context, it, mode)
        }
    }
    
    val openDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? ->
        uri?.let { viewModel.importRulesFromUri(context, it) }
    }

    // 拖拽排序相关状态
    val haptic = LocalHapticFeedback.current
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(
        lazyListState = lazyListState,
        scrollThreshold = 100.dp,
        onMove = { from, to ->
            val fromKey = from.key as? String ?: return@rememberReorderableLazyListState
            val toKey = to.key as? String ?: return@rememberReorderableLazyListState

            // 查找两个 key 对应的具体规则
            val fromRule = listEntries.find { it.id == fromKey } ?: return@rememberReorderableLazyListState
            val toRule = listEntries.find { it.id == toKey } ?: return@rememberReorderableLazyListState

            // 物理屏障：跨分组移动（或越过 Header）绝对禁止，彻底消除触摸位置与组末尾/开头的来回闪现跳动
            if (fromRule.group != toRule.group) {
                return@rememberReorderableLazyListState
            }

            // 同组内安全排序移动
            when (fromRule.group) {
                RuleGroup.DISPATCH, RuleGroup.REDIRECT -> {
                    if (rules.any { it.id == fromKey } && rules.any { it.id == toKey }) {
                        viewModel.onMove(fromKey, toKey)
                    }
                }
                RuleGroup.IMAGE -> {
                    if (imageRules.any { it.id == fromKey } && imageRules.any { it.id == toKey }) {
                        viewModel.onMoveImageRule(fromKey, toKey)
                    }
                }
            }
        }
    )
    val coroutineScope = rememberCoroutineScope()
    val isAnyDragging = reorderableState.isAnyItemDragging

    /**
     * 组边界锁定器：根据当前规则所属组在 LazyColumn 中的实时物理槽位，
     * 精准限制拖拽位移区间，拖到组首项或组末项后绝对不再跟随手指移动。
     */
    val dragBoundsProvider: (Any) -> Pair<Float, Float>? = remember(visibleItems, listEntries) {
        { key ->
            val keyStr = key as? String
            if (keyStr == null) null
            else {
                val currentRule = listEntries.find { it.id == keyStr }
                if (currentRule == null) null
                else {
                    val group = currentRule.group
                    val groupRuleKeys = visibleItems.mapNotNull {
                        (it as? RuleScreenItem.Rule)?.entry?.takeIf { e -> e.group == group }?.id
                    }.toSet()

                    val visibleInfos = lazyListState.layoutInfo.visibleItemsInfo
                    val groupInfos = visibleInfos.filter { it.key in groupRuleKeys }
                    val currentInfo = visibleInfos.find { it.key == keyStr }

                    if (groupInfos.isNotEmpty() && currentInfo != null) {
                        val minTop = groupInfos.minOf { it.offset }
                        val maxTop = groupInfos.maxOf { it.offset }
                        val currentSlotTop = currentInfo.offset

                        val minY = (minTop - currentSlotTop).toFloat()
                        val maxY = (maxTop - currentSlotTop).toFloat()
                        minY to maxY
                    } else {
                        null
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.initRepository(context)
        PackageRepository.loadAllApps(context.applicationContext)
    }

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { message: String ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    var activeBreathingRuleId by remember { mutableStateOf<String?>(null) }

    /**
     * 列表上方条件性 item 的数量（失效提示横幅 + 链接提取卡片）。
     *
     * 定位规则时要把"规则在数据里的序号"换算成"LazyColumn 里的绝对序号"，
     * 少了这一段偏移就会滚到错误的位置。用 derivedStateOf 是因为它要在
     * LaunchedEffect 的闭包里读，必须是实时值而不是首次组合时的快照。
     */
    val listPrefixItemCount by remember {
        androidx.compose.runtime.derivedStateOf {
            var count = 0
            if (uninstalledEnabledRuleIds.isNotEmpty() && !isAlertBannerDismissed &&
                !isSelectionMode && searchQuery.isBlank()
            ) {
                count++
            }
            if (searchQuery.isBlank()) count++
            count
        }
    }

    LaunchedEffect(Unit) {
        viewModel.locateRuleEvent.collect { event ->
            val targetId = event.ruleId
            searchQuery = ""

            // 1. 等待规则列表从 Repository 加载就绪（最多轮询 20 次，每次 50ms）
            var textRules = viewModel.rules.value
            var currentImageRules = viewModel.imageRules.value
            var waitCount = 0
            while ((textRules.isEmpty() && currentImageRules.isEmpty()) && waitCount < 20) {
                kotlinx.coroutines.delay(50)
                textRules = viewModel.rules.value
                currentImageRules = viewModel.imageRules.value
                waitCount++
            }

            // 若在文本规则中未找到，且图片规则为空，额外等待图片规则就绪
            if (textRules.none { it.id == targetId } && currentImageRules.isEmpty()) {
                var imgWait = 0
                while (currentImageRules.isEmpty() && imgWait < 15) {
                    kotlinx.coroutines.delay(50)
                    currentImageRules = viewModel.imageRules.value
                    imgWait++
                }
            }

            // 判定目标规则所在分组并确保自动展开折叠
            val targetTextRule = textRules.find { it.id == targetId }
            val targetGroup = when {
                targetTextRule != null && targetTextRule.resolveStrategy == ResolutionStrategy.RE_DISPATCH -> RuleGroup.DISPATCH
                targetTextRule != null -> RuleGroup.REDIRECT
                currentImageRules.any { it.id == targetId } -> RuleGroup.IMAGE
                else -> null
            }
            if (targetGroup != null && targetGroup.name in collapsedGroups) {
                val next = collapsedGroups - targetGroup.name
                collapsedGroupsRaw = next.joinToString(",")
            }

            // 计算目标规则在 LazyColumn 中的绝对 index（考虑 prefix items、各组 header 与折叠情况）
            var computedIndex = -1
            if (targetGroup != null) {
                var runningIndex = listPrefixItemCount
                for (grp in RuleGroup.entries) {
                    val groupItemIds: List<String> = when (grp) {
                        RuleGroup.DISPATCH -> textRules.filter { it.resolveStrategy == ResolutionStrategy.RE_DISPATCH }.map { it.id }
                        RuleGroup.REDIRECT -> textRules.filter { it.resolveStrategy != ResolutionStrategy.RE_DISPATCH }.map { it.id }
                        RuleGroup.IMAGE -> currentImageRules.map { it.id }
                    }
                    if (groupItemIds.isNotEmpty()) {
                        runningIndex++ // 独立 header item
                        if (grp == targetGroup) {
                            val inGroupIdx = groupItemIds.indexOf(targetId)
                            if (inGroupIdx >= 0) {
                                computedIndex = runningIndex + inGroupIdx
                                break
                            }
                        } else {
                            if (grp.name !in collapsedGroups) {
                                runningIndex += groupItemIds.size
                            }
                        }
                    }
                }
            }

            val found = computedIndex >= 0
            val targetIndex = if (found) computedIndex else 0

            if (found) {
                // 等待 Pager 翻页动画缓冲与 LazyColumn 挂载就绪
                kotlinx.coroutines.delay(200)

                // 2. 等待 LazyColumn 测量挂载就绪
                var layoutWaitCount = 0
                while (lazyListState.layoutInfo.totalItemsCount == 0 && layoutWaitCount < 10) {
                    kotlinx.coroutines.delay(30)
                    layoutWaitCount++
                }

                // 缓冲一帧确保布局稳定
                kotlinx.coroutines.delay(50)

                // 计算屏幕居中偏移量
                val viewportHeight = lazyListState.layoutInfo.viewportSize.height
                val itemHeightPx = density.run { 80.dp.roundToPx() }
                val centerOffset = if (viewportHeight > 0) {
                    -((viewportHeight - itemHeightPx) / 2).coerceAtLeast(0)
                } else {
                    -density.run { 200.dp.roundToPx() }
                }

                // 瞬间定位至目标项屏幕中央（无滚动动画）
                lazyListState.scrollToItem(
                    index = targetIndex,
                    scrollOffset = centerOffset
                )

                // 确保一帧渲染后再点亮呼吸动画，让动画在用户眼前完整绽放
                kotlinx.coroutines.delay(50)
                activeBreathingRuleId = targetId
            } else {
                Toast.makeText(context, "未找到对应规则（可能已被修改或删除）", Toast.LENGTH_SHORT).show()
                viewModel.clearLocateRuleEvent()
            }
        }
    }

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(lazyListState.isScrollInProgress) {
        if (lazyListState.isScrollInProgress) {
            keyboardController?.hide()
        }
    }

    // 两类规则共用同一份批量选择态：它们在列表里是平级的，
    // 长按哪一条都是同一个动作，因此不再有"两套模式互斥"这回事
    BackHandler(enabled = isSelectionMode) {
        viewModel.exitSelectionMode()
    }

    var frozenSelectedCount by remember { mutableIntStateOf(0) }
    var frozenIsAllSelected by remember { mutableStateOf(false) }
    var frozenIsAnySelected by remember { mutableStateOf(false) }
    var frozenAllEnabled by remember { mutableStateOf(true) }

    if (isSelectionMode) {
        // 选中集合同时容纳两类规则的 id，统计与"批量开关"都要按合并后的结果算。
        // 两类规则的模型不同，所以启用态分别判断后再合并结论
        val selectedTextRules = rules.filter { it.id in selectedRuleIds }
        val selectedImageRules = imageRules.filter { it.id in selectedRuleIds }
        val totalCount = rules.size + imageRules.size

        frozenSelectedCount = selectedRuleIds.size
        frozenIsAllSelected = selectedRuleIds.size == totalCount && totalCount > 0
        frozenIsAnySelected = selectedRuleIds.isNotEmpty()
        frozenAllEnabled = selectedRuleIds.isNotEmpty() &&
            selectedTextRules.all { it.isEnabled } &&
            selectedImageRules.all { it.isEnabled }
    }

    val isAllEmpty = rules.isEmpty() && imageRules.isEmpty()
    val isSearchEmpty = !isAllEmpty && searchQuery.isNotBlank() &&
        filteredRules.isEmpty() && filteredImageRules.isEmpty()

    MainPageScaffold(
        titleContent = {
            AnimatedContent(
                targetState = if (isSelectionMode) "已选 $frozenSelectedCount" else "分发规则",
                transitionSpec = {
                    (fadeIn(animationSpec = tween(200)) togetherWith fadeOut(animationSpec = tween(150)))
                        .using(SizeTransform(clip = false))
                },
                label = "appBarTitle"
            ) { titleText ->
                Text(
                    text = titleText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-1.5).sp
                    ),
                    modifier = Modifier.offset(y = (-4).dp),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        actions = {
            AnimatedContent(
                targetState = isSelectionMode,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(200)) togetherWith fadeOut(animationSpec = tween(150)))
                        .using(SizeTransform(clip = false))
                },
                label = "appBarActions"
            ) { inSelection ->
                if (inSelection) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showOrganizationSheet = true }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "一键整理",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.2.sp,
                                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                            ),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                } else {
                    IconButton(onClick = { showDataManagementSheet = true }) {
                        Icon(
                            imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_data_management),
                            contentDescription = "数据管理",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = isSelectionMode,
                enter = slideInVertically(animationSpec = tween(220, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(150)),
                exit = slideOutVertically(animationSpec = tween(180, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(120))
            ) {
                SelectionBottomBar(
                    onToggleSelectAll = {
                        if (frozenIsAllSelected) viewModel.clearSelection() else viewModel.selectAll()
                    },
                    allSelected = frozenIsAllSelected,
                    onToggleEnabled = {
                        // 选中项当前全部为启用态则批量停用，否则批量启用。
                        // 两类规则共用一份选中集合，因此这一个动作会同时作用在两类上
                        viewModel.toggleSelectedRules(!frozenAllEnabled)
                    },
                    allEnabled = frozenAllEnabled,
                    onShare = {
                        // 选中的两类规则按各自包络键导出（导出后可直接粘回数据管理导入）
                        val json = viewModel.exportSelectedRulesToJson()
                        val sendIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, json)
                            type = "text/plain"
                        }
                        context.startActivity(Intent.createChooser(sendIntent, "分享选中的规则"))
                    },
                    onSaveToFile = {
                        createDocumentLauncher.launch("linkgo_rules_${System.currentTimeMillis()}.json")
                    },
                    onDuplicate = {
                        viewModel.duplicateSelectedRules()
                    },
                    onDelete = { showDeleteDialog = true },
                    anySelected = frozenIsAnySelected
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
        ) {
            // 常驻搜索栏：左右 16dp 边距与卡片对齐，批量模式下淡化禁用且高度固定零跳动
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (isSelectionMode) 0.38f else 1f)
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
            ) {
                CapsuleSearchBar(
                    query = searchQuery,
                    onQueryChange = {
                        if (!isSelectionMode) {
                            searchQuery = it
                        }
                    },
                    placeholder = "搜索规则、特征或包名...",
                    enabled = !isSelectionMode,
                    focusRequester = focusRequester,
                    onClear = {
                        searchQuery = ""
                        focusRequester.requestFocus()
                        coroutineScope.launch {
                            lazyListState.scrollToItem(0)
                        }
                    }
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (isAllEmpty) {
                    EmptyRulesView(modifier = Modifier.fillMaxSize())
                } else if (isSearchEmpty) {
                    EmptySearchView(
                        query = searchQuery,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp, 
                        end = 16.dp, 
                        top = 8.dp, 
                        bottom = bottomPadding + 80.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 0. 未安装应用失效规则智能提示横幅（方案一）
                    if (uninstalledEnabledRuleIds.isNotEmpty() && !isAlertBannerDismissed && !isSelectionMode && searchQuery.isBlank()) {
                        item(key = "uninstalled_alert_banner") {
                            UninstalledRulesAlertBanner(
                                count = uninstalledEnabledRuleIds.size,
                                onLocate = {
                                    val firstId = uninstalledEnabledRuleIds.firstOrNull()
                                    if (firstId != null) {
                                        viewModel.locateRule(firstId)
                                    }
                                },
                                onDisableAll = {
                                    viewModel.disableRules(uninstalledEnabledRuleIds.toSet())
                                },
                                onDismiss = {
                                    isAlertBannerDismissed = true
                                }
                            )
                        }
                    }

                    // 1. 链接提取 (系统置顶) - 仅在非空搜索时收起，批量模式下淡化禁用且高度固定零跳动
                    if (searchQuery.isBlank()) {
                        item(key = "system_normalization") {
                            Column(
                                modifier = Modifier.alpha(if (isSelectionMode) 0.38f else if (isAnyDragging) 0.5f else 1f)
                            ) {
                                NormalizationCard(
                                    enabled = normalizationEnabled,
                                    isSelectionMode = isSelectionMode,
                                    onToggle = { if (!isSelectionMode) viewModel.toggleNormalization(it) },
                                    onClick = { if (!isSelectionMode) onNavigateToExtractionSettings() }
                                )
                            }
                        }
                    }

                    // 2. 规则列表：单一扁平化渲染（统一 Header 与 Rule）
                    // 彻底综合解决：
                    // - 分组折叠时完全剔除被收起规则，绝无任何空列表行；
                    // - 拖拽排序全列表统一坐标系统，配合 onMove 组边界物理拦截，彻底杜绝跨组闪现；
                    // - 全局 animateItem 原生动效支撑折叠/展开与拖拽让位。
                    items(
                        items = visibleItems,
                        key = { it.key },
                        contentType = {
                            when (it) {
                                is RuleScreenItem.Header -> "header"
                                is RuleScreenItem.Rule -> it.entry.group.name
                            }
                        }
                    ) { item ->
                        when (item) {
                            is RuleScreenItem.Header -> {
                                Box(
                                    modifier = Modifier
                                        .alpha(if (isAnyDragging) 0.5f else 1f)
                                        .padding(top = 4.dp, bottom = 2.dp)
                                        .animateItem(
                                            placementSpec = spring(
                                                stiffness = Spring.StiffnessMediumLow,
                                                visibilityThreshold = IntOffset.VisibilityThreshold
                                            )
                                        )
                                ) {
                                    CollapsibleSectionHeader(
                                        title = "${item.group.title} (${item.count})",
                                        collapsed = item.isCollapsed,
                                        backgroundColor = MaterialTheme.colorScheme.background,
                                        onToggleCollapse = { toggleGroupCollapse(item.group) }
                                    )
                                }
                            }
                            is RuleScreenItem.Rule -> {
                                val entry = item.entry
                                ReorderableItem(
                                    state = reorderableState,
                                    key = entry.id,
                                    dragBoundsProvider = dragBoundsProvider
                                ) { isDragging ->
                                    val dragHandleModifier = remember(reorderableState, haptic) {
                                        Modifier.draggableHandle(
                                            onDragStarted = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            },
                                            onDragStopped = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                // 两类规则共用同一个拖拽回调，按被拖项的类型决定落盘方式
                                                when (entry) {
                                                    is RuleListEntry.Text -> viewModel.normalizeRuleOrder()
                                                    is RuleListEntry.Image -> viewModel.saveImageRuleOrder()
                                                }
                                            },
                                        )
                                    }

                                    Box(
                                        modifier = if (isDragging) Modifier else Modifier.animateItem(
                                            placementSpec = spring(
                                                stiffness = Spring.StiffnessMediumLow,
                                                visibilityThreshold = IntOffset.VisibilityThreshold
                                            )
                                        )
                                    ) {
                                        DraggableRuleEntry(
                                            entry = entry,
                                            viewModel = viewModel,
                                            isSelectionMode = isSelectionMode,
                                            selectedRuleIds = selectedRuleIds,
                                            isHighlighted = activeBreathingRuleId == entry.id,
                                            onHighlightFinished = {
                                                activeBreathingRuleId = null
                                                viewModel.clearLocateRuleEvent()
                                            },
                                            isDragging = isDragging,
                                            onNavigateToEditRule = onNavigateToEditRule,
                                            onNavigateToEditImageRule = onNavigateToEditImageRule,
                                            dragHandleModifier = dragHandleModifier
                                        )
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

    if (showDeleteDialog) {
        ConfirmActionSheet(
            title = "删除确认",
            // 一次批量删除同时作用于两类规则：选中集合是共享的，文案也按同一份集合报数
            message = "确定要删除选中的 ${selectedRuleIds.size} 条规则吗？此操作无法撤销。",
            confirmLabel = "彻底删除",
            destructive = true,
            onConfirm = {
                viewModel.deleteSelectedRules()
                showDeleteDialog = false
            },
            onDismiss = { showDeleteDialog = false }
        )
    }

    if (showImportPreview) {
        RulesSelectionDialog(
            title = "导入预览",
            confirmTitle = "确认导入",
            pendingRules = pendingRules,
            pendingExtractPatterns = pendingImportExtractPatterns,
            pendingImageRules = pendingImportImageRules,
            currentRules = rules,
            currentExtractPatterns = extractionPatterns,
            currentImageRules = imageRules,
            showDuplicateWarning = true,
            onDismiss = { viewModel.dismissImportPreview() },
            onConfirm = { selectedIds ->
                val count = viewModel.confirmImport(selectedIds)
                Toast.makeText(context, "成功导入 $count 条", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showExportPreview) {
        RulesSelectionDialog(
            title = "导出预览",
            confirmTitle = "确认导出",
            pendingRules = pendingExportRules,
            pendingExtractPatterns = pendingExportExtractPatterns,
            pendingImageRules = pendingExportImageRules,
            currentRules = emptyList(),
            showDuplicateWarning = true,
            onDismiss = { viewModel.dismissExportPreview() },
            onConfirm = { selectedIds ->
                if (exportTarget == com.moting.linkgo.viewmodel.ExportTarget.CLIPBOARD) {
                    val json = viewModel.exportSpecifiedRulesToJson(selectedIds)
                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(json))
                    Toast.makeText(context, "已复制选中的规则、提取规则与图片规则到剪贴板", Toast.LENGTH_SHORT).show()
                    viewModel.dismissExportPreview()
                } else {
                    viewModel.recordExportSelection(selectedIds)
                    createDocumentLauncher.launch("linkgo_rules_${System.currentTimeMillis()}.json")
                }
            }
        )
    }

    if (showManualImportDialog) {
        ManualImportDialog(
            onDismiss = { showManualImportDialog = false },
            onConfirm = { text ->
                if (viewModel.prepareImport(text)) {
                    showManualImportDialog = false
                }
            }
        )
    }

    if (showDataManagementSheet) {
        DataManagementSheet(
            onDismiss = { showDataManagementSheet = false },
            onImportClipboard = {
                showDataManagementSheet = false
                val text = clipboardManager.getText()?.text
                if (!text.isNullOrBlank()) {
                    viewModel.prepareImport(text)
                } else {
                    Toast.makeText(context, "剪贴板内容为空", Toast.LENGTH_SHORT).show()
                }
            },
            onImportFile = {
                showDataManagementSheet = false
                openDocumentLauncher.launch(arrayOf("application/json", "text/plain"))
            },
            onManualImport = {
                showDataManagementSheet = false
                showManualImportDialog = true
            },
            onExportClipboard = {
                showDataManagementSheet = false
                viewModel.prepareExport(com.moting.linkgo.viewmodel.ExportTarget.CLIPBOARD)
            },
            onExportFile = {
                showDataManagementSheet = false
                viewModel.prepareExport(com.moting.linkgo.viewmodel.ExportTarget.FILE)
            }
        )
    }

    if (showOrganizationSheet) {
        // 数量按两类规则合计：只数文本规则会让图片规则里的失效项被漏掉
        val uninstalledAppCount = remember(rules, imageRules) { viewModel.getUninstalledAppCount(context) }
        RuleOrganizationBottomSheet(
            onDismiss = { showOrganizationSheet = false },
            uninstalledCount = uninstalledAppCount,
            onApplySort = { byApp, byEnabled, byName ->
                showOrganizationSheet = false
                viewModel.organizeRulesMulti(context, byApp, byEnabled, byName)
            },
            onDisableUninstalled = {
                showOrganizationSheet = false
                viewModel.disableUninstalledRules(context)
            },
            onSinkUninstalled = {
                showOrganizationSheet = false
                viewModel.sinkUninstalledRules(context)
            }
        )
    }
}

/**
 * 规则整理 BottomSheet (无图标，紧凑多选排版)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleOrganizationBottomSheet(
    onDismiss: () -> Unit,
    uninstalledCount: Int,
    onApplySort: (byApp: Boolean, byEnabled: Boolean, byName: Boolean) -> Unit,
    onDisableUninstalled: () -> Unit,
    onSinkUninstalled: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var sortByApp by remember { mutableStateOf(false) }
    var sortByEnabled by remember { mutableStateOf(false) }
    var sortByName by remember { mutableStateOf(false) }

    val anySelected = sortByApp || sortByEnabled || sortByName

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp, top = 0.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // 标题区（无图标，紧凑排版）
            Column(modifier = Modifier.padding(bottom = 12.dp)) {
                Text(
                    text = "整理规则",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "多选排序维度，重新排列规则顺序或清理失效规则",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }

            // 1. 排序方式（多选）
            Text(
                text = "排序方式",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 2.dp, bottom = 6.dp)
            )

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(vertical = 2.dp)) {
                    SortCheckboxOptionItem(
                        title = "按目标应用归类",
                        subtitle = "相同应用的规则聚在一块，组内按匹配精度排序",
                        checked = sortByApp,
                        onCheckedChange = { sortByApp = it }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                    )
                    SortCheckboxOptionItem(
                        title = "已启用的排在前面",
                        subtitle = "开启的规则置顶，关闭的规则沉底",
                        checked = sortByEnabled,
                        onCheckedChange = { sortByEnabled = it }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                    )
                    SortCheckboxOptionItem(
                        title = "按规则名称 (A-Z)",
                        subtitle = "按规则名称拼音/字母顺序排列",
                        checked = sortByName,
                        onCheckedChange = { sortByName = it }
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Button(
                onClick = { onApplySort(sortByApp, sortByEnabled, sortByName) },
                enabled = anySelected,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    "应用排序",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                )
            }

            Spacer(Modifier.height(16.dp))

            // 2. 失效规则清理
            Text(
                text = "失效规则清理",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 2.dp, bottom = 6.dp)
            )

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Text(
                        text = "未安装应用检测",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (uninstalledCount > 0) "检测到 $uninstalledCount 条规则的目标应用在本机未安装"
                               else "所有规则的目标应用均已正常安装",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (uninstalledCount > 0) MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
                               else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )

                    if (uninstalledCount > 0) {
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilledTonalButton(
                                onClick = onDisableUninstalled,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(36.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                                ),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Text("一键停用", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                            }
                            OutlinedButton(
                                onClick = onSinkUninstalled,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(36.dp),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Text("移到末尾", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SortCheckboxOptionItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        onClick = { onCheckedChange(!checked) },
        color = if (checked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 3.dp, vertical = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (checked) FontWeight.Bold else FontWeight.Medium
                    ),
                    color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary,
                    uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            )
        }
    }
}

/**
 * 未安装应用失效规则智能提示横幅 (方案一)
 */
@Composable
fun UninstalledRulesAlertBanner(
    count: Int,
    onLocate: () -> Unit,
    onDisableAll: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.42f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.2f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_warning_triangle),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onLocate
                    )
            ) {
                Text(
                    text = "检测到 $count 条规则目标应用未安装",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = "点击滚动定位至对应规则",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.75f)
                )
            }

            // 一键禁用按钮
            TextButton(
                onClick = onDisableAll,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Text(
                    text = "一键禁用",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.error
                )
            }

            // 关闭按钮
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_xmark),
                    contentDescription = "关闭提示",
                    tint = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.55f),
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }
}

/**
 * 链接提取卡片 (系统规则)
 */
@Composable
fun NormalizationCard(
    enabled: Boolean,
    isSelectionMode: Boolean = false,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = !isSelectionMode,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_flash),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "链接提取",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (enabled) "尝试从文本中提取链接" else "功能已关闭，规则仅处理原始文本",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            PremiumSwitch(
                checked = enabled,
                onCheckedChange = { onToggle(it) },
                enabled = !isSelectionMode
            )
        }
    }
}

/**
 * 规则卡片的展示模型。
 *
 * 图片规则与跳转规则在外观、交互、拖拽手感上必须完全一致，唯一的差异是副标题那一行
 * （跳转规则显示"匹配方式 + 模式"，图片规则显示"分享入口 + 目标应用"）。
 * 因此把差异收敛成这一个模型，列表项组件本身对规则类型无感知——
 * 此前两类规则各走一套列表项代码，拖拽缩放、圆角裁剪、长按语义都在包装层里，
 * 图片侧没有这层包装，于是手感天然不一致。
 */
private class RuleCardModel(
    val icon: @Composable () -> Unit,
    val name: String,
    val isEnabled: Boolean,
    val subtitleTag: String,
    val subtitleText: String,
    val subtitleColor: Color? = null
)

/** 由文本规则构建展示模型 */
@Composable
private fun buildRuleCardModel(rule: DispatchRule): RuleCardModel = RuleCardModel(
    icon = {
        RuleIcon(
            isReDispatch = rule.resolveStrategy == ResolutionStrategy.RE_DISPATCH,
            iconPath = rule.iconPath,
            targetPackage = rule.targetPackage,
            size = 44.dp,
            shape = RoundedCornerShape(12.dp),
            isEnabled = rule.isEnabled
        )
    },
    name = rule.name,
    isEnabled = rule.isEnabled,
    subtitleTag = rule.matchType.label,
    subtitleText = rule.pattern
)

/** 由图片规则构建展示模型 */
@Composable
private fun buildImageRuleCardModel(rule: com.moting.linkgo.model.ImageRule): RuleCardModel {
    val context = LocalContext.current
    // 目标应用是图片规则唯一会失效的地方（被卸载、或版本不再支持收图），
    // 实时校验并把结论直接标在卡片上，避免用户配好了却永远跳不出去
    val targetState = remember(rule.targetPackage) {
        when {
            rule.targetPackage.isBlank() -> TargetState.UNSET
            !PackageRepository.isAppInstalled(context, rule.targetPackage) -> TargetState.MISSING
            ImageRouter.canReceiveImage(context, rule.targetPackage) -> TargetState.OK
            else -> TargetState.UNSUPPORTED
        }
    }
    val appLabel = remember(rule.targetPackage) {
        if (rule.targetPackage.isBlank()) "" else PackageRepository.getAppLabel(context, rule.targetPackage)
    }
    val (hintText, hintColor) = when (targetState) {
        TargetState.OK -> "发送到 $appLabel" to null
        TargetState.UNSET -> "未设置分享入口" to MaterialTheme.colorScheme.secondary
        TargetState.MISSING -> "目标应用未安装" to MaterialTheme.colorScheme.error
        TargetState.UNSUPPORTED -> "该应用不支持接收图片" to MaterialTheme.colorScheme.error
    }
    return RuleCardModel(
        icon = {
            RuleIcon(
                iconPath = rule.iconPath,
                targetPackage = rule.targetPackage,
                size = 44.dp,
                shape = RoundedCornerShape(12.dp),
                isEnabled = rule.isEnabled
            )
        },
        name = rule.name.ifBlank { "未命名图片规则" },
        isEnabled = rule.isEnabled,
        subtitleTag = "分享入口",
        subtitleText = hintText,
        subtitleColor = hintColor
    )
}

/**
 * 规则列表项：**文本规则与图片规则唯一的列表渲染入口**。
 *
 * 拖拽缩放、圆角裁剪、无障碍语义、选中态、长按语义全部在这里统一处理，
 * 调用方只需给出展示模型与选择态回调。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun RuleListItem(
    model: RuleCardModel,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    isHighlighted: Boolean,
    onHighlightFinished: () -> Unit,
    isDragging: Boolean,
    dragHandleModifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggle: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (isDragging) 1.02f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "dragScale"
    )

    val isElevated = isDragging || scale > 1.002f

    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                clip = true
                shape = RoundedCornerShape(20.dp)
            }
            .zIndex(if (isElevated) 1f else 0f)
            .clearAndSetSemantics {
                contentDescription = model.name
            }
    ) {
        RuleCardShell(
            icon = model.icon,
            name = model.name,
            isEnabled = model.isEnabled,
            isSelectionMode = isSelectionMode,
            isHighlighted = isHighlighted,
            isSelected = isSelected,
            onHighlightFinished = onHighlightFinished,
            dragHandleModifier = dragHandleModifier,
            subtitle = {
                RuleTypeTag(text = model.subtitleTag)
                RuleSubtitleText(
                    text = model.subtitleText,
                    isEnabled = model.isEnabled,
                    modifier = Modifier.weight(1f),
                    color = model.subtitleColor
                )
            },
            onClick = onClick,
            onLongClick = onLongClick,
            onToggle = onToggle
        )
    }
}

/**
 * 规则列表里的一个条目。
 *
 * 文本规则与图片规则在这张列表里是**平级**的：同一个长按进入批量选择、同一份选中集合、
 * 同一套拖拽语义。之前它们是两条独立的 items() 且各有一套选择态，
 * 于是同一个长按动作在两类规则上表现不同——合并成一条列表后，
 * 差异只剩"渲染用哪个展示模型"和"点击去哪一页编辑"。
 */
private sealed interface RuleListEntry {
    val id: String

    /** 行内分组标题的归属 */
    val group: RuleGroup

    data class Text(val rule: DispatchRule) : RuleListEntry {
        override val id: String get() = rule.id
        override val group: RuleGroup
            get() = if (rule.resolveStrategy == ResolutionStrategy.RE_DISPATCH) RuleGroup.DISPATCH
            else RuleGroup.REDIRECT
    }

    data class Image(val rule: com.moting.linkgo.model.ImageRule) : RuleListEntry {
        override val id: String get() = rule.id
        override val group: RuleGroup get() = RuleGroup.IMAGE
    }
}

/** 列表内的分组，顺序即展示顺序 */
private enum class RuleGroup(val title: String) {
    DISPATCH("分发规则"),
    REDIRECT("跳转规则"),
    IMAGE("图片规则")
}

/**
 * 规则列表的可见行包装模型（统一 Header 与 Rule，保证 LazyColumn 拥有单一扁平数据源）。
 * 折叠时直接从可见列表中排除被收起的规则（零空行、动画由 Compose 原生驱动），
 * 拖拽时保证全列表坐标统一，配合组边界拦截彻底解决跨组闪现。
 */
private sealed interface RuleScreenItem {
    val key: String

    data class Header(
        val group: RuleGroup,
        val count: Int,
        val isCollapsed: Boolean
    ) : RuleScreenItem {
        override val key: String get() = "header_${group.name}"
    }

    data class Rule(
        val entry: RuleListEntry
    ) : RuleScreenItem {
        override val key: String get() = entry.id
    }
}

/**
 * 规则列表项：**文本规则与图片规则唯一的列表渲染入口**。
 *
 * 与图片规则走同一个 RuleListItem，仅负责把规则转成展示模型、
 * 并把点击/长按/开关派发到对应类型的方法上。
 */
@Composable
private fun DraggableRuleEntry(
    entry: RuleListEntry,
    viewModel: RulesViewModel,
    isSelectionMode: Boolean,
    selectedRuleIds: Set<String>,
    isHighlighted: Boolean = false,
    onHighlightFinished: () -> Unit = {},
    isDragging: Boolean,
    onNavigateToEditRule: (String?) -> Unit,
    onNavigateToEditImageRule: (String?) -> Unit,
    dragHandleModifier: Modifier = Modifier
) {
    when (entry) {
        is RuleListEntry.Text -> RuleListItem(
            model = buildRuleCardModel(entry.rule),
            isSelectionMode = isSelectionMode,
            isSelected = entry.id in selectedRuleIds,
            isHighlighted = isHighlighted,
            onHighlightFinished = onHighlightFinished,
            isDragging = isDragging,
            dragHandleModifier = dragHandleModifier,
            onClick = {
                if (isSelectionMode) viewModel.toggleRuleSelection(entry.id)
                else onNavigateToEditRule(entry.id)
            },
            onLongClick = {
                if (!isSelectionMode) viewModel.enterSelectionMode(entry.id)
            },
            onToggle = { viewModel.toggleRule(entry.id) }
        )

        is RuleListEntry.Image -> RuleListItem(
            model = buildImageRuleCardModel(entry.rule),
            isSelectionMode = isSelectionMode,
            isSelected = entry.id in selectedRuleIds,
            isHighlighted = isHighlighted,
            onHighlightFinished = onHighlightFinished,
            isDragging = isDragging,
            dragHandleModifier = dragHandleModifier,
            onClick = {
                if (isSelectionMode) viewModel.toggleRuleSelection(entry.id)
                else onNavigateToEditImageRule(entry.id)
            },
            onLongClick = {
                if (!isSelectionMode) viewModel.enterSelectionMode(entry.id)
            },
            onToggle = { viewModel.toggleImageRule(entry.id) }
        )
    }
}

/** 图片规则目标应用的实时状态 */
private enum class TargetState { OK, UNSET, MISSING, UNSUPPORTED }


/**
 * 可折叠分组标题组件。
 *
 * **主列表与选择对话框共用同一个实现**：此前主列表用的是不可折叠的 [RuleGroupHeader]，
 * 两者是同一份标题结构的重复实现，只会各自漂移。折叠版收敛了整行点击折叠 + 箭头旋转 + 可选复选框，
 * 由 [backgroundColor] 适配主列表需要的实底色（选择对话框为透明）。
 */
@Composable
fun CollapsibleSectionHeader(
    title: String,
    collapsed: Boolean,
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.Transparent,
    showCheckbox: Boolean = false,
    isChecked: Boolean = false,
    onToggleCollapse: () -> Unit,
    onToggleSelect: () -> Unit = {}
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = backgroundColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleCollapse)
                .padding(top = 12.dp, bottom = 8.dp),
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
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.5.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )

            if (showCheckbox) {
                Icon(
                    imageVector = if (isChecked) ImageVector.vectorResource(id = R.drawable.ic_iconoir_check_square) else ImageVector.vectorResource(id = R.drawable.ic_iconoir_square),
                    contentDescription = null,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable(onClick = onToggleSelect),
                    tint = if (isChecked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (collapsed) "展开" else "折叠",
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { rotationZ = if (collapsed) 0f else 90f },
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}



/**
 * 规则卡片外壳：把「视觉与交互」与「规则类型」彻底分开。
 *
 * 抽出来的理由很直接：图片规则与跳转规则在外观上必须**完全一致**
 * （用户明确要求），而两者唯一的差异只是副标题那一行的内容——
 * 跳转规则显示"匹配方式 + 模式"，图片规则显示"分享接口 + 目标应用"。
 * 复制一份卡片实现迟早会漂移，所以只把副标题做成插槽。
 */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun RuleCardShell(
    icon: @Composable () -> Unit,
    name: String,
    isEnabled: Boolean,
    isSelectionMode: Boolean,
    isHighlighted: Boolean,
    isSelected: Boolean = false,
    onHighlightFinished: () -> Unit,
    dragHandleModifier: Modifier,
    subtitle: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggle: () -> Unit
) {
    val isTargetHighlighted = isHighlighted
    val highlightProgress = remember { Animatable(0f) }

    LaunchedEffect(isTargetHighlighted) {
        if (isTargetHighlighted) {
            highlightProgress.snapTo(0f)
            // 呼吸第 1 次
            highlightProgress.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
            highlightProgress.animateTo(0.15f, tween(400, easing = FastOutSlowInEasing))
            // 呼吸第 2 次
            highlightProgress.animateTo(1f, tween(400, easing = FastOutSlowInEasing))
            // 平滑淡出至完全归零
            highlightProgress.animateTo(0f, tween(600, easing = FastOutSlowInEasing))
            onHighlightFinished()
        } else {
            highlightProgress.snapTo(0f)
        }
    }

    // 选择态由外层传入的 isHighlighted 之外的状态决定，这里只处理呼吸高亮；
    // 选中底色由 SubtitleSlot 同一套令牌表达，保证两类规则观感一致
    val selectProgress by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = tween(200),
        label = "selectProgress"
    )
    val effectiveProgress = if (isSelected) selectProgress else highlightProgress.value

    val normalBg = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.65f)
    val selectedBg = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
    val primaryColor = MaterialTheme.colorScheme.primary

    val backgroundColor = if (effectiveProgress > 0f) {
        lerp(normalBg, selectedBg, effectiveProgress)
    } else {
        normalBg
    }

    val borderColor = if (effectiveProgress > 0f) {
        lerp(Color.Transparent, primaryColor, effectiveProgress)
    } else {
        Color.Transparent
    }

    val borderWidth = (2 * effectiveProgress).dp

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = backgroundColor,
        border = BorderStroke(borderWidth, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon()

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    subtitle()
                }
            }

            Spacer(Modifier.width(12.dp))

            if (isSelectionMode) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .then(dragHandleModifier),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_menu),
                        contentDescription = "拖动排序",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
            } else {
                PremiumSwitch(checked = isEnabled, onCheckedChange = { onToggle() })
            }
        }
    }
}

/** 副标题左侧的类型标签（匹配方式 / 分享接口），两类规则共用同一视觉 */
@Composable
private fun RuleTypeTag(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
        )
    }
}

/** 副标题右侧的主题文本（模式 / 目标应用） */
@Composable
private fun RuleSubtitleText(text: String, isEnabled: Boolean, modifier: Modifier = Modifier, color: Color? = null) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium.copy(
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.2.sp
        ),
        color = color ?: if (isEnabled) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

@Composable
fun EmptyRulesView(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_task_list),
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "暂无规则",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Text(
            "点击右下角按钮添加 URL 分发规则",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
        )
    }
}

@Composable
fun EmptySearchView(
    query: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_search),
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "未找到相关规则",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "未搜索到包含 “$query” 的规则名称、特征或包名",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
    }
}

@Composable
fun SelectionBottomBar(
    onToggleSelectAll: () -> Unit,
    allSelected: Boolean,
    onToggleEnabled: () -> Unit,
    allEnabled: Boolean,
    onShare: () -> Unit,
    onSaveToFile: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    anySelected: Boolean,
    showDelete: Boolean = true
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 4.dp,
        shadowElevation = 8.dp
    ) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            Row(
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 12.dp)
                    .fillMaxWidth()
                    .navigationBarsPadding(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onToggleSelectAll) {
                        Icon(
                            if (allSelected) ImageVector.vectorResource(id = R.drawable.ic_iconoir_check_square) else ImageVector.vectorResource(id = R.drawable.ic_iconoir_square),
                            contentDescription = if (allSelected) "取消全选" else "全选",
                            tint = if (allSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // 竖分割线：分隔「全选」与「批量开关」
                    Spacer(Modifier.width(8.dp))
                    VerticalDivider(modifier = Modifier.height(24.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(Modifier.width(8.dp))

                    // 批量启用/停用开关（返回键即可退出批量模式，故底栏不再放关闭按钮）
                    IconButton(onClick = onToggleEnabled, enabled = anySelected) {
                        Icon(
                            ImageVector.vectorResource(id = if (allEnabled) R.drawable.ic_iconoir_toggle_on else R.drawable.ic_iconoir_toggle_off),
                            contentDescription = if (allEnabled) "批量停用选中" else "批量启用选中",
                            tint = if (allEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                IconButton(onClick = onShare, enabled = anySelected) {
                    Icon(ImageVector.vectorResource(id = R.drawable.ic_iconoir_share_ios), contentDescription = "分享选中的规则")
                }
                IconButton(onClick = onSaveToFile, enabled = anySelected) {
                    Icon(ImageVector.vectorResource(id = R.drawable.ic_iconoir_download), contentDescription = "导出为文件")
                }
                IconButton(onClick = onDuplicate, enabled = anySelected) {
                    Icon(ImageVector.vectorResource(id = R.drawable.ic_iconoir_copy), contentDescription = "批量复刻")
                }
                if (showDelete) {
                    IconButton(onClick = onDelete, enabled = anySelected) {
                        Icon(ImageVector.vectorResource(id = R.drawable.ic_iconoir_trash), contentDescription = "删除选中", tint = if (anySelected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataManagementSheet(
    onDismiss: () -> Unit,
    onImportClipboard: () -> Unit,
    onImportFile: () -> Unit,
    onManualImport: () -> Unit,
    onExportClipboard: () -> Unit,
    onExportFile: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "数据管理中心",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.5).sp
                    )
                )
                IconButton(onClick = onDismiss) {
                    Icon(ImageVector.vectorResource(id = R.drawable.ic_iconoir_xmark), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // 1. 剪贴板
            Text(
                "剪贴板",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GridActionItem(
                    title = "导入剪贴板",
                    subtitle = "读取已复制内容",
                    icon = ImageVector.vectorResource(id = R.drawable.ic_iconoir_paste_clipboard),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                    onClick = onImportClipboard
                )
                GridActionItem(
                    title = "备份至剪贴板",
                    subtitle = "导出为文本格式",
                    icon = ImageVector.vectorResource(id = R.drawable.ic_iconoir_copy),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                    onClick = onExportClipboard
                )
            }

            Spacer(Modifier.height(16.dp))

            // 2. 本地文件
            Text(
                "本地文件",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GridActionItem(
                    title = "恢复本地备份",
                    subtitle = "读取 .json 存档",
                    icon = ImageVector.vectorResource(id = R.drawable.ic_iconoir_import),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                    onClick = onImportFile
                )
                GridActionItem(
                    title = "创建本地备份",
                    subtitle = "将规则保存为.json",
                    icon = ImageVector.vectorResource(id = R.drawable.ic_iconoir_download),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                    onClick = onExportFile
                )
            }

            Spacer(Modifier.height(16.dp))

            // 3. 智能辅助
            Text(
                "智能辅助",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )
            WideActionItem(
                title = "手动输入识别",
                subtitle = "手动输入并解析规则片段",
                icon = ImageVector.vectorResource(id = R.drawable.ic_iconoir_edit_pencil),
                color = MaterialTheme.colorScheme.primary,
                onClick = onManualImport
            )
        }
    }
}

@Composable
fun GridActionItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                color = color.copy(alpha = 0.12f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.size(32.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun WideActionItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = color.copy(alpha = 0.12f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.size(32.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun RuleSelectionItem(
    rule: DispatchRule,
    isSelected: Boolean,
    isDuplicate: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) 
                else Color.Transparent,
        border = BorderStroke(
            1.dp, 
            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) 
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier.alpha(if (isDuplicate && !isSelected) 0.6f else 1f)
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RuleIcon(
                isReDispatch = rule.resolveStrategy == ResolutionStrategy.RE_DISPATCH,
                iconPath = rule.iconPath,
                targetPackage = rule.targetPackage,
                size = 36.dp,
                shape = RoundedCornerShape(8.dp)
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        rule.name, 
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isDuplicate) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                "已存在", 
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = rule.matchType.label,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                    
                    Text(
                        text = rule.pattern, 
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/**
 * 图片规则的勾选条目：与 [RuleSelectionItem] 同一套视觉，
 * 差异只在副标题——图片规则没有匹配式，展示的是它的分享入口。
 */
@Composable
private fun ImageRuleSelectionItem(
    rule: com.moting.linkgo.model.ImageRule,
    isSelected: Boolean,
    isDuplicate: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else Color.Transparent,
        border = BorderStroke(
            1.dp,
            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier.alpha(if (isDuplicate && !isSelected) 0.6f else 1f)
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RuleIcon(
                iconPath = rule.iconPath,
                targetPackage = rule.targetPackage,
                size = 36.dp,
                shape = RoundedCornerShape(8.dp)
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        rule.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isDuplicate) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                "已存在",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "分享入口",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }

                    Text(
                        text = rule.targetPackage.ifBlank { "未设置目标应用" },
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualImportDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "手动输入导入",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
            ) {
                NativeOutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    hint = "规则 JSON…",
                    singleLine = false,
                    maxLines = Int.MAX_VALUE
                )
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = {
                        val clipText = clipboardManager.getText()?.text
                        if (!clipText.isNullOrBlank()) {
                            text = clipText
                        }
                    }
                ) {
                    Text("粘贴")
                }
                
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
                
                Spacer(Modifier.width(8.dp))
                
                Button(
                    onClick = { onConfirm(text) },
                    enabled = text.isNotBlank(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("开始识别")
                }
            }
        },
        shape = RoundedCornerShape(28.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesSelectionDialog(
    title: String,
    confirmTitle: String,
    pendingRules: List<DispatchRule>,
    pendingExtractPatterns: List<ExtractPattern> = emptyList(),
    pendingImageRules: List<com.moting.linkgo.model.ImageRule> = emptyList(),
    currentRules: List<DispatchRule>,
    currentExtractPatterns: List<ExtractPattern> = emptyList(),
    currentImageRules: List<com.moting.linkgo.model.ImageRule> = emptyList(),
    showDuplicateWarning: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    val reDispatchList = remember(pendingRules) { 
        pendingRules.filter { it.resolveStrategy == ResolutionStrategy.RE_DISPATCH } 
    }
    val standardList = remember(pendingRules) { 
        pendingRules.filter { it.resolveStrategy != ResolutionStrategy.RE_DISPATCH } 
    }

    // 图片规则的重复判定：它没有 pattern，只有"名称 + 分享入口"能用来比对
    fun isImageDuplicate(rule: com.moting.linkgo.model.ImageRule): Boolean =
        currentImageRules.any { it.name == rule.name && it.targetPackage == rule.targetPackage }

    var selectedIds by remember { 
        mutableStateOf(
            (pendingRules.filter { rule ->
                val isDuplicate = currentRules.any { it.name == rule.name && it.pattern == rule.pattern }
                !isDuplicate
            }.map { it.id } +
            pendingExtractPatterns.filter { pattern ->
                val isDuplicate = currentExtractPatterns.any { it.name == pattern.name && it.pattern == pattern.pattern }
                !isDuplicate
            }.map { it.id } +
            pendingImageRules.filter { !isImageDuplicate(it) }.map { it.id }).toSet()
        ) 
    }

    // 各板块折叠状态
    var extractCollapsed by remember { mutableStateOf(false) }
    var reDispatchCollapsed by remember { mutableStateOf(false) }
    var standardCollapsed by remember { mutableStateOf(false) }
    var imageCollapsed by remember { mutableStateOf(false) }

    val allIds = pendingRules.map { it.id } +
        pendingExtractPatterns.map { it.id } +
        pendingImageRules.map { it.id }
    val totalCount = allIds.size

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Text(
                title, 
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
            ) 
        },
        text = {
            Column {
                Text(
                    "请选择要操作的内容 (共 ${totalCount} 项)：",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectedIds = if (selectedIds.size == totalCount) emptySet() else allIds.toSet()
                        }
                        .padding(vertical = 12.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val allSelected = selectedIds.size == totalCount
                    Icon(
                        imageVector = if (allSelected) ImageVector.vectorResource(id = R.drawable.ic_iconoir_check_square) else ImageVector.vectorResource(id = R.drawable.ic_iconoir_square),
                        contentDescription = null,
                        tint = if (allSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "全选 (${selectedIds.size}/$totalCount)", 
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (allSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp), 
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 链接提取板块（第一位）
                    if (pendingExtractPatterns.isNotEmpty()) {
                        val allInGroupSelected = pendingExtractPatterns.all { selectedIds.contains(it.id) }
                        item {
                            CollapsibleSectionHeader(
                                title = "链接提取 (${pendingExtractPatterns.size})",
                                collapsed = extractCollapsed,
                                showCheckbox = true,
                                isChecked = allInGroupSelected,
                                onToggleCollapse = { extractCollapsed = !extractCollapsed },
                                onToggleSelect = {
                                    val groupIds = pendingExtractPatterns.map { it.id }.toSet()
                                    selectedIds = if (allInGroupSelected) selectedIds - groupIds 
                                                 else selectedIds + groupIds
                                }
                            )
                        }
                        if (!extractCollapsed) {
                            items(pendingExtractPatterns, key = { it.id }) { pattern ->
                                ExtractPatternSelectionItem(
                                    pattern = pattern,
                                    isSelected = selectedIds.contains(pattern.id),
                                    isDuplicate = currentExtractPatterns.any { it.name == pattern.name && it.pattern == pattern.pattern && showDuplicateWarning },
                                    onToggle = {
                                        selectedIds = if (selectedIds.contains(pattern.id)) selectedIds - pattern.id 
                                                     else selectedIds + pattern.id
                                    }
                                )
                            }
                        }
                    }

                    // 分发规则板块
                    if (reDispatchList.isNotEmpty()) {
                        val allInGroupSelected = reDispatchList.all { selectedIds.contains(it.id) }
                        item {
                            CollapsibleSectionHeader(
                                title = "分发规则 (${reDispatchList.size})",
                                collapsed = reDispatchCollapsed,
                                showCheckbox = true,
                                isChecked = allInGroupSelected,
                                onToggleCollapse = { reDispatchCollapsed = !reDispatchCollapsed },
                                onToggleSelect = {
                                    val groupIds = reDispatchList.map { it.id }.toSet()
                                    selectedIds = if (allInGroupSelected) selectedIds - groupIds 
                                                 else selectedIds + groupIds
                                }
                            )
                        }
                        if (!reDispatchCollapsed) {
                            items(reDispatchList, key = { it.id }) { rule ->
                                RuleSelectionItem(
                                    rule = rule,
                                    isSelected = selectedIds.contains(rule.id),
                                    isDuplicate = currentRules.any { it.name == rule.name && it.pattern == rule.pattern && showDuplicateWarning },
                                    onToggle = {
                                        selectedIds = if (selectedIds.contains(rule.id)) selectedIds - rule.id 
                                                     else selectedIds + rule.id
                                    }
                                )
                            }
                        }
                    }

                    // 跳转规则板块
                    if (standardList.isNotEmpty()) {
                        val allInGroupSelected = standardList.all { selectedIds.contains(it.id) }
                        item {
                            CollapsibleSectionHeader(
                                title = "跳转规则 (${standardList.size})",
                                collapsed = standardCollapsed,
                                showCheckbox = true,
                                isChecked = allInGroupSelected,
                                onToggleCollapse = { standardCollapsed = !standardCollapsed },
                                onToggleSelect = {
                                    val groupIds = standardList.map { it.id }.toSet()
                                    selectedIds = if (allInGroupSelected) selectedIds - groupIds 
                                                 else selectedIds + groupIds
                                }
                            )
                        }
                        if (!standardCollapsed) {
                            items(standardList, key = { it.id }) { rule ->
                                RuleSelectionItem(
                                    rule = rule,
                                    isSelected = selectedIds.contains(rule.id),
                                    isDuplicate = currentRules.any { it.name == rule.name && it.pattern == rule.pattern && showDuplicateWarning },
                                    onToggle = {
                                        selectedIds = if (selectedIds.contains(rule.id)) selectedIds - rule.id 
                                                     else selectedIds + rule.id
                                    }
                                )
                            }
                        }
                    }

                    // 图片规则板块（与分发/跳转规则平级）
                    if (pendingImageRules.isNotEmpty()) {
                        val allInGroupSelected = pendingImageRules.all { selectedIds.contains(it.id) }
                        item {
                            CollapsibleSectionHeader(
                                title = "图片规则 (${pendingImageRules.size})",
                                collapsed = imageCollapsed,
                                showCheckbox = true,
                                isChecked = allInGroupSelected,
                                onToggleCollapse = { imageCollapsed = !imageCollapsed },
                                onToggleSelect = {
                                    val groupIds = pendingImageRules.map { it.id }.toSet()
                                    selectedIds = if (allInGroupSelected) selectedIds - groupIds
                                                 else selectedIds + groupIds
                                }
                            )
                        }
                        if (!imageCollapsed) {
                            items(pendingImageRules, key = { it.id }) { rule ->
                                ImageRuleSelectionItem(
                                    rule = rule,
                                    isSelected = selectedIds.contains(rule.id),
                                    isDuplicate = isImageDuplicate(rule) && showDuplicateWarning,
                                    onToggle = {
                                        selectedIds = if (selectedIds.contains(rule.id)) selectedIds - rule.id
                                                     else selectedIds + rule.id
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selectedIds) },
                enabled = selectedIds.isNotEmpty(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("$confirmTitle (${selectedIds.size})")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
        shape = RoundedCornerShape(28.dp)
    )
}

@Composable
private fun ExtractPatternSelectionItem(
    pattern: ExtractPattern,
    isSelected: Boolean,
    isDuplicate: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) 
                else Color.Transparent,
        border = BorderStroke(
            1.dp, 
            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) 
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier.alpha(if (isDuplicate && !isSelected) 0.6f else 1f)
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (pattern.isEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_code),
                    contentDescription = null,
                    tint = if (pattern.isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        pattern.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isDuplicate) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                "已存在",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
                if (pattern.pattern.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        pattern.pattern,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
