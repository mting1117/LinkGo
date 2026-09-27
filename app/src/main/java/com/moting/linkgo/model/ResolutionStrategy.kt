package com.moting.linkgo.model

import com.google.gson.annotations.SerializedName

/**
 * 链接解析与分发策略
 */
enum class ResolutionStrategy(val label: String) {
    @SerializedName("NONE", alternate = ["0"])
    NONE("不解析"),
    
    @SerializedName("DIRECT", alternate = ["1"])
    DIRECT("解析并执行本规则"),
    
    @SerializedName("RE_DISPATCH", alternate = ["2"])
    RE_DISPATCH("解析并二次分发")
}
