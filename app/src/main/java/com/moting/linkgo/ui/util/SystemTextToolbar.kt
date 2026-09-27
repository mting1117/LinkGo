package com.moting.linkgo.ui.util

import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus

/**
 * 系统级文本工具栏代理
 *
 * Compose 默认的 [TextToolbar] 实现会自绘一个 Popup 浮动工具栏，绕过 Android View 系统的
 * [ActionMode]，导致深度定制系统（HyperOS / ColorOS / OriginOS 等）的增强型长按菜单
 * （AI 摘要、翻译、系统搜索、超级剪贴板等）无法正常显示。
 *
 * 此实现将 Compose 的文本菜单请求完全委托给系统的 [ActionMode.TYPE_FLOATING]，
 * 确保系统 ROM 的完整原生体验。
 */
class SystemTextToolbar(private val view: View) : TextToolbar {

    private var currentActionMode: ActionMode? = null

    override var status: TextToolbarStatus = TextToolbarStatus.Hidden
        private set

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?
    ) {
        // 若已有一个 ActionMode 正在显示，先关闭它再重建，避免重叠
        currentActionMode?.finish()

        val callback = object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                // 注意：HyperOS / ColorOS 的系统会在此回调处自动注入系统级扩展菜单项
                // 我们只需将 Compose 提供的标准语义动作添加进来即可
                if (onCutRequested != null) {
                    menu.add(Menu.NONE, MENU_ID_CUT, Menu.NONE, android.R.string.cut)
                        .setIcon(android.R.drawable.ic_menu_close_clear_cancel)
                        .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                }
                if (onCopyRequested != null) {
                    menu.add(Menu.NONE, MENU_ID_COPY, Menu.NONE, android.R.string.copy)
                        .setIcon(android.R.drawable.ic_menu_send)
                        .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                }
                if (onPasteRequested != null) {
                    menu.add(Menu.NONE, MENU_ID_PASTE, Menu.NONE, android.R.string.paste)
                        .setIcon(android.R.drawable.ic_input_add)
                        .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                }
                if (onSelectAllRequested != null) {
                    menu.add(Menu.NONE, MENU_ID_SELECT_ALL, Menu.NONE, android.R.string.selectAll)
                        .setShowAsAction(MenuItem.SHOW_AS_ACTION_WITH_TEXT)
                }
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                return when (item.itemId) {
                    MENU_ID_CUT -> { onCutRequested?.invoke(); mode.finish(); true }
                    MENU_ID_COPY -> { onCopyRequested?.invoke(); mode.finish(); true }
                    MENU_ID_PASTE -> { onPasteRequested?.invoke(); mode.finish(); true }
                    MENU_ID_SELECT_ALL -> { onSelectAllRequested?.invoke(); true }
                    else -> false
                }
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                currentActionMode = null
                status = TextToolbarStatus.Hidden
            }

            // 关键：覆盖 onGetContentRect，让系统知道被选中文本的位置，
            // 从而将浮动工具栏精确定位在选区上方
            override fun onGetContentRect(mode: ActionMode, view: View, outRect: android.graphics.Rect) {
                outRect.set(
                    rect.left.toInt(),
                    rect.top.toInt(),
                    rect.right.toInt(),
                    rect.bottom.toInt()
                )
            }
        }

        currentActionMode = view.startActionMode(callback, ActionMode.TYPE_FLOATING)
        status = TextToolbarStatus.Shown
    }

    override fun hide() {
        currentActionMode?.finish()
        currentActionMode = null
        status = TextToolbarStatus.Hidden
    }

    companion object {
        private const val MENU_ID_CUT = 0
        private const val MENU_ID_COPY = 1
        private const val MENU_ID_PASTE = 2
        private const val MENU_ID_SELECT_ALL = 3
    }
}
