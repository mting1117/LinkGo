package com.moting.linkgo.ui.rules

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moting.linkgo.R
import com.moting.linkgo.model.ExtractPattern
import com.moting.linkgo.ui.components.AppFloatingActionButton
import com.moting.linkgo.ui.components.ConfirmActionSheet
import com.moting.linkgo.ui.components.MainPageScaffold
import com.moting.linkgo.ui.components.NativeOutlinedTextField
import com.moting.linkgo.ui.components.PremiumSwitch
import com.moting.linkgo.ui.components.SettingsSection
import com.moting.linkgo.util.ExtractionHit
import com.moting.linkgo.util.UrlUtils
import com.moting.linkgo.util.WindowRouter
import com.moting.linkgo.viewmodel.RulesViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ExtractionSettingsScreen(
    viewModel: RulesViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    val extractionPatterns by viewModel.extractionPatterns.collectAsState()
    val isSelectionMode by viewModel.isExtractionSelectionMode.collectAsState()
    val selectedExtractionPatternIds by viewModel.selectedExtractionPatternIds.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deleteSingleTarget by remember { mutableStateOf<ExtractPattern?>(null) }
    var showTestSheet by remember { mutableStateOf(false) }

    var searchQuery by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    // SAF 导出到文件（复用 linkgo_extract_patterns 包络，可在数据中心导入）
    val createDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: android.net.Uri? ->
        uri?.let {
            val json = viewModel.exportSelectedExtractionPatternsToJson()
            try {
                context.contentResolver.openOutputStream(it)?.use { os -> os.write(json.toByteArray()) }
                Toast.makeText(context, "导出成功", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 按搜索词过滤（内置与自定义均参与过滤）
    val filteredPatterns = remember(extractionPatterns, searchQuery) {
        if (searchQuery.isBlank()) extractionPatterns
        else extractionPatterns.filter { p ->
            p.name.contains(searchQuery, ignoreCase = true) ||
                p.pattern.contains(searchQuery, ignoreCase = true)
        }
    }

    BackHandler(enabled = isSelectionMode) {
        viewModel.exitExtractionSelectionMode()
    }

    var frozenSelectedCount by remember { mutableIntStateOf(0) }
    var frozenIsAllSelected by remember { mutableStateOf(false) }
    var frozenIsAnySelected by remember { mutableStateOf(false) }
    var frozenAllEnabled by remember { mutableStateOf(true) }

    if (isSelectionMode) {
        val selectableCount = extractionPatterns.size
        frozenSelectedCount = selectedExtractionPatternIds.size
        frozenIsAllSelected = selectedExtractionPatternIds.size == selectableCount && selectableCount > 0
        frozenIsAnySelected = selectedExtractionPatternIds.isNotEmpty()
        frozenAllEnabled = selectedExtractionPatternIds.isNotEmpty() &&
            extractionPatterns.filter { it.id in selectedExtractionPatternIds }.all { it.isEnabled }
    }

    val isSearchEmpty = !isSelectionMode && searchQuery.isNotBlank() && filteredPatterns.isEmpty()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
    MainPageScaffold(
        titleContent = {
            AnimatedContent(
                targetState = if (isSelectionMode) "已选 $frozenSelectedCount" else "链接提取",
                transitionSpec = {
                    (fadeIn(animationSpec = tween(200)) togetherWith fadeOut(animationSpec = tween(150)))
                        .using(SizeTransform(clip = false))
                },
                label = "extractionAppBarTitle"
            ) { titleText ->
                Text(
                    text = titleText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        navigationIcon = {
            IconButton(
                onClick = {
                    if (isSelectionMode) viewModel.exitExtractionSelectionMode()
                    else onNavigateBack()
                }
            ) {
                Icon(
                    ImageVector.vectorResource(id = if (isSelectionMode) R.drawable.ic_iconoir_xmark else R.drawable.ic_iconoir_arrow_left),
                    contentDescription = if (isSelectionMode) "退出批量模式" else "返回"
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
                label = "extractionAppBarActions"
            ) { inSelection ->
                if (inSelection) {
                    // 选择态：右上角保留「一键整理」占位对齐规则页，但对提取正则无实际整理逻辑，故不显示
                    // 保持空以实现标题/底栏对齐，避免误操作
                    Spacer(modifier = Modifier.width(0.dp))
                } else {
                    Row {
                        IconButton(onClick = { showTestSheet = true }) {
                            Icon(
                                ImageVector.vectorResource(id = R.drawable.ic_iconoir_rocket),
                                contentDescription = "测试链接提取效果",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { showResetDialog = true }) {
                            Icon(
                                ImageVector.vectorResource(id = R.drawable.ic_iconoir_refresh),
                                contentDescription = "恢复内置默认",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
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
                    onToggleSelectAll = { if (frozenIsAllSelected) viewModel.clearExtractionPatternSelection() else viewModel.selectAllExtractionPatterns() },
                    allSelected = frozenIsAllSelected,
                    onToggleEnabled = { viewModel.toggleAllExtractionPatterns(!frozenAllEnabled) },
                    allEnabled = frozenAllEnabled,
                    onShare = {
                        val json = viewModel.exportSelectedExtractionPatternsToJson()
                        val sendIntent = android.content.Intent().apply {
                            action = android.content.Intent.ACTION_SEND
                            putExtra(android.content.Intent.EXTRA_TEXT, json)
                            type = "text/plain"
                        }
                        context.startActivity(android.content.Intent.createChooser(sendIntent, "分享选中的提取规则"))
                    },
                    onSaveToFile = {
                        createDocumentLauncher.launch("linkgo_extract_patterns_${System.currentTimeMillis()}.json")
                    },
                    onDuplicate = { viewModel.duplicateSelectedExtractionPatterns() },
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
                .imePadding()
        ) {
            // 搜索栏（选择态淡化禁用，对齐规则页）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (isSelectionMode) 0.38f else 1f)
                    .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 8.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier
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
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = "搜索提取规则名称或正则...",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = {
                                    if (!isSelectionMode) searchQuery = it
                                },
                                enabled = !isSelectionMode,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequester),
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Medium
                                ),
                                singleLine = true,
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(
                                    onSearch = { keyboardController?.hide() }
                                )
                            )
                        }
                        if (searchQuery.isNotEmpty() && !isSelectionMode) {
                            IconButton(
                                onClick = {
                                    searchQuery = ""
                                    focusRequester.requestFocus()
                                }
                            ) {
                                Icon(
                                    ImageVector.vectorResource(id = R.drawable.ic_iconoir_xmark),
                                    contentDescription = "清空搜索",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(top = 2.dp, bottom = 100.dp)
            ) {
                if (extractionPatterns.isEmpty() && !isSelectionMode) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "暂无提取规则，点击右下角 + 添加",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (isSearchEmpty) {
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        ) {
                            Text(
                                "未找到匹配的提取规则",
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                items(filteredPatterns, key = { it.id }) { pattern ->
                    ExtractPatternItem(
                        pattern = pattern,
                        selectionMode = isSelectionMode,
                        isSelected = pattern.id in selectedExtractionPatternIds,
                        selectable = true,
                        onToggle = { viewModel.toggleExtractionPattern(pattern.id) },
                        onDelete = { deleteSingleTarget = pattern },
                        onUpdate = { viewModel.updateExtractionPattern(it) },
                        onLongPress = { viewModel.enterExtractionSelectionMode(pattern.id) },
                        onSelect = { viewModel.toggleExtractionPatternSelection(pattern.id) }
                    )
                }
            }
        }
    }

    // 非选择态右下角悬浮「添加提取规则」FAB（偏移与规则页全局 FAB 保持一致：end 16 / bottom 16）
    AnimatedVisibility(
        visible = !isSelectionMode,
        enter = fadeIn(animationSpec = tween(250)) + androidx.compose.animation.scaleIn(initialScale = 0.92f, animationSpec = tween(250)),
        exit = fadeOut(animationSpec = tween(250)) + androidx.compose.animation.scaleOut(targetScale = 0.92f, animationSpec = tween(250)),
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 16.dp, bottom = 16.dp)
    ) {
        AppFloatingActionButton(
            onClick = { showAddDialog = true },
            contentDescription = "添加提取规则"
        )
    }
    } // Box

    if (showAddDialog) {
        var newName by remember { mutableStateOf("") }
        var newPattern by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("添加提取规则", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.imePadding()
                ) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("名称") },
                        placeholder = { Text("如：磁力链接") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newPattern,
                        onValueChange = { newPattern = it },
                        label = { Text("正则表达式") },
                        placeholder = { Text("输入正则...") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newName.isNotBlank() && newPattern.isNotBlank()) {
                            viewModel.addExtractionPattern(newName, newPattern)
                            showAddDialog = false
                        }
                    }
                ) {
                    Text("添加")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("取消")
                }
            },
            shape = RoundedCornerShape(28.dp)
        )
    }

    if (showDeleteDialog) {
        ConfirmActionSheet(
            title = "删除选中的提取规则",
            message = "确认删除选中的 ${frozenSelectedCount} 条提取规则吗？此操作不可撤销。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                viewModel.deleteSelectedExtractionPatterns()
                showDeleteDialog = false
            },
            onDismiss = { showDeleteDialog = false }
        )
    }

    if (showResetDialog) {
        ConfirmActionSheet(
            title = "恢复默认规则",
            message = "确认恢复出厂默认的链接提取规则吗？\n将重置或补全出厂默认规则，您的自定义规则将予以保留。",
            confirmLabel = "确认恢复",
            destructive = false,
            onConfirm = {
                viewModel.resetBuiltinPatterns()
                Toast.makeText(context, "已恢复默认提取规则", Toast.LENGTH_SHORT).show()
                showResetDialog = false
            },
            onDismiss = { showResetDialog = false }
        )
    }

    deleteSingleTarget?.let { pattern ->
        ConfirmActionSheet(
            title = "删除提取规则",
            message = "确定要删除提取规则「${pattern.name}」吗？此操作无法撤销。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                viewModel.deleteExtractionPattern(pattern.id)
                deleteSingleTarget = null
            },
            onDismiss = { deleteSingleTarget = null }
        )
    }

    if (showTestSheet) {
        TestExtractionSheet(
            patterns = extractionPatterns,
            onDismiss = { showTestSheet = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TestExtractionSheet(
    patterns: List<ExtractPattern>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    var testInput by remember { mutableStateOf("") }

    val extractedUrlsData by remember(patterns, testInput) {
        derivedStateOf {
            if (testInput.isBlank()) emptyList<ExtractionHit>()
            else UrlUtils.extractAllUrlsWithRules(testInput, patterns)
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "测试链接提取效果",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.5).sp
                    )
                )
                IconButton(onClick = onDismiss) {
                    Icon(ImageVector.vectorResource(id = R.drawable.ic_iconoir_xmark), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            NativeOutlinedTextField(
                value = testInput,
                onValueChange = { testInput = it },
                modifier = Modifier.fillMaxWidth().height(110.dp),
                hint = "粘贴含链接的文本进行测试...",
                singleLine = false,
                maxLines = 4,
                cornerRadius = 16.dp
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { testInput = "" },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        ImageVector.vectorResource(id = R.drawable.ic_iconoir_trash),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("清空")
                }

                Button(
                    onClick = {
                        clipboardManager.getText()?.text?.let { testInput = it }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        ImageVector.vectorResource(id = R.drawable.ic_iconoir_paste_clipboard),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("粘贴最新")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (testInput.isNotBlank()) {
                if (extractedUrlsData.isEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "未匹配到任何结果",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                } else {
                    Text(
                        "提取结果 (${extractedUrlsData.size})",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        extractedUrlsData.forEach { hit ->
                            TestExtractionItem(
                                url = hit.url,
                                ruleName = hit.ruleName,
                                onCopy = {
                                    clipboardManager.setText(AnnotatedString(hit.url))
                                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                                },
                                onTest = {
                                    scope.launch {
                                        WindowRouter.handleUrl(context, hit.url, WindowRouter.DispatchSource.DIRECT)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExtractPatternItem(
    pattern: ExtractPattern,
    selectionMode: Boolean,
    isSelected: Boolean,
    selectable: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onUpdate: (ExtractPattern) -> Unit,
    onLongPress: () -> Unit,
    onSelect: () -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }
    var editedName by remember { mutableStateOf(pattern.name) }
    var editedPattern by remember { mutableStateOf(pattern.pattern) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = when {
            isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            else -> MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f)
        },
        border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    if (selectionMode) {
                        if (selectable) onSelect()
                    } else {
                        isExpanded = !isExpanded
                    }
                },
                onLongClick = {
                    if (!selectionMode && selectable) onLongPress()
                }
            )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (pattern.isEnabled && !isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            else if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
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

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = pattern.name,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = if (pattern.isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = pattern.pattern,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = if (!isExpanded) FontWeight.Bold else FontWeight.Normal,
                            letterSpacing = 0.2.sp
                        ),
                        color = if (pattern.isEnabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (!selectionMode) {
                    PremiumSwitch(
                        checked = pattern.isEnabled,
                        onCheckedChange = { onToggle() }
                    )
                }
            }

            AnimatedVisibility(visible = isExpanded && !selectionMode) {
                Column(
                    modifier = Modifier
                        .padding(top = 16.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = editedName,
                        onValueChange = { editedName = it },
                        label = { Text("名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyLarge
                    )

                    OutlinedTextField(
                        value = editedPattern,
                        onValueChange = { editedPattern = it },
                        label = { Text("正则表达式") },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyLarge,
                        singleLine = false,
                        maxLines = 3
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = onDelete,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                        ) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }

                        Button(
                            onClick = {
                                onUpdate(pattern.copy(name = editedName, pattern = editedPattern))
                                isExpanded = false
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("保存")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TestExtractionItem(
    url: String,
    ruleName: String = "",
    onCopy: () -> Unit,
    onTest: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_link),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onCopy) {
                    Icon(
                        imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_copy),
                        contentDescription = "复制",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onTest) {
                    Icon(
                        imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_rocket),
                        contentDescription = "测试跳转",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (ruleName.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "命中规则：$ruleName",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}