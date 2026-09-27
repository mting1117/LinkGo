package com.moting.linkgo.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

/**
 * DataStore 实例扩展
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "linkgo_settings")

/**
 * 剪贴板触发胶囊的停靠位置：贴边方向 + 垂直归一化比例。
 */
data class CapsuleAnchor(val edge: String, val yRatio: Float)

/**
 * 通知样式枚举：
 * 1. STANDARD: 标准通知 (兼容 Android 8.0+)
 * 2. LIVE_ACTIVITY: 实时活动通知 (Android 16+)
 * 3. SUPER_ISLAND: 小米超级岛 (HyperOS 焦点通知)
 */
enum class NotificationStyle(val value: String, val title: String) {
    STANDARD("standard", "标准通知"),
    LIVE_ACTIVITY("live_activity", "实时活动通知"),
    SUPER_ISLAND("super_island", "小米超级岛");

    companion object {
        fun fromValue(value: String?): NotificationStyle {
            return entries.firstOrNull { it.value == value } ?: defaultStyle()
        }

        fun defaultStyle(): NotificationStyle {
            return if (com.moting.linkgo.util.HyperIslandHelper.isEligibleDevice()) {
                SUPER_ISLAND
            } else if (android.os.Build.VERSION.SDK_INT >= 36) {
                LIVE_ACTIVITY
            } else {
                STANDARD
            }
        }
    }
}

/**
 * 内存瞬时缓存单例：解决 Compose 切页面时 collectAsState 默认值跳变问题
 */
object SettingsCache {
    var isLoaded: Boolean = false
    var windowConfig: WindowConfig = WindowConfig()
    var floatingBottomBarEnabled: Boolean = true
    var clipboardMonitorEnabled: Boolean = false
    var clipboardMonitorBackend: String = com.moting.linkgo.clipboard.ClipboardBackend.DEFAULT
    // 应用内链接捕获：0=关闭 1=拦截 2=询问
    var appLinkCaptureMode: Int = 0
    // 询问模式下，点击胶囊外部跳转后是否自动销毁原应用内置浏览器页面
    var appLinkAskAutoFinish: Boolean = false
    // 直接拦截模式下「仅命中规则时拦截」：未命中任何跳转规则（本该走备选浏览器）时一律放行
    var appLinkRuleOnlyIntercept: Boolean = false
    // 应用内链接捕获的接管应用列表（仅列表内的应用打开链接才被 LinkGo 处理）
    var appLinkCaptureApps: List<com.moting.linkgo.model.CaptureApp> = com.moting.linkgo.model.CaptureApp.defaults()
    val enabledCapturePackageNames: List<String> get() = appLinkCaptureApps.filter { it.isEnabled }.map { it.packageName }
    // 应用内链接捕获的域名放行规则（来源应用 + 域名，命中视为生态内内容不捕获）
    var appLinkExemptDomains: List<com.moting.linkgo.model.ExemptDomain> = com.moting.linkgo.model.ExemptDomain.defaults()
    // 跳转规则表快照（JSON，格式与 DataStore 快照一致）：随捕获配置下发给 HookEntry，
    // 用于「规则目标应用 == 发起应用 → 放行」的断环判定
    var dispatchRules: String = "[]"
    var browserSelectorTimer: Int = 5
    var showTriggerToast: Boolean = false
    var dynamicColorEnabled: Boolean = true
    var backgroundBlurEnabled: Boolean = true
    var fallbackBrowser: String? = null
    var fallbackWindowMode: Int = -1
    var fallbackPreheatEnabled: Boolean = false
    var fallbackPreheatDelay: Long = 0L
    var excludeFromRecents: Boolean = false
    // 备选浏览器跳转时是否隐藏目标应用的最近任务卡片（与 LinkGo 自身可见性各自独立）
    var fallbackExcludeFromRecents: Boolean = false
    var normalizationEnabled: Boolean = true
    var showRichNotification: Boolean = false
    var notificationStyle: NotificationStyle = NotificationStyle.defaultStyle()
    var notificationAutoDismissSeconds: Int = 5
    var superIslandBypassEnabled: Boolean = true
    var superIslandBypassDurationMs: Int = 100
    var superIslandOuterGlow: Boolean = false
    var superIslandDragShareEnabled: Boolean = true
    var superIslandLoggingEnabled: Boolean = false
    var assistantBypassEnabled: Boolean = false
    var autoUnfreezeEnabled: Boolean = true
    var unfreezeShowToast: Boolean = true
    var globalPrivilegeEnabled: Boolean = true
    var capShizukuSilentGrant: Boolean = true
    var capA11yHealBoot: Boolean = true
    var capA11yHealApp: Boolean = true
    var capA11yPrewarmClipboard: Boolean = true
    var capBatterySilentWhitelist: Boolean = true
    var clearClipboardAfterJump: Boolean = false
    var clipboardChangeToastEnabled: Boolean = false
    var clipboardBroadcastEnabled: Boolean = false
    var clipboardAutoDismissSeconds: Int = 5
    var maxMultiCapsuleCount: Int = 3
    var capsuleDismissOnTouchOutside: Boolean = true
    var lastSystemPrivilege: String = "shizuku"
    var lastSystemWay: String = "hidden_api"
    var privilegeMode: String = "auto"
    var lastAppLinkSubMode: Int = 1
    var capsuleEdge: String = "right"
    var capsuleYRatio: Float = 2f / 3f
    var extractionPatterns: List<com.moting.linkgo.model.ExtractPattern> = emptyList()
    var imageRules: List<com.moting.linkgo.model.ImageRule> = emptyList()
    var jumpHistory: List<com.moting.linkgo.model.JumpRecord> = emptyList()
    var edgeGestureConfig: com.moting.linkgo.model.EdgeGestureConfig = com.moting.linkgo.model.EdgeGestureConfig()

    /**
     * 是否保留屏幕取图入口（点空白指认 / 滑动框选 / 截全屏）。
     *
     * 取图只有两条去向：解出二维码走文本链路，没解出交给图片规则。
     * 两者都不存在时（未开「屏幕二维码识别」且没有启用中的图片规则），用户点下去也取不到东西，
     * 因此屏幕识别与滑动直达都不该为此停留。
     */
    val screenCaptureEnabled: Boolean
        get() = edgeGestureConfig.screenQrEnabled || imageRules.any { it.isEnabled }
}

/**
 * 设置存储库，管理小窗的尺寸、位置和模式配置
 */
class SettingsRepository(private val context: Context) {

    val currentJumpHistory: List<com.moting.linkgo.model.JumpRecord> get() = SettingsCache.jumpHistory
    val currentEdgeGestureConfig: com.moting.linkgo.model.EdgeGestureConfig get() = SettingsCache.edgeGestureConfig

    val currentFloatingBottomBar: Boolean get() = SettingsCache.floatingBottomBarEnabled
    val currentWindowConfig: WindowConfig get() = SettingsCache.windowConfig
    val currentClipboardMonitorEnabled: Boolean get() = SettingsCache.clipboardMonitorEnabled
    val currentClipboardMonitorBackend: String get() = SettingsCache.clipboardMonitorBackend
    val currentAppLinkCaptureMode: Int get() = SettingsCache.appLinkCaptureMode
    val currentAppLinkAskAutoFinish: Boolean get() = SettingsCache.appLinkAskAutoFinish
    val currentAppLinkRuleOnlyIntercept: Boolean get() = SettingsCache.appLinkRuleOnlyIntercept
    val currentAppLinkCaptureApps: List<com.moting.linkgo.model.CaptureApp> get() = SettingsCache.appLinkCaptureApps
    val currentAppLinkCapturePackageNames: List<String> get() = SettingsCache.appLinkCaptureApps.map { it.packageName }
    val currentAppLinkExemptDomains: List<com.moting.linkgo.model.ExemptDomain> get() = SettingsCache.appLinkExemptDomains
    val currentBrowserSelectorTimer: Int get() = SettingsCache.browserSelectorTimer
    val currentShowTriggerToast: Boolean get() = SettingsCache.showTriggerToast
    val currentDynamicColorEnabled: Boolean get() = SettingsCache.dynamicColorEnabled
    val currentBackgroundBlurEnabled: Boolean get() = SettingsCache.backgroundBlurEnabled
    val currentFallbackBrowser: String? get() = SettingsCache.fallbackBrowser
    val currentFallbackWindowMode: Int get() = SettingsCache.fallbackWindowMode
    val currentFallbackPreheatEnabled: Boolean get() = SettingsCache.fallbackPreheatEnabled
    val currentFallbackPreheatDelay: Long get() = SettingsCache.fallbackPreheatDelay
    val currentExcludeFromRecents: Boolean get() = SettingsCache.excludeFromRecents
    val currentFallbackExcludeFromRecents: Boolean get() = SettingsCache.fallbackExcludeFromRecents
    val currentNormalizationEnabled: Boolean get() = SettingsCache.normalizationEnabled
    val currentShowRichNotification: Boolean get() = SettingsCache.showRichNotification
    val currentNotificationStyle: NotificationStyle get() = SettingsCache.notificationStyle
    val currentNotificationAutoDismissSeconds: Int get() = SettingsCache.notificationAutoDismissSeconds
    val currentSuperIslandBypassEnabled: Boolean get() = SettingsCache.superIslandBypassEnabled
    val currentSuperIslandBypassDurationMs: Int get() = SettingsCache.superIslandBypassDurationMs
    val currentSuperIslandOuterGlow: Boolean get() = SettingsCache.superIslandOuterGlow
    val currentSuperIslandDragShareEnabled: Boolean get() = SettingsCache.superIslandDragShareEnabled
    val currentSuperIslandLoggingEnabled: Boolean get() = SettingsCache.superIslandLoggingEnabled
    val currentAssistantBypassEnabled: Boolean get() = SettingsCache.assistantBypassEnabled
    val currentAutoUnfreezeEnabled: Boolean get() = SettingsCache.autoUnfreezeEnabled
    val currentUnfreezeShowToast: Boolean get() = SettingsCache.unfreezeShowToast
    val currentGlobalPrivilegeEnabled: Boolean get() = SettingsCache.globalPrivilegeEnabled
    val currentCapShizukuSilentGrant: Boolean get() = SettingsCache.capShizukuSilentGrant
    val currentCapA11yHealBoot: Boolean get() = SettingsCache.capA11yHealBoot
    val currentCapA11yHealApp: Boolean get() = SettingsCache.capA11yHealApp
    val currentCapA11yPrewarmClipboard: Boolean get() = SettingsCache.capA11yPrewarmClipboard
    val currentCapBatterySilentWhitelist: Boolean get() = SettingsCache.capBatterySilentWhitelist
    val currentClearClipboardAfterJump: Boolean get() = SettingsCache.clearClipboardAfterJump
    val currentClipboardChangeToastEnabled: Boolean get() = SettingsCache.clipboardChangeToastEnabled
    val currentClipboardBroadcastEnabled: Boolean get() = SettingsCache.clipboardBroadcastEnabled
    val currentClipboardAutoDismissSeconds: Int get() = SettingsCache.clipboardAutoDismissSeconds
    val currentMaxMultiCapsuleCount: Int get() = SettingsCache.maxMultiCapsuleCount
    val currentCapsuleDismissOnTouchOutside: Boolean get() = SettingsCache.capsuleDismissOnTouchOutside
    val currentLastSystemPrivilege: String get() = SettingsCache.lastSystemPrivilege
    val currentLastSystemWay: String get() = SettingsCache.lastSystemWay
    val currentPrivilegeMode: String get() = SettingsCache.privilegeMode
    val currentLastAppLinkSubMode: Int get() = SettingsCache.lastAppLinkSubMode

    companion object {
        private const val FAST_CACHE_NAME = "linkgo_fast_cache"

        fun fastSyncCache(ctx: Context) {
            if (SettingsCache.isLoaded) return
            try {
                val sp = ctx.getSharedPreferences(FAST_CACHE_NAME, Context.MODE_PRIVATE)
                if (sp.contains("is_initialized")) {
                    SettingsCache.floatingBottomBarEnabled = sp.getBoolean("floating_bottom_bar_enabled", true)
                    SettingsCache.clipboardMonitorEnabled = sp.getBoolean("clipboard_monitor_enabled", false)
                    SettingsCache.clipboardMonitorBackend = sp.getString("clipboard_monitor_backend", com.moting.linkgo.clipboard.ClipboardBackend.DEFAULT) ?: com.moting.linkgo.clipboard.ClipboardBackend.DEFAULT
                    // 图片规则在冷启动补偿路径上就要能匹配（LSPosed 广播会直接拉起进程），
                    // 因此一并放进 0ms 快照；只是一个 JSON 字符串，读取成本可忽略。
                    val imageRulesJson = sp.getString("image_rules", null)
                    SettingsCache.imageRules = if (imageRulesJson.isNullOrBlank()) {
                        emptyList()
                    } else {
                        parseImageRules(imageRulesJson)
                    }
                    SettingsCache.appLinkCaptureMode = sp.getInt("app_link_capture_mode", 0).coerceIn(0, 2)
                    SettingsCache.appLinkAskAutoFinish = sp.getBoolean("app_link_ask_auto_finish", false)
                    SettingsCache.appLinkRuleOnlyIntercept = sp.getBoolean("app_link_rule_only_intercept", false)
                    val captureAppsJson = sp.getString("app_link_capture_apps", null)
                    if (!captureAppsJson.isNullOrBlank()) {
                        SettingsCache.appLinkCaptureApps = parseCaptureApps(captureAppsJson)
                    } else {
                        SettingsCache.appLinkCaptureApps = com.moting.linkgo.model.CaptureApp.defaults()
                    }
                    val exemptJson = sp.getString("app_link_exempt_domains", null)
                    if (!exemptJson.isNullOrBlank()) {
                        SettingsCache.appLinkExemptDomains = parseExemptDomains(exemptJson)
                    } else {
                        SettingsCache.appLinkExemptDomains = com.moting.linkgo.model.ExemptDomain.defaults()
                    }
                    // 跳转规则表快照：应用内链接捕获的断环判定依赖它，冷启动路径上必须即时可用
                    SettingsCache.dispatchRules = sp.getString("dispatch_rules", "[]") ?: "[]"
                    SettingsCache.browserSelectorTimer = sp.getInt("browser_selector_timer", 5)
                    SettingsCache.showTriggerToast = sp.getBoolean("show_trigger_toast", false)
                    SettingsCache.dynamicColorEnabled = sp.getBoolean("dynamic_color_enabled", true)
                    SettingsCache.backgroundBlurEnabled = sp.getBoolean("background_blur_enabled", true)
                    SettingsCache.fallbackBrowser = sp.getString("fallback_browser", null)
                    SettingsCache.fallbackWindowMode = sp.getInt("fallback_window_mode", -1)
                    SettingsCache.fallbackPreheatEnabled = sp.getBoolean("fallback_preheat_enabled", false)
                    SettingsCache.fallbackPreheatDelay = sp.getLong("fallback_preheat_delay", 500L)
                    SettingsCache.excludeFromRecents = sp.getBoolean("exclude_from_recents", false)
                    SettingsCache.fallbackExcludeFromRecents = sp.getBoolean("fallback_exclude_from_recents", false)
                    SettingsCache.normalizationEnabled = sp.getBoolean("normalization_enabled", true)
                    SettingsCache.showRichNotification = sp.getBoolean("show_rich_notification", false)
                    SettingsCache.notificationStyle = NotificationStyle.fromValue(sp.getString("notification_style", null))
                    SettingsCache.notificationAutoDismissSeconds = sp.getInt("notification_auto_dismiss_seconds", 5).coerceIn(2, 30)
                    SettingsCache.superIslandBypassEnabled = sp.getBoolean("super_island_bypass_enabled", true)
                    SettingsCache.superIslandBypassDurationMs = sp.getInt("super_island_bypass_duration_ms", 1500).coerceIn(100, 3000)
                    SettingsCache.superIslandOuterGlow = sp.getBoolean("super_island_outer_glow", false)
                    SettingsCache.superIslandDragShareEnabled = sp.getBoolean("super_island_drag_share_enabled", true)
                    SettingsCache.superIslandLoggingEnabled = sp.getBoolean("super_island_logging_enabled", false)
                    SettingsCache.assistantBypassEnabled = sp.getBoolean("assistant_bypass_enabled", false)
                    SettingsCache.autoUnfreezeEnabled = sp.getBoolean("auto_unfreeze_enabled", true)
                    SettingsCache.unfreezeShowToast = sp.getBoolean("unfreeze_show_toast", true)
                    SettingsCache.globalPrivilegeEnabled = sp.getBoolean("global_privilege_enabled", true)
                    SettingsCache.capShizukuSilentGrant = sp.getBoolean("cap_shizuku_silent_grant", true)
                    SettingsCache.capA11yHealBoot = sp.getBoolean("cap_a11y_heal_boot", true)
                    SettingsCache.capA11yHealApp = sp.getBoolean("cap_a11y_heal_app", true)
                    SettingsCache.capA11yPrewarmClipboard = sp.getBoolean("cap_a11y_prewarm_clipboard", true)
                    SettingsCache.capBatterySilentWhitelist = sp.getBoolean("cap_battery_silent_whitelist", true)
                    SettingsCache.clearClipboardAfterJump = sp.getBoolean("clear_clipboard_after_jump", false)
                    SettingsCache.clipboardChangeToastEnabled = sp.getBoolean("clipboard_change_toast_enabled", false)
                    SettingsCache.clipboardAutoDismissSeconds = sp.getInt("clipboard_auto_dismiss_seconds", 5)
                    SettingsCache.maxMultiCapsuleCount = sp.getInt("max_multi_capsule_count", 3)
                    SettingsCache.capsuleDismissOnTouchOutside = sp.getBoolean("capsule_dismiss_on_touch_outside", true)
                    SettingsCache.lastSystemPrivilege = sp.getString("last_system_privilege", "shizuku") ?: "shizuku"
                    SettingsCache.lastSystemWay = sp.getString("last_system_way", "hidden_api") ?: "hidden_api"
                    SettingsCache.privilegeMode = sp.getString("privilege_mode", "auto") ?: "auto"
                    SettingsCache.lastAppLinkSubMode = sp.getInt("last_app_link_sub_mode", 1).coerceIn(1, 2)
                    SettingsCache.capsuleEdge = sp.getString("capsule_edge", "right") ?: "right"
                    SettingsCache.capsuleYRatio = sp.getFloat("capsule_y_ratio", 2f / 3f)
                    
                    val patternsJson = sp.getString("extraction_patterns", null)
                    if (!patternsJson.isNullOrBlank()) {
                        SettingsCache.extractionPatterns = parseExtractionPatterns(patternsJson)
                    } else {
                        SettingsCache.extractionPatterns = com.moting.linkgo.model.ExtractPattern.builtinDefaults()
                    }

                    SettingsCache.edgeGestureConfig = com.moting.linkgo.model.EdgeGestureConfig(
                        enabled = sp.getBoolean("edge_gesture_enabled", false),
                        widthDp = if (sp.contains("edge_gesture_width_dp")) {
                            sp.getInt("edge_gesture_width_dp", 18)
                        } else if (sp.contains("edge_gesture_width_ratio")) {
                            (sp.getFloat("edge_gesture_width_ratio", 0.045f) * 392f).roundToInt().coerceIn(6, 60)
                        } else {
                            18
                        },
                        heightDp = if (sp.contains("edge_gesture_height_dp")) {
                            sp.getInt("edge_gesture_height_dp", 140)
                        } else if (sp.contains("edge_gesture_height_ratio")) {
                            (sp.getFloat("edge_gesture_height_ratio", 0.160f) * 850f).roundToInt().coerceIn(40, 400)
                        } else {
                            140
                        },
                        verticalRatio = sp.getFloat("edge_gesture_vertical_ratio", 0.350f),
                        horizontalRatio = sp.getFloat("edge_gesture_horizontal_ratio", 0.000f),
                        edgeAlpha = sp.getFloat("edge_gesture_alpha", 0.60f),
                        mirrorEnabled = sp.getBoolean("edge_gesture_mirror", true),
                        clickAction = com.moting.linkgo.model.GestureAction.fromValue(sp.getString("edge_gesture_click_action", "screen_recognition")),
                        swipeAction = com.moting.linkgo.model.GestureAction.fromValue(sp.getString("edge_gesture_swipe_action", "radar_direct")),
                        longPressAction = com.moting.linkgo.model.GestureAction.fromValue(sp.getString("edge_gesture_long_press_action", "none")),
                        ballRadiusDp = sp.getInt("edge_gesture_ball_radius", 28),
                        ballStrokeWidthDp = sp.getFloat("edge_gesture_ball_stroke", 2.5f),
                        ballAlpha = sp.getFloat("edge_gesture_ball_alpha", 0.65f),
                        ballStyle = com.moting.linkgo.model.BallStyle.fromValue(sp.getString("edge_gesture_ball_style", "stroke_glow")),
                        controlMode = com.moting.linkgo.model.PointerControlMode.fromValue(sp.getString("edge_gesture_control_mode", "remote_pointer")),
                        pointerSensitivity = sp.getFloat("edge_gesture_pointer_sensitivity", 2.2f),
                        pointerInitialDistanceDp = sp.getInt("edge_gesture_pointer_initial_distance", 96),
                        fingerOffsetYDp = sp.getInt("edge_gesture_finger_offset_y", 36),
                        gestureHapticEnabled = sp.getBoolean("edge_gesture_gesture_haptic", sp.getBoolean("edge_gesture_haptic", true)),
                        collisionHapticEnabled = sp.getBoolean("edge_gesture_collision_haptic", sp.getBoolean("edge_gesture_haptic", true)),
                        bubbleSquishEnabled = sp.getBoolean("edge_gesture_bubble_squish", true),
                        stayDurationMs = sp.getInt("edge_gesture_stay_duration_ms", 800),
                        directSingleImageRule = sp.getBoolean("edge_gesture_direct_single_image_rule", true),
                        screenQrEnabled = sp.getBoolean("edge_gesture_screen_qr_enabled", false),
                        orientationScope = com.moting.linkgo.model.OrientationScope.fromValue(sp.getString("edge_gesture_orientation_scope", "all")),
                        appScopeMode = com.moting.linkgo.model.AppScopeMode.fromValue(sp.getString("edge_gesture_app_scope_mode", "blacklist")),
                        appScopePackages = sp.getStringSet("edge_gesture_app_scope_packages", emptySet()) ?: emptySet()
                    )

                    SettingsCache.windowConfig = WindowConfig(
                        portWidthRatio = sp.getFloat("port_width", 0.8f),
                        portHeightRatio = sp.getFloat("port_height", 0.55f),
                        portXRatio = sp.getFloat("port_x", 0.1f),
                        portYRatio = sp.getFloat("port_y", 0.225f),
                        landWidthRatio = sp.getFloat("land_width", 0.68f),
                        landHeightRatio = sp.getFloat("land_height", 0.4f),
                        landXRatio = sp.getFloat("land_x", 0.16f),
                        landYRatio = sp.getFloat("land_y", 0.05f),
                        windowingMode = sp.getInt("window_mode", 5),
                        isEnabled = sp.getBoolean("small_window_enabled", true)
                    )

                    SettingsCache.isLoaded = true
                    android.util.Log.i("SettingsRepo", "fastSyncCache: 0ms 同步快照读取就绪 (monitorEnabled=${SettingsCache.clipboardMonitorEnabled}, backend=${SettingsCache.clipboardMonitorBackend}, windowMode=${SettingsCache.windowConfig.windowingMode})")
                }
            } catch (e: Exception) {
                android.util.Log.w("SettingsRepo", "fastSyncCache error: ${e.message}")
            }
        }

        fun persistFastCache(ctx: Context) {
            try {
                val sp = ctx.getSharedPreferences(FAST_CACHE_NAME, Context.MODE_PRIVATE)
                val eg = SettingsCache.edgeGestureConfig
                sp.edit()
                    .putBoolean("is_initialized", true)
                    .putBoolean("floating_bottom_bar_enabled", SettingsCache.floatingBottomBarEnabled)
                    .putBoolean("clipboard_monitor_enabled", SettingsCache.clipboardMonitorEnabled)
                    .putString("clipboard_monitor_backend", SettingsCache.clipboardMonitorBackend)
                    .putInt("app_link_capture_mode", SettingsCache.appLinkCaptureMode)
                    .putBoolean("app_link_ask_auto_finish", SettingsCache.appLinkAskAutoFinish)
                    .putBoolean("app_link_rule_only_intercept", SettingsCache.appLinkRuleOnlyIntercept)
                    .putString("app_link_capture_apps", serializeCaptureApps(SettingsCache.appLinkCaptureApps))
                    .putString("app_link_exempt_domains", serializeExemptDomains(SettingsCache.appLinkExemptDomains))
                    .putString("dispatch_rules", SettingsCache.dispatchRules)
                    .putInt("browser_selector_timer", SettingsCache.browserSelectorTimer)
                    .putBoolean("show_trigger_toast", SettingsCache.showTriggerToast)
                    .putBoolean("dynamic_color_enabled", SettingsCache.dynamicColorEnabled)
                    .putBoolean("background_blur_enabled", SettingsCache.backgroundBlurEnabled)
                    .putString("fallback_browser", SettingsCache.fallbackBrowser)
                    .putInt("fallback_window_mode", SettingsCache.fallbackWindowMode)
                    .putBoolean("fallback_preheat_enabled", SettingsCache.fallbackPreheatEnabled)
                    .putLong("fallback_preheat_delay", SettingsCache.fallbackPreheatDelay)
                    .putBoolean("exclude_from_recents", SettingsCache.excludeFromRecents)
                    .putBoolean("fallback_exclude_from_recents", SettingsCache.fallbackExcludeFromRecents)
                    .putBoolean("normalization_enabled", SettingsCache.normalizationEnabled)
                    .putBoolean("show_rich_notification", SettingsCache.showRichNotification)
                    .putString("notification_style", SettingsCache.notificationStyle.value)
                    .putInt("notification_auto_dismiss_seconds", SettingsCache.notificationAutoDismissSeconds)
                    .putBoolean("super_island_bypass_enabled", SettingsCache.superIslandBypassEnabled)
                    .putInt("super_island_bypass_duration_ms", SettingsCache.superIslandBypassDurationMs)
                    .putBoolean("super_island_outer_glow", SettingsCache.superIslandOuterGlow)
                    .putBoolean("super_island_drag_share_enabled", SettingsCache.superIslandDragShareEnabled)
                    .putBoolean("super_island_logging_enabled", SettingsCache.superIslandLoggingEnabled)
                    .putBoolean("assistant_bypass_enabled", SettingsCache.assistantBypassEnabled)
                    .putBoolean("auto_unfreeze_enabled", SettingsCache.autoUnfreezeEnabled)
                    .putBoolean("unfreeze_show_toast", SettingsCache.unfreezeShowToast)
                    .putBoolean("global_privilege_enabled", SettingsCache.globalPrivilegeEnabled)
                    .putBoolean("cap_shizuku_silent_grant", SettingsCache.capShizukuSilentGrant)
                    .putBoolean("cap_a11y_heal_boot", SettingsCache.capA11yHealBoot)
                    .putBoolean("cap_a11y_heal_app", SettingsCache.capA11yHealApp)
                    .putBoolean("cap_a11y_prewarm_clipboard", SettingsCache.capA11yPrewarmClipboard)
                    .putBoolean("cap_battery_silent_whitelist", SettingsCache.capBatterySilentWhitelist)
                    .putBoolean("clear_clipboard_after_jump", SettingsCache.clearClipboardAfterJump)
                    .putBoolean("clipboard_change_toast_enabled", SettingsCache.clipboardChangeToastEnabled)
                    .putInt("clipboard_auto_dismiss_seconds", SettingsCache.clipboardAutoDismissSeconds)
                    .putInt("max_multi_capsule_count", SettingsCache.maxMultiCapsuleCount)
                    .putBoolean("capsule_dismiss_on_touch_outside", SettingsCache.capsuleDismissOnTouchOutside)
                    .putString("last_system_privilege", SettingsCache.lastSystemPrivilege)
                    .putString("last_system_way", SettingsCache.lastSystemWay)
                    .putString("privilege_mode", SettingsCache.privilegeMode)
                    .putInt("last_app_link_sub_mode", SettingsCache.lastAppLinkSubMode)
                    .putString("capsule_edge", SettingsCache.capsuleEdge)
                    .putFloat("capsule_y_ratio", SettingsCache.capsuleYRatio)
                    .putString("extraction_patterns", serializeExtractionPatterns(SettingsCache.extractionPatterns))
                    .putString("image_rules", serializeImageRules(SettingsCache.imageRules))
                    .putBoolean("edge_gesture_enabled", eg.enabled)
                    .putInt("edge_gesture_width_dp", eg.widthDp)
                    .putInt("edge_gesture_height_dp", eg.heightDp)
                    .putFloat("edge_gesture_width_ratio", eg.widthDp / 392f)
                    .putFloat("edge_gesture_height_ratio", eg.heightDp / 850f)
                    .putFloat("edge_gesture_vertical_ratio", eg.verticalRatio)
                    .putFloat("edge_gesture_horizontal_ratio", eg.horizontalRatio)
                    .putFloat("edge_gesture_alpha", eg.edgeAlpha)
                    .putBoolean("edge_gesture_mirror", eg.mirrorEnabled)
                    .putString("edge_gesture_click_action", eg.clickAction.value)
                    .putString("edge_gesture_swipe_action", eg.swipeAction.value)
                    .putString("edge_gesture_long_press_action", eg.longPressAction.value)
                    .putInt("edge_gesture_ball_radius", eg.ballRadiusDp)
                    .putFloat("edge_gesture_ball_stroke", eg.ballStrokeWidthDp)
                    .putFloat("edge_gesture_ball_alpha", eg.ballAlpha)
                    .putString("edge_gesture_ball_style", eg.ballStyle.value)
                    .putString("edge_gesture_control_mode", eg.controlMode.value)
                    .putFloat("edge_gesture_pointer_sensitivity", eg.pointerSensitivity)
                    .putInt("edge_gesture_pointer_initial_distance", eg.pointerInitialDistanceDp)
                    .putInt("edge_gesture_finger_offset_y", eg.fingerOffsetYDp)
                    .putBoolean("edge_gesture_gesture_haptic", eg.gestureHapticEnabled)
                    .putBoolean("edge_gesture_collision_haptic", eg.collisionHapticEnabled)
                    .putBoolean("edge_gesture_haptic", eg.collisionHapticEnabled)
                    .putBoolean("edge_gesture_bubble_squish", eg.bubbleSquishEnabled)
                    .putInt("edge_gesture_stay_duration_ms", eg.stayDurationMs)
                    .putBoolean("edge_gesture_direct_single_image_rule", eg.directSingleImageRule)
                    .putBoolean("edge_gesture_screen_qr_enabled", eg.screenQrEnabled)
                    .putString("edge_gesture_orientation_scope", eg.orientationScope.value)
                    .putString("edge_gesture_app_scope_mode", eg.appScopeMode.value)
                    .putStringSet("edge_gesture_app_scope_packages", eg.appScopePackages)
                    .putFloat("port_width", SettingsCache.windowConfig.portWidthRatio)
                    .putFloat("port_height", SettingsCache.windowConfig.portHeightRatio)
                    .putFloat("port_x", SettingsCache.windowConfig.portXRatio)
                    .putFloat("port_y", SettingsCache.windowConfig.portYRatio)
                    .putFloat("land_width", SettingsCache.windowConfig.landWidthRatio)
                    .putFloat("land_height", SettingsCache.windowConfig.landHeightRatio)
                    .putFloat("land_x", SettingsCache.windowConfig.landXRatio)
                    .putFloat("land_y", SettingsCache.windowConfig.landYRatio)
                    .putInt("window_mode", SettingsCache.windowConfig.windowingMode)
                    .putBoolean("small_window_enabled", SettingsCache.windowConfig.isEnabled)
                    .apply()
            } catch (e: Exception) {
                android.util.Log.w("SettingsRepo", "persistFastCache error: ${e.message}")
            }
        }

        private val PORT_WIDTH = floatPreferencesKey("port_width")
        private val PORT_HEIGHT = floatPreferencesKey("port_height")
        private val PORT_X = floatPreferencesKey("port_x")
        private val PORT_Y = floatPreferencesKey("port_y")

        private val LAND_WIDTH = floatPreferencesKey("land_width")
        private val LAND_HEIGHT = floatPreferencesKey("land_height")
        private val LAND_X = floatPreferencesKey("land_x")
        private val LAND_Y = floatPreferencesKey("land_y")

        private val WINDOW_MODE = intPreferencesKey("window_mode")
        private val SMALL_WINDOW_ENABLED = booleanPreferencesKey("small_window_enabled")
        private val DISPATCH_RULES = stringPreferencesKey("dispatch_rules")
        private val IMAGE_RULES = stringPreferencesKey("image_rules")
        private val FALLBACK_BROWSER = stringPreferencesKey("fallback_browser")
        private val EXCLUDE_FROM_RECENTS = booleanPreferencesKey("exclude_from_recents")
        private val FALLBACK_EXCLUDE_FROM_RECENTS = booleanPreferencesKey("fallback_exclude_from_recents")
        private val SHOW_TRIGGER_TOAST = booleanPreferencesKey("show_trigger_toast")
        private val FALLBACK_WINDOW_MODE = intPreferencesKey("fallback_window_mode")
        private val JUMP_HISTORY = stringPreferencesKey("jump_history")
        private val TOTAL_JUMP_COUNT = intPreferencesKey("total_jump_count")
        private val NORMALIZATION_ENABLED = booleanPreferencesKey("normalization_enabled")
        private val NORMALIZATION_REGEX = stringPreferencesKey("normalization_regex")
        private val NORMALIZATION_TEMPLATE = stringPreferencesKey("normalization_template")
        private val SHOW_RICH_NOTIFICATION = booleanPreferencesKey("show_rich_notification")
        private val NOTIFICATION_STYLE = stringPreferencesKey("notification_style")
        private val NOTIFICATION_AUTO_DISMISS_SECONDS = intPreferencesKey("notification_auto_dismiss_seconds")
        private val SUPER_ISLAND_BYPASS_ENABLED = booleanPreferencesKey("super_island_bypass_enabled")
        private val SUPER_ISLAND_BYPASS_DURATION_MS = intPreferencesKey("super_island_bypass_duration_ms")
        private val SUPER_ISLAND_OUTER_GLOW = booleanPreferencesKey("super_island_outer_glow")
        private val SUPER_ISLAND_DRAG_SHARE_ENABLED = booleanPreferencesKey("super_island_drag_share_enabled")
        private val SUPER_ISLAND_LOGGING_ENABLED = booleanPreferencesKey("super_island_logging_enabled")
        private val FALLBACK_PREHEAT_ENABLED = booleanPreferencesKey("fallback_preheat_enabled")
        private val FALLBACK_PREHEAT_DELAY = longPreferencesKey("fallback_preheat_delay")
        private val ASSISTANT_BYPASS_ENABLED = booleanPreferencesKey("assistant_bypass_enabled")
        private val AUTO_UNFREEZE_ENABLED = booleanPreferencesKey("auto_unfreeze_enabled")
        private val UNFREEZE_SHOW_TOAST = booleanPreferencesKey("unfreeze_show_toast")
        private val GLOBAL_PRIVILEGE_ENABLED = booleanPreferencesKey("global_privilege_enabled")
        private val CAP_SHIZUKU_SILENT_GRANT = booleanPreferencesKey("cap_shizuku_silent_grant")
        private val CAP_A11Y_HEAL_BOOT = booleanPreferencesKey("cap_a11y_heal_boot")
        private val CAP_A11Y_HEAL_APP = booleanPreferencesKey("cap_a11y_heal_app")
        private val CAP_A11Y_PREWARM_CLIPBOARD = booleanPreferencesKey("cap_a11y_prewarm_clipboard")
        private val CAP_BATTERY_SILENT_WHITELIST = booleanPreferencesKey("cap_battery_silent_whitelist")
        private val EXTRACTION_PATTERNS = stringPreferencesKey("extraction_patterns")
        private val DYNAMIC_COLOR_ENABLED = booleanPreferencesKey("dynamic_color_enabled")
        private val BACKGROUND_BLUR_ENABLED = booleanPreferencesKey("background_blur_enabled")
        
        private val BROWSER_SELECTOR_HIDDEN = stringPreferencesKey("browser_selector_hidden")
        private val BROWSER_SELECTOR_ORDER = stringPreferencesKey("browser_selector_order")
        private val BROWSER_SELECTOR_TIMER = intPreferencesKey("browser_selector_timer")
        private val CLIPBOARD_MONITOR_ENABLED = booleanPreferencesKey("clipboard_monitor_enabled")
        private val CLIPBOARD_MONITOR_MODE = stringPreferencesKey("clipboard_monitor_mode")
        private val CLIPBOARD_MONITOR_BACKEND = stringPreferencesKey("clipboard_monitor_backend")
        private val APP_LINK_CAPTURE_MODE = intPreferencesKey("app_link_capture_mode")
        private val APP_LINK_ASK_AUTO_FINISH = booleanPreferencesKey("app_link_ask_auto_finish")
        private val APP_LINK_RULE_ONLY_INTERCEPT = booleanPreferencesKey("app_link_rule_only_intercept")
        private val APP_LINK_CAPTURE_APPS = stringPreferencesKey("app_link_capture_apps")
        private val APP_LINK_EXEMPT_DOMAINS = stringPreferencesKey("app_link_exempt_domains")
        private val CAPSULE_X = floatPreferencesKey("clipboard_capsule_x") // 旧中心X，仅迁移用
        private val CAPSULE_Y = floatPreferencesKey("clipboard_capsule_y")
        private val CAPSULE_EDGE = stringPreferencesKey("clipboard_capsule_edge")
        private val CLIPBOARD_AUTO_DISMISS_SECONDS = intPreferencesKey("clipboard_auto_dismiss_seconds")
        private val CLEAR_CLIPBOARD_AFTER_JUMP = booleanPreferencesKey("clear_clipboard_after_jump")
        private val CLIPBOARD_CHANGE_TOAST_ENABLED = booleanPreferencesKey("clipboard_change_toast_enabled")
        private val CLIPBOARD_BROADCAST_ENABLED = booleanPreferencesKey("clipboard_broadcast_enabled")
        private val FLOATING_BOTTOM_BAR_ENABLED = booleanPreferencesKey("floating_bottom_bar_enabled")
        private val MAX_MULTI_CAPSULE_COUNT = intPreferencesKey("max_multi_capsule_count")
        private val CAPSULE_DISMISS_ON_TOUCH_OUTSIDE = booleanPreferencesKey("capsule_dismiss_on_touch_outside")
        private val LAST_SYSTEM_PRIVILEGE = stringPreferencesKey("last_system_privilege")
        private val LAST_SYSTEM_WAY = stringPreferencesKey("last_system_way")
        private val PRIVILEGE_MODE = stringPreferencesKey("privilege_mode")
        private val LAST_APP_LINK_SUB_MODE = intPreferencesKey("last_app_link_sub_mode")

        private val EDGE_GESTURE_ENABLED = booleanPreferencesKey("edge_gesture_enabled")
        private val EDGE_GESTURE_WIDTH_DP = intPreferencesKey("edge_gesture_width_dp")
        private val EDGE_GESTURE_HEIGHT_DP = intPreferencesKey("edge_gesture_height_dp")
        private val EDGE_GESTURE_WIDTH_RATIO = floatPreferencesKey("edge_gesture_width_ratio")
        private val EDGE_GESTURE_HEIGHT_RATIO = floatPreferencesKey("edge_gesture_height_ratio")
        private val EDGE_GESTURE_VERTICAL_RATIO = floatPreferencesKey("edge_gesture_vertical_ratio")
        private val EDGE_GESTURE_HORIZONTAL_RATIO = floatPreferencesKey("edge_gesture_horizontal_ratio")
        private val EDGE_GESTURE_ALPHA = floatPreferencesKey("edge_gesture_alpha")
        private val EDGE_GESTURE_MIRROR = booleanPreferencesKey("edge_gesture_mirror")
        private val EDGE_GESTURE_CLICK_ACTION = stringPreferencesKey("edge_gesture_click_action")
        private val EDGE_GESTURE_SWIPE_ACTION = stringPreferencesKey("edge_gesture_swipe_action")
        private val EDGE_GESTURE_LONG_PRESS_ACTION = stringPreferencesKey("edge_gesture_long_press_action")
        private val EDGE_GESTURE_BALL_RADIUS = intPreferencesKey("edge_gesture_ball_radius")
        private val EDGE_GESTURE_BALL_STROKE = floatPreferencesKey("edge_gesture_ball_stroke")
        private val EDGE_GESTURE_BALL_ALPHA = floatPreferencesKey("edge_gesture_ball_alpha")
        private val EDGE_GESTURE_BALL_STYLE = stringPreferencesKey("edge_gesture_ball_style")
        private val EDGE_GESTURE_CONTROL_MODE = stringPreferencesKey("edge_gesture_control_mode")
        private val EDGE_GESTURE_POINTER_SENSITIVITY = floatPreferencesKey("edge_gesture_pointer_sensitivity")
        private val EDGE_GESTURE_POINTER_INITIAL_DISTANCE = intPreferencesKey("edge_gesture_pointer_initial_distance")
        private val EDGE_GESTURE_FINGER_OFFSET_Y = intPreferencesKey("edge_gesture_finger_offset_y")
        private val EDGE_GESTURE_GESTURE_HAPTIC = booleanPreferencesKey("edge_gesture_gesture_haptic")
        private val EDGE_GESTURE_COLLISION_HAPTIC = booleanPreferencesKey("edge_gesture_collision_haptic")
        private val EDGE_GESTURE_HAPTIC = booleanPreferencesKey("edge_gesture_haptic")
        private val EDGE_GESTURE_BUBBLE_SQUISH = booleanPreferencesKey("edge_gesture_bubble_squish")
        private val EDGE_GESTURE_STAY_DURATION_MS = intPreferencesKey("edge_gesture_stay_duration_ms")
        private val EDGE_GESTURE_DIRECT_SINGLE_IMAGE_RULE = booleanPreferencesKey("edge_gesture_direct_single_image_rule")
        private val EDGE_GESTURE_SCREEN_QR_ENABLED = booleanPreferencesKey("edge_gesture_screen_qr_enabled")
        private val EDGE_GESTURE_ORIENTATION_SCOPE = stringPreferencesKey("edge_gesture_orientation_scope")
        private val EDGE_GESTURE_APP_SCOPE_MODE = stringPreferencesKey("edge_gesture_app_scope_mode")
        private val EDGE_GESTURE_APP_SCOPE_PACKAGES = stringSetPreferencesKey("edge_gesture_app_scope_packages")

        fun parseExtractionPatterns(json: String): List<com.moting.linkgo.model.ExtractPattern> {
            return try {
                val arr = org.json.JSONArray(json)
                val list = mutableListOf<com.moting.linkgo.model.ExtractPattern>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(
                        com.moting.linkgo.model.ExtractPattern(
                            id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                            name = obj.optString("name", "未命名"),
                            pattern = obj.optString("pattern", ""),
                            isEnabled = obj.optBoolean("isEnabled", true),
                            isBuiltin = obj.optBoolean("isBuiltin", false)
                        )
                    )
                }
                list
            } catch (e: Exception) {
                com.moting.linkgo.model.ExtractPattern.builtinDefaults()
            }
        }

        fun serializeExtractionPatterns(patterns: List<com.moting.linkgo.model.ExtractPattern>): String {
            val arr = org.json.JSONArray()
            patterns.forEach { p ->
                val obj = org.json.JSONObject()
                obj.put("id", p.id)
                obj.put("name", p.name)
                obj.put("pattern", p.pattern)
                obj.put("isEnabled", p.isEnabled)
                obj.put("isBuiltin", p.isBuiltin)
                arr.put(obj)
            }
            return arr.toString()
        }

        fun parseExemptDomains(json: String): List<com.moting.linkgo.model.ExemptDomain> {
            return try {
                val arr = org.json.JSONArray(json)
                val list = mutableListOf<com.moting.linkgo.model.ExemptDomain>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    list.add(
                        com.moting.linkgo.model.ExemptDomain(
                            id = obj.optString("id"),
                            sourcePkg = obj.optString("sourcePkg"),
                            // 兼容旧数据：早期字段为 domain
                            pattern = obj.optString("pattern").ifBlank { obj.optString("domain") },
                            matchType = runCatching {
                                com.moting.linkgo.model.MatchType.valueOf(obj.optString("matchType", "CONTAINS"))
                            }.getOrDefault(com.moting.linkgo.model.MatchType.CONTAINS),
                            note = obj.optString("note"),
                            isEnabled = obj.optBoolean("isEnabled", true)
                        )
                    )
                }
                list
            } catch (e: Exception) {
                com.moting.linkgo.model.ExemptDomain.defaults()
            }
        }

        fun parseImageRules(json: String): List<com.moting.linkgo.model.ImageRule> {
            return try {
                val root = org.json.JSONTokener(json).nextValue()
                val arr = if (root is org.json.JSONObject) {
                    if (root.has("linkgo_image_rules")) root.getJSONArray("linkgo_image_rules") else org.json.JSONArray()
                } else if (root is org.json.JSONArray) {
                    root
                } else {
                    org.json.JSONArray()
                }

                val list = mutableListOf<com.moting.linkgo.model.ImageRule>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(
                        com.moting.linkgo.model.ImageRule(
                            id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                            name = obj.optString("name", "未命名图片规则"),
                            targetPackage = obj.optString("targetPackage", ""),
                            targetClass = obj.optString("targetClass", ""),
                            ruleLaunchMode = obj.optInt("ruleLaunchMode", -1),
                            excludeFromRecents = obj.optBoolean("excludeFromRecents", false),
                            isEnabled = obj.optBoolean("isEnabled", true),
                            iconPath = if (obj.has("iconPath") && !obj.isNull("iconPath")) obj.getString("iconPath") else null
                        )
                    )
                }
                list
            } catch (e: Exception) {
                android.util.Log.w("SettingsRepo", "parseImageRules 失败: ${e.message}")
                emptyList()
            }
        }

        fun serializeImageRules(rules: List<com.moting.linkgo.model.ImageRule>): String {
            val arr = org.json.JSONArray()
            rules.forEach { rule ->
                val obj = org.json.JSONObject()
                obj.put("id", rule.id)
                obj.put("name", rule.name)
                obj.put("targetPackage", rule.targetPackage)
                obj.put("targetClass", rule.targetClass)
                obj.put("ruleLaunchMode", rule.ruleLaunchMode)
                obj.put("excludeFromRecents", rule.excludeFromRecents)
                obj.put("isEnabled", rule.isEnabled)
                obj.put("iconPath", rule.iconPath)
                arr.put(obj)
            }
            return arr.toString()
        }

        fun parseCaptureApps(json: String): List<com.moting.linkgo.model.CaptureApp> {
            return try {
                val arr = org.json.JSONArray(json)
                val list = mutableListOf<com.moting.linkgo.model.CaptureApp>()
                val seenPkgs = mutableSetOf<String>()
                val baseTime = 1700000000000L
                for (i in 0 until arr.length()) {
                    val item = arr.opt(i)
                    if (item is org.json.JSONObject) {
                        val pkg = item.optString("packageName", "").trim()
                        if (pkg.isNotEmpty() && seenPkgs.add(pkg)) {
                            list.add(
                                com.moting.linkgo.model.CaptureApp(
                                    packageName = pkg,
                                    isEnabled = item.optBoolean("isEnabled", true),
                                    addedAt = item.optLong("addedAt", baseTime + i * 1000L)
                                )
                            )
                        }
                    } else if (item is String && item.isNotBlank()) {
                        val pkg = item.trim()
                        if (seenPkgs.add(pkg)) {
                            list.add(
                                com.moting.linkgo.model.CaptureApp(
                                    packageName = pkg,
                                    isEnabled = true,
                                    addedAt = baseTime + i * 1000L
                                )
                            )
                        }
                    }
                }
                if (list.isNotEmpty()) list else com.moting.linkgo.model.CaptureApp.defaults()
            } catch (e: Exception) {
                com.moting.linkgo.model.CaptureApp.defaults()
            }
        }

        fun serializeCaptureApps(list: List<com.moting.linkgo.model.CaptureApp>): String {
            val arr = org.json.JSONArray()
            list.forEach { app ->
                val obj = org.json.JSONObject()
                obj.put("packageName", app.packageName)
                obj.put("isEnabled", app.isEnabled)
                obj.put("addedAt", app.addedAt)
                arr.put(obj)
            }
            return arr.toString()
        }

        /** 序列化仅已启用的包名列表（供 HookEntry 同步） */
        fun serializeEnabledCapturePackageNames(list: List<com.moting.linkgo.model.CaptureApp>): String {
            val arr = org.json.JSONArray()
            list.filter { it.isEnabled }.forEach { arr.put(it.packageName) }
            return arr.toString()
        }

        fun serializeExemptDomains(list: List<com.moting.linkgo.model.ExemptDomain>): String {
            val arr = org.json.JSONArray()
            list.forEach { e ->
                val obj = org.json.JSONObject()
                obj.put("id", e.id)
                obj.put("sourcePkg", e.sourcePkg ?: "")
                obj.put("pattern", e.pattern)
                obj.put("matchType", e.matchType.name)
                obj.put("note", e.note)
                obj.put("isEnabled", e.isEnabled)
                arr.put(obj)
            }
            return arr.toString()
        }
    }

    /**
     * 轻量读取「剪贴板后台监听总开关 + 后端」当前值。
     * 供前台服务被系统重建（START_STICKY 的 intent 为 null）时还原真实配置，
     * 避免回落到默认后端导致监听方式错配；不触碰其余缓存，也不持久化。
     */
    suspend fun currentClipboardMonitorConfig(): Pair<Boolean, String> {
        if (SettingsCache.isLoaded) {
            return SettingsCache.clipboardMonitorEnabled to SettingsCache.clipboardMonitorBackend
        }
        return try {
            val prefs = context.dataStore.data.first()
            val enabled = prefs[CLIPBOARD_MONITOR_ENABLED] ?: false
            val backend = prefs[CLIPBOARD_MONITOR_BACKEND]
                ?: com.moting.linkgo.clipboard.ClipboardBackend.migrateFromLegacyMode(prefs[CLIPBOARD_MONITOR_MODE])
            enabled to backend
        } catch (e: Exception) {
            android.util.Log.w("SettingsRepo", "currentClipboardMonitorConfig error: ${e.message}")
            false to com.moting.linkgo.clipboard.ClipboardBackend.DEFAULT
        }
    }

    suspend fun preloadAll() {
        try {
            val prefs = context.dataStore.data.first()
            SettingsCache.floatingBottomBarEnabled = prefs[FLOATING_BOTTOM_BAR_ENABLED] ?: true
            SettingsCache.clipboardMonitorEnabled = prefs[CLIPBOARD_MONITOR_ENABLED] ?: false
            val backend = prefs[CLIPBOARD_MONITOR_BACKEND]
            SettingsCache.clipboardMonitorBackend = backend ?: com.moting.linkgo.clipboard.ClipboardBackend.migrateFromLegacyMode(prefs[CLIPBOARD_MONITOR_MODE])
            SettingsCache.browserSelectorTimer = prefs[BROWSER_SELECTOR_TIMER] ?: 5
            SettingsCache.showTriggerToast = prefs[SHOW_TRIGGER_TOAST] ?: false
            SettingsCache.dynamicColorEnabled = prefs[DYNAMIC_COLOR_ENABLED] ?: true
            SettingsCache.backgroundBlurEnabled = prefs[BACKGROUND_BLUR_ENABLED] ?: true
            SettingsCache.fallbackBrowser = prefs[FALLBACK_BROWSER]
            SettingsCache.fallbackWindowMode = prefs[FALLBACK_WINDOW_MODE] ?: -1
            SettingsCache.fallbackPreheatEnabled = prefs[FALLBACK_PREHEAT_ENABLED] ?: false
            SettingsCache.fallbackPreheatDelay = prefs[FALLBACK_PREHEAT_DELAY] ?: 500L
            SettingsCache.excludeFromRecents = prefs[EXCLUDE_FROM_RECENTS] ?: false
            // 一次性迁移：旧版单一开关同时管「LinkGo 自身」与「备选浏览器」，拆分后首次读取时让
            // 备选开关继承旧值以免老用户行为回归；写入新键后两者即完全独立
            val storedFallbackExclude = prefs[FALLBACK_EXCLUDE_FROM_RECENTS]
            SettingsCache.fallbackExcludeFromRecents = storedFallbackExclude ?: (prefs[EXCLUDE_FROM_RECENTS] ?: false)
            if (storedFallbackExclude == null) {
                context.dataStore.edit { it[FALLBACK_EXCLUDE_FROM_RECENTS] = SettingsCache.fallbackExcludeFromRecents }
            }
            SettingsCache.normalizationEnabled = prefs[NORMALIZATION_ENABLED] ?: true
            SettingsCache.showRichNotification = prefs[SHOW_RICH_NOTIFICATION] ?: false
            SettingsCache.notificationStyle = NotificationStyle.fromValue(prefs[NOTIFICATION_STYLE])
            SettingsCache.notificationAutoDismissSeconds = (prefs[NOTIFICATION_AUTO_DISMISS_SECONDS] ?: 5).coerceIn(2, 30)
            SettingsCache.superIslandBypassEnabled = prefs[SUPER_ISLAND_BYPASS_ENABLED] ?: true
            SettingsCache.superIslandBypassDurationMs = (prefs[SUPER_ISLAND_BYPASS_DURATION_MS] ?: 100).coerceIn(50, 500)
            SettingsCache.superIslandOuterGlow = prefs[SUPER_ISLAND_OUTER_GLOW] ?: false
            SettingsCache.superIslandDragShareEnabled = prefs[SUPER_ISLAND_DRAG_SHARE_ENABLED] ?: true
            SettingsCache.assistantBypassEnabled = prefs[ASSISTANT_BYPASS_ENABLED] ?: false
            SettingsCache.autoUnfreezeEnabled = prefs[AUTO_UNFREEZE_ENABLED] ?: true
            SettingsCache.unfreezeShowToast = prefs[UNFREEZE_SHOW_TOAST] ?: true
            SettingsCache.globalPrivilegeEnabled = prefs[GLOBAL_PRIVILEGE_ENABLED] ?: true
            SettingsCache.capShizukuSilentGrant = prefs[CAP_SHIZUKU_SILENT_GRANT] ?: true
            SettingsCache.capA11yHealBoot = prefs[CAP_A11Y_HEAL_BOOT] ?: true
            SettingsCache.capA11yHealApp = prefs[CAP_A11Y_HEAL_APP] ?: true
            SettingsCache.capA11yPrewarmClipboard = prefs[CAP_A11Y_PREWARM_CLIPBOARD] ?: true
            SettingsCache.capBatterySilentWhitelist = prefs[CAP_BATTERY_SILENT_WHITELIST] ?: true
            SettingsCache.clearClipboardAfterJump = prefs[CLEAR_CLIPBOARD_AFTER_JUMP] ?: false
            SettingsCache.clipboardChangeToastEnabled = prefs[CLIPBOARD_CHANGE_TOAST_ENABLED] ?: false
            SettingsCache.clipboardBroadcastEnabled = prefs[CLIPBOARD_BROADCAST_ENABLED] ?: false
            SettingsCache.clipboardAutoDismissSeconds = (prefs[CLIPBOARD_AUTO_DISMISS_SECONDS] ?: 5).coerceIn(2, 30)
            SettingsCache.maxMultiCapsuleCount = (prefs[MAX_MULTI_CAPSULE_COUNT] ?: 3).coerceIn(1, 10)
            SettingsCache.capsuleDismissOnTouchOutside = prefs[CAPSULE_DISMISS_ON_TOUCH_OUTSIDE] ?: true
            SettingsCache.lastSystemPrivilege = prefs[LAST_SYSTEM_PRIVILEGE] ?: "shizuku"
            SettingsCache.lastSystemWay = prefs[LAST_SYSTEM_WAY] ?: "hidden_api"
            SettingsCache.privilegeMode = prefs[PRIVILEGE_MODE] ?: "auto"
            SettingsCache.lastAppLinkSubMode = prefs[LAST_APP_LINK_SUB_MODE] ?: 1
            
            val edge = prefs[CAPSULE_EDGE] ?: prefs[CAPSULE_X]?.let { if (it < 0.5f) "left" else "right" }
            if (edge != null) SettingsCache.capsuleEdge = edge
            val yRatio = prefs[CAPSULE_Y]
            if (yRatio != null) SettingsCache.capsuleYRatio = yRatio

            val patternsJson = prefs[EXTRACTION_PATTERNS]
            if (!patternsJson.isNullOrBlank()) {
                SettingsCache.extractionPatterns = parseExtractionPatterns(patternsJson)
            } else {
                SettingsCache.extractionPatterns = com.moting.linkgo.model.ExtractPattern.builtinDefaults()
            }

            val imageRulesJson = prefs[IMAGE_RULES]
            SettingsCache.imageRules = if (imageRulesJson.isNullOrBlank()) {
                emptyList()
            } else {
                parseImageRules(imageRulesJson)
            }

            SettingsCache.edgeGestureConfig = com.moting.linkgo.model.EdgeGestureConfig(
                enabled = prefs[EDGE_GESTURE_ENABLED] ?: false,
                widthDp = prefs[EDGE_GESTURE_WIDTH_DP]
                    ?: prefs[EDGE_GESTURE_WIDTH_RATIO]?.let { (it * 392f).roundToInt().coerceIn(6, 60) }
                    ?: 18,
                heightDp = prefs[EDGE_GESTURE_HEIGHT_DP]
                    ?: prefs[EDGE_GESTURE_HEIGHT_RATIO]?.let { (it * 850f).roundToInt().coerceIn(40, 400) }
                    ?: 140,
                verticalRatio = prefs[EDGE_GESTURE_VERTICAL_RATIO] ?: 0.350f,
                horizontalRatio = prefs[EDGE_GESTURE_HORIZONTAL_RATIO] ?: 0.000f,
                edgeAlpha = prefs[EDGE_GESTURE_ALPHA] ?: 0.60f,
                mirrorEnabled = prefs[EDGE_GESTURE_MIRROR] ?: true,
                clickAction = com.moting.linkgo.model.GestureAction.fromValue(prefs[EDGE_GESTURE_CLICK_ACTION] ?: "screen_recognition"),
                swipeAction = com.moting.linkgo.model.GestureAction.fromValue(prefs[EDGE_GESTURE_SWIPE_ACTION] ?: "radar_direct"),
                longPressAction = com.moting.linkgo.model.GestureAction.fromValue(prefs[EDGE_GESTURE_LONG_PRESS_ACTION] ?: "none"),
                ballRadiusDp = prefs[EDGE_GESTURE_BALL_RADIUS] ?: 28,
                ballStrokeWidthDp = prefs[EDGE_GESTURE_BALL_STROKE] ?: 2.5f,
                ballAlpha = prefs[EDGE_GESTURE_BALL_ALPHA] ?: 0.65f,
                ballStyle = com.moting.linkgo.model.BallStyle.fromValue(prefs[EDGE_GESTURE_BALL_STYLE] ?: "stroke_glow"),
                controlMode = com.moting.linkgo.model.PointerControlMode.fromValue(prefs[EDGE_GESTURE_CONTROL_MODE] ?: "remote_pointer"),
                pointerSensitivity = prefs[EDGE_GESTURE_POINTER_SENSITIVITY] ?: 2.2f,
                pointerInitialDistanceDp = prefs[EDGE_GESTURE_POINTER_INITIAL_DISTANCE] ?: 96,
                fingerOffsetYDp = prefs[EDGE_GESTURE_FINGER_OFFSET_Y] ?: 36,
                gestureHapticEnabled = prefs[EDGE_GESTURE_GESTURE_HAPTIC] ?: prefs[EDGE_GESTURE_HAPTIC] ?: true,
                collisionHapticEnabled = prefs[EDGE_GESTURE_COLLISION_HAPTIC] ?: prefs[EDGE_GESTURE_HAPTIC] ?: true,
                bubbleSquishEnabled = prefs[EDGE_GESTURE_BUBBLE_SQUISH] ?: true,
                stayDurationMs = prefs[EDGE_GESTURE_STAY_DURATION_MS] ?: 800,
                directSingleImageRule = prefs[EDGE_GESTURE_DIRECT_SINGLE_IMAGE_RULE] ?: true,
                screenQrEnabled = prefs[EDGE_GESTURE_SCREEN_QR_ENABLED] ?: false,
                orientationScope = com.moting.linkgo.model.OrientationScope.fromValue(prefs[EDGE_GESTURE_ORIENTATION_SCOPE] ?: "all"),
                appScopeMode = com.moting.linkgo.model.AppScopeMode.fromValue(prefs[EDGE_GESTURE_APP_SCOPE_MODE] ?: "blacklist"),
                appScopePackages = prefs[EDGE_GESTURE_APP_SCOPE_PACKAGES] ?: emptySet()
            )

            SettingsCache.dispatchRules = prefs[DISPATCH_RULES] ?: "[]"
            SettingsCache.isLoaded = true
            persistFastCache(context)
            android.util.Log.i("SettingsRepo", "preloadAll: 完整配置预加载就绪 (monitorEnabled=${SettingsCache.clipboardMonitorEnabled}, backend=${SettingsCache.clipboardMonitorBackend})")
        } catch (e: Exception) {
            android.util.Log.w("SettingsRepo", "preloadAll error: ${e.message}")
        }
    }

    /**
     * 边缘手势与雷达探照配置流
     */
    val edgeGestureConfigFlow: Flow<com.moting.linkgo.model.EdgeGestureConfig> = context.dataStore.data.map { prefs ->
        val config = com.moting.linkgo.model.EdgeGestureConfig(
            enabled = prefs[EDGE_GESTURE_ENABLED] ?: false,
            widthDp = prefs[EDGE_GESTURE_WIDTH_DP]
                ?: prefs[EDGE_GESTURE_WIDTH_RATIO]?.let { (it * 392f).roundToInt().coerceIn(6, 60) }
                ?: 18,
            heightDp = prefs[EDGE_GESTURE_HEIGHT_DP]
                ?: prefs[EDGE_GESTURE_HEIGHT_RATIO]?.let { (it * 850f).roundToInt().coerceIn(40, 400) }
                ?: 140,
            verticalRatio = prefs[EDGE_GESTURE_VERTICAL_RATIO] ?: 0.350f,
            horizontalRatio = prefs[EDGE_GESTURE_HORIZONTAL_RATIO] ?: 0.000f,
            edgeAlpha = prefs[EDGE_GESTURE_ALPHA] ?: 0.60f,
            mirrorEnabled = prefs[EDGE_GESTURE_MIRROR] ?: true,
            clickAction = com.moting.linkgo.model.GestureAction.fromValue(prefs[EDGE_GESTURE_CLICK_ACTION] ?: "screen_recognition"),
            swipeAction = com.moting.linkgo.model.GestureAction.fromValue(prefs[EDGE_GESTURE_SWIPE_ACTION] ?: "radar_direct"),
            longPressAction = com.moting.linkgo.model.GestureAction.fromValue(prefs[EDGE_GESTURE_LONG_PRESS_ACTION] ?: "none"),
            ballRadiusDp = prefs[EDGE_GESTURE_BALL_RADIUS] ?: 28,
            ballStrokeWidthDp = prefs[EDGE_GESTURE_BALL_STROKE] ?: 2.5f,
            ballAlpha = prefs[EDGE_GESTURE_BALL_ALPHA] ?: 0.65f,
            ballStyle = com.moting.linkgo.model.BallStyle.fromValue(prefs[EDGE_GESTURE_BALL_STYLE] ?: "stroke_glow"),
            controlMode = com.moting.linkgo.model.PointerControlMode.fromValue(prefs[EDGE_GESTURE_CONTROL_MODE] ?: "remote_pointer"),
            pointerSensitivity = prefs[EDGE_GESTURE_POINTER_SENSITIVITY] ?: 2.2f,
            pointerInitialDistanceDp = prefs[EDGE_GESTURE_POINTER_INITIAL_DISTANCE] ?: 96,
            fingerOffsetYDp = prefs[EDGE_GESTURE_FINGER_OFFSET_Y] ?: 36,
            gestureHapticEnabled = prefs[EDGE_GESTURE_GESTURE_HAPTIC] ?: prefs[EDGE_GESTURE_HAPTIC] ?: true,
            collisionHapticEnabled = prefs[EDGE_GESTURE_COLLISION_HAPTIC] ?: prefs[EDGE_GESTURE_HAPTIC] ?: true,
            bubbleSquishEnabled = prefs[EDGE_GESTURE_BUBBLE_SQUISH] ?: true,
            stayDurationMs = prefs[EDGE_GESTURE_STAY_DURATION_MS] ?: 800,
            directSingleImageRule = prefs[EDGE_GESTURE_DIRECT_SINGLE_IMAGE_RULE] ?: true,
            screenQrEnabled = prefs[EDGE_GESTURE_SCREEN_QR_ENABLED] ?: false,
            orientationScope = com.moting.linkgo.model.OrientationScope.fromValue(prefs[EDGE_GESTURE_ORIENTATION_SCOPE] ?: "all"),
            appScopeMode = com.moting.linkgo.model.AppScopeMode.fromValue(prefs[EDGE_GESTURE_APP_SCOPE_MODE] ?: "blacklist"),
            appScopePackages = prefs[EDGE_GESTURE_APP_SCOPE_PACKAGES] ?: emptySet()
        )
        SettingsCache.edgeGestureConfig = config
        config
    }

    /**
     * 更新边缘手势与雷达探照配置
     */
    suspend fun updateEdgeGestureConfig(config: com.moting.linkgo.model.EdgeGestureConfig) {
        SettingsCache.edgeGestureConfig = config
        persistFastCache(context)
        context.dataStore.edit { prefs ->
            prefs[EDGE_GESTURE_ENABLED] = config.enabled
            prefs[EDGE_GESTURE_WIDTH_DP] = config.widthDp
            prefs[EDGE_GESTURE_HEIGHT_DP] = config.heightDp
            prefs[EDGE_GESTURE_WIDTH_RATIO] = config.widthDp / 392f
            prefs[EDGE_GESTURE_HEIGHT_RATIO] = config.heightDp / 850f
            prefs[EDGE_GESTURE_VERTICAL_RATIO] = config.verticalRatio
            prefs[EDGE_GESTURE_HORIZONTAL_RATIO] = config.horizontalRatio
            prefs[EDGE_GESTURE_ALPHA] = config.edgeAlpha
            prefs[EDGE_GESTURE_MIRROR] = config.mirrorEnabled
            prefs[EDGE_GESTURE_CLICK_ACTION] = config.clickAction.value
            prefs[EDGE_GESTURE_SWIPE_ACTION] = config.swipeAction.value
            prefs[EDGE_GESTURE_LONG_PRESS_ACTION] = config.longPressAction.value
            prefs[EDGE_GESTURE_BALL_RADIUS] = config.ballRadiusDp
            prefs[EDGE_GESTURE_BALL_STROKE] = config.ballStrokeWidthDp
            prefs[EDGE_GESTURE_BALL_ALPHA] = config.ballAlpha
            prefs[EDGE_GESTURE_BALL_STYLE] = config.ballStyle.value
            prefs[EDGE_GESTURE_CONTROL_MODE] = config.controlMode.value
            prefs[EDGE_GESTURE_POINTER_SENSITIVITY] = config.pointerSensitivity
            prefs[EDGE_GESTURE_POINTER_INITIAL_DISTANCE] = config.pointerInitialDistanceDp
            prefs[EDGE_GESTURE_FINGER_OFFSET_Y] = config.fingerOffsetYDp
            prefs[EDGE_GESTURE_GESTURE_HAPTIC] = config.gestureHapticEnabled
            prefs[EDGE_GESTURE_COLLISION_HAPTIC] = config.collisionHapticEnabled
            prefs[EDGE_GESTURE_HAPTIC] = config.collisionHapticEnabled
            prefs[EDGE_GESTURE_BUBBLE_SQUISH] = config.bubbleSquishEnabled
            prefs[EDGE_GESTURE_STAY_DURATION_MS] = config.stayDurationMs
            prefs[EDGE_GESTURE_DIRECT_SINGLE_IMAGE_RULE] = config.directSingleImageRule
            prefs[EDGE_GESTURE_SCREEN_QR_ENABLED] = config.screenQrEnabled
            prefs[EDGE_GESTURE_ORIENTATION_SCOPE] = config.orientationScope.value
            prefs[EDGE_GESTURE_APP_SCOPE_MODE] = config.appScopeMode.value
            prefs[EDGE_GESTURE_APP_SCOPE_PACKAGES] = config.appScopePackages
        }
    }

    /**
     * 全部偏好变更流（供「变更后自动备份」监听）。
     */
    val dataChanges: Flow<Unit> = context.dataStore.data.map { Unit }

    /**
     * 悬浮底栏开关流 (默认开启)
     */
    val floatingBottomBarEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        val v = prefs[FLOATING_BOTTOM_BAR_ENABLED] ?: true
        SettingsCache.floatingBottomBarEnabled = v
        v
    }

    /**
     * 更新悬浮底栏开关
     */
    suspend fun updateFloatingBottomBarEnabled(enabled: Boolean) {
        SettingsCache.floatingBottomBarEnabled = enabled
        context.dataStore.edit { prefs ->
            prefs[FLOATING_BOTTOM_BAR_ENABLED] = enabled
        }
        persistFastCache(context)
    }

    /**
     * 获取多链接直达胶囊最大展示数量流 (1~10，默认 3)
     */
    val maxMultiCapsuleCount: Flow<Int> = context.dataStore.data.map { prefs ->
        val v = prefs[MAX_MULTI_CAPSULE_COUNT] ?: 3
        SettingsCache.maxMultiCapsuleCount = v
        v
    }

    /**
     * 更新多链接直达胶囊最大展示数量
     */
    suspend fun updateMaxMultiCapsuleCount(count: Int) {
        val safe = count.coerceIn(1, 10)
        SettingsCache.maxMultiCapsuleCount = safe
        context.dataStore.edit { prefs ->
            prefs[MAX_MULTI_CAPSULE_COUNT] = safe
        }
        persistFastCache(context)
    }

    /**
     * 获取点击外部区域自动隐藏胶囊的开关流 (默认开启)
     */
    val capsuleDismissOnTouchOutside: Flow<Boolean> = context.dataStore.data.map { prefs ->
        val v = prefs[CAPSULE_DISMISS_ON_TOUCH_OUTSIDE] ?: true
        SettingsCache.capsuleDismissOnTouchOutside = v
        v
    }

    /**
     * 更新点击外部区域自动隐藏胶囊的开关
     */
    suspend fun updateCapsuleDismissOnTouchOutside(enabled: Boolean) {
        SettingsCache.capsuleDismissOnTouchOutside = enabled
        context.dataStore.edit { prefs ->
            prefs[CAPSULE_DISMISS_ON_TOUCH_OUTSIDE] = enabled
        }
        persistFastCache(context)
    }

    /**
     * 获取系统监听矩阵维度
     */
    val lastSystemPrivilege: Flow<String> = context.dataStore.data.map { prefs ->
        val v = prefs[LAST_SYSTEM_PRIVILEGE] ?: "shizuku"
        SettingsCache.lastSystemPrivilege = v
        v
    }

    suspend fun updateLastSystemPrivilege(privilege: String) {
        SettingsCache.lastSystemPrivilege = privilege
        context.dataStore.edit { prefs ->
            prefs[LAST_SYSTEM_PRIVILEGE] = privilege
        }
        persistFastCache(context)
    }

    val lastSystemWay: Flow<String> = context.dataStore.data.map { prefs ->
        val v = prefs[LAST_SYSTEM_WAY] ?: "hidden_api"
        SettingsCache.lastSystemWay = v
        v
    }

    suspend fun updateLastSystemWay(way: String) {
        SettingsCache.lastSystemWay = way
        context.dataStore.edit { prefs ->
            prefs[LAST_SYSTEM_WAY] = way
        }
        persistFastCache(context)
    }

    /**
     * 获取当前的小窗配置流
     */
    val windowConfig: Flow<WindowConfig> = context.dataStore.data.map { prefs ->
        val cfg = WindowConfig(
            portWidthRatio = prefs[PORT_WIDTH] ?: 0.8f,
            portHeightRatio = prefs[PORT_HEIGHT] ?: 0.55f,
            portXRatio = prefs[PORT_X] ?: 0.1f,
            portYRatio = prefs[PORT_Y] ?: 0.225f,
            
            landWidthRatio = prefs[LAND_WIDTH] ?: 0.30f,
            landHeightRatio = prefs[LAND_HEIGHT] ?: 0.85f,
            landXRatio = prefs[LAND_X] ?: 0.05f,
            landYRatio = prefs[LAND_Y] ?: 0.075f,
            
            windowingMode = prefs[WINDOW_MODE] ?: 5,
            isEnabled = prefs[SMALL_WINDOW_ENABLED] ?: true
        )
        SettingsCache.windowConfig = cfg
        cfg
    }

    /**
     * 更新小窗配置
     */
    suspend fun updateConfig(config: WindowConfig) {
        SettingsCache.windowConfig = config
        context.dataStore.edit { prefs ->
            prefs[PORT_WIDTH] = config.portWidthRatio
            prefs[PORT_HEIGHT] = config.portHeightRatio
            prefs[PORT_X] = config.portXRatio
            prefs[PORT_Y] = config.portYRatio

            prefs[LAND_WIDTH] = config.landWidthRatio
            prefs[LAND_HEIGHT] = config.landHeightRatio
            prefs[LAND_X] = config.landXRatio
            prefs[LAND_Y] = config.landYRatio

            prefs[WINDOW_MODE] = config.windowingMode
            prefs[SMALL_WINDOW_ENABLED] = config.isEnabled
        }
        persistFastCache(context)
    }

    /**
     * 获取规则列表
     */
    val dispatchRules: Flow<List<com.moting.linkgo.model.DispatchRule>> = context.dataStore.data.map { prefs ->
        val jsonStr = prefs[DISPATCH_RULES] ?: "[]"
        parseRules(jsonStr)
    }

    /**
     * 更新规则列表
     */
    suspend fun updateRules(rules: List<com.moting.linkgo.model.DispatchRule>) {
        val json = serializeRules(rules)
        context.dataStore.edit { prefs ->
            prefs[DISPATCH_RULES] = json
        }
        // 规则变更后立即刷新缓存并下发 HookEntry：
        // 应用内链接捕获依赖「规则目标应用 == 发起应用 → 放行」断环，规则表不同步会退化成无限循环
        SettingsCache.dispatchRules = json
        persistFastCache(context)
        runCatching {
            com.moting.linkgo.applink.LinkIntentReceiver.syncConfigToHook(
                context,
                SettingsCache.appLinkCaptureMode
            )
        }
    }

    /**
     * 获取图片规则列表（顺序即优先级，见 [com.moting.linkgo.model.ImageRule] 的说明）
     */
    val imageRules: Flow<List<com.moting.linkgo.model.ImageRule>> = context.dataStore.data.map { prefs ->
        val jsonStr = prefs[IMAGE_RULES]
        if (jsonStr.isNullOrBlank()) emptyList() else parseImageRules(jsonStr)
    }

    /**
     * 更新图片规则列表（同步刷新 SettingsCache，使剪贴板监听等运行时立即生效）
     */
    suspend fun updateImageRules(rules: List<com.moting.linkgo.model.ImageRule>) {
        context.dataStore.edit { prefs ->
            prefs[IMAGE_RULES] = serializeImageRules(rules)
        }
        SettingsCache.imageRules = rules
    }

    /**
     * 获取默认备选浏览器包名
     */
    val fallbackBrowser: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[FALLBACK_BROWSER]
    }

    /**
     * 更新默认备选浏览器包名
     */
    suspend fun updateFallbackBrowser(packageName: String?) {
        context.dataStore.edit { prefs ->
            if (packageName == null) {
                prefs.remove(FALLBACK_BROWSER)
            } else {
                prefs[FALLBACK_BROWSER] = packageName
            }
        }
    }

    /**
     * 获取 LinkGo 自身是否从最近任务隐藏
     */
    val excludeFromRecents: Flow<Boolean> = context.dataStore.data.map { prefs ->
        val v = prefs[EXCLUDE_FROM_RECENTS] ?: false
        SettingsCache.excludeFromRecents = v
        v
    }

    /**
     * 更新 LinkGo 自身是否从最近任务隐藏
     */
    suspend fun updateExcludeFromRecents(exclude: Boolean) {
        SettingsCache.excludeFromRecents = exclude
        context.dataStore.edit { prefs ->
            prefs[EXCLUDE_FROM_RECENTS] = exclude
        }
    }

    /**
     * 获取备选浏览器跳转时是否隐藏目标应用的最近任务卡片
     */
    val fallbackExcludeFromRecents: Flow<Boolean> = context.dataStore.data.map { prefs ->
        val v = prefs[FALLBACK_EXCLUDE_FROM_RECENTS] ?: false
        SettingsCache.fallbackExcludeFromRecents = v
        v
    }

    /**
     * 更新备选浏览器跳转时是否隐藏目标应用的最近任务卡片
     */
    suspend fun updateFallbackExcludeFromRecents(exclude: Boolean) {
        SettingsCache.fallbackExcludeFromRecents = exclude
        context.dataStore.edit { prefs ->
            prefs[FALLBACK_EXCLUDE_FROM_RECENTS] = exclude
        }
    }

    /**
     * 获取是否显示基于安卓 16 的实时跳转通知 (Rich Ongoing Notification)
     */
    val showRichNotification: Flow<Boolean> = context.dataStore.data.map { prefs ->
        val v = prefs[SHOW_RICH_NOTIFICATION] ?: false // 默认为关闭，开启时申请
        SettingsCache.showRichNotification = v
        v
    }

    /**
     * 更新是否显示实时跳转通知
     */
    suspend fun updateShowRichNotification(show: Boolean) {
        SettingsCache.showRichNotification = show
        context.dataStore.edit { prefs ->
            prefs[SHOW_RICH_NOTIFICATION] = show
        }
        persistFastCache(context)
    }

    /**
     * 获取通知样式 (标准通知 / 实时活动 / 小米超级岛)
     */
    val notificationStyle: Flow<NotificationStyle> = context.dataStore.data.map { prefs ->
        val v = NotificationStyle.fromValue(prefs[NOTIFICATION_STYLE])
        SettingsCache.notificationStyle = v
        v
    }

    /**
     * 更新通知样式
     */
    suspend fun updateNotificationStyle(style: NotificationStyle) {
        SettingsCache.notificationStyle = style
        context.dataStore.edit { prefs ->
            prefs[NOTIFICATION_STYLE] = style.value
        }
        persistFastCache(context)
    }

    /**
     * 获取自动清除通知时长 (秒)
     */
    val notificationAutoDismissSeconds: Flow<Int> = context.dataStore.data.map { prefs ->
        val v = (prefs[NOTIFICATION_AUTO_DISMISS_SECONDS] ?: 5).coerceIn(2, 30)
        SettingsCache.notificationAutoDismissSeconds = v
        v
    }

    suspend fun updateNotificationAutoDismissSeconds(seconds: Int) {
        val clamped = seconds.coerceIn(2, 30)
        SettingsCache.notificationAutoDismissSeconds = clamped
        context.dataStore.edit { prefs ->
            prefs[NOTIFICATION_AUTO_DISMISS_SECONDS] = clamped
        }
        persistFastCache(context)
    }

    /**
     * 获取是否开启绕过超级岛限制 (仅 Shizuku)
     */
    val superIslandBypassEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        val v = prefs[SUPER_ISLAND_BYPASS_ENABLED] ?: true
        SettingsCache.superIslandBypassEnabled = v
        v
    }

    suspend fun updateSuperIslandBypassEnabled(enabled: Boolean) {
        SettingsCache.superIslandBypassEnabled = enabled
        context.dataStore.edit { prefs ->
            prefs[SUPER_ISLAND_BYPASS_ENABLED] = enabled
        }
        persistFastCache(context)
    }

    /**
     * 获取 XMSF 断网盲窗时长 (100~3000ms)
     */
    val superIslandBypassDurationMs: Flow<Int> = context.dataStore.data.map { prefs ->
        val v = (prefs[SUPER_ISLAND_BYPASS_DURATION_MS] ?: 100).coerceIn(50, 500)
        SettingsCache.superIslandBypassDurationMs = v
        v
    }

    suspend fun updateSuperIslandBypassDurationMs(duration: Int) {
        val clamped = duration.coerceIn(50, 500)
        SettingsCache.superIslandBypassDurationMs = clamped
        context.dataStore.edit { prefs ->
            prefs[SUPER_ISLAND_BYPASS_DURATION_MS] = clamped
        }
        persistFastCache(context)
    }

    /**
     * 获取外发光效果是否开启
     */
    val superIslandOuterGlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        val v = prefs[SUPER_ISLAND_OUTER_GLOW] ?: false
        SettingsCache.superIslandOuterGlow = v
        v
    }

    suspend fun updateSuperIslandOuterGlow(glow: Boolean) {
        SettingsCache.superIslandOuterGlow = glow
        context.dataStore.edit { prefs ->
            prefs[SUPER_ISLAND_OUTER_GLOW] = glow
        }
        persistFastCache(context)
    }

    /**
     * 获取是否支持拖曳分享链接
     */
    val superIslandDragShareEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        val v = prefs[SUPER_ISLAND_DRAG_SHARE_ENABLED] ?: true
        SettingsCache.superIslandDragShareEnabled = v
        v
    }

    suspend fun updateSuperIslandDragShareEnabled(enabled: Boolean) {
        SettingsCache.superIslandDragShareEnabled = enabled
        context.dataStore.edit { prefs ->
            prefs[SUPER_ISLAND_DRAG_SHARE_ENABLED] = enabled
        }
        persistFastCache(context)
    }

    /**
     * 获取是否开启超级岛诊断日志
     */
    val superIslandLoggingEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        val v = prefs[SUPER_ISLAND_LOGGING_ENABLED] ?: false
        SettingsCache.superIslandLoggingEnabled = v
        v
    }

    suspend fun updateSuperIslandLoggingEnabled(enabled: Boolean) {
        SettingsCache.superIslandLoggingEnabled = enabled
        context.dataStore.edit { prefs ->
            prefs[SUPER_ISLAND_LOGGING_ENABLED] = enabled
        }
        persistFastCache(context)
    }

    /**
     * 获取是否显示触发提示
     */
    val showTriggerToast: Flow<Boolean> = context.dataStore.data.map { prefs ->
        val v = prefs[SHOW_TRIGGER_TOAST] ?: false
        SettingsCache.showTriggerToast = v
        v
    }

    /**
     * 更新是否显示触发提示
     */
    suspend fun updateShowTriggerToast(show: Boolean) {
        SettingsCache.showTriggerToast = show
        context.dataStore.edit { prefs ->
            prefs[SHOW_TRIGGER_TOAST] = show
        }
    }

    /**
     * 获取备选浏览器启动模式
     */
    val fallbackWindowMode: Flow<Int> = context.dataStore.data.map { prefs ->
        val mode = prefs[FALLBACK_WINDOW_MODE] ?: -1
        SettingsCache.fallbackWindowMode = mode
        mode
    }

    /**
     * 更新备选浏览器启动模式
     */
    suspend fun updateFallbackWindowMode(mode: Int) {
        SettingsCache.fallbackWindowMode = mode
        context.dataStore.edit { prefs ->
            prefs[FALLBACK_WINDOW_MODE] = mode
        }
    }

    /**
     * 获取跳转历史
     */
    val jumpHistory: Flow<List<com.moting.linkgo.model.JumpRecord>> = context.dataStore.data.map { prefs ->
        val jsonStr = prefs[JUMP_HISTORY] ?: "[]"
        val list = parseHistory(jsonStr)
        SettingsCache.jumpHistory = list
        list
    }

    /**
     * 获取总跳转次数
     */
    val totalJumpCount: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[TOTAL_JUMP_COUNT] ?: 0
    }

    /**
     * 获取标准化重构配置
     */
    val normalizationEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        val v = prefs[NORMALIZATION_ENABLED] ?: true
        SettingsCache.normalizationEnabled = v
        v
    }

    val normalizationRegex: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[NORMALIZATION_REGEX] ?: com.moting.linkgo.util.UrlUtils.DEFAULT_REGEX
    }

    val normalizationTemplate: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[NORMALIZATION_TEMPLATE] ?: com.moting.linkgo.util.UrlUtils.DEFAULT_TEMPLATE
    }

    /**
     * 更新标准化重构配置
     */
    suspend fun updateNormalizationEnabled(enabled: Boolean) {
        SettingsCache.normalizationEnabled = enabled
        context.dataStore.edit { prefs ->
            prefs[NORMALIZATION_ENABLED] = enabled
        }
    }

    suspend fun updateNormalizationConfig(regex: String, template: String) {
        context.dataStore.edit { prefs ->
            prefs[NORMALIZATION_REGEX] = regex
            prefs[NORMALIZATION_TEMPLATE] = template
        }
    }

    /**
     * 获取链接提取规则列表（含旧数据迁移逻辑）
     */
    val extractionPatterns: Flow<List<com.moting.linkgo.model.ExtractPattern>> = context.dataStore.data.map { prefs ->
        val json = prefs[EXTRACTION_PATTERNS]
        val defaults = com.moting.linkgo.model.ExtractPattern.builtinDefaults()
        
        if (!json.isNullOrBlank()) {
            parseExtractionPatterns(json)
        } else {
            // 迁移：检查旧的单正则字段
            val oldRegex = prefs[NORMALIZATION_REGEX]
            if (!oldRegex.isNullOrBlank() && oldRegex != com.moting.linkgo.util.UrlUtils.DEFAULT_REGEX) {
                listOf(
                    com.moting.linkgo.model.ExtractPattern(
                        id = "migrated_custom",
                        name = "自定义规则（迁移）",
                        pattern = oldRegex,
                        isBuiltin = false
                    )
                ) + defaults
            } else {
                defaults
            }
        }
    }

    /**
     * 更新链接提取规则列表（同步刷新 SettingsCache，使剪贴板监听等运行时立即生效）
     */
    suspend fun updateExtractionPatterns(patterns: List<com.moting.linkgo.model.ExtractPattern>) {
        context.dataStore.edit { prefs ->
            prefs[EXTRACTION_PATTERNS] = serializeExtractionPatterns(patterns)
        }
        SettingsCache.extractionPatterns = patterns
    }

    /**
     * 恢复出厂默认提取规则（重置已存在的默认规则并补齐缺失项，不删除用户自定义规则）
     */
    suspend fun resetBuiltinPatterns() {
        context.dataStore.edit { prefs ->
            val json = prefs[EXTRACTION_PATTERNS]
            val current = if (!json.isNullOrBlank()) parseExtractionPatterns(json) else com.moting.linkgo.model.ExtractPattern.builtinDefaults()
            val defaults = com.moting.linkgo.model.ExtractPattern.builtinDefaults()
            val defaultMap = defaults.associateBy { it.id }

            // 1. 如果已存在默认 ID 的项，恢复其默认名称与正则；其余自定义项原样保留
            val updated = current.map { p ->
                if (defaultMap.containsKey(p.id)) {
                    val default = defaultMap[p.id]!!
                    p.copy(name = default.name, pattern = default.pattern)
                } else {
                    p
                }
            }.toMutableList()

            // 2. 补齐被用户删除的默认规则
            val existingIds = updated.map { it.id }.toSet()
            val missing = defaults.filter { it.id !in existingIds }
            if (missing.isNotEmpty()) {
                updated.addAll(0, missing)
            }

            prefs[EXTRACTION_PATTERNS] = serializeExtractionPatterns(updated)
            SettingsCache.extractionPatterns = updated
        }
    }

    /**
     * 获取备选浏览器预热配置
     */
    val fallbackPreheatEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        val v = prefs[FALLBACK_PREHEAT_ENABLED] ?: false
        SettingsCache.fallbackPreheatEnabled = v
        v
    }

    val fallbackPreheatDelay: Flow<Long> = context.dataStore.data.map { prefs ->
        val v = prefs[FALLBACK_PREHEAT_DELAY] ?: 500L
        SettingsCache.fallbackPreheatDelay = v
        v
    }

    /**
     * 更新备选浏览器预热配置
     */
    suspend fun updateFallbackPreheatEnabled(enabled: Boolean) {
        SettingsCache.fallbackPreheatEnabled = enabled
        context.dataStore.edit { prefs ->
            prefs[FALLBACK_PREHEAT_ENABLED] = enabled
        }
    }

    suspend fun updateFallbackPreheatDelay(delay: Long) {
        SettingsCache.fallbackPreheatDelay = delay
        context.dataStore.edit { prefs ->
            prefs[FALLBACK_PREHEAT_DELAY] = delay
        }
    }

    /**
     * 获取助手劫持模式开关
     */
    val assistantBypassEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ASSISTANT_BYPASS_ENABLED] ?: false
    }

    /**
     * 更新助手劫持模式开关
     */
    suspend fun updateAssistantBypassEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ASSISTANT_BYPASS_ENABLED] = enabled
        }
    }

    /**
     * 获取跳转前自动解冻冻结应用开关
     */
    val autoUnfreezeEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        val v = prefs[AUTO_UNFREEZE_ENABLED] ?: true
        SettingsCache.autoUnfreezeEnabled = v
        v
    }

    /**
     * 更新跳转前自动解冻冻结应用开关
     */
    suspend fun updateAutoUnfreezeEnabled(enabled: Boolean) {
        SettingsCache.autoUnfreezeEnabled = enabled
        context.dataStore.edit { prefs ->
            prefs[AUTO_UNFREEZE_ENABLED] = enabled
        }
        persistFastCache(context)
    }

    /**
     * 获取解冻动作提示开关
     */
    val unfreezeShowToast: Flow<Boolean> = context.dataStore.data.map { prefs ->
        val v = prefs[UNFREEZE_SHOW_TOAST] ?: true
        SettingsCache.unfreezeShowToast = v
        v
    }

    /**
     * 更新解冻动作提示开关
     */
    suspend fun updateUnfreezeShowToast(enabled: Boolean) {
        SettingsCache.unfreezeShowToast = enabled
        context.dataStore.edit { prefs ->
            prefs[UNFREEZE_SHOW_TOAST] = enabled
        }
        persistFastCache(context)
    }

    /**
     * 获取全局特权总开关（权限中心；关闭后所有提权命令被拒）
     */
    val globalPrivilegeEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        val v = prefs[GLOBAL_PRIVILEGE_ENABLED] ?: true
        SettingsCache.globalPrivilegeEnabled = v
        v
    }

    /**
     * 更新全局特权总开关，并同步 [com.moting.linkgo.util.privilege.PrivilegeEngine]
     */
    suspend fun updateGlobalPrivilegeEnabled(enabled: Boolean) {
        SettingsCache.globalPrivilegeEnabled = enabled
        com.moting.linkgo.util.privilege.PrivilegeEngine.globalEnabled = enabled
        context.dataStore.edit { prefs ->
            prefs[GLOBAL_PRIVILEGE_ENABLED] = enabled
        }
        persistFastCache(context)
    }

    /**
     * 获取全局特权工作模式（"auto", "root", "shizuku"）
     */
    val privilegeMode: Flow<String> = context.dataStore.data.map { prefs ->
        val v = prefs[PRIVILEGE_MODE] ?: "auto"
        SettingsCache.privilegeMode = v
        v
    }

    /**
     * 更新全局特权工作模式，并同步 [com.moting.linkgo.util.privilege.PrivilegeEngine]
     */
    suspend fun updatePrivilegeMode(mode: String) {
        SettingsCache.privilegeMode = mode
        com.moting.linkgo.util.privilege.PrivilegeEngine.mode =
            com.moting.linkgo.util.privilege.PrivilegeEngine.PrivilegeMode.fromString(mode)
        context.dataStore.edit { prefs ->
            prefs[PRIVILEGE_MODE] = mode
        }
        persistFastCache(context)
    }

    /**
     * 获取应用内链接捕获上次选择的子模式（1=拦截, 2=询问）
     */
    val lastAppLinkSubMode: Flow<Int> = context.dataStore.data.map { prefs ->
        val v = prefs[LAST_APP_LINK_SUB_MODE] ?: 1
        SettingsCache.lastAppLinkSubMode = v
        v
    }

    /**
     * 更新应用内链接捕获上次选择的子模式
     */
    suspend fun updateLastAppLinkSubMode(subMode: Int) {
        val clamped = subMode.coerceIn(1, 2)
        SettingsCache.lastAppLinkSubMode = clamped
        context.dataStore.edit { prefs ->
            prefs[LAST_APP_LINK_SUB_MODE] = clamped
        }
        persistFastCache(context)
    }

    /**
     * 获取全部能力开关状态（Map：Capability -> enabled），UI 一次订阅。
     */
    val capabilities: Flow<Map<com.moting.linkgo.util.privilege.CapabilityCenter.Capability, Boolean>> =
        context.dataStore.data.map { prefs ->
            val map = mutableMapOf<com.moting.linkgo.util.privilege.CapabilityCenter.Capability, Boolean>()
            prefs[CAP_SHIZUKU_SILENT_GRANT]?.let { SettingsCache.capShizukuSilentGrant = it }
            prefs[CAP_A11Y_HEAL_BOOT]?.let { SettingsCache.capA11yHealBoot = it }
            prefs[CAP_A11Y_HEAL_APP]?.let { SettingsCache.capA11yHealApp = it }
            prefs[CAP_A11Y_PREWARM_CLIPBOARD]?.let { SettingsCache.capA11yPrewarmClipboard = it }
            prefs[CAP_BATTERY_SILENT_WHITELIST]?.let { SettingsCache.capBatterySilentWhitelist = it }
            map[com.moting.linkgo.util.privilege.CapabilityCenter.Capability.SHIZUKU_SILENT_GRANT] = SettingsCache.capShizukuSilentGrant
            map[com.moting.linkgo.util.privilege.CapabilityCenter.Capability.A11Y_HEAL_BOOT] = SettingsCache.capA11yHealBoot
            map[com.moting.linkgo.util.privilege.CapabilityCenter.Capability.A11Y_HEAL_APP] = SettingsCache.capA11yHealApp
            map[com.moting.linkgo.util.privilege.CapabilityCenter.Capability.A11Y_PREWARM_CLIPBOARD] = SettingsCache.capA11yPrewarmClipboard
            map[com.moting.linkgo.util.privilege.CapabilityCenter.Capability.BATTERY_SILENT_WHITELIST] = SettingsCache.capBatterySilentWhitelist
            map
        }

    /**
     * 更新单个能力开关（能力层静默降级开关）
     */
    suspend fun updateCapability(cap: com.moting.linkgo.util.privilege.CapabilityCenter.Capability, enabled: Boolean) {
        com.moting.linkgo.util.privilege.CapabilityCenter.setEnabled(cap, enabled)
        context.dataStore.edit { prefs ->
            when (cap) {
                com.moting.linkgo.util.privilege.CapabilityCenter.Capability.SHIZUKU_SILENT_GRANT -> prefs[CAP_SHIZUKU_SILENT_GRANT] = enabled
                com.moting.linkgo.util.privilege.CapabilityCenter.Capability.A11Y_HEAL_BOOT -> prefs[CAP_A11Y_HEAL_BOOT] = enabled
                com.moting.linkgo.util.privilege.CapabilityCenter.Capability.A11Y_HEAL_APP -> prefs[CAP_A11Y_HEAL_APP] = enabled
                com.moting.linkgo.util.privilege.CapabilityCenter.Capability.A11Y_PREWARM_CLIPBOARD -> prefs[CAP_A11Y_PREWARM_CLIPBOARD] = enabled
                com.moting.linkgo.util.privilege.CapabilityCenter.Capability.BATTERY_SILENT_WHITELIST -> prefs[CAP_BATTERY_SILENT_WHITELIST] = enabled
            }
        }
        persistFastCache(context)
    }

    /**
     * 获取是否开启系统动态配色
     */
    val dynamicColorEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        val v = prefs[DYNAMIC_COLOR_ENABLED] ?: true
        SettingsCache.dynamicColorEnabled = v
        v
    }

    /**
     * 更新系统动态配色状态
     */
    suspend fun updateDynamicColorEnabled(enabled: Boolean) {
        SettingsCache.dynamicColorEnabled = enabled
        context.dataStore.edit { prefs ->
            prefs[DYNAMIC_COLOR_ENABLED] = enabled
        }
    }

    /**
     * 获取是否开启半屏弹窗全屏背景高斯模糊与毛玻璃视效 (Android 12+)
     */
    val backgroundBlurEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        val v = prefs[BACKGROUND_BLUR_ENABLED] ?: true
        SettingsCache.backgroundBlurEnabled = v
        v
    }

    /**
     * 更新半屏弹窗高斯模糊开关
     */
    suspend fun updateBackgroundBlurEnabled(enabled: Boolean) {
        SettingsCache.backgroundBlurEnabled = enabled
        context.dataStore.edit { prefs ->
            prefs[BACKGROUND_BLUR_ENABLED] = enabled
        }
        try {
            context.getSharedPreferences(FAST_CACHE_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean("background_blur_enabled", enabled)
                .apply()
        } catch (e: Exception) {}
    }

    /**
     * 获取浏览器选择器隐藏列表
     */
    val browserHiddenList: Flow<List<String>> = context.dataStore.data.map { prefs ->
        prefs[BROWSER_SELECTOR_HIDDEN]?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
    }

    /**
     * 更新浏览器选择器隐藏列表
     */
    suspend fun updateBrowserHiddenList(list: List<String>) {
        context.dataStore.edit { prefs ->
            prefs[BROWSER_SELECTOR_HIDDEN] = list.joinToString(",")
        }
    }

    /**
     * 获取浏览器选择器排序列表
     */
    val browserOrderList: Flow<List<String>> = context.dataStore.data.map { prefs ->
        prefs[BROWSER_SELECTOR_ORDER]?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
    }

    /**
     * 更新浏览器选择器排序列表
     */
    suspend fun updateBrowserOrderList(list: List<String>) {
        context.dataStore.edit { prefs ->
            prefs[BROWSER_SELECTOR_ORDER] = list.joinToString(",")
        }
    }

    /**
     * 获取浏览器选择器倒计时时长 (秒)
     */
    val browserSelectorTimer: Flow<Int> = context.dataStore.data.map { prefs ->
        val v = prefs[BROWSER_SELECTOR_TIMER] ?: 5
        SettingsCache.browserSelectorTimer = v
        v
    }

    /**
     * 更新浏览器选择器倒计时时长
     */
    suspend fun updateBrowserSelectorTimer(seconds: Int) {
        SettingsCache.browserSelectorTimer = seconds
        context.dataStore.edit { prefs ->
            prefs[BROWSER_SELECTOR_TIMER] = seconds
        }
    }

    /**
     * 获取剪贴板后台监听开关
     */
    val clipboardMonitorEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        val v = prefs[CLIPBOARD_MONITOR_ENABLED] ?: false
        SettingsCache.clipboardMonitorEnabled = v
        v
    }

    /**
     * 更新剪贴板后台监听开关
     */
    suspend fun updateClipboardMonitorEnabled(enabled: Boolean) {
        SettingsCache.clipboardMonitorEnabled = enabled
        context.dataStore.edit { prefs ->
            prefs[CLIPBOARD_MONITOR_ENABLED] = enabled
        }
    }

    /**
     * 获取剪贴板监听后端（完整模式，6 种取值见 ClipboardBackend）。
     * 旧 clipboard_monitor_mode 值（root/shizuku）会自动迁移映射。
     */
    val clipboardMonitorBackend: Flow<String> = context.dataStore.data.map { prefs ->
        val backend = prefs[CLIPBOARD_MONITOR_BACKEND]
        val res = if (backend != null) backend
        else com.moting.linkgo.clipboard.ClipboardBackend.migrateFromLegacyMode(prefs[CLIPBOARD_MONITOR_MODE])
        SettingsCache.clipboardMonitorBackend = res
        res
    }

    /**
     * 更新剪贴板监听后端
     */
    suspend fun updateClipboardMonitorBackend(backend: String) {
        SettingsCache.clipboardMonitorBackend = backend
        context.dataStore.edit { prefs ->
            prefs[CLIPBOARD_MONITOR_BACKEND] = backend
        }
    }

    /**
     * 获取侧边悬浮胶囊的自动消失时长（秒，2~30，默认 5）。
     */
    val clipboardAutoDismissSeconds: Flow<Int> = context.dataStore.data.map { prefs ->
        val v = (prefs[CLIPBOARD_AUTO_DISMISS_SECONDS] ?: 5).coerceIn(2, 30)
        SettingsCache.clipboardAutoDismissSeconds = v
        v
    }

    /**
     * 更新侧边悬浮胶囊的自动消失时长（秒，2~30）。
     */
    suspend fun updateClipboardAutoDismissSeconds(seconds: Int) {
        val clamped = seconds.coerceIn(2, 30)
        SettingsCache.clipboardAutoDismissSeconds = clamped
        context.dataStore.edit { prefs ->
            prefs[CLIPBOARD_AUTO_DISMISS_SECONDS] = clamped
        }
        persistFastCache(context)
    }

    /**
     * 获取「跳转后清空剪贴板」开关（用于防止目标应用读取剪贴板再次触发跳转）。
     */
    val clearClipboardAfterJump: Flow<Boolean> = context.dataStore.data.map { prefs ->
        val v = prefs[CLEAR_CLIPBOARD_AFTER_JUMP] ?: false
        SettingsCache.clearClipboardAfterJump = v
        v
    }

    /**
     * 更新「跳转后清空剪贴板」开关
     */
    suspend fun updateClearClipboardAfterJump(enabled: Boolean) {
        SettingsCache.clearClipboardAfterJump = enabled
        context.dataStore.edit { prefs ->
            prefs[CLEAR_CLIPBOARD_AFTER_JUMP] = enabled
        }
    }

    /**
     * 获取「剪贴板变动提示」开关（剪贴板变动时立即弹出“已复制”Toast 并覆盖旧通知）。
     */
    val clipboardChangeToastEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        val v = prefs[CLIPBOARD_CHANGE_TOAST_ENABLED] ?: false
        SettingsCache.clipboardChangeToastEnabled = v
        v
    }

    /**
     * 更新「剪贴板变动提示」开关
     */
    suspend fun updateClipboardChangeToastEnabled(enabled: Boolean) {
        SettingsCache.clipboardChangeToastEnabled = enabled
        context.dataStore.edit { prefs ->
            prefs[CLIPBOARD_CHANGE_TOAST_ENABLED] = enabled
        }
        persistFastCache(context)
    }

    /**
     * 获取「剪贴板变动广播」开关（剪贴板变动时对外广播事件，供其他应用接收）。
     */
    val clipboardBroadcastEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        val v = prefs[CLIPBOARD_BROADCAST_ENABLED] ?: false
        SettingsCache.clipboardBroadcastEnabled = v
        v
    }

    /**
     * 更新「剪贴板变动广播」开关
     */
    suspend fun updateClipboardBroadcastEnabled(enabled: Boolean) {
        SettingsCache.clipboardBroadcastEnabled = enabled
        context.dataStore.edit { prefs ->
            prefs[CLIPBOARD_BROADCAST_ENABLED] = enabled
        }
    }

    /**
     * 获取「应用内链接捕获」模式（0=关闭 1=拦截 2=询问）。
     */
    val appLinkCaptureMode: Flow<Int> = context.dataStore.data.map { prefs ->
        val v = (prefs[APP_LINK_CAPTURE_MODE] ?: 0).coerceIn(0, 2)
        SettingsCache.appLinkCaptureMode = v
        v
    }

    /**
     * 更新「应用内链接捕获」模式（0=关闭 1=拦截 2=询问）。
     */
    suspend fun updateAppLinkCaptureMode(mode: Int) {
        val clamped = mode.coerceIn(0, 2)
        SettingsCache.appLinkCaptureMode = clamped
        context.dataStore.edit { prefs ->
            prefs[APP_LINK_CAPTURE_MODE] = clamped
        }
        persistFastCache(context)
    }

    /**
     * 获取「询问模式下跳转后销毁原内置浏览器页面」开关。
     */
    val appLinkAskAutoFinish: Flow<Boolean> = context.dataStore.data.map { prefs ->
        val v = prefs[APP_LINK_ASK_AUTO_FINISH] ?: false
        SettingsCache.appLinkAskAutoFinish = v
        v
    }

    /**
     * 更新「询问模式下跳转后销毁原内置浏览器页面」开关。
     */
    suspend fun updateAppLinkAskAutoFinish(enabled: Boolean) {
        SettingsCache.appLinkAskAutoFinish = enabled
        context.dataStore.edit { prefs ->
            prefs[APP_LINK_ASK_AUTO_FINISH] = enabled
        }
        persistFastCache(context)
    }

    /**
     * 获取「仅命中规则时拦截」开关（仅直接拦截模式生效）。
     */
    val appLinkRuleOnlyIntercept: Flow<Boolean> = context.dataStore.data.map { prefs ->
        val v = prefs[APP_LINK_RULE_ONLY_INTERCEPT] ?: false
        SettingsCache.appLinkRuleOnlyIntercept = v
        v
    }

    /**
     * 更新「仅命中规则时拦截」开关：未命中跳转规则的链接一律放行，交回应用内置浏览器打开。
     */
    suspend fun updateAppLinkRuleOnlyIntercept(enabled: Boolean) {
        SettingsCache.appLinkRuleOnlyIntercept = enabled
        context.dataStore.edit { prefs ->
            prefs[APP_LINK_RULE_ONLY_INTERCEPT] = enabled
        }
        persistFastCache(context)
    }

    /**
     * 获取应用内链接捕获的接管应用列表（仅列表内应用的链接被 LinkGo 处理）。
     */
    val appLinkCaptureApps: Flow<List<com.moting.linkgo.model.CaptureApp>> = context.dataStore.data.map { prefs ->
        val v = prefs[APP_LINK_CAPTURE_APPS]?.let { parseCaptureApps(it) }
            ?: com.moting.linkgo.model.CaptureApp.defaults()
        SettingsCache.appLinkCaptureApps = v
        v
    }

    /**
     * 更新接管应用列表（同步刷新 SettingsCache 并持久化快照）。
     */
    suspend fun updateAppLinkCaptureApps(list: List<com.moting.linkgo.model.CaptureApp>) {
        SettingsCache.appLinkCaptureApps = list
        context.dataStore.edit { prefs ->
            prefs[APP_LINK_CAPTURE_APPS] = serializeCaptureApps(list)
        }
        persistFastCache(context)
    }

    /**
     * 切换单个接管应用的启用状态。
     */
    suspend fun toggleCaptureAppEnabled(pkg: String, isEnabled: Boolean) {
        val current = SettingsCache.appLinkCaptureApps
        val updated = current.map { if (it.packageName == pkg) it.copy(isEnabled = isEnabled) else it }
        updateAppLinkCaptureApps(updated)
    }

    /**
     * 重置应用内链接捕获为出厂默认：接管应用（微信/QQ）+ 放行域名（出厂默认）。
     * 与 [ExemptDomain.defaults] 的来源保持一致，避免两处默认值漂移。
     */
    suspend fun resetAppLinkCaptureDefaults() {
        updateAppLinkCaptureApps(com.moting.linkgo.model.CaptureApp.defaults())
        resetAppLinkExemptDomains()
    }

    /**
     * 获取应用内链接捕获的豁免域名规则列表。
     */
    val appLinkExemptDomains: Flow<List<com.moting.linkgo.model.ExemptDomain>> = context.dataStore.data.map { prefs ->
        val v = prefs[APP_LINK_EXEMPT_DOMAINS]?.let { parseExemptDomains(it) }
            ?: com.moting.linkgo.model.ExemptDomain.defaults()
        SettingsCache.appLinkExemptDomains = v
        v
    }

    /**
     * 更新豁免域名规则列表（同步刷新 SettingsCache 并持久化快照）。
     */
    suspend fun updateAppLinkExemptDomains(list: List<com.moting.linkgo.model.ExemptDomain>) {
        SettingsCache.appLinkExemptDomains = list
        context.dataStore.edit { prefs ->
            prefs[APP_LINK_EXEMPT_DOMAINS] = serializeExemptDomains(list)
        }
        persistFastCache(context)
    }

    /**
     * 重置豁免域名为出厂默认列表（清空所有调整）。
     */
    suspend fun resetAppLinkExemptDomains() {
        updateAppLinkExemptDomains(com.moting.linkgo.model.ExemptDomain.defaults())
    }


    /**
     * 获取剪贴板触发胶囊的停靠位置（贴边方向 + 垂直归一化比例；null 表示从未调整过）。
     * 旧的中心点比例数据（clipboard_capsule_x）会自动迁移为贴边方向。
     */
    val capsuleAnchor: Flow<CapsuleAnchor?> = context.dataStore.data.map { prefs ->
        val y = prefs[CAPSULE_Y]
        val edge = prefs[CAPSULE_EDGE] ?: prefs[CAPSULE_X]?.let { if (it < 0.5f) "left" else "right" }
        if (edge != null && y != null) CapsuleAnchor(edge, y) else null
    }

    /**
     * 保存胶囊停靠位置（贴边方向 + 垂直归一化比例）。
     */
    suspend fun updateCapsuleAnchor(edge: String, yRatio: Float) {
        SettingsCache.capsuleEdge = edge
        SettingsCache.capsuleYRatio = yRatio
        context.dataStore.edit { prefs ->
            prefs[CAPSULE_EDGE] = edge
            prefs[CAPSULE_Y] = yRatio
        }
        persistFastCache(context)
    }

    /**
     * 记录一次跳转
     */
    suspend fun addJumpRecord(record: com.moting.linkgo.model.JumpRecord) {
        context.dataStore.edit { prefs ->
            // 1. 更新总数
            val currentCount = prefs[TOTAL_JUMP_COUNT] ?: 0
            prefs[TOTAL_JUMP_COUNT] = currentCount + 1

            // 2. 更新历史列表 (上限 200)
            val jsonStr = prefs[JUMP_HISTORY] ?: "[]"
            val history = parseHistory(jsonStr).toMutableList()
            history.add(0, record) // 插入到开头
            val finalHistory = if (history.size > 200) history.take(200) else history
            prefs[JUMP_HISTORY] = serializeHistory(finalHistory)
        }
    }

    /**
     * 清空历史
     */
    suspend fun clearHistory() {
        context.dataStore.edit { prefs ->
            prefs.remove(JUMP_HISTORY)
        }
    }

    /**
     * 整体替换跳转历史与总次数（备份恢复用）。
     */
    suspend fun replaceJumpHistory(history: List<com.moting.linkgo.model.JumpRecord>, totalCount: Int) {
        context.dataStore.edit { prefs ->
            prefs[JUMP_HISTORY] = serializeHistory(history.take(200))
            prefs[TOTAL_JUMP_COUNT] = totalCount
        }
        SettingsCache.jumpHistory = history.take(200)
    }

    /**
     * 删除指定链路/单条跳转记录
     */
    suspend fun deleteJumpRecordsByTraceId(traceIdOrId: String) {
        context.dataStore.edit { prefs ->
            val jsonStr = prefs[JUMP_HISTORY] ?: "[]"
            val history = parseHistory(jsonStr).toMutableList()
            history.removeAll { (it.traceId ?: it.id) == traceIdOrId || it.id == traceIdOrId }
            prefs[JUMP_HISTORY] = serializeHistory(history)
        }
    }

    private fun parseRules(json: String): List<com.moting.linkgo.model.DispatchRule> {
        return try {
            val root = org.json.JSONTokener(json).nextValue()
            val arr = if (root is org.json.JSONObject) {
                if (root.has("linkgo_rules")) {
                    root.getJSONArray("linkgo_rules")
                } else {
                    org.json.JSONArray()
                }
            } else if (root is org.json.JSONArray) {
                root
            } else {
                org.json.JSONArray()
            }

            val list = mutableListOf<com.moting.linkgo.model.DispatchRule>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    com.moting.linkgo.model.DispatchRule(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        name = obj.optString("name", "未命名规则"),
                        pattern = obj.optString("pattern", ""),
                        targetPackage = obj.optString("targetPackage", ""),
                        template = if (obj.has("template")) obj.getString("template") else null,
                        ruleLaunchMode = obj.optInt("ruleLaunchMode", -1),
                        matchType = try {
                            com.moting.linkgo.model.MatchType.valueOf(obj.optString("matchType", "REGEX"))
                        } catch (e: Exception) {
                            com.moting.linkgo.model.MatchType.REGEX
                        },
                        isEnabled = obj.optBoolean("isEnabled", true),
                        resolveShortLink = obj.optBoolean("resolveShortLink", false),
                        resolveStrategy = try {
                            val strategyName = obj.optString("resolveStrategy", "")
                            if (strategyName.isNotBlank()) {
                                com.moting.linkgo.model.ResolutionStrategy.valueOf(strategyName)
                            } else {
                                if (obj.optBoolean("resolveShortLink", false)) {
                                    com.moting.linkgo.model.ResolutionStrategy.DIRECT
                                } else {
                                    com.moting.linkgo.model.ResolutionStrategy.NONE
                                }
                            }
                        } catch (e: Exception) {
                            if (obj.optBoolean("resolveShortLink", false)) {
                                com.moting.linkgo.model.ResolutionStrategy.DIRECT
                            } else {
                                com.moting.linkgo.model.ResolutionStrategy.NONE
                            }
                        },
                        excludeFromRecents = obj.optBoolean("excludeFromRecents", false),
                        extractPattern = if (obj.has("extractPattern")) obj.getString("extractPattern") else null,
                        isPreheatEnabled = obj.optBoolean("isPreheatEnabled", false),
                        preheatDelayMillis = obj.optLong("preheatDelayMillis", 500L),
                        iconPath = if (obj.has("iconPath")) obj.getString("iconPath") else null,
                        jumpAndCopy = obj.optBoolean("jumpAndCopy", false)
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun serializeRules(rules: List<com.moting.linkgo.model.DispatchRule>): String {
        val arr = org.json.JSONArray()
        rules.forEach { rule ->
            val obj = org.json.JSONObject()
            obj.put("id", rule.id)
            obj.put("name", rule.name)
            obj.put("pattern", rule.pattern)
            obj.put("targetPackage", rule.targetPackage)
            obj.put("template", rule.template)
            obj.put("ruleLaunchMode", rule.ruleLaunchMode)
            obj.put("matchType", rule.matchType?.name ?: com.moting.linkgo.model.MatchType.REGEX.name)
            obj.put("isEnabled", rule.isEnabled)
            obj.put("resolveShortLink", rule.resolveShortLink)
            obj.put("resolveStrategy", rule.resolveStrategy?.name ?: com.moting.linkgo.model.ResolutionStrategy.NONE.name)
            obj.put("excludeFromRecents", rule.excludeFromRecents)
            obj.put("extractPattern", rule.extractPattern)
            obj.put("isPreheatEnabled", rule.isPreheatEnabled)
            obj.put("preheatDelayMillis", rule.preheatDelayMillis)
            obj.put("iconPath", rule.iconPath)
            obj.put("jumpAndCopy", rule.jumpAndCopy)
            arr.put(obj)
        }
        return arr.toString()
    }

    private fun parseHistory(json: String): List<com.moting.linkgo.model.JumpRecord> {        return try {
            val arr = org.json.JSONArray(json)
            val list = mutableListOf<com.moting.linkgo.model.JumpRecord>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    com.moting.linkgo.model.JumpRecord(
                        id = obj.getString("id"),
                        timestamp = obj.getLong("timestamp"),
                        ruleName = obj.getString("ruleName"),
                        ruleId = if (obj.isNull("ruleId")) null else obj.getString("ruleId"),
                        originalUrl = obj.getString("originalUrl"),
                        targetPackage = obj.getString("targetPackage"),
                        rulePattern = if (obj.has("rulePattern") && !obj.isNull("rulePattern")) obj.getString("rulePattern") else null,
                        matchTypeName = if (obj.has("matchTypeName") && !obj.isNull("matchTypeName")) obj.getString("matchTypeName") else null,
                        executionStatus = obj.optInt("executionStatus", 0),
                        errorMessage = if (obj.has("errorMessage") && !obj.isNull("errorMessage")) obj.getString("errorMessage") else null,
                        traceId = if (obj.has("traceId") && !obj.isNull("traceId")) obj.getString("traceId") else null,
                        resultUrl = if (obj.has("resultUrl") && !obj.isNull("resultUrl")) obj.getString("resultUrl") else null,
                        stepIndex = obj.optInt("stepIndex", 0),
                        kind = obj.optString("kind", com.moting.linkgo.model.JumpRecord.KIND_TEXT)
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun serializeHistory(history: List<com.moting.linkgo.model.JumpRecord>): String {
        val arr = org.json.JSONArray()
        history.forEach { record ->
            val obj = org.json.JSONObject()
            obj.put("id", record.id)
            obj.put("timestamp", record.timestamp)
            obj.put("ruleName", record.ruleName)
            obj.put("ruleId", record.ruleId)
            obj.put("originalUrl", record.originalUrl)
            obj.put("targetPackage", record.targetPackage)
            obj.put("rulePattern", record.rulePattern)
            obj.put("matchTypeName", record.matchTypeName)
            obj.put("executionStatus", record.executionStatus)
            obj.put("errorMessage", record.errorMessage)
            obj.put("traceId", record.traceId)
            obj.put("resultUrl", record.resultUrl)
            obj.put("stepIndex", record.stepIndex)
            obj.put("kind", record.kind)
            arr.put(obj)
        }
        return arr.toString()
    }

    /**
     * 原子批量恢复所有设置项（备份恢复用，单次 DataStore 事务）
     */
    suspend fun batchRestoreSettings(s: com.moting.linkgo.data.backup.SettingsSnapshot) {
        context.dataStore.edit { prefs ->
            prefs[FLOATING_BOTTOM_BAR_ENABLED] = s.floatingBottomBarEnabled
            prefs[CLIPBOARD_MONITOR_ENABLED] = s.clipboardMonitorEnabled
            prefs[CLIPBOARD_MONITOR_BACKEND] = s.clipboardMonitorBackend
            prefs[BROWSER_SELECTOR_TIMER] = s.browserSelectorTimer
            prefs[SHOW_TRIGGER_TOAST] = s.showTriggerToast
            prefs[DYNAMIC_COLOR_ENABLED] = s.dynamicColorEnabled
            if (s.fallbackBrowser != null) {
                prefs[FALLBACK_BROWSER] = s.fallbackBrowser
            } else {
                prefs.remove(FALLBACK_BROWSER)
            }
            prefs[FALLBACK_WINDOW_MODE] = s.fallbackWindowMode
            prefs[FALLBACK_PREHEAT_ENABLED] = s.fallbackPreheatEnabled
            prefs[FALLBACK_PREHEAT_DELAY] = s.fallbackPreheatDelay
            prefs[EXCLUDE_FROM_RECENTS] = s.excludeFromRecents
            prefs[FALLBACK_EXCLUDE_FROM_RECENTS] = s.fallbackExcludeFromRecents
            prefs[NORMALIZATION_ENABLED] = s.normalizationEnabled
            prefs[NORMALIZATION_REGEX] = s.normalizationRegex
            prefs[NORMALIZATION_TEMPLATE] = s.normalizationTemplate
            prefs[SHOW_RICH_NOTIFICATION] = s.showRichNotification
            prefs[NOTIFICATION_STYLE] = s.notificationStyle
            prefs[NOTIFICATION_AUTO_DISMISS_SECONDS] = s.notificationAutoDismissSeconds
            prefs[SUPER_ISLAND_BYPASS_ENABLED] = s.superIslandBypassEnabled
            prefs[SUPER_ISLAND_BYPASS_DURATION_MS] = s.superIslandBypassDurationMs
            prefs[SUPER_ISLAND_OUTER_GLOW] = s.superIslandOuterGlow
            prefs[SUPER_ISLAND_DRAG_SHARE_ENABLED] = s.superIslandDragShareEnabled
            prefs[ASSISTANT_BYPASS_ENABLED] = s.assistantBypassEnabled
            prefs[AUTO_UNFREEZE_ENABLED] = s.autoUnfreezeEnabled
            prefs[UNFREEZE_SHOW_TOAST] = s.unfreezeShowToast
            prefs[GLOBAL_PRIVILEGE_ENABLED] = s.globalPrivilegeEnabled
            prefs[CAP_SHIZUKU_SILENT_GRANT] = s.capShizukuSilentGrant
            prefs[CAP_A11Y_HEAL_BOOT] = s.capA11yHealBoot
            prefs[CAP_A11Y_HEAL_APP] = s.capA11yHealApp
            prefs[CAP_A11Y_PREWARM_CLIPBOARD] = s.capA11yPrewarmClipboard
            prefs[CAP_BATTERY_SILENT_WHITELIST] = s.capBatterySilentWhitelist
            prefs[CLEAR_CLIPBOARD_AFTER_JUMP] = s.clearClipboardAfterJump
            prefs[CLIPBOARD_CHANGE_TOAST_ENABLED] = s.clipboardChangeToastEnabled
            prefs[CLIPBOARD_BROADCAST_ENABLED] = s.clipboardBroadcastEnabled
            prefs[CLIPBOARD_AUTO_DISMISS_SECONDS] = s.clipboardAutoDismissSeconds
            prefs[MAX_MULTI_CAPSULE_COUNT] = s.maxMultiCapsuleCount
            prefs[CAPSULE_DISMISS_ON_TOUCH_OUTSIDE] = s.capsuleDismissOnTouchOutside
            prefs[LAST_SYSTEM_PRIVILEGE] = s.lastSystemPrivilege
            prefs[LAST_SYSTEM_WAY] = s.lastSystemWay
            prefs[PRIVILEGE_MODE] = s.privilegeMode
            prefs[LAST_APP_LINK_SUB_MODE] = s.lastAppLinkSubMode
            prefs[CAPSULE_EDGE] = s.capsuleEdge
            prefs[CAPSULE_Y] = s.capsuleYRatio
            prefs[BROWSER_SELECTOR_HIDDEN] = s.browserHiddenList.joinToString(",")
            prefs[BROWSER_SELECTOR_ORDER] = s.browserOrderList.joinToString(",")

            // 背景模糊与超级岛诊断
            prefs[BACKGROUND_BLUR_ENABLED] = s.backgroundBlurEnabled
            prefs[SUPER_ISLAND_LOGGING_ENABLED] = s.superIslandLoggingEnabled

            // 快捷手势（开关平铺字段优先，整体配置兜底，兼容旧备份）
            val eg = s.edgeGesture
            val egEnabled = s.edgeGestureEnabled || eg.enabled
            prefs[EDGE_GESTURE_ENABLED] = egEnabled
            prefs[EDGE_GESTURE_WIDTH_DP] = eg.widthDp
            prefs[EDGE_GESTURE_HEIGHT_DP] = eg.heightDp
            prefs[EDGE_GESTURE_WIDTH_RATIO] = eg.widthDp / 392f
            prefs[EDGE_GESTURE_HEIGHT_RATIO] = eg.heightDp / 850f
            prefs[EDGE_GESTURE_VERTICAL_RATIO] = eg.verticalRatio
            prefs[EDGE_GESTURE_HORIZONTAL_RATIO] = eg.horizontalRatio
            prefs[EDGE_GESTURE_ALPHA] = eg.edgeAlpha
            prefs[EDGE_GESTURE_MIRROR] = eg.mirrorEnabled
            prefs[EDGE_GESTURE_CLICK_ACTION] = eg.clickAction.value
            prefs[EDGE_GESTURE_SWIPE_ACTION] = eg.swipeAction.value
            prefs[EDGE_GESTURE_LONG_PRESS_ACTION] = eg.longPressAction.value
            prefs[EDGE_GESTURE_BALL_RADIUS] = eg.ballRadiusDp
            prefs[EDGE_GESTURE_BALL_STROKE] = eg.ballStrokeWidthDp
            prefs[EDGE_GESTURE_BALL_ALPHA] = eg.ballAlpha
            prefs[EDGE_GESTURE_BALL_STYLE] = eg.ballStyle.value
            prefs[EDGE_GESTURE_CONTROL_MODE] = eg.controlMode.value
            prefs[EDGE_GESTURE_POINTER_SENSITIVITY] = eg.pointerSensitivity
            prefs[EDGE_GESTURE_POINTER_INITIAL_DISTANCE] = eg.pointerInitialDistanceDp
            prefs[EDGE_GESTURE_FINGER_OFFSET_Y] = eg.fingerOffsetYDp
            prefs[EDGE_GESTURE_GESTURE_HAPTIC] = eg.gestureHapticEnabled
            prefs[EDGE_GESTURE_COLLISION_HAPTIC] = eg.collisionHapticEnabled
            prefs[EDGE_GESTURE_HAPTIC] = eg.collisionHapticEnabled
            prefs[EDGE_GESTURE_BUBBLE_SQUISH] = eg.bubbleSquishEnabled
            prefs[EDGE_GESTURE_STAY_DURATION_MS] = eg.stayDurationMs
            prefs[EDGE_GESTURE_ORIENTATION_SCOPE] = eg.orientationScope.value
            prefs[EDGE_GESTURE_APP_SCOPE_MODE] = eg.appScopeMode.value
            prefs[EDGE_GESTURE_APP_SCOPE_PACKAGES] = eg.appScopePackages

            // 应用内链接捕获（null = 该备份未包含，跳过以免误清空现有配置）
            s.appLinkCaptureMode?.let { prefs[APP_LINK_CAPTURE_MODE] = it.coerceIn(0, 2) }
            s.appLinkAskAutoFinish?.let { prefs[APP_LINK_ASK_AUTO_FINISH] = it }
            s.appLinkRuleOnlyIntercept?.let { prefs[APP_LINK_RULE_ONLY_INTERCEPT] = it }
            s.appLinkCaptureApps?.let { prefs[APP_LINK_CAPTURE_APPS] = serializeCaptureApps(it) }
            s.appLinkExemptDomains?.let { prefs[APP_LINK_EXEMPT_DOMAINS] = serializeExemptDomains(it) }

            // 小窗配置
            val w = s.windowConfig
            prefs[PORT_WIDTH] = w.portWidthRatio
            prefs[PORT_HEIGHT] = w.portHeightRatio
            prefs[PORT_X] = w.portXRatio
            prefs[PORT_Y] = w.portYRatio
            prefs[LAND_WIDTH] = w.landWidthRatio
            prefs[LAND_HEIGHT] = w.landHeightRatio
            prefs[LAND_X] = w.landXRatio
            prefs[LAND_Y] = w.landYRatio
            prefs[WINDOW_MODE] = w.windowingMode
            prefs[SMALL_WINDOW_ENABLED] = w.isEnabled
        }

        // 同步内存缓存
        SettingsCache.floatingBottomBarEnabled = s.floatingBottomBarEnabled
        SettingsCache.clipboardMonitorEnabled = s.clipboardMonitorEnabled
        SettingsCache.clipboardMonitorBackend = s.clipboardMonitorBackend
        SettingsCache.browserSelectorTimer = s.browserSelectorTimer
        SettingsCache.showTriggerToast = s.showTriggerToast
        SettingsCache.dynamicColorEnabled = s.dynamicColorEnabled
        SettingsCache.fallbackBrowser = s.fallbackBrowser
        SettingsCache.fallbackWindowMode = s.fallbackWindowMode
        SettingsCache.fallbackPreheatEnabled = s.fallbackPreheatEnabled
        SettingsCache.fallbackPreheatDelay = s.fallbackPreheatDelay
        SettingsCache.excludeFromRecents = s.excludeFromRecents
        SettingsCache.fallbackExcludeFromRecents = s.fallbackExcludeFromRecents
        SettingsCache.normalizationEnabled = s.normalizationEnabled
        SettingsCache.showRichNotification = s.showRichNotification
        SettingsCache.notificationStyle = NotificationStyle.fromValue(s.notificationStyle)
        SettingsCache.notificationAutoDismissSeconds = s.notificationAutoDismissSeconds
        SettingsCache.superIslandBypassEnabled = s.superIslandBypassEnabled
        SettingsCache.superIslandBypassDurationMs = s.superIslandBypassDurationMs
        SettingsCache.superIslandOuterGlow = s.superIslandOuterGlow
        SettingsCache.superIslandDragShareEnabled = s.superIslandDragShareEnabled
        SettingsCache.assistantBypassEnabled = s.assistantBypassEnabled
        SettingsCache.autoUnfreezeEnabled = s.autoUnfreezeEnabled
        SettingsCache.unfreezeShowToast = s.unfreezeShowToast
        SettingsCache.globalPrivilegeEnabled = s.globalPrivilegeEnabled
        com.moting.linkgo.util.privilege.PrivilegeEngine.globalEnabled = s.globalPrivilegeEnabled
        SettingsCache.capShizukuSilentGrant = s.capShizukuSilentGrant
        SettingsCache.capA11yHealBoot = s.capA11yHealBoot
        SettingsCache.capA11yHealApp = s.capA11yHealApp
        SettingsCache.capA11yPrewarmClipboard = s.capA11yPrewarmClipboard
        SettingsCache.capBatterySilentWhitelist = s.capBatterySilentWhitelist
        SettingsCache.clearClipboardAfterJump = s.clearClipboardAfterJump
        SettingsCache.clipboardChangeToastEnabled = s.clipboardChangeToastEnabled
        SettingsCache.clipboardBroadcastEnabled = s.clipboardBroadcastEnabled
        SettingsCache.clipboardAutoDismissSeconds = s.clipboardAutoDismissSeconds
        SettingsCache.maxMultiCapsuleCount = s.maxMultiCapsuleCount
        SettingsCache.capsuleDismissOnTouchOutside = s.capsuleDismissOnTouchOutside
        SettingsCache.lastSystemPrivilege = s.lastSystemPrivilege
        SettingsCache.lastSystemWay = s.lastSystemWay
        SettingsCache.privilegeMode = s.privilegeMode
        com.moting.linkgo.util.privilege.PrivilegeEngine.mode =
            com.moting.linkgo.util.privilege.PrivilegeEngine.PrivilegeMode.fromString(s.privilegeMode)
        SettingsCache.lastAppLinkSubMode = s.lastAppLinkSubMode
        SettingsCache.capsuleEdge = s.capsuleEdge
        SettingsCache.capsuleYRatio = s.capsuleYRatio
        SettingsCache.windowConfig = s.windowConfig

        // 背景模糊与超级岛诊断
        SettingsCache.backgroundBlurEnabled = s.backgroundBlurEnabled
        SettingsCache.superIslandLoggingEnabled = s.superIslandLoggingEnabled

        // 快捷手势（开关以平铺/整体任一为真为准）
        SettingsCache.edgeGestureConfig = s.edgeGesture.copy(
            enabled = s.edgeGestureEnabled || s.edgeGesture.enabled
        )

        // 应用内链接捕获（null = 该备份未包含，保留当前值）
        s.appLinkCaptureMode?.let { SettingsCache.appLinkCaptureMode = it.coerceIn(0, 2) }
        s.appLinkAskAutoFinish?.let { SettingsCache.appLinkAskAutoFinish = it }
        s.appLinkRuleOnlyIntercept?.let { SettingsCache.appLinkRuleOnlyIntercept = it }
        s.appLinkCaptureApps?.let { SettingsCache.appLinkCaptureApps = it }
        s.appLinkExemptDomains?.let { SettingsCache.appLinkExemptDomains = it }

        persistFastCache(context)

        // 应用内捕获配置变更后同步到 system_server 的 HookEntry（与设置页写入行为保持一致）
        if (s.appLinkCaptureMode != null || s.appLinkCaptureApps != null || s.appLinkExemptDomains != null
            || s.appLinkRuleOnlyIntercept != null) {
            com.moting.linkgo.applink.LinkIntentReceiver.syncConfigToHook(
                context,
                SettingsCache.appLinkCaptureMode
            )
        }
    }

    internal fun parseExtractionPatterns(json: String): List<com.moting.linkgo.model.ExtractPattern> {
        return try {
            val arr = org.json.JSONArray(json)
            val list = mutableListOf<com.moting.linkgo.model.ExtractPattern>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    com.moting.linkgo.model.ExtractPattern(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        name = obj.optString("name", "未命名"),
                        pattern = obj.optString("pattern", ""),
                        isEnabled = obj.optBoolean("isEnabled", true),
                        isBuiltin = obj.optBoolean("isBuiltin", false)
                    )
                )
            }
            list
        } catch (e: Exception) {
            com.moting.linkgo.model.ExtractPattern.builtinDefaults()
        }
    }

    internal fun serializeExtractionPatterns(patterns: List<com.moting.linkgo.model.ExtractPattern>): String {
        val arr = org.json.JSONArray()
        patterns.forEach { p ->
            val obj = org.json.JSONObject()
            obj.put("id", p.id)
            obj.put("name", p.name)
            obj.put("pattern", p.pattern)
            obj.put("isEnabled", p.isEnabled)
            obj.put("isBuiltin", p.isBuiltin)
            arr.put(obj)
        }
        return arr.toString()
    }
}
