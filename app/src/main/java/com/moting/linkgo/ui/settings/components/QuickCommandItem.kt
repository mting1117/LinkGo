package com.moting.linkgo.ui.settings.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moting.linkgo.R
import com.moting.linkgo.ui.components.SettingItem

/**
 * 外部调用条目
 *
 * 采用剪贴板变动广播布局风格：
 * 上半部分展示功能标题与用途说明，下半部分横排展示外部调起所需的完整类名及右侧复制按钮。
 * 点击复制按钮或地址区域即可一键复制到剪贴板，方便粘贴到自动化工具中。
 */
@Composable
fun QuickCommandItem(
    headlineText: String,
    supportingText: String,
    address: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val copyAddress = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("LinkGo 外部调用", address))
        Toast.makeText(context, "类名已复制", Toast.LENGTH_SHORT).show()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = copyAddress)
    ) {
        SettingItem(
            headlineText = headlineText,
            supportingText = supportingText,
            trailingContent = null
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = address,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = copyAddress
            ) {
                Icon(
                    imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_copy),
                    contentDescription = "复制类名",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

