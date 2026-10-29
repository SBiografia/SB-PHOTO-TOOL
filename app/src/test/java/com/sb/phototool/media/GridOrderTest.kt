package com.sb.phototool.media

import org.junit.Assert.assertEquals
import org.junit.Test

class GridOrderTest {
    @Test
    fun threeByThree_startsAtBottomRightAndEndsAtTopLeft() {
        val order = instagramPostOrder(columns = 3, rows = 3)

        assertEquals(9, order.size)
        assertEquals(2 to 2, order.first())
        assertEquals(0 to 0, order.last())
        assertEquals(listOf(2 to 2, 2 to 1, 2 to 0), order.take(3))
    }
}

