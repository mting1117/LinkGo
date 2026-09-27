package com.moting.linkgo.ui.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material3.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moting.linkgo.model.MatchType
import com.moting.linkgo.model.DispatchRule
import com.moting.linkgo.model.ResolutionStrategy
import com.moting.linkgo.viewmodel.RulesViewModel
import com.moting.linkgo.util.WindowRouter
import android.content.ComponentName
import android.net.Uri
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.painterResource
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.text.style.TextAlign
import com.moting.linkgo.ui.components.*

/**
 * 全屏规则编辑器页面
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun EditRuleScreen(
    ruleId: String?,
    preName: String? = null,
    prePattern: String? = null,
    prePackage: String? = null,
    onNavigateBack: () -> Unit,
    onNavigateToAppPicker: (Boolean) -> Unit,
    onNavigateToActivityPicker: (String) -> Unit,
    selectedPackage: String?,
    selectedActivity: String?,
    selectedAppName: String?,
    onClearBackStackData: (String) -> Unit,
    preIsFromSelector: Boolean = false,
    /** 新建时的初始解析策略：由「新增规则」菜单的"分发规则"入口传入，用于预置为桥接分发 */
    preStrategy: String? = null,
    viewModel: RulesViewModel = viewModel()
) {
    val rules by viewModel.rules.collectAsState()
    val fallbackBrowser by viewModel.fallbackBrowser.collectAsState()
    val windowConfig by viewModel.windowConfig.collectAsState()
    val normalizationEnabled by viewModel.normalizationEnabled.collectAsState()
    val normalizationRegex by viewModel.normalizationRegex.collectAsState()
    val normalizationTemplate by viewModel.normalizationTemplate.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    
    // 确保数据仓库初始化，否则直接跳转过来时 rules 列表可能为空
    LaunchedEffect(Unit) {
        viewModel.initRepository(context)
    }

    val rule = remember(ruleId, rules) {
        if (ruleId != null) rules.find { it.id == ruleId } else null
    }

    var name by rememberSaveable { mutableStateOf("") }
    var pattern by rememberSaveable { mutableStateOf("") }
    var pkg by rememberSaveable { mutableStateOf("") }
    var cls by rememberSaveable { mutableStateOf("") }
    var template by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var ruleLaunchMode by rememberSaveable { mutableIntStateOf(-1) }
    var matchType by rememberSaveable { mutableStateOf(MatchType.CONTAINS) }
    var extractPattern by rememberSaveable { mutableStateOf("") }
    var resolveShortLink by rememberSaveable { mutableStateOf(false) }
    var resolveStrategy by rememberSaveable { mutableStateOf(ResolutionStrategy.NONE) }
    var excludeFromRecents by rememberSaveable { mutableStateOf(false) } 
    var iconPath by rememberSaveable { mutableStateOf<String?>(null) }
    var jumpAndCopy by rememberSaveable { mutableStateOf(false) }
    var isPickingForIcon by rememberSaveable { mutableStateOf(false) }
    
    // --- 强制预热逻辑判定 (提前定义以供状态使用) ---
    // 1. 判定目标是否未导出
    val isTargetNotExported = remember(pkg, cls) {
        if (pkg.isNotBlank() && cls.isNotBlank()) {
            !WindowRouter.isComponentExported(context, ComponentName(pkg, cls))
        } else false
    }

    // 2. 判定是否最终以小窗模式运行
    val isEffectiveSmallWindow = when (ruleLaunchMode) {
        5 -> true // 显式指定小窗
        1 -> false // 显式指定全屏
        else -> windowConfig.isEnabled && windowConfig.windowingMode != 1 // 遵循全局且全局当前开启了自由窗口
    }

    // 3. 最终强制判定
    val isPreheatForced = isTargetNotExported && isEffectiveSmallWindow

    var userPreheatPreference by rememberSaveable { mutableStateOf(false) }
    val effectivePreheatEnabled = if (isPreheatForced) true else userPreheatPreference
    var preheatDelayMillis by rememberSaveable { mutableStateOf("0") }

    
    // 链接测试状态 (用于承载实时仿真数据)
    var testUrl by rememberSaveable { mutableStateOf("") }
    var testJumpError by remember { mutableStateOf<String?>(null) }
    var testPipeline by remember { mutableStateOf<List<TransformationResult>>(emptyList()) }
    var isSimulating by remember { mutableStateOf(false) }
    
    var showTestBottomSheet by rememberSaveable { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboardManager = LocalClipboardManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // 图片选择 Launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val internalPath = viewModel.saveCustomIcon(context, uri)
            if (internalPath != null) {
                iconPath = internalPath
            }
        }
    }

    // 实时解析与路径仿真逻辑 (由 WindowRouter 统一驱动)
    LaunchedEffect(testUrl, pattern, matchType, template.text, resolveStrategy, extractPattern, resolveShortLink, pkg, cls, rules, showTestBottomSheet) {
        if (!showTestBottomSheet || testUrl.isBlank()) {
            testPipeline = emptyList()
            return@LaunchedEffect
        }
        
        isSimulating = true
        testPipeline = emptyList()
        delay(500) // 防抖处理

        val currentEditingRule = DispatchRule(
            id = ruleId ?: "temp_test",
            name = name.ifBlank { "正在编辑的规则" },
            pattern = pattern,
            targetPackage = if (cls.isNotBlank()) "$pkg/$cls" else pkg,
            template = template.text,
            matchType = matchType,
            extractPattern = extractPattern.ifBlank { null },
            resolveStrategy = resolveStrategy,
            ruleLaunchMode = ruleLaunchMode,
            isEnabled = true,
            resolveShortLink = resolveShortLink,
            excludeFromRecents = excludeFromRecents,
            isPreheatEnabled = effectivePreheatEnabled,
            preheatDelayMillis = preheatDelayMillis.toLongOrNull() ?: 0L,
            jumpAndCopy = jumpAndCopy
        )

        // 核心同步：调用统一仿真引擎
        val steps = WindowRouter.simulateRoute(
            context = context,
            testUrl = testUrl,
            currentRule = currentEditingRule,
            allRules = rules,
            fallbackPkg = fallbackBrowser,
            isNormEnabled = normalizationEnabled,
            normRegex = normalizationRegex,
            normTemplate = normalizationTemplate,
            onFinalJump = { _, _ -> }
        )

        // 数据适配：将仿真步进转换为 UI 展示模型 (保留高亮渲染功能)
        testPipeline = steps.map { step ->
            val stageName = when {
                step.depth == 0 -> "当前规则"
                step.isFinal -> "最终匹配"
                else -> "中间匹配 (${step.depth})"
            }

            if (step.matchedRule != null) {
                // 利用 calculateTransformation 进行高亮渲染逻辑处理
                val highlightResult = calculateTransformation(
                    inputUrl = step.standardizedUrl,
                    resolvedUrl = step.resolvedUrl, // 传入解析后的结果
                    rule = step.matchedRule,
                    stageName = stageName,
                    skipMatchCheck = true,
                    engineResultUrl = step.resultUrl // 注入真引擎结果
                )
                
                val tpl = step.matchedRule.template ?: ""
                highlightResult?.copy(
                    displayInputUrl = if (step.depth == 0) testUrl else step.inputUrl,
                    resolvedUrl = step.resolvedUrl,
                    engineResultUrl = step.resultUrl, 
                    isDispatchOnly = step.matchedRule.resolveStrategy == ResolutionStrategy.RE_DISPATCH,
                    hasUrlEncode = tpl.contains("_url_enc") || tpl.contains("{url_encode}"),
                    hasUrlDecode = tpl.contains("_url_dec") || tpl.contains("{url_decode}"),
                    hasB64Encode = tpl.contains("_b64_enc"),
                    hasB64Decode = tpl.contains("_b64_dec"),
                    isExtracted = step.isExtracted,
                    isResolved = step.isResolved,
                    isReconstructed = step.isReconstructed,
                    error = step.error
                ) ?: TransformationResult(stageName, step.inputUrl, AnnotatedString(step.inputUrl), AnnotatedString(step.inputUrl), null, emptyMap(), error = "渲染失败")
            } else {
                // 库未命中或错误阶段
                TransformationResult(
                    stageName = stageName,
                    inputUrl = step.inputUrl,
                    sourceAnnotated = AnnotatedString(step.inputUrl),
                    resultAnnotated = AnnotatedString(step.resultUrl ?: step.inputUrl),
                    resolvedAnnotated = null,
                    colorMap = emptyMap(),
                    targetLabel = step.targetLabel,
                    isLibraryMiss = step.isLibraryMiss,
                    isExtracted = step.isExtracted,
                    isPatternMismatch = step.isPatternMismatch,
                    error = step.error
                )
            }
        }
        
        isSimulating = false
    }
    
    // 多锚点请求器，用于精准定位
    val nameRequester = remember { BringIntoViewRequester() }
    val pkgRequester = remember { BringIntoViewRequester() }
    val clsRequester = remember { BringIntoViewRequester() }
    val patternRequester = remember { BringIntoViewRequester() }
    val extractRequester = remember { BringIntoViewRequester() }
    val templateRequester = remember { BringIntoViewRequester() }
    val preheatRequester = remember { BringIntoViewRequester() }

    val isImeVisible = WindowInsets.ime.asPaddingValues().calculateBottomPadding() > 0.dp

    // 当键盘弹起时，联动 ScrollState 确保滚动位置正确
    LaunchedEffect(isImeVisible) {
        if (isImeVisible) {
            delay(100)
            // 键盘弹起时由各输入框的 bringIntoView 自行触发，此处作为兜底
        }
    }

    var hasLoadedInitialData by remember { mutableStateOf(false) }
    
    // 初始化数据逻辑
    LaunchedEffect(rule, preName, prePattern, prePackage, preStrategy) {
        if (!hasLoadedInitialData) {
            if (rule != null) {
                // 编辑现有模式：回填完整数据
                name = rule.name
                pattern = rule.pattern
                template = TextFieldValue(rule.template ?: "", selection = TextRange((rule.template ?: "").length))
                ruleLaunchMode = rule.ruleLaunchMode
                val target = rule.targetPackage
                if (target.contains("/")) {
                    pkg = target.substringBefore("/")
                    cls = target.substringAfter("/")
                } else {
                    pkg = target
                    cls = ""
                }
                matchType = rule.matchType
                extractPattern = rule.extractPattern ?: ""
                resolveShortLink = rule.resolveShortLink
                resolveStrategy = rule.resolveStrategy
                excludeFromRecents = rule.excludeFromRecents
                userPreheatPreference = rule.isPreheatEnabled
                preheatDelayMillis = rule.preheatDelayMillis.toString()
                iconPath = rule.iconPath
                jumpAndCopy = rule.jumpAndCopy

                hasLoadedInitialData = true
            } else if (ruleId == null) {
                // 新建并预填模式 (可能来自首页分析，也可能来自选择器)
                if (preName != null) name = preName
                if (prePattern != null) {
                    // 统一采用首页流程逻辑：优先提取 Host，其次 Scheme，最后兜底原始字符串
                    val uri = try { android.net.Uri.parse(prePattern) } catch(e: Exception) { null }
                    val extracted = uri?.host ?: uri?.scheme
                    pattern = extracted ?: prePattern
                    matchType = MatchType.CONTAINS
                    
                    // 默认预览链接设为 pattern，方便直接测试
                    if (prePattern.startsWith("http")) testUrl = prePattern
                }
                if (prePackage != null) {
                    if (prePackage.contains("/")) {
                        pkg = prePackage.substringBefore("/")
                        cls = prePackage.substringAfter("/")
                    } else {
                        pkg = prePackage
                        cls = "" // 不自动填充：留空让 WindowRouter 隐式路由到正确的 URL 处理 Activity
                    }
                }
                
                // 从「新增规则」菜单进来的分发规则：预置为桥接分发。
                // 模式留空（而非填入通配正则）——空白模式在匹配时被视为"命中一切"，
                // 而一条 pattern 为空的分发规则只会把链接原样传给下一轮，不会成为黑洞。
                if (preStrategy == ResolutionStrategy.RE_DISPATCH.name) {
                    resolveStrategy = ResolutionStrategy.RE_DISPATCH
                    if (name.isBlank()) name = "新分发规则"
                } else if (preStrategy == ResolutionStrategy.NONE.name) {
                    resolveStrategy = ResolutionStrategy.NONE
                }

                // 确保标记已加载
                if (preName != null || prePattern != null || prePackage != null || preStrategy != null) {
                    hasLoadedInitialData = true
                }
            }
        }
    }

    // 处理回传参数 (Picker)
    LaunchedEffect(selectedPackage) { 
        if (selectedPackage != null) {
            if (isPickingForIcon) {
                iconPath = "app://$selectedPackage"
                isPickingForIcon = false
            } else {
                pkg = selectedPackage 
            }
            onClearBackStackData("selected_package")
        }
    }
    LaunchedEffect(selectedActivity) { 
        if (selectedActivity != null) {
            cls = selectedActivity
            onClearBackStackData("selected_activity")
        }
    }
    LaunchedEffect(selectedAppName) {
        if (selectedAppName != null) {
            // 如果名称为空，自动填入应用名称
            if (name.isBlank()) {
                name = selectedAppName
            }
            onClearBackStackData("selected_app_name")
        }
    }

    // --- 退出确认逻辑 ---
    var showExitDialog by remember { mutableStateOf(false) }
    
    // 计算当前表单是否被修改过 (Dirty Check)
    val isDirty by remember(
        name, pattern, pkg, cls, template.text, matchType, extractPattern, resolveShortLink, excludeFromRecents, rule,
        preName, prePattern, prePackage, resolveStrategy, ruleLaunchMode, userPreheatPreference, preheatDelayMillis,
        iconPath, jumpAndCopy
    ) {

        derivedStateOf {
            if (rule != null) {
                // 编辑模式：与原规则对比
                val target = if (cls.isNotBlank()) "$pkg/$cls" else pkg
                name != rule.name ||
                pattern != rule.pattern ||
                target != rule.targetPackage ||
                template.text != (rule.template ?: "") ||
                matchType != rule.matchType ||
                extractPattern != (rule.extractPattern ?: "") ||
                resolveShortLink != rule.resolveShortLink ||
                resolveStrategy != rule.resolveStrategy ||
                ruleLaunchMode != rule.ruleLaunchMode ||
                excludeFromRecents != rule.excludeFromRecents ||
                userPreheatPreference != rule.isPreheatEnabled ||
                preheatDelayMillis != rule.preheatDelayMillis.toString() ||
                iconPath != rule.iconPath ||
                jumpAndCopy != rule.jumpAndCopy

            } else {
                // 新建模式：检查是否有任何修改（与初始预填值对比）
                val initialPkg = if (prePackage?.contains("/") == true) prePackage.substringBefore("/") else (prePackage ?: "")
                val initialCls = if (prePackage?.contains("/") == true) prePackage.substringAfter("/") else ""
                
                name != (preName ?: "") || 
                pattern != (prePattern ?: "") || 
                pkg != initialPkg || 
                cls != initialCls ||
                template.text.isNotBlank() ||
                extractPattern.isNotBlank() ||
                resolveShortLink ||
                resolveStrategy != ResolutionStrategy.NONE ||
                ruleLaunchMode != -1 ||
                excludeFromRecents ||
                userPreheatPreference ||
                !iconPath.isNullOrBlank() ||
                jumpAndCopy
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

    // 拦截物理返回键：仅在有修改时拦截以显示确认对话框，否则允许系统执行预测性返回预览
    androidx.activity.compose.BackHandler(enabled = isDirty) {
        performBack()
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
                val target = if (cls.isNotBlank()) "$pkg/$cls" else pkg
                if (isNew) {
                    viewModel.addRule(
                        name = name,
                        pattern = pattern,
                        targetPackage = if (resolveStrategy == ResolutionStrategy.RE_DISPATCH && pkg.isBlank()) "" else target,
                        template = template.text.ifBlank { null },
                        extractPattern = extractPattern.ifBlank { null },
                        ruleLaunchMode = ruleLaunchMode,
                        matchType = matchType,
                        resolveShortLink = resolveShortLink,
                        resolveStrategy = resolveStrategy,
                        excludeFromRecents = excludeFromRecents,
                        isPreheatEnabled = effectivePreheatEnabled,
                        preheatDelayMillis = preheatDelayMillis.toLongOrNull() ?: 0L,
                        iconPath = iconPath,
                        jumpAndCopy = jumpAndCopy
                    )
                } else {
                    viewModel.updateRule(rule!!.copy(
                        name = name,
                        pattern = pattern,
                        targetPackage = if (resolveStrategy == ResolutionStrategy.RE_DISPATCH && pkg.isBlank()) "" else target,
                        template = template.text.ifBlank { null },
                        extractPattern = extractPattern.ifBlank { null },
                        ruleLaunchMode = ruleLaunchMode,
                        matchType = matchType,
                        resolveShortLink = resolveShortLink,
                        resolveStrategy = resolveStrategy,
                        excludeFromRecents = excludeFromRecents,
                        isPreheatEnabled = effectivePreheatEnabled,
                        preheatDelayMillis = preheatDelayMillis.toLongOrNull() ?: 0L,
                        iconPath = iconPath,
                        jumpAndCopy = jumpAndCopy
                    ))
                }
                showExitDialog = false
                onNavigateBack()
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
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.ime), // 核心：显式合并键盘 Insets，确保布局深度感知键盘高度
        topBar = {
            TopAppBar(
                title = { Text(if (ruleId == null) "添加规则" else "编辑规则", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = performBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    // 1. 测试按钮 (强调实验性)
                    FilledTonalButton(
                        onClick = { showTestBottomSheet = true },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                        modifier = Modifier.height(40.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            Icons.Default.Science,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("测试", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // 2. 保存按钮 (第一行动点，最为强调)
                    Button(
                        onClick = {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            val target = if (cls.isNotBlank()) "$pkg/$cls" else pkg
                            if (ruleId == null) {
                                viewModel.addRule(
                                    name = name, 
                                    pattern = pattern, 
                                    targetPackage = if (resolveStrategy == ResolutionStrategy.RE_DISPATCH && pkg.isBlank()) "" else target, 
                                    template = template.text.ifBlank { null }, 
                                    ruleLaunchMode = ruleLaunchMode, 
                                    matchType = matchType, 
                                    extractPattern = extractPattern.ifBlank { null }, 
                                    resolveShortLink = resolveShortLink,
                                    resolveStrategy = resolveStrategy,
                                    excludeFromRecents = excludeFromRecents,
                                    isPreheatEnabled = effectivePreheatEnabled, 
                                    preheatDelayMillis = preheatDelayMillis.toLongOrNull() ?: 0L,
                                    iconPath = iconPath,
                                    jumpAndCopy = jumpAndCopy
                                )
                            } else {

                                viewModel.updateRule(rule!!.copy(
                                    name = name,
                                    pattern = pattern,
                                    targetPackage = if (resolveStrategy == ResolutionStrategy.RE_DISPATCH && pkg.isBlank()) "" else target,
                                    template = template.text.ifBlank { null },
                                    extractPattern = extractPattern.ifBlank { null },
                                    ruleLaunchMode = ruleLaunchMode,
                                    matchType = matchType,
                                    resolveShortLink = resolveShortLink,
                                    resolveStrategy = resolveStrategy,
                                    excludeFromRecents = excludeFromRecents,
                                    isPreheatEnabled = effectivePreheatEnabled,
                                    preheatDelayMillis = preheatDelayMillis.toLongOrNull() ?: 0L,
                                    iconPath = iconPath,
                                    jumpAndCopy = jumpAndCopy
                                ))

                            }
                            onNavigateBack()
                        },
                        enabled = name.isNotBlank() && pattern.isNotBlank() && (resolveStrategy == ResolutionStrategy.RE_DISPATCH || pkg.isNotBlank()),
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
                    containerColor = Color.Transparent, // 透明顶栏：实现无缝沉浸式视觉
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 1. 基本信息 (Essential Info)
            EditSection(title = "1. 基本信息") {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    var showIconMenu by remember { mutableStateOf(false) }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // 左侧图标位
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clickable { showIconMenu = true },
                            contentAlignment = Alignment.Center
                        ) {
                            RuleIcon(
                                iconPath = iconPath,
                                targetPackage = pkg,
                                isReDispatch = resolveStrategy == ResolutionStrategy.RE_DISPATCH,
                                size = 56.dp,
                                shape = RoundedCornerShape(14.dp)
                            )

                            // 编辑角标
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
                            
                            MaterialTheme(
                                shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(20.dp))
                            ) {
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
                                        text = { Text("选择应用图标") },
                                        leadingIcon = { Icon(Icons.Default.Apps, contentDescription = null) },
                                        onClick = {
                                            showIconMenu = false
                                            isPickingForIcon = true
                                            onNavigateToAppPicker(true)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("上传本地图片") },
                                        leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
                                        onClick = {
                                            showIconMenu = false
                                            imagePickerLauncher.launch("image/*")
                                        }
                                    )
                                    if (iconPath != null) {
                                        DropdownMenuItem(
                                            text = { 
                                                Text(
                                                    if (resolveStrategy == ResolutionStrategy.RE_DISPATCH) "恢复默认图标" 
                                                    else "恢复默认联动"
                                                ) 
                                            },
                                            leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                            onClick = {
                                                showIconMenu = false
                                                iconPath = null
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        NativeOutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            hint = "规则名称",
                            singleLine = true,
                            modifier = Modifier.weight(1f).bringIntoViewRequester(nameRequester),
                            onFocusChange = { if (it) scope.launch { delay(300); nameRequester.bringIntoView() } }
                        )
                    }

                    if (resolveStrategy != ResolutionStrategy.RE_DISPATCH) {
                        NativeOutlinedTextField(
                            value = pkg,
                            onValueChange = { pkg = it },
                            hint = "应用包名",
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().bringIntoViewRequester(pkgRequester),
                            onFocusChange = { if (it) scope.launch { delay(300); pkgRequester.bringIntoView() } },
                            trailingIcon = {
                                IconButton(onClick = { 
                                    isPickingForIcon = false
                                    onNavigateToAppPicker(false) 
                                }) {
                                    Icon(
                                        Icons.Default.Adjust,
                                        contentDescription = "选择应用",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        )

                        NativeOutlinedTextField(
                            value = cls,
                            onValueChange = { cls = it },
                            hint = "应用类名",
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().bringIntoViewRequester(clsRequester),
                            onFocusChange = { if (it) scope.launch { delay(300); clsRequester.bringIntoView() } },
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        isPickingForIcon = false
                                        if (pkg.isNotBlank()) {
                                            onNavigateToActivityPicker(pkg)
                                        } else {
                                            onNavigateToAppPicker(false)
                                        }
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Adjust,
                                        contentDescription = "选择活动",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        )

                        Spacer(modifier = Modifier.height(4.dp))
                        Text("窗口模式选择", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

                        Spacer(modifier = Modifier.height(4.dp))
                        
                        // 不留后台开关 (平铺，仅点击开关触发)
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

                        // 应用预热经典布局 (平铺，仅点击开关触发)
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp, horizontal = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "应用小窗预热 (实验性)",
                                        style = MaterialTheme.typography.bodyMedium, 
                                        fontWeight = FontWeight.Bold,
                                        color = if (isPreheatForced) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        if (isPreheatForced) "目标页面未导出，小窗跳转需强制开启预热" 
                                        else "先冷启动目标 App，延迟后再跳转",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isPreheatForced) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                PremiumSwitch(
                                    checked = effectivePreheatEnabled,
                                    onCheckedChange = { if (!isPreheatForced) userPreheatPreference = it },
                                    enabled = !isPreheatForced
                                )
                            }
                            
                            if (effectivePreheatEnabled) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp, horizontal = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(
                                        "预热延迟",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium
                                    )
                                    NativeOutlinedTextField(
                                        value = preheatDelayMillis,
                                        onValueChange = { if (it.all { char -> char.isDigit() }) preheatDelayMillis = it },
                                        hint = "建议 300-3000",
                                        singleLine = true,
                                        modifier = Modifier.weight(1f).bringIntoViewRequester(preheatRequester),
                                        onFocusChange = { if (it) scope.launch { delay(300); preheatRequester.bringIntoView() } },
                                        inputType = android.text.InputType.TYPE_CLASS_NUMBER,
                                        trailingIcon = {
                                            Text(
                                                "ms", 
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(end = 12.dp)
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    } else {
                         // 桥接模式说明 (平铺)
                         Row(
                             modifier = Modifier
                                 .fillMaxWidth()
                                 .padding(vertical = 8.dp, horizontal = 2.dp), 
                             verticalAlignment = Alignment.CenterVertically
                         ) {
                             Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
                             Spacer(Modifier.width(12.dp))
                             Text(
                                 "桥接模式：解析后将根据得到的新链接重新匹配库中规则，因此无需在此设置目标应用。",
                                 style = MaterialTheme.typography.labelSmall,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant
                             )
                         }
                    }

                }
            }


            // 2. 匹配规则 (Matching Rules)
            EditSection(title = "2. 匹配规则") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // 模式选择器
                    Text("匹配模式", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PremiumSegmentedRow(
                        options = listOf(MatchType.CONTAINS, MatchType.EXACT, MatchType.REGEX),
                        selectedOption = matchType,
                        onOptionSelected = { matchType = it },
                        labelProvider = { mode ->
                            when (mode) {
                                MatchType.CONTAINS -> "域名/包含"
                                MatchType.EXACT -> "精确匹配"
                                MatchType.REGEX -> "正则表达式"
                            }
                        }
                    )

                    NativeOutlinedTextField(
                        value = pattern,
                        onValueChange = { pattern = it },
                        hint = when(matchType) {
                            MatchType.CONTAINS -> "匹配域名"
                            MatchType.EXACT -> "完整链接"
                            MatchType.REGEX -> "正则表达式"
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().bringIntoViewRequester(patternRequester),
                        onFocusChange = { if (it) scope.launch { delay(300); patternRequester.bringIntoView() } }
                    )
                    
                    // 新版双开关布局 (平铺无嵌套卡片，仅点击开关自身触发)
                    Text("核心跳转增强", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    
                    // 1. 解析短链接开关
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp, horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "解析短链接 (HTTP)", 
                                style = MaterialTheme.typography.bodyMedium, 
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "追踪 302 重定向并提取真实地址",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        PremiumSwitch(
                            checked = resolveShortLink,
                            onCheckedChange = { resolveShortLink = it }
                        )
                    }

                    // 2. 二次分发开关
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp, horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "二次规则分发", 
                                style = MaterialTheme.typography.bodyMedium, 
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "解析后将产物作为新链接重新匹配全库规则",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        PremiumSwitch(
                            checked = resolveStrategy == ResolutionStrategy.RE_DISPATCH,
                            onCheckedChange = { isChecked ->
                                resolveStrategy = if (isChecked) ResolutionStrategy.RE_DISPATCH 
                                else (if (resolveShortLink) ResolutionStrategy.DIRECT else ResolutionStrategy.NONE)
                            }
                        )
                    }

                    // 3. 跳转并复制开关
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp, horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "跳转并复制", 
                                style = MaterialTheme.typography.bodyMedium, 
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "跳转成功后自动将最终链接复制到剪贴板",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        PremiumSwitch(
                            checked = jumpAndCopy,
                            onCheckedChange = { jumpAndCopy = it }
                        )
                    }
                }
            }


            // 3. 重构模板 (Reconstruction)
                EditSection(title = "3. 重构模板 (可选)") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        NativeOutlinedTextField(
                            value = extractPattern,
                            onValueChange = { extractPattern = it },
                            hint = "正则提取(留空提取整个)",
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().bringIntoViewRequester(extractRequester),
                            onFocusChange = { if (it) scope.launch { delay(300); extractRequester.bringIntoView() } },
                            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                        )

                        NativeOutlinedTextField(
                            value = template,
                            onValueChange = { template = it },
                            hint = "重构链接(搭配变量使用)",
                            singleLine = false,
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth().bringIntoViewRequester(templateRequester),
                            onFocusChange = { if (it) scope.launch { delay(400); templateRequester.bringIntoView() } },
                            inputType = android.text.InputType.TYPE_CLASS_TEXT or 
                                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or 
                                    android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                        )

                        // --- 新增：变量后缀变换工具栏 ---
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "🛠️ 变换选项", 
                                    style = MaterialTheme.typography.labelMedium, 
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "(可嵌套处理，如先解码再编码)", 
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), 
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                val suffixOptions = listOf(
                                    "_url_enc" to "URL编码",
                                    "_url_dec" to "URL解码",
                                    "_b64_enc" to "B64编码",
                                    "_b64_dec" to "B64解码"
                                )
                                
                                suffixOptions.forEach { (suffix, label) ->
                                    Surface(
                                        onClick = { 
                                            val text = template.text
                                            val sel = template.selection
                                            val content = text
                                            val cursor = sel.start

                                            var startBracket = -1
                                            for (i in (cursor - 1) downTo 0) {
                                                if (content[i] == '{') { startBracket = i; break }
                                                if (content[i] == '}') break 
                                            }
                                            
                                            var endBracket = -1
                                            if (startBracket != -1) {
                                                for (i in startBracket until content.length) {
                                                    if (content[i] == '}') { endBracket = i; break }
                                                }
                                            }

                                            if (startBracket != -1 && endBracket != -1 && cursor >= startBracket && cursor <= endBracket + 1) {
                                                val before = content.substring(0, endBracket)
                                                val after = content.substring(endBracket)
                                                val newText = before + suffix + after
                                                // 光标在 } 左侧
                                                template = TextFieldValue(newText, TextRange(endBracket + suffix.length))
                                            } else {
                                                val tag = "{url$suffix}"
                                                val newText = content.substring(0, cursor) + tag + content.substring(sel.end)
                                                // 光标在 } 左侧
                                                template = TextFieldValue(newText, TextRange(cursor + tag.length - 1))
                                            }
                                        },
                                        modifier = Modifier.weight(1f).height(44.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center,
                                            modifier = Modifier.fillMaxSize().padding(horizontal = 2.dp)
                                        ) {
                                            Text(
                                                text = label, 
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold),
                                                maxLines = 1,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Spacer(modifier = Modifier.height(1.dp))
                                            Text(
                                                text = suffix,
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp, fontWeight = FontWeight.Normal),
                                                maxLines = 1,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 变量说明列表 (平铺无嵌套外框背景)
                        Text("💡 变量说明 (点击变量插入):", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 2.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val variables = listOf(
                                Triple("{url}", "完整原始链接", "https://example.com/p/123"),
                                Triple("{scheme}", "协议头 (Scheme)", "https, http, intent 等"),
                                Triple("{host}", "域名部分 (Host)", "example.com"),
                                Triple("{path}", "路径部分 (Path)", "/p/123"),
                                Triple("{query}", "参数串 (Query)", "id=123 (不含?)"),
                                Triple("{0}", "正则提取的全文", "匹配到的所有内容 (默认同{url})"),
                                Triple("{1}", "正则捕获组 1", "正则表达式中第 1 个 () 的内容"),
                                Triple("{2}", "正则捕获组 2", "依此类推 {3}, {4}...")
                            )

                            variables.forEach { (tag, desc, example) ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { 
                                            val text = template.text
                                            val sel = template.selection
                                            val newText = text.substring(0, sel.start) + tag + text.substring(sel.end)
                                            template = TextFieldValue(
                                                text = newText,
                                                selection = TextRange(sel.start + tag.length)
                                            )
                                        }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = tag,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = desc,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = "示例: $example",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

            Spacer(modifier = Modifier.height(20.dp))
            Spacer(modifier = Modifier.navigationBarsPadding()) // 确保内容不被小白条遮挡
        }

        // 5. 链接测试弹窗 (New BottomSheet)
        if (showTestBottomSheet) {
            ModalBottomSheet(
                onDismissRequest = { showTestBottomSheet = false },
                sheetState = sheetState,
                containerColor = MaterialTheme.colorScheme.surface,
                dragHandle = { BottomSheetDefaults.DragHandle() }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding() // 核心：随键盘升起，防止遮挡
                        .verticalScroll(rememberScrollState()) // 核心：支持滚动查看结果
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 1. 标题区
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Science, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.width(8.dp))
                        Text("链接测试", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    }
                    
                    // 2. 输入区
                    NativeOutlinedTextField(
                        value = testUrl,
                        onValueChange = { testUrl = it },
                        hint = "输入待测试的链接...",
                        singleLine = false,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // 3. 快捷操作按钮组
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { testUrl = "" },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("清空内容")
                        }

                        Button(
                            onClick = {
                                clipboardManager.getText()?.text?.let { testUrl = it }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("粘贴最新")
                        }
                    }

                    // 4. 转换管道流 (实时反馈)

                    if (testUrl.isNotBlank()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (isSimulating && testPipeline.isEmpty()) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
                                Text("正在探测转换路径...", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                            } else if (testPipeline.isNotEmpty()) {
                                testPipeline.forEachIndexed { index, result ->
                                    if (index > 0) {
                                        TransferIndicator(url = result.inputUrl)
                                    }
                                    TransformationNode(
                                        result = result, 
                                        isFirst = index == 0,
                                        jumpError = if (index == testPipeline.size - 1) testJumpError else null,
                                        isExecuting = isSimulating && index == testPipeline.size - 1
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // 5. 实战确认按钮
                        Button(
                            onClick = { 
                                testJumpError = null
                                
                                val currentEditingRule = DispatchRule(
                                    id = ruleId ?: "test_temp",
                                    name = name.ifBlank { "正在测试的规则" },
                                    pattern = pattern,
                                    targetPackage = if (cls.isNotBlank()) "$pkg/$cls" else pkg,
                                    template = template.text,
                                    matchType = matchType,
                                    extractPattern = extractPattern.ifBlank { null },
                                    resolveStrategy = resolveStrategy,
                                    ruleLaunchMode = ruleLaunchMode,
                                    isEnabled = true,
                                    resolveShortLink = resolveShortLink,
                                    excludeFromRecents = excludeFromRecents,
                                    isPreheatEnabled = effectivePreheatEnabled,
                                    preheatDelayMillis = preheatDelayMillis.toLongOrNull() ?: 0L,
                                    jumpAndCopy = jumpAndCopy
                                )

                                WindowRouter.launchRuleTestCascade(
                                    context = context,
                                    testUrl = testUrl,
                                    currentRule = currentEditingRule,
                                    allRules = rules,
                                    fallbackPkg = fallbackBrowser,
                                    config = windowConfig,
                                    isNormEnabled = normalizationEnabled,
                                    normRegex = normalizationRegex,
                                    normTemplate = normalizationTemplate,
                                    onStep = { /* 实时模式不再依赖此处回调驱动流 */ },
                                    onFinalResult = { success, error -> if (!success) testJumpError = error }
                                )
                            },
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape = RoundedCornerShape(14.dp),
                            enabled = testPipeline.isNotEmpty() && !testPipeline.last().isPatternMismatch
                        ) {
                            Icon(Icons.Default.Launch, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("执行最终跳转测试", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EditSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(modifier = Modifier.padding(16.dp)) {
                content()
            }
        }
    }
}

// --- 链接转换测试重构组件 ---

/**
 * 链接转换单级结果模型
 */
data class TransformationResult(
    val stageName: String,
    val inputUrl: String,
    val sourceAnnotated: AnnotatedString,
    val resultAnnotated: AnnotatedString,
    val resolvedAnnotated: AnnotatedString? = null, // 新增：解析后的带标注链接
    val colorMap: Map<String, Color>,
    val targetLabel: String? = null,
    val error: String? = null,
    val resolvedUrl: String? = null,
    val isDispatchOnly: Boolean = false,
    val isLibraryMiss: Boolean = false,
    val isPatternMismatch: Boolean = false,
    val displayInputUrl: String? = null,
    val engineResultUrl: String? = null,
    val extractPatternAnnotated: AnnotatedString? = null,
    val hasUrlEncode: Boolean = false,
    val hasUrlDecode: Boolean = false,
    val hasB64Encode: Boolean = false,
    val hasB64Decode: Boolean = false,
    val isExtracted: Boolean = false,
    val isResolved: Boolean = false,
    val isReconstructed: Boolean = false
)

private val VARIABLE_COLORS = listOf(
    Color(0xFF2196F3), Color(0xFFE91E63), Color(0xFFFF9800), 
    Color(0xFF9C27B0), Color(0xFF4CAF50), Color(0xFF00BCD4), 
    Color(0xFFFF5722), Color(0xFF3F51B5)
)

/**
 * 核心演算逻辑：计算单级 URL 转换的高亮与结果
 */
private fun calculateTransformation(
    inputUrl: String, 
    resolvedUrl: String? = null,
    rule: DispatchRule,
    stageName: String = "转换预览",
    skipMatchCheck: Boolean = false,
    engineResultUrl: String? = null // 新增：强制对齐引擎结果
): TransformationResult? {
    if (inputUrl.isBlank()) return null
    
    // 核心基准：解析后的 URL (若存在) 为重构基准
    val effectiveUrl = if (rule.resolveShortLink && resolvedUrl != null) resolvedUrl else inputUrl
    val decodedUrl = effectiveUrl 
    
    return try {
        val uri = Uri.parse(decodedUrl)
        val pattern = rule.pattern
        val matchType = rule.matchType
        val extractPattern = rule.extractPattern ?: ""
        val templateText = rule.template?.ifBlank { "{url}" } ?: "{url}"

        // 1. 匹配校验 (基于规则配置的初始匹配目标)
        var mismatchError: String? = null
        val matchTarget = if (rule.resolveShortLink && resolvedUrl != null) inputUrl else decodedUrl
        val isMatched = if (skipMatchCheck) true else when (matchType) {
            MatchType.REGEX -> {
                if (pattern.isBlank()) true else {
                    try {
                        java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.CASE_INSENSITIVE)
                            .matcher(matchTarget).find()
                    } catch (e: Exception) {
                        mismatchError = "正则表达式语法错误: ${e.message}"
                        false
                    }
                }
            }
            MatchType.EXACT -> matchTarget.equals(pattern, ignoreCase = true).also { if(!it) mismatchError = "链接与完整规则不完全匹配" }
            MatchType.CONTAINS -> {
                val host = try { Uri.parse(matchTarget).host } catch (e: Exception) { null }
                val matched = host != null && pattern.isNotBlank() && (
                    host.equals(pattern, ignoreCase = true) || 
                    host.endsWith(".$pattern", ignoreCase = true)
                )
                if (!matched) mismatchError = if (host == null) "无法识别 URL 域名" else "域名 '$host' 不匹配配置 '$pattern'"
                matched
            }
        }

        if (!isMatched) {
            return TransformationResult(
                stageName = stageName,
                inputUrl = inputUrl,
                sourceAnnotated = AnnotatedString(inputUrl),
                resultAnnotated = AnnotatedString(inputUrl),
                colorMap = emptyMap(),
                error = mismatchError ?: "匹配规则校验失败",
                isPatternMismatch = true
            )
        }

        val colorMap = mutableMapOf<String, Color>()
        var colorIdx = 0
        fun getColor(key: String): Color = colorMap.getOrPut(key) { VARIABLE_COLORS[colorIdx++ % VARIABLE_COLORS.size] }

        // 解析出当前模板真正使用的基础变量
        val usedBaseVars = mutableSetOf<String>()
        val varRegexForScan = java.util.regex.Pattern.compile("\\{([a-zA-Z0-9]+)((?:_url_enc|_url_dec|_b64_enc|_b64_dec)*)\\}")
        val varMatcherForScan = varRegexForScan.matcher(templateText)
        while (varMatcherForScan.find()) {
            usedBaseVars.add(varMatcherForScan.group(1)!!)
        }

        // 辅助方法：为指定 URL 生成标注
        fun buildAnnotated(targetUrl: String): AnnotatedString {
            return buildAnnotatedString {
                val uriForSource = try { Uri.parse(targetUrl) } catch(e: Exception) { null }
                val regex = extractPattern.takeIf { it.isNotBlank() }?.let {
                    try { java.util.regex.Pattern.compile(it, java.util.regex.Pattern.CASE_INSENSITIVE) } catch(e: Exception) { null }
                }
                val matcher = regex?.matcher(targetUrl)
                val hasMatch = matcher?.find() == true
                
                data class HighlightRange(val start: Int, val end: Int, val tag: String)
                val highlights = mutableListOf<HighlightRange>()
                if (hasMatch) {
                    for (i in 0..matcher!!.groupCount()) {
                        val s = matcher.start(i)
                        val e = matcher.end(i)
                        if (s >= 0 && e > s && usedBaseVars.contains(i.toString())) {
                            highlights.add(HighlightRange(s, e, "{$i}"))
                        }
                    }
                }
                if (usedBaseVars.contains("url")) highlights.add(HighlightRange(0, targetUrl.length, "{url}"))
                if (usedBaseVars.contains("scheme")) uriForSource?.scheme?.let { s -> targetUrl.indexOf(s).takeIf { it >= 0 }?.let { highlights.add(HighlightRange(it, it + s.length, "{scheme}")) } }
                if (usedBaseVars.contains("host")) uriForSource?.host?.let { h -> targetUrl.indexOf(h).takeIf { it >= 0 }?.let { highlights.add(HighlightRange(it, it + h.length, "{host}")) } }
                if (usedBaseVars.contains("path")) uriForSource?.path?.takeIf { it != "/" }?.let { p -> targetUrl.indexOf(p).takeIf { it >= 0 }?.let { highlights.add(HighlightRange(it, it + p.length, "{path}")) } }
                if (usedBaseVars.contains("query")) uriForSource?.query?.let { q -> targetUrl.indexOf(q).takeIf { it >= 0 }?.let { highlights.add(HighlightRange(it, it + q.length, "{query}")) } }
    
                append(targetUrl)
                highlights.forEach { h ->
                    addStyle(style = SpanStyle(background = getColor(h.tag).copy(alpha = 0.2f), fontWeight = FontWeight.Bold, color = getColor(h.tag)), start = h.start, end = h.end)
                }
            }
        }

        // 2. 确定各阶段标注内容
        val (sourceAnnotated, resolvedAnnotated) = if (rule.resolveShortLink && resolvedUrl != null) {
            // 模式 A: 发生了解析。原始链接保持纯净，标注应用在解析后的链接上
            AnnotatedString(inputUrl) to buildAnnotated(resolvedUrl)
        } else {
            // 模式 B: 未解析。标注直接应用在原始链接上
            buildAnnotated(inputUrl) to null
        }

        // 3. 构建重构结果高亮 (Result Highlight)
        val resultAnnotated = buildAnnotatedString {
            // 支持后缀的正则解析：{(变量名)(后缀链)}
            val varRegex = java.util.regex.Pattern.compile("\\{([a-zA-Z0-9]+)((?:_url_enc|_url_dec|_b64_enc|_b64_dec)*)\\}")
            val varMatcher = varRegex.matcher(templateText)
            val captureGroups = mutableMapOf<String, String>()
            var isRegexMatched = true
            if (extractPattern.isNotBlank()) {
                try {
                    val eRegex = java.util.regex.Pattern.compile(extractPattern, java.util.regex.Pattern.CASE_INSENSITIVE)
                    val m = eRegex.matcher(decodedUrl) 
                    if (m.find()) {
                        for (i in 0..m.groupCount()) {
                            captureGroups[i.toString()] = m.group(i) ?: ""
                        }
                    } else {
                        isRegexMatched = false
                    }
                } catch (e: Exception) {
                    isRegexMatched = false
                }
            }

            if (!isRegexMatched) {
                append(decodedUrl)
                return@buildAnnotatedString
            }

            // 基础变量库
            val baseVars = mutableMapOf<String, String>()
            baseVars["url"] = decodedUrl
            baseVars["0"] = decodedUrl
            if (uri != null) {
                baseVars["host"] = uri.host ?: ""
                baseVars["path"] = uri.path ?: ""
                baseVars["query"] = uri.query ?: ""
                baseVars["scheme"] = uri.scheme ?: ""
            }
            captureGroups.forEach { (k, v) -> baseVars[k] = v }

            data class TagRange(val start: Int, val end: Int, val tag: String)
            val tagRanges = mutableListOf<TagRange>()
            val sb = StringBuilder()
            var last = 0
            
            while (varMatcher.find()) {
                sb.append(templateText.substring(last, varMatcher.start()))
                val fullTag = varMatcher.group(0)!!
                val varName = varMatcher.group(1)!!
                val suffixChain = varMatcher.group(2) ?: ""
                
                // 执行变换逻辑 (与 WindowRouter 同步)
                var replacement = baseVars[varName] ?: ""
                if (suffixChain.isNotBlank()) {
                    val knownSuffixes = listOf("_url_enc", "_url_dec", "_b64_enc", "_b64_dec")
                    var currentChain = suffixChain
                    while (currentChain.isNotEmpty()) {
                        val nextAction = knownSuffixes.find { currentChain.startsWith(it) }
                        if (nextAction != null) {
                            replacement = when (nextAction) {
                                "_url_enc" -> try { java.net.URLEncoder.encode(replacement, "UTF-8") } catch(e: Exception) { replacement }
                                "_url_dec" -> try { java.net.URLDecoder.decode(replacement, "UTF-8") } catch(e: Exception) { replacement }
                                "_b64_enc" -> try { android.util.Base64.encodeToString(replacement.toByteArray(), android.util.Base64.NO_WRAP) } catch(e: Exception) { replacement }
                                "_b64_dec" -> try { String(android.util.Base64.decode(replacement, android.util.Base64.NO_WRAP)) } catch(e: Exception) { replacement }
                                else -> replacement
                            }
                            currentChain = currentChain.substring(nextAction.length)
                        } else {
                            currentChain = currentChain.substringAfter("_", "")
                        }
                    }
                }
                
                // 处理硬编码兼容变量的预览
                if (fullTag == "{url_encode}") replacement = try { java.net.URLEncoder.encode(decodedUrl, "UTF-8") } catch(e: Exception) { decodedUrl }
                if (fullTag == "{url_decode}") replacement = try { java.net.URLDecoder.decode(decodedUrl, "UTF-8") } catch(e: Exception) { decodedUrl }

                val start = sb.length
                sb.append(replacement)
                val end = sb.length
                // 使用基础变量名作为颜色映射的 key，确保上下区域颜色匹配
                tagRanges.add(TagRange(start, end, "{$varName}"))
                last = varMatcher.end()
            }
            sb.append(templateText.substring(last))
            val finalBase = engineResultUrl ?: sb.toString()
            append(finalBase)
            
            if (finalBase.length == sb.length) {
                tagRanges.forEach { range ->
                    if (range.start < range.end && range.end <= finalBase.length) {
                        addStyle(
                            style = SpanStyle(
                                background = getColor(range.tag).copy(alpha = 0.2f), 
                                fontWeight = FontWeight.Bold, 
                                color = getColor(range.tag)
                            ), 
                            start = range.start, 
                            end = range.end
                        )
                    }
                }
            }
        }

        // 4. 正则模式标注 (略，保持原有逻辑)
        val extractPatternAnnotated = if (extractPattern.isNotBlank()) {
            buildAnnotatedString {
                var groupIdx = 1
                val stack = mutableListOf<Pair<Int, Int>>() 
                var i = 0
                while (i < extractPattern.length) {
                    val char = extractPattern[i]
                    if (char == '\\' && i + 1 < extractPattern.length) {
                        append(extractPattern.substring(i, i + 2)); i += 2; continue
                    }
                    if (char == '(') {
                        if (i + 2 < extractPattern.length && extractPattern[i + 1] == '?') { append("(") }
                        else { val start = length; append("("); stack.add(groupIdx to start); groupIdx++ }
                    } else if (char == ')' && stack.isNotEmpty()) {
                        val (gId, start) = stack.removeAt(stack.size - 1); append(")"); val end = length; val color = getColor("{$gId}")
                        addStyle(SpanStyle(color = color, fontWeight = FontWeight.ExtraBold, background = color.copy(alpha = 0.15f)), start, end)
                    } else { append(char) }
                    i++
                }
            }
        } else null

        TransformationResult(
            stageName = stageName,
            inputUrl = inputUrl,
            sourceAnnotated = sourceAnnotated,
            resolvedAnnotated = resolvedAnnotated,
            resultAnnotated = resultAnnotated,
            colorMap = colorMap,
            targetLabel = rule.name,
            extractPatternAnnotated = extractPatternAnnotated,
            resolvedUrl = resolvedUrl
        )
    } catch (e: Exception) {
        null
    }
}

/**
 * 全量规格的链接转换管道节点
 */
@Composable
private fun TransformationNode(
    result: TransformationResult,
    isFirst: Boolean = false,
    isExecuting: Boolean = false,
    jumpError: String? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                RoundedCornerShape(16.dp)
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 1. 头部信息: 阶段名称与目标 App
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = if (isFirst) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.outline
                },
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = result.stageName,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.surface
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                result.targetLabel ?: "未知目标",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            
            Row(
                modifier = Modifier.padding(start = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (result.hasUrlEncode) StepBadge("编码", MaterialTheme.colorScheme.secondary)
                if (result.hasUrlDecode) StepBadge("解码", MaterialTheme.colorScheme.secondary)
                if (result.hasB64Encode) StepBadge("B64编码", MaterialTheme.colorScheme.secondary)
                if (result.hasB64Decode) StepBadge("B64解码", MaterialTheme.colorScheme.secondary)
                if (result.isExtracted) StepBadge("提取", MaterialTheme.colorScheme.secondary)
                if (result.isResolved) StepBadge("解析", MaterialTheme.colorScheme.secondary)
                if (result.isReconstructed) StepBadge("重构", MaterialTheme.colorScheme.secondary)
            }
            
            if (isExecuting) {
                Spacer(Modifier.weight(1f))
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }

        // 2. 转换详情 (如果不匹配则隐藏详情，仅保留错误提示)
        if (!result.isPatternMismatch) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LabCopyableSection(
                    title = "原始链接", 
                    content = result.sourceAnnotated,
                    copyText = result.sourceAnnotated.text
                )

                // 1.5 正则提取模式 (如果有且包含变量)
                if (result.extractPatternAnnotated != null) {
                    LabCopyableSection(
                        title = "正则提取模式",
                        content = result.extractPatternAnnotated,
                        copyText = result.extractPatternAnnotated.text
                    )
                }
                
                // 1. HTTP 解析结果 (对齐原始链接 UI)
                if (result.resolvedUrl != null && result.resolvedUrl != result.inputUrl) {
                    LabCopyableSection(
                        title = "HTTP 解析结果", 
                        content = result.resolvedAnnotated ?: AnnotatedString(result.resolvedUrl!!),
                        copyText = result.resolvedUrl!!
                    )
                }
                
                // 2. 变换/重构产物 (如果结果与解析前/解析后不同)
                val finalResult = result.resultAnnotated.text
                val compareBase = result.resolvedUrl ?: result.inputUrl
                if (finalResult != compareBase) {
                    LabCopyableSection(
                        title = "重构链接", 
                        content = result.resultAnnotated,
                        copyText = finalResult
                    )
                }
            }

            // 3. 变量对照表 (只要有变量就显示)
            if (result.colorMap.isNotEmpty()) {
                VariableTagList(colorMap = result.colorMap)
            }
        }

        // 4. 诊断卡片 (模式不匹配/语法错误/库未命中)
        if (result.isPatternMismatch || result.isLibraryMiss || jumpError != null) {
            Surface(
                color = if (jumpError != null || result.isPatternMismatch) 
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                else 
                    MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val icon = if (result.isLibraryMiss) Icons.Default.HelpOutline else Icons.Default.ReportProblem
                        val title = when {
                            result.isPatternMismatch -> "匹配模式不符"
                            result.isLibraryMiss -> "后续路径未命中规则库"
                            else -> "跳转实测失败"
                        }
                        val tint = if (jumpError != null || result.isPatternMismatch) 
                            MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary

                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(title, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = tint)
                    }
                    Spacer(Modifier.height(4.dp))
                    
                    val errorMsg = when {
                        result.isPatternMismatch -> result.error ?: "链接不满足当前规则的筛选条件。"
                        result.isLibraryMiss -> "分发链接在规则库中找不到后续接管者，将进入系统默认打开流程。"
                        else -> jumpError ?: ""
                    }
                    
                    Text(text = errorMsg, style = MaterialTheme.typography.bodySmall)

                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VariableTagList(colorMap: Map<String, Color>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        colorMap.forEach { (tag, color) ->
            Surface(
                color = color.copy(alpha = 0.1f),
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(0.5.dp, color.copy(alpha = 0.3f))
            ) {
                Row(modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(5.dp).background(color, CircleShape))
                    Spacer(Modifier.width(4.dp))
                    Text(tag, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = color, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun TransferIndicator(url: String) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 向下的指引箭头
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.ArrowDownward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(14.dp)
            )
        }
        
        Spacer(Modifier.width(12.dp))
        
        // 传递的链接摘要 (支持多行与点击复制)
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .weight(1f)
                .clickable {
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("URL", url))
                    android.widget.Toast.makeText(context, "已复制中间链接", android.widget.Toast.LENGTH_SHORT).show()
                }
        ) {
            Text(
                text = url,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                lineHeight = 14.sp
            )
        }
    }
}

@Composable
private fun StepBadge(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.1f),
        contentColor = color,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(0.5.dp, color.copy(alpha = 0.3f))
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold)
        )
    }
}

@Composable
private fun LabCopyableSection(
    title: String,
    content: AnnotatedString,
    copyText: String,
    isSuccessHighlight: Boolean = false
) {
    val context = LocalContext.current
    Surface(
        color = if (isSuccessHighlight) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.4f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().clickable { 
            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("URL", copyText))
            android.widget.Toast.makeText(context, "已复制$title", android.widget.Toast.LENGTH_SHORT).show()
        },
        border = if (isSuccessHighlight) BorderStroke(0.5.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)) else null
    ) {
        Column(modifier = Modifier.padding(6.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold, color = if (isSuccessHighlight) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline, fontSize = 8.sp)
            Text(
                text = content,
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 14.sp, fontSize = 11.sp),
                fontWeight = FontWeight.Medium,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = if (isSuccessHighlight) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface 
            )
        }
    }
}
