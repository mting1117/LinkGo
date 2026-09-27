package com.moting.linkgo.image

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 按屏幕坐标指认区域。
 *
 * 这是"取图"能力的唯一入口，取代了原先的自动扫描：
 * 不再由程序猜"哪一块像图片"，而是由用户手指的落点决定取哪一块。
 * 自动判据（类名关键词、无文字区域、面积占比）在实测里既会误判
 * （状态栏图标、空输入框、导航栏背景都被当成图片），又会漏判
 * （类名被混淆的 App 图片、封面上的小图），而"不猜"就没有这两类问题。
 *
 * 取的是落点处**最深**的节点：手指点到哪一层，就取哪一层。
 * 因此点到封面上的"时长标签"会取到标签本身，这是刻意的——
 * 粒度由用户的落点决定，而不是由程序再做一次猜测。
 */
object ScreenRegionPicker {

    private const val TAG = "LinkGo_RegionPicker"

    /** 下降深度上限，防御异常树结构导致的无限递归 */
    private const val MAX_DEPTH = 64

    /**
     * 搜索结果：在整棵子树里挑出最内层的命中节点。
     *
     * 越深越优先；深度相同时取面积更小的那个——同层多个节点重叠时，
     * 面积小的那个才是视觉上压在上面的那一块。
     */
    private class Hit {
        var bounds: Rect? = null
        var depth = -1
        var area = Long.MAX_VALUE

        fun offer(candidate: Rect, candidateDepth: Int, candidateArea: Long) {
            if (candidateDepth > depth || (candidateDepth == depth && candidateArea < area)) {
                bounds = candidate
                depth = candidateDepth
                area = candidateArea
            }
        }
    }

    /**
     * 找出屏幕坐标 (x, y) 处最内层节点的矩形。
     *
     * @return 命中区域；null 表示该点没有任何可用节点（例如点在了纯背景上）
     */
    fun pick(service: AccessibilityService, x: Int, y: Int): Rect? {
        val windows = try {
            service.windows
        } catch (e: Exception) {
            null
        }

        // 多个窗口时取最上层那个：用户看到的是最上面的窗口，落点命中的自然也该是它。
        // layer 越大越靠上，与原列表顺序相比显式排序更可靠
        val ordered = windows?.sortedByDescending { it.layer } ?: emptyList()
        for (window in ordered) {
            val root = try {
                window.root
            } catch (e: Exception) {
                null
            } ?: continue
            // 排除自身窗口：本应用的悬浮层会盖在目标应用之上，不排除就会取到自己画的框
            if (root.packageName?.toString() == service.packageName) continue
            val hit = Hit()
            descend(root, x, y, 0, hit)
            hit.bounds?.let {
                logHit(x, y, it, hit.depth)
                return it
            }
        }

        // 窗口列表拿不到时的兜底，与链接扫描保持一致
        val active = try {
            service.rootInActiveWindow
        } catch (e: Exception) {
            null
        }
        if (active != null && active.packageName?.toString() != service.packageName) {
            val hit = Hit()
            descend(active, x, y, 0, hit)
            hit.bounds?.let {
                logHit(x, y, it, hit.depth)
                return it
            }
        }
        android.util.Log.i(TAG, "指认区域 ($x, $y) → 未命中任何节点")
        return null
    }

    /** 打出命中结果与深度，便于核对"取到的是不是用户指的那一层" */
    private fun logHit(x: Int, y: Int, bounds: Rect, depth: Int) {
        android.util.Log.i(
            TAG,
            "指认区域 ($x, $y) → ${bounds.width()}x${bounds.height()} " +
                "@${bounds.left},${bounds.top} 深度=$depth"
        )
    }

    /**
     * 深度优先收集所有包含落点的节点。
     *
     * **必须是深度优先并遍历全部命中分支**，不能每层只挑一个子节点往下走：
     * 后者一旦选中的那条分支先到底，同层另一条更深的子树就再也看不到了，
     * 结果会停在包着它的上层容器上（实测表现就是"取到的还是外面那层）"）。
     *
     * 剪枝靠矩形本身：有效矩形却不含落点的节点，整棵子树都与落点无关，直接返回。
     * 矩形读取异常（宽高为零）时不剪枝，继续往下找，避免因一次读取失败丢掉整支。
     */
    private fun descend(node: AccessibilityNodeInfo, x: Int, y: Int, depth: Int, hit: Hit) {
        if (depth > MAX_DEPTH) return

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val hasBounds = bounds.width() > 0 && bounds.height() > 0
        if (hasBounds) {
            if (!bounds.contains(x, y)) return
            // 存拷贝：Rect 是可变对象，无障碍树给出的实例会被复用改写
            hit.offer(Rect(bounds), depth, bounds.width().toLong() * bounds.height())
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = try {
                node.getChild(i)
            } catch (e: Exception) {
                null
            } ?: continue
            descend(child, x, y, depth + 1, hit)
        }
    }

    /**
     * 判断给定选区是否属于横向或竖向的窄选（即用户随手划出的一条横线或竖线）。
     *
     * 判定标准：
     * 1. 横向窄（纵向划线）：宽度 <= 24dp，且高度 >= 24dp；
     * 2. 竖向窄（横向划线）：高度 <= 24dp，且宽度 >= 24dp。
     */
    fun isNarrowSelection(rect: Rect, density: Float): Boolean {
        val w = rect.width()
        val h = rect.height()
        val narrowLimit = 24f * density
        val minLength = 24f * density
        val isHorizontalNarrow = w <= narrowLimit && h >= minLength
        val isVerticalNarrow = h <= narrowLimit && w >= minLength
        return isHorizontalNarrow || isVerticalNarrow
    }

    /**
     * 若当前选区属于窄选，则吸附展开为所有相交控件的最左上角与最右下角组成的矩形；
     * 若不属于窄选或未命中相交节点，则原样返回。
     */
    fun snapIfNarrow(service: AccessibilityService, region: Rect, density: Float): Rect {
        if (!isNarrowSelection(region, density)) return region
        val snapped = pickIntersectingUnion(service, region)
        return snapped ?: region
    }

    /**
     * 在无障碍树中找出所有与 [region] 狭窄选区相交的有效控件节点，
     * 并计算其最左上角和最右下角联合组成的外接矩形范围。
     */
    fun pickIntersectingUnion(service: AccessibilityService, region: Rect): Rect? {
        val dm = service.resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels

        val windows = try {
            service.windows
        } catch (e: Exception) {
            null
        }

        val ordered = windows?.sortedByDescending { it.layer } ?: emptyList()
        for (window in ordered) {
            val root = try {
                window.root
            } catch (e: Exception) {
                null
            } ?: continue
            if (root.packageName?.toString() == service.packageName) continue

            val collected = mutableListOf<Rect>()
            collectIntersectingLeafBounds(root, region, screenW, screenH, 0, collected)
            if (collected.isNotEmpty()) {
                val union = computeBoundingBox(collected)
                if (union != null) {
                    android.util.Log.i(TAG, "窄选划线相交吸附 ${collected.size} 个控件 → $union")
                    return union
                }
            }
        }

        // 兜底：活动窗口根节点
        val active = try {
            service.rootInActiveWindow
        } catch (e: Exception) {
            null
        }
        if (active != null && active.packageName?.toString() != service.packageName) {
            val collected = mutableListOf<Rect>()
            collectIntersectingLeafBounds(active, region, screenW, screenH, 0, collected)
            if (collected.isNotEmpty()) {
                val union = computeBoundingBox(collected)
                if (union != null) {
                    android.util.Log.i(TAG, "窄选划线相交吸附(兜底) ${collected.size} 个控件 → $union")
                    return union
                }
            }
        }

        return null
    }

    /**
     * 递归收集与 [region] 相交的最深层有效内容节点边界，自动过滤全屏级大容器。
     */
    private fun collectIntersectingLeafBounds(
        node: AccessibilityNodeInfo,
        region: Rect,
        screenW: Int,
        screenH: Int,
        depth: Int,
        outList: MutableList<Rect>
    ) {
        if (depth > MAX_DEPTH) return

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) return

        // 不相交直接剪枝
        if (!Rect.intersects(bounds, region)) return

        // 过滤超大全屏大容器（宽占屏 85% 且高占屏 70% 以上的大背景/滚动大框架），强制下钻子节点
        val isHugeContainer = bounds.width() >= (screenW * 0.85f).toInt() &&
                bounds.height() >= (screenH * 0.70f).toInt()

        val childCount = node.childCount
        var hasIntersectingChild = false
        for (i in 0 until childCount) {
            val child = try {
                node.getChild(i)
            } catch (e: Exception) {
                null
            } ?: continue
            val childBounds = Rect()
            child.getBoundsInScreen(childBounds)
            if (childBounds.width() > 0 && childBounds.height() > 0 && Rect.intersects(childBounds, region)) {
                hasIntersectingChild = true
                collectIntersectingLeafBounds(child, region, screenW, screenH, depth + 1, outList)
            }
        }

        // 若子节点无相交（到达最深有效内容层），且非超大全屏容器，则计入目标控件
        if (!hasIntersectingChild && !isHugeContainer) {
            outList.add(Rect(bounds))
        }
    }

    /** 计算所有矩形集合的外接矩形（最左上到最右下） */
    private fun computeBoundingBox(rects: List<Rect>): Rect? {
        if (rects.isEmpty()) return null
        var minL = Int.MAX_VALUE
        var minT = Int.MAX_VALUE
        var maxR = Int.MIN_VALUE
        var maxB = Int.MIN_VALUE
        for (r in rects) {
            if (r.left < minL) minL = r.left
            if (r.top < minT) minT = r.top
            if (r.right > maxR) maxR = r.right
            if (r.bottom > maxB) maxB = r.bottom
        }
        return if (maxR > minL && maxB > minT) Rect(minL, minT, maxR, maxB) else null
    }
}
