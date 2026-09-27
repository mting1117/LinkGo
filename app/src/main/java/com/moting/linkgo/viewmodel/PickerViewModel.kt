package com.moting.linkgo.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moting.linkgo.data.PackageRepository
import com.moting.linkgo.model.ActivityInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AppFilter { ALL, USER, SYSTEM }

class PickerViewModel : ViewModel() {
    // 观察仓库中的全局应用列表
    val appList = PackageRepository.appList
    
    // 仓库的全局加载状态（用于应用列表）
    val isLoading = PackageRepository.isLoading

    // 当前筛选模式
    private val _currentFilter = MutableStateFlow(AppFilter.ALL)
    val currentFilter = _currentFilter.asStateFlow()

    private val _activityList = MutableStateFlow<List<ActivityInfo>>(emptyList())
    val activityList = _activityList.asStateFlow()

    // 针对 Activity 加载的单独状态（因为 Activity 扫描较快，通常在页面内处理）
    private val _isActivityLoading = MutableStateFlow(false)
    val isActivityLoading = _isActivityLoading.asStateFlow()

    fun setFilter(filter: AppFilter) {
        _currentFilter.value = filter
    }

    fun getAppIcon(context: Context, packageName: String) = PackageRepository.getAppIcon(context, packageName)

    fun loadAllApps(context: Context) {
        viewModelScope.launch {
            PackageRepository.loadAllApps(context.applicationContext)
        }
    }

    fun loadActivities(context: Context, packageName: String) {
        viewModelScope.launch {
            _isActivityLoading.value = true
            _activityList.value = PackageRepository.loadActivities(context.applicationContext, packageName)
            _isActivityLoading.value = false
        }
    }
}
