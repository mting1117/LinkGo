package com.moting.linkgo.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moting.linkgo.clipboard.ClipboardBackend
import com.moting.linkgo.ui.components.*
import com.moting.linkgo.viewmodel.SettingsViewModel

/**
 * 主设置页面（参考 InstallerX / HyperOS 规范，最深严格两级，无图标纯净版）：
 * 1. 【常规设置】(界面设置 / 通知与超级岛 / 交互设置 —— 统一二级入口)
 * 2. 【功能设置】(剪贴板 / 应用内捕获 / 权限中心 / 自由窗口 / 外部调用 —— 统一二级中枢入口)
 * 3. 【其他设置】(备份恢复 / 关于 LinkGo —— 统一二级入口)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    bottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    onNavigateToInterfaceSettings: () -> Unit,
    onNavigateToNotificationSettings: () -> Unit,
    onNavigateToInteractionSettings: () -> Unit,
    onNavigateToSmallWindow: () -> Unit,
    onNavigateToEdgeGesture: () -> Unit,
    onNavigateToClipboardMonitor: () -> Unit,
    onNavigateToAppLinkCapture: () -> Unit,
    onNavigateToPermissionCenter: () -> Unit,
    onNavigateToExternalCall: () -> Unit,
    onNavigateToBackup: () -> Unit,
    onNavigateToSponsor: () -> Unit = {},
    onNavigateToAbout: () -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    MainPageScaffold(
        title = "设置"
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding()),
            contentPadding = PaddingValues(
                bottom = bottomPadding + 32.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
            }

            // 1. 常规设置 (界面 / 通知 / 交互 二级入口)
            item {
                SettingsSection(topLabel = "常规设置") {
                    // 界面设置
                    SettingItem(
                        headlineText = "界面设置",
                        supportingText = "动态配色、毛玻璃虚化与悬浮底栏",
                        trailingContent = { NavigationArrow() },
                        modifier = Modifier.clickable { onNavigateToInterfaceSettings() }
                    )

                    SettingsSectionDivider()

                    // 通知与超级岛
                    SettingItem(
                        headlineText = "通知与超级岛",
                        supportingText = "通知栏、实时活动与超级岛",
                        trailingContent = { NavigationArrow() },
                        modifier = Modifier.clickable { onNavigateToNotificationSettings() }
                    )

                    SettingsSectionDivider()

                    // 交互设置
                    SettingItem(
                        headlineText = "交互设置",
                        supportingText = "倒计时、自动解冻与任务列表可见性",
                        trailingContent = { NavigationArrow() },
                        modifier = Modifier.clickable { onNavigateToInteractionSettings() }
                    )
                }
            }

            // 2. 功能设置 (核心能力的成熟二级配置入口)
            item {
                SettingsSection(topLabel = "功能设置") {
                    // 快捷手势 (二级入口，第一位)
                    val edgeGestureSubText = if (uiState.edgeGestureConfig.enabled) {
                        "已启用 (滑动触发${uiState.edgeGestureConfig.swipeAction.title})"
                    } else {
                        "侧边手势与雷达探照直达"
                    }
                    SettingItem(
                        headlineText = "快捷手势",
                        supportingText = edgeGestureSubText,
                        trailingContent = { NavigationArrow() },
                        modifier = Modifier.clickable { onNavigateToEdgeGesture() }
                    )

                    SettingsSectionDivider()

                    // 剪贴板后台监听 (二级入口)
                    val clipSubText = if (uiState.clipboardMonitorEnabled) {
                        "已启用 (${ClipboardBackend.displayName(uiState.clipboardMonitorBackend)})"
                    } else {
                        "自动识别复制的链接"
                    }
                    SettingItem(
                        headlineText = "剪贴板后台监听",
                        supportingText = clipSubText,
                        trailingContent = { NavigationArrow() },
                        modifier = Modifier.clickable { onNavigateToClipboardMonitor() }
                    )

                    SettingsSectionDivider()

                    // 应用内链接捕获 (二级入口)
                    val appLinkSubText = when (uiState.appLinkCaptureMode) {
                        1 -> "拦截模式 (已接管 ${uiState.appLinkCapturedAppsCount} 个应用)"
                        2 -> "询问模式 (已接管 ${uiState.appLinkCapturedAppsCount} 个应用)"
                        else -> "接管微信、QQ等内嵌网页跳转"
                    }
                    SettingItem(
                        headlineText = "应用内链接捕获",
                        supportingText = appLinkSubText,
                        trailingContent = { NavigationArrow() },
                        modifier = Modifier.clickable { onNavigateToAppLinkCapture() }
                    )

                    SettingsSectionDivider()

                    // 权限中心 (二级入口)
                    SettingItem(
                        headlineText = "权限中心",
                        supportingText = "后台特权与系统授权管理",
                        trailingContent = { NavigationArrow() },
                        modifier = Modifier.clickable { onNavigateToPermissionCenter() }
                    )

                    SettingsSectionDivider()

                    // 自由窗口 (二级入口)
                    SettingItem(
                        headlineText = "自由窗口",
                        supportingText = "小窗启动尺寸与居中位置",
                        trailingContent = { NavigationArrow() },
                        modifier = Modifier.clickable { onNavigateToSmallWindow() }
                    )

                    SettingsSectionDivider()

                    // 外部调用 (二级入口)
                    SettingItem(
                        headlineText = "外部调用",
                        supportingText = "第三方自动化与快捷指令调起",
                        trailingContent = { NavigationArrow() },
                        modifier = Modifier.clickable { onNavigateToExternalCall() }
                    )
                }
            }

            // 3. 其他设置 (二级入口)
            item {
                SettingsSection(topLabel = "其他设置") {
                    // 数据备份 (二级入口)
                    SettingItem(
                        headlineText = "备份与恢复",
                        supportingText = "本地与 WebDAV 云备份",
                        trailingContent = { NavigationArrow() },
                        modifier = Modifier.clickable { onNavigateToBackup() }
                    )

                    SettingsSectionDivider()

                    // 赞助支持 (二级入口)
                    SettingItem(
                        headlineText = "赞助与支持",
                        supportingText = "请开发者喝杯咖啡，支持持续维护",
                        trailingContent = { NavigationArrow() },
                        modifier = Modifier.clickable { onNavigateToSponsor() }
                    )

                    SettingsSectionDivider()

                    // 关于 (二级入口)
                    SettingItem(
                        headlineText = "关于 LinkGo",
                        supportingText = "版本信息与开发团队",
                        trailingContent = { NavigationArrow() },
                        modifier = Modifier.clickable { onNavigateToAbout() }
                    )
                }
            }
        }
    }
}

