package com.moting.linkgo.ui.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.moting.linkgo.LinkGoApp
import com.moting.linkgo.ui.components.ConfirmActionSheet
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moting.linkgo.R
import com.moting.linkgo.applink.AppLinkHookSyncStatus
import com.moting.linkgo.applink.LinkIntentReceiver
import com.moting.linkgo.data.SettingsRepository
import com.moting.linkgo.model.ExemptDomain
import com.moting.linkgo.model.MatchType
import com.moting.linkgo.ui.components.AsyncAppIcon
import com.moting.linkgo.ui.components.CollapsingTopBarScaffold
import com.moting.linkgo.ui.components.NativeOutlinedTextField
import com.moting.linkgo.ui.components.PremiumSegmentedRow
import com.moting.linkgo.ui.components.PremiumSwitch
import com.moting.linkgo.ui.components.SettingItem
import com.moting.linkgo.ui.components.SettingsSection
import com.moting.linkgo.ui.components.SettingsSectionDivider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 应用内链接捕获设置（二级菜单）。
 *
 * - 捕获模式：关闭 / 拦截 / 询问；拦截模式弹「内置打开」提示胶囊；
 * - 接管应用：仅列表内应用打开的链接由 LinkGo 处理（其余应用一律放行）；
 * - 域名放行：已接管应用内命中以下域名的链接视为生态内内容不捕获。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppLinkCaptureSettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToMultiAppPicker: () -> Unit = {},
    pickerSelectedApps: List<String>? = null,
    onAppsPickerConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { SettingsRepository(context) }

    val mode by repository.appLinkCaptureMode.collectAsState(initial = repository.currentAppLinkCaptureMode)
    val lastSubMode by repository.lastAppLinkSubMode.collectAsState(initial = repository.currentLastAppLinkSubMode)
    val isLsposedActive = remember { LinkGoApp.xposedService != null }
    val askAutoFinish by repository.appLinkAskAutoFinish.collectAsState(initial = repository.currentAppLinkAskAutoFinish)
    val ruleOnlyIntercept by repository.appLinkRuleOnlyIntercept.collectAsState(initial = repository.currentAppLinkRuleOnlyIntercept)
    val captureApps by repository.appLinkCaptureApps.collectAsState(initial = repository.currentAppLinkCaptureApps)
    val exemptDomains by repository.appLinkExemptDomains.collectAsState(initial = repository.currentAppLinkExemptDomains)

    // Hook 端同步快照：进入页面主动重发一次配置，并在稍后刷新读取 Hook 端回写的规则条数，
    // 用于让用户确认「自定义规则列表是否已同步到 system_server」
    var hookRulesCount by remember { mutableStateOf<Int?>(null) }
    var hookSyncedAt by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) {
        hookRulesCount = AppLinkHookSyncStatus.rulesCount(context)
        hookSyncedAt = AppLinkHookSyncStatus.syncedAt(context)
        LinkIntentReceiver.syncConfigToHook(context.applicationContext, mode)
        // Hook 端处理广播并回写状态需要一点时间，延时后再读一次
        delay(1500)
        hookRulesCount = AppLinkHookSyncStatus.rulesCount(context)
        hookSyncedAt = AppLinkHookSyncStatus.syncedAt(context)
    }

    // 卡片折叠状态（按需求：默认全部折叠，初始为空集合）
    var expandedPkgs by rememberSaveable { mutableStateOf(emptySet<String>()) }

    // 添加/编辑放行规则半屏弹窗状态
    var showExemptSheet by rememberSaveable { mutableStateOf(false) }
    var editingExemptId by rememberSaveable { mutableStateOf<String?>(null) }
    var sheetSourcePkg by rememberSaveable { mutableStateOf<String?>(null) }
    var sheetMatchType by rememberSaveable { mutableStateOf(MatchType.CONTAINS.name) }
    var sheetPattern by rememberSaveable { mutableStateOf("") }
    var sheetNote by rememberSaveable { mutableStateOf("") }

    // 批量编辑接管应用回传（增量合并算法：保留已有项时间戳与开关，新增项追加在后，排序固定按添加时间）
    LaunchedEffect(pickerSelectedApps) {
        val selected = pickerSelectedApps ?: return@LaunchedEffect
        val selectedSet = selected.toSet()
        val currentMap = captureApps.associateBy { it.packageName }

        // 1. 保留已有且仍勾选的应用
        val retained = captureApps.filter { it.packageName in selectedSet }

        // 2. 新增勾选的应用（赋予最新时间戳排在末尾）
        val existingPkgs = currentMap.keys
        val newPkgs = selected.filter { it !in existingPkgs }
        val now = System.currentTimeMillis()
        val newlyAdded = newPkgs.mapIndexed { index, pkg ->
            com.moting.linkgo.model.CaptureApp(
                packageName = pkg,
                isEnabled = true,
                addedAt = now + index * 10L
            )
        }

        val merged = (retained + newlyAdded).sortedBy { it.addedAt }

        // 3. 计算取消勾选的应用，级联删除放行域名
        val removed = existingPkgs - selectedSet
        android.util.Log.i("LinkGo_AppLink", "[UI-ACTION] 批量更新接管应用: 勾选=${selected.size}个, 新增=${newlyAdded.size}个, 移除=${removed.size}个")
        repository.updateAppLinkCaptureApps(merged)
        if (removed.isNotEmpty()) {
            repository.updateAppLinkExemptDomains(exemptDomains.filter { it.sourcePkg !in removed })
        }
        LinkIntentReceiver.syncConfigToHook(context.applicationContext, mode)
        onAppsPickerConsumed()
    }

    // 恢复默认确认 + 删除接管应用确认
    var showResetConfirm by rememberSaveable { mutableStateOf(false) }
    var deleteConfirmPkg by rememberSaveable { mutableStateOf<String?>(null) }

    fun openAddExemptSheet(sourcePkg: String?) {
        editingExemptId = null
        sheetSourcePkg = sourcePkg
        sheetMatchType = MatchType.CONTAINS.name
        sheetPattern = ""
        sheetNote = ""
        showExemptSheet = true
    }

    fun openEditExemptSheet(item: ExemptDomain) {
        editingExemptId = item.id
        sheetSourcePkg = item.sourcePkg
        sheetMatchType = item.matchType.name
        sheetPattern = item.pattern
        sheetNote = item.note
        showExemptSheet = true
    }

    fun saveExempt(sourcePkg: String, matchType: MatchType, pattern: String, note: String) {
        scope.launch {
            val editingId = editingExemptId
            android.util.Log.i("LinkGo_AppLink", "[UI-ACTION] 保存放行规则: isEdit=${editingId != null}, pkg=$sourcePkg, type=$matchType, pattern=$pattern")
            val newList = if (editingId != null) {
                exemptDomains.map { if (it.id == editingId) it.copy(sourcePkg = sourcePkg, matchType = matchType, pattern = pattern, note = note) else it }
            } else {
                exemptDomains + ExemptDomain(sourcePkg = sourcePkg, matchType = matchType, pattern = pattern, note = note)
            }
            repository.updateAppLinkExemptDomains(newList)
            LinkIntentReceiver.syncConfigToHook(context.applicationContext, mode)
        }
    }

    /** 删除接管应用：同时级联删除其名下所有放行规则。 */
    fun deleteCaptureApp(pkg: String) {
        scope.launch {
            android.util.Log.i("LinkGo_AppLink", "[UI-ACTION] 移除接管应用: pkg=$pkg")
            repository.updateAppLinkCaptureApps(captureApps.filter { it.packageName != pkg })
            repository.updateAppLinkExemptDomains(exemptDomains.filter { it.sourcePkg != pkg })
            LinkIntentReceiver.syncConfigToHook(context.applicationContext, mode)
        }
    }

    CollapsingTopBarScaffold(
        title = "应用内链接捕获",
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        }
    ) { padding, nestedScrollConnection ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(nestedScrollConnection),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── 0. LSPosed 状态感知 ─────────────────────────
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (isLsposedActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = ImageVector.vectorResource(
                                if (isLsposedActive) R.drawable.ic_iconoir_check_circle
                                else R.drawable.ic_iconoir_warning_triangle
                            ),
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = if (isLsposedActive) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = if (isLsposedActive) "LSPosed 模块已就绪" else "LSPosed 模块未生效",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = if (isLsposedActive) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = if (isLsposedActive) "系统级 Hook 运行正常，可接管已选应用内的链接跳转"
                                else "此功能依赖 LSPosed 框架。请在 LSPosed 管理器中启用 LinkGo 模块并勾选需要接管的应用（如微信、QQ），然后重启目标应用。",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isLsposedActive) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f)
                            )
                        }
                    }
                }
            }

            // ── 1. 规则列表同步状态（Hook 端回写）─────────────
            item {
                val syncedAtText = hookSyncedAt?.let {
                    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(it))
                }
                val syncTitle = when {
                    hookSyncedAt == null -> "规则列表尚未同步"
                    else -> "规则列表已同步至系统 Hook"
                }
                val syncDetail = when {
                    hookSyncedAt == null ->
                        "Hook 端未回报。请在 LSPosed 中启用模块并勾选作用域，或检查模块是否需要重载。"
                    else ->
                        "生效规则 ${hookRulesCount ?: 0} 条 · 同步于 $syncedAtText" +
                            if ((hookRulesCount ?: 0) == 0) "（当前没有可用的跳转规则）" else ""
                }
                val syncOk = hookSyncedAt != null
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (syncOk) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = ImageVector.vectorResource(
                                if (syncOk) R.drawable.ic_iconoir_check_circle
                                else R.drawable.ic_iconoir_warning_triangle
                            ),
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = if (syncOk) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = syncTitle,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = if (syncOk) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = syncDetail,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (syncOk) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f)
                            )
                        }
                    }
                }
            }

            // ── 2. 捕获总开关与模式选择 ─────────────────────
            item {
                val isCaptureEnabled = mode > 0
                val activeSubMode = if (mode in 1..2) mode else (if (lastSubMode in 1..2) lastSubMode else 1)
                SettingsSection(topLabel = "功能开关与模式") {
                    SettingItem(
                        headlineText = "开启应用内链接捕获",
                        supportingText = if (isCaptureEnabled) "已开启 · 由 LinkGo 接管已选应用内的链接点击"
                        else "已关闭 · 应用内点击链接照常使用内置浏览器",
                        trailingContent = {
                            PremiumSwitch(
                                checked = isCaptureEnabled,
                                onCheckedChange = { checked ->
                                    val newMode = if (checked) activeSubMode else 0
                                    scope.launch {
                                        if (checked) repository.updateLastAppLinkSubMode(activeSubMode)
                                        repository.updateAppLinkCaptureMode(newMode)
                                        LinkIntentReceiver.syncConfigToHook(context.applicationContext, newMode)
                                    }
                                }
                            )
                        }
                    )

                    AnimatedVisibility(
                        visible = isCaptureEnabled,
                        enter = expandVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                        exit = shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()
                    ) {
                        Column {
                            SettingsSectionDivider()
                            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                PremiumSegmentedRow(
                                    options = listOf(1, 2),
                                    selectedOption = activeSubMode,
                                    onOptionSelected = { selected ->
                                        scope.launch {
                                            repository.updateLastAppLinkSubMode(selected)
                                            repository.updateAppLinkCaptureMode(selected)
                                            LinkIntentReceiver.syncConfigToHook(context.applicationContext, selected)
                                        }
                                    },
                                    labelProvider = { if (it == 1) "直接拦截" else "弹出询问" }
                                )
                            }
                            SettingsSectionDivider()
                            Text(
                                text = if (activeSubMode == 1) {
                                    "直接拦截：在应用内点击链接时，内置浏览器不跳转，直接由 LinkGo 打开，同时弹「内置打开」胶囊方便切换。"
                                } else {
                                    "弹出询问：内置浏览器照常打开，同时屏幕弹出悬浮胶囊，点击胶囊后才由 LinkGo 打开。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                            )
                            // 「仅命中规则时拦截」为两种子模式共用：拦截模式决定是否中止内置浏览器启动，
                            // 询问模式决定是否弹询问胶囊，二者判定都在 Hook 端执行
                            SettingsSectionDivider()
                            SettingItem(
                                headlineText = "仅命中规则时拦截",
                                supportingText = "直接拦截与弹出询问共用：开启后只有命中跳转规则的链接才由 LinkGo 接管；未命中规则（本该走备选浏览器）的链接一律放行，交回应用自己的内置浏览器打开",
                                trailingContent = {
                                    PremiumSwitch(
                                        checked = ruleOnlyIntercept,
                                        onCheckedChange = { checked ->
                                            scope.launch {
                                                repository.updateAppLinkRuleOnlyIntercept(checked)
                                                LinkIntentReceiver.syncConfigToHook(context.applicationContext, mode)
                                            }
                                        }
                                    )
                                }
                            )
                            AnimatedVisibility(
                                visible = activeSubMode == 2,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                Column {
                                    SettingsSectionDivider()
                                    SettingItem(
                                        headlineText = "跳转后关闭内置页面",
                                        supportingText = "点击悬浮胶囊跳转后，自动关闭应用内的内置浏览器，无需按返回键退出",
                                        trailingContent = {
                                            PremiumSwitch(
                                                checked = askAutoFinish,
                                                onCheckedChange = { checked ->
                                                    scope.launch {
                                                        repository.updateAppLinkAskAutoFinish(checked)
                                                    }
                                                }
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 接管规则：说明 + 双按钮（不含分组）
            item {
                SettingsSection(topLabel = "接管规则") {
                    Text(
                        text = "每个分组代表一个被接管的应用：该应用打开的链接由 LinkGo 处理；组内域名视为生态内容放行；未列出的应用一律放行。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                    SettingsSectionDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { onNavigateToMultiAppPicker() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("添加")
                        }
                        OutlinedButton(
                            onClick = { showResetConfirm = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("恢复默认")
                        }
                    }
                }
            }

            // 每个接管应用 = 独立圆角分组卡片（方案1：分层解耦，默认折叠，固定按添加时间排序）
            val sortedCaptureApps = captureApps.sortedBy { it.addedAt }
            items(sortedCaptureApps, key = { it.packageName }) { app ->
                val pkg = app.packageName
                val items = exemptDomains.filter { it.sourcePkg == pkg }
                val isExpanded = pkg in expandedPkgs
                val rotationAngle by animateFloatAsState(
                    targetValue = if (isExpanded) 90f else 0f,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "arrowRotation"
                )

                val cardContainerAlpha by animateFloatAsState(
                    targetValue = if (app.isEnabled) 0.6f else 0.35f,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "cardContainerAlpha"
                )
                val contentAlpha by animateFloatAsState(
                    targetValue = if (app.isEnabled) 1f else 0.45f,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "cardContentAlpha"
                )

                androidx.compose.material3.Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = cardContainerAlpha)
                ) {
                    Column {
                        GroupHeaderRow(
                            context = context,
                            sourcePkg = pkg,
                            isEnabled = app.isEnabled,
                            isExpanded = isExpanded,
                            arrowRotation = rotationAngle,
                            rulesCount = items.size,
                            onToggle = { enabled ->
                                scope.launch {
                                    android.util.Log.i("LinkGo_AppLink", "[UI-ACTION] 切换接管应用开关: pkg=$pkg, enabled=$enabled")
                                    repository.toggleCaptureAppEnabled(pkg, enabled)
                                    LinkIntentReceiver.syncConfigToHook(context.applicationContext, mode)
                                }
                            },
                            onHeaderClick = {
                                expandedPkgs = if (isExpanded) expandedPkgs - pkg else expandedPkgs + pkg
                            }
                        )

                        // 展开后内容区：放行域名列表 + 底部两端分离操作底栏（关闭接管时内容平滑淡化）
                        AnimatedVisibility(
                            visible = isExpanded,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Column(modifier = Modifier.alpha(contentAlpha)) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                )
                                Spacer(Modifier.height(4.dp))

                                if (items.isEmpty()) {
                                    Text(
                                        text = "暂无放行域名，可点击下方「添加放行规则」",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp)
                                    )
                                } else {
                                    items.forEach { item ->
                                        ExemptSettingItem(
                                            item = item,
                                            onToggle = { enabled ->
                                                scope.launch {
                                                    android.util.Log.i("LinkGo_AppLink", "[UI-ACTION] 切换放行规则开关: id=${item.id}, pattern=${item.pattern}, enabled=$enabled")
                                                    repository.updateAppLinkExemptDomains(
                                                        exemptDomains.map { if (it.id == item.id) it.copy(isEnabled = enabled) else it }
                                                    )
                                                    LinkIntentReceiver.syncConfigToHook(context.applicationContext, mode)
                                                }
                                            },
                                            onEdit = { openEditExemptSheet(item) }
                                        )
                                    }
                                }

                                Spacer(Modifier.height(6.dp))
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                )

                                // 卡片操作底栏（方案1：双实体胶囊按钮，与顶部添加/恢复默认同构）
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    FilledTonalButton(
                                        onClick = { openAddExemptSheet(pkg) },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(38.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                        ),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                    ) {
                                        Icon(
                                            imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_plus),
                                            contentDescription = null,
                                            modifier = Modifier.size(17.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "添加放行规则",
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                            maxLines = 1
                                        )
                                    }

                                    FilledTonalButton(
                                        onClick = { deleteConfirmPkg = pkg },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(38.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                                            contentColor = MaterialTheme.colorScheme.error
                                        ),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                    ) {
                                        Icon(
                                            imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_trash),
                                            contentDescription = null,
                                            modifier = Modifier.size(17.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "移除接管",
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (captureApps.isEmpty()) {
                item {
                    Text(
                        text = "暂无接管应用，点击上方「添加」选择需要由 LinkGo 处理的应用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                    )
                }
            }

            item {
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showExemptSheet) {
        ExemptDomainSheet(
            sourcePkg = sheetSourcePkg,
            matchType = runCatching { MatchType.valueOf(sheetMatchType) }.getOrDefault(MatchType.CONTAINS),
            pattern = sheetPattern,
            note = sheetNote,
            isEdit = editingExemptId != null,
            onMatchTypeChange = { sheetMatchType = it.name },
            onConfirm = { sp, mt, pat, nt ->
                saveExempt(sp, mt, pat, nt)
                showExemptSheet = false
            },
            onDelete = {
                val editingId = editingExemptId
                if (editingId != null) {
                    scope.launch {
                        android.util.Log.i("LinkGo_AppLink", "[UI-ACTION] 删除放行规则: id=$editingId")
                        repository.updateAppLinkExemptDomains(exemptDomains.filter { it.id != editingId })
                        LinkIntentReceiver.syncConfigToHook(context.applicationContext, mode)
                    }
                }
                showExemptSheet = false
            },
            onDismiss = { showExemptSheet = false }
        )
    }

    if (showResetConfirm) {
        ConfirmActionSheet(
            title = "恢复默认",
            message = "将接管应用重置为微信/QQ，放行域名重置为出厂默认，所有自定义规则将被清除。",
            confirmLabel = "恢复默认",
            onConfirm = {
                showResetConfirm = false
                scope.launch {
                    android.util.Log.i("LinkGo_AppLink", "[UI-ACTION] 恢复应用内捕获出厂默认")
                    repository.resetAppLinkCaptureDefaults()
                    LinkIntentReceiver.syncConfigToHook(context.applicationContext, mode)
                }
            },
            onDismiss = { showResetConfirm = false }
        )
    }

    if (deleteConfirmPkg != null) {
        ConfirmActionSheet(
            title = "删除接管应用",
            message = "将删除「${resolveAppLabel(context, deleteConfirmPkg!!) ?: deleteConfirmPkg}」的接管及其所有放行域名规则。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                val pkg = deleteConfirmPkg
                deleteConfirmPkg = null
                if (pkg != null) deleteCaptureApp(pkg)
            },
            onDismiss = { deleteConfirmPkg = null }
        )
    }
}



/** 分组标题行：来源图标 + 应用名（数量徽章）+ 包名 + 可选操作（+ 添加 / 🗑 删除）。 */
/**
 * 卡片头部行（方案1极简版）：折叠指示箭头 + 来源图标 + 应用名与副文本 + 单一独立开关。
 * 绝不横向堆砌操作按钮，彻底消除拥挤感。
 */
@Composable
private fun GroupHeaderRow(
    context: Context,
    sourcePkg: String,
    isEnabled: Boolean,
    isExpanded: Boolean,
    arrowRotation: Float,
    rulesCount: Int,
    onToggle: (Boolean) -> Unit,
    onHeaderClick: () -> Unit
) {
    val headerAlpha by animateFloatAsState(
        targetValue = if (isEnabled) 1f else 0.45f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "headerAlpha"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onHeaderClick)
            .padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .alpha(headerAlpha),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 折叠指示箭头（带弹性旋转动效）
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (isExpanded) "折叠" else "展开",
                modifier = Modifier
                    .size(22.dp)
                    .rotate(arrowRotation),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(6.dp))
            // 40dp 规范图标
            AsyncAppIcon(
                packageName = sourcePkg,
                size = 40.dp,
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = resolveAppLabel(context, sourcePkg) ?: sourcePkg,
                    style = MaterialTheme.typography.titleMedium.copy(
                        textDecoration = if (!isEnabled) TextDecoration.LineThrough else null
                    ),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (rulesCount > 0) "$sourcePkg · 已放行 $rulesCount 个域名" else "$sourcePkg · 未放行任何域名",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        // 右侧仅保留单一独立开关（保持 100% 鲜明，便于随时重新启用）
        PremiumSwitch(
            checked = isEnabled,
            onCheckedChange = onToggle
        )
    }
}

/** 数量徽章（跟在应用名后）。 */
@Composable
private fun CountBadge(count: Int) {
    Box(
        modifier = Modifier
            .padding(start = 6.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

/** 放行规则行：图标 + 域名 + 备注 + 开关；点击整行进入编辑。 */
@Composable
private fun ExemptSettingItem(
    item: ExemptDomain,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit
) {
    SettingItem(
        headlineText = item.pattern,
        supportingText = buildString {
            append("${item.matchType.label} 匹配")
            if (item.note.isNotBlank()) {
                append(" · ${item.note}")
            }
        },
        leadingContent = {
            AsyncAppIcon(
                packageName = item.sourcePkg,
                size = 36.dp,
                shape = RoundedCornerShape(10.dp)
            )
        },
        trailingContent = {
            PremiumSwitch(
                checked = item.isEnabled,
                onCheckedChange = onToggle
            )
        },
        modifier = Modifier.clickable { onEdit() }
    )
}

/**
 * M3 半屏底部弹窗：添加/编辑放行规则（匹配模式 + 匹配内容）。
 * 来源应用固定为当前分组应用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExemptDomainSheet(
    sourcePkg: String?,
    matchType: MatchType,
    pattern: String,
    note: String,
    isEdit: Boolean,
    onMatchTypeChange: (MatchType) -> Unit,
    onConfirm: (String, MatchType, String, String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var patternInput by rememberSaveable { mutableStateOf(pattern) }
    var noteInput by rememberSaveable { mutableStateOf(note) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val effectiveSource = sourcePkg ?: return

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
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (isEdit) "编辑放行规则" else "添加放行规则",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.5).sp
                    )
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_xmark),
                        contentDescription = "关闭",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                text = "来源应用",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncAppIcon(
                    packageName = effectiveSource,
                    size = 24.dp,
                    shape = RoundedCornerShape(8.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = resolveAppLabel(context, effectiveSource) ?: effectiveSource,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = effectiveSource,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = "匹配模式",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MatchType.entries.forEach { mt ->
                    FilterChip(
                        selected = mt == matchType,
                        onClick = { onMatchTypeChange(mt) },
                        label = { Text(mt.label) }
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = "匹配内容",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            NativeOutlinedTextField(
                value = patternInput,
                onValueChange = { patternInput = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                hint = when (matchType) {
                    MatchType.CONTAINS -> "如 mp.weixin.qq.com"
                    MatchType.REGEX -> "如 .*weixin\\.qq\\.com.*"
                    MatchType.EXACT -> "完整 URL"
                },
                cornerRadius = 16.dp,
                singleLine = true
            )
            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "备注",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            NativeOutlinedTextField(
                value = noteInput,
                onValueChange = { noteInput = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                hint = "备注（可选）",
                cornerRadius = 16.dp,
                singleLine = true
            )

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (isEdit) {
                    OutlinedButton(
                        onClick = onDelete,
                        modifier = Modifier.weight(1f),
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("删除")
                    }
                } else {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("取消")
                    }
                }
                Button(
                    onClick = {
                        val normalized = trimInput(patternInput)
                        if (normalized == null) {
                            error = "请输入匹配内容"
                        } else {
                            onConfirm(effectiveSource, matchType, normalized, noteInput.trim())
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (isEdit) "保存" else "添加")
                }
            }
        }
    }
}

/** 匹配内容仅去除首尾空白（正则/完整 URL 保留原样）。 */
private fun trimInput(input: String): String? = input.trim().ifBlank { null }

/** 解析应用显示名（包名 → 应用名），失败返回 null。 */
private fun resolveAppLabel(context: Context, pkg: String): String? = runCatching {
    context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
}.getOrNull()
