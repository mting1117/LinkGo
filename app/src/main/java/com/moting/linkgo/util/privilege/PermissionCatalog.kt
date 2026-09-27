package com.moting.linkgo.util.privilege

/**
 * 权限目录（Permission Center 的第二层）。
 *
 * 将「系统权限 + 提权通道 + 敏感后台能力」统一抽象为权限项，
 * 每一项附完整用途说明（[description]）与风险等级（[risk]），
 * 供权限中心 UI 透明化展示，满足谨慎用户对「每个权限到底服务什么」的知情需求。
 *
 * ⚠️ 描述必须完整：一个权限往往服务多个功能（例如无障碍服务同时承担
 * 屏幕识别、剪贴板监听预热、开机自启保活三职责），描述遗漏 = 用户知情缺失。
 */
enum class PermissionItem(
    val id: String,
    val label: String,
    val description: String,
    val risk: RiskLevel
) {
    // ── 提权通道 ──────────────────────────────────────────────
    SHIZUKU(
        id = "shizuku",
        label = "Shizuku 授权",
        description = "授予 LinkGo 系统级 IPC 能力。服务以下功能：\n" +
            "① 剪贴板后台监听（Binder 注册系统隐藏监听器 / 读取 logcat）；\n" +
            "② 跳转后静默清空剪贴板；\n" +
            "③ 静默授予自身悬浮窗、通知、安全设置等权限；\n" +
            "④ 超级岛断网旁路（绕过云端白名单校验）；\n" +
            "⑤ 目标应用解冻（pm enable / am unfreeze）；\n" +
            "⑥ 电池白名单与无障碍自愈提权。",
        risk = RiskLevel.HIGH
    ),
    ROOT(
        id = "root",
        label = "Root 授权",
        description = "在已 Root 设备上以最高权限执行命令。能力与 Shizuku 重叠，作为无 Shizuku 时的替代通道：\n" +
            "① 剪贴板后台监听（app_process 派生守护进程 / logcat）；\n" +
            "② 静默授予自身系统权限；\n" +
            "③ 目标应用解冻；\n" +
            "④ 电池白名单与无障碍自愈。\n" +
            "注意：Root 权限可读写系统关键区域，请仅在信任环境下授予。",
        risk = RiskLevel.HIGH
    ),

    // ── 运行时权限 ────────────────────────────────────────────
    OVERLAY(
        id = "overlay",
        label = "悬浮窗",
        description = "在所有应用上层绘制悬浮内容，服务：\n" +
            "① 复制链接后的悬浮胶囊（快速跳转入口）；\n" +
            "② 屏幕识别的链接高亮 Overlay 与数字选择条。",
        risk = RiskLevel.MEDIUM
    ),
    POST_NOTIFICATIONS(
        id = "notifications",
        label = "通知",
        description = "发送状态栏通知，服务：\n" +
            "① 链接跳转实时活动通知（含直接跳转 / 复制链接按钮）；\n" +
            "② 剪贴板捕获提示与超级岛胶囊通知；\n" +
            "③ 后台监听状态常驻通知。",
        risk = RiskLevel.LOW
    ),
    WRITE_SECURE_SETTINGS(
        id = "secure_settings",
        label = "写入安全系统设置",
        description = "系统级隐私权限，服务：\n" +
            "① 后台提取剪贴板（Android 10+ 限制下的合法读取通道）；\n" +
            "② 静默开启 / 重启无障碍服务（含开机自愈）；\n" +
            "③ 助手劫持跳转（绕过未导出 Activity 限制）。\n" +
            "该权限可修改系统安全设置，风险较高。",
        risk = RiskLevel.HIGH
    ),
    BATTERY_WHITELIST(
        id = "battery",
        label = "忽略电池优化",
        description = "将 LinkGo 加入系统电池白名单，避免后台前台服务被系统省电策略杀死，保障剪贴板监听与悬浮胶囊的稳定性。",
        risk = RiskLevel.LOW
    ),
    ACCESSIBILITY(
        id = "accessibility",
        label = "无障碍服务",
        description = "系统级辅助能力，服务三个功能：\n" +
            "① 屏幕识别：扫描当前屏幕中的链接并高亮、精确坐标点击（核心功能）；\n" +
            "② 剪贴板监听预热：无障碍服务连接时自动拉起剪贴板后台监听；\n" +
            "③ 开机自启与保活：开机广播与自愈引擎通过特权静默点亮本服务，作为系统常驻服务提升 LinkGo 后台存活率，防止冷启动延迟。",
        risk = RiskLevel.HIGH
    ),
    BACKGROUND_POPUP(
        id = "background_popup",
        label = "后台弹出界面",
        description = "允许 LinkGo 在后台或锁屏状态下直接弹出跳转解析与目标应用选择界面，避免因定制系统（MIUI/HyperOS等）限制导致链接跳转无响应或必须先切换回前台。",
        risk = RiskLevel.HIGH
    ),
    AUTO_START(
        id = "auto_start",
        label = "自启动管理",
        description = "保障系统开机或后台被系统清理后，能够自动拉起核心链路与剪贴板监听，避免冷启动延迟与监听丢失。国内定制系统（HyperOS/ColorOS/OriginOS等）需单独放行。",
        risk = RiskLevel.HIGH
    ),
    BOOT_COMPLETED(
        id = "boot",
        label = "开机广播声明",
        description = "AndroidManifest 清单中静态声明接收系统开机广播，为应用提供开机启动资格，协同自启动管理完成链路恢复。",
        risk = RiskLevel.LOW
    ),
    QUERY_ALL_PACKAGES(
        id = "query_all",
        label = "查询所有应用",
        description = "读取设备已安装应用列表（包名 / 图标 / 名称），用于规则目标选择器、浏览器选择器与目标预测。",
        risk = RiskLevel.LOW
    ),

    // ── 跨进程敏感能力 ────────────────────────────────────────
    LSPOSED_HOOK(
        id = "lsposed",
        label = "LSPosed 系统钩子",
        description = "以 LSPosed 模块方式注入 system_server 等系统进程，服务：\n" +
            "① 在系统级截获剪贴板变动（无需前台服务常驻）；\n" +
            "② 剪贴板捕获时解冻 LinkGo 自身进程（跨厂商冻结器兼容）；\n" +
            "③ 超级岛诊断日志事件采集。\n" +
            "仅在 LSPosed 框架中激活模块时生效，属于最敏感的跨进程能力。",
        risk = RiskLevel.HIGH
    ),
    CLIPBOARD_CLEAR(
        id = "clipboard_clear",
        label = "剪贴板静默清空",
        description = "跳转后自动清空剪贴板，防止目标应用二次触发跳转或泄露链接内容。\n" +
            "通过 Shizuku / Root 执行或同签名广播完成，涉及读取与删除用户剪贴板数据。",
        risk = RiskLevel.MEDIUM
    ),
    SUPER_ISLAND_BYPASS(
        id = "island_bypass",
        label = "超级岛断网旁路",
        description = "小米 HyperOS 超级岛通知的旁路能力：\n" +
            "通过 Shizuku 对 XMSF（小米消息服务）执行瞬时断网，防止超级岛通知被云端白名单校验拦截。\n" +
            "涉及系统网络防火墙级操作，风险较高。",
        risk = RiskLevel.HIGH
    );

    /** 风险等级：用于 UI 徽标与谨慎用户的知情提示 */
    enum class RiskLevel(val label: String) {
        LOW("低"),
        MEDIUM("中"),
        HIGH("高")
    }

    companion object {
        fun byId(id: String): PermissionItem? = entries.find { it.id == id }
    }
}
