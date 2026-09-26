package space.subread.dictionary

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import space.subread.dictionary.core.Reorder

/**
 * A vertical list whose rows move by drag, up or down. A press on the handle of a row starts the
 * drag: the row follows the finger, and the other rows move out of the way. On release, `onMove`
 * gets the old index and the new index of the row. The page does not scroll during the drag,
 * except when the finger is at the top or the bottom edge of the page.
 */
// The list is built in code only, so it needs no constructor for a layout file.
@SuppressLint("ViewConstructor")
class DragList(context: Context, private val onMove: (from: Int, to: Int) -> Unit) : LinearLayout(context) {

    private val density = resources.displayMetrics.density
    private var from = -1
    private var to = -1
    private var startY = 0f

    init {
        orientation = VERTICAL
    }

    /** Adds a row. A press on `handle`, a view inside the row, starts the drag. */
    // The handle has no click. TalkBack moves a row with the accessibility actions of the handle.
    @SuppressLint("ClickableViewAccessibility")
    fun add(row: View, handle: View, params: LayoutParams) {
        addView(row, params)
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> start(indexOfChild(row), event.rawY)
                MotionEvent.ACTION_MOVE -> drag(event.rawY)
                MotionEvent.ACTION_UP -> drop(move = true)
                MotionEvent.ACTION_CANCEL -> drop(move = false)
                else -> false
            }
        }
    }

    private fun start(index: Int, rawY: Float): Boolean {
        // A second finger on a second handle does not start a second drag.
        if (from >= 0) return false
        // The page must not take the finger for a scroll.
        requestDisallowInterceptTouchEvent(true)
        from = index
        to = index
        startY = y(rawY)
        getChildAt(index).apply {
            // Over the other rows, white with a black frame, so that it is clear on an e-ink screen.
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke((2 * density).toInt(), Color.BLACK)
            }
            translationZ = 8 * density
        }
        return true
    }

    private fun drag(rawY: Float): Boolean {
        if (from < 0) return false
        scrollAtEdge(rawY)
        val row = getChildAt(from)
        // The row stays inside the list.
        val dy = (y(rawY) - startY).coerceIn(-row.top.toFloat(), (height - row.bottom).toFloat())
        row.translationY = dy
        to = Reorder.target(
            List(childCount) { getChildAt(it).top },
            List(childCount) { getChildAt(it).height },
            from,
            (row.top + row.height / 2 + dy).toInt(),
        )
        // Each other row moves one place up or down, or stays: the height of the dragged row and its margins.
        val params = row.layoutParams as LayoutParams
        val step = row.height + params.topMargin + params.bottomMargin
        val order = Reorder.move(List(childCount) { it }, from, to)
        for (i in 0 until childCount) {
            if (i != from) getChildAt(i).translationY = ((order.indexOf(i) - i) * step).toFloat()
        }
        return true
    }

    private fun drop(move: Boolean): Boolean {
        if (from < 0) return false
        val (a, b) = from to to
        from = -1
        to = -1
        if (move && a != b) {
            // onMove builds the list again, so it runs after the touch event. Until then, the rows stay where the drag put them.
            post { onMove(a, b) }
        } else {
            for (i in 0 until childCount) getChildAt(i).translationY = 0f
            getChildAt(a).apply {
                background = null
                translationZ = 0f
            }
        }
        return true
    }

    /** The finger in the coordinates of this list. They change when the page scrolls. */
    private fun y(rawY: Float): Float {
        val at = IntArray(2)
        getLocationOnScreen(at)
        return rawY - at[1]
    }

    /** Near the top or the bottom edge of the page, each move of the finger scrolls the page a little. */
    private fun scrollAtEdge(rawY: Float) {
        val page = generateSequence(parent) { it.parent }.filterIsInstance<ScrollView>().firstOrNull() ?: return
        val at = IntArray(2)
        page.getLocationOnScreen(at)
        val edge = EDGE_DP * density
        val step = (SCROLL_DP * density).toInt()
        when {
            rawY < at[1] + edge -> page.scrollBy(0, -step)
            rawY > at[1] + page.height - edge -> page.scrollBy(0, step)
        }
    }

    private companion object {
        /** How near the edge of the page the finger must be to scroll it. */
        const val EDGE_DP = 48

        /** How far the page scrolls for each move of the finger at the edge. */
        const val SCROLL_DP = 8
    }
}
