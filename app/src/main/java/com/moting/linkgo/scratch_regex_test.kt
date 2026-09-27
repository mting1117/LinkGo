import java.util.regex.Pattern

fun main() {
    val input = "【Gemini+NotebookLM+Google 幻灯片，五分钟搞定 PPT 制作 #PPT #Gemini #NotebookLM #Google #SEO-哔哩哔哩】 https://b23.tv/SlVJjGR"
    
    // 情况 A：单斜杠 (正确输入到 UI 的值)
    val regexUI = "(https?://[^\\s\\u4e00-\\u9fa5]+(?<![.,!?:;]))"
    
    // 情况 B：双斜杠 (如果用户从 Kotlin 代码直接复制到 UI)
    val regexWrong = "(https?://[^\\\\s\\\\u4e00-\\\\u9fa5]+(?<![.,!?:;]))"

    println("测试输入: $input")
    
    testRegex("正则 A (模拟 UI 正确输入)", regexUI, input)
    testRegex("正则 B (模拟 UI 错误输入)", regexWrong, input)
}

fun testRegex(tag: String, regex: String, input: String) {
    try {
        val pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
        val matcher = pattern.matcher(input)
        if (matcher.find()) {
            println("$tag 结果: ${matcher.group(1)}")
        } else {
            println("$tag 结果: 匹配失败 (返回原样)")
        }
    } catch (e: Exception) {
        println("$tag 结果: 语法错误 (${e.message})")
    }
}
