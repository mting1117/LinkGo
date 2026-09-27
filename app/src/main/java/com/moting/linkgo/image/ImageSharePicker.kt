package com.moting.linkgo.image

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moting.linkgo.R
import com.moting.linkgo.ui.components.CollapsingTopBarScaffold
import com.moting.linkgo.ui.components.NativeOutlinedTextField
import com.moting.linkgo.ui.components.RuleIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 一个可接收图片分享的「分享接口」。
 *
 * 与 `AppInfo` 的区别：粒度到 **Activity**，因为同一个应用可能有多个分享入口
 * （例如"发送给朋友"与"分享到朋友圈"是两个 Activity），图片规则要能精确指定用哪一个。
 */
data class ImageShareEntry(
    /** 承载该分享接口的应用包名 */
    val packageName: String,
    /** 分享接口的完整类名，即 ACTION_SEND 的接收组件 */
    val className: String,
    /** 应用名（选择页的次要信息） */
    val appLabel: String,
    /** 分享接口自身的名称，用于回填规则名称 */
    val entryLabel: String
)

/**
 * 查询系统里所有可接收图片分享的接口（应用 + Activity 粒度）。
 *
 * 数据源是 `ACTION_SEND` 加 image 通配类型的实时解析结果，
 * 因此返回的每一项都**确实能收图**，选择页天然是白名单，用户不可能选到无效目标。
 *
 * `AndroidManifest.xml` 的 queries 已声明该 intent，否则 Android 11+ 的包可见性会返回空列表。
 */
object ImageShareTargets {

    private const val TAG = "LinkGo_ImageShare"

    fun query(context: Context): List<ImageShareEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        val resolves = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "查询图片分享接口失败: ${e.message}")
            return emptyList()
        }

        val entries = mutableListOf<ImageShareEntry>()
        val seen = mutableSetOf<String>()
        resolves.forEach { resolve ->
            val activity = resolve.activityInfo ?: return@forEach
            val key = "${activity.packageName}/${activity.name}"
            if (!seen.add(key)) return@forEach

            // 接口名称优先取 Activity 自己的 label；为空时退回应用名，最后退回类名末段
            val entryLabel = resolve.loadLabel(pm).toString().ifBlank {
                resolve.activityInfo.applicationInfo?.loadLabel(pm)?.toString().orEmpty()
            }.ifBlank { activity.name.substringAfterLast('.') }

            val appLabel = try {
                activity.applicationInfo?.loadLabel(pm)?.toString().orEmpty()
            } catch (e: Exception) {
                ""
            }.ifBlank { activity.packageName }

            entries.add(
                ImageShareEntry(
                    packageName = activity.packageName,
                    className = activity.name,
                    appLabel = appLabel,
                    entryLabel = entryLabel
                )
            )
        }

        // 应用名优先排序，同一应用内按接口名排序，保证列表稳定
        val collator = java.text.Collator.getInstance(java.util.Locale.CHINA)
        val sorted = entries.sortedWith(
            compareBy<ImageShareEntry> { collator.getCollationKey(it.appLabel) }
                .thenBy { collator.getCollationKey(it.entryLabel) }
        )
        Log.i(TAG, "发现 ${sorted.size} 个可接收图片分享的接口")
        return sorted
    }
}

/**
 * 分享接口选择页。
 *
 * 与类名选择页（`ActivityPickerScreen`）同一视觉与交互形态：
 * 折叠顶栏 + 搜索框 + 卡片列表。区别只在数据源——这里只列**能接收图片分享**的接口，
 * 因此用户不可能选到一个跳不出去的目标。
 *
 * 做成独立全屏 composable 而不是走导航路由：图片规则把「包名 + 类名 + 名称」三者
 * 一起消费，走导航 savedStateHandle 需要额外约定编码格式，直接用回调更简单也更不容易出错。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageSharePickerScreen(
    onEntrySelected: (ImageShareEntry) -> Unit,
    onNavigateBack: () -> Unit
) {
    BackHandler { onNavigateBack() }

    val context = LocalContext.current
    var entries by remember { mutableStateOf<List<ImageShareEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        entries = withContext(Dispatchers.IO) {
            ImageShareTargets.query(context)
        }
        loading = false
    }

    val filtered = remember(entries, query) {
        if (query.isBlank()) {
            entries
        } else {
            entries.filter {
                it.appLabel.contains(query, ignoreCase = true) ||
                    it.entryLabel.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true) ||
                    it.className.contains(query, ignoreCase = true)
            }
        }
    }

    CollapsingTopBarScaffold(
        title = "选择分享接口",
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_arrow_left),
                    contentDescription = "返回"
                )
            }
        }
    ) { padding, nestedScrollConnection ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(nestedScrollConnection)
        ) {
            NativeOutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                hint = "搜索应用或分享接口...",
                cornerRadius = 16.dp,
                singleLine = true
            )

            when {
                loading -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                }

                filtered.isEmpty() -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = if (entries.isEmpty()) {
                            "未发现可接收图片的应用。请确认已安装支持图片分享的应用（如微信、QQ、相册）。"
                        } else {
                            "没有匹配的分享接口"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filtered, key = { "${it.packageName}/${it.className}" }) { entry ->
                        Surface(
                            onClick = { onEntrySelected(entry) },
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RuleIcon(
                                    iconPath = null,
                                    targetPackage = entry.packageName,
                                    size = 40.dp,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = entry.entryLabel,
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = entry.appLabel,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = entry.className,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
