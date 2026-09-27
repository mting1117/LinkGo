package com.moting.linkgo.applink

/**
 * system_server 钩子（HookEntry）与主进程接收器（LinkIntentReceiver）之间的契约常量。
 */
object AppLinkHookContract {
    @JvmField val APPLICATION_ID = "com.moting.linkgo"
    @JvmField val RECEIVER_CLASS = "com.moting.linkgo.applink.LinkIntentReceiver"

    /** 默认接管应用包名 */
    @JvmField val PKG_WECHAT = "com.tencent.mm"
    @JvmField val PKG_QQ = "com.tencent.mobileqq"

    /** system_server 捕获到应用内打开的链接后，广播给主进程 */
    @JvmField val ACTION_HANDLE_APP_LINK = "com.moting.linkgo.action.HANDLE_APP_LINK"
    @JvmField val EXTRA_LINK_URL = "com.moting.linkgo.extra.LINK_URL"
    @JvmField val EXTRA_LINK_SOURCE = "com.moting.linkgo.extra.LINK_SOURCE"

    /** 本次捕获的唯一标识：Hook 端据此匹配回执、决定是否执行超时回退 */
    @JvmField val EXTRA_LINK_TOKEN = "com.moting.linkgo.extra.LINK_TOKEN"

    /**
     * 主进程 → system_server：回报「本次链接已接管」。
     *
     * 收到即取消 Hook 端的超时回退兜底：主进程既已接管，后续失败由主进程走
     * [ACTION_OPEN_IN_BUILTIN] 主动补偿，Hook 端不能再自行放行，否则会同时打开内置浏览器与目标应用。
     */
    @JvmField val ACTION_LINK_HANDLED = "com.moting.linkgo.action.LINK_HANDLED"

    /** 拦截模式：主进程 → system_server，把当前链接改用内置浏览器打开（单次放行 + Intent 重放） */
    @JvmField val ACTION_OPEN_IN_BUILTIN = "com.moting.linkgo.action.OPEN_IN_BUILTIN"
    @JvmField val EXTRA_LINK_URL_REF = "com.moting.linkgo.extra.LINK_URL_REF"

    /**
     * 「分发放行窗口」协议已废弃（原 ACTION_PASS_LINK / EXTRA_PASS_SECONDS）。
     *
     * 该窗口按 URL 字面量在 5 秒内一律放行，会把用户短时间内重复打开的同一条链接误放行
     * （微信直接内置打开、不再被捕获）。分发出去后可能出现的循环改由
     * 「跳转规则目标应用 == 本次发起应用 → 放行」判据处理，不再依赖时间窗口。
     */

    /** 主进程 → system_server，同步捕获模式与暂停时长配置 */
    @JvmField val ACTION_SYNC_CAPTURE_CONFIG = "com.moting.linkgo.action.SYNC_CAPTURE_CONFIG"
    @JvmField val EXTRA_CAPTURE_MODE = "com.moting.linkgo.extra.CAPTURE_MODE"

    /**
     * system_server → 主进程：请求重新下发捕获配置。
     *
     * 模块热重载后 HookEntry 是全新实例、内存中的规则表为空，需要主进程重发一次 SYNC
     * 才能恢复「规则目标应用 == 发起应用 → 放行」的断环判定。
     * 该广播由主进程的 LinkIntentReceiver 接收（见 onReceive 的 ACTION_REQUEST_SYNC 分支）。
     */
    @JvmField val ACTION_REQUEST_SYNC = "com.moting.linkgo.action.REQUEST_SYNC"

    /**
     * system_server → 主进程：回报本次同步的实际生效状态（规则条数等）。
     *
     * 用于设置页展示「规则列表是否已同步到 Hook 端」。走显式定向广播而非 libxposed 远程偏好，
     * 因为 `getRemotePreferences` 在 system_server 作用域下实测写入不成功，主进程读不到。
     */
    @JvmField val ACTION_LINK_SYNC_STATUS = "com.moting.linkgo.action.LINK_SYNC_STATUS"
    @JvmField val EXTRA_RULES_COUNT = "com.moting.linkgo.extra.RULES_COUNT"

    /** 豁免域名规则（JSON 数组，随 SYNC 一起下发） */
    @JvmField val EXTRA_EXEMPT_DOMAINS = "com.moting.linkgo.extra.EXEMPT_DOMAINS"

    /** 接管应用列表（JSON 数组，随 SYNC 一起下发） */
    @JvmField val EXTRA_CAPTURE_APPS = "com.moting.linkgo.extra.CAPTURE_APPS"

    /**
     * 「仅命中规则时拦截」开关（随 SYNC 一起下发，仅直接拦截模式生效）。
     *
     * 开启后 Hook 端先比对跳转规则表：命中规则才拦截；未命中（本该走备选浏览器）一律放行，
     * 交回应用自己的内置浏览器打开。
     */
    @JvmField val EXTRA_RULE_ONLY_INTERCEPT = "com.moting.linkgo.extra.RULE_ONLY_INTERCEPT"

    /**
     * 跳转规则表（JSON 数组，随 SYNC 一起下发）。
     *
     * 每项含 pattern / matchType / targetPackage，供 HookEntry 判定
     * 「链接的规则目标应用 == 本次发起应用」→ 放行，断开「分发出去又被自己捕获」的死循环。
     */
    @JvmField val EXTRA_DISPATCH_RULES = "com.moting.linkgo.extra.DISPATCH_RULES"

    /** 询问模式：主进程 → system_server，跳转外部后请求销毁原内置浏览器页面 */
    @JvmField val ACTION_FINISH_LINK_ACTIVITY = "com.moting.linkgo.action.FINISH_LINK_ACTIVITY"
    @JvmField val EXTRA_TARGET_URL = "com.moting.linkgo.extra.TARGET_URL"
    @JvmField val EXTRA_TARGET_PACKAGE = "com.moting.linkgo.extra.TARGET_PACKAGE"
}
