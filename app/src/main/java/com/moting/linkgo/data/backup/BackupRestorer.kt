package com.moting.linkgo.data.backup

import android.content.Context
import com.moting.linkgo.data.SettingsRepository
import com.moting.linkgo.model.DispatchRule
import java.io.File

/**
 * 备份恢复器：按 [RestoreGroup] 选择性回写 DataStore，并同步 SettingsCache 与 fast cache。
 */
class BackupRestorer(private val context: Context) {

    private val repo: SettingsRepository by lazy { SettingsRepository(context) }

    /**
     * 选择性恢复快照。
     */
    suspend fun restore(snapshot: BackupSnapshot, groups: Set<RestoreGroup>): RestoreResult {
        val restored = mutableListOf<RestoreGroup>()

        try {
            // 规则图标：先落盘，再回写规则（修正跨设备绝对路径）
            val iconMap = restoreRuleIcons(snapshot.data.ruleIcons)
            val rules = if (iconMap.isEmpty()) snapshot.data.rules else remapIconPaths(snapshot.data.rules, iconMap)
            val imageRules = if (iconMap.isEmpty()) snapshot.data.imageRules
            else remapImageIconPaths(snapshot.data.imageRules, iconMap)

            if (RestoreGroup.RULES in groups) {
                repo.updateRules(rules)
                restored.add(RestoreGroup.RULES)
            }
            if (RestoreGroup.IMAGE_RULES in groups) {
                repo.updateImageRules(imageRules)
                restored.add(RestoreGroup.IMAGE_RULES)
            }
            if (RestoreGroup.EXTRACTION_PATTERNS in groups) {
                val mergedPatterns = mergeExtractionPatterns(snapshot.data.extractionPatterns)
                repo.updateExtractionPatterns(mergedPatterns)
                restored.add(RestoreGroup.EXTRACTION_PATTERNS)
            }
            if (RestoreGroup.HISTORY in groups) {
                repo.replaceJumpHistory(snapshot.data.jumpHistory, snapshot.data.totalJumpCount)
                restored.add(RestoreGroup.HISTORY)
            }
            if (RestoreGroup.SETTINGS in groups) {
                repo.batchRestoreSettings(snapshot.data.settings)
                restored.add(RestoreGroup.SETTINGS)
            }

            // 统一刷新内存缓存 + 快速缓存
            repo.preloadAll()

            return RestoreResult(true, "已恢复：${restored.joinToString("、") { it.label }}", restored)
        } catch (e: Exception) {
            android.util.Log.e("BackupRestorer", "恢复失败", e)
            return RestoreResult(false, "恢复失败：${e.message ?: e.toString()}", restored)
        }
    }

    private fun mergeExtractionPatterns(backupPatterns: List<com.moting.linkgo.model.ExtractPattern>): List<com.moting.linkgo.model.ExtractPattern> {
        if (backupPatterns.isEmpty()) return com.moting.linkgo.model.ExtractPattern.builtinDefaults()
        return backupPatterns
    }

    /**
     * 将备份内的图标 base64 落盘到当前设备 rule_icons 目录，返回 原文件名 -> 新绝对路径。
     */
    private fun restoreRuleIcons(icons: Map<String, String>): Map<String, String> {
        if (icons.isEmpty()) return emptyMap()
        val iconsDir = File(context.filesDir, "rule_icons")
        if (!iconsDir.exists()) iconsDir.mkdirs()
        val map = mutableMapOf<String, String>()
        icons.forEach { (name, base64) ->
            try {
                val safeName = File(name).name
                val dest = File(iconsDir, safeName)
                dest.writeBytes(android.util.Base64.decode(base64, android.util.Base64.DEFAULT))
                map[safeName] = dest.absolutePath
            } catch (e: Exception) {
                android.util.Log.w("BackupRestorer", "图标回写失败: $name", e)
            }
        }
        return map
    }

    private fun remapIconPaths(rules: List<DispatchRule>, iconMap: Map<String, String>): List<DispatchRule> =
        rules.map { rule ->
            val path = rule.iconPath
            if (path.isNullOrBlank() || path.startsWith("app://")) {
                rule
            } else {
                val base = File(path).name
                val newPath = iconMap[base]
                if (newPath != null) rule.copy(iconPath = newPath) else rule
            }
        }

    /** 图片规则的图标路径重映射：与文本规则同一套逻辑，模型不同所以单独一份 */
    private fun remapImageIconPaths(
        rules: List<com.moting.linkgo.model.ImageRule>,
        iconMap: Map<String, String>
    ): List<com.moting.linkgo.model.ImageRule> =
        rules.map { rule ->
            val path = rule.iconPath
            if (path.isNullOrBlank() || path.startsWith("app://")) {
                rule
            } else {
                val base = File(path).name
                val newPath = iconMap[base]
                if (newPath != null) rule.copy(iconPath = newPath) else rule
            }
        }
}
