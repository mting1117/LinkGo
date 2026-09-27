package com.moting.linkgo.ui.components

import android.graphics.drawable.Drawable
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.moting.linkgo.data.PackageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 异步应用图标组件
 * 实现按需加载、图标缓存与平滑过渡
 */
@Composable
fun AsyncAppIcon(
    packageName: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(8.dp),
    placeholderAlpha: Float = 0.5f
) {
    val context = LocalContext.current
    var iconDrawable by remember(packageName) { mutableStateOf<Drawable?>(null) }
    var isLoading by remember(packageName) { mutableStateOf(true) }

    LaunchedEffect(packageName) {
        isLoading = true
        // 1. 先尝试同步检查内存缓存
        val cached = PackageRepository.getCachedIcon(packageName)
        if (cached != null) {
            iconDrawable = cached
            isLoading = false
        } else if (PackageRepository.isPackageNotFound(packageName)) {
            // 已确认无图标，直接降级
            iconDrawable = null
            isLoading = false
        } else {
            // 2. 缓存未命中，在 IO 线程加载（PackageRepository.getAppIcon 内部会加载并存入缓存）
            withContext(Dispatchers.IO) {
                val loaded = PackageRepository.getAppIcon(context, packageName)
                withContext(Dispatchers.Main) {
                    iconDrawable = loaded
                    isLoading = false
                }
            }
        }
    }

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        val currentDrawable = iconDrawable
        if (currentDrawable != null) {
            val bitmap = remember(currentDrawable) { 
                try {
                    currentDrawable.toBitmap() 
                } catch (e: Exception) {
                    null
                }
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(size)
                        .clip(shape),
                    contentScale = ContentScale.Crop
                )
            } else {
                FallbackAppIcon(size, shape, placeholderAlpha)
            }
        } else {
            FallbackAppIcon(size, shape, placeholderAlpha)
        }
    }
}

@Composable
private fun FallbackAppIcon(size: Dp, shape: androidx.compose.ui.graphics.Shape, placeholderAlpha: Float) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = placeholderAlpha)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.Android,
            contentDescription = null,
            modifier = Modifier.padding(size / 5),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
    }
}

/**
 * 规则图标组件
 * 兼容本地路径、app:// 协议和包名自动联动
 */
@Composable
fun RuleIcon(
    iconPath: String?,
    targetPackage: String?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(8.dp),
    isEnabled: Boolean = true,
    isReDispatch: Boolean = false
) {
    if (isReDispatch && iconPath.isNullOrBlank()) {
        // 使用分发规则专属图标（与跳转规则同尺寸圆角背景，图标使用 onSurface 自然色）
        Box(
            modifier = modifier
                .size(size)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .alpha(if (isEnabled) 1f else 0.5f),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = com.moting.linkgo.R.drawable.ic_hub_primary),
                contentDescription = null,
                tint = if (isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.size(size * 0.56f)
            )
        }
        return
    }

    val alpha = if (isEnabled) 1f else 0.5f
    val effectiveIconSource = remember(iconPath, targetPackage) {
        when {
            !iconPath.isNullOrBlank() -> iconPath
            !targetPackage.isNullOrBlank() -> {
                val pkg = targetPackage.split("/")[0]
                if (pkg.isNotBlank()) "app://$pkg" else null
            }
            else -> null
        }
    }

    Box(modifier = modifier.size(size).alpha(alpha), contentAlignment = Alignment.Center) {
        if (effectiveIconSource != null) {
            if (effectiveIconSource.startsWith("app://")) {
                val pkgName = effectiveIconSource.substringAfter("app://")
                AsyncAppIcon(packageName = pkgName, size = size, shape = shape)
            } else {
                // 加载本地图片
                val bitmap = remember(effectiveIconSource) {
                    try {
                        android.graphics.BitmapFactory.decodeFile(effectiveIconSource)?.asImageBitmap()
                    } catch (e: Exception) {
                        null
                    }
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        modifier = Modifier.size(size).clip(shape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    FallbackAppIcon(size, shape, 0.5f)
                }
            }
        } else {
            FallbackAppIcon(size, shape, 0.5f)
        }
    }
}

/**
 * 操作二次确认半屏抽屉弹窗（紧凑精简排版，支持双操作与三操作形态，自适应系统手势避让）。
 *
 * @param title 弹窗主标题
 * @param message 确认提示详细说明文本
 * @param confirmLabel 主确认按钮文案（如“彻底删除”、“清空”、“保存并退出”）
 * @param dismissLabel 取消按钮文案（默认“取消”）
 * @param destructive 是否为破坏性/危险操作（true 时主按钮背景采用 error 语义色）
 * @param secondaryActionLabel 次要操作文案（如“放弃更改”，为 null 时呈现标准双按钮水平布局）
 * @param secondaryDestructive 次要操作是否带有破坏性色彩（true 时次要操作按钮呈现 error 警告色）
 * @param onSecondaryAction 点击次要操作按钮回调
 * @param onConfirm 点击主确认按钮回调
 * @param onDismiss 点击取消按钮或滑脱关闭回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmActionSheet(
    title: String,
    message: String,
    confirmLabel: String,
    dismissLabel: String = "取消",
    destructive: Boolean = false,
    secondaryActionLabel: String? = null,
    secondaryDestructive: Boolean = false,
    onSecondaryAction: (() -> Unit)? = null,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp, top = 0.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(20.dp))

            if (secondaryActionLabel != null && onSecondaryAction != null) {
                // 三操作模式（如：保存退出 / 放弃更改 / 取消继续编辑）
                Button(
                    onClick = onConfirm,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(0.dp),
                    colors = if (destructive) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    } else {
                        ButtonDefaults.buttonColors()
                    }
                ) {
                    Text(confirmLabel, style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onSecondaryAction,
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(0.dp),
                        colors = if (secondaryDestructive) {
                            ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        } else {
                            ButtonDefaults.outlinedButtonColors()
                        },
                        border = if (secondaryDestructive) {
                            ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                                brush = SolidColor(MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                            )
                        } else {
                            ButtonDefaults.outlinedButtonBorder(enabled = true)
                        }
                    ) {
                        Text(secondaryActionLabel, style = MaterialTheme.typography.labelLarge)
                    }
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(dismissLabel, style = MaterialTheme.typography.labelLarge)
                    }
                }
            } else {
                // 标准双操作模式（水平并排等宽：左取消、右确认）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(dismissLabel, style = MaterialTheme.typography.labelLarge)
                    }
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(0.dp),
                        colors = if (destructive) {
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            )
                        } else {
                            ButtonDefaults.buttonColors()
                        }
                    ) {
                        Text(confirmLabel, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}
