package com.moting.linkgo.util

import android.graphics.drawable.Drawable
import com.moting.linkgo.clipboard.ClipboardHandler
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.graphics.Rect
import android.view.Surface
import android.view.WindowManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lsposed.hiddenapibypass.HiddenApiBypass
import com.moting.linkgo.data.SettingsRepository
import com.moting.linkgo.data.PackageRepository
import com.moting.linkgo.model.MatchType
import com.moting.linkgo.model.ResolutionStrategy
import com.moting.linkgo.model.DispatchRule
import java.util.UUID
import kotlinx.coroutines.withTimeoutOrNull
import com.moting.linkgo.util.NotificationHelper
import com.moting.linkgo.util.ShareOpenResultManager
import com.moting.linkgo.util.ShareOpenResult
import okhttp3.OkHttpClient
import okhttp3.Request
import android.app.PendingIntent
import java.util.concurrent.TimeUnit
import android.content.ComponentName
import android.content.pm.PackageManager
import android.provider.Settings
import android.app.SearchManager
import android.os.Bundle
import android.os.Process
import kotlinx.coroutines.delay
import com.moting.linkgo.util.privilege.PrivilegeEngine


object WindowRouter {
    private const val TAG = "WindowRouter"
    const val NUBIA_WINDOW_REPLY_IDENTIFIER = "_WindowReply"
    const val MAX_DISPATCH_DEPTH = 4

    private val patternCache = android.util.LruCache<String, java.util.regex.Pattern>(64)

    fun getCompiledPattern(regex: String, flags: Int = java.util.regex.Pattern.CASE_INSENSITIVE): java.util.regex.Pattern? {
        val key = "$flags:$regex"
        return patternCache.get(key) ?: try {
            val compiled = java.util.regex.Pattern.compile(regex, flags)
            patternCache.put(key, compiled)
            compiled
        } catch (e: Exception) {
            null
        }
    }

    private val globalScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
    
    private val httpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()


    suspend fun shareOpenIntent(
        context: Context,
        targetIntent: Intent,
        timeoutMillis: Long = 10_000L,
        addNewTask: Boolean = true
    ): ShareOpenResult {
        val requestId = UUID.randomUUID().toString()

        val intent = Intent(Intent.ACTION_SEND).apply {
            component = android.content.ComponentName(context, com.moting.linkgo.ShareOpenAppProxyActivity::class.java)
            type = "text/plain"
            putExtra("REQUEST_ID", requestId)
            putExtra(Intent.EXTRA_INTENT, targetIntent)
            
            // 核心修正点1：如果使用了 setClassName 导致 .package 为空，必须补全 fallback
            val pkgName = targetIntent.`package` ?: targetIntent.component?.packageName ?: ""
            putExtra(Intent.EXTRA_TEXT, pkgName)
            if (addNewTask) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        val chooser = Intent.createChooser(intent, null).apply {
            if (addNewTask) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        context.startActivity(chooser)

        return withTimeoutOrNull(timeoutMillis) {
            ShareOpenResultManager.awaitResult(requestId)
        } ?: ShareOpenResult(
            requestId = requestId,
            success = false,
            data = null
        )
    }

    /**
     * 以小窗模式启动应用
     */
    fun launchInSmallWindow(context: Context, packageName: String) {
        val appContext = context.applicationContext // 强化隔离，避免使用 Activity Context 导致的任务关联
        val repository = SettingsRepository(appContext)

        CoroutineScope(Dispatchers.Main).launch {
            try {
                // 1. 获取用户自定义配置
                val config = repository.windowConfig.first()
                val packageManager = appContext.packageManager

                // 解析包名和类名
                val (pkg, cls) = if (packageName.contains("/")) {
                    packageName.substringBefore("/") to packageName.substringAfter("/")
                } else {
                    packageName to null
                }

                val launchIntent = packageManager.getLaunchIntentForPackage(pkg)

                if (launchIntent == null) {
                    Toast.makeText(appContext, "无法启动该应用：未找到入口", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                if (cls != null) {
                    launchIntent.setClassName(pkg, cls)
                }

                // 2. 补全组件名
                val resolveInfo = packageManager.resolveActivity(launchIntent, 0)
                if (resolveInfo != null) {
                    launchIntent.setClassName(resolveInfo.activityInfo.packageName, resolveInfo.activityInfo.name)
                }

                // 3. 【核心修正】强制执行任务栈隔离，解决错位 Bug
                // 必须包含 NEW_TASK 和 MULTIPLE_TASK 以确保目标在全新栈中启动，不挤占 LinkGo
                launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK or 
                                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT

                // 3.5 模式 4：基于 Chooser 代理提权的 OriginOS 小窗方案
                if (config.windowingMode == 4) {
                    val realLaunchIntent = Intent(launchIntent).apply {
                        // 核心修正点2：强制剥离 NEW_TASK 和 MULTIPLE_TASK，防止目标应用逃逸小窗容器
                        flags = flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv()
                        flags = flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK.inv()
                    }
                    val result = shareOpenIntent(appContext, realLaunchIntent)
                    Log.d(TAG, "Mode 4 (Proxy) 启动完成, 成功状态: ${result.success}")
                    return@launch
                }

                // 3.6 模式 6：努比亚 MyOS 小窗回复 (Intent Identifier 方案)
                if (config.windowingMode == 6) {
                    launchIntent.identifier = NUBIA_WINDOW_REPLY_IDENTIFIER
                    Log.d(TAG, "Mode 6 (Nubia) 命中，注入 identifier=_WindowReply，使用空 Bundle 启动")
                    appContext.startActivity(launchIntent, Bundle())
                    Log.d(TAG, "Mode 6 (Nubia) 启动完成: $packageName")
                    return@launch
                }

                // 4. 计算当前屏幕下的物理 Bounds (使用 MaximumWindowMetrics 保证与预览器物理基准绝对 1:1)
                // 注意：获取 Display 必须使用 visual Context (Activity)，不能使用 appContext
                // 4. 【终极重构】直接通过宽高比判定当前视觉方向，彻底解决锁定竖屏 Activity 的干扰
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val (physicalW, physicalH) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val m = wm.maximumWindowMetrics
                    m.bounds.width() to m.bounds.height()
                } else {
                    val display = wm.defaultDisplay
                    val realSize = Point()
                    display.getRealSize(realSize)
                    realSize.x to realSize.y
                }

                // 获取当前窗口真实宽高（感知旋转）
                val (visualW, visualH) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val m = wm.currentWindowMetrics
                    m.bounds.width() to m.bounds.height()
                } else {
                    val display = wm.defaultDisplay
                    val size = Point()
                    display.getSize(size)
                    size.x to size.y
                }

                val isPortraitReal = visualW < visualH
                
                // 强制对齐基准：竖屏时 effectiveWidth 为短边，横屏时为长边
                val minD = Math.min(physicalW, physicalH)
                val maxD = Math.max(physicalW, physicalH)
                val effectiveWidth = if (isPortraitReal) minD else maxD
                val effectiveHeight = if (isPortraitReal) maxD else minD

                val options = ActivityOptions.makeBasic()
                
                // 4.5 精确解禁 ActivityOptions (即时防止厂商二次拦截)
                Log.d(TAG, "SDK Level: ${Build.VERSION.SDK_INT}, 准备解禁 ActivityOptions")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try { HiddenApiBypass.addHiddenApiExemptions("Landroid/app/ActivityOptions") } catch (e: Exception) {}
                }

                try {
                    val method = options.javaClass.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                    method.isAccessible = true
                    method.invoke(options, config.windowingMode)
                    
                    // 华为/荣耀专项适配：注入 StackId = 2
                    if (config.windowingMode != 1 && isHuaweiOrHonor()) {
                        try {
                            val stackMethod = options.javaClass.getMethod("setLaunchStackId", Int::class.javaPrimitiveType)
                            stackMethod.isAccessible = true
                            stackMethod.invoke(options, 2)
                            Log.d(TAG, "已为华为/荣耀设备注入 StackId=2")
                        } catch (e: Exception) { Log.e(TAG, "StackId 设置失败", e) }
                    }
                } catch (e: Exception) { Log.e(TAG, "WindowingMode 设置失败", e) }

                // 5. 应用自定义 Bounds (统一横竖屏逻辑)
                options.launchBounds = config.calculateBounds(effectiveWidth, effectiveHeight, isPortraitReal)
                Log.d(TAG, "最终 Bounds: ${options.launchBounds} | 方向: ${if(isPortraitReal) "竖" else "横"}")

                // 6. 厂商特定适配 (Xiaomi)
                adaptManufacturerFlags(launchIntent, config.windowingMode)

                // 7. 发起跳转
                appContext.startActivity(launchIntent, options.toBundle())
                Log.d(TAG, "指令已发送: $packageName")

            } catch (e: Exception) {
                Log.e(TAG, "启动异常", e)
                Toast.makeText(appContext, "启动失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    enum class DispatchResult {
        SUCCESS,
        FAIL,
        NEED_SELECTOR
    }

    /**
     * 路由结果封装
     */
    data class RouteResult(
        val status: DispatchResult,
        val finalUrl: String,
        val traceId: String,
        val stepIndex: Int
    )

    /**
     * 分派来源类型
     */
    enum class DispatchSource {
        DIRECT, // 系统浏览器直接跳转 (VIEW)
        SMART   // 内容智能分流 (SEND / 手动输入 / 首页粘贴)
    }

    /**
     * 预测结果封装
     */
    /**
     * 预测结果封装
     */
    data class PredictedTarget(
        val label: String, 
        val packageName: String?,
        val isReDispatch: Boolean = false,
        val wasDistributed: Boolean = false,
        val iconPath: String? = null
    )

    /**
     * 极速本地预测（纯本地规则匹配，0ms 网络等待），用于胶囊和通知的毫秒级首帧秒弹
     */
    suspend fun predictTargetFastLocal(context: Context, url: String): PredictedTarget {
        val appContext = context.applicationContext
        val repository = SettingsRepository(appContext)
        val rules = repository.dispatchRules.first()
        val fallbackPkg = repository.fallbackBrowser.first()
        val isNormEnabled = repository.normalizationEnabled.first()
        val normRegex = repository.normalizationRegex.first()
        val normTemplate = repository.normalizationTemplate.first()

        val standardizedUrl = standardizeUrl(
            url = url,
            isSmart = true,
            isNormEnabled = isNormEnabled,
            normRegex = normRegex,
            normTemplate = normTemplate
        )

        val matchedRule = rules.filter { it.isEnabled && it.pattern.isNotBlank() }.find { rule ->
            rule.resolveStrategy == ResolutionStrategy.RE_DISPATCH && matchRule(standardizedUrl, rule)
        } ?: rules.filter { it.isEnabled && it.pattern.isNotBlank() }.find { rule ->
            matchRule(standardizedUrl, rule)
        }

        return if (matchedRule != null) {
            val isReDispatch = matchedRule.resolveStrategy == ResolutionStrategy.RE_DISPATCH || matchedRule.targetPackage.isBlank()
            val realPkg = if (!isReDispatch && matchedRule.targetPackage.isNotBlank()) {
                val pkg = matchedRule.targetPackage
                if (pkg.contains("/")) pkg.substringBefore("/") else pkg
            } else if (!isReDispatch) {
                if (!fallbackPkg.isNullOrBlank() && fallbackPkg.contains("/")) fallbackPkg.substringBefore("/") else fallbackPkg
            } else null
            PredictedTarget(matchedRule.name, realPkg, isReDispatch, isReDispatch, matchedRule.iconPath)
        } else {
            val realPkg = if (!fallbackPkg.isNullOrBlank() && fallbackPkg.contains("/")) fallbackPkg.substringBefore("/") else (fallbackPkg ?: "")
            val pm = appContext.packageManager
            val label = try {
                val info = pm.getApplicationInfo(realPkg ?: "", 0)
                info.loadLabel(pm).toString()
            } catch (e: Exception) { "浏览器" }
            PredictedTarget(label, realPkg, false, false, null)
        }
    }

    /**
     * 预测 URL 的最终跳转目标详情（以 Flow 形式返回：先发射本地极速首帧，再发射网络深度解析结果）
     */
    fun predictTargetFlow(context: Context, url: String): Flow<PredictedTarget> = flow {
        // 1. 立即发射本地秒弹首帧 (< 5ms)
        val fastTarget = predictTargetFastLocal(context, url)
        emit(fastTarget)

        // 2. 后台异步执行深度解析（若有短链解析需求）
        val deepTarget = predictTargetDeepResolve(context, url)
        if (deepTarget != fastTarget) {
            emit(deepTarget)
        }
    }.distinctUntilChanged()

    /**
     * 完整深度预测（含短链网络追踪与多层分发）
     */
    suspend fun predictTargetDeepResolve(context: Context, url: String): PredictedTarget {
        val appContext = context.applicationContext
        val repository = SettingsRepository(appContext)
        val rules = repository.dispatchRules.first()
        val fallbackPkg = repository.fallbackBrowser.first()
        val isNormEnabled = repository.normalizationEnabled.first()
        val normRegex = repository.normalizationRegex.first()
        val normTemplate = repository.normalizationTemplate.first()

        var currentUrl = url
        var lastMatchedRule: com.moting.linkgo.model.DispatchRule? = null
        var iterationCount = 0
        var wasDistributed = false
        val visitedUrls = mutableSetOf<String>()

        while (iterationCount < MAX_DISPATCH_DEPTH) {
            if (!visitedUrls.add(currentUrl)) {
                Log.w(TAG, "预测检测到循环跳转环: $currentUrl, 终止循环")
                break
            }

            val standardizedUrl = standardizeUrl(
                url = currentUrl,
                isSmart = true,
                isNormEnabled = isNormEnabled,
                normRegex = normRegex,
                normTemplate = normTemplate
            )

            val matchedRule = rules.filter { it.isEnabled && it.pattern.isNotBlank() }.find { rule ->
                rule.resolveStrategy == ResolutionStrategy.RE_DISPATCH && matchRule(standardizedUrl, rule)
            } ?: rules.filter { it.isEnabled && it.pattern.isNotBlank() }.find { rule ->
                matchRule(standardizedUrl, rule)
            }

            if (matchedRule == null) {
                break
            }
            lastMatchedRule = matchedRule
            
            if (matchedRule.resolveStrategy == ResolutionStrategy.RE_DISPATCH) {
                wasDistributed = true
            }

            val transformedUrl = applyRuleTransform(standardizedUrl, matchedRule)
            if (matchedRule.resolveStrategy == ResolutionStrategy.RE_DISPATCH) {
                if (transformedUrl.isNotBlank() && transformedUrl != currentUrl) {
                    currentUrl = transformedUrl
                    iterationCount++
                    continue
                } else break
            } else {
                break
            }
        }

        return if (lastMatchedRule != null) {
            val isReDispatch = lastMatchedRule.resolveStrategy == ResolutionStrategy.RE_DISPATCH || lastMatchedRule.targetPackage.isBlank()
            val realPkg = if (!isReDispatch && lastMatchedRule.targetPackage.isNotBlank()) {
                val pkg = lastMatchedRule.targetPackage
                if (pkg.contains("/")) pkg.substringBefore("/") else pkg
            } else if (!isReDispatch) {
                if (!fallbackPkg.isNullOrBlank() && fallbackPkg.contains("/")) fallbackPkg.substringBefore("/") else fallbackPkg
            } else null
            PredictedTarget(lastMatchedRule.name, realPkg, isReDispatch, wasDistributed, lastMatchedRule.iconPath)
        } else {
            val realPkg = if (!fallbackPkg.isNullOrBlank() && fallbackPkg.contains("/")) fallbackPkg.substringBefore("/") else (fallbackPkg ?: "")
            val pm = appContext.packageManager
            val label = try {
                val info = pm.getApplicationInfo(realPkg ?: "", 0)
                info.loadLabel(pm).toString()
            } catch (e: Exception) { "浏览器" }
            PredictedTarget(label, realPkg, false, wasDistributed, null)
        }
    }

    /**
     * 预测 URL 的最终跳转目标详情（同步挂起版本，返回最终深度结果）
     */
    suspend fun predictTarget(context: Context, url: String): PredictedTarget {
        return predictTargetDeepResolve(context, url)
    }

    /**
     * 解析预测目标的图标 Drawable
     */
    fun resolvePredictedIcon(context: Context, predicted: PredictedTarget): Drawable {
        val pm = context.packageManager
        return when {
            !predicted.iconPath.isNullOrBlank() -> {
                loadCustomIconAsDrawable(context, predicted.iconPath)
                    ?: predicted.packageName?.let { runCatching { pm.getApplicationIcon(it) }.getOrNull() }
                    ?: (if (predicted.isReDispatch) context.getDrawable(com.moting.linkgo.R.drawable.ic_hub_primary) else null)
                    ?: pm.getApplicationIcon(context.packageName)
            }
            predicted.isReDispatch -> {
                context.getDrawable(com.moting.linkgo.R.drawable.ic_hub_primary)
                    ?: pm.getApplicationIcon(context.packageName)
            }
            !predicted.packageName.isNullOrBlank() -> {
                runCatching { pm.getApplicationIcon(predicted.packageName) }.getOrElse {
                    pm.getApplicationIcon(context.packageName)
                }
            }
            else -> pm.getApplicationIcon(context.packageName)
        }
    }

    /**
     * 内部工具：将输入 URL 进行解码与提纯 (归一化/去重)
     */
    fun standardizeUrl(
        url: String, 
        isSmart: Boolean, 
        isNormEnabled: Boolean, 
        normRegex: String = "", 
        normTemplate: String = ""
    ): String {
        // 第一阶段：保持原始 URL 进行提取，避免解码引入的干扰
        val decoded = url
        
        // 第二阶段：正则提取/清洗
        val extracted = if (isSmart && isNormEnabled) {
            if (!com.moting.linkgo.util.UrlUtils.isPureUrl(decoded)) {
                com.moting.linkgo.util.UrlUtils.performNormalization(decoded, normRegex, normTemplate)
            } else decoded
        } else decoded

        // 第三阶段：返回提取出的原始链接
        return extracted
    }

    /**
     * 处理并分发 URL
     */
    suspend fun handleUrl(
        context: Context, 
        url: String, 
        source: DispatchSource = DispatchSource.SMART,
        isReverse: Boolean = false,
        forceWindowMode: Int? = null
    ): RouteResult {
        Log.w("LinkGo_Flow", "[HANDLE-URL] handleUrl 开始: url=${url.take(100)}, source=$source, isReverse=$isReverse, forceMode=$forceWindowMode")
        if (url.isBlank()) return RouteResult(DispatchResult.FAIL, url, "", 0)
        
        val appContext = context.applicationContext
        val repository = SettingsRepository(appContext)
        
        // 移除全局初步解码，保持原始 URL 进入处理链，确保正则匹配的准确性
        var currentUrl = url
        var depth = 0
        val maxDepth = 3
        
        // 生成流水 Trace ID
        val traceId = UUID.randomUUID().toString()
        var stepIndex = 0
        
        try {
            // 1. 获取基础配置
            val rules = repository.dispatchRules.first()
            val fallbackPkg = repository.fallbackBrowser.first()
            val fallbackWindowMode = repository.fallbackWindowMode.first()
            val config = repository.windowConfig.first()
            val isNormEnabled = repository.normalizationEnabled.first()

            while (depth < maxDepth) {
                // 执行智能分流/提纯 (统一调用工具方法)
                val standardizedUrl = standardizeUrl(
                    url = currentUrl,
                    isSmart = source == DispatchSource.SMART,
                    isNormEnabled = isNormEnabled,
                    normRegex = repository.normalizationRegex.first(),
                    normTemplate = repository.normalizationTemplate.first()
                )
                
                // 2. 匹配规则 (分发/分流规则拥有极高优先级，防止被普通规则拦截)
                val matchedRule = rules.find { rule ->
                    if (!rule.isEnabled) return@find false
                    if (rule.pattern.isBlank()) return@find false // 分发模式下空白模式不自动匹配以防黑洞
                    matchRule(standardizedUrl, rule) && rule.resolveStrategy == ResolutionStrategy.RE_DISPATCH
                } ?: rules.find { rule ->
                    if (!rule.isEnabled) return@find false
                    if (rule.pattern.isBlank()) return@find true
                    matchRule(standardizedUrl, rule)
                }

                val strategy = matchedRule?.resolveStrategy ?: ResolutionStrategy.NONE
                
                // === 核心逻辑点：执行处理 (无论分发还是直接跳转，都遵循开关配置) ===
                val processedUrl = processUrlInternal(
                    url = standardizedUrl,
                    resolveShortLink = matchedRule?.resolveShortLink ?: false,
                    template = matchedRule?.template,
                    extractPattern = matchedRule?.extractPattern
                )

                // 3. 准备反馈信息
                val targetPackage = matchedRule?.targetPackage ?: fallbackPkg
                val realPackageName = if (!targetPackage.isNullOrBlank() && targetPackage.contains("/")) targetPackage.substringBefore("/") else (targetPackage ?: "")
                
                val targetLabel = if (matchedRule != null) {
                    matchedRule.name
                } else {
                    val pm = appContext.packageManager
                    val label = try {
                        val info = pm.getApplicationInfo(realPackageName, 0)
                        info.loadLabel(pm).toString()
                    } catch (e: Exception) { null }
                    label ?: "浏览器"
                }

                // 4. 根据策略决定动作
                if (strategy == ResolutionStrategy.RE_DISPATCH) {
                    // 桥接模式：处理后进入下一轮循环
                    Log.w("LinkGo_Flow", "[REDISPATCH] 命中分流规则: ${matchedRule?.name}, 进入下一轮匹配")
                    
                    if (processedUrl == currentUrl) {
                        // 如果处理后没变，强制跳出防止死循环
                        Log.w("LinkGo_Flow", "[REDISPATCH-BREAK] 处理结果无变化，终止循环")
                        return executeLaunchFlow(context, repository, matchedRule, processedUrl, fallbackPkg, config, fallbackWindowMode, standardizedUrl, traceId, stepIndex, skipProcessing = true, isReverse = isReverse, forceWindowMode = forceWindowMode)
                    }

                    // 记录解析步骤
                    repository.addJumpRecord(
                        com.moting.linkgo.model.JumpRecord(
                            ruleName = "[分发] ${matchedRule?.name}",
                            ruleId = matchedRule?.id,
                            originalUrl = standardizedUrl,
                            targetPackage = "",
                            rulePattern = matchedRule?.pattern,
                            matchTypeName = matchedRule?.matchType?.label,
                            executionStatus = 0,
                            traceId = traceId,
                            resultUrl = processedUrl,
                            stepIndex = stepIndex++
                        )
                    )

                    currentUrl = processedUrl
                    depth++
                    continue
                } else {
                    // 常规模式：执行最终启动 (标记 skipProcessing=true 避免二次解析)
                    return executeLaunchFlow(context, repository, matchedRule, processedUrl, fallbackPkg, config, fallbackWindowMode, standardizedUrl, traceId, stepIndex, skipProcessing = true, isReverse = isReverse, forceWindowMode = forceWindowMode)
                }
            }
            // 如果全部循环结束依然没 return，说明最后一步也是备选
            return executeLaunchFlow(context, repository, null, currentUrl, fallbackPkg, config, fallbackWindowMode, currentUrl, traceId, stepIndex, skipProcessing = true, isReverse = isReverse, forceWindowMode = forceWindowMode)
        } catch (e: Exception) {
            Log.e("LinkGo_Flow", "[ERROR] 分发异常", e)
            return RouteResult(DispatchResult.FAIL, url, traceId, stepIndex)
        }
    }

    /**
     * 核心 URL 处理逻辑：解析短链 + 正则提取 + 模板重构
     * 将解析与重构逻辑从跳转流程中剥离，使其能够被匹配分送复用。
     */
    private suspend fun processUrlInternal(
        url: String,
        resolveShortLink: Boolean,
        template: String?,
        extractPattern: String?
    ): String {
        // 1. 处理短链接解析 (如果启用)
        var effectiveUrl = url
        if (resolveShortLink) {
            effectiveUrl = withContext(Dispatchers.IO) {
                try { resolveUrl(url) } catch (e: Exception) { url }
            }
            Log.d(TAG, "处理中解析成功: $url -> $effectiveUrl")
        }

        // 2. 正则提取与模版拼接 (Splicing Logic)
        if (template.isNullOrBlank()) return effectiveUrl

        val uri = try { android.net.Uri.parse(effectiveUrl) } catch(e: Exception) { null }
        
        // 提取支持的变量基础值
        val baseVariables = mutableMapOf<String, String>()
        baseVariables["url"] = effectiveUrl
        baseVariables["0"] = effectiveUrl // 默认全文
        if (uri != null) {
            baseVariables["host"] = uri.host ?: ""
            baseVariables["path"] = uri.path ?: ""
            baseVariables["query"] = uri.query ?: ""
            baseVariables["scheme"] = uri.scheme ?: ""
        }

        // 处理正则提取并更新 baseVariables
        val effectiveExtractPattern = extractPattern?.takeIf { it.isNotBlank() }
        if (effectiveExtractPattern != null) {
            val pattern = try { 
                java.util.regex.Pattern.compile(effectiveExtractPattern, java.util.regex.Pattern.CASE_INSENSITIVE) 
            } catch (e: Exception) { null }
            
            if (pattern != null) {
                val matcher = pattern.matcher(effectiveUrl)
                if (matcher.find()) {
                    for (i in 0..matcher.groupCount()) {
                        baseVariables[i.toString()] = matcher.group(i) ?: ""
                    }
                } else {
                    return effectiveUrl // 核心修正：不匹配则返回原串
                }
            } else {
                return effectiveUrl
            }
        }

        // 3. 执行增强替换逻辑 (支持后缀变换)
        // 正则说明：匹配 {变量名} 或 {变量名_后缀1_后缀2}
        val varRegex = java.util.regex.Pattern.compile("\\{([a-zA-Z0-9]+)((?:_url_enc|_url_dec|_b64_enc|_b64_dec)*)\\}")
        val matcher = varRegex.matcher(template)
        val sb = StringBuffer()
        
        while (matcher.find()) {
            val varName = matcher.group(1)
            val suffixChain = matcher.group(2) ?: ""
            val baseValue = baseVariables[varName] ?: ""
            
            // 执行变换链
            val transformedValue = applyTransformChain(baseValue, suffixChain)
            matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(transformedValue))
        }
        matcher.appendTail(sb)
        var result = sb.toString()

        // 4. 兼容性处理：处理原有硬编码变量
        result = result.replace("{url_encode}", try { java.net.URLEncoder.encode(effectiveUrl, "UTF-8") } catch(e: Exception) { effectiveUrl })
            .replace("{url_decode}", try { java.net.URLDecoder.decode(effectiveUrl, "UTF-8") } catch(e: Exception) { effectiveUrl })

        // 终极检测：如果最终产物中仍然包含未替换的数字占位符 (如 {1})，说明重构失败
        if (result.contains(Regex("\\{\\d+\\}"))) {
            return effectiveUrl
        }
        
        return result
    }

    /**
     * 执行后缀变换链
     */
    private fun applyTransformChain(value: String, suffixChain: String): String {
        if (suffixChain.isBlank()) return value
        var result = value
        // 提取所有后缀项
        val suffixes = suffixChain.split("_").filter { it.isNotBlank() }
        
        // 由于 split 会丢失下划线前缀，我们重新组装或直接根据缩写判断
        // 因为我们支持 _url_enc 这种复合形式，需要小心处理
        // 简单方案：直接在原始字符串中寻找已知指令
        val actions = mutableListOf<String>()
        if (suffixChain.contains("_url_enc")) actions.add("_url_enc")
        // ... 注意：简单 split 会破坏顺序。
        // 修正方案：按顺序提取已知指令
        val knownSuffixes = listOf("_url_enc", "_url_dec", "_b64_enc", "_b64_dec")
        var currentChain = suffixChain
        
        // 按照链条中的出现顺序依次执行
        while (currentChain.isNotEmpty()) {
            val nextAction = knownSuffixes.find { currentChain.startsWith(it) }
            if (nextAction != null) {
                result = when (nextAction) {
                    "_url_enc" -> try { java.net.URLEncoder.encode(result, "UTF-8") } catch(e: Exception) { result }
                    "_url_dec" -> try { java.net.URLDecoder.decode(result, "UTF-8") } catch(e: Exception) { result }
                    "_b64_enc" -> try { android.util.Base64.encodeToString(result.toByteArray(), android.util.Base64.NO_WRAP) } catch(e: Exception) { result }
                    "_b64_dec" -> try { String(android.util.Base64.decode(result, android.util.Base64.NO_WRAP)) } catch(e: Exception) { result }
                    else -> result
                }
                currentChain = currentChain.substring(nextAction.length)
            } else {
                // 遇到无法识别的后缀，跳过一个下划线部分继续尝试
                currentChain = currentChain.substringAfter("_", "")
            }
        }
        return result
    }

    /**
     * 内部封装最终的启动与落库逻辑
     */
    private suspend fun executeLaunchFlow(
        context: Context,
        repository: SettingsRepository,
        matchedRule: DispatchRule?,
        url: String,
        fallbackPkg: String?,
        config: com.moting.linkgo.data.WindowConfig,
        fallbackModePref: Int,
        originalUrl: String, 
        traceId: String,
        stepIndex: Int,
        skipProcessing: Boolean = false,
        isReverse: Boolean = false,
        forceWindowMode: Int? = null
    ): RouteResult {
        val appContext = context.applicationContext
        val targetPackage = matchedRule?.targetPackage ?: fallbackPkg
        
        if (targetPackage.isNullOrBlank()) {
            return RouteResult(DispatchResult.NEED_SELECTOR, url, traceId, stepIndex)
        }

        // --- 方案 B：未安装预检与智能降级 ---
        var effectiveTargetPackage = targetPackage
        var effectiveMatchedRule = matchedRule
        var isFallbackDueToNotInstalled = false
        var uninstalledAppLabel = ""

        if (matchedRule != null && matchedRule.targetPackage.isNotBlank()) {
            val rulePkg = if (matchedRule.targetPackage.contains("/")) matchedRule.targetPackage.substringBefore("/") else matchedRule.targetPackage
            if (!PackageRepository.isAppInstalled(appContext, rulePkg)) {
                uninstalledAppLabel = matchedRule.name.ifBlank { rulePkg }
                isFallbackDueToNotInstalled = true
                Log.w(TAG, "[未安装降级] 规则目标应用未安装: $rulePkg ($uninstalledAppLabel)")

                val fallbackCleanPkg = if (fallbackPkg?.contains("/") == true) fallbackPkg.substringBefore("/") else fallbackPkg
                val isFallbackInstalled = !fallbackCleanPkg.isNullOrBlank() && PackageRepository.isAppInstalled(appContext, fallbackCleanPkg)

                if (isFallbackInstalled) {
                    // 改用备用浏览器打开
                    effectiveTargetPackage = fallbackPkg!!
                    effectiveMatchedRule = null
                } else {
                    // 无有效备选浏览器，提示并拉起应用选择弹窗
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appContext, "未安装目标应用 $uninstalledAppLabel，请选择打开方式", Toast.LENGTH_SHORT).show()
                    }
                    return RouteResult(DispatchResult.NEED_SELECTOR, url, traceId, stepIndex)
                }
            }
        }
        
        val realPackageName = if (effectiveTargetPackage.contains("/")) effectiveTargetPackage.substringBefore("/") else effectiveTargetPackage
        val targetLabel = if (effectiveMatchedRule != null) effectiveMatchedRule.name else {
            val pm = appContext.packageManager
            try { pm.getApplicationInfo(realPackageName, 0).loadLabel(pm).toString() } catch(e: Exception) { "备选" }
        }

        val rawWindowMode = when {
            effectiveMatchedRule != null && effectiveMatchedRule.ruleLaunchMode != -1 -> effectiveMatchedRule.ruleLaunchMode
            effectiveMatchedRule == null && fallbackModePref != -1 -> fallbackModePref
            else -> -1
        }
        val normalWindowMode = when (rawWindowMode) {
            -1 -> if (config.isEnabled) config.windowingMode else 1
            5 -> config.windowingMode
            else -> rawWindowMode
        }
        val windowMode = if (forceWindowMode != null && forceWindowMode != -1) {
            if (forceWindowMode == 5) config.windowingMode else forceWindowMode
        } else if (!isReverse) {
            normalWindowMode
        } else {
            // 反转模式：若默认是小窗模式，反转为普通全屏(1)；若默认是普通全屏(1)，反转为小窗模式
            if (normalWindowMode != 1) 1 else config.windowingMode
        }

        val suppressJumpNotification = ClipboardHandler.suppressJumpNotification
        ClipboardHandler.suppressJumpNotification = false

        if (suppressJumpNotification) {
            NotificationHelper.dismiss(appContext)
            Log.w("LinkGo_Flow", "[NOTIFY-DISMISS] 剪贴板跳转已撤回感知通知，跳过再次发送")
        }

        withContext(Dispatchers.Main) {
            val showRichNotificationSetting = repository.showRichNotification.first()
            val showTriggerToast = repository.showTriggerToast.first()
            
            Log.w("LinkGo_Flow", "[NOTIFY-CHECK] 最终启动通知判定: suppressJumpNotification=$suppressJumpNotification, stepIndex=$stepIndex, showRich=$showRichNotificationSetting, targetLabel=$targetLabel, pkg=$realPackageName")
            
            var chipShown = false
            if (!suppressJumpNotification && showRichNotificationSetting && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    val icon = if (effectiveMatchedRule != null && !effectiveMatchedRule.iconPath.isNullOrBlank()) {
                        loadCustomIconAsDrawable(appContext, effectiveMatchedRule.iconPath) ?: appContext.packageManager.getApplicationIcon(realPackageName)
                    } else if (effectiveMatchedRule?.resolveStrategy == ResolutionStrategy.RE_DISPATCH) {
                        appContext.getDrawable(com.moting.linkgo.R.drawable.ic_hub_primary) ?: appContext.packageManager.getApplicationIcon(appContext.packageName)
                    } else {
                        appContext.packageManager.getApplicationIcon(realPackageName)
                    }
                    val jumpPi = PendingIntent.getActivity(
                        appContext, 0,
                        Intent(appContext, com.moting.linkgo.LinkDispatcherActivity::class.java).apply {
                            action = Intent.ACTION_VIEW
                            data = android.net.Uri.parse(url)
                            putExtra("FROM_NOTIFICATION", true)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                        PendingIntent.FLAG_IMMUTABLE
                    )
                    chipShown = NotificationHelper.show(appContext, NotificationHelper.LiveActivityData(
                        icon = icon,
                        title = targetLabel,
                        bigTitle = targetLabel,
                        body = url.take(96),
                        fullText = url,
                        rawUrl = url,
                        bigIcon = icon,
                        contentIntent = jumpPi,
                        actionLabel = "直接跳转",
                        actionIntent = jumpPi
                    ))
                    Log.w("LinkGo_Flow", "[NOTIFY-POST-RESULT] NotificationHelper.show 返回: $chipShown")
                } catch (e: Exception) { Log.e(TAG, "[NOTIFY-ERROR] 最终通知发送失败", e) }
            } else {
                Log.w("LinkGo_Flow", "[NOTIFY-SKIP] 跳过通知发送: suppress=$suppressJumpNotification, showRich=$showRichNotificationSetting, sdk=${Build.VERSION.SDK_INT}")
            }
            if (isFallbackDueToNotInstalled) {
                Toast.makeText(appContext, "未安装 $uninstalledAppLabel，已改用备用浏览器打开", Toast.LENGTH_SHORT).show()
            } else if (!chipShown && showTriggerToast) {
                Toast.makeText(appContext, "跳转: $targetLabel", Toast.LENGTH_SHORT).show()
            }
        }

        // 确定预热配置和不留后台卡片配置：规则优先，备选兜底
        val isPreheatEnabled = effectiveMatchedRule?.isPreheatEnabled ?: repository.fallbackPreheatEnabled.first()
        val preheatDelayMillis = effectiveMatchedRule?.preheatDelayMillis ?: repository.fallbackPreheatDelay.first()
        val isExcludeFromRecents = effectiveMatchedRule?.excludeFromRecents ?: repository.fallbackExcludeFromRecents.first()

        // 跳转并复制：在拉起外部应用【之前】写入剪贴板，避免外部应用获得焦点后读取的仍是旧剪贴板内容
        if (effectiveMatchedRule?.jumpAndCopy == true && url.isNotBlank()) {
            try {
                val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("LinkGo", url))
                Log.w("LinkGo_Flow", "[JUMP-AND-COPY] 已复制最终链接到剪贴板(启动前): ${url.take(100)}")
            } catch (e: Exception) {
                Log.w(TAG, "跳转并复制写剪贴板异常(启动前): ${e.message}")
            }
        }

        var status = 0
        var errorMsg: String? = null
        val launchOk = try {
            performLaunchInternal(
                context = context,
                url = url,
                targetPackage = effectiveTargetPackage,
                windowMode = windowMode,
                template = effectiveMatchedRule?.template,
                extractPattern = effectiveMatchedRule?.extractPattern,
                resolveShortLink = effectiveMatchedRule?.resolveShortLink ?: false,
                excludeFromRecents = isExcludeFromRecents,
                isFallback = effectiveMatchedRule == null,
                isPreheatEnabled = isPreheatEnabled,
                preheatDelayMillis = preheatDelayMillis,
                skipProcessing = skipProcessing
            )
        } catch (e: Exception) {
            status = 1
            errorMsg = e.localizedMessage ?: e.toString()
            Log.e(TAG, "启动失败", e)
            false
        }
        // 启动未成功：按客观状态如实记录并提示（区分未安装 vs 已冻结停用）
        if (!launchOk) {
            status = 1
            val isInstalled = PackageRepository.isAppInstalled(appContext, realPackageName)
            val failedMsg = errorMsg ?: if (!isInstalled) {
                "未安装目标应用 $targetLabel，跳转失败"
            } else {
                "目标应用已停用或冻结，跳转失败"
            }
            errorMsg = failedMsg
            withContext(Dispatchers.Main) {
                Toast.makeText(appContext, failedMsg, Toast.LENGTH_SHORT).show()
            }
        }
        try {
            val recordName = if (isFallbackDueToNotInstalled) {
                "[备选兜底] $targetLabel (原目标 $uninstalledAppLabel 未安装)"
            } else {
                targetLabel
            }
            repository.addJumpRecord(
                com.moting.linkgo.model.JumpRecord(
                    ruleName = recordName,
                    ruleId = effectiveMatchedRule?.id,
                    originalUrl = originalUrl,
                    targetPackage = effectiveTargetPackage,
                    rulePattern = effectiveMatchedRule?.pattern,
                    matchTypeName = effectiveMatchedRule?.matchType?.label,
                    executionStatus = status,
                    errorMessage = errorMsg,
                    traceId = traceId,
                    resultUrl = url,
                    stepIndex = stepIndex
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "addJumpRecord 异常: ${e.message}")
        }
        
        // 启动成功后：非「跳转并复制」场景下按用户设置清空剪贴板（跳转并复制的写剪贴板已在启动前完成）
        if (status == 0) {
            try {
                if (matchedRule?.jumpAndCopy != true) {
                    val shouldClear = repository.clearClipboardAfterJump.first()
                    if (shouldClear) {
                        com.moting.linkgo.clipboard.ClipboardClearer.clearAfterJump(appContext)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "跳转后剪贴板处理异常: ${e.message}")
            }
        }

        val resultStatus = if (status == 0) DispatchResult.SUCCESS else DispatchResult.FAIL
        return RouteResult(resultStatus, url, traceId, stepIndex)
    }

    /**
     * 加载自定义图标并转换为 Drawable
     */
    /**
     * 加载规则自定义图标。
     *
     * 对外暴露是因为图片规则链路（`ClipboardHandler`）也要用它：规则图标是用户资产，
     * 与文本规则共用同一份 `rule_icons` 存储，不该在图片侧另写一份加载逻辑。
     */
    fun loadCustomIconAsDrawable(context: Context, iconPath: String): android.graphics.drawable.Drawable? {
        return try {
            if (iconPath.startsWith("app://")) {
                val pkg = iconPath.substringAfter("app://")
                context.packageManager.getApplicationIcon(pkg)
            } else {
                val bitmap = android.graphics.BitmapFactory.decodeFile(iconPath)
                if (bitmap != null) {
                    android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 匹配测试详情步骤
     */
    /**
     * 匹配仿真步骤：承载从输入到最终跳转的所有中间状态
     */
    data class TestDispatchStep(
        val depth: Int,
        val inputUrl: String,           // 本级原始输入
        val decodedUrl: String? = null, // 第一阶段解码后
        val standardizedUrl: String,    // 归一化/提取后
        val matchedRule: DispatchRule?,  // 匹配结果
        val resolvedUrl: String? = null, // 短链解析结果 (如果有)
        val resultUrl: String? = null,   // 重构/变换后的最终 URL
        val targetPackage: String? = null,
        val targetLabel: String? = null,
        val isFinal: Boolean = false,
        val isLibraryMiss: Boolean = false,
        val isDecoded: Boolean = false,       // 是否执行了解码
        val isExtracted: Boolean = false,     // 是否执行了正则提取/归一化
        val isResolved: Boolean = false,      // 是否触发了解析
        val isReconstructed: Boolean = false, // 是否触发了重构
        val isPatternMismatch: Boolean = false, // 专门针对正则/域名不匹配的情况
        val error: String? = null
    )

    /**
     * 核心路由仿真引擎：严格同步生产逻辑，提供步骤追踪详情
     */
    suspend fun simulateRoute(
        context: Context,
        testUrl: String,
        currentRule: DispatchRule, // 当前编辑规则
        allRules: List<DispatchRule>,
        fallbackPkg: String,
        isNormEnabled: Boolean = false,
        normRegex: String = "",
        normTemplate: String = "",
        onFinalJump: (Boolean, String?) -> Unit // 只有显式触发跳转时才调用
    ): List<TestDispatchStep> {
        val steps = mutableListOf<TestDispatchStep>()
        var currentUrl = testUrl.trim()
        var depth = 0
        val maxDepth = 4

        try {
            while (depth < maxDepth) {
                val stepInput = currentUrl
                
                // 1. 标准化处理 (包含正则归一化与按需解码)
                // 针对预览逻辑优化：首步输入不执行正则提取逻辑（isSmart = false），确保测试链接完整性
                val standardizedUrl = standardizeUrl(stepInput, depth > 0, isNormEnabled, normRegex, normTemplate)
                
                val didDecode = com.moting.linkgo.util.UrlUtils.safeDecode(stepInput) != stepInput || 
                                com.moting.linkgo.util.UrlUtils.safeDecode(standardizedUrl) != standardizedUrl
                
                val isExtracted = standardizedUrl != stepInput

                // 3. 匹配：首步强制校验当前编辑规则，如果不匹配则直接终止并提示
                val matchedRule = if (depth == 0) {
                    if (matchRule(standardizedUrl, currentRule)) {
                        currentRule
                    } else {
                        // 针对编辑页的特殊反馈：不匹配时不检索全量库，直接报错
                        steps.add(TestDispatchStep(
                            depth = depth,
                            inputUrl = stepInput,
                            decodedUrl = if (didDecode) standardizedUrl else null,
                            standardizedUrl = standardizedUrl,
                            matchedRule = null,
                            resultUrl = standardizedUrl,
                            targetPackage = fallbackPkg,
                            targetLabel = "未命中",
                            isFinal = true,
                            isPatternMismatch = true,
                            error = "当前规则不匹配此链接，检查匹配规则"
                        ))
                        break
                    }
                } else {
                    // 后续步骤（重分发流转）才检索全量库
                    allRules.find { rule ->
                        rule.isEnabled && rule.resolveStrategy == ResolutionStrategy.RE_DISPATCH && matchRule(standardizedUrl, rule)
                    } ?: allRules.find { rule ->
                        rule.isEnabled && matchRule(standardizedUrl, rule)
                    }
                }

                if (matchedRule == null) {
                    val pm = context.packageManager
                    val label = try { pm.getApplicationInfo(fallbackPkg, 0).loadLabel(pm).toString() } catch(e: Exception) { "默认浏览器" }
                    steps.add(TestDispatchStep(
                        depth = depth,
                        inputUrl = stepInput,
                        decodedUrl = if (didDecode) standardizedUrl else null,
                        standardizedUrl = standardizedUrl,
                        matchedRule = null,
                        resultUrl = standardizedUrl,
                        targetPackage = fallbackPkg,
                        targetLabel = label,
                        isFinal = true,
                        isLibraryMiss = (depth > 0),
                        isDecoded = didDecode,
                        isExtracted = isExtracted
                    ))
                    break
                }

                // 4. 解析
                var isResolved = false
                val resolvedUrl = if (matchedRule.resolveShortLink) {
                    try { 
                        val result = withContext(Dispatchers.IO) { resolveUrl(standardizedUrl) }
                        if (result != standardizedUrl) {
                            isResolved = true
                            result
                        } else null
                    } catch (e: Exception) { null }
                } else null

                // 5. 重构
                val finalProcessed = processUrlInternal(
                    url = resolvedUrl ?: standardizedUrl,
                    resolveShortLink = false,
                    template = matchedRule.template,
                    extractPattern = matchedRule.extractPattern
                )
                
                // 状态精准判定
                val sourceForReconstruct = resolvedUrl ?: standardizedUrl
                val isReconstructed = finalProcessed != sourceForReconstruct
                
                // 已经通过 didFirstDecode 等判定了提取状态，此处不再重复定义

                val isFinal = matchedRule.resolveStrategy != ResolutionStrategy.RE_DISPATCH
                steps.add(TestDispatchStep(
                    depth = depth,
                    inputUrl = stepInput,
                    decodedUrl = if (didDecode) standardizedUrl else null,
                    standardizedUrl = standardizedUrl,
                    matchedRule = matchedRule,
                    resolvedUrl = resolvedUrl,
                    resultUrl = finalProcessed,
                    targetPackage = matchedRule.targetPackage,
                    targetLabel = matchedRule.name,
                    isFinal = isFinal,
                    isDecoded = false,
                    isExtracted = isExtracted,
                    isResolved = isResolved,
                    isReconstructed = isReconstructed
                ))

                if (isFinal) break

                // 匹配判断逻辑
                if (finalProcessed == currentUrl) {
                    // 死循环修正
                    steps[steps.size - 1] = steps.last().copy(error = "变换后无变化，匹配终止")
                    break
                }
                currentUrl = finalProcessed
                depth++
            }
        } catch (e: Exception) {
            steps.add(TestDispatchStep(depth, currentUrl, currentUrl, currentUrl, null, error = e.localizedMessage))
        }
        return steps
    }

    /**
     * 临时保留原接口以防破坏编译，内部重定向至新引擎 (后续清理)
     */
    fun launchRuleTestCascade(
        context: Context,
        testUrl: String,
        currentRule: DispatchRule,
        allRules: List<DispatchRule>,
        fallbackPkg: String,
        config: com.moting.linkgo.data.WindowConfig,
        isNormEnabled: Boolean = false,
        normRegex: String = "",
        normTemplate: String = "",
        onStep: (TestDispatchStep) -> Unit,
        onFinalResult: (Boolean, String?) -> Unit
    ) {
        globalScope.launch {
            val steps = withContext(Dispatchers.Default) {
                simulateRoute(context, testUrl, currentRule, allRules, fallbackPkg, isNormEnabled, normRegex, normTemplate, onFinalResult)
            }
            
            // 回调步骤
            steps.forEach { onStep(it) }
            
            // 执行真正的物理跳转 (最后一步)
            val last = steps.lastOrNull { it.isFinal }
            if (last != null) {
                try {
                    val repository = com.moting.linkgo.data.SettingsRepository(context)
                    val rawWindowMode = if (last.matchedRule != null && last.matchedRule.ruleLaunchMode != -1) last.matchedRule.ruleLaunchMode else -1
                    val windowMode = when (rawWindowMode) {
                        -1 -> if (config.isEnabled) config.windowingMode else 1
                        5 -> config.windowingMode
                        else -> rawWindowMode
                    }
                    
                    val isExcludeFromRecents = last.matchedRule?.excludeFromRecents ?: repository.fallbackExcludeFromRecents.first()
                    val launchOk = performLaunchInternal(
                        context = context,
                        url = last.resultUrl ?: last.standardizedUrl,
                        targetPackage = last.targetPackage ?: fallbackPkg,
                        windowMode = windowMode,
                        template = null, // 已处理
                        extractPattern = null, // 已处理
                        resolveShortLink = false, // 已处理
                        excludeFromRecents = isExcludeFromRecents,
                        isFallback = (last.matchedRule == null),
                        isPreheatEnabled = last.matchedRule?.isPreheatEnabled ?: false,
                        preheatDelayMillis = last.matchedRule?.preheatDelayMillis ?: 0L,
                        skipProcessing = true
                    )
                    withContext(Dispatchers.Main) {
                        val targetPkg = last.targetPackage ?: fallbackPkg
                        val cleanTarget = if (targetPkg.contains("/")) targetPkg.substringBefore("/") else targetPkg
                        val isInstalled = PackageRepository.isAppInstalled(context, cleanTarget)
                        val failMsg = if (!isInstalled) "未安装目标应用，跳转失败" else "目标应用已停用或冻结，跳转失败"
                        onFinalResult(launchOk, if (launchOk) null else failMsg)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { onFinalResult(false, e.localizedMessage) }
                }
            }
        }
    }

    fun matchRule(url: String, rule: DispatchRule): Boolean {
        if (rule.pattern.isBlank()) return false
        return when (rule.matchType) {
            com.moting.linkgo.model.MatchType.REGEX -> {
                val pattern = getCompiledPattern(rule.pattern)
                pattern?.matcher(url)?.find() ?: false
            }
            com.moting.linkgo.model.MatchType.EXACT -> url.equals(rule.pattern, ignoreCase = true)
            com.moting.linkgo.model.MatchType.CONTAINS -> {
                val host = try { android.net.Uri.parse(url).host } catch (e: Exception) { null }
                if (host != null && (host.equals(rule.pattern, ignoreCase = true) || host.endsWith(".${rule.pattern}", ignoreCase = true))) {
                    true
                } else {
                    url.contains(rule.pattern, ignoreCase = true)
                }
            }
        }
    }

    /**
     * 「仅命中规则时拦截」的接管前预判：该链接在真实分发链路上是否会命中跳转规则。
     *
     * 与 [handleUrl] 的匹配口径完全一致：先按归一化（standardizeUrl）后的 URL 匹配、仅启用中的
     * 规则参与，空白模式的兜底规则命中一切链接（主进程语义），链式分发规则同样算命中。
     *
     * 用途：在真正分发**之前**决定"是否接管本次链接"。未命中规则说明本次只会落到备选浏览器
     * （未配备选时甚至没有目标），此时应当放行交回应用自己的内置浏览器，而不是接管。
     * 放在主进程是因为它拥有权威的规则表与归一化配置——Hook 端只有原始 URL 与规则快照，
     * 两侧结论分歧时以本方法为准。
     */
    suspend fun hasMatchedRule(
        context: Context,
        url: String,
        source: DispatchSource = DispatchSource.SMART
    ): Boolean {
        if (url.isBlank()) return false
        val repository = SettingsRepository(context.applicationContext)
        val standardizedUrl = standardizeUrl(
            url = url,
            isSmart = source == DispatchSource.SMART,
            isNormEnabled = repository.normalizationEnabled.first(),
            normRegex = repository.normalizationRegex.first(),
            normTemplate = repository.normalizationTemplate.first()
        )
        return repository.dispatchRules.first().any { rule ->
            if (!rule.isEnabled) return@any false
            if (rule.pattern.isBlank()) return@any true // 兜底规则命中一切链接
            matchRule(standardizedUrl, rule)
        }
    }

    /**
     * 统一规则 URL 转换方法（供预测、执行与测试共享同一转换逻辑，彻底消除差异）
     */
    suspend fun applyRuleTransform(url: String, rule: DispatchRule): String {
        return processUrlInternal(
            url = url,
            resolveShortLink = rule.resolveShortLink,
            template = rule.template,
            extractPattern = rule.extractPattern
        )
    }



    /**
     * 核心启动逻辑封装 (挂起实现)
     *
     * @return true=启动成功发出；false=启动失败（目标可能被冻结/停用，内部已尝试自动解冻重试）
     */
    suspend fun performLaunchInternal(
        context: Context,
        url: String,
        targetPackage: String,
        windowMode: Int,
        template: String?,
        extractPattern: String?,
        resolveShortLink: Boolean = false,
        excludeFromRecents: Boolean = false,
        isFallback: Boolean = false,
        isPreheatEnabled: Boolean = false,
        preheatDelayMillis: Long = 0L,
        skipProcessing: Boolean = false
    ): Boolean {
        val appContext = context.applicationContext
        val repository = SettingsRepository(appContext)

        // 1. 执行核心解析与变换逻辑
        val finalUrl = if (skipProcessing) {
            url // 已预处理，直接使用
        } else {
            processUrlInternal(
                url = url,
                resolveShortLink = resolveShortLink,
                template = template,
                extractPattern = extractPattern
            )
        }
        
        val config = repository.windowConfig.first()
        val uri = try { android.net.Uri.parse(finalUrl) } catch(e: Exception) { null }

        // 确定实际使用的窗口模式
        val effectiveWindowMode = when (windowMode) {
            -1 -> if (config.isEnabled) config.windowingMode else 1
            5 -> config.windowingMode
            else -> windowMode
        }

        // 解析包名和类名
        val (basePkg, baseCls) = if (targetPackage.contains("/")) {
            targetPackage.substringBefore("/") to targetPackage.substringAfter("/")
        } else {
            targetPackage to null
        }

        // [调试日志] 打印最终生成的重构链接
        Log.d(TAG, "[路由开始] 最终 URL: $finalUrl")

        // 1. 构建基础 Intent：针对 intent:// 协议启用深度解析，否则保持普通 VIEW 模式
        val launchIntent = if (finalUrl.startsWith("intent://", ignoreCase = true)) {
            try {
                // 使用系统 Intent URI 解析器解析高级指令，提取 Extras (如 S.rawUrl)
                Intent.parseUri(finalUrl, Intent.URI_INTENT_SCHEME).apply {
                    // 优先级：如果 LinkGo 规则显式指定了组件，则覆盖字符串内的配置
                    if (baseCls != null) {
                        setClassName(basePkg, baseCls)
                    } else if (basePkg.isNotBlank()) {
                        `package` = basePkg
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Intent URI 解析失败: ${e.message}")
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(finalUrl)).apply {
                    if (baseCls != null) setClassName(basePkg, baseCls) else if (basePkg.isNotBlank()) `package` = basePkg
                }
            }
        } else {
            // 普通 HTTP/HTTPS 或 DeepLink
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(finalUrl)).apply {
                if (baseCls != null) setClassName(basePkg, baseCls) else if (basePkg.isNotBlank()) `package` = basePkg
            }
        }

        // --- 针对备选浏览器（包名启动）尝试显式解析 Activity ---
        if (baseCls == null && launchIntent.data == null) {
            val pm = appContext.packageManager
            val resolveInfo = withContext(Dispatchers.IO) {
                try {
                    pm.resolveActivity(launchIntent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                } catch (e: Exception) { null }
            }
            if (resolveInfo != null) {
                launchIntent.setClassName(resolveInfo.activityInfo.packageName, resolveInfo.activityInfo.name)
                Log.d(TAG, "解析到显式组件: ${resolveInfo.activityInfo.name}")
            }
        }

        // [模式 6] 努比亚 MyOS：通过 Intent Identifier 触发系统小窗回复
        if (effectiveWindowMode == 6) {
            launchIntent.identifier = NUBIA_WINDOW_REPLY_IDENTIFIER
            Log.d(TAG, "[模式6-努比亚] 已注入 identifier=_WindowReply，由系统分配悬浮小窗")
        }

        // --- 核心增强：跳转前主动预检解冻（全域覆盖主空间与分身空间，防止分身弹窗假成功）---
        if (com.moting.linkgo.data.SettingsCache.autoUnfreezeEnabled && basePkg.isNotBlank() && basePkg != appContext.packageName) {
            val needsUnfreeze = UnfreezeHelper.checkNeedsUnfreeze(appContext, basePkg)
            if (needsUnfreeze) {
                Log.w(TAG, "[UNFREEZE] 事前检测到目标应用处于冻结/停用状态，执行全域解冻: $basePkg")
                val unfrozen = UnfreezeHelper.unfreeze(basePkg)
                if (unfrozen) {
                    delay(150)
                    if (com.moting.linkgo.data.SettingsCache.unfreezeShowToast) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(appContext, "目标应用已自动解冻", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        // 预生成 Options
        val options = createActivityOptions(context, config, effectiveWindowMode)

        // --- 核心增强：针对未导出 Activity 的智能三梯次直达（Root -> VSync -> 助手劫持） ---
        val component = launchIntent.component
        if (component != null && !isComponentExported(appContext, component)) {
            if (effectiveWindowMode != 4) {
                return launchNonExportedActivity(
                    context = appContext,
                    launchIntent = launchIntent,
                    component = component,
                    basePkg = basePkg,
                    effectiveWindowMode = effectiveWindowMode,
                    excludeFromRecents = excludeFromRecents,
                    isPreheatEnabled = isPreheatEnabled,
                    preheatDelayMillis = preheatDelayMillis,
                    options = options
                )
            }
        }

        // 适配特殊厂商标志位
        adaptManufacturerFlags(launchIntent, effectiveWindowMode)

        // === 核心重构：双跳板代理路由分发 ===
        val routerClass = if (excludeFromRecents) {
            com.moting.linkgo.router.HiddenRouterActivity::class.java
        } else {
            com.moting.linkgo.router.NormalRouterActivity::class.java
        }

        // 本地挂起函数：构建跳板 Intent 并启动，等待跳板回传真实启动结果
        suspend fun dispatchViaRouter(requestId: String): Boolean {
            val routerIntent = Intent(appContext, routerClass).apply {
                // 后台发起的意图必须新建任务栈，启动跳板容器
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("EXTRA_FINAL_URL", finalUrl)
                putExtra("EXTRA_REQUEST_ID", requestId)

                if (effectiveWindowMode == 4) {
                    // 模式 4：OriginOS 代理
                    putExtra("EXTRA_IS_MODE_4", true)

                    val safeFinalIntent = Intent(launchIntent).apply {
                        flags = flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv()
                        flags = flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK.inv()
                    }
                    putExtra(Intent.EXTRA_INTENT, safeFinalIntent)

                    if (isPreheatEnabled) {
                        val pm = appContext.packageManager
                        val preheatIntent = pm.getLaunchIntentForPackage(basePkg)?.apply {
                            flags = flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv()
                            flags = flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK.inv()
                        }
                        if (preheatIntent != null) {
                            putExtra("EXTRA_PREHEAT_INTENT", preheatIntent)
                            putExtra("EXTRA_PREHEAT_DELAY", preheatDelayMillis)
                        }
                    }
                } else {
                    // 普通模式
                    putExtra("EXTRA_IS_MODE_4", false)

                    launchIntent.apply {
                        if (excludeFromRecents) {
                            // 【核心修复】不留后台模式也必须保留 NEW_TASK 和 REORDER_TO_FRONT
                            // 否则第二次跳转无法识别已开启的小窗，导致双开或全屏
                            flags = flags or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                            flags = flags or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                        } else {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        }
                    }
                    putExtra(Intent.EXTRA_INTENT, launchIntent)

                    if (isPreheatEnabled) {
                        val pm = appContext.packageManager
                        val preheatIntent = pm.getLaunchIntentForPackage(basePkg)?.apply {
                            if (effectiveWindowMode == 6) identifier = NUBIA_WINDOW_REPLY_IDENTIFIER
                            if (excludeFromRecents) {
                                flags = flags or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                                flags = flags or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                            } else {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                            }
                        }
                        if (preheatIntent != null) {
                            putExtra("EXTRA_PREHEAT_INTENT", preheatIntent)
                            putExtra("EXTRA_PREHEAT_DELAY", preheatDelayMillis)
                        }
                    }

                    if (options != null) {
                        putExtra("EXTRA_OPTIONS_BUNDLE", options.toBundle())
                    }

                    if (Build.VERSION.SDK_INT >= 34) {
                        putExtra("EXTRA_USE_PENDING_INTENT", true)
                    }
                }
            }

            Log.d(TAG, "[路由分发] 启动代理跳板: ${routerClass.simpleName}")
            return try {
                appContext.startActivity(routerIntent)
                // 等待跳板回传真实结果（超时降级为成功，避免跳板异常丢失时阻塞正常跳转）
                withTimeoutOrNull(3_000L) {
                    ShareOpenResultManager.awaitResult(requestId)
                }?.success != false
            } catch (e: Exception) {
                Log.e(TAG, "启动跳板失败", e)
                false
            }
        }

        // 首次分发
        var launchOk = dispatchViaRouter(UUID.randomUUID().toString())

        // 首次失败 → 自动解冻后重试一次（杜绝目标应用被冻结时的静默失败）
        // currentChannel() 的 su 探测可能阻塞，统一在 IO 线程执行
        if (!launchOk && com.moting.linkgo.data.SettingsCache.autoUnfreezeEnabled && basePkg != appContext.packageName) {
            val shouldUnfreeze = withContext(Dispatchers.IO) {
                com.moting.linkgo.util.privilege.PrivilegeEngine.currentChannel() !=
                    com.moting.linkgo.util.privilege.PrivilegeEngine.Channel.NONE
            }
            if (shouldUnfreeze) {
                Log.w(TAG, "[UNFREEZE] 目标应用启动失败，尝试解冻: $basePkg")
                val unfrozen = UnfreezeHelper.unfreeze(basePkg)
                if (unfrozen) {
                    delay(150)
                    launchOk = dispatchViaRouter(UUID.randomUUID().toString())
                    if (launchOk && com.moting.linkgo.data.SettingsCache.unfreezeShowToast) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(appContext, "目标应用已自动解冻并重新跳转", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
        return launchOk
    }

    /**
     * 根据规则指定的启动模式（-1 全局 / 5 小窗 / 1 全屏）、全局小窗配置以及是否反转，仲裁出最终生效的窗口模式代号。
     * 若开启反转模式：原生效模式为小窗则反转为全屏(1)；原生效模式为全屏则反转为小窗(config.windowingMode)。
     */
    fun resolveWindowMode(
        rawLaunchMode: Int,
        config: com.moting.linkgo.data.WindowConfig = com.moting.linkgo.data.SettingsCache.windowConfig,
        isReverse: Boolean = false
    ): Int {
        val normalWindowMode = when (rawLaunchMode) {
            -1 -> if (config.isEnabled) config.windowingMode else 1
            5 -> config.windowingMode
            else -> rawLaunchMode
        }
        return if (!isReverse) {
            normalWindowMode
        } else {
            // 反转模式：若默认是小窗模式，反转为普通全屏(1)；若默认是普通全屏(1)，反转为小窗模式
            if (normalWindowMode != 1) 1 else config.windowingMode
        }
    }

    /**
     * 构建 ActivityOptions 核心辅助方法
     */
    fun createActivityOptions(
        context: Context,
        config: com.moting.linkgo.data.WindowConfig,
        windowMode: Int
    ): ActivityOptions? {
        // 模式 4 (OriginOS Chooser 代理) / 模式 6 (努比亚 Identifier) 在正式跳转时不使用原生 Options
        if (windowMode == 4 || windowMode == 6) {
            Log.d(TAG, "模式 4/6 不使用 ActivityOptions，由系统分配小窗")
            return null
        }

        val options = ActivityOptions.makeBasic()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try { HiddenApiBypass.addHiddenApiExemptions("Landroid/app/ActivityOptions") } catch (e: Exception) {}
        }

        try {
            val method = options.javaClass.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
            method.isAccessible = true
            method.invoke(options, windowMode)

            if (windowMode != 1 && isHuaweiOrHonor()) {
                try {
                    val stackMethod = options.javaClass.getMethod("setLaunchStackId", Int::class.javaPrimitiveType)
                    stackMethod.isAccessible = true
                    stackMethod.invoke(options, 2)
                } catch (e: Exception) {}
            }
            
            if (Build.VERSION.SDK_INT >= 34) {
                try {
                    val balMethod = options.javaClass.getMethod("setPendingIntentBackgroundActivityStartMode", Int::class.javaPrimitiveType)
                    balMethod.isAccessible = true
                    balMethod.invoke(options, 1)
                    Log.d(TAG, "已注入 Android 14 后台授权权限位")
                } catch (e: Exception) {
                    Log.e(TAG, "后台权限位设置失败", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "ActivityOptions 反射调用失败", e)
        }

        if (windowMode != 1) {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val (physicalW, physicalH) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val m = wm.maximumWindowMetrics
                m.bounds.width() to m.bounds.height()
            } else {
                val display = wm.defaultDisplay
                val realSize = Point()
                display.getRealSize(realSize)
                realSize.x to realSize.y
            }

            val (visualW, visualH) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val m = wm.currentWindowMetrics
                m.bounds.width() to m.bounds.height()
            } else {
                val display = wm.defaultDisplay
                val size = Point()
                display.getSize(size)
                size.x to size.y
            }

            val isPortraitReal = visualW < visualH
            val minD = Math.min(physicalW, physicalH)
            val maxD = Math.max(physicalW, physicalH)
            val effectiveWidth = if (isPortraitReal) minD else maxD
            val effectiveHeight = if (isPortraitReal) maxD else minD

            options.launchBounds = config.calculateBounds(effectiveWidth, effectiveHeight, isPortraitReal)
        }
        
        return options
    }

    fun adaptManufacturerFlags(intent: Intent, windowMode: Int) {
        if (windowMode == 1) return

        val manufacturer = Build.MANUFACTURER.lowercase()
        if (manufacturer.contains("xiaomi")) {
            Log.d(TAG, "已禁用所有 Xiaomi 厂商私有标志位（当前仅依赖原生 ActivityOptions）")
        }
    }

    private fun isHuaweiOrHonor(): Boolean {
        val manufacturer = Build.MANUFACTURER.uppercase()
        val brand = Build.BRAND.uppercase()
        return manufacturer.contains("HUAWEI") || 
               brand.contains("HUAWEI") || 
               manufacturer.contains("HONOR") || 
               brand.contains("HONOR")
    }

    /**
     * 解析重定向链接 (供 UI 测试与内部跳转使用)
     */
    /**
     * 解析重定向链接 (供 UI 测试与内部跳转使用)
     */
    suspend fun resolveUrl(originalUrl: String): String = withContext(Dispatchers.IO) {
        var currentUrl = originalUrl
        var cookie: String? = null
        val maxRedirects = 10
             
        try {
            repeat(maxRedirects) {
                val requestBuilder = Request.Builder()
                    .url(currentUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                
                if (cookie != null) {
                    requestBuilder.header("Cookie", cookie)
                }
                
                httpClient.newCall(requestBuilder.build()).execute().use { response ->
                    response.header("Set-Cookie")?.let { setCookie ->
                        cookie = setCookie.split(";").getOrNull(0)
                    }

                    val code = response.code
                    val location = response.header("Location")

                    // 模式一：标准的 3xx 重定向
                    if (code in 300..399 && !location.isNullOrBlank()) {
                        currentUrl = resolveRelativeUrl(currentUrl, location)
                        return@repeat 
                    } 
                    
                    // 模式二与三：200 OK，但内容里藏了跳转
                    if (code == 200) {
                        val body = response.body?.string() ?: ""

                        // A. 匹配 HTML Meta 刷新标签
                        val metaUrl = extractMetaRefreshUrl(body)
                        if (!metaUrl.isNullOrBlank()) {
                            currentUrl = resolveRelativeUrl(currentUrl, metaUrl)
                            return@repeat
                        }

                        // B. 匹配 JavaScript 常见跳转
                        val jsUrl = extractJavaScriptUrl(body)
                        if (!jsUrl.isNullOrBlank()) {
                            currentUrl = resolveRelativeUrl(currentUrl, jsUrl)
                            return@repeat
                        }

                        // C. 兜底奇葩情况：新浪微博等 200 + Location 模式
                        if (!location.isNullOrBlank()) {
                            currentUrl = resolveRelativeUrl(currentUrl, location)
                            return@repeat
                        }
                    }
                    
                    return@withContext currentUrl
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析重定向异常: ${e.message}")
            return@withContext currentUrl
        }
        currentUrl
    }

    private fun resolveRelativeUrl(baseUrl: String, location: String): String {
        return try {
            val base = java.net.URL(baseUrl)
            val resolved = java.net.URL(base, location)
            resolved.toString()
        } catch (e: Exception) {
            location
        }
    }

    private fun extractMetaRefreshUrl(html: String): String? {
        try {
            val pattern = java.util.regex.Pattern.compile(
                "<meta[^>]*http-equiv\\s*=\\s*[\"']?refresh[\"']?[^>]*url\\s*=\\s*[\"']?([^\"'>\\s]+)[\"']?",
                java.util.regex.Pattern.CASE_INSENSITIVE
            )
            val matcher = pattern.matcher(html)
            if (matcher.find()) {
                val url = matcher.group(1)
                return url?.replace("&amp;", "&")?.let { decodeLiteral(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "extractMetaRefreshUrl 异常", e)
        }
        return null
    }

    private fun extractJavaScriptUrl(html: String): String? {
        try {
            // 1. 抓出 <script> 标签块
            val scriptPattern = java.util.regex.Pattern.compile(
                "<script[^>]*>(.*?)</script>",
                java.util.regex.Pattern.CASE_INSENSITIVE or java.util.regex.Pattern.DOTALL
            )
            val scriptMatcher = scriptPattern.matcher(html)

            while (scriptMatcher.find()) {
                val scriptContent = scriptMatcher.group(1).trim()

                // 2. 匹配 window.location 等号后面的表达式
                val locPattern = java.util.regex.Pattern.compile(
                    "window\\.location(?:\\.href)?\\s*=\\s*(?:decodeURIComponent\\()?([a-zA-Z0-9_\"'/.:?=~%-]+)\\)?\\s*;?",
                    java.util.regex.Pattern.CASE_INSENSITIVE
                )
                val locMatcher = locPattern.matcher(scriptContent)

                if (locMatcher.find()) {
                    val rightHandSide = locMatcher.group(1).trim()

                    // 情况 A：等号右边本身是带引号的纯字符串网址
                    if ((rightHandSide.startsWith("\"") && rightHandSide.endsWith("\"")) ||
                        (rightHandSide.startsWith("'") && rightHandSide.endsWith("'"))) {
                        return rightHandSide.substring(1, rightHandSide.length - 1)
                    }

                    // 情况 B：等号右边是个变量名（专门对付知乎的防爬）
                    val varName = rightHandSide
                    val varPattern = java.util.regex.Pattern.compile(
                        "(?:var|let|const)?\\s*" + java.util.regex.Pattern.quote(varName) + "\\s*=\\s*[\"']([^\"']+)[\"']",
                        java.util.regex.Pattern.CASE_INSENSITIVE
                    )
                    val varMatcher = varPattern.matcher(scriptContent)
                    if (varMatcher.find()) {
                        return varMatcher.group(1)?.let { decodeLiteral(it, percentDecode = true) }
                    }

                    // 情况 C：直接是 URL
                    if (rightHandSide.startsWith("http://") || rightHandSide.startsWith("https://")) {
                        return rightHandSide
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "extractJavaScriptUrl 异常", e)
        }
        return null
    }

    /**
     * 还原字符串字面量：处理 JS/JSON 转义（\/ -> /、\\ -> \、\uXXXX、\xXX 等）。
     * percentDecode = true 时额外做一次百分号解码（如 https%3A%2F%2F，且不做 '+' -> ' '），
     * 该行为只属于原本就在解码的变量分支，其它调用点不要开。
     * 注意：这是取字面量后的必经步骤，而不是失败后的兜底。
     */
    private fun decodeLiteral(raw: String, percentDecode: Boolean = false): String {
        val needPercent = percentDecode && raw.contains('%')
        if (!raw.contains('\\') && !needPercent) return raw
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '\\' || i + 1 >= raw.length) {
                sb.append(c); i++; continue
            }
            when (val e = raw[i + 1]) {
                'u', 'x' -> {
                    val n = if (e == 'u') 4 else 2
                    val hex = raw.substring(i + 2, minOf(i + 2 + n, raw.length))
                    val code = if (hex.length == n) hex.toIntOrNull(16) else null
                    if (code != null) { sb.append(code.toChar()); i += 2 + n } else { sb.append(e); i += 2 }
                }
                'n' -> { sb.append('\n'); i += 2 }
                'r' -> { sb.append('\r'); i += 2 }
                't' -> { sb.append('\t'); i += 2 }
                else -> { sb.append(e); i += 2 }
            }
        }
        val unescaped = sb.toString()
        if (!needPercent) return unescaped
        // 只有"还不是带协议的地址"时才做百分号解码，避免破坏正常 URL 里的 % 与 +
        return if (unescaped.contains('%') && !unescaped.contains("://")) {
            try {
                java.net.URLDecoder.decode(unescaped.replace("+", "%2B"), "UTF-8")
            } catch (e: Exception) {
                unescaped
            }
        } else unescaped
    }

    /**
     * 判断组件是否导出
     */
    fun isComponentExported(context: Context, component: ComponentName): Boolean {
        return try {
            context.packageManager.getActivityInfo(component, 0).exported
        } catch (e: Exception) {
            true // 找不到默认认为导出
        }
    }

    /**
     * 未导出 Activity（exported=false）直达调度中枢：
     * 1. 强制预热保障：未导出小窗模式下必须强制预热主入口冷启动进程；
     * 2. 梯次 1：Root 权限优先（全版本通杀，免改助手，成功率 100%）；
     * 3. 梯次 2：Android 14/15 的 am start-in-vsync 免提权穿透；
     * 4. 梯次 3：助手劫持直达（Android 16+ 降级兜底，需 WRITE_SECURE_SETTINGS）。
     */
    private suspend fun launchNonExportedActivity(
        context: Context,
        launchIntent: Intent,
        component: ComponentName,
        basePkg: String,
        effectiveWindowMode: Int,
        excludeFromRecents: Boolean,
        isPreheatEnabled: Boolean,
        preheatDelayMillis: Long,
        options: ActivityOptions?
    ): Boolean {
        // 核心加固：后台调起的未导出页面必须显式具备 NEW_TASK 标志，避免 shell / context 启动抛出异常
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (excludeFromRecents) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }

        // --- 1. 强制预热保障（未导出小窗强制开启预热） ---
        val isEffectiveSmallWindow = effectiveWindowMode == 5 || effectiveWindowMode == 6
        val shouldPreheat = isPreheatEnabled || isEffectiveSmallWindow
        if (shouldPreheat) {
            val pm = context.packageManager
            val preheatIntent = pm.getLaunchIntentForPackage(basePkg)?.apply {
                if (effectiveWindowMode == 6) identifier = NUBIA_WINDOW_REPLY_IDENTIFIER
                if (excludeFromRecents) {
                    flags = flags or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    flags = flags or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                } else {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                }
            }
            if (preheatIntent != null) {
                Log.d(TAG, "[未导出直达] 执行前置预热（小窗强制或用户开启）...")
                context.startActivity(preheatIntent, options?.toBundle())
                val delayMs = if (preheatDelayMillis > 0) preheatDelayMillis else 300L
                delay(delayMs)
            }
        }

        // --- 2. 梯次 1：Root (su) 权限直开（首选最高优先级，全版本通杀） ---
        val canUseRoot = PrivilegeEngine.currentChannel() == PrivilegeEngine.Channel.ROOT ||
            (PrivilegeEngine.mode != PrivilegeEngine.PrivilegeMode.SHIZUKU && PrivilegeEngine.canExecSu())
        if (canUseRoot) {
            Log.d(TAG, "[未导出直达] 梯次1：使用 Root (su) 启动未导出组件: $component")
            Log.d(TAG, "[未导出直达] launchIntent.data=${launchIntent.data}, action=${launchIntent.action}, extras=${launchIntent.extras}")
            val rootOk = launchViaRoot(launchIntent, component, effectiveWindowMode)
            if (rootOk) return true
            Log.w(TAG, "[未导出直达] Root 启动返回失败，尝试降级到后续方案")
        }

        // --- 3. 梯次 2：Android 14 & 15 VSync 免提权穿透 ---
        if (Build.VERSION.SDK_INT in 34..35) {
            Log.d(TAG, "[未导出直达] 梯次2：Android 14/15 VSync 免权限穿透: $component")
            val vsyncOk = launchViaVsync(launchIntent, component)
            if (vsyncOk) return true
            Log.w(TAG, "[未导出直达] VSync 启动失败，降级到助手劫持方案")
        }

        // --- 4. 梯次 3：助手劫持直达（Android 16+ 或 VSync 失败兜底） ---
        val hasSecureSettings = context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
        if (hasSecureSettings) {
            Log.d(TAG, "[未导出直达] 梯次3：执行助手劫持跳转: $component")
            val assistantOk = launchViaAssistant(context, launchIntent, options?.toBundle())
            if (assistantOk) return true
        }

        // --- 5. 全部梯次未生效时的友好提示 ---
        withContext(Dispatchers.Main) {
            val tip = when {
                Build.VERSION.SDK_INT >= 36 -> "目标页面未导出，Android 16+ 需开启 Root 模式或授权安全设置"
                !hasSecureSettings -> "目标页面未导出，请授予安全设置权限或开启 Root 模式"
                else -> "目标页面未导出，未获取到有效提权通道"
            }
            Toast.makeText(context, tip, Toast.LENGTH_LONG).show()
        }
        return false
    }

    /**
     * 梯次 1：通过 Root (su) 执行 am start 打开任意未导出 Activity
     * 针对未导出页面采用显式组件 (-n pkg/cls) + 显式 Action/Data + FLAG_ACTIVITY_NEW_TASK (-f 0x10000000)
     * 规避 URI Scheme 导致 component 丢失的严重缺陷，并原生支持 Freeform 小窗 (--windowingMode 5)
     */
    private suspend fun launchViaRoot(
        intent: Intent,
        component: ComponentName,
        effectiveWindowMode: Int
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val compStr = component.flattenToString()
            val cmdBuilder = StringBuilder("am start -n \"$compStr\"")

            val action = intent.action ?: Intent.ACTION_VIEW
            cmdBuilder.append(" -a \"$action\"")

            val dataStr = intent.dataString
            if (!dataStr.isNullOrBlank()) {
                val safeData = dataStr.replace("\"", "\\\"")
                cmdBuilder.append(" -d \"$safeData\"")
            }

            val flags = intent.flags or Intent.FLAG_ACTIVITY_NEW_TASK
            cmdBuilder.append(" -f $flags")
            cmdBuilder.append(" --user 0")

            // 序列化 Intent extras 到 am start 参数（修复 intent:// scheme 中 S.rawUrl 等 extra 丢失）
            intent.extras?.let { bundle ->
                for (key in bundle.keySet()) {
                    val safeKey = key.replace("\"", "\\\"")
                    when (val value = bundle.get(key)) {
                        is String -> {
                            val safeVal = value.replace("\"", "\\\"")
                            cmdBuilder.append(" --es \"$safeKey\" \"$safeVal\"")
                        }
                        is Int -> cmdBuilder.append(" --ei \"$safeKey\" $value")
                        is Long -> cmdBuilder.append(" --el \"$safeKey\" $value")
                        is Float -> cmdBuilder.append(" --ef \"$safeKey\" $value")
                        is Boolean -> cmdBuilder.append(" --ez \"$safeKey\" $value")
                        else -> Log.d(TAG, "[Root直达] 跳过不支持的 extra 类型: $key -> ${value?.javaClass?.simpleName}")
                    }
                }
            }

            if (effectiveWindowMode != 1 && effectiveWindowMode != 4 && effectiveWindowMode != 6) {
                cmdBuilder.append(" --windowingMode $effectiveWindowMode")
            }

            val cmd = cmdBuilder.toString()
            Log.d(TAG, "[Root直达] 执行命令: $cmd")

            val output = PrivilegeEngine.execWithOutput(cmd)
            Log.d(TAG, "[Root直达] 终端输出: $output")

            val isSuccess = if (output != null) {
                !output.contains("Error:", ignoreCase = true) &&
                    !output.contains("SecurityException", ignoreCase = true)
            } else {
                PrivilegeEngine.exec(cmd)
            }

            // 若显式指令未成功，尝试带 NEW_TASK 与 --user 0 的 URI 格式兜底
            if (!isSuccess) {
                Log.w(TAG, "[Root直达] 显式指令未成功，尝试 URI 格式兜底")
                val intentUri = intent.toUri(Intent.URI_INTENT_SCHEME)
                val fallbackCmd = "am start -f $flags --user 0 \"$intentUri\""
                val fallbackOut = PrivilegeEngine.execWithOutput(fallbackCmd)
                if (fallbackOut != null && !fallbackOut.contains("Error:", ignoreCase = true)) {
                    return@withContext true
                }
            }

            isSuccess
        } catch (e: Exception) {
            Log.e(TAG, "[Root直达] 失败: ${e.message}", e)
            false
        }
    }

    /**
     * 梯次 2：通过 am start-in-vsync 利用 VSync 信号调度免提权打开未导出 Activity
     */
    private suspend fun launchViaVsync(
        intent: Intent,
        component: ComponentName
    ): Boolean = withContext(Dispatchers.IO) {
        val compStr = component.flattenToString()
        val flags = intent.flags or Intent.FLAG_ACTIVITY_NEW_TASK
        val cmdBuilder = StringBuilder("am start-in-vsync -n \"$compStr\"")
        val action = intent.action ?: Intent.ACTION_VIEW
        cmdBuilder.append(" -a \"$action\"")
        val dataStr = intent.dataString
        if (!dataStr.isNullOrBlank()) {
            val safeData = dataStr.replace("\"", "\\\"")
            cmdBuilder.append(" -d \"$safeData\"")
        }
        cmdBuilder.append(" -f $flags")
        val cmd = cmdBuilder.toString()
        Log.d(TAG, "[VSYNC直达] 执行命令: $cmd")

        // 优先普通非特权 sh 进程执行（零权限开箱即用）
        val directOk = runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val finished = process.waitFor(3_500L, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                false
            } else {
                process.exitValue() == 0
            }
        }.getOrDefault(false)

        if (directOk) return@withContext true

        // 若直接执行受某些 ROM 策略限制，且具备 Shizuku 则尝试 Shizuku 代理
        if (PrivilegeEngine.testShizuku()) {
            val shizukuOk = PrivilegeEngine.exec(cmd)
            if (shizukuOk) return@withContext true
        }

        // 回退 URI 格式
        val intentUri = intent.toUri(Intent.URI_INTENT_SCHEME)
        val uriCmd = "am start-in-vsync \"$intentUri\""
        runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", uriCmd))
            p.waitFor(3_500L, TimeUnit.MILLISECONDS) && p.exitValue() == 0
        }.getOrDefault(false)
    }

    /**
     * 增强型助手提权引擎：利用 launchAssist 稳定接口 + 深度 Bundle 注入
     * 解决 Android 15/16 底层签名变动导致的反射失败
     */
    suspend fun launchViaAssistant(context: Context, intent: Intent, options: Bundle? = null): Boolean {
        val resolver = context.contentResolver
        val component = intent.component ?: return false
        val assistantSetting = "assistant"

        // 1. 备份当前助手
        val currentAssistant = Settings.Secure.getString(resolver, assistantSetting)
        val targetAssistant = component.flattenToString()

        // 2. 深度封装参数：将 Intent 参数与窗口参数合二为一
        val args = intent.extras ?: Bundle()
        
        // 尝试注入小窗参数：使用安卓系统内部常用的 options 键名
        options?.let {
            args.putBundle("android:activity.optionsBundle", it)
            args.putBundle("android.activity.launchOptions", it)
        }

        // 额外记录目标，以防某些系统需要显式参数
        args.putString("target_component", targetAssistant)

        return try {
            // 3. 临时劫持
            Settings.Secure.putString(resolver, assistantSetting, targetAssistant)

            // 4. 触发启动：使用最稳健的 SearchManager 接口
            val searchManager = context.getSystemService(Context.SEARCH_SERVICE) as SearchManager
            
            Log.d(TAG, "--- 助手提权跳转开始 (launchAssist) ---")
            Log.d(TAG, "目标: $targetAssistant, 携带参数数量: ${args.size()}")

            HiddenApiBypass.invoke(SearchManager::class.java, searchManager, "launchAssist", args)

            // 5. 还原环境
            delay(250)
            Settings.Secure.putString(resolver, assistantSetting, currentAssistant)
            true
        } catch (e: Exception) {
            Log.e(TAG, "助手劫持启动失败: ${e.message}", e)
            Settings.Secure.putString(resolver, assistantSetting, currentAssistant)
            false
        }
    }

    /**
     * 对外暴露的浏览器/分流入口
     */
    fun openBrowser(context: Context, url: String) {
        val appContext = context.applicationContext
        globalScope.launch {
            val result = handleUrl(appContext, url)
            if (result.status == DispatchResult.NEED_SELECTOR) {
                val intent = Intent(appContext, com.moting.linkgo.BrowserSelectorActivity::class.java).apply {
                    putExtra("URL", result.finalUrl)
                    putExtra("TRACE_ID", result.traceId)
                    putExtra("STEP_INDEX", result.stepIndex)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(intent)
            }
        }
    }
}
