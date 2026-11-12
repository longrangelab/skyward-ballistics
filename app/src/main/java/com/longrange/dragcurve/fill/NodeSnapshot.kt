package com.longrange.dragcurve.fill

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.longrange.dragcurve.match.Rect
import com.longrange.dragcurve.match.CalcAppUi
import com.longrange.dragcurve.match.UiNode

class NodeSnapshot private constructor(
    val roots: List<UiNode>,
    private val infos: Map<Int, AccessibilityNodeInfo>,
    val appInForeground: Boolean,
) {
    fun info(n: UiNode): AccessibilityNodeInfo? = infos[n.id]

    companion object {
        private const val MAX_NODES = 4000
        private const val MAX_DEPTH = 80

        fun take(service: AccessibilityService, pkg: String = CalcAppUi.PACKAGE): NodeSnapshot {
            val infos = HashMap<Int, AccessibilityNodeInfo>()
            val roots = mutableListOf<UiNode>()
            var nextId = 0
            var count = 0

            fun build(info: AccessibilityNodeInfo, depth: Int): UiNode {
                val cls0 = info.className?.toString().orEmpty()
                if (info.isEditable || cls0.endsWith("EditText") || info.isCheckable) {
                    try {
                        info.refresh()
                    } catch (_: Exception) {
                    }
                }
                val r = android.graphics.Rect()
                info.getBoundsInScreen(r)
                val cls = info.className?.toString().orEmpty()
                var text = info.text?.toString()
                var hint: String? = null
                if (Build.VERSION.SDK_INT >= 26) {
                    hint = info.hintText?.toString()
                    if (info.isShowingHintText && hint != null && text == hint) text = ""
                }
                val node = UiNode(
                    id = nextId++,
                    className = cls,
                    text = text,
                    desc = info.contentDescription?.toString(),
                    hint = hint,
                    bounds = Rect(r.left, r.top, r.right, r.bottom),
                    clickable = info.isClickable,
                    checkable = info.isCheckable,
                    checked = info.isChecked,
                    editable = info.isEditable || cls.endsWith("EditText"),
                    scrollable = info.isScrollable,
                    enabled = info.isEnabled,
                    packageName = info.packageName?.toString(),
                    longClickable = info.isLongClickable,
                )
                infos[node.id] = info
                count++
                if (depth < MAX_DEPTH) {
                    for (i in 0 until info.childCount) {
                        if (count >= MAX_NODES) break
                        val c = info.getChild(i) ?: continue
                        node.addChild(build(c, depth + 1))
                    }
                }
                return node
            }

            if (Build.VERSION.SDK_INT >= 34) {
                try {
                    service.clearCache()
                } catch (_: Exception) {
                }
            }
            val windows = try {
                service.windows.sortedByDescending { it.layer }
            } catch (e: Exception) {
                emptyList()
            }
            var foreground = false
            for (w in windows) {
                val root = w.root ?: continue
                if (root.packageName?.toString() != pkg) continue
                if (w.isActive || w.isFocused) foreground = true
                roots += build(root, 0)
            }
            if (roots.isEmpty()) {
                val active = service.rootInActiveWindow
                if (active != null && active.packageName?.toString() == pkg) {
                    foreground = true
                    roots += build(active, 0)
                }
            } else if (!foreground) {
                val active = service.rootInActiveWindow
                foreground = active?.packageName?.toString() == pkg
            }
            return NodeSnapshot(roots, infos, foreground)
        }
    }
}
