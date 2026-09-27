package com.moting.linkgo.util.privilege

import android.util.Log
import com.moting.linkgo.data.SettingsCache

/**
 * 能力开关中心（Permission Center 的能力层）。
 *
 * 在"通道层"（Shizuku/Root/无障碍等系统权限的授予/收回）之上，
 * 针对"跨功能的辅助行为"提供独立细粒度开关：
 * - 关闭某个能力只停用该行为，不影响其他功能（例如关闭"Shizuku 静默授权自身权限"，
 *   剪贴板监听等仍可正常使用 Shizuku）；
 * - 运行时拦截点在执行对应行为前查询 [isEnabled]，能力关闭则静默降级（不打扰用户）；
 * - 默认全部开启，不改变现有行为。
 *
 * 存储：内存状态以 [SettingsCache] 为唯一事实来源（fast cache 同步），
 * 持久化由 SettingsRepository 的 DataStore 全链路负责。
 */
object CapabilityCenter {
    private const val TAG = "CapabilityCenter"

    /** 通道分组（UI 卡片分组标题） */
    enum class Group(val label: String) {
        SHIZUKU("Shizuku 能力"),
        ACCESSIBILITY("无障碍能力"),
        SYSTEM("系统能力")
    }

    /** 能力项定义 */
    enum class Capability(
        val id: String,
        val label: String,
        val description: String,
        val group: Group
    ) {
        SHIZUKU_SILENT_GRANT(
            id = "cap_shizuku_silent_grant",
            label = "后台自动授权应用权限",
            description = "通过 Root 或 Shizuku 自动为 LinkGo 授予悬浮窗、通知、安全设置等权限。关闭后需要手动前往系统设置开启。",
            group = Group.SYSTEM
        ),
        A11Y_HEAL_BOOT(
            id = "cap_a11y_heal_boot",
            label = "自愈 · 开机自启",
            description = "开机广播时自动点亮无障碍服务常驻。关闭后开机不再自动点亮，手动使用屏幕识别不受影响。",
            group = Group.ACCESSIBILITY
        ),
        A11Y_HEAL_APP(
            id = "cap_a11y_heal_app",
            label = "自愈 · 应用内自愈",
            description = "App 启动 / 切前台 / Shizuku 就绪时自动拉起无障碍服务。关闭后不再后台自动拉起。",
            group = Group.ACCESSIBILITY
        ),
        A11Y_PREWARM_CLIPBOARD(
            id = "cap_a11y_prewarm_clipboard",
            label = "连接时预热剪贴板监听",
            description = "无障碍服务连接时自动拉起剪贴板后台监听。关闭后需在剪贴板设置页手动开启监听。",
            group = Group.ACCESSIBILITY
        ),
        BATTERY_SILENT_WHITELIST(
            id = "cap_battery_silent_whitelist",
            label = "电池白名单静默写入",
            description = "通过提权通道静默将本应用加入 Doze 电池白名单。关闭后不再静默写入，可手动前往系统电池优化设置。",
            group = Group.SYSTEM
        )
    }

    /** 查询能力是否开启（默认开启） */
    fun isEnabled(cap: Capability): Boolean = when (cap) {
        Capability.SHIZUKU_SILENT_GRANT -> SettingsCache.capShizukuSilentGrant
        Capability.A11Y_HEAL_BOOT -> SettingsCache.capA11yHealBoot
        Capability.A11Y_HEAL_APP -> SettingsCache.capA11yHealApp
        Capability.A11Y_PREWARM_CLIPBOARD -> SettingsCache.capA11yPrewarmClipboard
        Capability.BATTERY_SILENT_WHITELIST -> SettingsCache.capBatterySilentWhitelist
    }

    /** 设置能力开关（内存态，持久化由 SettingsRepository 负责） */
    fun setEnabled(cap: Capability, enabled: Boolean) {
        when (cap) {
            Capability.SHIZUKU_SILENT_GRANT -> SettingsCache.capShizukuSilentGrant = enabled
            Capability.A11Y_HEAL_BOOT -> SettingsCache.capA11yHealBoot = enabled
            Capability.A11Y_HEAL_APP -> SettingsCache.capA11yHealApp = enabled
            Capability.A11Y_PREWARM_CLIPBOARD -> SettingsCache.capA11yPrewarmClipboard = enabled
            Capability.BATTERY_SILENT_WHITELIST -> SettingsCache.capBatterySilentWhitelist = enabled
        }
        Log.i(TAG, "能力开关: ${cap.label} -> $enabled")
    }

    /** 按分组查询（供 UI 卡片分组渲染） */
    fun byGroup(group: Group): List<Capability> = Capability.entries.filter { it.group == group }
}
