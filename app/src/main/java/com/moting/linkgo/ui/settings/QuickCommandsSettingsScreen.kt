package com.moting.linkgo.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moting.linkgo.R
import com.moting.linkgo.ui.components.*
import com.moting.linkgo.ui.settings.components.QuickCommandItem

/**
 * 外部调用（二级设置页面）：
 * 集中管理 LinkGo 对外调起入口的 Activity 组件地址，可直接复制至 Tasker、MacroDroid 等自动化工具或系统侧边快捷手势。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickCommandsSettingsScreen(
    onNavigateBack: () -> Unit
) {
    CollapsingTopBarScaffold(
        title = "外部调用",
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_arrow_left),
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
            item {
                Spacer(modifier = Modifier.height(4.dp))
            }

            item {
                SettingsSection(topLabel = "调用入口") {
                    QuickCommandItem(
                        headlineText = "滑动直达",
                        supportingText = "呼出探照指针，松手直达链接",
                        address = "com.moting.linkgo.RadarDirectActivity"
                    )

                    SettingsSectionDivider()

                    QuickCommandItem(
                        headlineText = "屏幕识别",
                        supportingText = "静默捕获屏幕并提取网页链接",
                        address = "com.moting.linkgo.ui.LinkHighlighterActivity"
                    )

                    SettingsSectionDivider()

                    QuickCommandItem(
                        headlineText = "剪贴板分析",
                        supportingText = "提取并深度解析系统剪贴板",
                        address = "com.moting.linkgo.ClipboardAnalysisActivity"
                    )
                }
            }

            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "使用提示",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "上述组件均支持通过外部意图（Intent）显式调起。您可以将完整类名复制到 Tasker、MacroDroid、系统侧边栏或手势导航工具中作为动作执行，实现全局一键快速触发。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                            lineHeight = MaterialTheme.typography.bodySmall.lineHeight
                        )
                    }
                }
            }

            item {
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
