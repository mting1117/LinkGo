package com.moting.linkgo.hook;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import android.app.AppOpsManager;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.NonNull;

import com.moting.linkgo.applink.AppLinkHookContract;
import com.moting.linkgo.clipboard.ClipboardHookContract;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * LSPosed 模块入口：在 system_server 中 hook ClipboardService.setPrimaryClip*，
 * 于写入点直接截获剪贴板文本与来源包名，广播给主进程的 ClipboardTextReceiver。
 * 复刻 HyperCopy 的 HookEntry。
 */
public class HookEntry extends XposedModule {
    private static final String TAG = "LinkGoHook";
    private static final String CLIPBOARD_SERVICE_CLASS = "com.android.server.clipboard.ClipboardService";
    private static final String RECEIVER_CLASS = "com.moting.linkgo.clipboard.ClipboardTextReceiver";
    private static final long MICRO_DEBOUNCE_WINDOW_MILLIS = 50L;
    private static final int MAX_TEXT_LENGTH = 16384;
    private static final int INSTALL_RETRY_LIMIT = 20;
    private static final long INSTALL_RETRY_DELAY_MILLIS = 1000L;
    private static final int CLEAR_RECEIVER_RETRY_LIMIT = 20;
    private static final long CLEAR_RECEIVER_RETRY_DELAY_MILLIS = 1000L;

    /**
     * Xposed 实例的静态引用：
     * 图片通道的解码/落盘全在静态辅助方法里，而 HookLog 需要 XposedInterface 才能同时写 logcat 与 Xposed 日志，
     * 因此这里持有一份引用供静态日志出口使用。仅在构造时赋值一次。
     */
    private static volatile XposedInterface staticSelf = null;

    /** 静态解冻执行器：sendHookBroadcast 需要它做冷启动接力，而广播出口本身是静态的 */


    private static String lastText = "";
    private static long lastSentAt = 0L;
    private boolean hooksInstalled = false;
    private boolean clearReceiverRegistered = false;
    private BroadcastReceiver clearReceiverInstance = null;

    /**
     * 冷启动接力解冻执行器。
     *
     * 保持静态：`sendHookBroadcast` 与 `postLaunchUnfreezeRelay` 都是静态辅助方法，
     * 而它们都要用它做冷启动期间的接力解冻。Xposed 模块实例通常只有一个，静态化没有副作用。
     */
    private static final ScheduledExecutorService staticUnfreezeExecutor =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "LinkGo-UnfreezeRelay");
            t.setDaemon(true);
            return t;
        });

    // ── 应用内链接捕获（system_server 拦截应用内打开的 http/https 链接）──
    /**
     * Activity 启动入口候选类：
     * 1. ActivityTaskManagerService（Binder 服务实现，每次启动必经、签名稳定、调用者 UID 可靠）——首选；
     * 2. ActivityStarter 系列（AOSP / HyperOS / 小米 android.miui 包）——多保险。
     */
    private static final String[] ACTIVITY_STARTER_CLASSES = {
        "com.android.server.wm.ActivityTaskManagerService",
        "com.android.server.wm.ActivityTaskManagerServiceImpl",
        "com.android.server.wm.ActivityStarter",
        "com.android.server.wm.ActivityStarterImpl",
        "android.miui.ActivityStarter"
    };
    /** 捕获模式：0=关闭 1=拦截 2=询问（由主进程经广播同步） */
    private static volatile int captureMode = 0;
    /** 仅命中规则时拦截：开启后未命中跳转规则的链接一律放行（本该走备选浏览器，交回应用内置浏览器） */
    private static volatile boolean ruleOnlyIntercept = false;
    /** 单次放行 URL：命中该 URL 的启动直接放行一次（「内置打开」重放防死循环） */
    private static volatile String singlePassUrl = null;

    /**
     * 超时回退窗口：拦截后若既未收到主进程「已接管」回执、也未收到回退指令，
     * 由 Hook 端自行重放缓存 Intent 放行内置浏览器，避免「点了完全没反应」。
     * 取值需覆盖主进程被冷启动拉起 + 广播往返，同时不宜过长（用户会感知为无响应）。
     */
    private static final long FALLBACK_TIMEOUT_MILLIS = 1500L;
    /** 超时回退专用定时器（取消时整表清理，不与其它延时任务共用） */
    private static final Handler fallbackHandler = new Handler(Looper.getMainLooper());
    /** 当前待回退 token；收到主进程回执即清空，迟到回执不再撤销已发生的回退 */
    private static volatile String pendingFallbackToken = null;
    /** 接管应用列表（仅列表内应用打开的链接才被处理；未同步前默认微信/QQ） */
    private static volatile String[] captureApps = {
        com.moting.linkgo.applink.AppLinkHookContract.PKG_WECHAT,
        com.moting.linkgo.applink.AppLinkHookContract.PKG_QQ
    };

    /**
     * 跳转规则表，格式 "matchType::pattern::targetPkg"（matchType: CONTAINS/REGEX/EXACT）。
     * 由主进程随 SYNC 下发，用于「链接的规则目标应用 == 本次发起应用 → 放行」的断环判定。
     * 判据只依赖规则匹配与包名，不受 URL 参数变异（如目标应用追加 plg_auth）影响。
     */
    private static volatile String[] dispatchRules = new String[0];

    /**
     * 「仅命中规则时拦截」的命中判据表，格式 "matchType::pattern"，**含链式分发规则**
     * （分发规则也是规则，主进程会沿链路继续分发，不能当作未命中）。
     * 与 [dispatchRules] 分开：后者只含终端规则，专用于「目标应用 == 发起应用」断环判定。
     */
    private static volatile String[] ruleHitPatterns = new String[0];

    /**
     * 规则表内是否存在「空白模式」兜底规则（主进程语义：空白模式命中一切链接）。
     * 该规则不参与「目标应用」判定，但「仅命中规则时拦截」必须知道它存在，否则会与主进程结论分歧。
     */
    private static volatile boolean hasCatchAllRule = false;

    /**
     * 「仅命中规则时拦截」判据：该链接是否命中任一启用的跳转规则（含链式分发规则）。
     *
     * 与主进程 handleUrl 的匹配口径对齐：命中即会被 LinkGo 接管分发，未命中才走备选浏览器。
     * 空白模式的兜底规则命中一切链接（见 updateDispatchRules 的 hasCatchAllRule 登记）。
     *
     * 注意：这里是 Hook 端的**快速路径**，用的是原始 URL；主进程还会先做归一化再匹配
     * （standardizeUrl），因此两侧结论可能分歧——最终以主进程的 WindowRouter.hasMatchedRule 为准
     * （LinkIntentReceiver 在接管前会再判一次，不一致时按未命中放行）。
     */
    private static boolean hasMatchedDispatchRule(String url) {
        if (url == null || url.isEmpty()) return false;
        if (hasCatchAllRule) return true;
        String[] hits = ruleHitPatterns;
        for (String hit : hits) {
            if (hit == null) continue;
            int sep = hit.indexOf("::");
            if (sep <= 0) continue;
            if (matchDispatchPattern(hit.substring(0, sep), hit.substring(sep + 2), url)) return true;
        }
        return false;
    }

    /** 来源应用是否在接管列表内。 */
    private static boolean isCaptureApp(String callingPkg) {
        if (callingPkg == null || callingPkg.isEmpty()) return false;
        String[] apps = captureApps;
        for (String a : apps) {
            if (a != null && a.equals(callingPkg)) return true;
        }
        return false;
    }

    /**
     * 解析主进程下发的跳转规则表 JSON。
     * 规则顺序即优先级，必须与主进程一致，否则本条链接的「目标应用」判定会分歧。
     */
    private static void updateDispatchRules(String json) {
        if (json == null || json.isEmpty()) return;
        try {
            // 兼容两种快照形态：裸数组 与 {"linkgo_rules":[...]} 包装（与主进程 parseRules 一致）
            Object root = new org.json.JSONTokener(json).nextValue();
            org.json.JSONArray arr;
            if (root instanceof org.json.JSONArray) {
                arr = (org.json.JSONArray) root;
            } else if (root instanceof org.json.JSONObject) {
                arr = ((org.json.JSONObject) root).optJSONArray("linkgo_rules");
            } else {
                arr = null;
            }
            if (arr == null) return;
            java.util.ArrayList<String> list = new java.util.ArrayList<>();
            // 「仅命中规则时拦截」的命中判据表：链式分发规则同样算命中（它也是规则），
            // 与下面只含终端规则的「目标应用」判定表分开维护。
            java.util.ArrayList<String> hitPatterns = new java.util.ArrayList<>();
            boolean catchAll = false;
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject obj = arr.optJSONObject(i);
                if (obj == null) continue;
                // 与主进程一致：仅启用中的规则参与匹配
                if (!obj.optBoolean("isEnabled", true)) continue;
                String pat = obj.optString("pattern", "").trim();
                if (pat.isEmpty()) {
                    // 空白模式 = 主进程的兜底匹配（命中一切链接）：不进判定表，只登记存在性
                    catchAll = true;
                    continue;
                }
                String type = obj.optString("matchType", "REGEX");
                hitPatterns.add(type + "::" + pat);
                // 链式分发规则的目标应用要跑完整条链路才能确定，不参与「目标应用 == 发起应用」判定
                String strategy = obj.optString("resolveStrategy", "");
                if ("RE_DISPATCH".equals(strategy) || "2".equals(strategy)) continue;
                String pkg = obj.optString("targetPackage", "").trim();
                if (pkg.isEmpty()) continue;
                // 目标可能是「包名/类名」形式，判定只用包名
                int slash = pkg.indexOf('/');
                if (slash > 0) pkg = pkg.substring(0, slash);
                if (pkg.isEmpty()) continue;
                list.add(type + "::" + pat + "::" + pkg);
            }
            dispatchRules = list.toArray(new String[0]);
            ruleHitPatterns = hitPatterns.toArray(new String[0]);
            hasCatchAllRule = catchAll;
        } catch (Throwable t) {
            // 解析失败保持旧规则，静默降级（static 上下文不依赖实例日志）
        }
    }

    /**
     * 按跳转规则表解析该链接的目标应用包名；无命中返回 null。
     * 匹配语义与主进程 WindowRouter.matchRule 对齐（CONTAINS 支持 host 相等/子域，否则 URL 包含）。
     */
    private static String resolveRuleTargetPackage(String url) {
        if (url == null || url.isEmpty()) return null;
        String[] rules = dispatchRules;
        for (String rule : rules) {
            if (rule == null) continue;
            String[] parts = rule.split("::", 3);
            if (parts.length < 3) continue;
            String type = parts[0];
            String pat = parts[1];
            String pkg = parts[2];
            if (pat.isEmpty() || pkg.isEmpty()) continue;
            if (matchDispatchPattern(type, pat, url)) return pkg;
        }
        return null;
    }

    private static boolean matchDispatchPattern(String type, String pattern, String url) {
        try {
            if ("EXACT".equals(type)) {
                return url.equalsIgnoreCase(pattern);
            }
            if ("CONTAINS".equals(type)) {
                String host = null;
                try {
                    host = android.net.Uri.parse(url).getHost();
                } catch (Throwable ignored) {
                }
                if (host != null && !pattern.isEmpty()
                    && (host.equalsIgnoreCase(pattern)
                        || host.toLowerCase().endsWith("." + pattern.toLowerCase()))) {
                    return true;
                }
                return url.toLowerCase().contains(pattern.toLowerCase());
            }
            // 默认按正则（与主进程 MatchType.REGEX 一致）
            java.util.regex.Pattern p =
                java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.CASE_INSENSITIVE);
            return p.matcher(url).find();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 断环判定：链接命中的跳转规则，其目标应用正是本次发起应用时放行。
     *
     * 语义：这条链接本来就该由该应用打开，LinkGo 不应再接管跳转。
     * 典型场景——微信打开链接经规则分发到 QQ，QQ 内部再次打开同一链接即放行，循环就此断开。
     * 判据不含 URL 字面量，因此目标应用对 URL 做参数追加（如 plg_auth）也不会逃逸。
     */
    private static boolean isRuleTargetSelf(String callingPkg, String url) {
        if (callingPkg == null || callingPkg.isEmpty() || url == null || url.isEmpty()) return false;
        String target = resolveRuleTargetPackage(url);
        return target != null && target.equals(callingPkg);
    }

    /** 解析主进程下发的接管应用 JSON，更新内存列表。 */
    private static void updateCaptureApps(String json) {
        if (json == null || json.isEmpty()) return;
        try {
            org.json.JSONArray arr = new org.json.JSONArray(json);
            java.util.ArrayList<String> list = new java.util.ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                Object item = arr.opt(i);
                if (item instanceof org.json.JSONObject) {
                    org.json.JSONObject obj = (org.json.JSONObject) item;
                    if (obj.optBoolean("isEnabled", true)) {
                        String pkg = obj.optString("packageName", "").trim();
                        if (!pkg.isEmpty() && !list.contains(pkg)) list.add(pkg);
                    }
                } else if (item instanceof String) {
                    String s = ((String) item).trim();
                    if (!s.isEmpty() && !list.contains(s)) list.add(s);
                }
            }
            captureApps = list.toArray(new String[0]);
        } catch (Throwable t) {
            // 解析失败保持旧列表，静默降级
        }
    }
    /** 被拦截链接的完整 Intent 缓存（供「内置打开」重放，60 秒过期） */
    private static volatile Intent cachedLinkIntent = null;
    private static volatile String cachedLinkUrl = null;
    private static volatile long cachedLinkAt = 0L;
    private static final long CACHE_TTL_MS = 60_000L;

    /** 询问模式最近一次启动的 ActivityRecord 弱引用（供跳转外部后自动销毁） */
    private static volatile java.lang.ref.WeakReference<Object> lastAskActivityRecord = null;
    private static volatile String lastAskActivityUrl = null;
    private static volatile String lastAskActivityPkg = null;
    private static volatile long lastAskActivityTime = 0L;
    private boolean appLinkHooksInstalled = false;
    private boolean appLinkReceiversRegistered = false;
    /** 应用内链接指令接收器实例：热重载前需显式注销，避免旧代接收器残留造成重复处理 */
    private BroadcastReceiver appLinkReceiverInstance = null;
    /** ActivityRecord 构造观察钩子（检测到 Activity 即取 activityIntentUri 提取链接） */
    private boolean activityRecordHooked = false;
    /** 安装过程诊断状态（随 SYNC 日志输出，绕开启动日志被冲刷问题） */
    private static volatile String appLinkInstallStatus = "pending";

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        logDebug("module loaded: process=" + param.getProcessName()
            + ", systemServer=" + param.isSystemServer()
            + ", api=" + getApiVersion());
        // 【临时诊断探针，验证后删除】手工重载模块若不触发 onSystemServerStarting
        // （system_server 已在运行、只是重新注入），这条路径是唯一的执行点
        if (param.isSystemServer()) {
            staticSelf = this;
            probeAppConfigReadability();
            installAppLinkCaptureHooksWithRetry(getClass().getClassLoader(), "onModuleLoaded", 0);
        }
    }

    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        // 供静态辅助方法（图片解码/落盘）里的日志出口使用
        staticSelf = this;
        installClipboardHooksWithRetry(param.getClassLoader(), "onSystemServerStarting", 0);
        installAppLinkCaptureHooksWithRetry(param.getClassLoader(), "onSystemServerStarting", 0);
        probeAppConfigReadability();
    }

    /**
     * 【临时诊断探针，验证结论后立即删除】
     *
     * 目的：判断「主进程推送配置」能否退化为「Hook 按需读取配置」。
     * A/B/C 任一可读，则整条广播推送链（接收器注册 / 重试 / 注销生命周期）
     * 都可以删除，故障模式随之从「通道断了静默失效」降级为「读到旧值」。
     * D 用于确认 Hook 端自持久化的落盘位置是否可用。
     *
     * 判据说明：File.canRead() 只是权限位，真正的 SELinux 判据在 FileInputStream 首次 read，
     * 因此这里必须实际打开读取一次，异常也要完整打出来。
     */
    private void probeAppConfigReadability() {
        final String pkgDir = "/data/data/" + AppLinkHookContract.APPLICATION_ID;

        // A. 直读应用私有目录的 SharedPreferences 文件
        for (String name : new String[]{"linkgo_fast_cache.xml", "linkgo_capture.xml"}) {
            try {
                java.io.File f = new java.io.File(pkgDir + "/shared_prefs/" + name);
                StringBuilder sb = new StringBuilder("[DIAG-PREFS] ").append(name)
                    .append(" exists=").append(f.exists())
                    .append(" canRead=").append(f.canRead())
                    .append(" len=").append(f.length());
                if (f.length() > 0) {
                    try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                        byte[] buf = new byte[400];
                        int n = in.read(buf);
                        if (n > 0) {
                            sb.append(" head=").append(new String(buf, 0, n).replace('\n', ' '));
                        }
                    }
                }
                logDebug(sb.toString());
            } catch (Throwable t) {
                logDebug("[DIAG-PREFS] " + name + " 读取失败: " + t);
            }
        }

        // B. libxposed 远程偏好（应用侧用普通 getSharedPreferences 写同名文件）
        try {
            android.content.SharedPreferences rp = getRemotePreferences("linkgo_fast_cache");
            logDebug("[DIAG-REMPREFS] null=" + (rp == null)
                + " is_initialized=" + (rp == null ? "n/a" : rp.getBoolean("is_initialized", false))
                + " captureMode=" + (rp == null ? "n/a" : rp.getInt("app_link_capture_mode", -1)));
        } catch (Throwable t) {
            logDebug("[DIAG-REMPREFS] 读取失败: " + t);
        }

        // C. 应用专属外部目录（应用侧无需任何权限即可写入）
        try {
            java.io.File f = new java.io.File(
                "/sdcard/Android/data/" + AppLinkHookContract.APPLICATION_ID + "/files/linkgo_probe.txt");
            StringBuilder sb = new StringBuilder("[DIAG-EXT] exists=").append(f.exists())
                .append(" canRead=").append(f.canRead())
                .append(" len=").append(f.length());
            if (f.length() > 0) {
                try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                    byte[] buf = new byte[200];
                    int n = in.read(buf);
                    if (n > 0) {
                        sb.append(" content=").append(new String(buf, 0, n).replace('\n', ' '));
                    }
                }
            }
            logDebug(sb.toString());
        } catch (Throwable t) {
            logDebug("[DIAG-EXT] 读取失败: " + t);
        }

        // D. Hook 端自持久化的候选落盘位置
        try {
            java.io.File dir = new java.io.File("/data/system");
            java.io.File probe = new java.io.File(dir, "linkgo_probe.tmp");
            boolean created = probe.createNewFile();
            logDebug("[DIAG-SYSWRITE] dirCanWrite=" + dir.canWrite()
                + " createNew=" + created + " exists=" + probe.exists());
            if (created) probe.delete();
        } catch (Throwable t) {
            logDebug("[DIAG-SYSWRITE] 写入失败: " + t);
        }

        // E. 接收器注册状态：用于区分「注册本身失败」与「注册成功后又被注销」
        //    onModuleLoaded 早于安装流程，此刻取值会失真，故延后 3 秒再采一次
        logProbeReceiverState("now");
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                () -> logProbeReceiverState("+3s"), 3000L);
        } catch (Throwable t) {
            logDebug("[DIAG-RECEIVER] 延迟采样注册失败: " + t);
        }
    }

    /** 采样一次接收器注册状态（探针辅助，随探针一并删除）。 */
    private void logProbeReceiverState(String phase) {
        try {
            logDebug("[DIAG-RECEIVER] " + phase
                + " registered=" + appLinkReceiversRegistered
                + " instance=" + (appLinkReceiverInstance != null));
        } catch (Throwable t) {
            logDebug("[DIAG-RECEIVER] " + phase + " 取值异常: " + t);
        }
    }

    @Override
    public boolean onHotReloading(@NonNull HotReloadingParam param) {
        logDebug("module hot reloading: preparing state transition (" + param + ")");
        try {
            Context context = findSystemContext();
            if (context != null && clearReceiverInstance != null) {
                try {
                    context.unregisterReceiver(clearReceiverInstance);
                } catch (Throwable ignored) {}
                clearReceiverInstance = null;
                clearReceiverRegistered = false;
                logDebug("unregistered clearReceiver on hot reloading");
            }
            synchronized (cachedImePackages) {
                cachedImePackages.clear();
                lastImeCacheUpdate = 0L;
            }
            // 应用内链接指令接收器同样要注销，否则旧代接收器残留会与新代重复处理同一条广播
            if (context != null && appLinkReceiverInstance != null) {
                try {
                    context.unregisterReceiver(appLinkReceiverInstance);
                } catch (Throwable ignored) {}
                appLinkReceiverInstance = null;
                appLinkReceiversRegistered = false;
                logDebug("unregistered appLinkReceiver on hot reloading");
            }
            // 热重载后包生命周期回调不会重放，必须让新代码重新装载全部能力：
            // 复位标志位，否则新代码的安装入口会因「已安装」直接返回，导致钩子整体失效。
            hooksInstalled = false;
            clearReceiverRegistered = false;
            appLinkHooksInstalled = false;
            appLinkReceiversRegistered = false;
            logDebug("hot reloading: 捕获能力标志位已复位，等待新代码重新装载");
        } catch (Throwable t) {
            logWarn("cleanup on hot reloading failed", t);
        }
        return true;
    }

    /**
     * 热重载完成（运行在**新代码**中）。
     *
     * 框架默认只卸载全部旧钩子，且不会重放包生命周期回调，因此这里必须显式重建：
     * 1. 摘掉旧代遗留的钩子（含剪贴板、应用内链接捕获及各诊断钩子）；
     * 2. 用 system_server 类加载器重新安装两组核心钩子与接收器。
     * 完成后方可做到「安装新 APK 即生效」，无需重启设备或手动重载模块。
     */
    @Override
    public void onHotReloaded(@NonNull HotReloadedParam param) {
        logDebug("module hot reloaded: new generation active (" + param + ")");
        // 日志出口挂到新代码的实例上，避免仍指向旧代对象
        staticSelf = this;
        try {
            for (io.github.libxposed.api.XposedInterface.HookHandle handle : param.getOldHookHandles()) {
                try {
                    handle.unhook();
                } catch (Throwable ignored) {
                }
            }
            logDebug("hot reloaded: 旧代钩子已摘除");
        } catch (Throwable t) {
            logWarn("hot reloaded: 旧代钩子摘除失败", t);
        }

        Context context = findSystemContext();
        if (context == null) {
            logWarn("hot reloaded: system context 尚未就绪，无法重装钩子", null);
            return;
        }
        ClassLoader classLoader = context.getClassLoader();
        if (classLoader == null) {
            logWarn("hot reloaded: system classLoader 不可用，无法重装钩子", null);
            return;
        }
        try {
            installClipboardHooksWithRetry(classLoader, "onHotReloaded", 0);
            installAppLinkCaptureHooksWithRetry(classLoader, "onHotReloaded", 0);
            registerClearReceiverWithRetry(0);
            logDebug("hot reloaded: 剪贴板与应用内链接捕获钩子已重装");
            probeAppConfigReadability();
        } catch (Throwable t) {
            logWarn("re-install hooks on hot reloaded failed", t);
        }
        // 新代内存中的规则表为空，请求主进程重发一次 SYNC 以恢复断环判定
        requestConfigResync();
    }

    /**
     * 向主进程请求重新下发捕获配置（含跳转规则表）。
     *
     * 热重载后 HookEntry 为全新实例，内存中的规则表为空，必须靠主进程重发一次
     * 才能让「规则目标应用 == 发起应用 → 放行」的断环判定立即恢复，无需用户重启应用。
     */
    private void requestConfigResync() {
        try {
            Context context = findSystemContextCached();
            if (context == null) return;
            Intent intent = new Intent(AppLinkHookContract.ACTION_REQUEST_SYNC)
                .setComponent(new ComponentName(
                    AppLinkHookContract.APPLICATION_ID,
                    AppLinkHookContract.RECEIVER_CLASS))
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND | Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            context.sendBroadcast(intent);
            logDebug("[APP-LINK-SYNC-REQ] 已请求主进程重发捕获配置");
        } catch (Throwable t) {
            logWarn("request config resync failed", t);
        }
    }

    private void installClipboardHooksWithRetry(ClassLoader classLoader, String source, int attempt) {
        if (hooksInstalled) return;
        if (installClipboardHooks(classLoader, source + ", attempt=" + attempt)) return;
        if (attempt >= INSTALL_RETRY_LIMIT) return;
        new Handler(Looper.getMainLooper()).postDelayed(
            () -> installClipboardHooksWithRetry(classLoader, source, attempt + 1),
            INSTALL_RETRY_DELAY_MILLIS
        );
    }

    private boolean installClipboardHooks(ClassLoader classLoader, String source) {
        if (hooksInstalled) {
            logDebug("ClipboardService hooks already installed, source=" + source);
            return true;
        }
        logDebug("installing ClipboardService hooks, source=" + source);
        try {
            Class<?> clipboardServiceClass = Class.forName(CLIPBOARD_SERVICE_CLASS, false, classLoader);
            Set<Method> hookedMethods = new HashSet<>();
            int hookedCount = hookClipboardMethods(clipboardServiceClass, hookedMethods);
            for (Class<?> declaredClass : clipboardServiceClass.getDeclaredClasses()) {
                hookedCount += hookClipboardMethods(declaredClass, hookedMethods);
            }

            // Hook isDefaultIme：赋予 LinkGo 官方原生后台读取剪贴板特权，并由系统自动授予图片 URI 权限（对齐 clipboardwhitelist）
            Set<Method> imeHookedMethods = new HashSet<>();
            int imeHookedCount = hookIsDefaultIme(clipboardServiceClass, imeHookedMethods);
            for (Class<?> declaredClass : clipboardServiceClass.getDeclaredClasses()) {
                imeHookedCount += hookIsDefaultIme(declaredClass, imeHookedMethods);
            }
            logDebug("isDefaultIme hooks installed: " + imeHookedCount);

            hooksInstalled = hookedCount > 0;
            if (hooksInstalled) registerClearReceiverWithRetry(0);
            logDebug("ClipboardService hooks installed: " + hookedCount);
            return hooksInstalled;
        } catch (ClassNotFoundException throwable) {
            logDebug("ClipboardService not ready, source=" + source + ", classLoader=" + classLoader);
            return false;
        } catch (Throwable throwable) {
            logError("Failed to hook ClipboardService", throwable);
            return false;
        }
    }

    private void registerClearReceiverWithRetry(int attempt) {
        if (clearReceiverRegistered) return;
        if (registerClearReceiver(findSystemContext(), attempt)) return;
        if (attempt >= CLEAR_RECEIVER_RETRY_LIMIT) return;
        new Handler(Looper.getMainLooper()).postDelayed(
            () -> registerClearReceiverWithRetry(attempt + 1),
            CLEAR_RECEIVER_RETRY_DELAY_MILLIS
        );
    }

    /**
     * 在 system_server 注册「清空剪贴板」接收器：
     * 主进程跳转后广播 ACTION_CLEAR_CLIPBOARD，这里直接 clearPrimaryClip
     * （system_server 无焦点限制，最可靠），防止目标应用读取剪贴板再次触发跳转。
     */
    private boolean registerClearReceiver(Context context, int attempt) {
        if (clearReceiverRegistered) return true;
        if (context == null) {
            logDebug("clipboard clear receiver context not ready, attempt=" + attempt);
            return false;
        }
        try {
            IntentFilter filter = new IntentFilter(ClipboardHookContract.ACTION_CLEAR_CLIPBOARD);
            clearReceiverInstance = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    if (!ClipboardHookContract.ACTION_CLEAR_CLIPBOARD.equals(intent.getAction())) return;
                    try {
                        ClipboardManager clipboard =
                            (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (clipboard == null) return;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            clipboard.clearPrimaryClip();
                        } else {
                            clipboard.setPrimaryClip(ClipData.newPlainText("", ""));
                        }
                        logDebug("clipboard cleared in system_server by LSPosed");
                        setResultCode(android.app.Activity.RESULT_OK);
                    } catch (Throwable throwable) {
                        logWarn("clear clipboard in system_server failed", throwable);
                    }
                }
            };
            context.registerReceiver(clearReceiverInstance, filter, ClipboardHookContract.PERMISSION_CLEAR_CLIPBOARD, null, Context.RECEIVER_EXPORTED);
            clearReceiverRegistered = true;
            logDebug("clipboard clear receiver registered");
            return true;
        } catch (Throwable throwable) {
            logWarn("register clipboard clear receiver failed, attempt=" + attempt, throwable);
            return false;
        }
    }

    private int hookClipboardMethods(Class<?> targetClass, Set<Method> hookedMethods) {
        int hookedCount = 0;
        for (Method method : targetClass.getDeclaredMethods()) {
            if (!isSetPrimaryClipMethod(method) || !hookedMethods.add(method)) continue;
            method.setAccessible(true);
            logDebug("hook ClipboardService method: " + method.toGenericString());
            hook(method).setId("linkgo_clipboard_" + method.toGenericString()).intercept(chain -> {
                int callingUid = Binder.getCallingUid();
                Object[] args = chain.getArgs().toArray();
                Object result = chain.proceed();
                try {
                    ClipData clipData = findClipData(args);
                    Context context = findContext(chain.getThisObject());
                    if (context == null) context = findSystemContext();
                    sendTextIfNeeded(context, clipData, args, callingUid);
                } catch (Throwable throwable) {
                    logWarn("clipboard hook callback failed", throwable);
                }
                return result;
            });
            hookedCount++;
        }
        return hookedCount;
    }

    private int hookIsDefaultIme(Class<?> targetClass, Set<Method> hookedMethods) {
        int count = 0;
        for (Method method : targetClass.getDeclaredMethods()) {
            if (!"isDefaultIme".equals(method.getName()) || !hookedMethods.add(method)) continue;
            Class<?>[] params = method.getParameterTypes();
            boolean hasStringParam = false;
            for (Class<?> p : params) {
                if (p == String.class) {
                    hasStringParam = true;
                    break;
                }
            }
            if (hasStringParam && (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class)) {
                method.setAccessible(true);
                logDebug("hook isDefaultIme method: " + method.toGenericString());
                hook(method).setId("linkgo_ime_" + method.toGenericString()).intercept(chain -> {
                    Object[] args = chain.getArgs().toArray();
                    for (Object arg : args) {
                        if (ClipboardHookContract.APPLICATION_ID.equals(arg)) {
                            return true;
                        }
                    }
                    return chain.proceed();
                });
                count++;
            }
        }
        return count;
    }

    private static boolean isSetPrimaryClipMethod(Method method) {
        if (!method.getName().startsWith("setPrimaryClip")) return false;
        for (Class<?> parameterType : method.getParameterTypes()) {
            if (ClipData.class.isAssignableFrom(parameterType)) return true;
        }
        return false;
    }

    private static ClipData findClipData(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof ClipData) return (ClipData) arg;
        }
        return null;
    }

    private static Context findContext(Object service) {
        return findContext(service, new HashSet<>(), 0);
    }

    private static Context findContext(Object service, Set<Object> visited, int depth) {
        if (service == null || depth > 2 || !visited.add(service)) return null;
        Class<?> current = service.getClass();
        while (current != null) {
            for (Field field : current.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(service);
                    if (value instanceof Context) return (Context) value;
                    if (field.isSynthetic() || field.getName().startsWith("this$")) {
                        Context context = findContext(value, visited, depth + 1);
                        if (context != null) return context;
                    }
                } catch (Throwable ignored) {
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static Context findSystemContext() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Method currentActivityThread = activityThreadClass.getDeclaredMethod("currentActivityThread");
            currentActivityThread.setAccessible(true);
            Object activityThread = currentActivityThread.invoke(null);
            if (activityThread == null) return null;
            Method getSystemContext = activityThreadClass.getDeclaredMethod("getSystemContext");
            getSystemContext.setAccessible(true);
            Object context = getSystemContext.invoke(activityThread);
            if (context instanceof Context) return (Context) context;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void sendTextIfNeeded(Context context, ClipData clipData, Object[] args, int callingUid) {
        if (context == null || clipData == null || clipData.getItemCount() == 0) return;
        if (containsImagePayload(clipData)) {
            sendImageIfNeeded(context, clipData, args, callingUid);
            return;
        }
        CharSequence text = extractPlainText(context, clipData);
        if (text == null) {
            return;
        }

        String value = text.toString().trim();
        if (value.isEmpty() || value.length() > MAX_TEXT_LENGTH) return;

        String sourcePackage = findSourcePackage(context, args, callingUid);

        // 1. 系统权限与鉴权校验（AppOps 校验）：
        // 若调用方的 WRITE_CLIPBOARD 权限被系统限制（如被用户设置为 ignore/deny），系统不会真正执行写入，直接忽略
        if (!isClipboardWriteAllowed(context, callingUid, sourcePackage)) {
            logDebug("[LSP-HOOK-REJECT] callingUid=" + callingUid + " pkg=" + sourcePackage
                + " write_clipboard 权限被系统限制，忽略无效 setPrimaryClip 调用");
            return;
        }

        // 2. 系统实际生效剪贴板校验（Committed State Check）：
        // 校验目标文本是否真正被系统服务吸收并在系统剪贴板中生效（防止因其它内部异常或策略被系统静默 drop 的假写入）
        if (!isActuallyCommittedInSystem(context, value)) {
            logDebug("[LSP-HOOK-UNCOMMITTED] 目标文本未在系统剪贴板中实际生效(可能被系统策略拦截或丢弃): len=" + value.length()
                + ", sourcePkg=" + sourcePackage);
            return;
        }

        // 3. 输入法（IME）粘贴回写智能识别：
        // 若来源是系统输入法应用，且本次写入的文本与系统当前/上一次已处理的文本相同，
        // 属于输入法在点击粘贴气泡或剪贴板历史项时的同步回写行为（本质是粘贴），坚决静默抑制！
        if (isInputMethodApp(context, sourcePackage) && value.equals(lastText)) {
            logDebug("[LSP-HOOK-SUPPRESS-IME] 输入法(" + sourcePackage + ")粘贴回写已存在文本，静默忽略: len=" + value.length());
            return;
        }

        // 4. 相同文本微防抖（仅针对框架层瞬时物理重入的 50ms 微防抖）：
        long now = System.currentTimeMillis();
        if (value.equals(lastText) && now - lastSentAt < MICRO_DEBOUNCE_WINDOW_MILLIS) {
            logDebug("[LSP-HOOK-SUPPRESS] 50ms微防抖抑制system_server重入发送: len=" + value.length());
            return;
        }
        lastText = value;
        lastSentAt = now;

        unfreezeTargetPackage(context, ClipboardHookContract.APPLICATION_ID);

        logDebug("[LSP-HOOK-TRIGGER] 捕获到剪贴板有效变动: len=" + value.length()
            + ", prefix=" + (value.length() > 30 ? value.substring(0, 30) : value)
            + ", sourcePkg=" + sourcePackage + ", uid=" + callingUid);

        Bundle payload = new Bundle();
        payload.putString(ClipboardHookContract.EXTRA_CLIPBOARD_TEXT, value);
        payload.putString(ClipboardHookContract.EXTRA_CLIPBOARD_SOURCE, sourcePackage == null ? "" : sourcePackage);
        sendHookBroadcast(context, ClipboardHookContract.ACTION_HANDLE_CLIPBOARD_TEXT, payload, now);
    }

    /**
     * 冷启动延时接力解冻：
     * 广播拉起处于死亡状态的目标进程时，进程刚 fork 出来会被 MIUI/HyperOS SmartPower/Greezer 瞬间打入 cgroup freeze 冻结。
     * 在 150ms（Zygote fork 结束）与 450ms（进入 Application/Receiver）连续调度二次解冻，彻底击碎冷启动休克。
     */
    private static void postLaunchUnfreezeRelay(Context context, String packageName) {
        try {
            staticUnfreezeExecutor.schedule(() -> {
                unfreezeTargetPackage(context, packageName);
            }, 150, TimeUnit.MILLISECONDS);

            staticUnfreezeExecutor.schedule(() -> {
                unfreezeTargetPackage(context, packageName);
            }, 450, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            logWarn("schedule postLaunchUnfreezeRelay failed", t);
        }
    }

    private static String findSourcePackage(Context context, Object[] args, int callingUid) {
        String[] callingPackages = context.getPackageManager().getPackagesForUid(callingUid);
        if (callingPackages != null && callingPackages.length > 0) return callingPackages[0];
        if (args == null) return "";
        for (Object arg : args) {
            if (!(arg instanceof String)) continue;
            String value = ((String) arg).trim();
            if (looksLikePackageName(value)) return value;
        }
        for (Object arg : args) {
            if (!(arg instanceof Integer)) continue;
            int uid = (Integer) arg;
            if (uid < 10_000) continue;
            String[] packages = context.getPackageManager().getPackagesForUid(uid);
            if (packages != null && packages.length > 0) return packages[0];
        }
        return "";
    }

    // ──────────────────────────── 图片通道 ────────────────────────────
    //
    // 系统级图片通道设计：
    // 1. Hook 点在 system_server 中拦截 isDefaultIme，使 LinkGo 获得与输入法同级的白名单特权；
    // 2. 当剪贴板发生图片写入时，仅进行变动感知、400ms 微防抖、解冻目标应用，并发送干净的显式广播通知 LinkGo；
    // 3. LinkGo 主进程在白名单特权下直接调用官方 cm.primaryClip 读取剪贴板，由系统自动安全授予私有 URI 权限并完成落盘。

    private static String lastImageKey = "";
    private static long lastImageSentAt = 0L;

    private static boolean containsImagePayload(ClipData clipData) {
        if (clipData == null || clipData.getItemCount() == 0) return false;
        ClipDescription description = clipData.getDescription();

        boolean hasConcreteMime = false;   // 是否声明了「具体」MIME（非 */* 通配）
        if (description != null) {
            for (int i = 0; i < description.getMimeTypeCount(); i++) {
                String mime = description.getMimeType(i);
                if (mime == null) continue;
                if (mime.startsWith("image/") || mime.contains("image")) {
                    return true; // 明确图片类型，直接确认
                }
                if (!mime.equals("*/*")) {
                    hasConcreteMime = true;
                }
            }
        }

        // 来源明确声明了非图片类型（如安装包 application/vnd.android.package-archive、压缩包 application/zip），
        // 是文件而非图片，不得再凭 content:// 或 file:// URI 泛化误判——否则会走图片链路解码失败，
        // 弹出「已复制图片（内容不可读）」的误导提示。
        if (hasConcreteMime) return false;

        // 仅在没有具体 MIME 线索（空 / 仅 */* 通配）时，才凭 URI scheme 泛化兜底
        for (int i = 0; i < clipData.getItemCount(); i++) {
            ClipData.Item item = clipData.getItemAt(i);
            if (item != null && item.getUri() != null) {
                android.net.Uri uri = item.getUri();
                String scheme = uri.getScheme();
                if ("content".equalsIgnoreCase(scheme) || "file".equalsIgnoreCase(scheme)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static android.net.Uri findImageUri(ClipData clipData) {
        if (clipData == null) return null;
        for (int i = 0; i < clipData.getItemCount(); i++) {
            ClipData.Item item = clipData.getItemAt(i);
            if (item != null && item.getUri() != null) {
                return item.getUri();
            }
        }
        return null;
    }

    private static String findImageMime(ClipData clipData) {
        if (clipData == null) return "image/*";
        ClipDescription description = clipData.getDescription();
        if (description != null) {
            for (int i = 0; i < description.getMimeTypeCount(); i++) {
                String mime = description.getMimeType(i);
                if (mime != null && mime.toLowerCase().startsWith("image/")) {
                    return mime.toLowerCase();
                }
            }
        }
        return "image/*";
    }

    /**
     * 图片剪贴板分支：变动感知、防抖、解冻应用、发送纯净显式广播。
     */
    private void sendImageIfNeeded(Context context, ClipData clipData, Object[] args, int callingUid) {
        if (context == null || clipData == null || clipData.getItemCount() == 0) return;

        android.net.Uri uri = findImageUri(clipData);
        String imageMime = findImageMime(clipData);
        String sourcePackage = findSourcePackage(context, args, callingUid);

        // AppOps 写入校验
        if (!isClipboardWriteAllowed(context, callingUid, sourcePackage)) {
            logDebug("[LSP-HOOK-IMAGE-REJECT] callingUid=" + callingUid + " pkg=" + sourcePackage
                + " write_clipboard 权限被限制，忽略图片变动");
            return;
        }

        // 400ms 同一 URI / 变动防抖
        String imageKey = uri != null ? uri.toString() : (sourcePackage + "_" + clipData.getItemCount());
        long now = System.currentTimeMillis();
        if (imageKey.equals(lastImageKey) && now - lastImageSentAt < 400L) {
            logDebug("[LSP-HOOK-IMAGE-SUPPRESS] 400ms 内同一图片重复回调，忽略: " + imageKey);
            return;
        }
        lastImageKey = imageKey;
        lastImageSentAt = now;

        unfreezeTargetPackage(context, ClipboardHookContract.APPLICATION_ID);

        Bundle payload = new Bundle();
        payload.putBoolean(ClipboardHookContract.EXTRA_IS_IMAGE, true);
        if (uri != null) {
            payload.putString(ClipboardHookContract.EXTRA_CLIPBOARD_IMAGE_URI, uri.toString());
        }
        payload.putString(ClipboardHookContract.EXTRA_CLIPBOARD_IMAGE_MIME, imageMime);
        payload.putString(ClipboardHookContract.EXTRA_CLIPBOARD_SOURCE, sourcePackage == null ? "" : sourcePackage);

        sendHookBroadcast(context, ClipboardHookContract.ACTION_HANDLE_CLIPBOARD_TEXT, payload, now);
        logDebug("[LSP-HOOK-IMAGE] 图片广播发送完成: uri=" + uri + ", sourcePkg=" + sourcePackage);
    }

    /**
     * 统一广播出口。
     * 保持纯净的显式广播，不携带任何未授权的 URI 授权标记，避免触发 AMS SecurityException。
     */
    private static void sendHookBroadcast(Context context, String action, Bundle extras, long timestamp) {
        Intent intent = new Intent(action)
            .setComponent(new ComponentName(ClipboardHookContract.APPLICATION_ID, RECEIVER_CLASS))
            .putExtra("clipboard_timestamp", timestamp)
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND | Intent.FLAG_INCLUDE_STOPPED_PACKAGES | 0x01000000 | 0x20000000);
        if (extras != null) intent.putExtras(extras);

        android.os.Bundle optionsBundle = null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Class<?> boClass = Class.forName("android.app.BroadcastOptions");
                Method makeBasicMethod = boClass.getMethod("makeBasic");
                Object bOptions = makeBasicMethod.invoke(null);
                if (bOptions != null) {
                    try {
                        Method setInteractiveMethod = boClass.getMethod("setInteractive", boolean.class);
                        setInteractiveMethod.invoke(bOptions, true);
                    } catch (Throwable ignored) {
                    }
                    try {
                        Method m = boClass.getMethod("setTemporaryAppAllowlist", long.class, int.class, int.class, String.class);
                        m.invoke(bOptions, 15000L, 0, 10000, "linkgo_clipboard");
                    } catch (Throwable t1) {
                        try {
                            Method m2 = boClass.getMethod("setTemporaryAppWhitelistDuration", long.class);
                            m2.invoke(bOptions, 15000L);
                        } catch (Throwable ignored) {
                        }
                    }
                    Method toBundleMethod = boClass.getMethod("toBundle");
                    optionsBundle = (android.os.Bundle) toBundleMethod.invoke(bOptions);
                }
            }
        } catch (Throwable ignored) {
        }

        long identity = Binder.clearCallingIdentity();
        try {
            if (optionsBundle != null) {
                try {
                    Method sendMethod = Context.class.getMethod(
                        "sendBroadcastAsUser",
                        Intent.class,
                        android.os.UserHandle.class,
                        String.class,
                        android.os.Bundle.class
                    );
                    sendMethod.invoke(context, intent, android.os.Process.myUserHandle(), null, optionsBundle);
                } catch (Throwable t) {
                    context.sendBroadcastAsUser(intent, android.os.Process.myUserHandle());
                }
            } else {
                context.sendBroadcastAsUser(intent, android.os.Process.myUserHandle());
            }
            logDebug("[LSP-BROADCAST-SENT] 已发送广播: action=" + action);
            postLaunchUnfreezeRelay(context, ClipboardHookContract.APPLICATION_ID);
        } catch (Throwable throwable) {
            logWarn("[LSP-BROADCAST-ERROR] 发送广播异常: " + action, throwable);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /** 静态上下文（辅助方法内）用的日志出口，避免与实例日志重复实现 */
    private static void logWarnStatic(String message, Throwable throwable) {
        android.util.Log.w(TAG, message + (throwable == null ? "" : ": " + throwable.getMessage()));
    }
    private static boolean looksLikePackageName(String value) {
        return !value.isEmpty() && value.contains(".") && !value.contains(" ");
    }

    private static CharSequence extractPlainText(Context context, ClipData clipData) {
        ClipDescription description = clipData.getDescription();
        if (description == null) return null;
        boolean textMime = description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)
            || description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML);
        if (!textMime) return null;

        ClipData.Item item = clipData.getItemAt(0);
        if (item == null || item.getUri() != null || item.getIntent() != null) return null;
        if (item.getText() != null) return item.getText();
        if (item.getHtmlText() != null) return item.getHtmlText();
        return item.coerceToText(context);
    }

    private static final Set<String> cachedImePackages = new HashSet<>();
    private static long lastImeCacheUpdate = 0L;

    /**
     * 校验调用者是否具有合法的系统剪贴板写入权限（AppOps 校验）。
     * 拦截被用户或系统策略设为 MODE_IGNORED / MODE_ERRORED 的非法调用。
     */
    private boolean isClipboardWriteAllowed(Context context, int callingUid, String callingPackage) {
        if (callingUid == android.os.Process.SYSTEM_UID || callingUid == 0) {
            return true;
        }
        if (context == null) return true;
        try {
            AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (appOps == null) return true;
            String pkg = callingPackage != null ? callingPackage : "";
            int mode;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !pkg.isEmpty()) {
                mode = appOps.unsafeCheckOpRawNoThrow("android:write_clipboard", callingUid, pkg);
            } else {
                mode = appOps.checkOpNoThrow("android:write_clipboard", callingUid, pkg);
            }
            if (mode == AppOpsManager.MODE_IGNORED || mode == AppOpsManager.MODE_ERRORED) {
                return false;
            }
        } catch (Throwable t) {
            logWarn("check appops write_clipboard failed", t);
        }
        return true;
    }

    /**
     * 校验目标文本是否真正已被系统剪贴板吸收并生效（防止因其它内部异常或策略被系统静默 drop 的假写入）。
     */
    private boolean isActuallyCommittedInSystem(Context context, String expectedText) {
        if (context == null || expectedText == null) return false;
        long token = Binder.clearCallingIdentity();
        try {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return false;
            ClipData primaryClip = cm.getPrimaryClip();
            if (primaryClip == null || primaryClip.getItemCount() == 0) return false;
            CharSequence activeText = extractPlainText(context, primaryClip);
            if (activeText == null) return false;
            return expectedText.equals(activeText.toString().trim());
        } catch (Throwable t) {
            logWarn("check actually committed clip failed", t);
            return true; // 发生特殊异常时防御性放行，避免误阻断
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * 动态检查目标应用是否为系统当前注册的输入法（IME）。
     * 缓存 30 秒，避免高频 IPC 损耗。
     */
    private boolean isInputMethodApp(Context context, String packageName) {
        if (packageName == null || packageName.isEmpty() || context == null) return false;
        long now = System.currentTimeMillis();
        synchronized (cachedImePackages) {
            if (now - lastImeCacheUpdate > 30_000L || cachedImePackages.isEmpty()) {
                long token = Binder.clearCallingIdentity();
                try {
                    InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        List<InputMethodInfo> imis = imm.getInputMethodList();
                        if (imis != null) {
                            cachedImePackages.clear();
                            for (InputMethodInfo imi : imis) {
                                cachedImePackages.add(imi.getPackageName());
                            }
                            lastImeCacheUpdate = now;
                        }
                    }
                } catch (Throwable t) {
                    logWarn("get input method list failed", t);
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
            return cachedImePackages.contains(packageName);
        }
    }

    /**
     * 在 system_server 内部构建全厂商通用解冻引擎（覆盖 AOSP、HyperOS/MIUI、ColorOS、OriginOS、HarmonyOS、OneUI）
     */
    private static void unfreezeTargetPackage(Context context, String packageName) {
        try {
            int targetUid = context.getPackageManager().getPackageUid(packageName, 0);

            // 1. 小米 HyperOS / MIUI 官方 "greezer" (miui.greeze.IGreezeManager) 服务
            try {
                Class<?> smCls = Class.forName("android.os.ServiceManager");
                Method getService = smCls.getMethod("getService", String.class);
                android.os.IBinder binder = (android.os.IBinder) getService.invoke(null, "greezer");
                if (binder != null) {
                    Class<?> stubCls = Class.forName("miui.greeze.IGreezeManager$Stub");
                    Method asInterface = stubCls.getMethod("asInterface", android.os.IBinder.class);
                    Object greezerManager = asInterface.invoke(null, binder);
                    if (greezerManager != null) {
                        for (Method m : greezerManager.getClass().getDeclaredMethods()) {
                            m.setAccessible(true);
                            String name = m.getName();
                            Class<?>[] pts = m.getParameterTypes();
                            if (name.contains("thaw") || name.contains("unfreeze") || name.contains("thawUid")) {
                                if (pts.length == 1 && pts[0] == int.class) {
                                    m.invoke(greezerManager, targetUid);
                                    logDebug("[LSP-UNFREEZE-XIAOMI] 成功调用 IGreezeManager." + name + "(uid=" + targetUid + ")");
                                } else if (pts.length == 2 && pts[0] == int.class && pts[1] == String.class) {
                                    m.invoke(greezerManager, targetUid, "WINDOW_OVERLAY");
                                    logDebug("[LSP-UNFREEZE-XIAOMI] 成功调用 IGreezeManager." + name + "(uid, reason)");
                                } else if (pts.length == 3 && pts[0] == int.class && pts[1] == int.class && pts[2] == String.class) {
                                    m.invoke(greezerManager, targetUid, 0, "WINDOW_OVERLAY");
                                    logDebug("[LSP-UNFREEZE-XIAOMI] 成功调用 IGreezeManager." + name + "(uid, flag, reason)");
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}

            // 2. 小米 ActionExecute.thaw 官方内核解冻
            try {
                Class<?> actionExecCls = Class.forName("com.android.server.am.ActionExecute");
                for (Method m : actionExecCls.getDeclaredMethods()) {
                    m.setAccessible(true);
                    if (m.getName().equals("thaw")) {
                        Class<?>[] pts = m.getParameterTypes();
                        if (pts.length == 2 && pts[0] == int.class && pts[1] == String.class) {
                            m.invoke(null, targetUid, "WINDOW_OVERLAY");
                            logDebug("[LSP-UNFREEZE-XIAOMI] 成功调用 ActionExecute.thaw(uid, reason)");
                        } else if (pts.length == 1 && pts[0] == int.class) {
                            m.invoke(null, targetUid);
                            logDebug("[LSP-UNFREEZE-XIAOMI] 成功调用 ActionExecute.thaw(uid)");
                        }
                    }
                }
            } catch (Throwable ignored) {}

            // 3. OPPO / 一加 / realme (ColorOS QuickFreeze) 动态解冻
            try {
                Class<?> smCls = Class.forName("android.os.ServiceManager");
                Method getService = smCls.getMethod("getService", String.class);
                String[] oppoServices = {"oplus_quick_freeze", "oplus_process_manager", "quick_freeze"};
                for (String sName : oppoServices) {
                    android.os.IBinder b = (android.os.IBinder) getService.invoke(null, sName);
                    if (b != null) {
                        logDebug("[LSP-UNFREEZE-OPPO] 发现 " + sName + " 冻结管理服务");
                        // 动态反射调用 unfreeze 方法
                    }
                }
            } catch (Throwable ignored) {}

            // 4. vivo / iQOO (OriginOS FrozenManager) 动态解冻
            try {
                Class<?> smCls = Class.forName("android.os.ServiceManager");
                Method getService = smCls.getMethod("getService", String.class);
                String[] vivoServices = {"vivo_frozen_service", "vivo_process_manager"};
                for (String sName : vivoServices) {
                    android.os.IBinder b = (android.os.IBinder) getService.invoke(null, sName);
                    if (b != null) {
                        logDebug("[LSP-UNFREEZE-VIVO] 发现 " + sName + " 冻结管理服务");
                    }
                }
            } catch (Throwable ignored) {}

            // 5. 华为 / 荣耀 (HarmonyOS / MagicOS HwFreeze)
            try {
                Class<?> smCls = Class.forName("android.os.ServiceManager");
                Method getService = smCls.getMethod("getService", String.class);
                android.os.IBinder b = (android.os.IBinder) getService.invoke(null, "hw_freeze_service");
                if (b != null) {
                    logDebug("[LSP-UNFREEZE-HUAWEI] 发现 hw_freeze_service 冻结管理服务");
                }
            } catch (Throwable ignored) {}

            // 6. 三星 (OneUI MARs Policy Manager)
            try {
                Class<?> smCls = Class.forName("android.os.ServiceManager");
                Method getService = smCls.getMethod("getService", String.class);
                android.os.IBinder b = (android.os.IBinder) getService.invoke(null, "mars_service");
                if (b != null) {
                    logDebug("[LSP-UNFREEZE-SAMSUNG] 发现 mars_service 冻结管理服务");
                }
            } catch (Throwable ignored) {}

            // 7. AOSP 原生 CachedAppOptimizer / Process.setProcessFrozen (Android 11 ~ 17 通用)
            try {
                Class<?> processClass = Class.forName("android.os.Process");
                for (Method m : processClass.getDeclaredMethods()) {
                    if (m.getName().equals("setProcessFrozen")) {
                        m.setAccessible(true);
                        if (m.getParameterTypes().length == 3) {
                            m.invoke(null, 0, targetUid, false);
                            logDebug("[LSP-UNFREEZE-AOSP] 成功调用 Process.setProcessFrozen(0, uid, false)");
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        } catch (Throwable throwable) {
            logWarn("[LSP-UNFREEZE-ERROR] 特权解冻执行异常: " + throwable.getMessage(), throwable);
        }
    }

    private static final String SYSTEM_UI_PKG = "com.android.systemui";
    private static final String XMSF_PKG = "com.xiaomi.xmsf";
    private static final String ACTION_SUPER_ISLAND_DIAG = "com.moting.linkgo.action.SUPER_ISLAND_DIAG_EVENT";
    private static final String EXTRA_DIAG_TAG = "extra_diag_tag";
    private static final String EXTRA_DIAG_MESSAGE = "extra_diag_message";
    private boolean systemUiHooksInstalled = false;
    private boolean xmsfHooksInstalled = false;

    @Override
    public void onPackageLoaded(@NonNull PackageLoadedParam param) {
        String pkg = param.getPackageName();
        if (SYSTEM_UI_PKG.equals(pkg)) {
            logDebug("onPackageLoaded: com.android.systemui, installing SystemUI island hooks");
            ClassLoader cl = getClassLoaderFromParam(param);
            installSystemUiHooksWithRetry(cl, 0);
        } else if (XMSF_PKG.equals(pkg)) {
            logDebug("onPackageLoaded: com.xiaomi.xmsf, installing XMSF cloud/permission hooks");
            ClassLoader cl = getClassLoaderFromParam(param);
            installXmsfHooksWithRetry(cl, 0);
        }
    }

    private static ClassLoader getClassLoaderFromParam(PackageLoadedParam param) {
        try {
            Method m = param.getClass().getMethod("getDefaultClassLoader");
            return (ClassLoader) m.invoke(param);
        } catch (Throwable t) {
            try {
                Method m2 = param.getClass().getMethod("getClassLoader");
                return (ClassLoader) m2.invoke(param);
            } catch (Throwable t2) {
                return HookEntry.class.getClassLoader();
            }
        }
    }

    // ── XMSF 监控逻辑 (云端互动与焦点权限查询) ──────────────────

    private void installXmsfHooksWithRetry(ClassLoader classLoader, int attempt) {
        if (xmsfHooksInstalled) return;
        if (installXmsfHooks(classLoader)) {
            xmsfHooksInstalled = true;
            return;
        }
        if (attempt >= 10) return;
        new Handler(Looper.getMainLooper()).postDelayed(
            () -> installXmsfHooksWithRetry(classLoader, attempt + 1),
            1000L
        );
    }

    private boolean installXmsfHooks(ClassLoader classLoader) {
        int hooked = 0;
        try {
            // 1. Hook ContentProvider.call 拦截焦点权限 getFocusPermission
            Class<?> cpClass = Class.forName("android.content.ContentProvider", false, classLoader);
            for (Method m : cpClass.getDeclaredMethods()) {
                if ("call".equals(m.getName()) && m.getParameterTypes().length >= 3) {
                    m.setAccessible(true);
                    hook(m).setId("linkgo_xmsf_cp_call").intercept(chain -> {
                        Object[] args = chain.getArgs().toArray();
                        String method = args.length > 0 && args[0] != null ? args[0].toString() : "";
                        String arg = args.length > 1 && args[1] != null ? args[1].toString() : "";
                        boolean isFocusQuery = "getFocusPermission".equals(method) || method.contains("Focus") || arg.contains("com.moting.linkgo");
                        if (isFocusQuery) {
                            sendIslandDiagBroadcast("XMSF_PERM_REQ", "XMSF 收到焦点权限查询: method=" + method + ", arg=" + arg);
                        }
                        Object result = chain.proceed();
                        if (isFocusQuery) {
                            String resDesc = result != null ? result.toString() : "null";
                            if (result instanceof android.os.Bundle) {
                                android.os.Bundle b = (android.os.Bundle) result;
                                resDesc = "Bundle{result=" + b.getInt("result", -999) + ", keys=" + b.keySet() + "}";
                            }
                            sendIslandDiagBroadcast("XMSF_PERM_RESP", "XMSF 焦点权限查询响应: " + resDesc);
                        }
                        return result;
                    });
                    hooked++;
                    break;
                }
            }
        } catch (Throwable t) {
            logWarn("hook ContentProvider in XMSF failed", t);
        }

        try {
            // 2. Hook URLConnection 监控 XMSF 与小米云端的网络互动
            Class<?> urlCls = Class.forName("java.net.URL", false, classLoader);
            for (Method m : urlCls.getDeclaredMethods()) {
                if ("openConnection".equals(m.getName())) {
                    m.setAccessible(true);
                    hook(m).setId("linkgo_xmsf_url_conn").intercept(chain -> {
                        Object urlObj = chain.getThisObject();
                        String urlStr = urlObj != null ? urlObj.toString() : "";
                        if (urlStr.contains("xiaomi") || urlStr.contains("miui") || urlStr.contains("xmpush") || urlStr.contains("focus")) {
                            sendIslandDiagBroadcast("XMSF_CLOUD_REQ", "XMSF 发起云端网络请求: " + urlStr);
                        }
                        try {
                            return chain.proceed();
                        } catch (Throwable netErr) {
                            sendIslandDiagBroadcast("XMSF_CLOUD_BLOCKED", "XMSF 云端请求受阻 (断网生效): " + netErr.getClass().getSimpleName() + " - " + netErr.getMessage());
                            throw netErr;
                        }
                    });
                    hooked++;
                    break;
                }
            }
        } catch (Throwable t) {
            logWarn("hook URLConnection in XMSF failed", t);
        }

        sendIslandDiagBroadcast("XMSF_INIT", "LSPosed 成功注入 XMSF 进程 (Hooked: " + hooked + ")");
        return hooked > 0;
    }

    // ── SystemUI 监控逻辑 (深度堆栈与丢弃点追踪) ───────────────

    private void installSystemUiHooksWithRetry(ClassLoader classLoader, int attempt) {
        if (systemUiHooksInstalled) return;
        if (installSystemUiHooks(classLoader)) {
            systemUiHooksInstalled = true;
            return;
        }
        if (attempt >= 10) return;
        new Handler(Looper.getMainLooper()).postDelayed(
            () -> installSystemUiHooksWithRetry(classLoader, attempt + 1),
            1000L
        );
    }

    private boolean installSystemUiHooks(ClassLoader classLoader) {
        int hooked = 0;
        try {
            // 1. Hook BaseBundle.getString，精准捕获 miui.focus.param 的业务堆栈
            Class<?> bundleClass = Class.forName("android.os.BaseBundle", false, classLoader);
            for (Method m : bundleClass.getDeclaredMethods()) {
                if ("getString".equals(m.getName()) && m.getParameterTypes().length == 1 && m.getParameterTypes()[0] == String.class) {
                    m.setAccessible(true);
                    hook(m).setId("linkgo_island_bundle_getString").intercept(chain -> {
                        Object key = chain.getArgs().get(0);
                        Object result = chain.proceed();
                        if ("miui.focus.param".equals(key) && result instanceof String) {
                            String str = (String) result;
                            if (str.contains("LinkGo") || str.contains("com.moting.linkgo") || str.contains("param_island")) {
                                String stack = getDeepStackTraceSummary();
                                sendIslandDiagBroadcast("SYSTEM_UI_PARSE", "SystemUI 读取 LinkGo 超级岛参数，业务调用栈:\n" + stack);
                            }
                        }
                        return result;
                    });
                    hooked++;
                    break;
                }
            }
        } catch (Throwable t) {
            logWarn("hook BaseBundle in SystemUI failed", t);
        }

        try {
            // 2. Hook NotificationListenerService / 通知进入 SystemUI 入口
            Class<?> nlsClass = Class.forName("android.service.notification.NotificationListenerService", false, classLoader);
            for (Method m : nlsClass.getDeclaredMethods()) {
                if ("onNotificationPosted".equals(m.getName())) {
                    m.setAccessible(true);
                    hook(m).setId("linkgo_island_nls_posted").intercept(chain -> {
                        Object[] args = chain.getArgs().toArray();
                        if (args.length > 0 && args[0] != null) {
                            String desc = args[0].toString();
                            if (desc.contains("com.moting.linkgo")) {
                                String shortDesc = desc.length() > 120 ? desc.substring(0, 120) : desc;
                                sendIslandDiagBroadcast("SYSTEM_UI_NOTIF_POSTED", "SystemUI 收到 LinkGo 系统通知: " + shortDesc);
                            }
                        }
                        return chain.proceed();
                    });
                    hooked++;
                    break;
                }
            }
        } catch (Throwable t) {
            logWarn("hook NotificationListenerService in SystemUI failed", t);
        }

        // 3. 探测并 Hook SystemUI 焦点通知管理与过滤候选类 (精确对齐 HyperOS 真实类名)
        String[] candidateClasses = new String[] {
            "miui.systemui.notification.focus.FocusNotifPreHandler",
            "miui.systemui.notification.focus.FocusNotificationController",
            "miui.systemui.notification.FocusNotificationPluginImpl",
            "miui.systemui.notification.focus.FocusNotifUtils",
            "miui.systemui.dynamicisland.DynamicIslandUtils",
            "miui.systemui.dynamicisland.events.DynamicIslandExposureManager",
            "com.android.systemui.statusbar.notification.focus.FocusNotificationManager",
            "com.android.systemui.statusbar.notification.focus.FocusNotificationController",
            "com.android.systemui.statusbar.notification.focus.FocusNotificationHelper",
            "com.android.systemui.statusbar.notification.focus.IslandNotificationHelper"
        };

        for (String className : candidateClasses) {
            try {
                Class<?> clazz = Class.forName(className, false, classLoader);
                for (Method m : clazz.getDeclaredMethods()) {
                    m.setAccessible(true);
                    String mName = m.getName().toLowerCase();
                    if (mName.contains("focus") || mName.contains("island") || mName.contains("allow") || mName.contains("filter") || mName.contains("drop")) {
                        hook(m).setId("linkgo_island_" + clazz.getSimpleName() + "_" + m.getName()).intercept(chain -> {
                            String argsDesc = "";
                            try {
                                Object[] args = chain.getArgs().toArray();
                                argsDesc = summarizeArgs(args);
                                if (argsDesc.contains("com.moting.linkgo") || argsDesc.contains("LinkGo")) {
                                    sendIslandDiagBroadcast("SYSTEM_UI_METHOD_IN", "进入 " + clazz.getSimpleName() + "." + m.getName() + " 入参: " + argsDesc);
                                }
                            } catch (Throwable ignored) {}
                            Object res = chain.proceed();
                            try {
                                if (argsDesc.contains("com.moting.linkgo") || argsDesc.contains("LinkGo")) {
                                    sendIslandDiagBroadcast("SYSTEM_UI_METHOD_OUT", clazz.getSimpleName() + "." + m.getName() + " 返回: " + res);
                                }
                            } catch (Throwable ignored) {}
                            return res;
                        });
                        hooked++;
                    }
                }
                sendIslandDiagBroadcast("SYSTEM_UI_INIT", "LSPosed 成功注入 SystemUI 类: " + className);
            } catch (ClassNotFoundException ignored) {
            } catch (Throwable t) {
                logWarn("hook candidate " + className + " failed", t);
            }
        }

        return hooked > 0;
    }

    private String summarizeArgs(Object[] args) {
        if (args == null || args.length == 0) return "()";
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            Object a = args[i];
            if (a == null) {
                sb.append("null");
            } else {
                String str = a.toString();
                if (str.length() > 80) {
                    str = str.substring(0, 80);
                }
                sb.append(a.getClass().getSimpleName()).append(": ").append(str);
            }
        }
        sb.append(")");
        return sb.toString();
    }

    /**
     * 过滤所有 LSPosed/代理框架栈，提取至少 15 层真实业务调用堆栈
     */
    private String getDeepStackTraceSummary() {
        StackTraceElement[] trace = Thread.currentThread().getStackTrace();
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (StackTraceElement elem : trace) {
            String cName = elem.getClassName();
            // 过滤 Hook 框架与反射层
            if (cName.contains("io.github.libxposed") ||
                cName.contains("com.moting.linkgo.hook") ||
                cName.contains("dalvik.system") ||
                cName.contains("java.lang.reflect") ||
                cName.contains("java.lang.Thread")) {
                continue;
            }
            sb.append("  at ").append(elem.getClassName()).append(".").append(elem.getMethodName())
              .append("(").append(elem.getFileName()).append(":").append(elem.getLineNumber()).append(")\n");
            count++;
            if (count >= 15) break;
        }
        return sb.toString().trim();
    }

    // ── 应用内链接捕获（ActivityStarter）──────────────────────

    private void installAppLinkCaptureHooksWithRetry(ClassLoader classLoader, String source, int attempt) {
        if (appLinkHooksInstalled) return;
        if (installAppLinkCaptureHooks(classLoader, source + ", attempt=" + attempt)) return;
        if (attempt >= INSTALL_RETRY_LIMIT) return;
        new Handler(Looper.getMainLooper()).postDelayed(
            () -> installAppLinkCaptureHooksWithRetry(classLoader, source, attempt + 1),
            INSTALL_RETRY_DELAY_MILLIS
        );
    }

    /**
     * 安装 ActivityStarter 捕获钩子：
     * 拦截「应用启动自己内置浏览器 Activity」的启动请求（component 包名 == 调用者包名 且 data 为 http/https），
     * 按捕获模式（拦截/询问）决定放行与广播。
     * 候选类覆盖 AOSP 与各定制 ROM（HyperOS 的 ActivityStarterImpl / android.miui.ActivityStarter），
     * 方法匹配放宽为「返回 int 的 execute / startActivityMayWait / startActivity」，参数从 1~4 个中反射定位 Intent。
     */
    private boolean installAppLinkCaptureHooks(ClassLoader classLoader, String source) {
        if (appLinkHooksInstalled) return true;
        logDebug("installing ActivityStarter capture hooks, source=" + source);
        appLinkInstallStatus = "installing...";
        try {
            boolean hooked = false;
            for (String className : ACTIVITY_STARTER_CLASSES) {
                Class<?> clazz = null;
                try {
                    clazz = Class.forName(className, false, classLoader);
                } catch (ClassNotFoundException ignored) {
                    logDebug("starter class not found: " + className);
                    sendIslandDiagBroadcast("APP_LINK_INSTALL", "类未找到: " + className);
                    appLinkInstallStatus = "类未找到: " + className;
                    continue;
                } catch (Throwable t) {
                    logWarn("load starter class failed: " + className, t);
                    continue;
                }
                for (Method m : clazz.getDeclaredMethods()) {
                    String n = m.getName();
                    // 放宽匹配：startActivity 系列（含 AsUser/WithOptions 等 Binder 入口）+
                    // execute 系列 + startActivityInner/MayWait，覆盖更多未被其他模块占用的入口
                    boolean match = n.startsWith("startActivity")
                        || n.equals("execute") || n.startsWith("execute")
                        || n.equals("startActivityInner")
                        || n.equals("startActivityMayWait");
                    if (!match) continue;
                    int pc = m.getParameterTypes().length;
                    if (pc < 1 || pc > 12) continue;
                    try {
                        m.setAccessible(true);
                        // id 必须唯一：同方法名多重载共用 id 会导致 libxposed 拒绝/冲突
                        String id = "linkgo_applink_" + clazz.getSimpleName() + "_" + n + "_" + pc;
                        hook(m).setId(id).intercept(chain -> handleActivityStart(chain));
                        hooked = true;
                        logDebug("hooked " + className + "." + n + ": " + m.toGenericString());
                    } catch (Throwable t) {
                        // 单个方法 hook 失败（如已被其他模块占用）不拖垮整体，记录后继续尝试其他方法
                        logWarn("hook method failed: " + className + "." + n, t);
                        appLinkInstallStatus = "hook失败: " + className + "." + n + " -> " + t.getClass().getSimpleName();
                    }
                }
            }

            // ActivityRecord 构造观察钩子：检测到 Activity（构造必经）→ 取 activityIntentUri → 提取链接。
            // 构造器返回 void 不可拦截，负责「询问」观察与「拦截未生效」的兜底记录。
            try {
                Class<?> arClass = Class.forName("com.android.server.wm.ActivityRecord", false, classLoader);
                for (java.lang.reflect.Constructor<?> ctor : arClass.getDeclaredConstructors()) {
                    boolean hasIntent = false;
                    for (Class<?> pt : ctor.getParameterTypes()) {
                        if (pt == Intent.class) {
                            hasIntent = true;
                            break;
                        }
                    }
                    if (!hasIntent) continue;
                    try {
                        ctor.setAccessible(true);
                        hook(ctor).setId("linkgo_applink_activityrecord_init").intercept(chain -> handleActivityRecordCreated(chain));
                        activityRecordHooked = true;
                        logDebug("hooked ActivityRecord.<init>: " + ctor.toGenericString());
                    } catch (Throwable t) {
                        logWarn("hook ActivityRecord.<init> failed", t);
                        appLinkInstallStatus = "hook失败: ActivityRecord.<init> -> " + t.getClass().getSimpleName();
                    }
                }
                if (!activityRecordHooked) {
                    logDebug("ActivityRecord 无含 Intent 的构造器");
                    appLinkInstallStatus = "ActivityRecord 无含 Intent 构造器";
                }
            } catch (ClassNotFoundException ignored) {
                logDebug("ActivityRecord class not found");
                appLinkInstallStatus = "类未找到: ActivityRecord";
            } catch (Throwable t) {
                logWarn("load ActivityRecord failed", t);
            }

            appLinkHooksInstalled = hooked;
            // 无论钩子是否装上，都注册配置同步接收器，保证 captureMode 同步链路始终可用
            registerAppLinkReceiversWithRetry(0);
            appLinkInstallStatus = "installed=" + hooked + ", activityRecord=" + activityRecordHooked;
            writeInstallStatusToPrefs();
            String resultMsg = "ActivityStarter capture hooks installed: " + hooked + ", activityRecord=" + activityRecordHooked + ", source=" + source;
            logDebug(resultMsg);
            sendIslandDiagBroadcast("APP_LINK_INSTALL", resultMsg);
            return hooked;
        } catch (Throwable t) {
            logWarn("Failed to install ActivityStarter capture hooks", t);
            appLinkInstallStatus = "安装异常: " + t.getClass().getSimpleName();
            sendIslandDiagBroadcast("APP_LINK_INSTALL", "安装异常: " + t);
            return false;
        }
    }

    /**
     * Activity 启动回调（热路径，必须极轻量：仅反射判包名/取 URL，不阻塞）。
     * 诊断期保留关键判定日志，便于定位静默失败环节。
     */
    private Object handleActivityStart(io.github.libxposed.api.XposedInterface.Chain chain) throws Throwable {
        try {
            java.util.List<Object> args = chain.getArgs();
            if (args == null || args.isEmpty()) return chain.proceed();

            Object request = args.get(0);
            Intent intent = findIntentInArgs(args);
            int mode = captureMode;
            boolean canIntercept = isIntReturningMethod(chain);

            String url = extractHttpLink(intent);

            // 分享链路（把内容/链接「发给」别的应用，如「B站分享到微信」）不是「应用内打开链接」，
            // 一律放行：否则分享这一步会被本模块吞掉（实测：微信转发的分享载荷会被当成微信打开链接）
            String shareReason = detectSharePayload(intent);
            if (!shareReason.isEmpty()) {
                logDebug("[APP-LINK-SHARE-PASS] 分享链路放行(" + shareReason + "): url=" + url);
                return chain.proceed();
            }

            // 发起方（referrer）只认权威来源：读不到即视为「来源不可得」→ isCaptureApp(null)=false → 放行，
            // 由应用自己的内置浏览器打开该链接（宁可放行，不可猜错后劫持）。
            StringBuilder referrerSource = new StringBuilder();
            String callingPkg = readReferrer(request, args, intent, findSystemContextCached(), referrerSource);
            boolean internal = isInternalAppOpen(callingPkg, intent);

            // 诊断日志：仅对潜在目标（含 http/https 或应用内打开）打印，避免普通启动刷屏
            if (url != null || internal) {
                String toUri = "";
                try {
                    if (intent != null) toUri = intent.toUri(0);
                } catch (Throwable ignored) {
                }
                logDebug("[APP-LINK-DBG] req=" + (request == null ? "null" : request.getClass().getSimpleName())
                    + " act=" + (intent == null ? "null" : String.valueOf(intent.getAction()))
                    + " dat=" + (intent == null || intent.getData() == null ? "null" : intent.getData().toString())
                    + " cmp=" + (intent == null || intent.getComponent() == null ? "null" : intent.getComponent().flattenToShortString())
                    + " referrer=" + callingPkg + "(" + referrerSource + ")" + " mode=" + mode
                    + (toUri.isEmpty() ? "" : " uri=" + toUri));
            }

            if (url == null) {
                return chain.proceed();
            }
            // 单次放行：「内置打开」重放防死循环——命中一次即清除
            if (url.equals(singlePassUrl)) {
                singlePassUrl = null;
                logDebug("[APP-LINK-DBG] 单次放行命中，放行: " + url);
                return chain.proceed();
            }
            // 分发放行窗口已移除：该窗口按 URL 字面量在 5 秒内一律放行，会把用户短时间内
            // 重复打开的同一条链接误放行（微信直接内置打开，不再捕获）。
            // 分发后可能产生的循环改由下面的「规则目标即发起应用」判据负责。

            if (!appLinkReceiversRegistered) {
                Context sysCtx = findSystemContextCached();
                if (sysCtx != null) {
                    logDebug("[APP-LINK-DBG] 运行时触发接收器补救注册");
                    registerAppLinkReceivers(sysCtx, 999);
                }
            }

            // 仅接管列表内的来源应用才处理（其余应用一律放行）
            if (!isCaptureApp(callingPkg)) {
                logDebug("[APP-LINK-JUDGE-PASS] 来源应用未接管，放行: caller=" + callingPkg
                    + ", 当前接管应用=" + java.util.Arrays.toString(captureApps));
                return chain.proceed();
            }

            // 断环：该链接的跳转规则目标应用正是本次发起应用 → 这条链接本就归它打开，放行不捕获
            if (isRuleTargetSelf(callingPkg, url)) {
                logDebug("[APP-LINK-RULE-PASS] 规则目标即发起应用，放行: url=" + url + ", pkg=" + callingPkg);
                return chain.proceed();
            }

            // 目标为 LinkGo 自身则放行（防自身分发被循环拦截）
            if (isTargetLinkGo(intent)) {
                logDebug("[APP-LINK-DBG] 目标为 LinkGo 自身，放行: " + url);
                return chain.proceed();
            }

            if (isExemptUrl(callingPkg, url)) {
                logDebug("[APP-LINK-JUDGE-EXEMPT] 命中放行域名，放行: url=" + url + ", caller=" + callingPkg
                    + ", 当前放行规则=" + java.util.Arrays.toString(exemptRules));
                return chain.proceed();
            }

            // 仅命中规则时拦截（直接拦截 / 弹出询问共用）：未命中任何跳转规则（本该走备选浏览器）
            // → 一律放行，既不中止内置浏览器启动，也不弹询问胶囊。
            if (mode != 0 && ruleOnlyIntercept && !hasMatchedDispatchRule(url)) {
                logDebug("[APP-LINK-RULE-ONLY-PASS] 未命中跳转规则，放行: url=" + url
                    + ", caller=" + callingPkg + ", mode=" + mode);
                return chain.proceed();
            }

            if (mode == 1) {
                if (canIntercept) {
                    // 多方法 hook 会对同一次启动重复回调：先去重，重复回调沿用第一次已注册的超时回退
                    if (isDuplicateBroadcast(url)) {
                        logDebug("[APP-LINK-DEDUP] 500ms 内相同 URL 已处理，跳过: " + url);
                        return 0;
                    }
                    logDebug("[APP-LINK-INTERCEPT] 拦截内置浏览器启动: url=" + url + ", callingPkg=" + callingPkg);
                    // 缓存完整 Intent，供「内置打开」胶囊与超时回退重放
                    cacheLinkIntent(intent, url, callingPkg);
                    // 顺序不可颠倒：先注册超时回退、再广播给主进程。
                    // 反过来的话，主进程的同步回执会早于定时器注册到达，回退将永远等不到取消。
                    String token = armBuiltinFallback(url);
                    broadcastAppLink(url, callingPkg, token);
                    return 0; // START_SUCCESS：让发起方无感知启动已中止
                }
                // 非 int 返回的入口（观察型 hook）：无法安全伪造返回值，仅记录不拦截
                logDebug("[APP-LINK-INTERCEPT-SKIP] 非 int 返回入口不可拦截，仅记录: url=" + url);
            } else if (mode == 2) {
                if (isDuplicateBroadcast(url)) {
                    logDebug("[APP-LINK-DEDUP] 500ms 内相同 URL 已处理，跳过: " + url);
                    return chain.proceed();
                }
                logDebug("[APP-LINK-ASK] 询问模式，内置浏览器照常打开并通知: url=" + url + ", callingPkg=" + callingPkg);
                broadcastAppLink(url, callingPkg, null);
            } else {
                logDebug("[APP-LINK-DBG] mode=0(关闭)，捕获后仅记录不处理: url=" + url);
            }
        } catch (Throwable t) {
            logWarn("app link capture callback failed", t);
        }
        return chain.proceed();
    }

    /** 当前被 hook 的方法是否为 int 返回（仅 int 返回的启动入口可安全伪造返回值实现拦截）。 */
    private static boolean isIntReturningMethod(io.github.libxposed.api.XposedInterface.Chain chain) {
        try {
            java.lang.reflect.Executable e = chain.getExecutable();
            return e instanceof Method && ((Method) e).getReturnType() == int.class;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * ActivityRecord 构造回调：检测到 Activity 创建（每次启动必经）后，
     * 取 activityIntentUri（intent.toUri()）并提取 http/https 链接值。
     * 构造器不可拦截，仅观察：询问模式广播通知；拦截模式若走到此处说明拦截未生效，降级广播兜底。
     */
    private Object handleActivityRecordCreated(io.github.libxposed.api.XposedInterface.Chain chain) throws Throwable {
        String callingPkg = null;
        String url = null;
        try {
            java.util.List<Object> args = chain.getArgs();
            if (args == null || args.isEmpty()) return chain.proceed();
            Intent intent = findIntentInArgs(args);
            if (intent == null) return chain.proceed();

            // 分享链路不接管，与 handleActivityStart 同一判据
            String shareReason = detectSharePayload(intent);
            if (!shareReason.isEmpty()) {
                logDebug("[APP-LINK-SHARE-PASS] ActivityRecord 观察路径：分享链路放行(" + shareReason + ")");
                return chain.proceed();
            }

            // 发起方（referrer）只认权威来源：读不到即视为「来源不可得」→ 不接管（放行内置浏览器打开）
            StringBuilder referrerSource = new StringBuilder();
            callingPkg = readReferrer(null, args, intent, findSystemContextCached(), referrerSource);

            // activityIntentUri = intent.toUri(0)，完整序列化（含 dat 与 extras）
            String toUri = "";
            try {
                toUri = intent.toUri(0);
            } catch (Throwable ignored) {
            }
            logDebug("[APP-LINK-RECORD] activityIntentUri=" + toUri
                + " referrer=" + callingPkg + "(" + referrerSource + ")" + " mode=" + captureMode);

            url = extractHttpLink(intent);
            if (url == null) {
                logDebug("[APP-LINK-RECORD] 未提取到 http/https 链接");
                return chain.proceed();
            }

            // 仅接管列表内的来源应用才处理
            if (!isCaptureApp(callingPkg)) {
                return chain.proceed();
            }

            // 断环：该链接的跳转规则目标应用正是本次发起应用 → 放行不捕获
            if (isRuleTargetSelf(callingPkg, url)) {
                logDebug("[APP-LINK-RECORD-RULE-PASS] 规则目标即发起应用，放行: url=" + url + ", pkg=" + callingPkg);
                return chain.proceed();
            }

            // 目标为 LinkGo 自身则放行
            if (isTargetLinkGo(intent)) {
                return chain.proceed();
            }

            if (isExemptUrl(callingPkg, url)) {
                logDebug("[APP-LINK-RECORD] 放行域名，放行: url=" + url + ", caller=" + callingPkg);
                return chain.proceed();
            }

            // 单次放行：「内置打开」重放防死循环——命中一次即清除
            if (url.equals(singlePassUrl)) {
                singlePassUrl = null;
                logDebug("[APP-LINK-RECORD-PASS] 单次放行命中，内置浏览器照常打开: " + url);
                return chain.proceed();
            }
            // 分发放行窗口已移除，理由同 handleActivityStart：避免把用户重复打开的同链接误放行。

            int mode = captureMode;
            // 仅命中规则时拦截（两种子模式共用）：未命中规则即不接管，也不弹询问胶囊、不做降级广播
            if (mode != 0 && ruleOnlyIntercept && !hasMatchedDispatchRule(url)) {
                logDebug("[APP-LINK-RULE-ONLY-PASS] 未命中跳转规则，放行: url=" + url
                    + ", caller=" + callingPkg + ", mode=" + mode);
                return chain.proceed();
            }
            if (mode == 2) {
                lastAskActivityUrl = url;
                lastAskActivityPkg = callingPkg;
                lastAskActivityTime = System.currentTimeMillis();
                logDebug("[APP-LINK-ASK-RECORD] 询问模式通知: url=" + url + ", caller=" + callingPkg);
                if (!isDuplicateBroadcast(url)) {
                    broadcastAppLink(url, callingPkg, null);
                }
            } else if (mode == 1) {
                // 拦截由启动入口 hook 完成；走到这里说明拦截未生效（内置浏览器已打开），
                // 降级为广播（至少让用户可感知）。此处不注册超时回退，否则会重复打开内置页面。
                logDebug("[APP-LINK-RECORD] 拦截模式但 ActivityRecord 已创建（拦截未生效）: url=" + url);
                if (!isDuplicateBroadcast(url)) {
                    broadcastAppLink(url, callingPkg, null);
                }
            } else {
                logDebug("[APP-LINK-RECORD] mode=0(关闭)，仅记录: url=" + url);
            }
        } catch (Throwable t) {
            logWarn("activity record callback failed", t);
        }
        Object result = chain.proceed();
        Object recordObj = chain.getThisObject();
        if (recordObj != null) {
            if (captureMode == 2) {
                lastAskActivityRecord = new java.lang.ref.WeakReference<>(recordObj);
                lastAskActivityUrl = url;
                lastAskActivityPkg = callingPkg;
                lastAskActivityTime = System.currentTimeMillis();
                logDebug("[APP-LINK-ASK-RECORD-SAVED] 询问模式成功缓存待销毁 ActivityRecord: " + recordObj
                    + ", url=" + url + ", pkg=" + callingPkg);
            } else if (lastAskActivityPkg != null && lastAskActivityPkg.equals(callingPkg)
                && (System.currentTimeMillis() - lastAskActivityTime < 3500L)) {
                // 典型场景：微信等应用先经由 WebViewStubProxyUI，再拉起真正的内置浏览器 MMWebViewUI
                // 在 3.5 秒内同一包名创建的新 ActivityRecord 顺延更新，确保销毁的是最顶层真实页面
                lastAskActivityRecord = new java.lang.ref.WeakReference<>(recordObj);
                lastAskActivityTime = System.currentTimeMillis();
                logDebug("[APP-LINK-ASK-RECORD-UPDATE] 顺延更新同包名栈顶 ActivityRecord: " + recordObj);
            }
        }
        return result;
    }

    private void registerAppLinkReceiversWithRetry(int attempt) {
        if (appLinkReceiversRegistered) return;
        if (registerAppLinkReceivers(findSystemContext(), attempt)) return;
        if (attempt >= CLEAR_RECEIVER_RETRY_LIMIT) return;
        new Handler(Looper.getMainLooper()).postDelayed(
            () -> registerAppLinkReceiversWithRetry(attempt + 1),
            CLEAR_RECEIVER_RETRY_DELAY_MILLIS
        );
    }

    /**
     * 注册「内置打开」与「配置同步」接收器：
     * 主进程点击提示胶囊 → 临时放行；主进程启动/改设置 → 同步捕获模式。
     */
    private boolean registerAppLinkReceivers(Context context, int attempt) {
        if (appLinkReceiversRegistered) return true;
        if (context == null) {
            logDebug("[APP-LINK-RECV-REG] context not ready, attempt=" + attempt);
            return false;
        }
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(AppLinkHookContract.ACTION_OPEN_IN_BUILTIN);
            filter.addAction(AppLinkHookContract.ACTION_LINK_HANDLED);
            filter.addAction(AppLinkHookContract.ACTION_SYNC_CAPTURE_CONFIG);
            filter.addAction(AppLinkHookContract.ACTION_FINISH_LINK_ACTIVITY);
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    String action = intent.getAction();
                    logDebug("[APP-LINK-RECV-EVENT] 收到广播: " + action);
                    if (AppLinkHookContract.ACTION_OPEN_IN_BUILTIN.equals(action)) {
                        String url = intent.getStringExtra(AppLinkHookContract.EXTRA_LINK_URL_REF);
                        logDebug("[APP-LINK-REPLAY-REQ] 收到「内置打开」请求: url=" + (url == null ? "null" : url));
                        replayCachedIntent(url);
                    } else if (AppLinkHookContract.ACTION_LINK_HANDLED.equals(action)) {
                        String token = intent.getStringExtra(AppLinkHookContract.EXTRA_LINK_TOKEN);
                        if (token != null && token.equals(pendingFallbackToken)) {
                            cancelPendingFallback();
                            logDebug("[APP-LINK-HANDLED] 主进程已接管，取消超时回退: token=" + token);
                        } else {
                            // 迟到回执：回退已经发生（或已被去重覆盖），不再撤销已放行的内置页面
                            logDebug("[APP-LINK-HANDLED-LATE] 回执无对应待回退项，按已放行处理: token=" + token);
                        }
                    } else if (AppLinkHookContract.ACTION_FINISH_LINK_ACTIVITY.equals(action)) {
                        String url = intent.getStringExtra(AppLinkHookContract.EXTRA_TARGET_URL);
                        String pkg = intent.getStringExtra(AppLinkHookContract.EXTRA_TARGET_PACKAGE);
                        logDebug("[APP-LINK-FINISH-REQ] 收到「销毁原页面」请求: url=" + url + ", pkg=" + pkg);
                        finishLastAskActivity(url, pkg);
                    } else if (AppLinkHookContract.ACTION_SYNC_CAPTURE_CONFIG.equals(action)) {
                        captureMode = intent.getIntExtra(AppLinkHookContract.EXTRA_CAPTURE_MODE, 0);
                        ruleOnlyIntercept = intent.getBooleanExtra(AppLinkHookContract.EXTRA_RULE_ONLY_INTERCEPT, false);
                        String exemptJson = intent.getStringExtra(AppLinkHookContract.EXTRA_EXEMPT_DOMAINS);
                        if (exemptJson != null && !exemptJson.isEmpty()) {
                            int before = exemptRules == null ? 0 : exemptRules.length;
                            updateExemptRules(exemptJson);
                            logDebug("[APP-LINK-SYNC] 放行规则更新: " + before + " -> "
                                + (exemptRules == null ? 0 : exemptRules.length) + " 条, 放行规则=" + java.util.Arrays.toString(exemptRules));
                        }
                        String appsJson = intent.getStringExtra(AppLinkHookContract.EXTRA_CAPTURE_APPS);
                        if (appsJson != null && !appsJson.isEmpty()) {
                            int before = captureApps == null ? 0 : captureApps.length;
                            updateCaptureApps(appsJson);
                            logDebug("[APP-LINK-SYNC] 接管应用更新: " + before + " -> "
                                + (captureApps == null ? 0 : captureApps.length) + " 个, 接管应用=" + java.util.Arrays.toString(captureApps));
                        }
                        String dispatchRulesJson = intent.getStringExtra(AppLinkHookContract.EXTRA_DISPATCH_RULES);
                        if (dispatchRulesJson != null && !dispatchRulesJson.isEmpty()) {
                            int before = dispatchRules == null ? 0 : dispatchRules.length;
                            updateDispatchRules(dispatchRulesJson);
                            logDebug("[APP-LINK-SYNC] 跳转规则更新: " + before + " -> "
                                + (dispatchRules == null ? 0 : dispatchRules.length) + " 条");
                        }
                        logDebug("[APP-LINK-SYNC] 同步捕获配置完成: mode=" + captureMode
                            + ", ruleOnlyIntercept=" + ruleOnlyIntercept
                            + ", 兜底规则=" + hasCatchAllRule
                            + ", hooksInstalled=" + appLinkHooksInstalled + ", status=" + appLinkInstallStatus);
                        // 主进程此时必然存活，趁机把安装状态写入远程偏好（主进程可直接读取）
                        writeInstallStatusToPrefs();
                        // 另走一条显式广播回报同步结果：远程偏好在 system_server 作用域下写入不生效，
                        // 设置页的同步状态提示依赖这条通道
                        reportSyncStatusToMain();
                    }
                }
            };

            boolean registered = false;
            try {
                context.registerReceiver(appLinkReceiverInstance = receiver, filter, null, null, Context.RECEIVER_EXPORTED);
                registered = true;
                logDebug("[APP-LINK-RECV-REG] 带 RECEIVER_EXPORTED 注册成功 (attempt=" + attempt + ")");
            } catch (Throwable t) {
                logWarn("[APP-LINK-RECV-REG] 带 RECEIVER_EXPORTED 注册失败，尝试普通注册: " + t.getMessage(), t);
                try {
                    context.registerReceiver(appLinkReceiverInstance = receiver, filter);
                    registered = true;
                    logDebug("[APP-LINK-RECV-REG] 普通注册成功 (attempt=" + attempt + ")");
                } catch (Throwable t2) {
                    logWarn("[APP-LINK-RECV-REG] 普通注册亦失败", t2);
                }
            }

            if (registered) {
                appLinkReceiversRegistered = true;
                // 注册就绪后，主动向主进程拉取最新配置，确保 Hook 内存不滞后
                requestConfigResync();
                return true;
            }
            return false;
        } catch (Throwable t) {
            logWarn("[APP-LINK-RECV-REG-FAIL] register app link receivers failed, attempt=" + attempt, t);
            return false;
        }
    }

    /**
     * 把钩子安装状态写入 libxposed 远程偏好（system_server → LinkGo 应用进程），
     * 主进程可直接 getSharedPreferences("linkgo_capture") 读取，绕开 logcat 缓冲被冲刷问题。
     */
    private void writeInstallStatusToPrefs() {
        try {
            android.content.SharedPreferences prefs = getRemotePreferences("linkgo_capture");
            if (prefs == null) return;
            int rulesCount = dispatchRules == null ? 0 : dispatchRules.length;
            prefs.edit()
                .putBoolean("hook_installed", appLinkHooksInstalled)
                .putBoolean("activity_record_hooked", activityRecordHooked)
                .putString("install_status", appLinkInstallStatus)
                // 规则表实际生效条数与完成时间：主进程设置页据此展示同步状态，
                // 也是判断「Hook 端是否真的收到了规则列表」的唯一可靠依据
                .putInt("rules_count", rulesCount)
                .putLong("synced_at", System.currentTimeMillis())
                .apply();
            logDebug("[APP-LINK-PREFS] 安装状态已写入远程偏好: " + appLinkInstallStatus
                + ", 规则条数=" + rulesCount);
        } catch (Throwable t) {
            logWarn("write install status to prefs failed", t);
        }
    }

    /**
     * 把本次同步的实际生效结果回报给主进程（显式定向广播）。
     *
     * 主进程转发给 LinkIntentReceiver 后落盘，供设置页展示「规则列表是否已同步」。
     * 不用 libxposed 远程偏好的原因：`getRemotePreferences` 在 system_server 作用域下实测不生效。
     */
    private void reportSyncStatusToMain() {
        try {
            Context context = findSystemContextCached();
            if (context == null) return;
            int rulesCount = dispatchRules == null ? 0 : dispatchRules.length;
            Intent intent = new Intent(AppLinkHookContract.ACTION_LINK_SYNC_STATUS)
                .setComponent(new ComponentName(
                    AppLinkHookContract.APPLICATION_ID,
                    AppLinkHookContract.RECEIVER_CLASS))
                .putExtra(AppLinkHookContract.EXTRA_RULES_COUNT, rulesCount)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND | Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            context.sendBroadcast(intent);
            logDebug("[APP-LINK-SYNC-REPORT] 已回报同步状态: 规则条数=" + rulesCount);
        } catch (Throwable t) {
            logWarn("report sync status failed", t);
        }
    }

    /** 缓存的系统 Context（热路径避免反复反射 ActivityThread）。 */
    private static volatile Context systemContextCache = null;

    private static Context findSystemContextCached() {
        Context c = systemContextCache;
        if (c == null) {
            c = findSystemContext();
            systemContextCache = c;
        }
        return c;
    }

    /**
     * 从方法参数中定位 Intent：优先直接找 Intent 实例，其次从 Request 结构反射读取。
     */
    private static Intent findIntentInArgs(java.util.List<Object> args) {
        if (args == null) return null;
        for (Object a : args) {
            if (a instanceof Intent) return (Intent) a;
        }
        for (Object a : args) {
            if (a != null && a.getClass().getName().endsWith(".Request")) {
                Intent i = readRequestIntent(a);
                if (i != null) return i;
            }
        }
        return null;
    }

    /** 发起方（referrer）字段候选：AOSP 为 launchedFromPackage，部分 ROM/框架提供 mReferrer。 */
    private static final String[] REFERRER_FIELD_CANDIDATES = {
        "mReferrer", "launchedFromPackage", "mLaunchedFromPackage", "referrerPackage"
    };

    /**
     * 解析本次启动的发起方包名（referrer），按可信度依次尝试：
     * 1. Request / ActivityRecord 上的 referrer 字段（mReferrer / launchedFromPackage）；
     * 2. Request 的 callingPackage 系列字段；
     * 3. 实参结构中的 callingPackage（紧跟 (callingPid, callingUid) 或紧邻 Intent 之前）；
     * 4. Intent.EXTRA_REFERRER（android-app://包名）——**应用可自行填写，权威性低于内核记录，故排在 3 之后**；
     * 5. Binder.getCallingUid() 反查包名。
     *
     * 全部拿不到时返回 null，调用方按「来源不可得」处理（不接管，交回应用自己的内置浏览器）。
     * 这个失败方向是刻意保留的：猜错来源去劫持链接，比不接管伤害大得多。
     *
     * 历史上此处还有「遍历实参，只要出现接管应用包名就当作发起方」与「返回第一个含点号的字符串」
     * 两级兜底，会把 Intent 的目标包名（如微信）与 MIME / resultWho 误判成发起方，
     * 导致「B站分享到微信」这类动作被当成微信的应用内打开链接而遭接管，现已移除。
     *
     * @param referrerSource 出参：命中的来源层级，仅用于诊断日志
     */
    private static String readReferrer(Object request, java.util.List<Object> args, Intent intent,
                                       Context context, StringBuilder referrerSource) {
        // 1. 对象字段（ActivityRecord / Request）
        String pkg = readStringFields(request, REFERRER_FIELD_CANDIDATES);
        if (isNotBlank(pkg)) {
            referrerSource.append("字段");
            return pkg;
        }

        // 2. Request 的 callingPackage 系列字段
        pkg = readRequestCallingPackage(request);
        if (isNotBlank(pkg)) {
            referrerSource.append("Request.callingPackage");
            return pkg;
        }

        // 3. 实参结构中的 callingPackage（系统内核记录的真实发起方）
        pkg = readCallingPackageFromArgs(args);
        if (isNotBlank(pkg)) {
            referrerSource.append("实参结构");
            return pkg;
        }

        // 4. Intent 自带的 referrer（应用可自行填写，权威性低于内核记录，故排在实参之后）
        pkg = readReferrerFromIntent(intent);
        if (isNotBlank(pkg)) {
            referrerSource.append("EXTRA_REFERRER");
            return pkg;
        }

        // 5. Binder 调用方 UID 反查
        if (context != null) {
            try {
                int uid = Binder.getCallingUid();
                String[] pkgs = context.getPackageManager().getPackagesForUid(uid);
                if (pkgs != null && pkgs.length > 0 && isNotBlank(pkgs[0])) {
                    referrerSource.append("调用方UID");
                    return pkgs[0];
                }
            } catch (Throwable ignored) {
            }
        }

        referrerSource.append("不可得");
        return null;
    }

    /** 反射读取对象上的字符串字段，按候选名依次尝试。 */
    private static String readStringFields(Object obj, String[] fieldNames) {
        if (obj == null) return null;
        for (String name : fieldNames) {
            try {
                Field f = obj.getClass().getDeclaredField(name);
                f.setAccessible(true);
                Object v = f.get(obj);
                if (v instanceof String && isNotBlank((String) v)) return (String) v;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 读取 Intent.EXTRA_REFERRER / EXTRA_REFERRER_NAME 中的来源包名。 */
    private static String readReferrerFromIntent(Intent intent) {
        if (intent == null) return null;
        try {
            android.os.Bundle extras = intent.getExtras();
            if (extras == null) return null;
            String[] keys = {Intent.EXTRA_REFERRER_NAME, Intent.EXTRA_REFERRER};
            for (String key : keys) {
                Object v = extras.get(key);
                if (v == null) continue;
                String s = v.toString();
                if (s.isEmpty()) continue;
                // 形态一：android-app://com.example.app
                int idx = s.indexOf("android-app://");
                if (idx >= 0) {
                    String pkg = s.substring(idx + "android-app://".length());
                    int slash = pkg.indexOf('/');
                    if (slash > 0) pkg = pkg.substring(0, slash);
                    if (isNotBlank(pkg)) return pkg;
                }
                // 形态二：直接就是包名
                if (looksLikePackage(s)) return s;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 从实参结构定位 callingPackage：AOSP 各入口该参数要么紧跟在 (callingPid, callingUid) 之后，
     * 要么紧邻 Intent 参数之前（中间可能隔一个 callingFeatureId）。
     * 只做结构识别，不再用「含点号的任意字符串」兜底（那会把 MIME / resultWho / 目标包名当发起方）。
     */
    private static String readCallingPackageFromArgs(java.util.List<Object> args) {
        if (args == null || args.isEmpty()) return null;

        // 结构一：(callingPid, callingUid, callingPackage)——AOSP startActivityMayWait / ActivityRecord 构造
        for (int i = 0; i + 2 < args.size(); i++) {
            if (!isIntLike(args.get(i)) || !isIntLike(args.get(i + 1))) continue;
            Object v = args.get(i + 2);
            if (v instanceof String && looksLikePackage((String) v)) return (String) v;
        }

        // 结构二：callingPackage 紧邻 Intent 之前——AOSP ActivityTaskManagerService.startActivity
        for (int i = 1; i < args.size(); i++) {
            if (!(args.get(i) instanceof Intent)) continue;
            for (int k = 1; k <= 2 && i - k >= 0; k++) {
                Object v = args.get(i - k);
                if (!(v instanceof String)) continue;
                String s = (String) v;
                // 排除「目标包名」：targetPackage 与 Intent 紧邻的入口（部分 ROM 包装层）不能当发起方
                if (looksLikePackage(s) && !isIntentTargetPackage((Intent) args.get(i), s)) return s;
            }
        }
        return null;
    }

    /** 该包名是否是本次 Intent 的目标（组件包名或 setPackage）。 */
    private static boolean isIntentTargetPackage(Intent intent, String pkg) {
        if (intent == null || pkg == null) return false;
        ComponentName cn = intent.getComponent();
        if (cn != null && pkg.equals(cn.getPackageName())) return true;
        return pkg.equals(intent.getPackage());
    }

    /** 粗判字符串是否像一个应用包名：含点号、无分隔符与空白、非系统框架名。 */
    private static boolean looksLikePackage(String s) {
        if (s == null) return false;
        String v = s.trim();
        if (v.length() < 3 || v.length() > 127) return false;
        if (v.indexOf('.') < 0) return false;
        if (v.indexOf('/') >= 0 || v.indexOf(':') >= 0 || v.indexOf(' ') >= 0) return false;
        if (v.startsWith("android.") || v.startsWith("com.android.")) return false;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '.' || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    private static boolean isIntLike(Object v) {
        return v instanceof Integer || v instanceof Long || v instanceof Short;
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    /**
     * 分享载荷类 extra 键：其值是「要发给别人的内容」，不是「本次要打开的网页」。
     * 命中即视为分享链路，不作为待打开链接提取。
     */
    private static boolean isSharePayloadKey(String key) {
        if (key == null) return false;
        // 微信开放平台 SDK 分享/登录载荷（_mmessage_content 里才是真正被分享的链接）
        if (key.startsWith("_mmessage")) return true;
        return Intent.EXTRA_TEXT.equals(key)
            || Intent.EXTRA_HTML_TEXT.equals(key)
            || Intent.EXTRA_SUBJECT.equals(key)
            || Intent.EXTRA_STREAM.equals(key);
    }

    /**
     * 分享链路识别：把内容（含链接）「发给」别的应用，不属于「应用内打开链接」，必须放行。
     *
     * 不能只按 action 判：微信 SDK 的分享入口是
     * `setClassName("com.tencent.mm", ".plugin.base.stub.WXEntryActivity")` + `_mmessage_*` extras，
     * action 为空或自定义 scheme；微信在分享链路里转发这份内容时同样保留这些载荷。
     * 实测「B站分享到微信」会在分享这一步被当成「微信在应用内打开链接」接管，正是旧判据只认
     * ACTION_SEND / SEND_MULTIPLE / CHOOSER 所致。
     *
     * @return 命中的依据（用于日志）；未命中返回空串
     */
    private static String detectSharePayload(Intent intent) {
        if (intent == null) return "";
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action) || Intent.ACTION_SEND_MULTIPLE.equals(action)
            || Intent.ACTION_CHOOSER.equals(action)) {
            return "action=" + action;
        }
        android.os.Bundle extras = intent.getExtras();
        if (extras == null) return "";
        try {
            for (String key : extras.keySet()) {
                if (isSharePayloadKey(key)) return "extra=" + key;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** 目标 Intent 是否指向 LinkGo 自身组件或包名。 */
    private static boolean isTargetLinkGo(Intent intent) {
        if (intent == null) return false;
        ComponentName cn = intent.getComponent();
        if (cn != null && AppLinkHookContract.APPLICATION_ID.equals(cn.getPackageName())) return true;
        if (AppLinkHookContract.APPLICATION_ID.equals(intent.getPackage())) return true;
        return false;
    }

    private static Intent readRequestIntent(Object request) {
        if (request == null) return null;
        // 候选字段：intent（AOSP）/ activityIntentUri（部分 ROM 直接存 toUri() 字符串）
        String[] candidates = {"intent", "activityIntentUri"};
        for (String name : candidates) {
            try {
                Field f = request.getClass().getDeclaredField(name);
                f.setAccessible(true);
                Object v = f.get(request);
                if (v instanceof Intent) return (Intent) v;
                if (v instanceof String && name.equals("activityIntentUri")) {
                    String uri = (String) v;
                    if (!uri.isEmpty()) {
                        try {
                            Intent parsed = Intent.parseUri(uri, 0);
                            if (parsed != null) return parsed;
                        } catch (Throwable ignored) {
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String readRequestCallingPackage(Object request) {
        if (request == null) return null;
        // 各 ROM 字段名不同，逐候选尝试
        String[] candidates = {"realCallingPackage", "inCallingPackage", "callingPackage", "callerPackage"};
        for (String name : candidates) {
            try {
                Field f = request.getClass().getDeclaredField(name);
                f.setAccessible(true);
                Object v = f.get(request);
                if (v instanceof String) return (String) v;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * 提取 http/https 链接，多级路径深度尝试：
     * 1. intent.getData()（标准 data URI）；
     * 2. 全量扫描 intent.getExtras() 中的 keySet 与一级嵌套 Bundle；
     * 3. 常见 extra 键兜底；
     * 4. activityIntentUri = intent.toUri(0) 序列化字符串解析。
     */
    private static String extractHttpLink(Intent intent) {
        if (intent == null) return null;
        android.net.Uri data = intent.getData();
        if (data != null) {
            String scheme = data.getScheme();
            if (scheme != null && (scheme.equals("http") || scheme.equals("https"))) {
                String url = data.toString();
                if (url != null && !url.isEmpty()) return url;
            }
        }
        // 深度扫描 extras：全量扫描 keySet()，提取任意以 http/https 开头的字符串（覆盖各种自定义 App）
        android.os.Bundle extras = intent.getExtras();
        if (extras != null) {
            try {
                for (String key : extras.keySet()) {
                    try {
                        // 分享载荷（要发给别人的内容）里的链接不是「本次要打开的网页」，跳过不提取；
                        // 顺带跳过 _mmessage_content 这类嵌套容器，避免把分享内容当成打开目标
                        if (isSharePayloadKey(key)) continue;
                        Object v = extras.get(key);
                        if (v instanceof String) {
                            String s = (String) v;
                            if (s.startsWith("http://") || s.startsWith("https://")) return s;
                        } else if (v instanceof android.os.Bundle) {
                            android.os.Bundle sub = (android.os.Bundle) v;
                            for (String subKey : sub.keySet()) {
                                Object subV = sub.get(subKey);
                                if (subV instanceof String) {
                                    String subS = (String) subV;
                                    if (subS.startsWith("http://") || subS.startsWith("https://")) return subS;
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        // 兜底 1：常见 extra 键（实测：QQ=url，微信=rawUrl；其余应用 key 名不定）
        if (extras != null) {
            String[] keys = {"rawUrl", "url", "URL", "target_url", "web_url", "href", "link", "toURL", "uri", "load_url", "extra_url", "jump_url"};
            for (String key : keys) {
                try {
                    Object v = extras.get(key);
                    String s = v == null ? null : v.toString();
                    if (s != null && (s.startsWith("http://") || s.startsWith("https://"))) return s;
                } catch (Throwable ignored) {
                }
            }
        }
        // 兜底 2：activityIntentUri（intent.toUri(0)）字符串解析
        try {
            String uri = intent.toUri(0);
            String fromUri = extractHttpFromIntentUri(uri);
            if (fromUri != null) return fromUri;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 从 intent.toUri(0) 的序列化字符串（activityIntentUri）中提取 http/https 链接。
     * 格式：
     * - 带 data：intent:https://example.com/path#Intent;action=...;end
     * - extras：  intent:#Intent;...;S.url=https%3A%2F%2Fexample.com%2F...;end
     */
    private static String extractHttpFromIntentUri(String uri) {
        if (uri == null || uri.isEmpty()) return null;
        // 1. data 段：intent:<data>#Intent;...
        int hashIdx = uri.indexOf("#Intent");
        if (uri.startsWith("intent:") && hashIdx > "intent:".length()) {
            String data = uri.substring("intent:".length(), hashIdx);
            if (data.startsWith("http://") || data.startsWith("https://")) return data;
        }
        // 2. extras 段：;T.key=value（T 为类型前缀 S/i/B/...），key 名含 url 即尝试
        int segIdx = uri.indexOf("#Intent;");
        if (segIdx >= 0) {
            String seg = uri.substring(segIdx + "#Intent;".length());
            int endIdx = seg.indexOf("end");
            if (endIdx >= 0) seg = seg.substring(0, endIdx);
            for (String part : seg.split(";")) {
                if (part.isEmpty()) continue;
                int eq = part.indexOf('=');
                if (eq <= 0) continue;
                String key = part.substring(0, eq);
                String value = part.substring(eq + 1);
                // 去掉类型前缀（如 S.url -> url；S.rawUrl -> rawUrl），key 名含 url 即尝试
                // 实测：QQ=url，微信=rawUrl；contains("url") 通用兜底覆盖其他应用
                String k = key.contains(".") ? key.substring(key.indexOf('.') + 1) : key;
                if (k.equalsIgnoreCase("url") || k.equalsIgnoreCase("rawUrl")
                    || k.equalsIgnoreCase("web_url") || k.equalsIgnoreCase("target_url")
                    || k.equalsIgnoreCase("href") || k.equalsIgnoreCase("link")
                    || k.equalsIgnoreCase("toURL") || k.equalsIgnoreCase("uri")
                    || k.toLowerCase().contains("url")) {
                    String decoded = android.net.Uri.decode(value);
                    if (decoded != null && (decoded.startsWith("http://") || decoded.startsWith("https://"))) {
                        return decoded;
                    }
                }
            }
        }
        return null;
    }

    /** 应用内打开：启动目标 Activity 的包名 == 发起启动的调用者包名。 */
    private static boolean isInternalAppOpen(String callingPkg, Intent intent) {
        if (callingPkg == null || callingPkg.isEmpty() || intent == null) return false;
        ComponentName cn = intent.getComponent();
        if (cn == null) return false;
        return callingPkg.equals(cn.getPackageName());
    }

    /**
     * 放行规则（黑名单）：格式 "sourcePkg::matchType::pattern"（matchType: CONTAINS/REGEX/EXACT）。
     * 命中（来源匹配 + 规则匹配）的链接视为应用生态内内容，直接放行（不捕获不拦截）。
     * 由主进程经 SYNC 广播下发 JSON 更新；未同步前使用内置默认（与主进程出厂一致）。
     */
    private static volatile String[] exemptRules = {
        "::CONTAINS::mp.weixin.qq.com", "::CONTAINS::weixin.qq.com", "::CONTAINS::work.weixin.qq.com",
        "::CONTAINS::qzone.qq.com", "::CONTAINS::kf.qq.com"
    };

    private static boolean isExemptUrl(String callingPkg, String url) {
        if (url == null) return false;
        String[] rules = exemptRules;
        for (String rule : rules) {
            if (rule == null) continue; // 防御：异常规则不参与匹配
            String[] parts = rule.split("::", 3);
            if (parts.length < 2) continue;
            String src = parts[0];
            String type = parts.length >= 3 ? parts[1] : "CONTAINS";
            String pat = parts.length >= 3 ? parts[2] : parts[1];
            if (pat.isEmpty()) continue;
            if (!src.isEmpty() && !src.equals(callingPkg)) continue;
            if (matchExemptPattern(type, pat, url)) return true;
        }
        return false;
    }

    /** 三种匹配模式（与分发规则 matchRule 语义一致）。 */
    private static boolean matchExemptPattern(String type, String pattern, String url) {
        try {
            if ("REGEX".equals(type)) {
                java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.CASE_INSENSITIVE);
                return p.matcher(url).find();
            } else if ("EXACT".equals(type)) {
                return url.equalsIgnoreCase(pattern);
            } else { // CONTAINS：host 相等/子域，否则 URL 包含
                String host = null;
                try {
                    host = android.net.Uri.parse(url).getHost();
                } catch (Throwable ignored) {
                }
                if (host != null && (host.equalsIgnoreCase(pattern)
                    || host.toLowerCase().endsWith("." + pattern.toLowerCase()))) {
                    return true;
                }
                return url.toLowerCase().contains(pattern.toLowerCase());
            }
        } catch (Throwable t) {
            return false;
        }
    }

    /** 解析主进程下发的放行规则 JSON，更新内存规则。 */
    private static void updateExemptRules(String json) {
        if (json == null || json.isEmpty()) return;
        try {
            org.json.JSONArray arr = new org.json.JSONArray(json);
            // 用可变列表收集有效项，避免跳过项留下 null 元素导致 NPE
            java.util.ArrayList<String> list = new java.util.ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject obj = arr.optJSONObject(i);
                if (obj == null) continue;
                if (!obj.optBoolean("isEnabled", true)) continue;
                String src = obj.optString("sourcePkg", "");
                String type = obj.optString("matchType", "CONTAINS");
                String pat = obj.optString("pattern", "").trim();
                if (pat.isEmpty()) continue;
                list.add(src + "::" + type + "::" + pat);
            }
            exemptRules = list.toArray(new String[0]);
        } catch (Throwable t) {
            // 解析失败保持旧规则，静默降级（static 上下文不依赖实例日志）
        }
    }

    /**
     * 拦截时缓存被拦截链接的完整 Intent（深拷贝），供「内置打开」胶囊重放。
     */
    private static void cacheLinkIntent(Intent intent, String url, String source) {
        try {
            cachedLinkIntent = new Intent(intent);
            cachedLinkUrl = url;
            cachedLinkAt = System.currentTimeMillis();
        } catch (Throwable t) {
            cachedLinkIntent = null;
            cachedLinkUrl = null;
        }
    }

    /**
     * 「内置打开」：用缓存的完整 Intent（系统 UID 权限）重新启动内置浏览器 Activity。
     * 重放前设置单次放行标记，防止被自身钩子再次拦截；60 秒内有效。
     */
    private static void replayCachedIntent(String url) {
        if (url == null || cachedLinkUrl == null || cachedLinkIntent == null) return;
        if (!cachedLinkUrl.equals(url)) return;
        if (System.currentTimeMillis() - cachedLinkAt > CACHE_TTL_MS) {
            logDebug("[APP-LINK-REPLAY-EXPIRED] 缓存已过期: " + url);
            cachedLinkIntent = null;
            cachedLinkUrl = null;
            return;
        }
        final Intent intent = cachedLinkIntent;
        if (intent.getComponent() == null) {
            logWarn("replay: 缓存的 Intent 无 component，放弃", null);
            return;
        }
        Context context = findSystemContextCached();
        if (context == null) return;
        try {
            // 单次放行标记：重放启动命中该 URL 时直接放行（防死循环）
            singlePassUrl = cachedLinkUrl;
            if ((intent.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) == 0) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            long identity = Binder.clearCallingIdentity();
            try {
                context.startActivity(intent);
                logDebug("[APP-LINK-REPLAY] 已用内置浏览器打开: " + cachedLinkUrl
                    + ", component=" + intent.getComponent().flattenToShortString());
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
            cachedLinkIntent = null;
            cachedLinkUrl = null;
        } catch (Throwable t) {
            singlePassUrl = null;
            logWarn("replay builtin open failed", t);
        }
    }

    /**
     * 注册超时回退并返回本次捕获的 token。
     *
     * 语义与流程 Demo 一致：先注册定时器、再发出请求（顺序反了，同步回执会早于定时器注册到达，
     * 回退将永远等不到取消）；收到主进程「已接管」回执即取消；超时则重放缓存 Intent 放行内置浏览器。
     * 回退是单向的——一旦执行，迟到的回执不再撤销（否则内置浏览器与目标应用会同时打开）。
     */
    private static String armBuiltinFallback(String url) {
        cancelPendingFallback();
        String token = url + "#" + System.currentTimeMillis();
        pendingFallbackToken = token;
        fallbackHandler.postDelayed(() -> {
            if (token.equals(pendingFallbackToken)) {
                pendingFallbackToken = null;
                logDebug("[APP-LINK-FALLBACK] 等待主进程接管超时（" + FALLBACK_TIMEOUT_MILLIS
                    + "ms），回退内置打开: " + url);
                replayCachedIntent(url);
            }
        }, FALLBACK_TIMEOUT_MILLIS);
        return token;
    }

    /** 取消待回退项：收到「已接管」回执，或本次捕获已被新一轮捕获取代。 */
    private static void cancelPendingFallback() {
        pendingFallbackToken = null;
        fallbackHandler.removeCallbacksAndMessages(null);
    }

    /**
     * 「销毁原页面」：在询问模式下用户点击胶囊外部跳转后，静默销毁原应用的内置浏览器页面。
     */
    private static void finishLastAskActivity(String url, String pkg) {
        try {
            android.util.Log.d(TAG, "[APP-LINK-FINISH-REQ] 开始处理销毁原页面指令: url=" + url + ", pkg=" + pkg);
            if (lastAskActivityRecord == null) {
                android.util.Log.w(TAG, "[APP-LINK-FINISH-FAIL] lastAskActivityRecord 为 null，跳过销毁");
                return;
            }
            Object record = lastAskActivityRecord.get();
            if (record == null) {
                android.util.Log.w(TAG, "[APP-LINK-FINISH-FAIL] lastAskActivityRecord 弱引用已被释放，跳过销毁");
                return;
            }
            if (System.currentTimeMillis() - lastAskActivityTime > 60_000L) {
                android.util.Log.w(TAG, "[APP-LINK-FINISH-FAIL] 待销毁 ActivityRecord 已超过 60 秒有效期，丢弃");
                lastAskActivityRecord = null;
                return;
            }
            new Handler(Looper.getMainLooper()).post(() -> {
                boolean finished = false;
                try {
                    // 策略一：优先查找并调用 finishIfPossible (AOSP ActivityRecord 核心销毁入口)
                    for (Method m : record.getClass().getDeclaredMethods()) {
                        if ("finishIfPossible".equals(m.getName())) {
                            m.setAccessible(true);
                            Class<?>[] pts = m.getParameterTypes();
                            try {
                                if (pts.length == 0) {
                                    m.invoke(record);
                                    finished = true;
                                } else if (pts.length == 1 && pts[0] == String.class) {
                                    m.invoke(record, "linkgo_ask_auto_finish");
                                    finished = true;
                                } else if (pts.length == 2 && pts[0] == String.class && (pts[1] == boolean.class || pts[1] == Boolean.class)) {
                                    // AOSP 11~15+ 现代签名：finishIfPossible(String reason, boolean oomAdj)
                                    m.invoke(record, "linkgo_ask_auto_finish", false);
                                    finished = true;
                                } else if (pts.length == 4) {
                                    // finishIfPossible(int resultCode, Intent resultData, String reason, boolean oomAdj)
                                    m.invoke(record, 0, null, "linkgo_ask_auto_finish", false);
                                    finished = true;
                                }
                                if (finished) {
                                    android.util.Log.d(TAG, "[APP-LINK-FINISH-OK] 已成功通过 finishIfPossible (" + pts.length + "参数) 销毁页面: " + record);
                                    break;
                                }
                            } catch (Throwable t) {
                                android.util.Log.w(TAG, "[APP-LINK-FINISH-TRY] finishIfPossible 调用异常，尝试下一重载: " + t.getMessage());
                            }
                        }
                    }

                    // 策略二：尝试 destroyImmediately / destroyIfPossible
                    if (!finished) {
                        for (Method m : record.getClass().getDeclaredMethods()) {
                            String name = m.getName();
                            if ("destroyImmediately".equals(name) || "destroyIfPossible".equals(name)) {
                                m.setAccessible(true);
                                Class<?>[] pts = m.getParameterTypes();
                                try {
                                    if (pts.length == 0) {
                                        m.invoke(record);
                                        finished = true;
                                    } else if (pts.length == 1 && pts[0] == String.class) {
                                        m.invoke(record, "linkgo_ask_auto_finish");
                                        finished = true;
                                    }
                                    if (finished) {
                                        android.util.Log.d(TAG, "[APP-LINK-FINISH-OK] 已通过 " + name + " 销毁页面: " + record);
                                        break;
                                    }
                                } catch (Throwable ignored) {}
                            }
                        }
                    }

                    // 策略三：尝试 finishActivityResults / finishActivity
                    if (!finished) {
                        for (Method m : record.getClass().getDeclaredMethods()) {
                            String name = m.getName();
                            if ("finishActivityResults".equals(name) || "finishActivity".equals(name)) {
                                m.setAccessible(true);
                                Class<?>[] pts = m.getParameterTypes();
                                try {
                                    if (pts.length == 0) {
                                        m.invoke(record);
                                        finished = true;
                                    } else if (pts.length == 1 && pts[0] == String.class) {
                                        m.invoke(record, "linkgo_ask_auto_finish");
                                        finished = true;
                                    } else if (pts.length == 3) {
                                        m.invoke(record, 0, null, "linkgo_ask_auto_finish");
                                        finished = true;
                                    }
                                    if (finished) {
                                        android.util.Log.d(TAG, "[APP-LINK-FINISH-OK] 已通过 " + name + " 销毁页面: " + record);
                                        break;
                                    }
                                } catch (Throwable ignored) {}
                            }
                        }
                    }

                    if (finished) {
                        lastAskActivityRecord = null;
                    } else {
                        android.util.Log.w(TAG, "[APP-LINK-FINISH-FAIL] 未能找到并成功执行任何已知的 Activity 销毁方法: " + record.getClass().getName());
                    }
                } catch (Throwable t) {
                    android.util.Log.w(TAG, "[APP-LINK-FINISH-ERROR] 执行销毁 ActivityRecord 失败: " + t.getMessage(), t);
                }
            });
        } catch (Throwable t) {
            android.util.Log.w(TAG, "[APP-LINK-FINISH-ERROR] finishLastAskActivity 异常: " + t.getMessage(), t);
        }
    }

    /**
     * 向主进程广播应用内链接（带临时 App Allowlist，冷拉起进程做静默分发）。
     * 去重由调用方经 [isDuplicateBroadcast] 决定，本方法只负责发送，避免与超时回退注册互相踩踏。
     */
    private static volatile String lastBroadcastUrl = "";
    private static volatile long lastBroadcastAt = 0L;

    /**
     * 多方法 hook 可能对同一次启动重复回调：500ms 内相同 URL 视为同一次捕获。
     * 返回 true 表示已去重，调用方必须直接沿用第一次已注册的超时回退，不得重新注册。
     */
    private static boolean isDuplicateBroadcast(String url) {
        long now = System.currentTimeMillis();
        if (url.equals(lastBroadcastUrl) && now - lastBroadcastAt < 500L) {
            return true;
        }
        lastBroadcastUrl = url;
        lastBroadcastAt = now;
        return false;
    }

    private void broadcastAppLink(String url, String sourcePkg, String token) {
        Context context = findSystemContextCached();
        if (context == null) return;
        Intent intent = new Intent(AppLinkHookContract.ACTION_HANDLE_APP_LINK)
            .setComponent(new ComponentName(AppLinkHookContract.APPLICATION_ID, AppLinkHookContract.RECEIVER_CLASS))
            .putExtra(AppLinkHookContract.EXTRA_LINK_URL, url)
            .putExtra(AppLinkHookContract.EXTRA_LINK_SOURCE, sourcePkg == null ? "" : sourcePkg)
            .putExtra(AppLinkHookContract.EXTRA_LINK_TOKEN, token == null ? "" : token)
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND | Intent.FLAG_INCLUDE_STOPPED_PACKAGES | 0x01000000 | 0x20000000 | 0x00000400);

        android.os.Bundle optionsBundle = null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Class<?> boClass = Class.forName("android.app.BroadcastOptions");
                Method makeBasicMethod = boClass.getMethod("makeBasic");
                Object bOptions = makeBasicMethod.invoke(null);
                if (bOptions != null) {
                    try {
                        Method m = boClass.getMethod("setTemporaryAppAllowlist", long.class, int.class, int.class, String.class);
                        m.invoke(bOptions, 15000L, 0, 0, "linkgo_applink");
                    } catch (Throwable t1) {
                        try {
                            Method m2 = boClass.getMethod("setTemporaryAppWhitelistDuration", long.class);
                            m2.invoke(bOptions, 15000L);
                        } catch (Throwable ignored) {
                        }
                    }
                    Method toBundleMethod = boClass.getMethod("toBundle");
                    optionsBundle = (android.os.Bundle) toBundleMethod.invoke(bOptions);
                }
            }
        } catch (Throwable ignored) {
        }

        long identity = Binder.clearCallingIdentity();
        try {
            if (optionsBundle != null) {
                try {
                    Method sendMethod = Context.class.getMethod(
                        "sendBroadcastAsUser",
                        Intent.class,
                        android.os.UserHandle.class,
                        String.class,
                        android.os.Bundle.class
                    );
                    sendMethod.invoke(context, intent, android.os.Process.myUserHandle(), null, optionsBundle);
                } catch (Throwable t) {
                    context.sendBroadcastAsUser(intent, android.os.Process.myUserHandle());
                }
            } else {
                context.sendBroadcastAsUser(intent, android.os.Process.myUserHandle());
            }
            logDebug("[APP-LINK-BROADCAST] 成功向 " + AppLinkHookContract.RECEIVER_CLASS + " 广播应用内链接");
        } catch (Throwable t) {
            logWarn("broadcast app link failed", t);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void sendIslandDiagBroadcast(String tag, String message) {
        logDebug("[ISLAND-DIAG] [" + tag + "] " + message);
        try {
            Context context = findSystemContext();
            if (context == null) return;
            Intent intent = new Intent(ACTION_SUPER_ISLAND_DIAG);
            intent.setPackage("com.moting.linkgo");
            intent.putExtra(EXTRA_DIAG_TAG, tag);
            intent.putExtra(EXTRA_DIAG_MESSAGE, message);
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            context.sendBroadcast(intent);
        } catch (Throwable t) {
            logWarn("sendIslandDiagBroadcast failed", t);
        }
    }

    private static void logDebug(String message) {
        HookLog.d(staticSelf, TAG, message);
    }

    private static void logWarn(String message, Throwable throwable) {
        HookLog.w(staticSelf, TAG, message, throwable);
    }

    private static void logError(String message, Throwable throwable) {
        HookLog.e(staticSelf, TAG, message, throwable);
    }
}
