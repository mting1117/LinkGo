package com.moting.linkgo.util

import java.util.regex.Pattern

/**
 * URL 处理工具类
 */
object UrlUtils {

    /**
     * 增强型链接提取正则表达式
     * 支持标准协议 (://) 和编码后的协议 (%3A%2F%2F)
     */
    const val DEFAULT_REGEX = "(?:[a-zA-Z0-9+.-]+://|[a-zA-Z0-9+.-]+%3A%2F%2F)[^\\s\\u4e00-\\u9fa5]+(?<![.,!?])"

    /**
     * 默认的提取模板
     */
    const val DEFAULT_TEMPLATE = "$1"

    private val patternCache = android.util.LruCache<String, Pattern>(64)
    private const val MAX_EXTRACT_TEXT_LENGTH = 16384

    private fun getCompiledPattern(regex: String, flags: Int = Pattern.CASE_INSENSITIVE or Pattern.DOTALL): Pattern? {
        val key = "$flags:$regex"
        return patternCache.get(key) ?: try {
            val compiled = Pattern.compile(regex, flags)
            patternCache.put(key, compiled)
            compiled
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 忽略的本地系统/媒体 URI 协议（如相册图片、本地文件、内部资源等，非可跳转的 Web/DeepLink 链接）
     */
    private val IGNORED_SCHEMES = setOf("content", "file", "android.resource")

    fun isIgnoredScheme(url: String): Boolean {
        val trimmed = url.trim().lowercase()
        val scheme = when {
            trimmed.contains("://") -> trimmed.substringBefore("://")
            trimmed.contains("%3a%2f%2f") -> trimmed.substringBefore("%3a%2f%2f")
            else -> ""
        }
        return scheme in IGNORED_SCHEMES
    }

    /**
     * 判断字符串是否为纯净的链接 (无任何杂质)
     * 支持 http, https 以及各类私有协议 deep links (如 bilibili://)
     */
    fun isPureUrl(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || isIgnoredScheme(trimmed)) return false
        
        // 判定准则：首尾去空后，符合协议结构 (scheme://...) 且中间不含空格或汉字的单行文本
        // 鲁棒正则：以字母开头的协议 + :// + 任何非空白非中文序列
        val pureUrlRegex = "^[a-zA-Z0-9+.-]+://[^\\s\\u4e00-\\u9fa5]+$"
        return try {
            val p = getCompiledPattern(pureUrlRegex, 0)
            p?.matcher(trimmed)?.matches() ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 执行标准化提取：利用正则从原始文本中提取出核心链接
     * @param input 原始文本 (含干扰信息)
     * @param regex 用户定义的正则表达式 (带捕获组)
     * @param template 用户定义的重构模板 (如 $1)
     */
    fun performNormalization(input: String, regex: String, template: String): String {
        if (input.isBlank() || regex.isBlank()) return input
        val safeInput = if (input.length > MAX_EXTRACT_TEXT_LENGTH) input.take(MAX_EXTRACT_TEXT_LENGTH) else input
        
        return try {
            val pattern = getCompiledPattern(regex) ?: return input
            val matcher = pattern.matcher(safeInput)
            
            if (matcher.find()) {
                // 提取模式：如果存在捕获组，则根据模板进行替换或直接提取第一组
                if (matcher.groupCount() > 0) {
                    // 如果模板是简单的 $1，直接返回 group(1) 效率更高且更精准
                    if (template == "$1") {
                        matcher.group(1) ?: input
                    } else {
                        // 支持更复杂的重构模板
                        matcher.replaceAll(template)
                    }
                } else {
                    // 没有捕获组，直接返回整个匹配到的部分 (Group 0)
                    matcher.group() ?: input
                }
            } else {
                input
            }
        } catch (e: Exception) {
            input
        }
    }

    /**
     * 基础提取逻辑 (作为备用或内部校验)
     */
    fun extractFirstUrl(text: String): String {
        val safeText = if (text.length > MAX_EXTRACT_TEXT_LENGTH) text.take(MAX_EXTRACT_TEXT_LENGTH) else text
        val pattern = getCompiledPattern("[a-zA-Z0-9+.-]+://[^\\s\\u4e00-\\u9fa5]+", Pattern.CASE_INSENSITIVE) ?: return ""
        val matcher = pattern.matcher(safeText)
        while (matcher.find()) {
            val u = matcher.group() ?: ""
            if (u.isNotBlank() && !isIgnoredScheme(u)) return u
        }
        return ""
    }

    /**
     * 从文本中提取所有不重复的链接（单正则版本，向下兼容）
     * @param text 原始文本
     * @param regex 可选的自定义正则表达式
     */
    fun extractAllUrls(text: String, regex: String = DEFAULT_REGEX): List<String> {
        val pattern = com.moting.linkgo.model.ExtractPattern(
            name = "legacy",
            pattern = if (regex.isBlank()) DEFAULT_REGEX else regex
        )
        return extractAllUrls(text, listOf(pattern))
    }

    /**
     * 从文本中提取所有不重复的链接（多规则版本）
     * 使用贪心区间去重算法处理多规则匹配结果的包含/重叠/独立关系
     */
    fun extractAllUrls(text: String, patterns: List<com.moting.linkgo.model.ExtractPattern>): List<String> {
        return extractAllUrlsWithRules(text, patterns).map { it.url }
    }

    /**
     * 从文本中提取所有不重复的链接，并附带命中的提取规则名（多规则版本）
     * 使用贪心区间去重算法处理多规则匹配结果的包含/重叠/独立关系
     * @return 每个元素的 url 为提取结果，ruleName 为命中规则名；纯链接降级时 ruleName 为空字符串
     */
    fun extractAllUrlsWithRules(text: String, patterns: List<com.moting.linkgo.model.ExtractPattern>): List<ExtractionHit> {
        if (text.isBlank()) return emptyList()
        val safeText = if (text.length > MAX_EXTRACT_TEXT_LENGTH) text.take(MAX_EXTRACT_TEXT_LENGTH) else text

        val enabledPatterns = patterns.filter { it.isEnabled && it.pattern.isNotBlank() }
        if (enabledPatterns.isEmpty()) return emptyList()

        // 1. 收集所有规则的匹配结果
        data class RawMatch(val start: Int, val end: Int, val text: String, val ruleName: String)
        val allMatches = mutableListOf<RawMatch>()

        for (ep in enabledPatterns) {
            try {
                val pattern = getCompiledPattern(ep.pattern) ?: continue
                val matcher = pattern.matcher(safeText)
                while (matcher.find()) {
                    val url = if (matcher.groupCount() > 0) matcher.group(1) else matcher.group()
                    if (!url.isNullOrBlank() && !isIgnoredScheme(url)) {
                        allMatches.add(RawMatch(matcher.start(), matcher.end(), url, ep.name))
                    }
                }
            } catch (e: Exception) {
                // 单条规则编译/匹配失败，跳过
            }
        }

        if (allMatches.isEmpty()) {
            // 降级：如果整体本身就是纯链接，则作为单条处理（无命中规则名）
            if (isPureUrl(text)) return listOf(ExtractionHit(text.trim(), ""))
            return emptyList()
        }

        // 2. 贪心区间去重：按长度降序，有交集的丢弃短的
        val sorted = allMatches.sortedByDescending { it.end - it.start }
        val selected = mutableListOf<RawMatch>()
        for (m in sorted) {
            val hasOverlap = selected.any { s ->
                m.start < s.end && m.end > s.start
            }
            if (!hasOverlap) {
                selected.add(m)
            }
        }

        // 3. 按出现位置排序，去重返回
        return selected.sortedBy { it.start }.distinctBy { it.text }.map { ExtractionHit(it.text, it.ruleName) }
    }


    /**
     * 已废弃自动解码，直接返回原串。
     * 现在的解码逻辑完全由规则模板中的占位符（如 {url_url_dec}）控制。
     */
    fun safeDecode(url: String): String = url.trim()
}

/**
 * 提取结果：url 为提取到的链接，ruleName 为命中的提取规则名（纯链接降级时为空字符串）
 */
data class ExtractionHit(
    val url: String,
    val ruleName: String = ""
)
