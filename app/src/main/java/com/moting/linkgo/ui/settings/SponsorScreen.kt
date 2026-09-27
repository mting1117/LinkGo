package com.moting.linkgo.ui.settings

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moting.linkgo.R
import com.moting.linkgo.ui.components.CollapsingTopBarScaffold
import kotlinx.coroutines.*

/**
 * 赞助码临时存储与延时清理管理器（进程级后台管理）
 */
private object SponsorImageManager {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * 保存赞助码到相册，并在 5 分钟后自动删除
     */
    fun saveTempImage(
        context: Context,
        onSuccess: (Uri) -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                val bitmap = BitmapFactory.decodeResource(appContext.resources, R.drawable.img_sponsor_wechat)
                    ?: throw IllegalStateException("无法解码赞赏码资源")

                val filename = "LinkGo_Sponsor_Temp_${System.currentTimeMillis()}.jpg"
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LinkGo")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }

                val resolver = appContext.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("无法在相册创建媒体项")

                resolver.openOutputStream(uri)?.use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
                } ?: throw IllegalStateException("无法写入图片数据")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } else {
                    @Suppress("DEPRECATION")
                    appContext.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri))
                }

                // 启动 5 分钟延时删除任务（300 秒）
                scheduleAutoDelete(appContext, uri)

                withContext(Dispatchers.Main) {
                    onSuccess(uri)
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    onFailure(e)
                }
            }
        }
    }

    private fun scheduleAutoDelete(appContext: Context, uri: Uri) {
        scope.launch {
            delay(5 * 60 * 1000L) // 5 分钟
            runCatching {
                appContext.contentResolver.delete(uri, null, null)
            }
        }
    }
}

/**
 * 赞助与支持页面
 * 展示微信赞赏码，支持临时保存图片并在5分钟后自动删除，以及快捷调起微信扫一扫。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SponsorScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    var isSaving by remember { mutableStateOf(false) }

    // 唤起微信扫一扫界面
    fun openWeChatScan() {
        val launchSuccess = runCatching {
            // 方案 1：优先采用标准的显式 LauncherUI + 扫一扫快捷标志位（兼容性最好，不被任何系统权限限制）
            // 对应：#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;package=com.tencent.mm;component=com.tencent.mm/.ui.LauncherUI;B.LauncherUI.From.Scaner.Shortcut=true;end
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName("com.tencent.mm", "com.tencent.mm.ui.LauncherUI")
                putExtra("LauncherUI.From.Scaner.Shortcut", true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
            true
        }.recoverCatching {
            // 方案 2：尝试 BIZSHORTCUT 快捷动作
            val shortcutIntent = Intent("com.tencent.mm.action.BIZSHORTCUT").apply {
                setPackage("com.tencent.mm")
                component = ComponentName("com.tencent.mm", "com.tencent.mm.ui.LauncherUI")
                putExtra("LauncherUI.From.Scaner.Shortcut", true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(shortcutIntent)
            true
        }.recoverCatching {
            // 方案 3：降级尝试系统 Scheme
            val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse("weixin://scanqrcode")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallbackIntent)
            true
        }.getOrDefault(false)

        if (!launchSuccess) {
            Toast.makeText(context, "无法拉起微信扫一扫，请确保已安装微信应用", Toast.LENGTH_SHORT).show()
        }
    }

    // 执行临时保存并拉起微信扫一扫
    fun handleSaveAndScan() {
        if (isSaving) return
        isSaving = true
        SponsorImageManager.saveTempImage(
            context = context,
            onSuccess = {
                isSaving = false
                Toast.makeText(context, "赞助码已临时保存（5分钟后自动删除），请在微信扫一扫右上角选择相册识别", Toast.LENGTH_LONG).show()
                openWeChatScan()
            },
            onFailure = { error ->
                isSaving = false
                Toast.makeText(context, "保存图片失败: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // 仅临时保存到相册
    fun handleSaveOnly() {
        if (isSaving) return
        isSaving = true
        SponsorImageManager.saveTempImage(
            context = context,
            onSuccess = {
                isSaving = false
                Toast.makeText(context, "赞助码已临时保存至相册，将在 5 分钟后自动删除", Toast.LENGTH_SHORT).show()
            },
            onFailure = { error ->
                isSaving = false
                Toast.makeText(context, "保存图片失败: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    CollapsingTopBarScaffold(
        title = "赞助与支持",
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    ImageVector.vectorResource(id = R.drawable.ic_iconoir_arrow_left),
                    contentDescription = "返回"
                )
            }
        }
    ) { padding, nestedScrollConnection ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(nestedScrollConnection),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = padding.calculateTopPadding() + 16.dp,
                bottom = padding.calculateBottomPadding() + 32.dp
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // 1. 微信赞赏码高清卡片
            item {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color.White,
                            shadowElevation = 2.dp,
                            modifier = Modifier.padding(bottom = 16.dp)
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.img_sponsor_wechat),
                                contentDescription = "微信赞赏码",
                                modifier = Modifier
                                    .size(260.dp)
                                    .clip(RoundedCornerShape(16.dp)),
                                contentScale = ContentScale.Fit
                            )
                        }

                        Text(
                            text = "微信赞赏码",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "请开发者喝杯咖啡 ☕ 支持 LinkGo 持续演进",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 2. 操作按钮区域
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 主操作按钮：保存并打开微信扫一扫
                    Button(
                        onClick = { handleSaveAndScan() },
                        enabled = !isSaving,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        Icon(
                            imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_scan_qr_code),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isSaving) "正在处理..." else "保存并打开微信扫一扫",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }

                    // 次操作按钮：临时保存赞助码到相册 (5分钟)
                    OutlinedButton(
                        onClick = { handleSaveOnly() },
                        enabled = !isSaving,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        Icon(
                            imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_download),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "临时保存赞助码到相册 (5分钟)",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "💡 提示：赞助码保存后仅临时保留 5 分钟，随后将自动从相册清理，不占用存储空间。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
