package space.subread.dictionary.core

import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.flatMap
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReorderTest {

    private data class Move(val list: List<Int>, val from: Int, val to: Int)

    // Few values, so that the lists have equal elements too.
    private val arbMove = Arb.list(Arb.int(0..5), 1..20).flatMap { list ->
        Arb.bind(Arb.int(list.indices), Arb.int(list.indices)) { from, to -> Move(list, from, to) }
    }

    /** Rows one under the other, as a LinearLayout puts them: each row has a height and a gap above it. */
    private data class Layout(val tops: List<Int>, val heights: List<Int>) {
        fun middle(i: Int) = tops[i] + heights[i] / 2
        val bottom get() = tops.last() + heights.last()
    }

    private val arbLayout = Arb.list(Arb.bind(Arb.int(1..200), Arb.int(0..30)) { height, gap -> height to gap }, 1..20).map { rows ->
        val tops = ArrayList<Int>()
        var top = 0
        for ((height, gap) in rows) {
            top += gap
            tops.add(top)
            top += height
        }
        Layout(tops, rows.map { it.first })
    }

    private data class Drag(val layout: Layout, val from: Int, val y: Int, val y2: Int)

    private val arbDrag = arbLayout.flatMap { layout ->
        val ys = Arb.int(-100..layout.bottom + 100)
        Arb.bind(Arb.int(layout.tops.indices), ys, ys) { from, y, y2 -> Drag(layout, from, y, y2) }
    }

    @Test
    fun moveKeepsTheOtherElementsInTheirOrder(): Unit = runBlocking {
        checkAll(arbMove) { (list, from, to) ->
            val moved = Reorder.move(list, from, to)
            assertEquals(list.sorted(), moved.sorted())
            assertEquals(list[from], moved[to])
            assertEquals(list.filterIndexed { i, _ -> i != from }, moved.filterIndexed { i, _ -> i != to })
            // The move back gives the list again.
            assertEquals(list, Reorder.move(moved, to, from))
        }
    }

    @Test
    fun theTargetIsInTheListAndGoesDownWithTheFinger(): Unit = runBlocking {
        checkAll(arbDrag) { (layout, from, y, y2) ->
            val a = Reorder.target(layout.tops, layout.heights, from, minOf(y, y2))
            val b = Reorder.target(layout.tops, layout.heights, from, maxOf(y, y2))
            assertTrue(a in layout.tops.indices)
            assertTrue(b in layout.tops.indices)
            assertTrue("$a > $b", a <= b)
        }
    }

    @Test
    fun theRowGoesBetweenTheMiddlesAboveAndBelowTheFinger(): Unit = runBlocking {
        checkAll(arbDrag) { (layout, from, y, _) ->
            val to = Reorder.target(layout.tops, layout.heights, from, y)
            val order = Reorder.move(layout.tops.indices.toList(), from, to)
            for (k in order.indices) {
                if (k < to) assertTrue(layout.middle(order[k]) < y)
                if (k > to) assertTrue(layout.middle(order[k]) >= y)
            }
        }
    }

    @Test
    fun aRowAtItsOwnMiddleStaysAndTheEdgesAreTheEnds(): Unit = runBlocking {
        checkAll(arbDrag) { (layout, from, _, _) ->
            val last = layout.tops.lastIndex
            assertEquals(from, Reorder.target(layout.tops, layout.heights, from, layout.middle(from)))
            assertEquals(0, Reorder.target(layout.tops, layout.heights, from, Int.MIN_VALUE))
            assertEquals(last, Reorder.target(layout.tops, layout.heights, from, Int.MAX_VALUE))
        }
    }

    @Test
    fun theFirstRowGoesToTheEndAndBack() {
        assertEquals(listOf("b", "c", "a"), Reorder.move(listOf("a", "b", "c"), 0, 2))
        assertEquals(listOf("c", "a", "b"), Reorder.move(listOf("a", "b", "c"), 2, 0))
        // Rows 0..2 of 100 px, no gap. The middle of row 0 goes past the middle of row 2 (250).
        val tops = listOf(0, 100, 200)
        val heights = listOf(100, 100, 100)
        assertEquals(1, Reorder.target(tops, heights, 0, 250))
        assertEquals(2, Reorder.target(tops, heights, 0, 251))
        assertEquals(0, Reorder.target(tops, heights, 2, 50))
    }
}
