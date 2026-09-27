package com.moting.linkgo.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moting.linkgo.BuildConfig
import com.moting.linkgo.R
import com.moting.linkgo.ui.components.CollapsingTopBarScaffold
import com.moting.linkgo.ui.components.SettingItem
import com.moting.linkgo.ui.components.SettingsSection
import com.moting.linkgo.ui.components.SettingsSectionDivider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current

    // 安全加载应用自适应图标 (兼容 AdaptiveIconDrawable / BitmapDrawable / VectorDrawable)
    val appIcon: ImageBitmap? = remember(context) {
        runCatching {
            val d = context.packageManager.getApplicationIcon(context.packageName)
            val width = d.intrinsicWidth.takeIf { it > 0 } ?: 192
            val height = d.intrinsicHeight.takeIf { it > 0 } ?: 192
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            d.setBounds(0, 0, canvas.width, canvas.height)
            d.draw(canvas)
            bmp.asImageBitmap()
        }.getOrNull()
    }

    fun copyToClipboard(label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "已复制 $label", Toast.LENGTH_SHORT).show()
    }

    CollapsingTopBarScaffold(
        title = "关于 LinkGo",
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
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. 应用图标与基础信息
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (appIcon != null) {
                        Image(
                            bitmap = appIcon,
                            contentDescription = "LinkGo Icon",
                            modifier = Modifier
                                .size(80.dp)
                                .clip(RoundedCornerShape(20.dp))
                        )
                    } else {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(80.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    ImageVector.vectorResource(id = R.drawable.ic_iconoir_link),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(40.dp)
                                )
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = "LinkGo",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Black,
                            letterSpacing = (-0.5).sp
                        )
                    )
                    
                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 2. 交流与分享
            item {
                SettingsSection(topLabel = "交流与分享") {
                    SettingItem(
                        headlineText = "QQ 交流群",
                        supportingText = "1093989249",
                        modifier = Modifier.clickable {
                            val groupUrl = "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=1093989249&card_type=group&source=qrcode"
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(groupUrl)))
                            } catch (_: Exception) {
                                copyToClipboard("QQ 群号", "1093989249")
                            }
                        }
                    )
                    SettingsSectionDivider()
                    SettingItem(
                        headlineText = "QQ 频道",
                        supportingText = "加入 LinkGo 频道获取最新动态",
                        modifier = Modifier.clickable {
                            val channelUrl = "https://pd.qq.com/s/1467ko7q?businessType=5"
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(channelUrl)))
                            } catch (_: Exception) {
                                copyToClipboard("频道链接", channelUrl)
                            }
                        }
                    )
                }
            }

            // 3. 开发团队
            item {
                SettingsSection(topLabel = "开发团队") {
                    SettingItem(
                        headlineText = "墨汀 (Moting)",
                        supportingText = "主开发者 / UI 设计"
                    )
                    SettingsSectionDivider()
                    SettingItem(
                        headlineText = "技术支持",
                        supportingText = "LumiaGG"
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(32.dp))
                Text(
                    text = "© 2026 Moting. All rights reserved.",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 32.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
    }
}
