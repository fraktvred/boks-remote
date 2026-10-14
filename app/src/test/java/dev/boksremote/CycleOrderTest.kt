package dev.boksremote

import dev.boksremote.speaker.CycleOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class CycleOrderTest {
    private val names = listOf("A", "B", "C", "D")

    @Test
    fun inOrderWrapsAround() {
        val order = CycleOrder()
        assertEquals("A", order.pick(names, shuffle = false, current = null))
        assertEquals("C", order.pick(names, shuffle = false, current = "B"))
        assertEquals("A", order.pick(names, shuffle = false, current = "D"))
        // A removed palette restarts from the top.
        assertEquals("A", order.pick(names, shuffle = false, current = "gone"))
        assertNull(order.pick(emptyList(), shuffle = false, current = null))
    }

    @Test
    fun shufflePlaysEveryPaletteOncePerRoundWithoutRepeats() {
        val order = CycleOrder(Random(7))
        var current: String? = null
        val played = mutableListOf<String>()
        repeat(names.size * 25) {
            val next = order.pick(names, shuffle = true, current = current)!!
            assertNotEquals(current, next)
            order.played(next, names)
            played += next
            current = next
        }
        played.chunked(names.size).forEach { round -> assertEquals(names.toSet(), round.toSet()) }
    }

    @Test
    fun shuffleWithOnePaletteKeepsIt() {
        assertEquals("A", CycleOrder().pick(listOf("A"), shuffle = true, current = "A"))
    }
}
