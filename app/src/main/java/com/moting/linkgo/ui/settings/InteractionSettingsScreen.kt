package com.moting.linkgo.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moting.linkgo.R
import com.moting.linkgo.ui.components.*
import com.moting.linkgo.viewmodel.SettingsViewModel

/**
 * 交互设置（二级页面）：
 * 包含选择器倒计时、跳转前自动解冻应用、任务列表可见性
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InteractionSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var timerInput by remember(uiState.browserSelectorTimer) {
        mutableStateOf(uiState.browserSelectorTimer.toString())
    }

    CollapsingTopBarScaffold(
        title = "交互设置",
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
                SettingsSection(topLabel = "行为") {
                    // 选择器倒计时
                    SettingItem(
                        headlineText = "选择器倒计时",
                        supportingText = "多浏览器等待选择时间",
                        trailingContent = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                BasicTextField(
                                    value = timerInput,
                                    onValueChange = { newValue ->
                                        if (newValue.length <= 3 && newValue.all { it.isDigit() }) {
                                            timerInput = newValue
                                            newValue.toIntOrNull()?.let {
                                                viewModel.onBrowserSelectorTimerChanged(it)
                                            }
                                        }
                                    },
                                    modifier = Modifier.width(IntrinsicSize.Min).widthIn(min = 20.dp),
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                                        textAlign = TextAlign.Center,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                                )
                                Text(
                                    "秒",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }
                        }
                    )

                    SettingsSectionDivider()

                    // 跳转前自动解冻应用
                    SettingItem(
                        headlineText = "跳转前自动解冻应用",
                        supportingText = "跳转时自动解冻目标应用",
                        trailingContent = {
                            PremiumSwitch(
                                checked = uiState.autoUnfreezeEnabled,
                                onCheckedChange = { viewModel.setAutoUnfreezeEnabled(it) }
                            )
                        }
                    )

                    AnimatedVisibility(
                        visible = uiState.autoUnfreezeEnabled,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column {
                            SettingsSectionDivider()
                            SettingItem(
                                headlineText = "解冻时显示提示",
                                supportingText = "弹出解冻结果提示",
                                trailingContent = {
                                    PremiumSwitch(
                                        checked = uiState.unfreezeShowToast,
                                        onCheckedChange = { viewModel.setUnfreezeShowToast(it) }
                                    )
                                }
                            )
                        }
                    }

                    SettingsSectionDivider()

                    // LinkGo 自身在最近任务中的可见性
                    SettingItem(
                        headlineText = "任务列表可见性",
                        supportingText = "LinkGo 自身不出现在最近任务",
                        trailingContent = {
                            PremiumSwitch(
                                checked = uiState.excludeFromRecents,
                                onCheckedChange = { viewModel.setExcludeFromRecents(it) }
                            )
                        }
                    )
                }
            }
        }
    }
}
