package com.moting.linkgo.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.compositionContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 无障碍 Overlay 管理器
 * 负责在 Service 环境下管理 WindowManager 窗口，并为 Compose 提供必要的运行环境
 */
class OverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var currentView: View? = null

    /**
     * 为 View 伪造生命周期环境，以便在 Service 中运行 Compose
     */
    private class FakeLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val _viewModelStore = ViewModelStore()
        private val savedStateRegistryController = SavedStateRegistryController.create(this)

        init {
            savedStateRegistryController.performRestore(null)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val viewModelStore: ViewModelStore get() = _viewModelStore
        override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

        fun destroy() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
    }

    private var fakeLifecycleOwner: FakeLifecycleOwner? = null

    /**
     * 展示 Overlay 窗口
     */
    fun showOverlay(view: View, focusable: Boolean = true) {
        if (currentView != null) {
            removeOverlay()
        }

        val params = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }

            if (!focusable) {
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            }
            
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.FILL // 改为 FILL 确保填充
        }

        // 强制 View 布局到系统栏区域
        view.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                                  View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                                  View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        // 如果是 ComposeView，设置生命周期环境
        if (view is AbstractComposeView || view.findViewById<View>(android.R.id.content) != null) {
            setupComposeEnvironment(view)
        }

        windowManager.addView(view, params)
        currentView = view
    }

    private fun setupComposeEnvironment(view: View) {
        val owner = FakeLifecycleOwner()
        view.setViewTreeLifecycleOwner(owner)
        view.setViewTreeViewModelStoreOwner(owner)
        view.setViewTreeSavedStateRegistryOwner(owner)
        fakeLifecycleOwner = owner
    }

    /**
     * 移除 Overlay 窗口
     */
    fun removeOverlay() {
        currentView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            fakeLifecycleOwner?.destroy()
            fakeLifecycleOwner = null
            currentView = null
        }
    }

    /**
     * 更新窗口焦点状态
     */
    fun setFocusable(focusable: Boolean) {
        val view = currentView ?: return
        val params = view.layoutParams as WindowManager.LayoutParams
        if (focusable) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        windowManager.updateViewLayout(view, params)
    }
}
