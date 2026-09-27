package com.moting.linkgo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moting.linkgo.data.SettingsRepository
import com.moting.linkgo.image.ClipPayload
import com.moting.linkgo.image.ImageRouter
import com.moting.linkgo.image.ImageStore
import com.moting.linkgo.model.ImageRule
import com.moting.linkgo.ui.components.RuleIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 图片手动选择页。
 *
 * 与 `BrowserSelectorActivity`（链接的多引擎选择）是**对等**的页面：
 * 一次剪贴板/屏幕取图如果命中多条图片规则，就不该由我们替用户挑一条，
 * 而应该像多浏览器那样把候选平铺出来让用户点。
 *
 * 布局对照链接侧：
 * - 顶部是图片摘要卡（链接侧是链接信息卡），让用户确认"我要发的是这张图"
 * - 下面是 6 列引擎网格，每格一条命中的图片规则
 * - 单条规则时网格自然退化为单格，不需要另做布局分支
 *
 * 与链接侧的一个重要差异：图片规则是"应用 + 分享接口"粒度的，
 * 因此网格里展示的是规则名与目标应用图标，而不是应用名——同一应用的多条规则要能区分开。
 */
class ImageSelectorActivity : ComponentActivity(), com.moting.linkgo.ui.BlurCapableHost {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = androidx.activity.SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT
            )
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            // 与链接选择页同一套底层模糊：两个弹窗的"毛玻璃"观感必须一致
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply {
                blurBehindRadius = 0
            }
            window.setDimAmount(0f)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(android.app.Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
            overrideActivityTransition(android.app.Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }

        val imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH)
        // 命中的规则 id：由调用方按"顺序即优先级"的规则顺序传入，这里不再重新判定
        val ruleIds = intent.getStringArrayListExtra(EXTRA_RULE_IDS).orEmpty()

        if (imagePath.isNullOrBlank()) {
            finish()
            return
        }

        setContent {
            val repository = remember { SettingsRepository(this) }
            val dynamicColorEnabled by repository.dynamicColorEnabled.collectAsState(initial = true)
            // 与链接选择页一致：模糊开关读全局设置，不在这里另立一套判断
            val backgroundBlurEnabled by repository.backgroundBlurEnabled.collectAsState(initial = true)
            com.moting.linkgo.ui.theme.链接跳转Theme(dynamicColor = dynamicColorEnabled) {
                ImageSelectorScreen(
                    imagePath = imagePath,
                    ruleIds = ruleIds,
                    blurEnabled = backgroundBlurEnabled,
                    onDismiss = { finish() }
                )
            }
        }
    }

    /**
     * 与链接选择页同一套模糊进度联动：共用外壳会回调这里，
     * 两个弹窗的毛玻璃强度因此始终一致。
     */
    override fun updateBlurProgress(progress: Float, blurEnabled: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (blurEnabled) {
                window.attributes = window.attributes.apply {
                    blurBehindRadius = (40 * progress).toInt().coerceAtLeast(0)
                }
                window.setDimAmount(0.28f * progress)
            } else {
                window.attributes = window.attributes.apply { blurBehindRadius = 0 }
                window.setDimAmount(0.48f * progress)
            }
        } else {
            window.setDimAmount(0.48f * progress)
        }
    }

    override fun finish() {
        super.finish()
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(android.app.Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
            overrideActivityTransition(android.app.Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        const val EXTRA_IMAGE_PATH = "IMAGE_PATH"
        const val EXTRA_IMAGE_MIME = "IMAGE_MIME"
        const val EXTRA_IMAGE_WIDTH = "IMAGE_WIDTH"
        const val EXTRA_IMAGE_HEIGHT = "IMAGE_HEIGHT"
        const val EXTRA_RULE_IDS = "IMAGE_RULE_IDS"

        /**
         * 组装指向本页的 Intent。
         *
         * 只传文件路径与规则 id，不传字节：Intent 同样受 Binder 事务大小限制，
         * 而且图片已经在磁盘上，没有必要再搬一次。
         */
        fun buildIntent(
            context: android.content.Context,
            image: ClipPayload.Image,
            ruleIds: List<String>
        ) = android.content.Intent(context, ImageSelectorActivity::class.java).apply {
            putExtra(EXTRA_IMAGE_PATH, image.file.absolutePath)
            putExtra(EXTRA_IMAGE_MIME, image.mimeType)
            putExtra(EXTRA_IMAGE_WIDTH, image.width)
            putExtra(EXTRA_IMAGE_HEIGHT, image.height)
            putStringArrayListExtra(EXTRA_RULE_IDS, ArrayList(ruleIds))
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}

/**
 * 图片选择页的 Composable 本体。
 *
 * 拆出来是为了能被其他入口复用（例如未来的规则页"测试并选择目标"）。
 */
@Composable
fun ImageSelectorScreen(
    imagePath: String,
    ruleIds: List<String>,
    blurEnabled: Boolean = true,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var candidates by remember { mutableStateOf<List<ImageRule>>(emptyList()) }
    var selectedRuleId by remember { mutableStateOf<String?>(null) }
    // 置位表示已经发出（直接用引擎发送 / 或已弹出系统分享选择器），
    // 用于挡住重复点击；不再用它来关闭页面——用户在预览区试过"复制"后
    // 仍应能继续选引擎发送，弹一次就关会把这个流程切断
    var consumed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // 只展示命中的规则：空列表意味着调用方没传（理论上不会发生），
        // 此时回退为"全部启用中的图片规则"，宁可多给选项也不要给出空白页
        val all = withContext(Dispatchers.IO) {
            runCatching { SettingsRepository(context).imageRules.first() }.getOrDefault(emptyList())
        }
        candidates = if (ruleIds.isEmpty()) {
            all.filter { it.isEnabled }
        } else {
            // 保持调用方传入的顺序（即规则列表顺序 = 优先级顺序）
            ruleIds.mapNotNull { id -> all.find { it.id == id } }
        }
    }

    // 重建载荷：宽高不参与决策，预览与发送都只需要文件本体与 MIME，
    // 因此这里按文件真实内容补齐尺寸，调用方不必再传那几个数字
    val image = remember(imagePath) { buildPayloadFromFile(File(imagePath)) }

    /**
     * 把图片发给指定引擎。点击网格单元即调用，长按则以反转窗口模式打开。
     */
    fun sendToRule(rule: ImageRule, isReverse: Boolean = false) {
        val payload = image ?: return
        com.moting.linkgo.image.ImageSelectionSession.clear()
        val sent = ImageRouter.sendTo(
            context, payload, rule.targetPackage, rule.targetClass, rule.excludeFromRecents,
            ruleLaunchMode = rule.ruleLaunchMode, ruleName = rule.name, ruleId = rule.id,
            isReverse = isReverse
        )
        if (!sent) {
            // 规则目标已失效（应用卸载 / 不再支持收图）时回落系统选择器；
            // 回落也失败则给出可见提示，不能静默无反应
            if (!ImageRouter.sendViaChooser(context, payload)) {
                com.moting.linkgo.util.InstantToastHelper.show(context, "没有可接收图片的应用")
            }
        }
        onDismiss()
    }

    val selectedRule = candidates.find { it.id == selectedRuleId }

    fun confirm() {
        val payload = image ?: return
        val rule = selectedRule ?: return
        if (consumed) return
        consumed = true
        com.moting.linkgo.image.ImageSelectionSession.clear()
        val ok = ImageRouter.sendTo(
            context, payload, rule.targetPackage, rule.targetClass, rule.excludeFromRecents,
            ruleLaunchMode = rule.ruleLaunchMode, ruleName = rule.name, ruleId = rule.id
        )
        if (!ok) {
            // 规则目标已失效（应用卸载 / 不再支持收图）时回落到系统选择器，
            // 而不是静默失败——用户点了就该有反应
            ImageRouter.sendViaChooser(context, payload)
        }
        onDismiss()
    }

    // 容器与链接选择页**完全共用**：毛玻璃、遮罩、底部滑入、标题角标、底部按钮栏都由外壳提供。
    // 此前这里是一个只做透明度渐显的全屏层，与链接侧的动效完全不同，观感像两个 App 的东西。
    // 不传 confirmLabel/onConfirm：这一页点网格单元即发送，不需要底部"取消/确认"两步
    // 不传 badgeText：候选数量对"发到哪"的决策没有帮助，标题保持干净
    com.moting.linkgo.ui.SelectionSheetScreen(
        title = "发送到",
        blurEnabled = blurEnabled,
        onDismiss = onDismiss
    ) {
        val canReselect = remember { com.moting.linkgo.image.ImageSelectionSession.canReselect() }
        // 预览区：3×2 图片 + 1×2 三动作 + 右下角重选角标
        ImagePreviewBlock(
            imagePath = imagePath,
            canReselect = canReselect,
            onReselect = {
                com.moting.linkgo.image.ImageSelectionSession.triggerReselect(context)
                onDismiss()
            },
            onShare = {
                val payload = image ?: return@ImagePreviewBlock
                // 分享失败必须有可见反馈：系统里没有任何可接收图片的活动时
                // createChooser 会抛异常，此前只写日志，用户看到的就是"点了没反应"
                if (!ImageRouter.sendViaChooser(context, payload)) {
                    com.moting.linkgo.util.InstantToastHelper.show(context, "没有可接收图片的应用")
                }
            },
            onCopy = {
                val payload = image ?: return@ImagePreviewBlock
                // 不关闭页面：用户可能想先复制一份、再选引擎发出去
                copyImageToClipboard(context, payload)
            },
            onSave = {
                val payload = image ?: return@ImagePreviewBlock
                saveImageToGallery(context, payload, isTemporary = false)
            },
            onSaveLongClick = {
                val payload = image ?: return@ImagePreviewBlock
                saveImageToGallery(context, payload, isTemporary = true)
            }
        )

        Spacer(Modifier.height(12.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(6),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(weight = 1f, fill = false)
        ) {
            items(candidates, key = { it.id }) { rule ->
                ImageRuleGridItem(
                    rule = rule,
                    // 点击即按默认模式发送，长按反转窗口模式发送（小窗反转为全屏，全屏反转为小窗）
                    onClick = { sendToRule(rule, isReverse = false) },
                    onLongClick = { sendToRule(rule, isReverse = true) }
                )
            }
        }
    }
}

/**
 * 从本地文件重建图片载荷。
 *
 * 尺寸通过 inJustDecodeBounds 读取：发送与预览都不需要像素数据，
 * 只有落盘时才知道的真实尺寸需要补齐，这样调用方不必在 Intent 里多带几个数字。
 */
private fun buildPayloadFromFile(file: File): ClipPayload.Image? {
    if (!file.isFile || file.length() == 0L) return null
    val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
    return ClipPayload.Image(
        file = file,
        mimeType = "image/jpeg",
        width = options.outWidth.coerceAtLeast(0),
        height = options.outHeight.coerceAtLeast(0),
        byteSize = file.length(),
        fingerprint = "",
        sourcePackage = null
    )
}

/** 由扩展名推导图片 MIME */
private fun mimeForExtension(extension: String): String = when (extension) {
    "png" -> "image/png"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "bmp" -> "image/bmp"
    "heic", "heif" -> "image/heic"
    else -> "image/jpeg"
}

/** 复制到系统剪贴板：源文件经 FileProvider 授权后写入 ClipData */
private fun copyImageToClipboard(context: android.content.Context, image: ClipPayload.Image) {
    try {
        val uri = ImageStore.shareUri(context, image.file)
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager
        clipboard?.setPrimaryClip(android.content.ClipData.newUri(context.contentResolver, "图片", uri))
        com.moting.linkgo.util.InstantToastHelper.show(context, "已复制图片")
    } catch (e: Exception) {
        // 失败必须有可见反馈，不能只写日志
        com.moting.linkgo.util.InstantToastHelper.show(context, "复制失败")
    }
}

/** 保存到相册：成功与失败都要有可见反馈，支持临时保存（5分钟后自动删除） */
private fun saveImageToGallery(
    context: android.content.Context,
    image: ClipPayload.Image,
    isTemporary: Boolean = false
) {
    val uri = ImageStore.saveToGallery(context, image, isTemporary = isTemporary)
    val text = if (uri != null) {
        if (isTemporary) "已临时保存到相册（5分钟后自动删除）" else "已保存到相册"
    } else {
        "保存失败"
    }
    com.moting.linkgo.util.InstantToastHelper.show(context, text)
}


/**
 * 图片预览区：以底部引擎网格的单元格为单位，整体占 4×2 格。
 *
 * 左侧 3×2 是图片本体（ContentScale.Fit，看清整张图而不是裁切），
 * 右侧 1×2 竖排三个动作：分享 / 复制 / 保存。
 *
 * 预览存在的理由是**用户来这个页面就是为了确认"发的是哪张图"**；
 * 三个动作覆盖"不发给任何已配引擎、直接处置这张图"的三条独立诉求。
 *
 * 注意这里**没有"已消费"标记**：此前用 consumed 挡重复点击，但它一旦置位永不复位，
 * 导致点过一次分享后按钮永久失效。现在三个动作各自幂等，不需要额外挡。
 */
@Composable
private fun ImagePreviewBlock(
    imagePath: String,
    canReselect: Boolean = false,
    onReselect: () -> Unit = {},
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onSave: () -> Unit,
    onSaveLongClick: (() -> Unit)? = null
) {
    var thumb by remember(imagePath) {
        mutableStateOf<android.graphics.Bitmap?>(null)
    }
    LaunchedEffect(imagePath) {
        thumb = withContext(Dispatchers.IO) {
            // 按实际展示尺寸采样解码：预览区约 200dp 宽，@3x 屏折算约 600px，
            // 取 512 兼顾清晰度与内存
            runCatching { ImageStore.loadThumbnail(File(imagePath), 512) }.getOrNull()
        }
    }

    // 高度对齐"两行网格"：单个单元格约 70dp，加 12dp 行间距 ≈ 152dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .height(152.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 左：3×2 图片
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f),
            modifier = Modifier
                .weight(3f)
                .fillMaxHeight()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                val bitmap = thumb
                if (bitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "待发送的图片",
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(6.dp)
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }

                // 右下角重选角标
                if (canReselect) {
                    val haptic = LocalHapticFeedback.current
                    Surface(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onReselect()
                        },
                        shape = RoundedCornerShape(topStart = 10.dp, bottomEnd = 16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                        ),
                        modifier = Modifier.align(Alignment.BottomEnd)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_refresh),
                                contentDescription = "重选",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "重选",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }
        }

        // 右：1×2 竖排三动作
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PreviewActionButton(
                label = "分享",
                icon = R.drawable.ic_iconoir_share_android,
                modifier = Modifier.weight(1f),
                onClick = onShare
            )
            PreviewActionButton(
                label = "复制",
                icon = R.drawable.ic_iconoir_copy,
                modifier = Modifier.weight(1f),
                onClick = onCopy
            )
            PreviewActionButton(
                label = "保存",
                icon = R.drawable.ic_iconoir_floppy_disk,
                modifier = Modifier.weight(1f),
                onClick = onSave,
                onLongClick = onSaveLongClick
            )
        }
    }
}

/** 预览区右侧的动作按钮：图标在上、文字在下，纵向填满一格，支持可选长按动作 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PreviewActionButton(
    label: String,
    icon: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(12.dp)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick?.let { action ->
                    {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        action()
                    }
                }
            )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            androidx.compose.material3.Icon(
                imageVector = ImageVector.vectorResource(id = icon),
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1
            )
        }
    }
}

/**
 * 网格单元：一条图片规则。
 *
 * 点击即发送（默认窗口模式），长按反转窗口模式发送（小窗转全屏，全屏转小窗）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageRuleGridItem(
    rule: ImageRule,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                }
            )
            .padding(vertical = 6.dp, horizontal = 2.dp)
    ) {
        RuleIcon(
            iconPath = rule.iconPath,
            targetPackage = rule.targetPackage,
            size = 40.dp,
            shape = RoundedCornerShape(10.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = rule.name.ifBlank { "图片规则" },
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

