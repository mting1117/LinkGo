package com.moting.linkgo.model

/**
 * 手势动作枚举
 */
enum class GestureAction(val value: String, val title: String, val description: String) {
    RADAR_DIRECT("radar_direct", "滑动直达", "滑动经过链接直接高亮，松手极速直达打开"),
    SCREEN_RECOGNITION("screen_recognition", "屏幕识别", "静默捕获屏幕图像并智能提取页面内所有网页链接"),
    CLIPBOARD_ANALYSIS("clipboard_analysis", "剪贴板分析", "直接提取并深度解析当前系统剪贴板中的最新内容"),
    NONE("none", "无操作", "不执行任何动作");

    companion object {
        fun fromValue(value: String?): GestureAction {
            return entries.firstOrNull { it.value == value } ?: RADAR_DIRECT
        }
    }
}

/**
 * 跟随手指探照圆球样式
 */
enum class BallStyle(val value: String, val title: String) {
    STROKE_GLOW("stroke_glow", "空心光晕圆环"),
    FILLED_TRANSLUCENT("filled_translucent", "半透明柔光圆球");

    companion object {
        fun fromValue(value: String?): BallStyle {
            return entries.firstOrNull { it.value == value } ?: STROKE_GLOW
        }
    }
}

/**
 * 探照圆球操控模式
 */
enum class PointerControlMode(val value: String, val title: String, val description: String) {
    REMOTE_POINTER("remote_pointer", "单手指针模式", "大屏单手利器：手指在舒适区滑动，指针通过动态杠杆全域触达全屏"),
    DIRECT_TOUCH("direct_touch", "触控跟随模式", "传统直觉触控：指针圆球跟随手指位置，支持设置防遮挡偏置");

    companion object {
        fun fromValue(value: String?): PointerControlMode {
            return entries.firstOrNull { it.value == value } ?: REMOTE_POINTER
        }
    }
}

/**
 * 边缘侧边手势与雷达探照直达配置模型
 */
data class EdgeGestureConfig(
    // --- 边缘触发热区布局配置 ---
    val enabled: Boolean = false,
    val widthDp: Int = 18,               // 宽度：默认 18 dp (横向感应厚度，横竖屏物理大小一致)
    val heightDp: Int = 140,             // 高度：默认 140 dp (垂直覆盖高度，横竖屏物理大小一致)
    val verticalRatio: Float = 0.350f,   // 垂直位置：默认 350‰ (35% 屏幕高，偏上避开系统返回)
    val horizontalRatio: Float = 0.000f, // 水平位置：默认 0‰ (0% 屏幕宽，贴紧边缘)
    val edgeAlpha: Float = 0.60f,        // 热区透明度：默认 60%
    val mirrorEnabled: Boolean = true,   // 是否开启左右双侧对称镜像
    val clickAction: GestureAction = GestureAction.SCREEN_RECOGNITION,
    val swipeAction: GestureAction = GestureAction.RADAR_DIRECT,
    val longPressAction: GestureAction = GestureAction.NONE,

    // --- 跟随手指圆球 (Finder Ball) 配置 ---
    val ballRadiusDp: Int = 28,
    val ballStrokeWidthDp: Float = 2.5f,
    val ballAlpha: Float = 0.65f,
    val ballStyle: BallStyle = BallStyle.STROKE_GLOW,
    val controlMode: PointerControlMode = PointerControlMode.REMOTE_POINTER, // 操控模式：单手指针 vs 触控跟随
    val pointerSensitivity: Float = 2.2f, // 指针灵敏度/杠杆放大倍率 (1.2x ~ 3.2x)
    val pointerInitialDistanceDp: Int = 96, // 单手指针模式下的初始间距 (40dp ~ 150dp)
    val fingerOffsetYDp: Int = 36, // 触控跟随模式下的垂直防遮挡偏移 (支持 0dp 完全贴手)
    val gestureHapticEnabled: Boolean = true, // 触发侧边手势时的触感反馈
    val collisionHapticEnabled: Boolean = true, // 探照碰撞与命中链接时的触感反馈
    val bubbleSquishEnabled: Boolean = true, // 水泡随速弹性形变与阻尼回弹
    val stayDurationMs: Int = 800, // 指针停留时长 (400ms ~ 3000ms，步长 50ms)
    val directSingleImageRule: Boolean = true, // 单图片规则时直接跳转（不弹图片选择页）
    /**
     * 屏幕二维码识别。
     *
     * 扫码与图片分发共用「截屏」这一个动作（见 `ScreenCapturer.captureRegion`）：截完先试解码二维码，
     * 解出就走文本链路，没解出才交给图片规则。因此它必须独立成开关——
     * 关闭且未配置启用中的图片规则时，取图没有任何去向，屏幕识别与滑动直达都不再保留取图入口
     * （判据见 `SettingsCache.screenCaptureEnabled`）。
     */
    val screenQrEnabled: Boolean = false,

    // --- 生效范围配置 (横竖屏与黑白应用名单) ---
    val orientationScope: OrientationScope = OrientationScope.ALL,
    val appScopeMode: AppScopeMode = AppScopeMode.BLACKLIST,
    val appScopePackages: Set<String> = emptySet()
) {
    @Deprecated("已替换为固定 dp 模式以保证横竖屏尺寸一致", ReplaceWith("widthDp"))
    val widthRatio: Float get() = widthDp / 392f

    @Deprecated("已替换为固定 dp 模式以保证横竖屏尺寸一致", ReplaceWith("heightDp"))
    val heightRatio: Float get() = heightDp / 850f
}

/**
 * 手势生效屏幕方向枚举
 */
enum class OrientationScope(val value: String, val title: String) {
    ALL("all", "横竖屏均生效"),
    PORTRAIT_ONLY("portrait_only", "仅竖屏生效"),
    LANDSCAPE_ONLY("landscape_only", "仅横屏生效");

    companion object {
        fun fromValue(value: String?): OrientationScope {
            return entries.firstOrNull { it.value == value } ?: ALL
        }
    }
}

/**
 * 手势应用生效名单模式枚举
 */
enum class AppScopeMode(val value: String, val title: String, val description: String) {
    BLACKLIST("blacklist", "黑名单模式", "所选应用内禁用手势，其余应用正常生效"),
    WHITELIST("whitelist", "白名单模式", "仅在所选应用内生效，其余应用禁用");

    companion object {
        fun fromValue(value: String?): AppScopeMode {
            return entries.firstOrNull { it.value == value } ?: BLACKLIST
        }
    }
}


