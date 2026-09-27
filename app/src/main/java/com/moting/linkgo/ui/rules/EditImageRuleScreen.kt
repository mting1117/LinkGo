package com.moting.linkgo.ui.rules

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moting.linkgo.data.PackageRepository
import com.moting.linkgo.image.ImageRouter
import com.moting.linkgo.model.ImageRule
import com.moting.linkgo.ui.components.ConfirmActionSheet
import com.moting.linkgo.ui.components.NativeOutlinedTextField
import com.moting.linkgo.ui.components.PremiumSegmentedRow
import com.moting.linkgo.ui.components.PremiumSwitch
import com.moting.linkgo.ui.components.RuleIcon
import com.moting.linkgo.viewmodel.RulesViewModel

/**
 * 图片规则编辑页。
 *
 * 结构对照文本规则编辑页（`EditRuleScreen`），但只保留两块：
 * 1. **基本信息**：规则名称 / 图标 / 窗口模式 / 不留后台卡片；
 * 2. **图片跳转**：目标应用（带定位与可用性标注）+ 目标活动 + 跳转模式。
 *
 * **刻意没有"匹配条件"板块**：已定稿"是图片就行"，图片规则不做属性过滤，
 * 多条规则之间靠列表顺序仲裁（见 `ImageRule` 的说明）。
 *
 * **刻意没有"应用小窗预热"**：预热是为"先冷启动目标 App、延迟后再跳转"设计的，
 * 而图片分享是一次性 `ACTION_SEND` 投递，目标应用被拉起后仍需用户二次操作才能完成发送，
 * 收益不明确，故不提供该开关（`ImageRule` 中对应字段已一并移除）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditImageRuleScreen(
    ruleId: String?,
    onNavigateBack: () -> Unit,
    viewModel: RulesViewModel = viewModel()
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) { viewModel.initRepository(context) }

    val imageRules by viewModel.imageRules.collectAsState()
    val windowConfig by viewModel.windowConfig.collectAsState()
    val existing = remember(ruleId, imageRules) {
        if (ruleId != null) imageRules.find { it.id == ruleId } else null
    }

    var name by rememberSaveable { mutableStateOf("") }
    var targetPackage by rememberSaveable { mutableStateOf("") }
    var targetClass by rememberSaveable { mutableStateOf("") }
    var ruleLaunchMode by rememberSaveable { mutableIntStateOf(-1) }
    var excludeFromRecents by rememberSaveable { mutableStateOf(false) }
    var iconPath by rememberSaveable { mutableStateOf<String?>(null) }

    var showSharePicker by remember { mutableStateOf(false) }
    var showExitDialog by rememberSaveable { mutableStateOf(false) }

    var hasLoadedInitialData by remember { mutableStateOf(false) }

    // 编辑模式回填：仅在首次拿到有效数据时回填，避免后续流发射冲刷用户正在编辑的草稿
    LaunchedEffect(existing) {
        if (!hasLoadedInitialData) {
            if (existing != null) {
                name = existing.name
                targetPackage = existing.targetPackage
                targetClass = existing.targetClass
                ruleLaunchMode = existing.ruleLaunchMode
                excludeFromRecents = existing.excludeFromRecents
                iconPath = existing.iconPath
                hasLoadedInitialData = true
            } else if (ruleId == null) {
                hasLoadedInitialData = true
            }
        }
    }

    // 判定是否有未保存的修改 (仅在初始数据加载完成后判定)
    val isDirty = remember(
        hasLoadedInitialData, existing, name, targetPackage, targetClass,
        ruleLaunchMode, excludeFromRecents, iconPath
    ) {
        if (!hasLoadedInitialData) false
        else {
            if (existing != null) {
                // 编辑已有规则：对比字段是否被修改
                name != existing.name ||
                targetPackage != existing.targetPackage ||
                targetClass != existing.targetClass ||
                ruleLaunchMode != existing.ruleLaunchMode ||
                excludeFromRecents != existing.excludeFromRecents ||
                iconPath != existing.iconPath
            } else {
                // 新建模式：检查是否有任何修改
                name.isNotBlank() ||
                targetPackage.isNotBlank() ||
                targetClass.isNotBlank() ||
                ruleLaunchMode != -1 ||
                excludeFromRecents ||
                !iconPath.isNullOrBlank()
            }
        }
    }

    // 统一处理退出逻辑
    val performBack = {
        keyboardController?.hide()
        focusManager.clearFocus()
        if (isDirty) {
            showExitDialog = true
        } else {
            onNavigateBack()
        }
    }

    // 拦截物理返回键：仅在有修改且未打开子选择页时拦截以显示确认对话框
    BackHandler(enabled = isDirty && !showSharePicker) {
        performBack()
    }

    // 图标选择：复用与文本规则完全相同的图标存储（rule_icons 目录是用户资产，不参与 TTL 清理）
    val iconPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.saveCustomIcon(context, uri)?.let { iconPath = it }
        }
    }

    // 目标应用可用性：卡片与保存前都要看它，避免用户配出一条永远跳不出去的规则
    val targetSupported = remember(targetPackage) {
        targetPackage.isNotBlank() && ImageRouter.canReceiveImage(context, targetPackage)
    }
    val targetLabel = remember(targetPackage) {
        if (targetPackage.isBlank()) "" else PackageRepository.getAppLabel(context, targetPackage)
    }

    fun buildRule(): ImageRule = ImageRule(
        id = existing?.id ?: java.util.UUID.randomUUID().toString(),
        name = name.ifBlank { targetLabel.ifBlank { "图片规则" } },
        targetPackage = targetPackage.trim(),
        targetClass = targetClass.trim(),
        ruleLaunchMode = ruleLaunchMode,
        excludeFromRecents = excludeFromRecents,
        iconPath = iconPath
    )

    fun save() {
        keyboardController?.hide()
        focusManager.clearFocus()
        if (targetPackage.isBlank()) {
            Toast.makeText(context, "请先设置目标应用", Toast.LENGTH_SHORT).show()
            return
        }
        if (!targetSupported) {
            // 不拦截保存（用户可能先配好、稍后再装目标应用），但必须让用户知道当前跳不通
            Toast.makeText(context, "该应用当前无法接收图片，规则已保存但暂不生效", Toast.LENGTH_LONG).show()
        }
        val rule = buildRule()
        if (existing == null) viewModel.addImageRule(rule) else viewModel.updateImageRule(rule)
        onNavigateBack()
    }

    if (showExitDialog) {
        val isNew = ruleId == null
        ConfirmActionSheet(
            title = if (isNew) "退出添加" else "保存更改",
            message = if (isNew) "新建规则内容未添加，是否添加本次新建？" else "已修改规则内容，是否保存这些更改？",
            confirmLabel = if (isNew) "添加并创建" else "保存并退出",
            dismissLabel = "继续编辑",
            secondaryActionLabel = if (isNew) "放弃添加" else "放弃更改",
            secondaryDestructive = true,
            onConfirm = {
                if (targetPackage.isBlank()) {
                    Toast.makeText(context, "请先设置目标应用", Toast.LENGTH_SHORT).show()
                    return@ConfirmActionSheet
                }
                save()
                showExitDialog = false
            },
            onSecondaryAction = {
                showExitDialog = false
                onNavigateBack()
            },
            onDismiss = { showExitDialog = false }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.ime),
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "添加图片规则" else "编辑图片规则", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = performBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    Button(
                        onClick = { save() },
                        enabled = targetPackage.isNotBlank(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                        modifier = Modifier.height(40.dp),
                        shape = RoundedCornerShape(12.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                    ) {
                        Icon(
                            Icons.Default.Save,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("保存", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 1. 基本信息（与文本跳转规则编辑页保持一致）
            EditSection(title = "1. 基本信息") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    var showIconMenu by remember { mutableStateOf(false) }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clickable { showIconMenu = true },
                            contentAlignment = Alignment.Center
                        ) {
                            RuleIcon(
                                iconPath = iconPath,
                                targetPackage = targetPackage,
                                size = 56.dp,
                                shape = RoundedCornerShape(14.dp)
                            )
                            Surface(
                                modifier = Modifier.align(Alignment.BottomEnd).size(20.dp),
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.surface)
                            ) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = null,
                                    modifier = Modifier.padding(4.dp),
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                            DropdownMenu(
                                expanded = showIconMenu,
                                onDismissRequest = { showIconMenu = false },
                                modifier = Modifier.border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(20.dp)
                                )
                            ) {
                                DropdownMenuItem(
                                    text = { Text("上传本地图片") },
                                    leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
                                    onClick = {
                                        showIconMenu = false
                                        iconPickerLauncher.launch("image/*")
                                    }
                                )
                                if (iconPath != null) {
                                    DropdownMenuItem(
                                        text = { Text("恢复默认联动") },
                                        leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                        onClick = {
                                            showIconMenu = false
                                            iconPath = null
                                        }
                                    )
                                }
                            }
                        }

                        NativeOutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            hint = "规则名称",
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "窗口模式选择",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    PremiumSegmentedRow(
                        options = listOf(-1, 5, 1),
                        selectedOption = ruleLaunchMode,
                        onOptionSelected = { ruleLaunchMode = it },
                        labelProvider = { mode ->
                            when (mode) {
                                -1 -> "全局"
                                5 -> "小窗"
                                1 -> "全屏"
                                else -> "未知"
                            }
                        }
                    )

                    val modeHint = when (ruleLaunchMode) {
                        -1 -> if (windowConfig.isEnabled) {
                            "跟随全局设置：${windowConfig.getModeName()} [已开启]"
                        } else {
                            "跟随全局设置：自由窗口已停用（将以普通全屏打开）"
                        }
                        5 -> "强制小窗打开：使用 ${windowConfig.getModeName()}"
                        1 -> "强制全屏打开（忽略全局自由窗口配置）"
                        else -> ""
                    }
                    if (modeHint.isNotBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = modeHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp, horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "不留后台卡片",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "本规则跳转的应用不保留最近任务卡片",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        PremiumSwitch(
                            checked = excludeFromRecents,
                            onCheckedChange = { excludeFromRecents = it }
                        )
                    }
                }
            }

            // 2. 分享入口
            EditSection(title = "2. 分享入口") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // 包名由类名选择的结果反推，因此这里只作展示，不接受直接编辑
                    NativeOutlinedTextField(
                        value = targetPackage,
                        onValueChange = {},
                        hint = "目标应用包名（由分享接口决定）",
                        singleLine = true,
                        enabled = false,
                        modifier = Modifier.fillMaxWidth()
                    )

                    NativeOutlinedTextField(
                        value = targetClass,
                        onValueChange = { targetClass = it },
                        hint = "分享接口类名",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(onClick = { showSharePicker = true }) {
                                Icon(
                                    Icons.Default.Adjust,
                                    contentDescription = "选择分享接口",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    )

                    // 目标可用性实时结论：这是图片规则唯一会静默失效的地方，必须直接标出来
                    if (targetPackage.isNotBlank()) {
                        TargetStatusRow(
                            label = targetLabel,
                            supported = targetSupported,
                            hasClass = targetClass.isNotBlank()
                        )
                    } else {
                        Text(
                            "点击右侧定位按钮选择分享接口——列表只包含系统里确实能接收图片的应用。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 3. 优先级说明：图片规则没有匹配条件，顺序是唯一仲裁依据，不说清用户无法预期行为
            EditSection(title = "3. 优先级说明") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "图片规则不做内容匹配——只要是图片就会命中。" +
                            "存在多条图片规则时，规则列表中靠前的优先，" +
                            "可在规则页拖拽调整顺序。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
    // 分享接口选择：一次拿到「包名 + 类名 + 接口名称」三者，
    // 因此用独立全屏页而不是走导航回传（后者需额外约定编码格式）
    if (showSharePicker) {
        com.moting.linkgo.image.ImageSharePickerScreen(
            onEntrySelected = { entry ->
                targetPackage = entry.packageName
                targetClass = entry.className
                // 仅在名称为空时回填，避免覆盖用户已输入的名称
                if (name.isBlank()) name = entry.entryLabel
                showSharePicker = false
            },
            onNavigateBack = { showSharePicker = false }
        )
    }
}

/**
 * 分享入口可用性提示行。
 *
 * 三态必须区分清楚：「还没选接口」与「选了的接口现在不可用」的处理方式完全不同
 * （去选 vs 换一个），所以文案不能合并。
 */
@Composable
private fun TargetStatusRow(label: String, supported: Boolean, hasClass: Boolean) {
    val (text, color) = when {
        supported && hasClass ->
            "将发送到「${label.ifBlank { "目标应用" }}」的分享接口" to MaterialTheme.colorScheme.primary
        supported ->
            "已选应用「${label.ifBlank { "目标应用" }}」，但还没指定分享接口" to MaterialTheme.colorScheme.secondary
        else ->
            "该应用当前无法接收图片（未安装或版本不支持分享图片）" to MaterialTheme.colorScheme.error
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color
    )
}

