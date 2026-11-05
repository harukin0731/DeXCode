package com.example.dexcode.ui

/** Horizontal intervals occupied by system caption buttons; independent of Android for tests. */
object CaptionSpace {
    fun widestGap(width: Int, occupied: List<Pair<Int, Int>>): IntRange? {
        if (width <= 0) return null
        var cursor = 0
        var bestStart = 0
        var bestEnd = 0
        occupied.sortedBy { it.first }.forEach { (left, right) ->
            val start = left.coerceIn(0, width)
            val end = right.coerceIn(start, width)
            if (start - cursor > bestEnd - bestStart) { bestStart = cursor; bestEnd = start }
            cursor = maxOf(cursor, end)
        }
        if (width - cursor > bestEnd - bestStart) { bestStart = cursor; bestEnd = width }
        return if (bestEnd > bestStart) bestStart until bestEnd else null
    }
}
