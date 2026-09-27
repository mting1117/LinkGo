package com.moting.linkgo.applink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.moting.linkgo.BrowserSelectorActivity
import com.moting.linkgo.clipboard.ClipboardHandler
import com.moting.linkgo.data.SettingsCache
import com.moting.linkgo.data.SettingsRepository
import com.moting.linkgo.overlay.ClipboardCapsuleOverlay
import com.moting.linkgo.util.WindowRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 应用内链接捕获的广播接收器。
 *
 * 由 system_server 中的 HookEntry 在捕获到"应用内打开的 http/https 链接"后广播而来。
 * 按当前捕获模式处理：
 * - 拦截（1）：内置浏览器已被 system_server 拦下，这里执行静默分发（分享到 LinkGo 同链路），
 *   并弹「内置打开」提示胶囊（点击后用内置浏览器打开该链接）；
 * - 询问（2）：内置浏览器照常打开，这里复用剪贴板式胶囊（点击后用 LinkGo 跳转）。
 */
class LinkIntentReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "LinkGo_AppLink"

        /**
         * 向 system_server 的 HookEntry 同步捕获配置（应用启动与设置变更时调用），
         * 同时携带豁免域名规则 JSON。
         */
        fun syncConfigToHook(context: Context, mode: Int) {
            val exemptJson = runCatching {
                SettingsRepository.serializeExemptDomains(SettingsCache.appLinkExemptDomains)
            }.getOrDefault("[]")
            val captureAppsJson = runCatching {
                SettingsRepository.serializeEnabledCapturePackageNames(SettingsCache.appLinkCaptureApps)
            }.getOrDefault("[]")
            // 跳转规则表：HookEntry 据此判定「链接目标应用 == 发起应用 → 放行」断开分发死循环，
            // 必须在配置同步时一并下发，否则规则变更后 Hook 端判定失效会重新陷入循环。
            val dispatchRulesJson = SettingsCache.dispatchRules.ifBlank { "[]" }
            val ruleOnlyIntercept = SettingsCache.appLinkRuleOnlyIntercept
            Log.i(TAG, "[SYNC-SEND] 同步捕获配置: mode=$mode, 接管应用=$captureAppsJson, 放行规则=$exemptJson, 跳转规则条数=" +
                runCatching { org.json.JSONArray(dispatchRulesJson).length() }.getOrDefault(0) +
                ", 仅命中规则时拦截=$ruleOnlyIntercept")
            // 显式定向发送给 system_server ("android")，避免后台隐式广播限制
            runCatching {
                context.sendBroadcast(
                    Intent(AppLinkHookContract.ACTION_SYNC_CAPTURE_CONFIG)
                        .putExtra(AppLinkHookContract.EXTRA_CAPTURE_MODE, mode)
                        .putExtra(AppLinkHookContract.EXTRA_EXEMPT_DOMAINS, exemptJson)
                        .putExtra(AppLinkHookContract.EXTRA_CAPTURE_APPS, captureAppsJson)
                        .putExtra(AppLinkHookContract.EXTRA_DISPATCH_RULES, dispatchRulesJson)
                        .putExtra(AppLinkHookContract.EXTRA_RULE_ONLY_INTERCEPT, ruleOnlyIntercept)
                        .setPackage("android")
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                )
            }.onFailure { Log.w(TAG, "定向同步广播发送失败: ${it.message}") }

            // 广播兜底发送
            runCatching {
                context.sendBroadcast(
                    Intent(AppLinkHookContract.ACTION_SYNC_CAPTURE_CONFIG)
                        .putExtra(AppLinkHookContract.EXTRA_CAPTURE_MODE, mode)
                        .putExtra(AppLinkHookContract.EXTRA_EXEMPT_DOMAINS, exemptJson)
                        .putExtra(AppLinkHookContract.EXTRA_CAPTURE_APPS, captureAppsJson)
                        .putExtra(AppLinkHookContract.EXTRA_DISPATCH_RULES, dispatchRulesJson)
                        .putExtra(AppLinkHookContract.EXTRA_RULE_ONLY_INTERCEPT, ruleOnlyIntercept)
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                )
            }.onFailure { Log.w(TAG, "兜底同步广播发送失败: ${it.message}") }
        }

        /**
         * 向 system_server 的 HookEntry 发出「内置打开」指令（用缓存的完整 Intent 重新启动内置浏览器）。
         */
        fun openInBuiltin(context: Context, url: String) {
            runCatching {
                context.sendBroadcast(
                    Intent(AppLinkHookContract.ACTION_OPEN_IN_BUILTIN)
                        .putExtra(AppLinkHookContract.EXTRA_LINK_URL_REF, url)
                )
            }.onFailure { Log.w(TAG, "内置打开广播发送失败: ${it.message}") }
        }

        /**
         * 向 system_server 的 HookEntry 回报「本次链接已接管」。
         *
         * Hook 端收到即取消超时回退兜底：既然主进程已接管，后续失败由主进程经
         * [openInBuiltin] 主动补偿，Hook 端不能再自行放行，否则会同时打开内置浏览器与目标应用。
         */
        fun notifyHandled(context: Context, token: String) {
            runCatching {
                context.sendBroadcast(
                    Intent(AppLinkHookContract.ACTION_LINK_HANDLED)
                        .putExtra(AppLinkHookContract.EXTRA_LINK_TOKEN, token)
                )
            }.onFailure { Log.w(TAG, "接管回执广播发送失败: ${it.message}") }
        }

        /** 最近一次询问模式的来源应用包名 */
        @Volatile
        var lastAskSourcePkg: String? = null

        /**
         * 向 system_server 的 HookEntry 发出「销毁原内置浏览器页面」指令。
         */
        fun finishTargetActivity(context: Context, url: String, sourcePkg: String? = null) {
            val targetPkg = sourcePkg ?: lastAskSourcePkg
            Log.i(TAG, "[FINISH-REQ] 发出「销毁原页面」广播: url=${url.take(60)}, targetPkg=$targetPkg")
            
            // 显式定向发送给 system_server ("android")，避免后台隐式广播限制
            runCatching {
                val explicitIntent = Intent(AppLinkHookContract.ACTION_FINISH_LINK_ACTIVITY)
                    .putExtra(AppLinkHookContract.EXTRA_TARGET_URL, url)
                    .putExtra(AppLinkHookContract.EXTRA_TARGET_PACKAGE, targetPkg)
                    .setPackage("android")
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                context.sendBroadcast(explicitIntent)
            }.onFailure { Log.w(TAG, "显式定向销毁广播发送失败: ${it.message}") }

            // 广播兜底发送
            runCatching {
                val fallbackIntent = Intent(AppLinkHookContract.ACTION_FINISH_LINK_ACTIVITY)
                    .putExtra(AppLinkHookContract.EXTRA_TARGET_URL, url)
                    .putExtra(AppLinkHookContract.EXTRA_TARGET_PACKAGE, targetPkg)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                context.sendBroadcast(fallbackIntent)
            }.onFailure { Log.w(TAG, "兜底销毁广播发送失败: ${it.message}") }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        // 模块热重载后 HookEntry 规则表为空，主动请求一次配置重发以恢复断环判定
        if (intent.action == AppLinkHookContract.ACTION_REQUEST_SYNC) {
            Log.i(TAG, "[SYNC-REQ] 收到 Hook 端配置重发请求（模块热重载后）")
            syncConfigToHook(context.applicationContext, SettingsCache.appLinkCaptureMode)
            return
        }
        // Hook 端回报本次同步的实际生效结果：落盘供设置页展示同步状态
        if (intent.action == AppLinkHookContract.ACTION_LINK_SYNC_STATUS) {
            val rulesCount = intent.getIntExtra(AppLinkHookContract.EXTRA_RULES_COUNT, -1)
            if (rulesCount >= 0) {
                AppLinkHookSyncStatus.save(context.applicationContext, rulesCount)
                Log.i(TAG, "[SYNC-REPORT] Hook 端回报规则列表已同步: $rulesCount 条")
            }
            return
        }
        if (intent.action != AppLinkHookContract.ACTION_HANDLE_APP_LINK) return
        val url = intent.getStringExtra(AppLinkHookContract.EXTRA_LINK_URL)
        if (url.isNullOrBlank()) {
            Log.w(TAG, "广播内链接为空，放弃处理")
            return
        }
        // 基础校验：仅接受 http/https 链接，过滤异常数据
        val scheme = runCatching { android.net.Uri.parse(url).scheme }.getOrNull()
        if (scheme != "http" && scheme != "https") {
            Log.w(TAG, "非 http/https 链接，放弃处理: $url")
            return
        }
        val sourcePkg = intent.getStringExtra(AppLinkHookContract.EXTRA_LINK_SOURCE)
        if (!sourcePkg.isNullOrBlank()) {
            lastAskSourcePkg = sourcePkg
        }
        Log.i(TAG, "收到应用内链接: url=${url.take(60)}, sourcePkg=$sourcePkg")

        // 立即回报「已接管」：Hook 端据此取消超时回退兜底。
        // 必须放在冷启动缓存加载之前——回执越快，Hook 端的兜底窗口越不会被误触发（误触发会双开）。
        val linkToken = intent.getStringExtra(AppLinkHookContract.EXTRA_LINK_TOKEN)
        if (!linkToken.isNullOrBlank()) {
            notifyHandled(context.applicationContext, linkToken)
        }

        // 申请短时 WakeLock，防止冷启动瞬间被系统电源管理冻结
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = runCatching {
            pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LinkGo:AppLinkWakeLock")?.apply {
                setReferenceCounted(false)
                acquire(3000L)
            }
        }.getOrNull()

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                SettingsRepository.fastSyncCache(context.applicationContext)
                if (!SettingsCache.isLoaded) {
                    SettingsRepository(context.applicationContext).preloadAll()
                }
                when (SettingsCache.appLinkCaptureMode) {
                    1 -> handleIntercept(context.applicationContext, url, sourcePkg)
                    2 -> handleAsk(context.applicationContext, url, sourcePkg)
                    else -> Log.w(TAG, "捕获模式为关闭，忽略本次广播")
                }
            } catch (e: Exception) {
                Log.e(TAG, "处理应用内链接异常: ${e.message}", e)
            } finally {
                try { wakeLock?.release() } catch (_: Exception) {}
                pendingResult.finish()
            }
        }
    }

    /**
     * 拦截模式：静默分发（分享到 LinkGo 同链路）+ 弹「内置打开」提示胶囊。
     *
     * 分发无法确定目标时（拿不到可跳转的站点/应用），回退为用原应用内置浏览器打开该链接：
     * 内置浏览器启动已被 system_server 拦下，若不回退，用户点击链接后将毫无反应。
     */
    private suspend fun handleIntercept(appContext: Context, url: String, sourcePkg: String?) {
        Log.i(TAG, "[INTERCEPT] 静默分发链接: ${url.take(60)}")
        // 「仅命中规则时拦截」：接管前用主进程自己的规则表（含归一化）再判一次。
        // Hook 端只有原始 URL 与规则快照，两侧结论可能分歧；这里是权威判据，
        // 未命中规则就直接交回应用内置浏览器，避免落到备选浏览器或弹出「选择浏览器」页面。
        if (SettingsCache.appLinkRuleOnlyIntercept &&
            !WindowRouter.hasMatchedRule(appContext, url, WindowRouter.DispatchSource.SMART)
        ) {
            Log.i(TAG, "[INTERCEPT-RULE-ONLY-PASS] 未命中跳转规则，放行内置打开: ${url.take(60)}")
            fallbackToBuiltin(appContext, url)
            return
        }
        // 不再请求「分发放行窗口」：该窗口按 URL 字面量在 5 秒内一律放行，
        // 会把用户短时间内重复打开的同一条链接误放行（微信直接内置打开）。
        // 分发出去后可能产生的循环，已由 Hook 端「规则目标即发起应用 → 放行」判据处理。
        val result = try {
            WindowRouter.handleUrl(appContext, url, WindowRouter.DispatchSource.SMART)
        } catch (e: Exception) {
            Log.e(TAG, "[INTERCEPT] 分发异常，回退内置打开: ${e.message}", e)
            fallbackToBuiltin(appContext, url)
            return
        }

        when (result.status) {
            WindowRouter.DispatchResult.NEED_SELECTOR -> {
                // 需要用户挑浏览器 = 本次拿不到可用的规则目标；开启开关时不接管，直接内置打开
                if (SettingsCache.appLinkRuleOnlyIntercept) {
                    Log.i(TAG, "[INTERCEPT-RULE-ONLY-PASS] 无可用的规则目标，改为内置打开: ${url.take(60)}")
                    fallbackToBuiltin(appContext, url)
                    return
                }
                appContext.startActivity(Intent(appContext, BrowserSelectorActivity::class.java).apply {
                    putExtra("URL", result.finalUrl)
                    putExtra("SOURCE", WindowRouter.DispatchSource.SMART.name)
                    putExtra("TRACE_ID", result.traceId)
                    putExtra("STEP_INDEX", result.stepIndex)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
            WindowRouter.DispatchResult.FAIL -> {
                // 拿不到请求站点/分发失败：直接内置打开，不留「点了没反应」
                fallbackToBuiltin(appContext, url)
                return
            }
            WindowRouter.DispatchResult.SUCCESS -> Unit
        }

        showOpenBuiltinCapsule(appContext, url)
    }

    /**
     * 回退：请求 Hook 端重放被拦下的完整 Intent，用原应用内置浏览器打开该链接。
     */
    private fun fallbackToBuiltin(appContext: Context, url: String) {
        Log.i(TAG, "[INTERCEPT-FALLBACK] 无法确定分发目标，回退内置打开: ${url.take(60)}")
        openInBuiltin(appContext, url)
        Toast.makeText(appContext, "已用内置浏览器打开", Toast.LENGTH_SHORT).show()
    }

    /**
     * 询问模式：内置浏览器照常打开，复用剪贴板式胶囊（点击后由 LinkGo 跳转）。
     */
    private suspend fun handleAsk(appContext: Context, url: String, sourcePkg: String?) {
        Log.i(TAG, "[ASK] 复用剪贴板式胶囊: ${url.take(60)}, sourcePkg=$sourcePkg")
        // 「仅命中规则时拦截」在询问模式下的表现：未命中规则就不接管、不弹询问胶囊
        // （内置浏览器本来就已照常打开，此处只需不打扰用户）
        if (SettingsCache.appLinkRuleOnlyIntercept &&
            !WindowRouter.hasMatchedRule(appContext, url, WindowRouter.DispatchSource.SMART)
        ) {
            Log.i(TAG, "[ASK-RULE-ONLY-PASS] 未命中跳转规则，不弹询问胶囊: ${url.take(60)}")
            return
        }
        if (!sourcePkg.isNullOrBlank()) {
            lastAskSourcePkg = sourcePkg
        }
        ClipboardHandler.handleDirect(appContext, url, isClipboardChange = false)
    }

    /**
     * 「内置打开」提示胶囊：点击后请求 system_server 用缓存的完整 Intent 重新启动内置浏览器。
     */
    private fun showOpenBuiltinCapsule(appContext: Context, url: String) {
        if (!Settings.canDrawOverlays(appContext)) {
            Log.w(TAG, "缺少悬浮窗权限，跳过内置打开提示胶囊")
            return
        }
        val capsule = ClipboardCapsuleOverlay(appContext)
        val icon = appContext.applicationInfo.loadIcon(appContext.packageManager)
        capsule.showArray(
            listOf(
                ClipboardCapsuleOverlay.CapsuleItemData(
                    icon = icon,
                    label = "内置打开",
                    rawText = true,
                    onTap = { _ ->
                        capsule.dismiss()
                        openInBuiltin(appContext, url)
                        Toast.makeText(appContext, "已用内置浏览器打开", Toast.LENGTH_SHORT).show()
                        Log.i(TAG, "已请求内置打开: ${url.take(60)}")
                    }
                )
            )
        )
    }
}

/**
 * Hook 端（system_server）回写、主进程读取的捕获同步快照。
 *
 * HookEntry 每次收到 SYNC 后都会把「实际生效的跳转规则条数 + 时间」写入 libxposed 远程偏好；
 * 这里读出来供设置页展示，用于判断规则列表是否真的同步到了 Hook 端
 * （不同步则断环判定失效，应用内链接捕获会陷入循环）。
 */
object AppLinkHookSyncStatus {
    private const val PREFS_NAME = "linkgo_hook_sync"
    private const val KEY_RULES_COUNT = "rules_count"
    private const val KEY_SYNCED_AT = "synced_at"

    /** Hook 端回报后由主进程落盘（见 LinkIntentReceiver.onReceive）。 */
    fun save(context: Context, rulesCount: Int) {
        runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_RULES_COUNT, rulesCount)
                .putLong(KEY_SYNCED_AT, System.currentTimeMillis())
                .apply()
        }
    }

    /** 已同步生效的跳转规则条数；null 表示 Hook 端从未回报（模块未生效或未同步）。 */
    fun rulesCount(context: Context): Int? = read(context) { prefs ->
        prefs.getInt(KEY_RULES_COUNT, -1).takeIf { it >= 0 }
    }

    /** 最近一次同步完成时间（毫秒）；null 表示从未同步。 */
    fun syncedAt(context: Context): Long? = read(context) { prefs ->
        prefs.getLong(KEY_SYNCED_AT, 0L).takeIf { it > 0L }
    }

    private fun <T> read(context: Context, block: (android.content.SharedPreferences) -> T?): T? =
        runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.let(block)
        }.getOrNull()
}
