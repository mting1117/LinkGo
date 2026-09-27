package com.moting.linkgo.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.net.Uri
import android.util.LruCache
import android.graphics.drawable.Drawable
import com.moting.linkgo.model.ActivityInfo
import com.moting.linkgo.model.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 应用包信息仓库（单例）
 * 负责全局应用列表和 Activity 列表的扫描、缓存与实时更新
 */
object PackageRepository {
    private val _appList = MutableStateFlow<List<AppInfo>>(emptyList())
    val appList = _appList.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val loadMutex = Mutex()

    // 图标缓存 (限制为 200 个)
    private val iconCache = LruCache<String, Drawable>(200)

    // 记录确认找不到图标的包名，避免重复查询引发卡顿
    private val notFoundPackages = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    // 缓存最后一次查询的特定包名的 Activity
    private var cachedPackageName: String? = null
    private var cachedActivities: List<ActivityInfo> = emptyList()

    /**
     * 检测包可见性是否受到系统拦截（针对 Android 11+ 及国内厂商系统）
     * 核心逻辑：如果系统中只能看到极少数几个应用（如仅自身和少量 GMS 组件），则视为受限。
     */
    fun isVisibilityRestricted(context: Context): Boolean {
        return try {
            val pm = context.packageManager
            // 获取已安装包的基础列表（快速扫描）
            val packages = pm.getInstalledPackages(0)
            // 经验值：正常状态下 Android 系统（含预装）应用通常 > 20 个
            packages.size < 15
        } catch (e: Exception) {
            true
        }
    }

    /**
     * 加载全量应用列表（采用物理扫描与逻辑扫描结合的混合模式）
     */
    suspend fun loadAllApps(context: Context, force: Boolean = false) {
        loadMutex.withLock {
            if (!force && _appList.value.isNotEmpty()) return

            _isLoading.value = true
            withContext(Dispatchers.IO) {
                try {
                    val pm = context.packageManager
                    val appMap = mutableMapOf<String, AppInfo>()
                    
                    // 1. 物理扫描：基于 getInstalledPackages 获取基础集合
                    val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(
                            (PackageManager.GET_META_DATA or 
                             PackageManager.MATCH_DISABLED_COMPONENTS or 
                             PackageManager.MATCH_UNINSTALLED_PACKAGES).toLong()
                        ))
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getInstalledPackages(
                            PackageManager.GET_META_DATA or 
                            PackageManager.MATCH_DISABLED_COMPONENTS or 
                            PackageManager.MATCH_UNINSTALLED_PACKAGES
                        )
                    }

                    packages.forEach { pkgInfo ->
                        val applicationInfo = pkgInfo.applicationInfo ?: return@forEach
                        appMap[pkgInfo.packageName] = AppInfo(
                            label = applicationInfo.loadLabel(pm).toString(),
                            packageName = pkgInfo.packageName,
                            isSystemApp = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                        )
                    }

                    // 2. 逻辑扫描：通过 Intent 发现可能被物理列表过滤的应用（穿透某些深度限制）
                    val intents = listOf(
                        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), // 启动器应用
                        Intent(Intent.ACTION_VIEW, Uri.parse("http://")), // 浏览器
                        Intent(Intent.ACTION_SEND).setType("text/plain"), // 分享处理
                        Intent(Intent.ACTION_CREATE_SHORTCUT) // 快捷方式创建者
                    )

                    intents.forEach { intent ->
                        val resolves = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                        resolves.forEach { resolve ->
                            val pkgName = resolve.activityInfo.packageName
                            if (!appMap.containsKey(pkgName)) {
                                val appInfo = resolve.activityInfo.applicationInfo
                                appMap[pkgName] = AppInfo(
                                    label = resolve.loadLabel(pm).toString(),
                                    packageName = pkgName,
                                    isSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                                )
                            }
                        }
                    }

                    val collator = java.text.Collator.getInstance(java.util.Locale.CHINA)
                    val sortedApps = appMap.values.toList().sortedWith(
                        compareBy<AppInfo> { it.isSystemApp } // 用户应用在前
                            .thenComparator { a, b -> collator.compare(a.label, b.label) }
                    )
                    _appList.value = sortedApps
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    _isLoading.value = false
                }
            }
        }
    }

    fun getCachedIcon(packageName: String): Drawable? {
        return iconCache.get(packageName)
    }

    fun isPackageNotFound(packageName: String): Boolean {
        return notFoundPackages.contains(packageName)
    }

    /**
     * 判断特定包名是否已在本机安装
     */
    fun isAppInstalled(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val cleanPkg = if (packageName.contains("/")) packageName.substringBefore("/") else packageName
        if (_appList.value.any { it.packageName == cleanPkg }) return true
        return try {
            context.packageManager.getPackageInfo(cleanPkg, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 获取并缓存图标（按需加载）
     */
    fun getAppIcon(context: Context, packageName: String): Drawable? {
        if (notFoundPackages.contains(packageName)) return null
        
        // 1. 尝试从内存缓存获取
        iconCache.get(packageName)?.let { return it }

        // 2. 尝试从 PackageManager 加载
        return try {
            val pm = context.packageManager
            val icon = pm.getApplicationIcon(packageName)
            iconCache.put(packageName, icon)
            icon
        } catch (e: Exception) {
            notFoundPackages.add(packageName)
            null
        }
    }

    /**
     * 获取特定包名的全量 Activity 列表（含名称校准与入口标记）
     */
    suspend fun loadActivities(context: Context, packageName: String): List<ActivityInfo> {
        if (cachedPackageName == packageName && cachedActivities.isNotEmpty()) {
            return cachedActivities
        }

        return withContext(Dispatchers.IO) {
            try {
                val pm = context.packageManager
                
                // 增加 MATCH_DISABLED_COMPONENTS 确保获取被临时禁用但在系统目录中的组件
                val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(
                        (PackageManager.GET_ACTIVITIES or PackageManager.MATCH_DISABLED_COMPONENTS).toLong()
                    ))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES or PackageManager.MATCH_DISABLED_COMPONENTS)
                }

                val appLabel = packageInfo.applicationInfo?.loadLabel(pm)?.toString() ?: packageName
                val launchIntent = pm.getLaunchIntentForPackage(packageName)
                val launcherClass = launchIntent?.component?.className

                val activities = packageInfo.activities?.map { activity ->
                    val activityLabel = if (activity.labelRes != 0 || activity.nonLocalizedLabel != null) {
                        try {
                            activity.loadLabel(pm).toString()
                        } catch (e: Exception) {
                            appLabel
                        }
                    } else {
                        appLabel
                    }
                    
                    ActivityInfo(
                        name = activity.name,
                        label = activityLabel,
                        packageName = packageName,
                        isExported = activity.exported,
                        isLauncher = activity.name == launcherClass,
                        isMainOrView = activity.name == launcherClass || activity.exported
                    )
                }?.sortedWith(
                    compareByDescending<ActivityInfo> { it.isLauncher }
                        .thenByDescending { it.isExported }
                        .thenBy { it.label }
                ) ?: emptyList()

                cachedPackageName = packageName
                cachedActivities = activities
                activities
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    /**
     * 获取系统中安装的所有浏览器
     */
    suspend fun getInstalledBrowsers(context: Context): List<AppInfo> {
        return withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://"))
            val resolves = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            }

            val browsers = mutableMapOf<String, AppInfo>()
            resolves.forEach { resolve ->
                val pkgName = resolve.activityInfo.packageName
                if (!browsers.containsKey(pkgName)) {
                    val appInfo = resolve.activityInfo.applicationInfo
                    browsers[pkgName] = AppInfo(
                        label = resolve.loadLabel(pm).toString(),
                        packageName = pkgName,
                        isSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    )
                }
            }
            browsers.values.toList().sortedBy { it.label.lowercase() }
        }
    }

    /**
     * 获取可接收图片分享的应用列表（图片规则的目标应用选择器数据源）。
     *
     * 结构与 [getInstalledBrowsers] 一致；区别在于探测用的 Intent 是 `ACTION_SEND + image 类型`，
     * 因此返回的是「能收图的应用」，而不是「能开网页的应用」。
     *
     * `AndroidManifest.xml` 的 queries 必须声明 SEND 加 image 通配类型，
     * 否则 Android 11+ 的包可见性会直接返回空列表。
     */
    suspend fun getImageShareTargets(context: Context): List<AppInfo> {
        return withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            val resolves = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }

            val targets = mutableMapOf<String, AppInfo>()
            resolves.forEach { resolve ->
                val activityInfo = resolve.activityInfo ?: return@forEach
                val pkgName = activityInfo.packageName
                if (!targets.containsKey(pkgName)) {
                    val appInfo = activityInfo.applicationInfo
                    targets[pkgName] = AppInfo(
                        label = resolve.loadLabel(pm).toString(),
                        packageName = pkgName,
                        isSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    )
                }
            }
            val collator = java.text.Collator.getInstance(java.util.Locale.CHINA)
            targets.values.sortedWith(
                compareBy<AppInfo> { it.isSystemApp }
                    .thenComparator { a, b -> collator.compare(a.label, b.label) }
            )
        }
    }

    /**
     * 解析应用的入口 Activity 类名
     */
    fun resolveMainActivity(context: Context, packageName: String): String? {
        return try {
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
            launchIntent?.component?.className
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取应用名称（支持组件与纯包名）
     */
    fun getAppLabel(context: Context, packageName: String): String {
        if (packageName.isBlank()) return ""
        val cleanPkg = if (packageName.contains("/")) packageName.substringBefore("/") else packageName
        val cached = _appList.value.firstOrNull { it.packageName == cleanPkg }
        if (cached != null) return cached.label
        return try {
            val pm = context.packageManager
            if (packageName.contains("/")) {
                val comp = android.content.ComponentName.unflattenFromString(packageName)
                if (comp != null) {
                    pm.getActivityInfo(comp, 0).loadLabel(pm).toString()
                } else {
                    pm.getApplicationInfo(cleanPkg, 0).loadLabel(pm).toString()
                }
            } else {
                pm.getApplicationInfo(cleanPkg, 0).loadLabel(pm).toString()
            }
        } catch (e: Exception) {
            cleanPkg
        }
    }

    /**
     * 清除缓存
     */
    fun invalidateCache() {
        cachedPackageName = null
        cachedActivities = emptyList()
        iconCache.evictAll()
        notFoundPackages.clear()
    }
}
