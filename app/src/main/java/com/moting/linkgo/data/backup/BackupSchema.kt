package com.moting.linkgo.data.backup

import com.moting.linkgo.clipboard.ClipboardBackend
import com.moting.linkgo.data.WindowConfig
import com.moting.linkgo.model.CaptureApp
import com.moting.linkgo.model.DispatchRule
import com.moting.linkgo.model.EdgeGestureConfig
import com.moting.linkgo.model.ExemptDomain
import com.moting.linkgo.model.ExtractPattern
import com.moting.linkgo.model.JumpRecord

/**
 * 备份文件格式与数据模型。
 *
 * 统一快照：本地与云通道共用同一份 [BackupSnapshot] JSON，可互相通用。
 * 文件体积小，全部保留、不设上限、不自动删除。
 */
object BackupFormat {
    const val MAGIC = "LINKGO_BAK"
    const val FORMAT = 1
    /** 2：补齐快捷手势、应用内链接捕获、背景模糊、超级岛日志等此前遗漏的数据 */
    /** 3：补齐图片规则（含其自定义图标），此前备份完全不含这一类数据 */
    const val SCHEMA_VERSION = 3
    const val FILE_EXT = ".json"
    const val MIME = "application/json"

    /** 云端相对目录（相对 basePath） */
    const val CLOUD_DIR = "LinkGo"
    const val CLOUD_BACKUPS_DIR = "LinkGo/backups"

    /** 本地私有备份目录名 */
    const val LOCAL_DIR = "backups"

    /** 备份文件名：LinkGo_Backup_<版本>_<时间>_<4位随机尾缀>.json */
    fun buildFileName(versionName: String, timestamp: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
        val tail = (1000 + (Math.random() * 9000).toInt()).toString().takeLast(4)
        return "LinkGo_Backup_${versionName}_${sdf.format(java.util.Date(timestamp))}_$tail$FILE_EXT"
    }
}

/** 可选择性恢复的分组 */
enum class RestoreGroup(val label: String) {
    SETTINGS("设置"),
    RULES("分发规则"),
    IMAGE_RULES("图片规则"),
    EXTRACTION_PATTERNS("链接提取规则"),
    HISTORY("跳转历史")
}

/** 备份目标通道选择 */
enum class BackupTarget {
    LOCAL,
    WEBDAV,
    BOTH
}

/** 扁平化设置快照（Gson 可序列化） */
data class SettingsSnapshot(
    val floatingBottomBarEnabled: Boolean = true,
    val clipboardMonitorEnabled: Boolean = false,
    val clipboardMonitorBackend: String = ClipboardBackend.DEFAULT,
    val browserSelectorTimer: Int = 5,
    val showTriggerToast: Boolean = false,
    val dynamicColorEnabled: Boolean = true,
    val fallbackBrowser: String? = null,
    val fallbackWindowMode: Int = -1,
    val fallbackPreheatEnabled: Boolean = false,
    val fallbackPreheatDelay: Long = 500L,
    val excludeFromRecents: Boolean = false,
    val fallbackExcludeFromRecents: Boolean = false,
    val normalizationEnabled: Boolean = true,
    val normalizationRegex: String = com.moting.linkgo.util.UrlUtils.DEFAULT_REGEX,
    val normalizationTemplate: String = com.moting.linkgo.util.UrlUtils.DEFAULT_TEMPLATE,
    val showRichNotification: Boolean = false,
    val notificationStyle: String = "default",
    val notificationAutoDismissSeconds: Int = 5,
    val superIslandBypassEnabled: Boolean = true,
    val superIslandBypassDurationMs: Int = 100,
    val superIslandOuterGlow: Boolean = false,
    val superIslandDragShareEnabled: Boolean = true,
    val assistantBypassEnabled: Boolean = false,
    val autoUnfreezeEnabled: Boolean = true,
    val unfreezeShowToast: Boolean = true,
    val globalPrivilegeEnabled: Boolean = true,
    val capShizukuSilentGrant: Boolean = true,
    val capA11yHealBoot: Boolean = true,
    val capA11yHealApp: Boolean = true,
    val capA11yPrewarmClipboard: Boolean = true,
    val capBatterySilentWhitelist: Boolean = true,
    val clearClipboardAfterJump: Boolean = false,
    val clipboardChangeToastEnabled: Boolean = false,
    val clipboardBroadcastEnabled: Boolean = false,
    val clipboardAutoDismissSeconds: Int = 5,
    val maxMultiCapsuleCount: Int = 3,
    val capsuleDismissOnTouchOutside: Boolean = true,
    val lastSystemPrivilege: String = "shizuku",
    val lastSystemWay: String = "hidden_api",
    val privilegeMode: String = "auto",
    val lastAppLinkSubMode: Int = 1,
    val capsuleEdge: String = "right",
    val capsuleYRatio: Float = 2f / 3f,
    val browserHiddenList: List<String> = emptyList(),
    val browserOrderList: List<String> = emptyList(),
    val windowConfig: WindowConfig = WindowConfig(),

    // ---- 背景模糊与超级岛诊断 ----
    val backgroundBlurEnabled: Boolean = true,
    val superIslandLoggingEnabled: Boolean = false,

    // ---- 快捷手势（边缘侧的开关单独平铺，其余整体打包）----
    val edgeGestureEnabled: Boolean = false,
    val edgeGesture: EdgeGestureConfig = EdgeGestureConfig(),

    // ---- 应用内链接捕获（可空 = 该备份未包含此项，恢复时跳过以免误清空）----
    val appLinkCaptureMode: Int? = null,
    val appLinkAskAutoFinish: Boolean? = null,
    val appLinkRuleOnlyIntercept: Boolean? = null,
    val appLinkCaptureApps: List<CaptureApp>? = null,
    val appLinkExemptDomains: List<ExemptDomain>? = null
)

/** 备份正文（核心数据） */
data class BackupData(
    val settings: SettingsSnapshot = SettingsSnapshot(),
    val rules: List<DispatchRule> = emptyList(),
    /** 图片规则：与分发/跳转规则平级的一类数据，早期版本遗漏了它 */
    val imageRules: List<com.moting.linkgo.model.ImageRule> = emptyList(),
    val extractionPatterns: List<ExtractPattern> = emptyList(),
    val jumpHistory: List<JumpRecord> = emptyList(),
    val totalJumpCount: Int = 0,
    val ruleIcons: Map<String, String> = emptyMap()
)

/** 明文元数据（清单/预览用，不泄露正文） */
data class BackupMeta(
    val rulesCount: Int = 0,
    val imageRulesCount: Int = 0,
    val patternsCount: Int = 0,
    val historyCount: Int = 0,
    val deviceName: String = ""
)

/** 完整备份文件结构（单一 JSON） */
data class BackupSnapshot(
    val magic: String = BackupFormat.MAGIC,
    val format: Int = BackupFormat.FORMAT,
    val schemaVersion: Int = BackupFormat.SCHEMA_VERSION,
    val appVersion: String = "",
    val createdAt: Long = 0L,
    val checksum: String = "",
    val meta: BackupMeta = BackupMeta(),
    val data: BackupData = BackupData()
)

/** 单通道执行状态 */
enum class ChannelStatus { SUCCESS, SKIPPED, FAILED }

data class ChannelResult(
    val channel: String,
    val status: ChannelStatus,
    val message: String? = null
)

/** 一次融合备份的双通道结果 */
data class BackupResult(
    val fileName: String,
    val local: ChannelResult,
    val cloud: ChannelResult
) {
    val cloudConfigured: Boolean
        get() = cloud.status != ChannelStatus.SKIPPED
}

/** 备份清单条目（本地/云通用） */
data class BackupInfo(
    val fileName: String,
    val createdAt: Long,
    val sizeBytes: Long,
    val appVersion: String = "",
    val meta: BackupMeta? = null,
    val source: String = "local"
)

/** 恢复结果 */
data class RestoreResult(
    val success: Boolean,
    val message: String,
    val restoredGroups: List<RestoreGroup> = emptyList()
)
