package com.moting.linkgo.image

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.util.Log
import com.moting.linkgo.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * 图片跳转执行：把 [ClipPayload.Image] 交给目标应用。
 *
 * 与文本链路的根本差异（对应《图片识别方案》§4.3）：
 * - 动作用 `ACTION_SEND` + `EXTRA_STREAM`，不是 `ACTION_VIEW` + `Uri`；
 * - **绝不交出源 `content://`**：那是别的应用的 provider，既无授权也未导出，
 *   目标应用打不开。一律经 [ImageStore.shareUri] 换成带授权 grant 的自有 URI。
 *
 * 窗口模式、预热、未导出组件直达、解冻等能力由文本链路的 `WindowRouter` 提供；
 * 本类只负责「把图片正确地递出去」，不重复实现那些机制（P4 接入规则时再接窗口能力）。
 */
object ImageRouter {

    private const val TAG = "LinkGo_ImageRouter"

    /** 未命中任何图片规则时的默认标签：交给用户选应用 */
    const val LABEL_MANUAL_PICK = "发送图片"

    /**
     * 命中多条图片规则时的胶囊与通知标签。
     */
    const val LABEL_PICK_TARGET = "图片分享"

    /**
     * 构造图片分享 Intent。
     *
     * @param targetPackage 指定目标应用；为空则只设置 type，由系统解析
     * @param targetClass 可选的显式 Activity，用于定向到具体分享面板
     */
    fun buildSendIntent(
        context: Context,
        image: ClipPayload.Image,
        targetPackage: String = "",
        targetClass: String = ""
    ): Intent {
        val shareUri = ImageStore.shareUri(context, image.file)
        return Intent(Intent.ACTION_SEND).apply {
            type = image.mimeType
            putExtra(Intent.EXTRA_STREAM, shareUri)
            clipData = android.content.ClipData.newUri(context.contentResolver, "image", shareUri)
            // 授权必须显式声明，否则目标应用打开 URI 时会 SecurityException
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            when {
                targetClass.isNotBlank() && targetPackage.isNotBlank() ->
                    component = ComponentName(targetPackage, targetClass)
                targetPackage.isNotBlank() -> `package` = targetPackage
            }
        }
    }

    /**
     * 弹出系统分享选择器发送图片。
     *
     * 用途有两个：P2 阶段无图片规则时的默认行为，以及 P4 之后规则目标失效时的回落。
     * 用系统选择器而不是自绘列表，是因为「哪些应用能收图」由系统实时判定，比我们维护的列表更新。
     */
    fun sendViaChooser(context: Context, image: ClipPayload.Image, title: String? = null): Boolean {
        return try {
            val chooser = Intent.createChooser(buildSendIntent(context, image), title).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            Log.i(TAG, "[IMAGE-SHARE] 已弹出系统选择器: ${image.width}x${image.height}, ${image.byteSize}B")
            true
        } catch (e: Exception) {
            Log.w(TAG, "[IMAGE-SHARE-FAIL] 分享选择器启动失败: ${e.message}")
            false
        }
    }

    /**
     * 直接发送到指定应用。
     *
     * @param excludeFromRecents 对应图片规则的「不留后台卡片」开关
     * @param ruleLaunchMode 规则指定的窗口模式（-1 全局 / 1 全屏 / 5 小窗），语义与 [DispatchRule.ruleLaunchMode] 一致；
     *   非 1 时通过 ActivityOptions.setLaunchWindowingMode 应用，-1 或 1 则用系统默认全屏
     * @param ruleName 命中规则名（用于写历史；为空则不写）
     * @param ruleId 命中规则 ID（用于写历史）
     * @return true 表示已成功发出；false 表示目标不可用（调用方应回落到 [sendViaChooser]）
     */
    fun sendTo(
        context: Context,
        image: ClipPayload.Image,
        targetPackage: String,
        targetClass: String = "",
        excludeFromRecents: Boolean = false,
        ruleLaunchMode: Int = -1,
        ruleName: String? = null,
        ruleId: String? = null,
        isReverse: Boolean = false
    ): Boolean {
        if (targetPackage.isBlank()) return false

        // 执行前自检：规则可能是在目标应用还在时配好的，之后被卸载或不再支持收图
        if (!canReceiveImage(context, targetPackage)) {
            Log.w(TAG, "[IMAGE-TARGET-INVALID] 目标应用不支持接收图片或未安装: $targetPackage")
            return false
        }

        return try {
            val intent = buildSendIntent(context, image, targetPackage, targetClass).apply {
                // 用 or 组合而非直接赋值，避免覆盖 buildSendIntent 里已设的 Uri 授权等标志位。
                // 这里刻意不搬 WindowRouter 的 REORDER_TO_FRONT：那条是为「复用已开启的小窗」服务的，
                // 图片分享是一次性 ACTION_SEND 投递，语义不同，不能盲抄。
                flags = flags or Intent.FLAG_ACTIVITY_NEW_TASK
                if (excludeFromRecents) {
                    flags = flags or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                }
            }

            // 窗口模式与尺寸适配：统一复用 WindowRouter 的仲裁机制与 ActivityOptions 构建
            // 支持全局小窗继承、厂商适配代号转换、用户配置的小窗 Bounds 精准注入以及 Android 14+ 后台权限
            val config = com.moting.linkgo.data.SettingsCache.windowConfig
            val effectiveWindowMode = com.moting.linkgo.util.WindowRouter.resolveWindowMode(ruleLaunchMode, config, isReverse = isReverse)
            com.moting.linkgo.util.WindowRouter.adaptManufacturerFlags(intent, effectiveWindowMode)

            when (effectiveWindowMode) {
                4 -> {
                    // 自由窗口4 (OriginOS)：利用代理跳板拉起小窗
                    val realLaunchIntent = Intent(intent).apply {
                        flags = flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv()
                        flags = flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK.inv()
                    }
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                        com.moting.linkgo.util.WindowRouter.shareOpenIntent(context, realLaunchIntent, addNewTask = true)
                    }
                }
                6 -> {
                    // 自由窗口6 (努比亚/红魔)：注入私有 identifier，由系统识别并分配小窗
                    intent.identifier = com.moting.linkgo.util.WindowRouter.NUBIA_WINDOW_REPLY_IDENTIFIER
                    context.startActivity(intent, android.os.Bundle())
                }
                1 -> {
                    // 强制全屏或常规全屏模式
                    context.startActivity(intent)
                }
                else -> {
                    // 自由窗口1/2/3/5 等模式：依赖原生 setLaunchWindowingMode 及精准 Bounds 注入
                    val options = com.moting.linkgo.util.WindowRouter.createActivityOptions(context, config, effectiveWindowMode)
                    if (options != null) {
                        context.startActivity(intent, options.toBundle())
                    } else {
                        context.startActivity(intent)
                    }
                }
            }
            Log.i(TAG, "[IMAGE-SHARE] 已发往 $targetPackage（不留后台=$excludeFromRecents, 原始模式=$ruleLaunchMode, 生效模式=$effectiveWindowMode）")

            // 图片规则派发写历史：只有拿到规则名（即确实经规则直达）才记，
            // 系统选择器回落（sendViaChooser）不在此处记录。
            if (!ruleName.isNullOrBlank()) {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    runCatching {
                        SettingsRepository(context.applicationContext).addJumpRecord(
                            com.moting.linkgo.model.JumpRecord(
                                ruleName = ruleName,
                                ruleId = ruleId,
                                originalUrl = "",
                                targetPackage = if (targetClass.isNotBlank()) "$targetPackage/$targetClass" else targetPackage,
                                executionStatus = 0,
                                kind = com.moting.linkgo.model.JumpRecord.KIND_IMAGE
                            )
                        )
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "[IMAGE-SHARE-FAIL] 发往 $targetPackage 失败: ${e.message}")
            false
        }
    }

    /**
     * 目标应用是否可接收图片。
     *
     * 注意必须显式带 `CATEGORY_DEFAULT` 并做 `MATCH_DEFAULT_ONLY` 查询，
     * 这与 `startActivity` 的解析规则一致，否则会把只有 intent-filter 但非 default 的
     * 组件误判为可用。
     */
    fun canReceiveImage(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        return try {
            val probe = Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_DEFAULT)
                setPackage(packageName)
            }
            queryImageReceivers(context, probe).isNotEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "目标可用性检查失败: ${e.message}")
            false
        }
    }

    /**
     * 查询系统里所有可接收图片分享的应用（去重后的包名列表）。
     *
     * `AndroidManifest.xml` 的 queries 必须声明 SEND 加 image 通配类型，
     * 否则 Android 11+ 的包可见性会直接返回空列表（本方案 §4.4 记过这个易漏点）。
     */
    fun queryImageShareTargets(context: Context): List<String> {
        val probe = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        return queryImageReceivers(context, probe)
            .mapNotNull { it.activityInfo?.packageName }
            .distinct()
            .sorted()
    }

    private fun queryImageReceivers(context: Context, intent: Intent): List<ResolveInfo> {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
    }

    /** 供 UI 展示用的简要描述 */
    fun describe(image: ClipPayload.Image): String =
        "${image.width}×${image.height} · ${formatBytes(image.byteSize)}"

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    /** 图片文件是否仍然存在（TTL 清理可能已把它删掉） */
    fun isStillAvailable(image: ClipPayload.Image): Boolean = File(image.file.absolutePath).isFile
}
