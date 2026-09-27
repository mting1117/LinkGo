package com.moting.linkgo.ui.rules

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moting.linkgo.R
import com.moting.linkgo.model.ActivityInfo
import com.moting.linkgo.model.AppInfo
import com.moting.linkgo.ui.components.AppFloatingActionButton
import com.moting.linkgo.ui.components.AsyncAppIcon
import com.moting.linkgo.ui.components.CollapsingTopBarScaffold
import com.moting.linkgo.ui.components.NativeOutlinedTextField
import com.moting.linkgo.ui.components.PremiumSegmentedRow
import com.moting.linkgo.viewmodel.AppFilter
import com.moting.linkgo.viewmodel.PickerViewModel

/**
 * 应用选择器全屏页面 (Floatwidget 规范)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerScreen(
    onAppSelected: (String, String) -> Unit,
    onAppsSelected: (List<Pair<String, String>>) -> Unit = { _ -> },
    onNavigateToActivityPicker: (String) -> Unit,
    onNavigateBack: () -> Unit,
    onlyApp: Boolean = false,
    multiSelect: Boolean = false,
    preselectedPackages: Set<String> = emptySet(),
    viewModel: PickerViewModel = viewModel()
) {
    val context = LocalContext.current
    val appList by viewModel.appList.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val currentFilter by viewModel.currentFilter.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    // 多选模式：已添加的接管应用默认勾选（remember 保持用户本次操作，返回时重置）
    var selectedPkgs by remember { mutableStateOf(preselectedPackages) }

    LaunchedEffect(Unit) {
        viewModel.loadAllApps(context)
    }

    val filteredApps = remember(appList, searchQuery, currentFilter) {
        appList.filter { app ->
            val matchSearch = searchQuery.isBlank() || 
                app.label.contains(searchQuery, ignoreCase = true) || 
                app.packageName.contains(searchQuery, ignoreCase = true)
            
            val matchFilter = when (currentFilter) {
                AppFilter.ALL -> true
                AppFilter.USER -> !app.isSystemApp
                AppFilter.SYSTEM -> app.isSystemApp
            }
            
            matchSearch && matchFilter
        }
    }

    val allCount = appList.size
    val userCount = remember(appList) { appList.count { !it.isSystemApp } }
    val systemCount = remember(appList) { appList.count { it.isSystemApp } }

    val collator = remember { java.text.Collator.getInstance(java.util.Locale.CHINA) }

    // 多选模式：按初始已添加集合置顶，置顶区与未选区内部分别按拼音 A-Z 严格排序（勾选/取消不触发重排，避免跳变）
    val orderedApps = remember(filteredApps, preselectedPackages) {
        if (multiSelect) {
            filteredApps.sortedWith(
                compareBy<AppInfo> { it.packageName !in preselectedPackages }
                    .thenBy { it.isSystemApp }
                    .thenComparator { a, b -> collator.compare(a.label, b.label) }
            )
        } else {
            filteredApps.sortedWith(
                compareBy<AppInfo> { it.isSystemApp }
                    .thenComparator { a, b -> collator.compare(a.label, b.label) }
            )
        }
    }

    CollapsingTopBarScaffold(
        title = "选择应用",
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    ImageVector.vectorResource(id = R.drawable.ic_iconoir_arrow_left),
                    contentDescription = "返回"
                )
            }
        },
        actions = {
            // 多选模式：右上角圆形数量徽标（加粗数字）
            if (multiSelect && selectedPkgs.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .size(28.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${selectedPkgs.size}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    ) { padding, nestedScrollConnection ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(nestedScrollConnection)
        ) {
            // 搜索框
            NativeOutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                hint = "搜索应用名称或包名...",
                cornerRadius = 16.dp,
                singleLine = true
            )
            // 现代极简胶囊单选筛选器
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                PremiumSegmentedRow(
                    options = AppFilter.values().toList(),
                    selectedOption = currentFilter,
                    onOptionSelected = { viewModel.setFilter(it) },
                    labelProvider = { filter ->
                        when (filter) {
                            AppFilter.ALL -> "全部 ($allCount)"
                            AppFilter.USER -> "用户 ($userCount)"
                            AppFilter.SYSTEM -> "系统 ($systemCount)"
                        }
                    }
                )
            }

            if (isLoading && appList.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (filteredApps.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally, 
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Icon(
                            ImageVector.vectorResource(id = R.drawable.ic_iconoir_search),
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "未发现应用",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "请检查是否已授权 LinkGo “读取应用列表”权限（包可见性权限）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                    items(orderedApps, key = { it.packageName }) { app ->
                        val checked = app.packageName in selectedPkgs
                        Surface(
                            onClick = {
                                if (multiSelect) {
                                    selectedPkgs = if (checked) selectedPkgs - app.packageName else selectedPkgs + app.packageName
                                } else {
                                    onAppSelected(app.packageName, app.label)
                                }
                            },
                            shape = RoundedCornerShape(16.dp),
                            color = if (checked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 图标
                                AsyncAppIcon(
                                    packageName = app.packageName,
                                    size = 44.dp,
                                    shape = RoundedCornerShape(12.dp)
                                )

                                Spacer(Modifier.width(14.dp))

                                // 应用信息
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = app.label,
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = app.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Spacer(Modifier.width(8.dp))

                                if (multiSelect) {
                                    // 多选模式：勾选框（选中高亮），右端不再显示活动入口
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = { on ->
                                            selectedPkgs = if (on) selectedPkgs + app.packageName else selectedPkgs - app.packageName
                                        }
                                    )
                                } else if (!onlyApp) {
                                    // 活动选择入口（仅非 onlyApp 模式显示：只需选应用时隐藏）
                                    IconButton(
                                        onClick = { onNavigateToActivityPicker(app.packageName) },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            ImageVector.vectorResource(id = R.drawable.ic_iconoir_app_window),
                                            contentDescription = "选择活动",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                    // 多选模式：右下角悬浮确认按钮（规则页同款，双对号）
                    // 无论当前搜索过滤展示哪些条目，均基于全量 selectedPkgs 保存，搜索绝不丢失勾选，且支持清空保存
                    if (multiSelect) {
                        AppFloatingActionButton(
                            onClick = {
                                val appMap = appList.associateBy { it.packageName }
                                val resultList = selectedPkgs.map { pkg ->
                                    val label = appMap[pkg]?.label ?: pkg
                                    pkg to label
                                }
                                onAppsSelected(resultList)
                            },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 16.dp, bottom = com.moting.linkgo.BottomBarMetrics.BarHeight + 16.dp),
                            iconResId = R.drawable.ic_iconoir_double_check,
                            contentDescription = if (selectedPkgs.isNotEmpty()) "确认添加（${selectedPkgs.size}）" else "清空选择"
                        )
                    }
                }
            }
        }
    }
}

/**
 * 活动 (Activity) 选择器全屏页面 (Floatwidget 规范)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityPickerScreen(
    packageName: String,
    onActivitySelected: (String) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: PickerViewModel = viewModel()
) {
    val context = LocalContext.current
    val activityList by viewModel.activityList.collectAsState()
    val isLoading by viewModel.isActivityLoading.collectAsState()
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(packageName) {
        viewModel.loadActivities(context, packageName)
    }

    val filteredActivities = remember(activityList, searchQuery) {
        activityList.filter { act ->
            searchQuery.isBlank() || 
                act.label.contains(searchQuery, ignoreCase = true) || 
                act.name.contains(searchQuery, ignoreCase = true)
        }
    }

    CollapsingTopBarScaffold(
        title = "选择活动 (Activity)",
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    ImageVector.vectorResource(id = R.drawable.ic_iconoir_arrow_left),
                    contentDescription = "返回"
                )
            }
        }
    ) { padding, nestedScrollConnection ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(nestedScrollConnection)
        ) {
            // 搜索框
            NativeOutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                hint = "搜索活动名称或类名...",
                cornerRadius = 16.dp,
                singleLine = true
            )

            if (isLoading && activityList.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (filteredActivities.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally, 
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Icon(
                            ImageVector.vectorResource(id = R.drawable.ic_iconoir_search),
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "未发现活动",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredActivities, key = { it.name }) { act ->
                        Surface(
                            onClick = { onActivitySelected(act.name) },
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = act.label.ifBlank { act.name.substringAfterLast('.') },
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (act.isLauncher) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.primary,
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                "入口",
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    if (act.isExported) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                "已导出",
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = act.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
