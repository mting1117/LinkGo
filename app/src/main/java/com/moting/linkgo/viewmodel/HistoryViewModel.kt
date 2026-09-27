package com.moting.linkgo.viewmodel

import android.app.Application
import android.text.format.DateUtils
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moting.linkgo.data.PackageRepository
import com.moting.linkgo.data.SettingsCache
import com.moting.linkgo.data.SettingsRepository
import com.moting.linkgo.model.JumpRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

enum class HistoryFilter(val label: String) {
    ALL("全部"),
    FAILED("仅失败"),
    MULTI_STEP("多步分发")
}

data class HistoryUiItem(
    val keyId: String,
    val traceIdOrId: String,
    val finalTargetPkg: String,
    val appLabel: String,
    val isMultiStep: Boolean,
    val hasError: Boolean,
    val timeFormatted: String,
    val displayUrl: String,
    val flowDescription: String,
    val timestamp: Long,
    val rawRecords: List<JumpRecord>,
    /** true 表示该条来自图片规则派发，历史卡片据此切换图标与首行文案 */
    val isImage: Boolean = false
)

data class HistoryDateGroup(
    val dateKey: String,
    val dateTitle: String,
    val items: List<HistoryUiItem>
)

data class HistoryUiState(
    val items: List<HistoryUiItem> = emptyList(),
    val dateGroups: List<HistoryDateGroup> = emptyList(),
    val selectedFilter: HistoryFilter = HistoryFilter.ALL,
    val searchQuery: String = "",
    val totalGroupsCount: Int = 0,
    val failCount: Int = 0,
    val multiCount: Int = 0,
    val isEmpty: Boolean = true
)

class HistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SettingsRepository(application)
    private val context = application.applicationContext

    private val _history = MutableStateFlow<List<JumpRecord>>(SettingsCache.jumpHistory)
    val history = _history.asStateFlow()

    private val _selectedFilter = MutableStateFlow(HistoryFilter.ALL)
    val selectedFilter = _selectedFilter.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    val uiState: StateFlow<HistoryUiState> = combine(
        _history,
        _selectedFilter,
        _searchQuery
    ) { rawHistory, filter, query ->
        if (rawHistory.isEmpty()) {
            return@combine HistoryUiState(isEmpty = true)
        }

        // 1. 按 traceId 聚合分组并排序
        val allGroups = rawHistory.groupBy { it.traceId ?: it.id }
            .values
            .map { group -> group.sortedBy { it.stepIndex } }
            .sortedByDescending { it.firstOrNull()?.timestamp ?: 0L }

        val totalGroupsCount = allGroups.size
        val failCount = allGroups.count { g -> g.any { it.executionStatus == 1 } }
        val multiCount = allGroups.count { g -> g.size > 1 }

        // 2. 状态标签初筛
        val filteredByStatus = when (filter) {
            HistoryFilter.ALL -> allGroups
            HistoryFilter.FAILED -> allGroups.filter { g -> g.any { it.executionStatus == 1 } }
            HistoryFilter.MULTI_STEP -> allGroups.filter { g -> g.size > 1 }
        }

        // 3. 转化为预计算的渲染视图模型
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        val uiItems = filteredByStatus.mapNotNull { group ->
            val firstRecord = group.firstOrNull() ?: return@mapNotNull null
            val isMultiStep = group.size > 1
            val hasError = group.any { it.executionStatus == 1 }
            val traceIdOrId = firstRecord.traceId ?: firstRecord.id

            // 格式化卡片时间 (统一为时分)
            val timeFormatted = timeFormat.format(Date(firstRecord.timestamp))

            // 提取目标包名与应用名
            val rawTargetPkg = group.lastOrNull { it.targetPackage.isNotBlank() }?.targetPackage ?: ""
            val finalTargetPkg = if (rawTargetPkg.contains("/")) rawTargetPkg.substringBefore("/") else rawTargetPkg
            val appLabel = if (finalTargetPkg.isNotBlank()) {
                PackageRepository.getAppLabel(context, finalTargetPkg)
            } else ""

            val displayUrl = firstRecord.originalUrl.trim()

            // 流转链路描述
            val rawRuleName = firstRecord.ruleName.removePrefix("[分发] ").removePrefix("[手动选择] ").trim()
            val targetName = if (appLabel.isNotBlank()) appLabel else if (finalTargetPkg.isNotBlank()) finalTargetPkg else "系统分发"
            val flowDescription = if (rawRuleName.isNotBlank() && rawRuleName != targetName) {
                "$rawRuleName → $targetName"
            } else {
                targetName
            }

            // 图片记录：按 kind 判定；无 URL、无匹配条件，首行文案由历史卡片改用「图片」语义渲染
            val isImage = group.any { it.kind == JumpRecord.KIND_IMAGE }

            HistoryUiItem(
                keyId = traceIdOrId,
                traceIdOrId = traceIdOrId,
                finalTargetPkg = finalTargetPkg,
                appLabel = appLabel,
                isMultiStep = isMultiStep,
                hasError = hasError,
                timeFormatted = timeFormatted,
                displayUrl = displayUrl,
                flowDescription = flowDescription,
                timestamp = firstRecord.timestamp,
                rawRecords = group,
                isImage = isImage
            )
        }

        // 4. 关键词即时搜索过滤
        val trimmedQuery = query.trim()
        val finalItems = if (trimmedQuery.isBlank()) {
            uiItems
        } else {
            uiItems.filter { item ->
                item.displayUrl.contains(trimmedQuery, ignoreCase = true) ||
                item.flowDescription.contains(trimmedQuery, ignoreCase = true) ||
                item.appLabel.contains(trimmedQuery, ignoreCase = true) ||
                item.finalTargetPkg.contains(trimmedQuery, ignoreCase = true)
            }
        }

        // 5. 按天组织日期板块
        val dayKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val todayKey = dayKeyFormat.format(Date())
        val yesterdayKey = dayKeyFormat.format(Date(System.currentTimeMillis() - DateUtils.DAY_IN_MILLIS))

        val dateGroups = finalItems
            .groupBy { item -> dayKeyFormat.format(Date(item.timestamp)) }
            .map { (dateKey, itemsInDate) ->
                val dateTitle = when (dateKey) {
                    todayKey -> "$dateKey (今天)"
                    yesterdayKey -> "$dateKey (昨天)"
                    else -> dateKey
                }

                HistoryDateGroup(
                    dateKey = dateKey,
                    dateTitle = dateTitle,
                    items = itemsInDate
                )
            }

        HistoryUiState(
            items = finalItems,
            dateGroups = dateGroups,
            selectedFilter = filter,
            searchQuery = query,
            totalGroupsCount = totalGroupsCount,
            failCount = failCount,
            multiCount = multiCount,
            isEmpty = false
        )
    }.flowOn(Dispatchers.Default).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        HistoryUiState()
    )

    init {
        viewModelScope.launch {
            repository.jumpHistory.collectLatest {
                _history.value = it
            }
        }
    }

    fun setFilter(filter: HistoryFilter) {
        _selectedFilter.value = filter
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun deleteHistoryGroup(traceIdOrId: String) {
        viewModelScope.launch {
            repository.deleteJumpRecordsByTraceId(traceIdOrId)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearHistory()
        }
    }

    fun init(context: android.content.Context? = null) {
        // ViewModel 初始化时已在构造中自动完成数据流监听
    }
}
