package com.moting.linkgo.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moting.linkgo.data.SettingsCache
import com.moting.linkgo.data.SettingsRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SettingsUiState(
    val dynamicColorEnabled: Boolean = SettingsCache.dynamicColorEnabled,
    val backgroundBlurEnabled: Boolean = SettingsCache.backgroundBlurEnabled,
    val floatingBottomBarEnabled: Boolean = SettingsCache.floatingBottomBarEnabled,
    val excludeFromRecents: Boolean = SettingsCache.excludeFromRecents,
    val clipboardMonitorEnabled: Boolean = SettingsCache.clipboardMonitorEnabled,
    val clipboardMonitorBackend: String = SettingsCache.clipboardMonitorBackend,
    val appLinkCaptureMode: Int = SettingsCache.appLinkCaptureMode,
    val appLinkCapturedAppsCount: Int = SettingsCache.appLinkCaptureApps.count { it.isEnabled },
    val browserSelectorTimer: Int = SettingsCache.browserSelectorTimer,
    val autoUnfreezeEnabled: Boolean = SettingsCache.autoUnfreezeEnabled,
    val unfreezeShowToast: Boolean = SettingsCache.unfreezeShowToast,
    val edgeGestureConfig: com.moting.linkgo.model.EdgeGestureConfig = SettingsCache.edgeGestureConfig
)

@OptIn(FlowPreview::class)
class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SettingsRepository(application)

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    // 倒计时输入防抖流
    private val timerUpdateFlow = MutableSharedFlow<Int>(extraBufferCapacity = 1)

    init {
        viewModelScope.launch {
            repository.dynamicColorEnabled.collect { v -> _uiState.update { it.copy(dynamicColorEnabled = v) } }
        }
        viewModelScope.launch {
            repository.backgroundBlurEnabled.collect { v -> _uiState.update { it.copy(backgroundBlurEnabled = v) } }
        }
        viewModelScope.launch {
            repository.floatingBottomBarEnabledFlow.collect { v -> _uiState.update { it.copy(floatingBottomBarEnabled = v) } }
        }
        viewModelScope.launch {
            repository.excludeFromRecents.collect { v -> _uiState.update { it.copy(excludeFromRecents = v) } }
        }
        viewModelScope.launch {
            repository.clipboardMonitorEnabled.collect { v -> _uiState.update { it.copy(clipboardMonitorEnabled = v) } }
        }
        viewModelScope.launch {
            repository.clipboardMonitorBackend.collect { v -> _uiState.update { it.copy(clipboardMonitorBackend = v) } }
        }
        viewModelScope.launch {
            repository.appLinkCaptureMode.collect { v -> _uiState.update { it.copy(appLinkCaptureMode = v) } }
        }
        viewModelScope.launch {
            repository.appLinkCaptureApps.collect { list -> _uiState.update { it.copy(appLinkCapturedAppsCount = list.count { it.isEnabled }) } }
        }
        viewModelScope.launch {
            repository.browserSelectorTimer.collect { v -> _uiState.update { it.copy(browserSelectorTimer = v) } }
        }
        viewModelScope.launch {
            repository.autoUnfreezeEnabled.collect { v -> _uiState.update { it.copy(autoUnfreezeEnabled = v) } }
        }
        viewModelScope.launch {
            repository.unfreezeShowToast.collect { v -> _uiState.update { it.copy(unfreezeShowToast = v) } }
        }
        viewModelScope.launch {
            repository.edgeGestureConfigFlow.collect { v -> _uiState.update { it.copy(edgeGestureConfig = v) } }
        }

        // 处理倒计时防抖写入
        viewModelScope.launch {
            timerUpdateFlow
                .debounce(300)
                .distinctUntilChanged()
                .collectLatest { seconds ->
                    repository.updateBrowserSelectorTimer(seconds)
                }
        }
    }

    fun setDynamicColorEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateDynamicColorEnabled(enabled)
        }
    }

    fun setBackgroundBlurEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateBackgroundBlurEnabled(enabled)
        }
    }

    fun setFloatingBottomBarEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateFloatingBottomBarEnabled(enabled)
        }
    }

    fun setExcludeFromRecents(exclude: Boolean) {
        viewModelScope.launch {
            repository.updateExcludeFromRecents(exclude)
        }
    }

    fun onBrowserSelectorTimerChanged(seconds: Int) {
        _uiState.update { it.copy(browserSelectorTimer = seconds) }
        timerUpdateFlow.tryEmit(seconds)
    }

    fun setAutoUnfreezeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateAutoUnfreezeEnabled(enabled)
        }
    }

    fun setUnfreezeShowToast(show: Boolean) {
        viewModelScope.launch {
            repository.updateUnfreezeShowToast(show)
        }
    }
}
