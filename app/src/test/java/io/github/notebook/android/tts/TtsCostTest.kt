package io.github.notebook.android.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TtsCostTest {
    @Test fun `spend follows the price per ten thousand characters`() {
        assertEquals(0.8, TtsCost.spent(10_000, 0.8f), 1e-6)
        assertEquals(0.4, TtsCost.spent(5_000, 0.8f), 1e-6)
    }

    @Test fun `remaining is only known when a balance was set`() {
        assertNull(TtsCost.remaining(0f, 50_000, 0.8f))
        assertEquals(6.0, TtsCost.remaining(10f, 50_000, 0.8f)!!, 1e-6)
        assertEquals(-0.8, TtsCost.remaining(1f, 22_500, 0.8f)!!, 1e-6)
    }

    @Test fun `characters left and yuan formatting`() {
        assertEquals(75_000L, TtsCost.charactersLeft(6.0, 0.8f))
        assertEquals(0L, TtsCost.charactersLeft(-1.0, 0.8f))
        assertEquals("¥6.00", TtsCost.yuan(6.0))
        assertEquals("-¥0.80", TtsCost.yuan(-0.8))
    }
}
