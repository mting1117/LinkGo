package com.moting.linkgo.ui.history

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moting.linkgo.R
import com.moting.linkgo.data.PackageRepository
import com.moting.linkgo.data.SettingsRepository
import com.moting.linkgo.model.ImageRule
import com.moting.linkgo.model.JumpRecord
import com.moting.linkgo.ui.components.AsyncAppIcon
import com.moting.linkgo.ui.components.ConfirmActionSheet
import com.moting.linkgo.ui.components.MainPageScaffold
import com.moting.linkgo.util.WindowRouter
import com.moting.linkgo.viewmodel.HistoryViewModel
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.*

/**
 * 全屏历史记录详情页面
 * 操作原地融合（重试融合至顶部应用卡片、复制融合至链接卡片、编辑融合至规则卡片），彻底告别底栏
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryDetailScreen(
    traceId: String,
    onNavigateBack: () -> Unit,
    onLocateRule: (String) -> Unit,
    viewModel: HistoryViewModel = viewModel()
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val historyList by viewModel.history.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.init(context)
    }

    // 根据 traceId 或 id 查找对应的一组跳转记录
    val group = remember(historyList, traceId) {
        historyList.filter { (it.traceId ?: it.id) == traceId }.sortedBy { it.stepIndex }
    }

    if (group.isEmpty()) {
        MainPageScaffold(
            title = "记录详情",
            navigationIcon = {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        ImageVector.vectorResource(id = R.drawable.ic_iconoir_arrow_left),
                        contentDescription = "返回"
                    )
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                if (historyList.isEmpty()) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text(
                        text = "未找到该条跳转记录",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        return
    }

    val firstRecord = group.first()
    val lastRecord = group.last()
    val isMultiStep = group.size > 1
    val hasError = group.any { it.executionStatus == 1 }
    val isImageRule = remember(group) { group.any { it.kind == JumpRecord.KIND_IMAGE } }

    // 格式化完整时间
    val fullTime = remember(firstRecord.timestamp) {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(firstRecord.timestamp))
    }

    // 目标应用包名与类名
    val rawTargetPkg = remember(group) {
        group.lastOrNull { it.targetPackage.isNotBlank() }?.targetPackage ?: ""
    }
    val finalTargetPkg = remember(rawTargetPkg) {
        if (rawTargetPkg.contains("/")) rawTargetPkg.substringBefore("/") else rawTargetPkg
    }
    val targetClassFromPkg = remember(rawTargetPkg) {
        if (rawTargetPkg.contains("/")) rawTargetPkg.substringAfter("/") else null
    }

    val editableRuleId = remember(group) { group.firstOrNull { it.ruleId != null }?.ruleId }

    // 若是图片规则且存在关联规则 ID，尝试异步查询完整的 ImageRule 获取目标类名与窗口参数
    val imageRule by produceState<ImageRule?>(
        initialValue = com.moting.linkgo.data.SettingsCache.imageRules.firstOrNull { it.id == editableRuleId },
        editableRuleId
    ) {
        if (isImageRule && editableRuleId != null) {
            val repo = SettingsRepository(context)
            val rule = runCatching { repo.imageRules.first().firstOrNull { it.id == editableRuleId } }.getOrNull()
            if (rule != null) {
                value = rule
            }
        }
    }

    val finalTargetClass = remember(targetClassFromPkg, imageRule) {
        targetClassFromPkg?.takeIf { it.isNotBlank() } ?: imageRule?.targetClass?.takeIf { it.isNotBlank() }
    }

    val appLabel = remember(finalTargetPkg) {
        if (finalTargetPkg.isNotBlank()) PackageRepository.getAppLabel(context, finalTargetPkg) else "系统分发"
    }

    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    MainPageScaffold(
        title = "记录详情",
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    ImageVector.vectorResource(id = R.drawable.ic_iconoir_arrow_left),
                    contentDescription = "返回"
                )
            }
        },
        actions = {
            IconButton(onClick = { showDeleteConfirmDialog = true }) {
                Icon(
                    imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_trash),
                    contentDescription = "删除记录",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // 1. 目标应用与流转状态卡片 (原地融合：重试跳转按钮)
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (finalTargetPkg.isNotBlank()) {
                        AsyncAppIcon(
                            packageName = finalTargetPkg,
                            size = 48.dp,
                            shape = RoundedCornerShape(14.dp)
                        )
                    } else {
                        Box(
                            modifier = Modifier.size(48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = com.moting.linkgo.R.drawable.ic_hub_primary),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }

                    Spacer(Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = appLabel,
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 17.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            Surface(
                                color = if (hasError) MaterialTheme.colorScheme.errorContainer else if (isMultiStep) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = if (hasError) "失败" else if (isMultiStep) "${group.size}步" else "成功",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
                                    color = if (hasError) MaterialTheme.colorScheme.onErrorContainer else if (isMultiStep) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Spacer(Modifier.height(3.dp))

                        Text(
                            text = fullTime,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    // 原地融合：重试分发按钮（仅文本链接支持重试，图片是一次性流数据）
                    if (!isImageRule) {
                        FilledTonalButton(
                            onClick = {
                                WindowRouter.openBrowser(context, firstRecord.originalUrl)
                                Toast.makeText(context, "已重新触发分发", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Icon(
                                imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_rocket),
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("重试", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 2. 核心大区块：链接详情（文本规则）或派发详情（图片规则）
            if (isImageRule) {
                Text(
                    text = "派发详情",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                Spacer(Modifier.height(8.dp))

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.7f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        DetailInfoRow(
                            label = "交互意图",
                            value = "图片分享投递 (ACTION_SEND)",
                            isBold = true
                        )

                        Spacer(Modifier.height(10.dp))
                        DetailInfoRow(
                            label = "数据类型",
                            value = "image/* (单图流数据)",
                            isMonospace = true
                        )

                        val launchModeText = when (imageRule?.ruleLaunchMode ?: -1) {
                            5 -> "小窗模式启动"
                            1 -> "全屏模式启动"
                            else -> "跟随全局 / 系统默认"
                        }
                        Spacer(Modifier.height(10.dp))
                        DetailInfoRow(
                            label = "窗口模式",
                            value = launchModeText
                        )

                        val recentsText = if (imageRule?.excludeFromRecents == true) {
                            "不保留后台任务卡片 (EXCLUDE_FROM_RECENTS)"
                        } else {
                            "正常保留最近任务卡片"
                        }
                        Spacer(Modifier.height(10.dp))
                        DetailInfoRow(
                            label = "任务策略",
                            value = recentsText
                        )
                    }
                }
            } else {
                Text(
                    text = "链接详情",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                Spacer(Modifier.height(8.dp))

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.7f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        // 原始捕获链接
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "捕获链接 (原始输入)",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                ),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.clickable {
                                    clipboardManager.setText(AnnotatedString(firstRecord.originalUrl))
                                    Toast.makeText(context, "原始链接已复制", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_copy),
                                        contentDescription = "复制",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = "复制",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(6.dp))

                        SelectionContainer {
                            Text(
                                text = firstRecord.originalUrl,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    lineHeight = 20.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.5.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        // 如果最终生成的链接不同，对比高亮展示最终结果
                        val finalResultUrl = lastRecord.resultUrl
                        if (!finalResultUrl.isNullOrBlank() && finalResultUrl != firstRecord.originalUrl) {
                            Spacer(Modifier.height(14.dp))
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                thickness = 0.8.dp
                            )
                            Spacer(Modifier.height(14.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "最终跳转链接 (转换/清洗后)",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    ),
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier.clickable {
                                        clipboardManager.setText(AnnotatedString(finalResultUrl))
                                        Toast.makeText(context, "最终链接已复制", Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_copy),
                                            contentDescription = "复制",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = "复制",
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(6.dp))

                            SelectionContainer {
                                Text(
                                    text = finalResultUrl,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        lineHeight = 20.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.5.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 3. 规则匹配与流转配置卡片 (原地融合：定位规则按钮)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "分发规则与配置",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (editableRuleId != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.clickable { onLocateRule(editableRuleId) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_position),
                                contentDescription = "定位规则",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "定位规则",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    val rawRuleName = firstRecord.ruleName.removePrefix("[分发] ").removePrefix("[手动选择] ").trim()

                    DetailInfoRow(
                        label = "命中规则",
                        value = if (rawRuleName.isNotBlank()) rawRuleName else if (isImageRule) "图片规则直达" else "备选分发",
                        isBold = true
                    )

                    if (!firstRecord.matchTypeName.isNullOrBlank()) {
                        Spacer(Modifier.height(10.dp))
                        DetailInfoRow(label = "匹配模式", value = firstRecord.matchTypeName)
                    }

                    if (!firstRecord.rulePattern.isNullOrBlank()) {
                        Spacer(Modifier.height(10.dp))
                        DetailInfoRow(
                            label = "匹配规则",
                            value = firstRecord.rulePattern,
                            isMonospace = true
                        )
                    }

                    if (finalTargetPkg.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "目标包名",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(84.dp)
                            )
                            SelectionContainer(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = finalTargetPkg,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.5.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            IconButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(finalTargetPkg))
                                    Toast.makeText(context, "包名已复制", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_copy),
                                    contentDescription = "复制包名",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }

                    if (!finalTargetClass.isNullOrBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isImageRule) "分享入口" else "目标类名",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(84.dp)
                            )
                            SelectionContainer(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = finalTargetClass,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp
                                    ),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(finalTargetClass))
                                    Toast.makeText(context, "类名已复制", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = ImageVector.vectorResource(id = R.drawable.ic_iconoir_copy),
                                    contentDescription = "复制类名",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 4. 多步分发链路 Pipeline (若有)
            if (isMultiStep) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "多步流转流水线",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                Spacer(Modifier.height(8.dp))

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.7f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        group.forEachIndexed { index, record ->
                            val isLast = index == group.size - 1
                            Row(verticalAlignment = Alignment.Top) {
                                Surface(
                                    shape = CircleShape,
                                    color = if (isLast) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = "${index + 1}",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            ),
                                            color = Color.White
                                        )
                                    }
                                }

                                Spacer(Modifier.width(12.dp))

                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(bottom = if (!isLast) 16.dp else 0.dp)
                                ) {
                                    Text(
                                        text = record.ruleName.removePrefix("[分发] ").removePrefix("[手动选择] "),
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    val stepUrl = record.resultUrl ?: record.originalUrl
                                    Spacer(Modifier.height(2.dp))
                                    SelectionContainer {
                                        Text(
                                            text = stepUrl,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontSize = 12.sp,
                                                fontFamily = FontFamily.Monospace
                                            ),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 5. 错误诊断日志 (若失败)
            val errorRecord = group.firstOrNull { it.executionStatus == 1 && !it.errorMessage.isNullOrBlank() }
            if (errorRecord != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "错误日志",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            clipboardManager.setText(AnnotatedString(errorRecord.errorMessage ?: ""))
                            Toast.makeText(context, "错误日志已复制", Toast.LENGTH_SHORT).show()
                        }
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "点击复制异常堆栈",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        SelectionContainer {
                            Text(
                                text = errorRecord.errorMessage ?: "",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 11.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 16.sp
                                ),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            // 底部安全边距 (避让手势小白条)
            Spacer(modifier = Modifier.height(24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()))
        }
    }

    if (showDeleteConfirmDialog) {
        ConfirmActionSheet(
            title = "删除记录",
            message = "确定要删除该条跳转记录吗？此操作无法撤销。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                viewModel.deleteHistoryGroup(traceId)
                showDeleteConfirmDialog = false
                onNavigateBack()
                Toast.makeText(context, "记录已删除", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showDeleteConfirmDialog = false }
        )
    }
}

@Composable
private fun DetailInfoRow(
    label: String,
    value: String,
    isBold: Boolean = false,
    isMonospace: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(84.dp)
        )
        SelectionContainer(modifier = Modifier.weight(1f)) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal,
                    fontFamily = if (isMonospace) FontFamily.Monospace else FontFamily.Default,
                    fontSize = if (isMonospace) 12.5.sp else 14.sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
