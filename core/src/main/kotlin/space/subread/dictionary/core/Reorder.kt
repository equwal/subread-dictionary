package space.subread.dictionary.core

/** The order of a list that the user changes by drag: the dictionaries in the settings. */
object Reorder {

    /** The list with the element at `from` moved to `to`. The other elements keep their order. */
    fun <T> move(list: List<T>, from: Int, to: Int): List<T> {
        require(from in list.indices && to in list.indices) { "from $from, to $to, size ${list.size}" }
        return list.toMutableList().apply { add(to, removeAt(from)) }
    }

    /**
     * The index where the dragged row goes: after each other row whose middle is above `y`.
     * `tops` and `heights` are the rows where the layout put them, before the drag. `y` is the
     * middle of the dragged row now. The index is always in the list. When `y` increases, the
     * index does not decrease.
     */
    fun target(tops: List<Int>, heights: List<Int>, from: Int, y: Int): Int {
        require(tops.size == heights.size && from in tops.indices) { "from $from, ${tops.size} tops, ${heights.size} heights" }
        return tops.indices.count { it != from && tops[it] + heights[it] / 2 < y }
    }
}
