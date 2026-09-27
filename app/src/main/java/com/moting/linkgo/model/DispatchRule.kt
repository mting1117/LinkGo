package com.moting.linkgo.model

import java.util.UUID
import com.google.gson.annotations.SerializedName

/**
 * 匹配模式
 */
enum class MatchType(val label: String) {
    @SerializedName("CONTAINS", alternate = ["a"])
    CONTAINS("包含"),
    @SerializedName("REGEX", alternate = ["b"])
    REGEX("正则"),
    @SerializedName("EXACT", alternate = ["c"])
    EXACT("精确")
}

/**
 * URL 分发规则
 */
data class DispatchRule(
    @SerializedName("id", alternate = ["a"])
    val id: String = UUID.randomUUID().toString(),
    
    @SerializedName("name", alternate = ["b"])
    val name: String = "",
    
    @SerializedName("pattern", alternate = ["c"])
    val pattern: String = "", 
    
    @SerializedName("targetPackage", alternate = ["d"])
    val targetPackage: String = "", 
    
    @SerializedName("template", alternate = ["e"])
    val template: String? = null, 
    
    @SerializedName("extractPattern", alternate = ["f"])
    val extractPattern: String? = null, 
    
    @SerializedName("ruleLaunchMode", alternate = ["g"])
    val ruleLaunchMode: Int = -1, 
    
    @SerializedName("matchType", alternate = ["h"])
    val matchType: MatchType = MatchType.REGEX, 
    
    @SerializedName("isEnabled", alternate = ["i"])
    val isEnabled: Boolean = true,
    
    @SerializedName("resolveShortLink", alternate = ["j"])
    val resolveShortLink: Boolean = false,
    
    @SerializedName("resolveStrategy", alternate = ["n"])
    val resolveStrategy: ResolutionStrategy = if (resolveShortLink) ResolutionStrategy.DIRECT else ResolutionStrategy.NONE,

    @SerializedName("excludeFromRecents", alternate = ["k"])
    val excludeFromRecents: Boolean = false,

    @SerializedName("isPreheatEnabled", alternate = ["l"])
    val isPreheatEnabled: Boolean = false,

    @SerializedName("preheatDelayMillis", alternate = ["m"])
    val preheatDelayMillis: Long = 0L,

    @SerializedName("iconPath", alternate = ["o"])
    val iconPath: String? = null,

    @SerializedName("jumpAndCopy", alternate = ["p"])
    val jumpAndCopy: Boolean = false
)

