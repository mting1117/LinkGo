package com.moting.linkgo.clipboard

/**
 * 剪贴板后台监听的 6 种后端模式定义与派生逻辑。
 *
 * 组织方式：
 *  - lsposed：系统层 hook 写入点，独立后端
 *  - shizuku/root × hidden_api/logs：系统监听 2×2 矩阵
 *  - none：不处理，仅公开监听
 */
object ClipboardBackend {
    const val LSPOSED = "lsposed"
    const val SHIZUKU_HIDDEN_API = "shizuku_hidden_api"
    const val ROOT_HIDDEN_API = "root_hidden_api"
    const val SHIZUKU_LOGS = "shizuku_logs"
    const val ROOT_LOGS = "root_logs"
    const val NONE = "none"

    /** 默认：Shizuku + 系统隐藏 API */
    const val DEFAULT = SHIZUKU_HIDDEN_API

    val ALL = listOf(LSPOSED, SHIZUKU_HIDDEN_API, ROOT_HIDDEN_API, SHIZUKU_LOGS, ROOT_LOGS, NONE)

    fun migrateFromLegacyMode(legacyMode: String?): String = when (legacyMode) {
        "lsposed" -> LSPOSED
        "root" -> ROOT_HIDDEN_API
        "shizuku" -> SHIZUKU_HIDDEN_API
        "none" -> NONE
        else -> DEFAULT
    }

    /** 是否走 root 提权 */
    fun isRoot(b: String) = b == ROOT_HIDDEN_API || b == ROOT_LOGS

    /** 是否使用系统隐藏 API（DEX）监听 */
    fun isHiddenApi(b: String) = b == SHIZUKU_HIDDEN_API || b == ROOT_HIDDEN_API

    /** 是否需要前台服务持活（只有 lsposed 不需要） */
    fun needsForegroundService(b: String) = b != LSPOSED

    /** 顶层分组：lsposed / system / none */
    fun topLevel(b: String): String = when (b) {
        LSPOSED -> "lsposed"
        NONE -> "none"
        else -> "system"
    }

    /** 系统监听矩阵里的提权方式 */
    fun privilegeOf(b: String): String = if (isRoot(b)) "root" else "shizuku"

    /** 系统监听矩阵里的监听方式 */
    fun wayOf(b: String): String = if (isHiddenApi(b)) "hidden_api" else "logs"

    /** 由矩阵的两个维度拼回完整后端 */
    fun build(privilege: String, way: String): String = when {
        privilege == "shizuku" && way == "hidden_api" -> SHIZUKU_HIDDEN_API
        privilege == "shizuku" && way == "logs" -> SHIZUKU_LOGS
        privilege == "root" && way == "hidden_api" -> ROOT_HIDDEN_API
        privilege == "root" && way == "logs" -> ROOT_LOGS
        else -> DEFAULT
    }

    /**
     * 根据全局当前提权通道（Channel）与选定的捕获方式（hidden_api / logs）
     * 动态派生系统监听的实际后端
     */
    fun deriveSystemBackend(channel: com.moting.linkgo.util.privilege.PrivilegeEngine.Channel, way: String): String {
        val priv = if (channel == com.moting.linkgo.util.privilege.PrivilegeEngine.Channel.ROOT) "root" else "shizuku"
        val safeWay = if (way == "logs") "logs" else "hidden_api"
        return build(priv, safeWay)
    }

    fun displayName(b: String): String = when (b) {
        LSPOSED -> "LSPosed"
        SHIZUKU_HIDDEN_API -> "隐藏 API (Shizuku)"
        ROOT_HIDDEN_API -> "隐藏 API (Root)"
        SHIZUKU_LOGS -> "系统日志 (Shizuku)"
        ROOT_LOGS -> "系统日志 (Root)"
        NONE -> "公开监听 (免提权)"
        else -> b
    }

    /** 说明文案，用于二级页面展示（偏向简要技术链路原理） */
    fun description(b: String): String = when (b) {
        LSPOSED -> "技术链路：在 system_server 进程 Hook ClipboardService 写入接口，直接截获剪贴板内容与来源应用 UID，无常驻进程。"
        SHIZUKU_HIDDEN_API -> "技术链路：通过 Shizuku Binder 跨进程反射注册 IOnPrimaryClipChangedListener 隐藏监听器，需前台服务保活。"
        ROOT_HIDDEN_API -> "技术链路：通过 app_process 派生 Root 守护进程反射注册隐藏监听器，建立跨进程管道回传，需前台服务。"
        SHIZUKU_LOGS -> "技术链路：通过 Shizuku 异步读取系统 logcat 管道并正则过滤剪贴板变动，作为免反射的通用兼容方案。"
        ROOT_LOGS -> "技术链路：通过 Root 管道流异步读取 logcat 过滤剪贴板变动，作为免反射的通用兼容方案。"
        NONE -> "技术链路：调用标准 SDK ClipboardManager 监听接口，依赖系统赋予的后台剪贴板读取特权。"
        else -> ""
    }

}