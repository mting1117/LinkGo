package com.moting.linkgo.data.backup

import android.content.Context
import android.os.Build
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.moting.linkgo.data.SettingsRepository
import kotlinx.coroutines.flow.first
import java.io.File
import java.security.MessageDigest

/**
 * 备份序列化器：聚合 LinkGo 全部数据（设置/规则/提取规则/历史/规则图标）为一个 [BackupSnapshot]。
 */
class BackupSerializer(private val context: Context) {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    private val repo: SettingsRepository by lazy { SettingsRepository(context) }

    /**
     * 采集全部数据并生成带校验和的完整快照。
     */
    suspend fun createSnapshot(appVersion: String): BackupSnapshot {
        val capMap = repo.capabilities.first()
        val capsuleAnchor = repo.capsuleAnchor.first()
        val edgeGestureConfig = repo.edgeGestureConfigFlow.first()
        val settings = SettingsSnapshot(
            floatingBottomBarEnabled = repo.floatingBottomBarEnabledFlow.first(),
            clipboardMonitorEnabled = repo.clipboardMonitorEnabled.first(),
            clipboardMonitorBackend = repo.clipboardMonitorBackend.first(),
            browserSelectorTimer = repo.browserSelectorTimer.first(),
            showTriggerToast = repo.showTriggerToast.first(),
            dynamicColorEnabled = repo.dynamicColorEnabled.first(),
            fallbackBrowser = repo.fallbackBrowser.first(),
            fallbackWindowMode = repo.fallbackWindowMode.first(),
            fallbackPreheatEnabled = repo.fallbackPreheatEnabled.first(),
            fallbackPreheatDelay = repo.fallbackPreheatDelay.first(),
            excludeFromRecents = repo.excludeFromRecents.first(),
            fallbackExcludeFromRecents = repo.fallbackExcludeFromRecents.first(),
            normalizationEnabled = repo.normalizationEnabled.first(),
            normalizationRegex = repo.normalizationRegex.first(),
            normalizationTemplate = repo.normalizationTemplate.first(),
            showRichNotification = repo.showRichNotification.first(),
            notificationStyle = repo.notificationStyle.first().value,
            notificationAutoDismissSeconds = repo.notificationAutoDismissSeconds.first(),
            superIslandBypassEnabled = repo.superIslandBypassEnabled.first(),
            superIslandBypassDurationMs = repo.superIslandBypassDurationMs.first(),
            superIslandOuterGlow = repo.superIslandOuterGlow.first(),
            superIslandDragShareEnabled = repo.superIslandDragShareEnabled.first(),
            assistantBypassEnabled = repo.assistantBypassEnabled.first(),
            autoUnfreezeEnabled = repo.autoUnfreezeEnabled.first(),
            unfreezeShowToast = repo.unfreezeShowToast.first(),
            globalPrivilegeEnabled = repo.globalPrivilegeEnabled.first(),
            capShizukuSilentGrant = capMap[com.moting.linkgo.util.privilege.CapabilityCenter.Capability.SHIZUKU_SILENT_GRANT] ?: true,
            capA11yHealBoot = capMap[com.moting.linkgo.util.privilege.CapabilityCenter.Capability.A11Y_HEAL_BOOT] ?: true,
            capA11yHealApp = capMap[com.moting.linkgo.util.privilege.CapabilityCenter.Capability.A11Y_HEAL_APP] ?: true,
            capA11yPrewarmClipboard = capMap[com.moting.linkgo.util.privilege.CapabilityCenter.Capability.A11Y_PREWARM_CLIPBOARD] ?: true,
            capBatterySilentWhitelist = capMap[com.moting.linkgo.util.privilege.CapabilityCenter.Capability.BATTERY_SILENT_WHITELIST] ?: true,
            clearClipboardAfterJump = repo.clearClipboardAfterJump.first(),
            clipboardChangeToastEnabled = repo.clipboardChangeToastEnabled.first(),
            clipboardBroadcastEnabled = repo.clipboardBroadcastEnabled.first(),
            clipboardAutoDismissSeconds = repo.clipboardAutoDismissSeconds.first(),
            maxMultiCapsuleCount = repo.maxMultiCapsuleCount.first(),
            capsuleDismissOnTouchOutside = repo.capsuleDismissOnTouchOutside.first(),
            lastSystemPrivilege = repo.lastSystemPrivilege.first(),
            lastSystemWay = repo.lastSystemWay.first(),
            privilegeMode = repo.privilegeMode.first(),
            lastAppLinkSubMode = repo.lastAppLinkSubMode.first(),
            capsuleEdge = capsuleAnchor?.edge ?: "right",
            capsuleYRatio = capsuleAnchor?.yRatio ?: (2f / 3f),
            browserHiddenList = repo.browserHiddenList.first(),
            browserOrderList = repo.browserOrderList.first(),
            windowConfig = repo.windowConfig.first(),

            // 背景模糊与超级岛诊断
            backgroundBlurEnabled = repo.backgroundBlurEnabled.first(),
            superIslandLoggingEnabled = repo.superIslandLoggingEnabled.first(),

            // 快捷手势
            edgeGestureEnabled = edgeGestureConfig.enabled,
            edgeGesture = edgeGestureConfig,

            // 应用内链接捕获
            appLinkCaptureMode = repo.appLinkCaptureMode.first(),
            appLinkAskAutoFinish = repo.appLinkAskAutoFinish.first(),
            appLinkRuleOnlyIntercept = repo.appLinkRuleOnlyIntercept.first(),
            appLinkCaptureApps = repo.appLinkCaptureApps.first(),
            appLinkExemptDomains = repo.appLinkExemptDomains.first()
        )

        val rules = repo.dispatchRules.first()
        val imageRules = repo.imageRules.first()
        val patterns = repo.extractionPatterns.first()
        val history = repo.jumpHistory.first()
        val totalCount = repo.totalJumpCount.first()
        // 两类规则引用的是同一个 rule_icons 目录，图标收集必须一起做：
        // 只收文本规则的图标会让图片规则的自定义图标在恢复后变成空白
        val icons = collectRuleIcons(
            rules.mapNotNull { it.iconPath } + imageRules.mapNotNull { it.iconPath }
        )

        val data = BackupData(
            settings = settings,
            rules = rules,
            imageRules = imageRules,
            extractionPatterns = patterns,
            jumpHistory = history,
            totalJumpCount = totalCount,
            ruleIcons = icons
        )

        val checksum = sha256(gson.toJson(data))

        return BackupSnapshot(
            appVersion = appVersion,
            createdAt = System.currentTimeMillis(),
            checksum = checksum,
            meta = BackupMeta(
                rulesCount = rules.size,
                imageRulesCount = imageRules.size,
                patternsCount = patterns.size,
                historyCount = history.size,
                deviceName = deviceName()
            ),
            data = data
        )
    }

    /**
     * 序列化快照为 JSON 字符串。
     */
    fun toJson(snapshot: BackupSnapshot): String = gson.toJson(snapshot)

    /**
     * 解析 JSON 为快照；格式非法时返回 null。
     */
    fun fromJson(json: String): BackupSnapshot? = try {
        val cleanJson = json.removePrefix("\uFEFF").trim()
        val s = gson.fromJson(cleanJson, BackupSnapshot::class.java)
        if (s.magic != BackupFormat.MAGIC) null else s
    } catch (e: Exception) {
        null
    }

    /**
     * 校验快照正文完整性（SHA-256 比对，容错设计）。
     */
    fun verifyChecksum(snapshot: BackupSnapshot): Boolean {
        if (snapshot.checksum.isBlank()) return true
        val expected = sha256(gson.toJson(snapshot.data))
        return expected == snapshot.checksum
    }

    /**
     * 采集规则引用的本地图标（rule_icons 目录）为 base64，实现跨设备自包含。
     *
     * 入参是两类规则的全部 iconPath 并集——分发/跳转规则与图片规则共用一个图标目录。
     */
    private fun collectRuleIcons(iconPaths: List<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val referenced = iconPaths
            .filter { path -> !path.startsWith("app://") }
            .mapNotNull { path -> File(path).takeIf { it.exists() } }

        referenced.forEach { file ->
            try {
                result[file.name] = android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
            } catch (e: Exception) {
                android.util.Log.w("BackupSerializer", "图标读取失败: ${file.name}", e)
            }
        }
        // 兜底：只备份被规则引用的文件，避免把整个目录打包进来导致体积膨胀
        return result
    }

    private fun deviceName(): String = try {
        "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    } catch (e: Exception) {
        ""
    }

    companion object {
        fun sha256(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray(Charsets.UTF_8))
            return "sha256:" + digest.joinToString("") { "%02x".format(it) }
        }
    }
}
