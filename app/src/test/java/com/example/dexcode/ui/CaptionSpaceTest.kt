package com.example.dexcode.ui

import org.junit.Assert.*
import org.junit.Test

class CaptionSpaceTest {
    @Test fun leavesSystemButtonsOnBothSidesUntouched() {
        assertEquals(80 until 800, CaptionSpace.widestGap(1000, listOf(0 to 80, 800 to 1000)))
    }
    @Test fun overlappingAndUnsortedButtonsAreMerged() {
        assertEquals(100 until 700, CaptionSpace.widestGap(1000, listOf(800 to 1000, 0 to 60, 40 to 100, 700 to 850)))
    }
    @Test fun centralButtonsChooseOneContiguousSafeArea() {
        assertEquals(550 until 1000, CaptionSpace.widestGap(1000, listOf(400 to 550)))
    }
    @Test fun outOfBoundsAndFullyOccupiedWindowsAreSafe() {
        assertNull(CaptionSpace.widestGap(100, listOf(-10 to 200)))
        assertNull(CaptionSpace.widestGap(0, emptyList()))
        assertEquals(0 until 100, CaptionSpace.widestGap(100, emptyList()))
    }
}
