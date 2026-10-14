package dev.boksremote

import dev.boksremote.speaker.SleepFade
import org.junit.Assert.assertEquals
import org.junit.Test

class SleepFadeTest {
    @Test
    fun fadesEvenlyToDark() {
        assertEquals(200, SleepFade.levelAt(200, 0, 60_000))
        assertEquals(100, SleepFade.levelAt(200, 30_000, 60_000))
        assertEquals(0, SleepFade.levelAt(200, 60_000, 60_000))
        assertEquals(0, SleepFade.levelAt(200, 90_000, 60_000))
    }
}
