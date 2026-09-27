package com.moting.linkgo

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.moting.linkgo.data.PackageRepository
import com.moting.linkgo.data.SettingsRepository
import androidx.compose.material3.MaterialTheme
import com.moting.linkgo.ui.components.AppFloatingActionButton
import com.moting.linkgo.ui.components.SpeedDialFloatingActionButton
import com.moting.linkgo.ui.components.SpeedDialItem
import com.moting.linkgo.ui.history.HistoryDetailScreen
import com.moting.linkgo.ui.history.HistoryScreen
import com.moting.linkgo.ui.home.HelpDocScreen
import com.moting.linkgo.ui.home.HomeScreen
import com.moting.linkgo.ui.rules.ActivityPickerScreen
import com.moting.linkgo.ui.rules.AppPickerScreen
import com.moting.linkgo.ui.rules.EditImageRuleScreen
import com.moting.linkgo.ui.rules.EditRuleScreen
import com.moting.linkgo.ui.rules.ExtractionSettingsScreen
import com.moting.linkgo.ui.rules.RulesScreen
import com.moting.linkgo.ui.settings.AboutScreen
import com.moting.linkgo.ui.settings.BackupSettingsScreen
import com.moting.linkgo.ui.settings.ClipboardMonitorSettingsScreen
import com.moting.linkgo.ui.settings.InterfaceSettingsScreen
import com.moting.linkgo.ui.settings.InteractionSettingsScreen
import com.moting.linkgo.ui.settings.NotificationSettingsScreen
import com.moting.linkgo.ui.settings.QuickCommandsSettingsScreen
import com.moting.linkgo.ui.settings.SettingsScreen
import com.moting.linkgo.ui.settings.SmallWindowSettingsScreen
import com.moting.linkgo.ui.theme.链接跳转Theme
import com.moting.linkgo.viewmodel.RulesViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val rulesViewModel: RulesViewModel by viewModels()
    private var shouldNavigateToRules by mutableStateOf(false)
    private var shouldNavigateToSettings by mutableStateOf(false)
    private var pendingRulePrefill by mutableStateOf<RulePrefill?>(null)

    data class RulePrefill(val url: String, val pkg: String, val name: String? = null)

    private val shizukuBinderReceived = rikka.shizuku.Shizuku.OnBinderReceivedListener {
        // Shizuku 粘性 Binder 建立握手
    }
    private val shizukuBinderDead = rikka.shizuku.Shizuku.OnBinderDeadListener {
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rikka.shizuku.Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceived)
        rikka.shizuku.Shizuku.addBinderDeadListener(shizukuBinderDead)
        handleIntent(intent)
        
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val repository = remember { SettingsRepository(context) }
            val dynamicColorEnabled by repository.dynamicColorEnabled.collectAsState(initial = true)

            LaunchedEffect(Unit) {
                rulesViewModel.uiEvent.collect { message ->
                    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            链接跳转Theme(dynamicColor = dynamicColorEnabled) {
                MainScreen(
                    rulesViewModel = rulesViewModel,
                    shouldNavigateToRules = shouldNavigateToRules,
                    onNavigatedToRules = { shouldNavigateToRules = false },
                    shouldNavigateToSettings = shouldNavigateToSettings,
                    onNavigatedToSettings = { shouldNavigateToSettings = false },
                    pendingRulePrefill = pendingRulePrefill,
                    onNavigatedToCreateRule = { pendingRulePrefill = null }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 1. 受能力开关「自愈·应用内自愈」约束：关闭后切前台不再自动拉起无障碍（独立协程）
        if (com.moting.linkgo.util.privilege.CapabilityCenter.isEnabled(
                com.moting.linkgo.util.privilege.CapabilityCenter.Capability.A11Y_HEAL_APP
            )
        ) {
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                com.moting.linkgo.util.AccessibilityUtils.autoHealService(this@MainActivity)
            }
        }

        // 2. 剪贴板后台监听切前台健康探针兜底（独立协程，互不干扰）
        lifecycleScope.launch {
            try {
                val isEnabled = com.moting.linkgo.data.SettingsCache.clipboardMonitorEnabled
                val backend = com.moting.linkgo.data.SettingsCache.clipboardMonitorBackend
                if (isEnabled && com.moting.linkgo.clipboard.ClipboardBackend.needsForegroundService(backend)) {
                    val isShizuku = backend == com.moting.linkgo.clipboard.ClipboardBackend.SHIZUKU_HIDDEN_API ||
                            backend == com.moting.linkgo.clipboard.ClipboardBackend.SHIZUKU_LOGS
                    val needHeal = !com.moting.linkgo.service.ClipboardMonitorService.isRunning ||
                            (isShizuku && com.moting.linkgo.service.ShizukuManager.getState() != com.moting.linkgo.service.ShizukuManager.State.BOUND)
                    if (needHeal) {
                        Log.i("MainActivity", "切前台探针检测到剪贴板服务状态异常，触发自愈恢复: backend=$backend")
                        com.moting.linkgo.clipboard.ClipboardMonitorController.apply(this@MainActivity, true, backend, force = true)
                    }
                }
            } catch (e: Exception) {
                Log.w("MainActivity", "切前台剪贴板自愈检查异常: ${e.message}")
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        rikka.shizuku.Shizuku.removeBinderReceivedListener(shizukuBinderReceived)
        rikka.shizuku.Shizuku.removeBinderDeadListener(shizukuBinderDead)
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        
        // 1. 优先处理来自 RuleImportActivity 的强制导入指令
        val forceImportText = intent.getStringExtra("FORCE_IMPORT_TEXT")
        if (forceImportText != null) {
            rulesViewModel.initRepository(this)
            if (rulesViewModel.prepareImport(forceImportText)) {
                shouldNavigateToRules = true
            } else {
                android.widget.Toast.makeText(this, "解析失败: 无法识别有效的规则格式", android.widget.Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (Intent.ACTION_VIEW == action) {
            intent.data?.let { uri ->
                rulesViewModel.initRepository(this)
                rulesViewModel.importRulesFromUri(this, uri)
                shouldNavigateToRules = true
            }
        } 
        // 处理 ACTION_SEND (外部分享文件或文本)
        else if (Intent.ACTION_SEND == action) {
            val stream = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            }

            if (stream != null) {
                rulesViewModel.initRepository(this)
                rulesViewModel.importRulesFromUri(this, stream)
                shouldNavigateToRules = true
            } else {
                // 处理分享的纯文本
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!text.isNullOrBlank()) {
                    rulesViewModel.initRepository(this)
                    if (rulesViewModel.prepareImport(text.trim())) {
                        shouldNavigateToRules = true
                    } else {
                        android.widget.Toast.makeText(this, "解析失败: 无法识别有效的规则格式", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else if (intent.action == "com.moting.linkgo.ACTION_CREATE_RULE") {
            val url = intent.getStringExtra("EXTRA_URL")
            val pkg = intent.getStringExtra("EXTRA_PACKAGE")
            val name = intent.getStringExtra("EXTRA_NAME")
            if (url != null && pkg != null) {
                pendingRulePrefill = RulePrefill(url, pkg, name)
            }
        } else if (intent.action == "com.moting.linkgo.ACTION_OPEN_SETTINGS") {
            shouldNavigateToSettings = true
        }
    }
}

data class NavigationItem(
    val title: String,
    val route: String,
    val icon: ImageVector
)

@Composable
fun MainScreen(
    rulesViewModel: RulesViewModel,
    shouldNavigateToRules: Boolean = false,
    onNavigatedToRules: () -> Unit = {},
    shouldNavigateToSettings: Boolean = false,
    onNavigatedToSettings: () -> Unit = {},
    pendingRulePrefill: MainActivity.RulePrefill? = null,
    onNavigatedToCreateRule: () -> Unit = {}
) {
    val context = LocalContext.current
    val repository = remember { SettingsRepository(context) }
    val excludeFromRecents by repository.excludeFromRecents.collectAsState(
        initial = repository.currentExcludeFromRecents
    )

    LaunchedEffect(excludeFromRecents) {
        val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        try {
            am.appTasks.forEach { it.setExcludeFromRecents(excludeFromRecents) }
        } catch (e: Exception) {
            android.util.Log.e("MainScreen", "设置任务隐藏失败", e)
        }
    }

    var showVisibilityDialog by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1000)
        if (PackageRepository.isVisibilityRestricted(context)) {
            showVisibilityDialog = true
        }
    }

    if (showVisibilityDialog) {
        VisibilityGuidanceDialog(
            onDismiss = { showVisibilityDialog = false },
            onGoToSettings = {
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
                showVisibilityDialog = false
            }
        )
    }

    val navController = rememberNavController()

    LaunchedEffect(pendingRulePrefill) {
        if (pendingRulePrefill != null) {
            val url = pendingRulePrefill.url
            val pkg = pendingRulePrefill.pkg
            val name = pendingRulePrefill.name
            navController.navigate("edit_rule?prePattern=${Uri.encode(url)}&prePackage=${Uri.encode(pkg)}&preName=${Uri.encode(name ?: "")}&preIsFromSelector=true")
            onNavigatedToCreateRule()
        }
    }

    NavHost(
        navController = navController,
        startDestination = "main",
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        enterTransition = {
            slideInHorizontally(initialOffsetX = { it / 4 }, animationSpec = tween(350, easing = FastOutSlowInEasing)) + fadeIn(animationSpec = tween(350))
        },
        exitTransition = {
            slideOutHorizontally(targetOffsetX = { -it }, animationSpec = tween(350, easing = FastOutSlowInEasing)) + fadeOut(animationSpec = tween(350))
        },
        popEnterTransition = {
            slideInHorizontally(initialOffsetX = { -it }, animationSpec = tween(350, easing = FastOutSlowInEasing)) + fadeIn(animationSpec = tween(350))
        },
        popExitTransition = {
            slideOutHorizontally(targetOffsetX = { it / 4 }, animationSpec = tween(350, easing = FastOutSlowInEasing)) + fadeOut(animationSpec = tween(350))
        }
    ) {
        composable("main") {
            MainPagerScreen(
                navController = navController,
                rulesViewModel = rulesViewModel,
                shouldNavigateToRules = shouldNavigateToRules,
                onNavigatedToRules = onNavigatedToRules,
                shouldNavigateToSettings = shouldNavigateToSettings,
                onNavigatedToSettings = onNavigatedToSettings
            )
        }

        composable("extraction_settings") {
            Box(Modifier.zIndex(1f)) {
                ExtractionSettingsScreen(
                    viewModel = rulesViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }

        composable(
            route = "edit_rule?ruleId={ruleId}&preName={preName}&prePattern={prePattern}&prePackage={prePackage}&preIsFromSelector={preIsFromSelector}&preStrategy={preStrategy}",
            arguments = listOf(
                navArgument("ruleId") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("preName") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("prePattern") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("prePackage") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("preIsFromSelector") { type = NavType.BoolType; defaultValue = false },
                navArgument("preStrategy") { type = NavType.StringType; nullable = true; defaultValue = null }
            )
        ) { backStackEntry ->
            val ruleId = backStackEntry.arguments?.getString("ruleId")
            val preName = backStackEntry.arguments?.getString("preName")
            val prePattern = backStackEntry.arguments?.getString("prePattern")
            val prePackage = backStackEntry.arguments?.getString("prePackage")
            val preIsFromSelector = backStackEntry.arguments?.getBoolean("preIsFromSelector") ?: false
            val preStrategy = backStackEntry.arguments?.getString("preStrategy")
            val selectedPkg = backStackEntry.savedStateHandle.get<String>("selected_package")
            val selectedCls = backStackEntry.savedStateHandle.get<String>("selected_activity")
            val selectedAppName = backStackEntry.savedStateHandle.get<String>("selected_app_name")
            
            Box(Modifier.zIndex(1f)) {
                EditRuleScreen(
                    ruleId = ruleId,
                    preName = preName,
                    prePattern = prePattern,
                    prePackage = prePackage,
                    preIsFromSelector = preIsFromSelector,
                    preStrategy = preStrategy,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToAppPicker = { onlyApp -> 
                        val route = if (onlyApp) "app_picker?onlyApp=true" else "app_picker"
                        navController.navigate(route) 
                    },
                    onNavigateToActivityPicker = { pkg -> navController.navigate("activity_picker/$pkg/未知应用") },
                    selectedPackage = selectedPkg,
                    selectedActivity = selectedCls,
                    selectedAppName = selectedAppName,
                    onClearBackStackData = { key ->
                        backStackEntry.savedStateHandle.remove<String>(key)
                    }
                )
            }
        }

        // 图片规则编辑页：结构与文本规则编辑页一致，但只有「基本信息」与「图片跳转」两块
        composable(
            route = "edit_image_rule?ruleId={ruleId}",
            arguments = listOf(
                navArgument("ruleId") { type = NavType.StringType; nullable = true; defaultValue = null }
            )
        ) { backStackEntry ->
            val imageRuleId = backStackEntry.arguments?.getString("ruleId")
            Box(Modifier.zIndex(1f)) {
                EditImageRuleScreen(
                    ruleId = imageRuleId,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
        composable(
            route = "app_picker?onlyApp={onlyApp}&multi={multi}",
            arguments = listOf(
                navArgument("onlyApp") { type = NavType.BoolType; defaultValue = false },
                navArgument("multi") { type = NavType.BoolType; defaultValue = false }
            )
        ) { backStackEntry ->
            val onlyApp = backStackEntry.arguments?.getBoolean("onlyApp") ?: false
            val multi = backStackEntry.arguments?.getBoolean("multi") ?: false
            val customPreselected = navController.previousBackStackEntry?.savedStateHandle?.get<List<String>>("preselected_packages")?.toSet()
            val defaultPreselected = if (multi) com.moting.linkgo.data.SettingsCache.enabledCapturePackageNames.toSet().ifEmpty { com.moting.linkgo.data.SettingsCache.appLinkCaptureApps.map { it.packageName }.toSet() } else emptySet()
            Box(Modifier.zIndex(1f)) {
                AppPickerScreen(
                    onlyApp = onlyApp,
                    multiSelect = multi,
                    preselectedPackages = customPreselected ?: defaultPreselected,
                    onAppSelected = { pkg, label ->
                        if (onlyApp) {
                            val entry = navController.previousBackStackEntry
                            if (entry != null) {
                                entry.savedStateHandle.set("selected_package", pkg)
                                entry.savedStateHandle.set("selected_app_name", label)
                            }
                            navController.popBackStack()
                        } else {
                            navController.navigate("activity_picker/$pkg/$label") {
                                popUpTo("app_picker") { inclusive = true }
                            }
                        }
                    },
                    onAppsSelected = { apps ->
                        val entry = navController.previousBackStackEntry
                        if (entry != null) {
                            entry.savedStateHandle.set("selected_apps", ArrayList(apps.map { it.first }))
                        }
                        navController.popBackStack()
                    },
                    onNavigateToActivityPicker = { pkg ->
                        navController.navigate("activity_picker/$pkg/未知应用") {
                            popUpTo("app_picker") { inclusive = true }
                        }
                    },
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }

        composable(
            route = "activity_picker/{packageName}/{appName}",
            arguments = listOf(
                navArgument("packageName") { type = NavType.StringType },
                navArgument("appName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val pkg = backStackEntry.arguments?.getString("packageName") ?: ""
            val appName = backStackEntry.arguments?.getString("appName") ?: "未知应用"

            // 未选择活动直接返回（箭头 / 系统返回键 / 手势）：仍注入已选应用包名，便于编辑页回填 pkg 字段。
            // 应用名仅在真实存在时注入，避免直接路径的占位符「未知应用」污染规则名。
            val backWithPackageInjection: () -> Unit = {
                val entry = navController.previousBackStackEntry
                if (entry != null) {
                    entry.savedStateHandle.set("selected_package", pkg)
                    if (appName.isNotBlank() && appName != "未知应用") {
                        entry.savedStateHandle.set("selected_app_name", appName)
                    }
                    entry.savedStateHandle.set("selected_activity", null)
                }
                navController.popBackStack()
            }

            // 拦截系统返回键/手势：默认行为会直接 popBackStack 而丢失包名注入
            BackHandler(enabled = true, onBack = backWithPackageInjection)

            Box(Modifier.zIndex(1f)) {
                ActivityPickerScreen(
                    packageName = pkg,
                    onActivitySelected = { cls ->
                        val entry = navController.previousBackStackEntry
                        if (entry != null) {
                            entry.savedStateHandle.set("selected_package", pkg)
                            entry.savedStateHandle.set("selected_activity", cls)
                            entry.savedStateHandle.set("selected_app_name", appName)
                        }
                        navController.popBackStack()
                    },
                    onNavigateBack = backWithPackageInjection
                )
            }
        }

        composable("settings_small_window") {
            Box(Modifier.zIndex(1f)) {
                SmallWindowSettingsScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }

        composable("settings_interface") {
            Box(Modifier.zIndex(1f)) {
                InterfaceSettingsScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }

        composable("settings_notification") {
            Box(Modifier.zIndex(1f)) {
                NotificationSettingsScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }

        composable("settings_interaction") {
            Box(Modifier.zIndex(1f)) {
                InteractionSettingsScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }

        composable("settings_edge_gesture") { backStackEntry ->
            val pickerApps by backStackEntry.savedStateHandle.getStateFlow<List<String>?>("selected_apps", null)
                .collectAsState()
            Box(Modifier.zIndex(1f)) {
                com.moting.linkgo.ui.settings.EdgeGestureSettingsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToAppPicker = { preselected ->
                        navController.currentBackStackEntry?.savedStateHandle?.set("preselected_packages", preselected.toList())
                        navController.navigate("app_picker?onlyApp=true&multi=true")
                    },
                    selectedAppsFromPicker = pickerApps,
                    onClearPickerResult = {
                        backStackEntry.savedStateHandle.remove<List<String>>("selected_apps")
                    }
                )
            }
        }

        composable("settings_clipboard_monitor") {
            Box(Modifier.zIndex(1f)) {
                ClipboardMonitorSettingsScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }

        composable("settings_app_link_capture") { backStackEntry ->
            val pickerApps by backStackEntry.savedStateHandle.getStateFlow<List<String>?>("selected_apps", null)
                .collectAsState()
            Box(Modifier.zIndex(1f)) {
                com.moting.linkgo.ui.settings.AppLinkCaptureSettingsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToMultiAppPicker = { navController.navigate("app_picker?onlyApp=true&multi=true") },
                    pickerSelectedApps = pickerApps,
                    onAppsPickerConsumed = {
                        backStackEntry.savedStateHandle.remove<List<String>>("selected_apps")
                    }
                )
            }
        }

        composable("settings_permission_center") {
            Box(Modifier.zIndex(1f)) {
                com.moting.linkgo.ui.settings.permission.PermissionCenterScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }

        composable("settings_external_call") {
            Box(Modifier.zIndex(1f)) {
                QuickCommandsSettingsScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }

        composable("about") {
            Box(Modifier.zIndex(1f)) {
                AboutScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }

        composable("backup") {
            Box(Modifier.zIndex(1f)) {
                BackupSettingsScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }

        composable("sponsor") {
            Box(Modifier.zIndex(1f)) {
                com.moting.linkgo.ui.settings.SponsorScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }

        composable("history") {
            Box(Modifier.zIndex(1f)) {
                HistoryScreen(
                    onNavigateToDetail = { traceId ->
                        navController.navigate("history_detail?traceId=$traceId")
                    },
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }

        composable(
            route = "history_detail?traceId={traceId}",
            arguments = listOf(
                navArgument("traceId") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            val traceId = backStackEntry.arguments?.getString("traceId") ?: ""
            Box(Modifier.zIndex(1f)) {
                HistoryDetailScreen(
                    traceId = traceId,
                    onNavigateBack = { navController.popBackStack() },
                    onLocateRule = { ruleId ->
                        rulesViewModel.locateRule(ruleId)
                        navController.popBackStack("main", inclusive = false)
                    }
                )
            }
        }

        composable("help_doc") {
            Box(Modifier.zIndex(1f)) {
                HelpDocScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun MainPagerScreen(
    navController: NavController,
    rulesViewModel: RulesViewModel,
    shouldNavigateToRules: Boolean,
    onNavigatedToRules: () -> Unit,
    shouldNavigateToSettings: Boolean,
    onNavigatedToSettings: () -> Unit
) {
    var isRulesSpecialMode by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val repository = remember { SettingsRepository(context) }
    val floatingBottomBarEnabled by repository.floatingBottomBarEnabledFlow.collectAsState(initial = repository.currentFloatingBottomBar)

    val items = listOf(
        NavigationItem("首页", "home", ImageVector.vectorResource(id = R.drawable.ic_iconoir_home)),
        NavigationItem("记录", "history", ImageVector.vectorResource(id = R.drawable.ic_iconoir_clock)),
        NavigationItem("规则", "rules", ImageVector.vectorResource(id = R.drawable.ic_iconoir_task_list)),
        NavigationItem("设置", "settings", ImageVector.vectorResource(id = R.drawable.ic_iconoir_settings))
    )

    val pagerState = rememberPagerState(initialPage = 0, pageCount = { items.size })
    val coroutineScope = rememberCoroutineScope()

    val scrollToTab: (Int) -> Unit = { page ->
        coroutineScope.launch {
            pagerState.animateScrollToPage(page)
        }
    }

    LaunchedEffect(Unit) {
        rulesViewModel.locateRuleEvent.collect {
            pagerState.animateScrollToPage(2)
        }
    }

    LaunchedEffect(shouldNavigateToRules) {
        if (shouldNavigateToRules) {
            pagerState.animateScrollToPage(2)
            onNavigatedToRules()
        }
    }

    LaunchedEffect(shouldNavigateToSettings) {
        if (shouldNavigateToSettings) {
            pagerState.animateScrollToPage(3)
            onNavigatedToSettings()
        }
    }

    val isImeVisible = WindowInsets.isImeVisible
    val showBottomBar = !isRulesSpecialMode && !isImeVisible
    val animBottomPadding by animateDpAsState(
        targetValue = if (!showBottomBar) 0.dp else if (floatingBottomBarEnabled) BottomBarMetrics.ContentBottomPadding else 80.dp,
        animationSpec = tween(durationMillis = 200),
        label = "BottomBarPadding"
    )

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.statusBars,
        bottomBar = {
            if (!floatingBottomBarEnabled) {
                AnimatedVisibility(
                    visible = showBottomBar,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                ) {
                    Surface(
                        color = if (isSystemInDarkTheme()) Color(0xFF242528) else Color.White,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        NavigationBar(
                            containerColor = Color.Transparent,
                            tonalElevation = 0.dp,
                            modifier = Modifier
                                .navigationBarsPadding()
                                .height(BottomBarMetrics.BarHeight),
                            windowInsets = WindowInsets(0, 0, 0, 0)
                        ) {
                            items.forEachIndexed { index, item ->
                                val selected = pagerState.currentPage == index
                                NavigationBarItem(
                                    icon = {
                                        Icon(
                                            item.icon,
                                            contentDescription = item.title,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    },
                                    label = {
                                        Text(
                                            item.title,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    selected = selected,
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                        unselectedIconColor = if (isSystemInDarkTheme()) Color.White else Color.Black,
                                        unselectedTextColor = if (isSystemInDarkTheme()) Color.White else Color.Black
                                    ),
                                    onClick = {
                                        scrollToTab(index)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                userScrollEnabled = !isRulesSpecialMode,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    0 -> {
                        HomeScreen(
                            bottomPadding = animBottomPadding,
                            onNavigateToRules = { scrollToTab(2) },
                            onNavigateToEditRule = { ruleId, name, pattern, pkg ->
                                val base = if (ruleId != null) "edit_rule?ruleId=$ruleId" else "edit_rule"
                                val query = buildString {
                                    var hasQuestionMark = base.contains("?")
                                    fun appendParam(key: String, value: String?) {
                                        if (value != null) {
                                            append(if (hasQuestionMark) "&" else "?")
                                            append("$key=${Uri.encode(value)}")
                                            hasQuestionMark = true
                                        }
                                    }
                                    appendParam("preName", name)
                                    appendParam("prePattern", pattern)
                                    appendParam("prePackage", pkg)
                                }
                                navController.navigate("$base$query")
                            },
                            onNavigateToSettings = { scrollToTab(3) },
                            onNavigateToHistory = { scrollToTab(1) },
                            onNavigateToHelp = {
                                navController.navigate("help_doc")
                            },
                            onNavigateToClipboardMonitor = {
                                navController.navigate("settings_clipboard_monitor")
                            },
                            onNavigateToAppLinkCapture = {
                                navController.navigate("settings_app_link_capture")
                            },
                            onNavigateToEdgeGesture = {
                                navController.navigate("settings_edge_gesture")
                            }
                        )
                    }
                    1 -> {
                        HistoryScreen(
                            bottomPadding = animBottomPadding,
                            onNavigateToDetail = { traceId ->
                                navController.navigate("history_detail?traceId=$traceId")
                            },
                            onNavigateBack = { scrollToTab(0) }
                        )
                    }
                    2 -> {
                        RulesScreen(
                            viewModel = rulesViewModel,
                            bottomPadding = animBottomPadding,
                            onNavigateToEditRule = { ruleId ->
                                navController.navigate(if (ruleId != null) "edit_rule?ruleId=$ruleId" else "edit_rule")
                            },
                            onNavigateToExtractionSettings = {
                                navController.navigate("extraction_settings")
                            },
                            onModeChange = { isRulesSpecialMode = it },
                            onNavigateToEditImageRule = { imageRuleId ->
                                navController.navigate(
                                    if (imageRuleId != null) "edit_image_rule?ruleId=$imageRuleId" else "edit_image_rule"
                                )
                            }
                        )
                    }
                    3 -> {
                        SettingsScreen(
                            bottomPadding = animBottomPadding,
                            onNavigateToInterfaceSettings = { navController.navigate("settings_interface") },
                            onNavigateToNotificationSettings = { navController.navigate("settings_notification") },
                            onNavigateToInteractionSettings = { navController.navigate("settings_interaction") },
                            onNavigateToSmallWindow = { navController.navigate("settings_small_window") },
                            onNavigateToEdgeGesture = { navController.navigate("settings_edge_gesture") },
                            onNavigateToClipboardMonitor = { navController.navigate("settings_clipboard_monitor") },
                            onNavigateToAppLinkCapture = { navController.navigate("settings_app_link_capture") },
                            onNavigateToPermissionCenter = { navController.navigate("settings_permission_center") },
                            onNavigateToExternalCall = { navController.navigate("settings_external_call") },
                            onNavigateToBackup = { navController.navigate("backup") },
                            onNavigateToSponsor = { navController.navigate("sponsor") },
                            onNavigateToAbout = { navController.navigate("about") },
                            onNavigateBack = { scrollToTab(0) }
                        )
                    }
                }
            }

            // 悬浮底栏模式 (1:1 像素级复刻 zhaozhao 规范)
            if (floatingBottomBarEnabled) {
                AnimatedVisibility(
                    visible = showBottomBar,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    FloatingBottomNavBar(
                        items = items,
                        currentPage = pagerState.currentPage,
                        onItemSelected = { index ->
                            scrollToTab(index)
                        }
                    )
                }
            }

            // 新增规则 Speed Dial 菜单展开状态
            var isSpeedDialExpanded by remember { mutableStateOf(false) }
            // 在规则页面显示全局添加 FAB (未处于批量选择或排序模式时)
            val showGlobalFab = (pagerState.currentPage == 2) && !isRulesSpecialMode

            // 当切页或进入特殊模式时，自动收起 Speed Dial
            LaunchedEffect(showGlobalFab) {
                if (!showGlobalFab) {
                    isSpeedDialExpanded = false
                }
            }

            SpeedDialFloatingActionButton(
                expanded = isSpeedDialExpanded,
                onExpandedChange = { isSpeedDialExpanded = it },
                visible = showGlobalFab,
                bottomPadding = animBottomPadding + 16.dp,
                endPadding = 16.dp,
                contentDescription = "添加规则",
                items = listOf(
                    SpeedDialItem(
                        label = "分发规则",
                        imageVector = ImageVector.vectorResource(id = R.drawable.ic_hub_primary),
                        iconTint = MaterialTheme.colorScheme.primary,
                        onClick = {
                            navController.navigate("edit_rule?preStrategy=RE_DISPATCH")
                        }
                    ),
                    SpeedDialItem(
                        label = "跳转规则",
                        imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_link),
                        iconTint = MaterialTheme.colorScheme.secondary,
                        onClick = {
                            navController.navigate("edit_rule?preStrategy=NONE")
                        }
                    ),
                    SpeedDialItem(
                        label = "图片规则",
                        imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_camera),
                        iconTint = MaterialTheme.colorScheme.tertiary,
                        onClick = {
                            navController.navigate("edit_image_rule")
                        }
                    )
                )
            )
        }
    }
}

/**
 * 主界面底部导航栏尺寸约定（悬浮/全宽共用，对齐 zhaozhao）
 */
object BottomBarMetrics {
    /** 底栏栏体高度 */
    val BarHeight = 64.dp

    /** 悬浮模式下底栏相对屏幕底部的上下悬浮边距 */
    val FloatingMargin = 12.dp

    /** 页面列表底部内容预留 = 栏高 + 上下悬浮边距 + 24dp 安全间距 */
    val ContentBottomPadding = BarHeight + FloatingMargin * 2 + 24.dp
}

/**
 * 悬浮胶囊底栏 (1:1 像素级复刻 zhaozhao 设计)：居中自适应宽度胶囊 + 滑动选中胶囊 + 精准 1dp 垂直间距
 */
@Composable
fun FloatingBottomNavBar(
    items: List<NavigationItem>,
    currentPage: Int,
    onItemSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = MaterialTheme.colorScheme.surfaceContainer
    val indicatorColor = MaterialTheme.colorScheme.primary
    val contentColor = MaterialTheme.colorScheme.onSurface
    val isDark = isSystemInDarkTheme()
    val density = LocalDensity.current

    val tabWidthDp = 76.dp
    val tabWidthPx = with(density) { tabWidthDp.toPx() }
    val targetOffset = currentPage * tabWidthPx

    var isInitialized by remember { mutableStateOf(false) }
    var lastRenderedPage by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(currentPage) }

    // 指示器平滑滑动 (初次挂载或页面未改变时瞬时定位，切换时平滑滑动)
    val indicatorOffsetPx by animateFloatAsState(
        targetValue = targetOffset,
        animationSpec = if (!isInitialized || lastRenderedPage == currentPage) {
            androidx.compose.animation.core.snap()
        } else {
            tween(durationMillis = 250, easing = FastOutSlowInEasing)
        },
        label = "floatingBarIndicator"
    )

    LaunchedEffect(currentPage) {
        if (!isInitialized) {
            isInitialized = true
        }
        lastRenderedPage = currentPage
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = BottomBarMetrics.FloatingMargin),
        contentAlignment = Alignment.BottomCenter
    ) {
        // 悬浮胶囊外壳 (双重物理立体弥散阴影 + 微轮廓)
        Box(
            modifier = Modifier
                .width(IntrinsicSize.Min)
                .drawBehind {
                    val shadowColor = if (isDark) {
                        android.graphics.Color.argb(160, 0, 0, 0)
                    } else {
                        android.graphics.Color.argb(58, 0, 0, 0)
                    }
                    val shadowRadius = with(density) { 14.dp.toPx() }
                    val shadowDy = with(density) { 4.dp.toPx() }

                    drawIntoCanvas { canvas ->
                        val paint = android.graphics.Paint().apply {
                            color = android.graphics.Color.TRANSPARENT
                            isAntiAlias = true
                            setShadowLayer(shadowRadius, 0f, shadowDy, shadowColor)
                        }
                        val frameworkCanvas = canvas.nativeCanvas
                        frameworkCanvas.drawRoundRect(
                            0f,
                            0f,
                            size.width,
                            size.height,
                            size.height / 2f,
                            size.height / 2f,
                            paint
                        )
                    }
                }
                .shadow(
                    elevation = 12.dp,
                    shape = CircleShape,
                    spotColor = if (isDark) Color.Black.copy(alpha = 0.6f) else Color.Black.copy(alpha = 0.25f),
                    ambientColor = if (isDark) Color.Black.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.15f)
                )
                .clip(CircleShape)
                .then(
                    if (isDark) {
                        Modifier.border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                    } else {
                        Modifier.border(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f), CircleShape)
                    }
                )
                .background(containerColor)
        ) {
            // 内容层（栏体 64dp、内边距 4dp；drawBehind 绘制滑动选中胶囊）
            Box(
                modifier = Modifier
                    .height(BottomBarMetrics.BarHeight)
                    .padding(4.dp)
                    .drawBehind {
                        if (tabWidthPx > 0f) {
                            drawRoundRect(
                                color = indicatorColor.copy(alpha = 0.30f),
                                topLeft = Offset(indicatorOffsetPx, 0f),
                                size = Size(tabWidthPx, size.height),
                                cornerRadius = CornerRadius(size.height / 2f)
                            )
                        }
                    }
            ) {
                // Tab 内容：图标 + 1dp 间距 + 文字
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items.forEachIndexed { index, item ->
                        val selected = currentPage == index
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .defaultMinSize(minWidth = 76.dp)
                                .clip(RoundedCornerShape(28.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { onItemSelected(index) }
                                ),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.title,
                                modifier = Modifier.size(20.dp),
                                tint = if (selected) indicatorColor else contentColor
                            )
                            Spacer(modifier = Modifier.height(1.dp))
                            Text(
                                text = item.title,
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) indicatorColor else contentColor,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun VisibilityGuidanceDialog(
    onDismiss: () -> Unit,
    onGoToSettings: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Rounded.Security,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = "需要应用列表权限",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "LinkGo 需要获取已安装应用列表才能为您提供跳转服务。在某些系统上，此权限可能被默认禁止（包可见性受限）。",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "提示：请在设置中将“读取应用列表”设为允许",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(12.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onGoToSettings,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("前往设置")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("稍后再说")
            }
        },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
    )
}
