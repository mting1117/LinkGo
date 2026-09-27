package com.moting.linkgo.ui.settings.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AdbGuideDialog(
    onDismissRequest: () -> Unit,
    onCheckPermission: () -> Unit
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val isShizukuGranted = remember { com.moting.linkgo.service.ShizukuManager.isGranted() }
    var isRootAvailable by remember { androidx.compose.runtime.mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        isRootAvailable = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.moting.linkgo.util.privilege.PrivilegeEngine.canExecSu()
        }
    }

    val adbCommand = "adb shell pm grant com.moting.linkgo android.permission.WRITE_SECURE_SETTINGS"

    AlertDialog(
        onDismissRequest = onDismissRequest,
        icon = { Icon(Icons.Default.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = {
            Text(
                "写入安全设置授权",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "开启后可实现无障碍服务永不掉线静默自愈。支持通过电脑 ADB 执行命令，或通过本地特权一键授权：",
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 20.sp
                )

                if (isShizukuGranted || isRootAvailable) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (isShizukuGranted) {
                            FilledTonalButton(
                                onClick = {
                                    val ok = com.moting.linkgo.util.AccessibilityUtils.grantSecureSettingsByShizuku(context)
                                    if (ok) {
                                        Toast.makeText(context, "已通过 Shizuku 成功授予安全设置权限！", Toast.LENGTH_SHORT).show()
                                        onCheckPermission()
                                    } else {
                                        Toast.makeText(context, "Shizuku 授权失败，请重试", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Shizuku 激活")
                            }
                        }
                        if (isRootAvailable) {
                            FilledTonalButton(
                                onClick = {
                                    scope.launch {
                                        val ok = com.moting.linkgo.util.AccessibilityUtils.grantSecureSettingsByRoot(context)
                                        if (ok) {
                                            Toast.makeText(context, "已通过 Root 成功授予安全设置权限！", Toast.LENGTH_SHORT).show()
                                            onCheckPermission()
                                        } else {
                                            Toast.makeText(context, "Root 授权失败，请重试", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Root 激活")
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(12.dp)
                ) {
                    Text(
                        adbCommand,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }

                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("ADB Command", adbCommand))
                        Toast.makeText(context, "命令已复制", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.align(Alignment.End),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("复制命令")
                }
            }
        },
        confirmButton = {
            Button(onClick = onCheckPermission) {
                Text("检测状态")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("关闭")
            }
        }
    )
}
