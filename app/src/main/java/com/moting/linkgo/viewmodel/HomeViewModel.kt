package com.moting.linkgo.viewmodel

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moting.linkgo.data.SettingsRepository
import com.moting.linkgo.model.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeViewModel : ViewModel() {
    private val _appList = MutableStateFlow<List<AppInfo>>(emptyList())
    val appList = _appList.asStateFlow()

    private val _fallbackBrowser = MutableStateFlow<String?>(com.moting.linkgo.data.SettingsCache.fallbackBrowser)
    val fallbackBrowser: StateFlow<String?> = _fallbackBrowser.asStateFlow()

    private val _fallbackAppInfo = MutableStateFlow<AppInfo?>(null)
    val fallbackAppInfo = _fallbackAppInfo.asStateFlow()

    private val _isDefaultBrowser = MutableStateFlow(false)
    val isDefaultBrowser = _isDefaultBrowser.asStateFlow()

    private val _fallbackWindowMode = MutableStateFlow(com.moting.linkgo.data.SettingsCache.fallbackWindowMode)
    val fallbackWindowMode: StateFlow<Int> = _fallbackWindowMode.asStateFlow()

    private val _fallbackPreheatEnabled = MutableStateFlow(com.moting.linkgo.data.SettingsCache.fallbackPreheatEnabled)
    val fallbackPreheatEnabled = _fallbackPreheatEnabled.asStateFlow()

    private val _fallbackPreheatDelay = MutableStateFlow(com.moting.linkgo.data.SettingsCache.fallbackPreheatDelay)
    val fallbackPreheatDelay = _fallbackPreheatDelay.asStateFlow()

    private val _totalJumpCount = MutableStateFlow(0)
    val totalJumpCount = _totalJumpCount.asStateFlow()

    private val _urlInput = MutableStateFlow("")
    val urlInput = _urlInput.asStateFlow()

    private val _matchingApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val matchingApps = _matchingApps.asStateFlow()

    private val _recommendedPackages = MutableStateFlow<Set<String>>(emptySet())
    val recommendedPackages = _recommendedPackages.asStateFlow()

    private val _latestRuleId = MutableStateFlow<String?>(null)
    val latestRuleId = _latestRuleId.asStateFlow()

    private val _totalRulesCount = MutableStateFlow(0)
    val totalRulesCount = _totalRulesCount.asStateFlow()

    private val _enabledRulesCount = MutableStateFlow(0)
    val enabledRulesCount = _enabledRulesCount.asStateFlow()

    private val _latestJumpInfo = MutableStateFlow<String?>(null)
    val latestJumpInfo = _latestJumpInfo.asStateFlow()

    private val _todayJumpCount = MutableStateFlow(0)
    val todayJumpCount = _todayJumpCount.asStateFlow()

    private val _fallbackExcludeFromRecents = MutableStateFlow(com.moting.linkgo.data.SettingsCache.fallbackExcludeFromRecents)
    val fallbackExcludeFromRecents = _fallbackExcludeFromRecents.asStateFlow()

    private val _clipboardMonitorEnabled = MutableStateFlow(com.moting.linkgo.data.SettingsCache.clipboardMonitorEnabled)
    val clipboardMonitorEnabled = _clipboardMonitorEnabled.asStateFlow()

    private val _clipboardMonitorBackend = MutableStateFlow(com.moting.linkgo.data.SettingsCache.clipboardMonitorBackend)
    val clipboardMonitorBackend = _clipboardMonitorBackend.asStateFlow()

    private val _appLinkCaptureMode = MutableStateFlow(com.moting.linkgo.data.SettingsCache.appLinkCaptureMode)
    val appLinkCaptureMode = _appLinkCaptureMode.asStateFlow()

    private val _appLinkCapturedAppsCount = MutableStateFlow(com.moting.linkgo.data.SettingsCache.appLinkCaptureApps.count { it.isEnabled })
    val appLinkCapturedAppsCount = _appLinkCapturedAppsCount.asStateFlow()

    private val _privilegeMode = MutableStateFlow("auto")
    val privilegeMode = _privilegeMode.asStateFlow()

    private val _edgeGestureConfig = MutableStateFlow(com.moting.linkgo.data.SettingsCache.edgeGestureConfig)
    val edgeGestureConfig = _edgeGestureConfig.asStateFlow()

    // 内部标准化与提取规则缓存
    private var normalizationEnabled = true
    private var extractionPatterns = com.moting.linkgo.model.ExtractPattern.builtinDefaults()
    private var normalizationTemplate = ""

    // 异步分析任务控制句柄，支持防抖与前置取消
    private var urlAnalysisJob: Job? = null

    private var repository: SettingsRepository? = null


    fun init(context: Context) {
        initRepository(context)
        refreshAllStatus(context)
    }

    fun refreshAllStatus(context: Context) {
        checkDefaultBrowserStatus(context)
        // 检查备选浏览器是否依然在
        _fallbackBrowser.value?.let { loadFallbackAppInfo(context, it) }
    }

    private fun checkDefaultBrowserStatus(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            _isDefaultBrowser.value = roleManager?.isRoleHeld(RoleManager.ROLE_BROWSER) == true
        }
    }

    private fun initRepository(context: Context) {
        if (repository == null) {
            repository = SettingsRepository(context.applicationContext)
            viewModelScope.launch {
                repository!!.fallbackBrowser.collectLatest { pkg ->
                    _fallbackBrowser.value = pkg
                    if (pkg != null) {
                        loadFallbackAppInfo(context, pkg)
                    } else {
                        _fallbackAppInfo.value = null
                    }
                }
            }
            viewModelScope.launch {
                repository!!.fallbackWindowMode.collectLatest { mode ->
                    _fallbackWindowMode.value = mode
                }
            }
            viewModelScope.launch {
                repository!!.fallbackPreheatEnabled.collectLatest { _fallbackPreheatEnabled.value = it }
            }
            viewModelScope.launch {
                repository!!.fallbackPreheatDelay.collectLatest { _fallbackPreheatDelay.value = it }
            }
            viewModelScope.launch {
                repository!!.totalJumpCount.collectLatest { count ->
                    _totalJumpCount.value = count
                }
            }
            viewModelScope.launch {
                repository!!.jumpHistory.collectLatest { history ->
                    val last = history.firstOrNull()
                    _latestRuleId.value = last?.ruleId
                    
                    // 格式化最近触发：日期 时间 规则名称
                    _latestJumpInfo.value = last?.let { record ->
                        val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                        val timeStr = sdf.format(Date(record.timestamp))
                        "$timeStr ${record.ruleName}"
                    }

                    // 计算今日触发数
                    launch(Dispatchers.Default) {
                        val calendar = java.util.Calendar.getInstance()
                        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
                        calendar.set(java.util.Calendar.MINUTE, 0)
                        calendar.set(java.util.Calendar.SECOND, 0)
                        calendar.set(java.util.Calendar.MILLISECOND, 0)
                        val todayStart = calendar.timeInMillis
                        _todayJumpCount.value = history.count { it.timestamp >= todayStart }
                    }
                }
            }
            viewModelScope.launch {
                repository!!.dispatchRules.collectLatest { rules ->
                    _totalRulesCount.value = rules.size
                    _enabledRulesCount.value = rules.count { it.isEnabled }
                }
            }
            viewModelScope.launch {
                repository!!.normalizationEnabled.collectLatest { normalizationEnabled = it }
            }
            viewModelScope.launch {
                repository!!.extractionPatterns.collectLatest { extractionPatterns = it }
            }
            viewModelScope.launch {
                repository!!.normalizationTemplate.collectLatest { normalizationTemplate = it }
            }
            viewModelScope.launch {
                repository!!.fallbackExcludeFromRecents.collectLatest { _fallbackExcludeFromRecents.value = it }
            }
            viewModelScope.launch {
                repository!!.clipboardMonitorEnabled.collectLatest { _clipboardMonitorEnabled.value = it }
            }
            viewModelScope.launch {
                repository!!.clipboardMonitorBackend.collectLatest { _clipboardMonitorBackend.value = it }
            }
            viewModelScope.launch {
                repository!!.appLinkCaptureMode.collectLatest { _appLinkCaptureMode.value = it }
            }
            viewModelScope.launch {
                repository!!.appLinkCaptureApps.collectLatest { apps ->
                    _appLinkCapturedAppsCount.value = apps.count { it.isEnabled }
                }
            }
            viewModelScope.launch {
                repository!!.privilegeMode.collectLatest { _privilegeMode.value = it }
            }
            viewModelScope.launch {
                repository!!.edgeGestureConfigFlow.collectLatest { config ->
                    _edgeGestureConfig.value = config
                }
            }
        }
    }

    private fun loadFallbackAppInfo(context: Context, packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pm = context.packageManager
                val info = pm.getApplicationInfo(packageName, 0)
                _fallbackAppInfo.value = AppInfo(
                    label = info.loadLabel(pm).toString(),
                    packageName = packageName
                )
            } catch (e: Exception) {
                _fallbackAppInfo.value = null
            }
        }
    }

    fun toggleFallbackBrowser(packageName: String) {
        viewModelScope.launch {
            val current = _fallbackBrowser.value
            if (packageName.isBlank() || current == packageName) {
                repository?.updateFallbackBrowser(null)
            } else {
                repository?.updateFallbackBrowser(packageName)
            }
        }
    }

    fun updateFallbackWindowMode(mode: Int) {
        viewModelScope.launch {
            repository?.updateFallbackWindowMode(mode)
        }
    }

    fun updateFallbackPreheatEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository?.updateFallbackPreheatEnabled(enabled)
        }
    }

    fun updateFallbackPreheatDelay(delay: Long) {
        viewModelScope.launch {
            repository?.updateFallbackPreheatDelay(delay)
        }
    }

    fun updateFallbackExcludeFromRecents(exclude: Boolean) {
        viewModelScope.launch {
            repository?.updateFallbackExcludeFromRecents(exclude)
        }
    }

    fun loadInstalledApps(packageManager: PackageManager, context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            // 构造浏览器特征 Intent
            val browserIntent = Intent(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                data = Uri.parse("http://")
            }
            
            val resolveInfos = packageManager.queryIntentActivities(
                browserIntent, 
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PackageManager.MATCH_ALL else 0
            )
            
            val selfPackageName = context.packageName
            val apps = resolveInfos
                .map { resolveInfo ->
                    AppInfo(
                        label = resolveInfo.loadLabel(packageManager).toString(),
                        packageName = resolveInfo.activityInfo.packageName,
                        className = resolveInfo.activityInfo.name
                    )
                }
                .filter { it.packageName != selfPackageName } // 过滤掉自身
                .distinctBy { it.packageName } // 包名去重
                .sortedBy { it.label.lowercase() }
            
            _appList.value = apps
            
            // 如果当前输入为空，默认显示基础列表
            if (_urlInput.value.isBlank()) {
                _matchingApps.value = apps
            }
        }
    }

    fun onUrlChange(context: Context, url: String) {
        _urlInput.value = url
        if (url.isBlank()) {
            urlAnalysisJob?.cancel()
            _matchingApps.value = _appList.value
            _recommendedPackages.value = emptySet()
            return
        }

        // 1. 标准化预处理 (应用智能分流策略)
        val isPure = com.moting.linkgo.util.UrlUtils.isPureUrl(url)
        val standardized = if (normalizationEnabled && !isPure) {
            // 用第一条启用的提取规则提取出链接，再用 normalizationTemplate 进行标准化重构
            val firstUrl = com.moting.linkgo.util.UrlUtils.extractAllUrls(url, extractionPatterns).firstOrNull() ?: url
            com.moting.linkgo.util.UrlUtils.performNormalization(firstUrl, com.moting.linkgo.util.UrlUtils.DEFAULT_REGEX, normalizationTemplate)
        } else {
            url.trim()
        }

        // 2. 根据标准化后的内容进行后续分析
        val extractedUrl = extractUrl(standardized)
        val uri = try { Uri.parse(extractedUrl) } catch (e: Exception) { null }
        if (uri == null || uri.scheme == null) {
            urlAnalysisJob?.cancel()
            _matchingApps.value = _appList.value
            _recommendedPackages.value = emptySet()
            return
        }

        // 取消前一个并发分析任务，防止频繁击键带来并发峰值
        urlAnalysisJob?.cancel()
        urlAnalysisJob = viewModelScope.launch(Dispatchers.IO) {
            val pm = context.packageManager
            val selfPackageName = context.packageName
            val host = uri.host

            // === 第一层：DomainVerificationManager (Android 12+) ===
            // 查找在 Manifest 中声明了当前域名的 App（即使未通过 App Links 验证）
            val domainDeclaredPackages = mutableSetOf<String>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !host.isNullOrBlank()) {
                try {
                    val manager = context.getSystemService(
                        android.content.pm.verify.domain.DomainVerificationManager::class.java
                    )
                    for (pkg in pm.getInstalledPackages(0)) {
                        if (pkg.packageName == selfPackageName) continue
                        try {
                            val userState = manager.getDomainVerificationUserState(pkg.packageName) ?: continue
                            if (userState.hostToStateMap.keys.any { declared ->
                                host.equals(declared, ignoreCase = true) ||
                                host.endsWith(".$declared", ignoreCase = true)
                            }) {
                                domainDeclaredPackages.add(pkg.packageName)
                            }
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }

            // === 第二层：queryIntentActivities 标准查询 ===
            val intent = Intent(Intent.ACTION_VIEW, uri)
            var resolveInfos = pm.queryIntentActivities(
                intent,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PackageManager.MATCH_ALL else 0
            ).toMutableList()

            // 兜底策略：如果特定链接无 App 响应，回退至协议级查询
            if (resolveInfos.isEmpty()) {
                val schemeOnlyUri = try { Uri.parse("${uri.scheme}://") } catch (e: Exception) { null }
                if (schemeOnlyUri != null) {
                    val fallbackIntent = Intent(Intent.ACTION_VIEW, schemeOnlyUri)
                    resolveInfos.addAll(pm.queryIntentActivities(
                        fallbackIntent,
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PackageManager.MATCH_ALL else 0
                    ))
                }
            }

            // 去重：同一包名优先保留声明了特定域名（authority）的 ResolveInfo
            val allInfos = resolveInfos
                .sortedByDescending { (it.filter?.countDataAuthorities() ?: 0) > 0 }
                .distinctBy { it.activityInfo.packageName }

            // === 合并两个来源 ===
            val queryPackages = allInfos.map { it.activityInfo.packageName }.toSet()
            val additionalApps = mutableListOf<AppInfo>()
            for (pkg in domainDeclaredPackages) {
                if (pkg !in queryPackages) {
                    try {
                        val appInfo = pm.getApplicationInfo(pkg, 0)
                        val label = appInfo.loadLabel(pm).toString()
                        val launcherIntent = pm.getLaunchIntentForPackage(pkg)
                        val launcherCls = launcherIntent?.component?.className
                        var targetCls: String? = null

                        // 核心增强：通过 DomainVerificationManager 反向探测类名 (Android 12+)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            val manager = context.getSystemService(android.content.pm.verify.domain.DomainVerificationManager::class.java)
                            val userState = manager.getDomainVerificationUserState(pkg)
                            val domains = userState?.hostToStateMap?.keys ?: emptySet()
                            
                            for (domain in domains) {
                                val testIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://$domain"))
                                val resolves = pm.queryIntentActivities(testIntent, PackageManager.MATCH_ALL)
                                val match = resolves.firstOrNull { it.activityInfo.packageName == pkg }
                                if (match != null) {
                                    targetCls = match.activityInfo.name
                                    if (targetCls != launcherCls) break
                                }
                            }
                        }

                        additionalApps.add(AppInfo(
                            label = label,
                            packageName = pkg,
                            className = targetCls ?: launcherCls
                        ))
                    } catch (_: Exception) {}
                }
            }

            // 计算推荐应用集合：域名声明应用 + 带有特定 host 过滤器的应用
            val recommendedSet = mutableSetOf<String>()
            recommendedSet.addAll(domainDeclaredPackages.filter { it != selfPackageName })
            
            allInfos.forEach { info ->
                val pkg = info.activityInfo.packageName
                if (pkg != selfPackageName && pkg != "android" && (info.filter?.countDataAuthorities() ?: 0) > 0) {
                    recommendedSet.add(pkg)
                }
            }
            _recommendedPackages.value = recommendedSet

            // 计算第一个展示的首选应用（用于列表置顶排序）
            val firstPreferred = domainDeclaredPackages.firstOrNull { it != selfPackageName }
                ?: allInfos.firstOrNull { info ->
                    info.activityInfo.packageName != selfPackageName &&
                    info.activityInfo.packageName != "android" &&
                    (info.filter?.countDataAuthorities() ?: 0) > 0
                }?.activityInfo?.packageName
                ?: allInfos.firstOrNull { it.activityInfo.packageName != selfPackageName }?.activityInfo?.packageName

            // 组合最终展示列表 (针对已匹配的 App 也执行启动页排除)
            val queryApps = allInfos
                .map { info ->
                    val pkg = info.activityInfo.packageName
                    val launcherIntent = pm.getLaunchIntentForPackage(pkg)
                    val launcherCls = launcherIntent?.component?.className
                    
                    // 如果当前 Activity 是启动页，尝试寻找该包名下是否有更好的 Activity
                    val targetCls = if (info.activityInfo.name == launcherCls) {
                        // 逻辑：如果已经从 queryIntentActivities 拿到了多个，选非启动页的
                        val others = resolveInfos.filter { it.activityInfo.packageName == pkg && it.activityInfo.name != launcherCls }
                        others.firstOrNull()?.activityInfo?.name ?: info.activityInfo.name
                    } else {
                        info.activityInfo.name
                    }

                    AppInfo(
                        label = info.loadLabel(pm).toString(),
                        packageName = pkg,
                        className = targetCls
                    )
                }
                .filter { it.packageName != selfPackageName }

            _matchingApps.value = (additionalApps + queryApps)
                .distinctBy { it.packageName }
                .sortedWith(
                    compareByDescending<AppInfo> { it.packageName == firstPreferred }
                        .thenByDescending { it.packageName in recommendedSet }
                        .thenBy { it.label }
                )
        }
    }

    fun addRuleForApp(app: AppInfo, onComplete: (String, String, String) -> Unit) {
        val url = _urlInput.value
        val uri = try { Uri.parse(url) } catch (e: Exception) { null }
        
        // 允许 host 为空的情况（使用 scheme 作为默认特征）
        val pattern = uri?.host ?: uri?.scheme ?: ""
        
        // 我们改为通过路由传递预填信息，而不立即存入数据库
        val target = if (!app.className.isNullOrBlank()) {
            "${app.packageName}/${app.className}"
        } else {
            app.packageName
        }
        
        // 传递：名称，特征，包名
        onComplete(app.label, pattern, target)
    }



    private fun extractUrl(text: String): String {
        // 鲁棒正则：符合 RFC 3986 规范，支持 a-z 开头的任意协议，包含下划线、点号等所有合法 URL 字符
        val regex = Regex("[a-zA-Z0-9+.-]+:(//)?[\\w.\\-.~:/?#\\[\\]@!$&'()*+,;=%]+")
        return regex.find(text)?.value ?: text
    }
}
