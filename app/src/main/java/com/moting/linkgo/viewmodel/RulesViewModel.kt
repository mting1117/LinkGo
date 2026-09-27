package com.moting.linkgo.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moting.linkgo.data.PackageRepository
import com.moting.linkgo.data.SettingsRepository
import com.moting.linkgo.model.DispatchRule
import com.moting.linkgo.model.ExtractPattern
import com.moting.linkgo.model.MatchType
import com.moting.linkgo.model.ResolutionStrategy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

enum class ExportTarget {
    CLIPBOARD,
    FILE
}


class RulesViewModel : ViewModel() {
    private var repository: SettingsRepository? = null
    
    private val _isSelectionMode = MutableStateFlow(false)
    val isSelectionMode = _isSelectionMode.asStateFlow()

    private val _selectedRuleIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedRuleIds = _selectedRuleIds.asStateFlow()

    // 链接提取规则选择态（独立于 rules 选择态，供链接提取页长按批量操作复用）
    private val _isExtractionSelectionMode = MutableStateFlow(false)
    val isExtractionSelectionMode = _isExtractionSelectionMode.asStateFlow()

    private val _selectedExtractionPatternIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedExtractionPatternIds = _selectedExtractionPatternIds.asStateFlow()

    // 规则定位高亮事件流 (replay=1 确保切换页面后不丢事件，带时间戳确保重复点击同一个规则也能触发)
    data class LocateRuleEvent(val ruleId: String, val timestamp: Long = System.currentTimeMillis())
    private val _locateRuleEvent = MutableSharedFlow<LocateRuleEvent>(replay = 1, extraBufferCapacity = 1)
    val locateRuleEvent = _locateRuleEvent.asSharedFlow()

    fun locateRule(ruleId: String) {
        _locateRuleEvent.tryEmit(LocateRuleEvent(ruleId))
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun clearLocateRuleEvent() {
        _locateRuleEvent.resetReplayCache()
    }

    // 链接提取规则相关状态
    private val _normalizationEnabled = MutableStateFlow(com.moting.linkgo.data.SettingsCache.normalizationEnabled)
    val normalizationEnabled = _normalizationEnabled.asStateFlow()

    private val _extractionPatterns = MutableStateFlow(com.moting.linkgo.model.ExtractPattern.builtinDefaults())
    val extractionPatterns = _extractionPatterns.asStateFlow()

    private val _normalizationRegex = MutableStateFlow(com.moting.linkgo.util.UrlUtils.DEFAULT_REGEX)
    val normalizationRegex = _normalizationRegex.asStateFlow()

    private val _normalizationTemplate = MutableStateFlow(com.moting.linkgo.util.UrlUtils.DEFAULT_TEMPLATE)
    val normalizationTemplate = _normalizationTemplate.asStateFlow()

    private val _fallbackBrowser = MutableStateFlow<String>(com.moting.linkgo.data.SettingsCache.fallbackBrowser ?: "")
    val fallbackBrowser = _fallbackBrowser.asStateFlow()

    private val _windowConfig = MutableStateFlow(com.moting.linkgo.data.SettingsCache.windowConfig)
    val windowConfig = _windowConfig.asStateFlow()

    private val _rules = MutableStateFlow<List<DispatchRule>>(emptyList())
    val rules = _rules.asStateFlow()

    // 图片规则：与文本规则并列在同一张列表里（见 RulesScreen 的统一列表项），
    // 顺序语义一致（列表越靠前越优先）
    private val _imageRules = MutableStateFlow<List<com.moting.linkgo.model.ImageRule>>(emptyList())
    val imageRules = _imageRules.asStateFlow()

    // 选择态刻意只保留一套：两类规则共用同一个 _isSelectionMode 与 _selectedRuleIds。
    // 之前图片规则另有一套（isImageSelectionMode / selectedImageRuleIds），
    // 于是同一个长按动作在两类规则上表现不同——既然它们在列表里是平级的，
    // 选择态就必须是同一份，批量操作再按 id 归属分流。

    // --- 衍生分组流 ---
    val reDispatchRules = _rules.map { list ->
        list.filter { it.resolveStrategy == ResolutionStrategy.RE_DISPATCH }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val standardRules = _rules.map { list ->
        list.filter { it.resolveStrategy != ResolutionStrategy.RE_DISPATCH }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiEvent = kotlinx.coroutines.flow.MutableSharedFlow<String>()
    val uiEvent = _uiEvent.asSharedFlow()

    fun initRepository(context: Context) {
        if (repository == null) {
            repository = SettingsRepository(context.applicationContext)
            viewModelScope.launch {
                repository!!.dispatchRules.collectLatest { incoming ->
                    // 对来自底层的规则进行内存级规范化，确保分发在上、跳转在下
                    val reDispatch = incoming.filter { it.resolveStrategy == ResolutionStrategy.RE_DISPATCH }
                    val standard = incoming.filter { it.resolveStrategy != ResolutionStrategy.RE_DISPATCH }
                    val normalized = reDispatch + standard
                    
                    _rules.value = normalized
                    
                    // 实时脏数据自愈：如果底层存的是老版本的混排数据，则顺手写回干净的分组数据
                    if (normalized != incoming) {
                        repository?.updateRules(normalized)
                    }

                    // 清理不再存在的选中项。
                    // 选中集合是两类规则共用的，只能剔除"两类里都不存在"的 id，
                    // 否则每次文本规则刷新都会把图片规则的选择态一起清掉（反之亦然）
                    val currentIds = normalized.map { r -> r.id }.toSet()
                    val imageIds = _imageRules.value.map { it.id }.toSet()
                    _selectedRuleIds.update { selected ->
                        selected.filter { id -> id in currentIds || id in imageIds }.toSet()
                    }
                }
            }
            viewModelScope.launch {
                repository!!.normalizationEnabled.collect { _normalizationEnabled.value = it }
            }
            viewModelScope.launch {
                repository!!.normalizationRegex.collect { _normalizationRegex.value = it }
            }
            viewModelScope.launch {
                repository!!.extractionPatterns.collect { _extractionPatterns.value = it }
            }
            // 图片规则：不做顺序规范化（它们没有分发/跳转分组），原样透传即可，
            // 顺序本身就是要保住的优先级信息
            viewModelScope.launch {
                repository!!.imageRules.collect { incoming ->
                    _imageRules.value = incoming
                    // 同上：按两类 id 的并集清理，避免把文本规则的选择态抹掉
                    val currentIds = incoming.map { it.id }.toSet()
                    val textIds = _rules.value.map { it.id }.toSet()
                    _selectedRuleIds.update { selected ->
                        selected.filter { it in currentIds || it in textIds }.toSet()
                    }
                }
            }
            viewModelScope.launch {
                repository!!.normalizationTemplate.collect { _normalizationTemplate.value = it }
            }
            viewModelScope.launch {
                repository!!.fallbackBrowser.collect { _fallbackBrowser.value = it ?: "" }
            }
            viewModelScope.launch {
                repository!!.windowConfig.collect { _windowConfig.value = it }
            }
        }
    }

    fun enterSelectionMode(initialId: String) {
        _selectedRuleIds.value = setOf(initialId)
        _isSelectionMode.value = true
    }

    fun exitSelectionMode() {
        _isSelectionMode.value = false
        _selectedRuleIds.value = emptySet()
        // 两类规则的顺序都要落盘：列表顺序就是优先级，退出批量态是最后的保存点
        saveRuleOrder()
        saveImageRuleOrder()
    }

    /**
     * 为规则生成唯一名称，若冲突则增加 (n) 后缀
     */
    private fun generateUniqueName(baseName: String, existingNames: List<String>): String {
        var name = baseName
        val regex = Regex("""^(.*)\s\((\d+)\)$""")
        
        // 尝试提取原始基准名 (去掉已有的后缀)
        val actualBaseName = regex.find(baseName)?.groupValues?.get(1) ?: baseName
        
        var index = 1
        while (existingNames.contains(name)) {
            name = "$actualBaseName ($index)"
            index++
        }
        return name
    }

    /**
     * 更新正则标准化开关
     */
    fun toggleNormalization(enabled: Boolean) {
        viewModelScope.launch {
            repository?.updateNormalizationEnabled(enabled)
            _normalizationEnabled.value = enabled
        }
    }

    /**
     * 更新正则标准化具体配置（保留：服务于分发前的链接清洗）
     */
    fun updateNormalizationConfig(regex: String, template: String) {
        viewModelScope.launch {
            repository?.updateNormalizationConfig(regex, template)
            _normalizationRegex.value = regex
            _normalizationTemplate.value = template
        }
    }

    // --- 提取规则 CRUD ---

    fun addExtractionPattern(name: String, pattern: String) {
        viewModelScope.launch {
            val current = _extractionPatterns.value.toMutableList()
            current.add(com.moting.linkgo.model.ExtractPattern(name = name, pattern = pattern))
            repository?.updateExtractionPatterns(current)
        }
    }

    fun updateExtractionPattern(updated: com.moting.linkgo.model.ExtractPattern) {
        viewModelScope.launch {
            val current = _extractionPatterns.value.map { if (it.id == updated.id) updated else it }
            repository?.updateExtractionPatterns(current)
        }
    }

    fun deleteExtractionPattern(id: String) {
        viewModelScope.launch {
            val current = _extractionPatterns.value.filter { it.id != id }
            repository?.updateExtractionPatterns(current)
        }
    }

    fun toggleExtractionPattern(id: String) {
        viewModelScope.launch {
            val current = _extractionPatterns.value.map {
                if (it.id == id) it.copy(isEnabled = !it.isEnabled) else it
            }
            repository?.updateExtractionPatterns(current)
        }
    }

    fun moveExtractionPattern(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        val current = _extractionPatterns.value.toMutableList()
        if (fromIndex in current.indices && toIndex in current.indices) {
            current.add(toIndex, current.removeAt(fromIndex))
            _extractionPatterns.value = current
            viewModelScope.launch {
                repository?.updateExtractionPatterns(current)
            }
        }
    }

    fun resetBuiltinPatterns() {
        viewModelScope.launch {
            repository?.resetBuiltinPatterns()
        }
    }

    /**
     * 批量复制选中的规则
     */
    fun duplicateSelectedRules() {
        viewModelScope.launch {
            val selectedIds = _selectedRuleIds.value
            if (selectedIds.isEmpty()) return@launch

            val currentRules = _rules.value
            val newList = mutableListOf<DispatchRule>()
            
            currentRules.forEach { rule ->
                newList.add(rule)
                if (rule.id in selectedIds) {
                    // 获取当前所有同类规则的名称（包含已处理的新副本），用于生成唯一名称
                    val isBridge = rule.resolveStrategy == ResolutionStrategy.RE_DISPATCH
                    val existingNames = (newList + currentRules).filter {
                        (it.resolveStrategy == ResolutionStrategy.RE_DISPATCH) == isBridge
                    }.map { it.name }.distinct()
                    
                    val newName = generateUniqueName(rule.name, existingNames)
                    val copy = rule.copy(
                        id = java.util.UUID.randomUUID().toString(),
                        name = newName
                    )
                    newList.add(copy)
                }
            }
            
            repository?.updateRules(newList)

            // 图片规则同样按这份共享的选中集合复制，行为与文本规则对齐
            val currentImages = _imageRules.value
            val duplicatedImages = mutableListOf<com.moting.linkgo.model.ImageRule>()
            currentImages.filter { it.id in selectedIds }.forEach { original ->
                val usedNames = currentImages.map { it.name } + duplicatedImages.map { it.name }
                duplicatedImages.add(
                    original.copy(
                        id = java.util.UUID.randomUUID().toString(),
                        name = generateUniqueName(original.name, usedNames)
                    )
                )
            }
            if (duplicatedImages.isNotEmpty()) {
                repository?.updateImageRules(currentImages + duplicatedImages)
            }

            _uiEvent.emit("新建 ${selectedIds.size} 条规则副本")
            exitSelectionMode()
        }
    }

    fun toggleRuleSelection(ruleId: String) {
        _selectedRuleIds.update { current ->
            if (current.contains(ruleId)) {
                current - ruleId
            } else {
                current + ruleId
            }
        }
    }

    fun selectAll() {
        // 全选覆盖两类：它们在列表里是平级的，全选却只选一半会让人莫名其妙
        _selectedRuleIds.value =
            (_rules.value.map { it.id } + _imageRules.value.map { it.id }).toSet()
    }

    fun clearSelection() {
        _selectedRuleIds.value = emptySet()
    }

    fun deleteSelectedRules() {
        viewModelScope.launch {
            val selected = _selectedRuleIds.value
            // 一次批量删除同时覆盖两类规则：选中集合是共享的，删除也必须按同一份集合执行，
            // 否则会出现"选中的图片规则没被删掉"这种半执行状态
            repository?.updateRules(_rules.value.filter { it.id !in selected })
            if (_imageRules.value.any { it.id in selected }) {
                repository?.updateImageRules(_imageRules.value.filter { it.id !in selected })
            }
            exitSelectionMode()
        }
    }

    /**
     * 批量导出选中的规则。
     *
     * 两类规则共用一份选中集合，因此导出时按归属分别装进各自的包络键；
     * 只导出非空的那一类，避免产出 `"linkgo_image_rules": []` 这种噪音。
     */
    fun exportSelectedRulesToJson(): String {
        val selected = _selectedRuleIds.value
        val data = mutableMapOf<String, Any>()
        val textRules = _rules.value.filter { it.id in selected }
        if (textRules.isNotEmpty()) {
            data["linkgo_rules"] = textRules
        }
        val imageRules = _imageRules.value.filter { it.id in selected }
        if (imageRules.isNotEmpty()) {
            data["linkgo_image_rules"] = imageRules
        }
        return GsonBuilder().setPrettyPrinting().create().toJson(data)
    }

    /**
     * 导出所有规则与全部提取规则
     */
    fun exportAllRulesToJson(): String {
        val data = mutableMapOf<String, Any>()
        if (_rules.value.isNotEmpty()) {
            data["linkgo_rules"] = _rules.value
        }
        if (_imageRules.value.isNotEmpty()) {
            data["linkgo_image_rules"] = _imageRules.value
        }
        if (_extractionPatterns.value.isNotEmpty()) {
            data["linkgo_extract_patterns"] = _extractionPatterns.value
        }
        return GsonBuilder().setPrettyPrinting().create().toJson(data)
    }

    // --- 链接提取规则选择态（复用规则页的长按批量操作模式） ---

    /**
     * 进入提取规则选择态并预选首项（内置规则同样可进入并选中，仅批量删除时排除）
     */
    fun enterExtractionSelectionMode(initialId: String) {
        val pattern = _extractionPatterns.value.find { it.id == initialId }
        if (pattern == null) return
        _selectedExtractionPatternIds.value = setOf(initialId)
        _isExtractionSelectionMode.value = true
    }

    fun exitExtractionSelectionMode() {
        _isExtractionSelectionMode.value = false
        _selectedExtractionPatternIds.value = emptySet()
    }

    fun toggleExtractionPatternSelection(id: String) {
        _selectedExtractionPatternIds.update { current ->
            if (current.contains(id)) current - id else current + id
        }
    }

    fun selectAllExtractionPatterns() {
        val ids = _extractionPatterns.value.map { it.id }.toSet()
        _selectedExtractionPatternIds.value = ids
    }

    fun clearExtractionPatternSelection() {
        _selectedExtractionPatternIds.value = emptySet()
    }

    /**
     * 批量删除选中的提取规则（全部降级为普通规则，均可删除）
     */
    fun deleteSelectedExtractionPatterns() {
        viewModelScope.launch {
            val selected = _selectedExtractionPatternIds.value
            if (selected.isEmpty()) {
                exitExtractionSelectionMode()
                return@launch
            }
            val remaining = _extractionPatterns.value.filter { it.id !in selected }
            repository?.updateExtractionPatterns(remaining)
            _uiEvent.emit("已删除 ${selected.size} 条提取规则")
            exitExtractionSelectionMode()
        }
    }

    /**
     * 批量复制选中的提取规则
     */
    fun duplicateSelectedExtractionPatterns() {
        viewModelScope.launch {
            val selected = _selectedExtractionPatternIds.value
            if (selected.isEmpty()) return@launch

            val current = _extractionPatterns.value
            val existingNames = current.map { it.name }
            val newPatterns = mutableListOf<ExtractPattern>()

            current.filter { it.id in selected }.forEach { original ->
                // 累积已生成副本名称，确保重名时后缀唯一，与规则页行为一致
                val usedNames = existingNames + newPatterns.map { it.name }
                newPatterns.add(
                    original.copy(
                        id = java.util.UUID.randomUUID().toString(),
                        name = generateUniqueName(original.name, usedNames),
                        isBuiltin = false
                    )
                )
            }
            if (newPatterns.isEmpty()) {
                exitExtractionSelectionMode()
                return@launch
            }
            val updated = current.toMutableList()
            updated.addAll(newPatterns)
            repository?.updateExtractionPatterns(updated)
            _uiEvent.emit("新建 ${newPatterns.size} 条提取规则副本")
            exitExtractionSelectionMode()
        }
    }

    /**
     * 批量导出选中的提取规则（可在数据中心粘贴导入，不再附带多余空规则数组）
     */
    fun exportSelectedExtractionPatternsToJson(): String {
        val selected = _selectedExtractionPatternIds.value
        val patternsToExport = _extractionPatterns.value.filter { it.id in selected }
        val data = mapOf("linkgo_extract_patterns" to patternsToExport)
        return GsonBuilder().setPrettyPrinting().create().toJson(data)
    }


    fun addRule(
        name: String, 
        pattern: String, 
        targetPackage: String, 
        template: String?, 
        ruleLaunchMode: Int,
        matchType: com.moting.linkgo.model.MatchType,
        extractPattern: String? = null,
        resolveShortLink: Boolean = false,
        resolveStrategy: ResolutionStrategy = ResolutionStrategy.NONE,
        excludeFromRecents: Boolean = false,
        isPreheatEnabled: Boolean = false,
        preheatDelayMillis: Long = 500L,
        iconPath: String? = null,
        jumpAndCopy: Boolean = false
    ) {
        viewModelScope.launch {
            val current = _rules.value.toMutableList()
            // 分类查重：仅获取同类型的现有名称
            val isTargetBridge = resolveStrategy == com.moting.linkgo.model.ResolutionStrategy.RE_DISPATCH
            val categoryNames = current.filter { 
                (it.resolveStrategy == com.moting.linkgo.model.ResolutionStrategy.RE_DISPATCH) == isTargetBridge 
            }.map { it.name }
            
            val finalizedName = generateUniqueName(name, categoryNames)
            
            if (finalizedName != name) {
                _uiEvent.emit("规则重名，修正为 $finalizedName")
            }

            current.add(DispatchRule(
                name = finalizedName, 
                pattern = pattern, 
                targetPackage = targetPackage, 
                template = template,
                extractPattern = extractPattern,
                ruleLaunchMode = ruleLaunchMode,
                matchType = matchType,
                resolveShortLink = resolveShortLink,
                resolveStrategy = resolveStrategy,
                excludeFromRecents = excludeFromRecents,
                isPreheatEnabled = isPreheatEnabled,
                preheatDelayMillis = preheatDelayMillis,
                iconPath = iconPath,
                jumpAndCopy = jumpAndCopy
            ))
            repository?.updateRules(current)
        }
    }

    fun deleteRule(ruleId: String) {
        viewModelScope.launch {
            val current = _rules.value.filter { it.id != ruleId }
            repository?.updateRules(current)
        }
    }

    fun updateRule(rule: DispatchRule) {
        viewModelScope.launch {
            val rules = _rules.value
            // 分类查重：仅获取同类型且不是自身的名称
            val isTargetBridge = rule.resolveStrategy == com.moting.linkgo.model.ResolutionStrategy.RE_DISPATCH
            val categoryNames = rules.filter { 
                it.id != rule.id && (it.resolveStrategy == com.moting.linkgo.model.ResolutionStrategy.RE_DISPATCH) == isTargetBridge
            }.map { it.name }
            
            val finalizedName = generateUniqueName(rule.name, categoryNames)
            
            if (finalizedName != rule.name) {
                _uiEvent.emit("规则重名，修正为 $finalizedName")
            }
            
            val finalRule = rule.copy(name = finalizedName)
            val current = rules.map {
                if (it.id == finalRule.id) finalRule else it
            }
            repository?.updateRules(current)
        }
    }

    fun toggleRule(ruleId: String) {
        viewModelScope.launch {
            val current = _rules.value.map {
                if (it.id == ruleId) it.copy(isEnabled = !it.isEnabled) else it
            }
            repository?.updateRules(current)
        }
    }

    /**
     * 批量禁用指定的规则集合
     */
    fun disableRules(ruleIds: Set<String>) {
        if (ruleIds.isEmpty()) return
        viewModelScope.launch {
            val current = _rules.value.map {
                if (it.id in ruleIds) it.copy(isEnabled = false) else it
            }
            repository?.updateRules(current)
            // 提示横幅统计的是两类规则，禁用也必须一起生效，否则会出现"点了禁用但数字没减"
            if (_imageRules.value.any { it.id in ruleIds }) {
                val updatedImages = _imageRules.value.map {
                    if (it.id in ruleIds) it.copy(isEnabled = false) else it
                }
                persistImageRuleOrder(updatedImages)
            }
            _uiEvent.emit("已禁用 ${ruleIds.size} 条失效规则")
        }
    }

    /**
     * 处理规则排序移动
     */
    fun onMove(fromId: String, toId: String) {
        val currentList = _rules.value.toMutableList()
        val fromIndex = currentList.indexOfFirst { it.id == fromId }
        val toIndex = currentList.indexOfFirst { it.id == toId }
        
        if (fromIndex == -1 || toIndex == -1 || fromIndex == toIndex) return
        
        val fromRule = currentList[fromIndex]
        val toRule = currentList[toIndex]
        
        val isFromReDispatch = fromRule.resolveStrategy == ResolutionStrategy.RE_DISPATCH
        val isToReDispatch = toRule.resolveStrategy == ResolutionStrategy.RE_DISPATCH
        
        // 单一连续列表无阻力自由位置交换，确保拖拽手势100%流畅不脱手
        currentList.add(toIndex, currentList.removeAt(fromIndex))
        _rules.value = currentList
        android.util.Log.d("LinkGoDrag", "ViewModel.onMove: from=${fromRule.name}(index=$fromIndex) -> to=${toRule.name}(index=$toIndex)")
    }

    /**
     * 拖拽停止或整理后的规则分组规范化：确保分发规则在上，跳转规则在下
     */
    fun normalizeRuleOrder() {
        val currentList = _rules.value
        val reDispatchRules = currentList.filter { it.resolveStrategy == ResolutionStrategy.RE_DISPATCH }
        val standardRules = currentList.filter { it.resolveStrategy != ResolutionStrategy.RE_DISPATCH }
        val normalized = reDispatchRules + standardRules
        if (normalized != currentList) {
            _rules.value = normalized
        }
        saveRuleOrder()
    }

    /**
     * 将当前的排序持久化到 DataStore
     */
    fun saveRuleOrder() {
        viewModelScope.launch {
            repository?.updateRules(_rules.value)
        }
    }

    /**
     * 导出规则为 JSON
     * @param ruleId 为空则导出全部
     */
    fun exportRuleToJson(ruleId: String? = null): String {
        val rulesToExport = if (ruleId == null) {
            _rules.value
        } else {
            _rules.value.filter { it.id == ruleId }
        }
        val data = mapOf("linkgo_rules" to rulesToExport)
        return GsonBuilder().setPrettyPrinting().create().toJson(data)
    }

    private val _pendingImportRules = MutableStateFlow<List<DispatchRule>>(emptyList())
    val pendingImportRules = _pendingImportRules.asStateFlow()

    private val _pendingImportExtractPatterns = MutableStateFlow<List<com.moting.linkgo.model.ExtractPattern>>(emptyList())
    val pendingImportExtractPatterns = _pendingImportExtractPatterns.asStateFlow()

    /**
     * 待导入的图片规则。
     *
     * 数据中心是唯一的数据出入口，三类数据（分发/跳转规则、提取规则、图片规则）
     * 必须都在这里可预览、可勾选，否则导出的图片规则根本没有路径回来。
     */
    private val _pendingImportImageRules = MutableStateFlow<List<com.moting.linkgo.model.ImageRule>>(emptyList())
    val pendingImportImageRules = _pendingImportImageRules.asStateFlow()

    private val _showImportPreview = MutableStateFlow(false)
    val showImportPreview = _showImportPreview.asStateFlow()

    // 导出预览相关状态
    private val _showExportPreview = MutableStateFlow(false)
    val showExportPreview = _showExportPreview.asStateFlow()

    private val _exportTarget = MutableStateFlow(ExportTarget.CLIPBOARD)
    val exportTarget = _exportTarget.asStateFlow()

    private val _pendingExportRules = MutableStateFlow<List<DispatchRule>>(emptyList())
    val pendingExportRules = _pendingExportRules.asStateFlow()

    private val _pendingExportExtractPatterns = MutableStateFlow<List<com.moting.linkgo.model.ExtractPattern>>(emptyList())
    val pendingExportExtractPatterns = _pendingExportExtractPatterns.asStateFlow()

    private val _pendingExportImageRules = MutableStateFlow<List<com.moting.linkgo.model.ImageRule>>(emptyList())
    val pendingExportImageRules = _pendingExportImageRules.asStateFlow()

    private var _lastSelectedExportIds: Set<String> = emptySet()

    fun dismissImportPreview() {
        _showImportPreview.value = false
        _pendingImportRules.value = emptyList()
        _pendingImportExtractPatterns.value = emptyList()
        _pendingImportImageRules.value = emptyList()
    }

    fun dismissExportPreview() {
        _showExportPreview.value = false
        _pendingExportRules.value = emptyList()
        _pendingExportExtractPatterns.value = emptyList()
        _pendingExportImageRules.value = emptyList()
    }

    /**
     * 进入导出预览模式
     */
    fun prepareExport(target: ExportTarget) {
        _pendingExportRules.value = _rules.value
        _pendingExportExtractPatterns.value = _extractionPatterns.value
        _pendingExportImageRules.value = _imageRules.value
        _exportTarget.value = target
        _showExportPreview.value = true
    }

    /**
     * 解析剪贴板 JSON 并开启预览 (终极稳健版)
     * 支持格式：
     * - {"linkgo_rules": [...]}
     * - {"linkgo_extract_patterns": [...]}
     * - 两者混合的对象 / 纯数组（按规则处理）
     */
    fun prepareImport(json: String): Boolean {
        val rawContent = json.trim()
        if (rawContent.isBlank()) {
            viewModelScope.launch { _uiEvent.emit("导入内容为空") }
            return false
        }

        val gson = GsonBuilder().setLenient().create()
        
        return try {
            // 物理定位 JSON 块 (提升杂质文本处理稳定性)
            val startIdx = rawContent.indexOfAny(charArrayOf('{', '['))
            val endIdx = rawContent.lastIndexOfAny(charArrayOf('}', ']'))
            
            if (startIdx == -1 || endIdx == -1 || endIdx <= startIdx) {
                viewModelScope.launch { _uiEvent.emit("内容中未检测到有效的 JSON 结构") }
                return false
            }
            
            val extractedJson = rawContent.substring(startIdx, endIdx + 1)
            val jsonElement = JsonParser.parseString(extractedJson)

            val resultRules = mutableListOf<DispatchRule>()
            val pendingExtracts = mutableListOf<com.moting.linkgo.model.ExtractPattern>()
            val pendingImages = mutableListOf<com.moting.linkgo.model.ImageRule>()

            when {
                jsonElement.isJsonObject -> {
                    val obj = jsonElement.asJsonObject
                    var recognizedWrapper = false

                    // 1. 包含 linkgo_rules 包装
                    if (obj.has("linkgo_rules") && obj.get("linkgo_rules").isJsonArray) {
                        recognizedWrapper = true
                        val arr = obj.getAsJsonArray("linkgo_rules")
                        for (element in arr) {
                            try {
                                val rule = gson.fromJson(element, DispatchRule::class.java)
                                if (rule != null && !rule.name.isNullOrBlank()) {
                                    resultRules.add(rule)
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("RulesViewModel", "单条规则解析失败: ${e.message}")
                            }
                        }
                    }

                    // 2. 包含 linkgo_extract_patterns 包装
                    if (obj.has("linkgo_extract_patterns") && obj.get("linkgo_extract_patterns").isJsonArray) {
                        recognizedWrapper = true
                        val extractsArr = obj.getAsJsonArray("linkgo_extract_patterns")
                        for (element in extractsArr) {
                            try {
                                val ep = gson.fromJson(element, com.moting.linkgo.model.ExtractPattern::class.java)
                                if (ep != null && !ep.name.isNullOrBlank()) {
                                    pendingExtracts.add(ep)
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("RulesViewModel", "单条提取规则解析失败: ${e.message}")
                            }
                        }
                    }

                    // 3. 包含 linkgo_image_rules 包装
                    if (obj.has("linkgo_image_rules") && obj.get("linkgo_image_rules").isJsonArray) {
                        recognizedWrapper = true
                        val imagesArr = obj.getAsJsonArray("linkgo_image_rules")
                        for (element in imagesArr) {
                            try {
                                val ir = gson.fromJson(element, com.moting.linkgo.model.ImageRule::class.java)
                                if (ir != null && !ir.name.isNullOrBlank()) {
                                    pendingImages.add(ir)
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("RulesViewModel", "单条图片规则解析失败: ${e.message}")
                            }
                        }
                    }

                    // 4. 无包络的单个对象，根据特征字段严格区分
                    if (!recognizedWrapper) {
                        if (obj.has("targetPackage") || obj.has("ruleLaunchMode") || obj.has("template") || obj.has("resolveStrategy")) {
                            try {
                                val rule = gson.fromJson(obj, DispatchRule::class.java)
                                if (rule != null && !rule.name.isNullOrBlank()) {
                                    resultRules.add(rule)
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("RulesViewModel", "单条规则解析失败: ${e.message}")
                            }
                        } else if (obj.has("name") && obj.has("pattern")) {
                            try {
                                val ep = gson.fromJson(obj, com.moting.linkgo.model.ExtractPattern::class.java)
                                if (ep != null && !ep.name.isNullOrBlank()) {
                                    pendingExtracts.add(ep)
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("RulesViewModel", "单条提取规则解析失败: ${e.message}")
                            }
                        } else if (obj.has("targetClass")) {
                            // 单条图片规则：图片规则没有 pattern，靠 targetClass 认出来
                            // （DispatchRule 没有这个字段，两者不会互相误判）
                            try {
                                val ir = gson.fromJson(obj, com.moting.linkgo.model.ImageRule::class.java)
                                if (ir != null && !ir.name.isNullOrBlank()) {
                                    pendingImages.add(ir)
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("RulesViewModel", "单条图片规则解析失败: ${e.message}")
                            }
                        }
                    }
                }
                jsonElement.isJsonArray -> {
                    val arr = jsonElement.asJsonArray
                    for (element in arr) {
                        if (element.isJsonObject) {
                            val itemObj = element.asJsonObject
                            if (itemObj.has("targetPackage") || itemObj.has("ruleLaunchMode") || itemObj.has("template") || itemObj.has("resolveStrategy")) {
                                try {
                                    val rule = gson.fromJson(itemObj, DispatchRule::class.java)
                                    if (rule != null && !rule.name.isNullOrBlank()) {
                                        resultRules.add(rule)
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("RulesViewModel", "规则项解析失败: ${e.message}")
                                }
                            } else if (itemObj.has("name") && itemObj.has("pattern")) {
                                try {
                                    val ep = gson.fromJson(itemObj, com.moting.linkgo.model.ExtractPattern::class.java)
                                    if (ep != null && !ep.name.isNullOrBlank()) {
                                        pendingExtracts.add(ep)
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("RulesViewModel", "提取规则项解析失败: ${e.message}")
                                }
                            } else if (itemObj.has("targetClass")) {
                                try {
                                    val ir = gson.fromJson(itemObj, com.moting.linkgo.model.ImageRule::class.java)
                                    if (ir != null && !ir.name.isNullOrBlank()) {
                                        pendingImages.add(ir)
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("RulesViewModel", "图片规则项解析失败: ${e.message}")
                                }
                            }
                        }
                    }
                }
            }

            if (resultRules.isNotEmpty() || pendingExtracts.isNotEmpty() || pendingImages.isNotEmpty()) {
                _pendingImportRules.value = resultRules
                _pendingImportExtractPatterns.value = pendingExtracts
                _pendingImportImageRules.value = pendingImages
                _showImportPreview.value = true
                true
            } else {
                viewModelScope.launch { _uiEvent.emit("未识别出有效的规则、提取规则或图片规则条目") }
                false
            }
        } catch (e: Exception) {
            android.util.Log.e("RulesViewModel", "解析过程发生异常", e)
            viewModelScope.launch { _uiEvent.emit("解析失败: ${e.localizedMessage ?: e.toString()}") }
            false
        }
    }

    /**
     * 确认导入选中的规则与提取规则 (基于 ID，统一追加新 ID)
     * @return 导入总数（规则 + 提取规则）
     */
    fun confirmImport(selectedIds: Set<String>): Int {
        var importedCount = 0

        // ── 规则导入 ──
        val incoming = _pendingImportRules.value
        if (incoming.isNotEmpty()) {
            val currentRules = _rules.value.toMutableList()
            incoming.forEach { rule ->
                if (rule.id in selectedIds) {
                    // 分类查重
                    val isBridge = rule.resolveStrategy == com.moting.linkgo.model.ResolutionStrategy.RE_DISPATCH
                    val categoryNames = currentRules.filter { 
                        (it.resolveStrategy == com.moting.linkgo.model.ResolutionStrategy.RE_DISPATCH) == isBridge 
                    }.map { it.name }
                    
                    val newName = generateUniqueName(rule.name, categoryNames)
                    currentRules.add(rule.copy(
                        id = java.util.UUID.randomUUID().toString(),
                        name = newName
                    ) )
                    importedCount++
                }
            }
            if (importedCount > 0) {
                viewModelScope.launch {
                    repository?.updateRules(currentRules)
                }
            }
        }

        // ── 提取规则导入（全部追加新 ID，查重后自动重命名，与规则一致）──
        // 导入项一律视为用户自定义数据落地：剥离内置标记，避免生成不可删除的重复内置副本
        val incomingExtracts = _pendingImportExtractPatterns.value
        if (incomingExtracts.isNotEmpty()) {
            val currentExtracts = _extractionPatterns.value.toMutableList()
            var extractImported = 0
            incomingExtracts.forEach { ep ->
                if (ep.id in selectedIds) {
                    // 查重并生成唯一名称，避免同名覆盖
                    val existingNames = currentExtracts.map { it.name }
                    val newName = generateUniqueName(ep.name, existingNames)
                    currentExtracts.add(ep.copy(
                        id = java.util.UUID.randomUUID().toString(),
                        name = newName,
                        isBuiltin = false
                    ))
                    extractImported++
                }
            }
            if (extractImported > 0) {
                importedCount += extractImported
                viewModelScope.launch {
                    repository?.updateExtractionPatterns(currentExtracts)
                }
            }
        }
        
        // ── 图片规则导入（同样全部追加新 ID 并查重命名）──
        // 图片规则没有 pattern，查重按"名称 + 分享入口"判断
        val incomingImages = _pendingImportImageRules.value
        if (incomingImages.isNotEmpty()) {
            val currentImages = _imageRules.value.toMutableList()
            var imageImported = 0
            incomingImages.forEach { ir ->
                if (ir.id in selectedIds) {
                    val existingNames = currentImages.map { it.name }
                    val newName = generateUniqueName(ir.name, existingNames)
                    currentImages.add(ir.copy(
                        id = java.util.UUID.randomUUID().toString(),
                        name = newName
                    ))
                    imageImported++
                }
            }
            if (imageImported > 0) {
                importedCount += imageImported
                viewModelScope.launch {
                    repository?.updateImageRules(currentImages)
                }
            }
        }

        dismissImportPreview()
        return importedCount
    }

    /**
     * 已废弃：直接导入 (由预览导入替代)
     */
    fun importRulesFromJson(json: String): Int {
        return if (prepareImport(json)) {
            // 这里为了向下兼容逻辑，可以默认全选导入，或者仅解析不建议在此直接导入
            0 
        } else 0
    }

    /**
     * 将选中的 ID 转换为 JSON (用于导出预览后的最终确认)
     * 同时打包勾选的分发/跳转规则、提取规则与图片规则（仅导出选中的项，避免未勾选板块混入）
     */
    fun exportSpecifiedRulesToJson(ids: Set<String>): String {
        val allRaw = _pendingExportRules.value
        val toExport = allRaw.filter { it.id in ids }
        val toExportExtracts = _pendingExportExtractPatterns.value.filter { it.id in ids }
        val toExportImages = _pendingExportImageRules.value.filter { it.id in ids }
        val data = mutableMapOf<String, Any>()
        if (toExport.isNotEmpty()) {
            data["linkgo_rules"] = toExport
        }
        if (toExportExtracts.isNotEmpty()) {
            data["linkgo_extract_patterns"] = toExportExtracts
        }
        if (toExportImages.isNotEmpty()) {
            data["linkgo_image_rules"] = toExportImages
        }
        return GsonBuilder().setPrettyPrinting().create().toJson(data)
    }

    /**
     * 临时记录导出的 ID，供文件保存回调使用
     */
    fun recordExportSelection(ids: Set<String>) {
        _lastSelectedExportIds = ids
        _showExportPreview.value = false
    }

    /**
     * 将规则导出到指定 URI (文件)
     */
    fun exportRulesToUri(context: android.content.Context, uri: android.net.Uri, mode: String) {
        viewModelScope.launch {
            try {
                val json = when(mode) {
                    "SELECTED" -> exportSelectedRulesToJson() // 批量模式下的导出
                    "PREVIEW" -> exportSpecifiedRulesToJson(_lastSelectedExportIds) // 预览模式下的导出
                    else -> exportAllRulesToJson() // 全量导出
                }
                context.contentResolver.openOutputStream(uri)?.use { 
                    it.write(json.toByteArray())
                }
                _uiEvent.emit("导出成功")
            } catch (e: Exception) {
                _uiEvent.emit("导出失败: ${e.message}")
            }
        }
    }

    /**
     * 从指定 URI (文件) 导入规则
     */
    fun importRulesFromUri(context: android.content.Context, uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                val content = context.contentResolver.openInputStream(uri)?.use { 
                    it.bufferedReader().use { reader -> reader.readText() }
                }
                if (!content.isNullOrBlank()) {
                    prepareImport(content)
                } else {
                    _uiEvent.emit("文件内容为空")
                }
            } catch (e: Exception) {
                _uiEvent.emit("读取文件失败: ${e.message}")
            }
        }
    }

    /**
     * 判断两个规则是否属于同一个大组（分发 vs 跳转）
     */
    fun isSameGroup(id1: String, id2: String): Boolean {
        val list = _rules.value
        val rule1 = list.find { it.id == id1 }
        val rule2 = list.find { it.id == id2 }
        if (rule1 == null || rule2 == null) {
            android.util.Log.d("LinkGoDrag", "isSameGroup: rule1=$rule1, rule2=$rule2 -> return false")
            return false
        }
        
        val is1ReDispatch = rule1.resolveStrategy == ResolutionStrategy.RE_DISPATCH
        val is2ReDispatch = rule2.resolveStrategy == ResolutionStrategy.RE_DISPATCH
        val same = is1ReDispatch == is2ReDispatch
        android.util.Log.d("LinkGoDrag", "isSameGroup: [${rule1.name}] (isReDispatch=$is1ReDispatch) vs [${rule2.name}] (isReDispatch=$is2ReDispatch) -> same=$same")
        return same
    }

    /**
     * 将外部图片转存到应用私有目录，避免 URI 权限失效
     */
    fun saveCustomIcon(context: Context, uri: android.net.Uri): String? {
        return try {
            val iconsDir = java.io.File(context.filesDir, "rule_icons")
            if (!iconsDir.exists()) iconsDir.mkdirs()
            
            val fileName = "icon_${System.currentTimeMillis()}.png"
            val destFile = java.io.File(iconsDir, fileName)
            
            context.contentResolver.openInputStream(uri)?.use { input ->
                java.io.FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            destFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ==========================================
    // 一键整理与健康治理 (Smart Organize & Health)
    // ==========================================

    /**
     * 统计目标应用未安装的规则条数（两类合并）。
     *
     * "一键整理"面板用它提示有多少条规则指向了已卸载的应用；
     * 只数文本规则会让图片规则里的失效项被漏掉。
     */
    fun getUninstalledAppCount(context: Context): Int {
        val textCount = _rules.value.count { rule ->
            rule.targetPackage.isNotBlank() && !PackageRepository.isAppInstalled(context, rule.targetPackage)
        }
        val imageCount = _imageRules.value.count { rule ->
            rule.targetPackage.isNotBlank() && !PackageRepository.isAppInstalled(context, rule.targetPackage)
        }
        return textCount + imageCount
    }

    /**
     * 1. 按目标应用聚合整理（推荐）：
     * 组内按：应用名称拼音 A-Z -> 特异性精度从高到低 (EXACT > PREFIX > CONTAINS > REGEX > ALL) -> 规则名称 A-Z
     * 分发组与跳转组保持隔离独立排序
     */
    fun organizeRulesByApp(context: Context) {
        viewModelScope.launch {
            val list = _rules.value
            if (list.isEmpty()) return@launch

            val reDispatchRules = list.filter { it.resolveStrategy == ResolutionStrategy.RE_DISPATCH }
            val standardRules = list.filter { it.resolveStrategy != ResolutionStrategy.RE_DISPATCH }

            val collator = java.text.Collator.getInstance(java.util.Locale.CHINA)
            val appComparator = Comparator<DispatchRule> { r1, r2 ->
                val appName1 = PackageRepository.getAppLabel(context, r1.targetPackage).ifBlank { r1.targetPackage }
                val appName2 = PackageRepository.getAppLabel(context, r2.targetPackage).ifBlank { r2.targetPackage }
                
                // 1. 应用名称拼音排序
                val appCompare = collator.compare(appName1, appName2)
                if (appCompare != 0) return@Comparator appCompare

                // 2. 特异性精度从高到低 (EXACT=0, PREFIX=1, CONTAINS=2, REGEX=3, ALL=4)
                val typeWeight1 = matchTypeSpecificityWeight(r1.matchType)
                val typeWeight2 = matchTypeSpecificityWeight(r2.matchType)
                if (typeWeight1 != typeWeight2) return@Comparator typeWeight1.compareTo(typeWeight2)

                // 3. 规则名称拼音 A-Z
                collator.compare(r1.name, r2.name)
            }

            val sortedReDispatch = reDispatchRules.sortedWith(appComparator)
            val sortedStandard = standardRules.sortedWith(appComparator)

            val sortedAll = sortedReDispatch + sortedStandard
            _rules.value = sortedAll
            repository?.updateRules(sortedAll)
            // 图片规则同步整理：顺序是它唯一的仲裁依据，漏掉它会让用户以为"整理把图片跳转变了"
            persistImageRuleOrder(sortImageRulesByApp(context, _imageRules.value))
            _uiEvent.emit("已按【目标应用聚合】完成排序")
        }
    }

    /**
     * 2. 按启用状态排序：
     * 已启用的规则在前，已停用的规则在后（组内独立排序）
     */
    fun organizeRulesByEnabled() {
        viewModelScope.launch {
            val list = _rules.value
            if (list.isEmpty()) return@launch

            val reDispatchRules = list.filter { it.resolveStrategy == ResolutionStrategy.RE_DISPATCH }
            val standardRules = list.filter { it.resolveStrategy != ResolutionStrategy.RE_DISPATCH }

            val enabledComparator = Comparator<DispatchRule> { r1, r2 ->
                if (r1.isEnabled != r2.isEnabled) {
                    if (r1.isEnabled) -1 else 1
                } else {
                    0
                }
            }

            val sortedReDispatch = reDispatchRules.sortedWith(enabledComparator)
            val sortedStandard = standardRules.sortedWith(enabledComparator)

            val sortedAll = sortedReDispatch + sortedStandard
            _rules.value = sortedAll
            repository?.updateRules(sortedAll)
            persistImageRuleOrder(sortImageRulesByEnabled(_imageRules.value))
            _uiEvent.emit("已按【启用状态优先】完成排序")
        }
    }

    /**
     * 3. 按规则名称字母排序 (A-Z)
     */
    fun organizeRulesByName() {
        viewModelScope.launch {
            val list = _rules.value
            if (list.isEmpty()) return@launch

            val collator = java.text.Collator.getInstance(java.util.Locale.CHINA)
            val nameComparator = Comparator<DispatchRule> { r1, r2 ->
                collator.compare(r1.name, r2.name)
            }

            val reDispatchRules = list.filter { it.resolveStrategy == ResolutionStrategy.RE_DISPATCH }
            val standardRules = list.filter { it.resolveStrategy != ResolutionStrategy.RE_DISPATCH }

            val sortedReDispatch = reDispatchRules.sortedWith(nameComparator)
            val sortedStandard = standardRules.sortedWith(nameComparator)

            val sortedAll = sortedReDispatch + sortedStandard
            _rules.value = sortedAll
            repository?.updateRules(sortedAll)
            persistImageRuleOrder(sortImageRulesByName(_imageRules.value))
            _uiEvent.emit("已按【规则名称 A-Z】完成排序")
        }
    }

    // ==========================================
    // 图片规则排序
    //
    // 图片规则没有匹配条件，顺序就是它唯一的仲裁依据。因此若"一键整理"只作用于文本规则，
    // 用户会困惑："我整理了规则，为什么图片跳转变了？"——所以图片规则同样参与整理，
    // 各自组内独立排序。
    //
    // 实现说明：`sortedWith` 是稳定排序，把多个维度**按优先级从低到高依次施加**，
    // 结果与复合比较器等价，但不必把文本规则的复合比较器复制一份成图片版本。
    // ==========================================

    /**
     * 按目标应用排序图片规则（组内再按名称拼音）
     */
    private fun sortImageRulesByApp(context: Context, imageRules: List<com.moting.linkgo.model.ImageRule>)
        : List<com.moting.linkgo.model.ImageRule> {
        val collator = java.text.Collator.getInstance(java.util.Locale.CHINA)
        return imageRules
            .sortedWith { a, b -> collator.compare(a.name, b.name) }
            .sortedWith { a, b ->
                val appA = PackageRepository.getAppLabel(context, a.targetPackage).ifBlank { a.targetPackage }
                val appB = PackageRepository.getAppLabel(context, b.targetPackage).ifBlank { b.targetPackage }
                collator.compare(appA, appB)
            }
    }

    private fun sortImageRulesByEnabled(imageRules: List<com.moting.linkgo.model.ImageRule>)
        : List<com.moting.linkgo.model.ImageRule> =
        imageRules.sortedWith { a, b -> if (a.isEnabled == b.isEnabled) 0 else if (a.isEnabled) -1 else 1 }

    private fun sortImageRulesByName(imageRules: List<com.moting.linkgo.model.ImageRule>)
        : List<com.moting.linkgo.model.ImageRule> {
        val collator = java.text.Collator.getInstance(java.util.Locale.CHINA)
        return imageRules.sortedWith { a, b -> collator.compare(a.name, b.name) }
    }

    /** 把整理后的图片规则写回（无变化时跳过写盘） */
    private suspend fun persistImageRuleOrder(sorted: List<com.moting.linkgo.model.ImageRule>) {
        if (sorted != _imageRules.value) {
            _imageRules.value = sorted
            repository?.updateImageRules(sorted)
        }
    }

    /**
     * 复合多选排序：
     * 根据用户勾选的多个排序维度进行级联组合排序
     */
    fun organizeRulesMulti(context: Context, byApp: Boolean, byEnabled: Boolean, byName: Boolean) {
        if (!byApp && !byEnabled && !byName) return

        viewModelScope.launch {
            val list = _rules.value
            if (list.isEmpty()) return@launch

            val reDispatchRules = list.filter { it.resolveStrategy == ResolutionStrategy.RE_DISPATCH }
            val standardRules = list.filter { it.resolveStrategy != ResolutionStrategy.RE_DISPATCH }

            val collator = java.text.Collator.getInstance(java.util.Locale.CHINA)

            val compositeComparator = Comparator<DispatchRule> { r1, r2 ->
                // 1. 已启用的排在前面
                if (byEnabled && r1.isEnabled != r2.isEnabled) {
                    return@Comparator if (r1.isEnabled) -1 else 1
                }

                // 2. 目标应用归类 (外层App名称，内层特异性精度)
                if (byApp) {
                    val appName1 = PackageRepository.getAppLabel(context, r1.targetPackage).ifBlank { r1.targetPackage }
                    val appName2 = PackageRepository.getAppLabel(context, r2.targetPackage).ifBlank { r2.targetPackage }
                    val appCompare = collator.compare(appName1, appName2)
                    if (appCompare != 0) return@Comparator appCompare

                    val typeWeight1 = matchTypeSpecificityWeight(r1.matchType)
                    val typeWeight2 = matchTypeSpecificityWeight(r2.matchType)
                    if (typeWeight1 != typeWeight2) return@Comparator typeWeight1.compareTo(typeWeight2)
                }

                // 3. 规则名称 A-Z
                if (byName) {
                    val nameCompare = collator.compare(r1.name, r2.name)
                    if (nameCompare != 0) return@Comparator nameCompare
                }

                0
            }

            val sortedReDispatch = reDispatchRules.sortedWith(compositeComparator)
            val sortedStandard = standardRules.sortedWith(compositeComparator)

            val sortedAll = sortedReDispatch + sortedStandard
            _rules.value = sortedAll
            repository?.updateRules(sortedAll)
            // 图片规则按相同维度级联排序。稳定排序让"从低优先级到高优先级依次施加"等价于复合比较器，
            // 因此不必把上面那个复合比较器再复制一份成图片版本。
            var img = _imageRules.value
            if (byName) img = sortImageRulesByName(img)
            if (byApp) img = sortImageRulesByApp(context, img)
            if (byEnabled) img = sortImageRulesByEnabled(img)
            persistImageRuleOrder(img)
            _uiEvent.emit("规则排序完成")
        }
    }

    /**
     * 4. 治理：一键停用所有未安装应用的规则
     */
    fun disableUninstalledRules(context: Context) {
        viewModelScope.launch {
            val list = _rules.value
            var changedCount = 0
            val updated = list.map { rule ->
                if (rule.targetPackage.isNotBlank() && !PackageRepository.isAppInstalled(context, rule.targetPackage)) {
                    if (rule.isEnabled) {
                        changedCount++
                        rule.copy(isEnabled = false)
                    } else {
                        rule
                    }
                } else {
                    rule
                }
            }
            if (changedCount > 0) {
                _rules.value = updated
                repository?.updateRules(updated)
                _uiEvent.emit("已自动停用 $changedCount 条未安装应用的规则")
            } else {
                _uiEvent.emit("未检测到需停用的未安装应用规则")
            }
            // 图片规则同样治理：目标应用被卸载后，这条规则永远跳不出去，留着只会误导用户
            val imageUpdated = _imageRules.value.map { rule ->
                if (rule.targetPackage.isNotBlank() &&
                    !PackageRepository.isAppInstalled(context, rule.targetPackage)
                ) {
                    rule.copy(isEnabled = false)
                } else {
                    rule
                }
            }
            persistImageRuleOrder(imageUpdated)
        }
    }

    /**
     * 5. 治理：一键沉底所有未安装应用的规则
     */
    fun sinkUninstalledRules(context: Context) {
        viewModelScope.launch {
            val list = _rules.value
            if (list.isEmpty()) return@launch

            val reDispatchRules = list.filter { it.resolveStrategy == ResolutionStrategy.RE_DISPATCH }
            val standardRules = list.filter { it.resolveStrategy != ResolutionStrategy.RE_DISPATCH }

            fun sinkInGroup(group: List<DispatchRule>): List<DispatchRule> {
                val (installed, uninstalled) = group.partition { rule ->
                    rule.targetPackage.isBlank() || PackageRepository.isAppInstalled(context, rule.targetPackage)
                }
                return installed + uninstalled
            }

            val sortedReDispatch = sinkInGroup(reDispatchRules)
            val sortedStandard = sinkInGroup(standardRules)

            val sortedAll = sortedReDispatch + sortedStandard
            _rules.value = sortedAll
            repository?.updateRules(sortedAll)
            // 图片规则同样沉底：顺序即优先级，把跳不出去的规则留在前面会挡住真正生效的规则
            val (imgInstalled, imgUninstalled) = _imageRules.value.partition { rule ->
                rule.targetPackage.isBlank() || PackageRepository.isAppInstalled(context, rule.targetPackage)
            }
            persistImageRuleOrder(imgInstalled + imgUninstalled)
            _uiEvent.emit("已将未安装应用的规则移至末尾")
        }
    }

    /**
     * 6. 治理：一键全部启用 / 全部停用
     */
    fun toggleAllRules(enabled: Boolean) {
        viewModelScope.launch {
            val list = _rules.value
            val updated = list.map { it.copy(isEnabled = enabled) }
            _rules.value = updated
            repository?.updateRules(updated)
            persistImageRuleOrder(_imageRules.value.map { it.copy(isEnabled = enabled) })
            _uiEvent.emit(if (enabled) "已全部启用规则" else "已全部停用规则")
        }
    }

    /**
     * 批量开关选中的规则（批量模式底栏开关使用）
     */
    fun toggleSelectedRules(enabled: Boolean) {
        viewModelScope.launch {
            val selected = selectedRuleIds.value
            if (selected.isEmpty()) return@launch
            val updated = _rules.value.map {
                if (it.id in selected) it.copy(isEnabled = enabled) else it
            }
            _rules.value = updated
            repository?.updateRules(updated)
            // 图片规则共用同一份选中集合，开关也要一起生效
            if (_imageRules.value.any { it.id in selected }) {
                val updatedImages = _imageRules.value.map {
                    if (it.id in selected) it.copy(isEnabled = enabled) else it
                }
                _imageRules.value = updatedImages
                persistImageRuleOrder(updatedImages)
            }
            _uiEvent.emit(if (enabled) "已启用 ${selected.size} 条规则" else "已停用 ${selected.size} 条规则")
        }
    }

    /**
     * 批量开关选中的提取规则（内置规则同样允许启用/停用）
     */
    fun toggleAllExtractionPatterns(enabled: Boolean) {
        viewModelScope.launch {
            val selected = _selectedExtractionPatternIds.value
            if (selected.isEmpty()) return@launch
            val updated = _extractionPatterns.value.map {
                if (it.id in selected) it.copy(isEnabled = enabled) else it
            }
            _extractionPatterns.value = updated
            repository?.updateExtractionPatterns(updated)
            _uiEvent.emit(if (enabled) "已启用 ${selected.size} 条提取规则" else "已停用 ${selected.size} 条提取规则")
            exitExtractionSelectionMode()
        }
    }

    // ==========================================
    // 图片规则 CRUD 与顺序维护
    //
    // 图片规则没有匹配条件，"顺序"是它唯一的仲裁依据，因此这里的每个写操作
    // 都必须保序：新增追加到末尾，移动/排序后立即落盘。
    // ==========================================

    fun addImageRule(rule: com.moting.linkgo.model.ImageRule) {
        viewModelScope.launch {
            val current = _imageRules.value.toMutableList()
            val finalizedName = generateUniqueName(
                rule.name.ifBlank { "图片规则" },
                current.map { it.name }
            )
            if (finalizedName != rule.name) {
                _uiEvent.emit("规则重名，修正为 $finalizedName")
            }
            current.add(rule.copy(name = finalizedName))
            repository?.updateImageRules(current)
        }
    }

    fun updateImageRule(rule: com.moting.linkgo.model.ImageRule) {
        viewModelScope.launch {
            val rules = _imageRules.value
            val finalizedName = generateUniqueName(
                rule.name.ifBlank { "图片规则" },
                rules.filter { it.id != rule.id }.map { it.name }
            )
            if (finalizedName != rule.name) {
                _uiEvent.emit("规则重名，修正为 $finalizedName")
            }
            repository?.updateImageRules(rules.map { if (it.id == rule.id) rule.copy(name = finalizedName) else it })
        }
    }

    fun deleteImageRule(ruleId: String) {
        viewModelScope.launch {
            repository?.updateImageRules(_imageRules.value.filter { it.id != ruleId })
        }
    }

    fun toggleImageRule(ruleId: String) {
        viewModelScope.launch {
            repository?.updateImageRules(
                _imageRules.value.map { if (it.id == ruleId) it.copy(isEnabled = !it.isEnabled) else it }
            )
        }
    }

    /**
     * 拖拽移动图片规则。
     *
     * 与文本规则不同，这里不做任何分组约束：图片规则没有分发/跳转之分，
     * 用户拖到哪就是哪，位置本身就是优先级。
     */
    fun onMoveImageRule(fromId: String, toId: String) {
        val current = _imageRules.value.toMutableList()
        val fromIndex = current.indexOfFirst { it.id == fromId }
        val toIndex = current.indexOfFirst { it.id == toId }
        if (fromIndex == -1 || toIndex == -1 || fromIndex == toIndex) return
        current.add(toIndex, current.removeAt(fromIndex))
        _imageRules.value = current
    }

    /** 拖拽结束时把顺序落盘 */
    fun saveImageRuleOrder() {
        viewModelScope.launch {
            repository?.updateImageRules(_imageRules.value)
        }
    }

    // ==========================================
    // 图片规则的导出（批量分享 / 导出到文件共用）
    //
    // 包络键用 linkgo_image_rules，SettingsRepository.parseImageRules 已支持该键读取，
    // 因此导出的 JSON 可以直接粘回数据管理里导入，与文本规则的行为对齐。
    // ==========================================

    fun exportAllImageRulesToJson(): String {
        val data = mapOf("linkgo_image_rules" to _imageRules.value)
        return GsonBuilder().setPrettyPrinting().create().toJson(data)
    }

    private fun matchTypeSpecificityWeight(type: MatchType): Int {
        return when (type) {
            MatchType.EXACT -> 0
            MatchType.CONTAINS -> 1
            MatchType.REGEX -> 2
        }
    }
}

