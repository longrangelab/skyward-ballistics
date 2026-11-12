package com.longrange.dragcurve.match

data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height < 0

    fun overlapsHorizontally(o: Rect): Boolean = left < o.right && o.left < right
    fun contains(o: Rect): Boolean = left <= o.left && top <= o.top && right >= o.right && bottom >= o.bottom

    companion object {
        private val BOUNDS = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")

        fun parse(s: String): Rect {
            val m = BOUNDS.find(s) ?: error("bad bounds: $s")
            val (l, t, r, b) = m.destructured
            return Rect(l.toInt(), t.toInt(), r.toInt(), b.toInt())
        }
    }
}

class UiNode(
    val id: Int,
    val className: String,
    text: String?,
    desc: String?,
    val hint: String? = null,
    val bounds: Rect,
    val clickable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val enabled: Boolean = true,
    val packageName: String? = null,
    val longClickable: Boolean = false,
) {
    val text: String = text?.trim().orEmpty()
    val desc: String = desc?.trim().orEmpty()
    val rawText: String? = text

    var parent: UiNode? = null
        private set
    private val _children = mutableListOf<UiNode>()
    val children: List<UiNode> get() = _children

    fun addChild(c: UiNode) {
        c.parent = this
        _children += c
    }

    val isEditText: Boolean get() = editable || className.endsWith("EditText")

    fun walk(): Sequence<UiNode> = sequence {
        yield(this@UiNode)
        for (c in _children) yieldAll(c.walk())
    }

    fun ancestors(): Sequence<UiNode> = generateSequence(parent) { it.parent }

    fun isDescendantOf(other: UiNode): Boolean = ancestors().any { it === other }

    override fun toString(): String =
        "UiNode#$id(${className.substringAfterLast('.')} text='$text' desc='$desc' $bounds" +
            (if (clickable) " clickable" else "") + (if (checkable) " checkable checked=$checked" else "") + ")"
}
