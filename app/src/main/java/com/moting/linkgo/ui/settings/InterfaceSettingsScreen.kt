package com.moting.linkgo.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moting.linkgo.R
import com.moting.linkgo.ui.components.*
import com.moting.linkgo.viewmodel.SettingsViewModel

/**
 * 界面设置（二级页面）：
 * 包含动态配色、半屏弹窗高斯模糊、悬浮底栏
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InterfaceSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val isAndroid12Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    CollapsingTopBarScaffold(
        title = "界面设置",
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
            item {
                Spacer(modifier = Modifier.height(4.dp))
            }

            item {
                SettingsSection(topLabel = "外观") {
                    // 动态配色
                    SettingItem(
                        headlineText = "动态配色",
                        supportingText = "跟随系统壁纸取色",
                        trailingContent = {
                            PremiumSwitch(
                                checked = uiState.dynamicColorEnabled,
                                onCheckedChange = { viewModel.setDynamicColorEnabled(it) }
                            )
                        }
                    )

                    SettingsSectionDivider()

                    // 半屏弹窗高斯模糊
                    SettingItem(
                        headlineText = "半屏弹窗高斯模糊",
                        supportingText = if (isAndroid12Plus) "半屏弹窗毛玻璃虚化" else "需 Android 12+",
                        supportingTextColor = if (isAndroid12Plus) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline,
                        trailingContent = {
                            PremiumSwitch(
                                checked = uiState.backgroundBlurEnabled && isAndroid12Plus,
                                enabled = isAndroid12Plus,
                                onCheckedChange = { viewModel.setBackgroundBlurEnabled(it) }
                            )
                        }
                    )

                    SettingsSectionDivider()

                    // 悬浮底栏
                    SettingItem(
                        headlineText = "悬浮底栏",
                        supportingText = "底栏悬浮胶囊风格",
                        trailingContent = {
                            PremiumSwitch(
                                checked = uiState.floatingBottomBarEnabled,
                                onCheckedChange = { viewModel.setFloatingBottomBarEnabled(it) }
                            )
                        }
                    )
                }
            }
        }
    }
}
