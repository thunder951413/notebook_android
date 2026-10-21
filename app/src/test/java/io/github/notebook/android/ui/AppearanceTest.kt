package io.github.notebook.android.ui

import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceTest {
    @Test fun `light presets are light and dark presets are dark`() {
        // Reader components decide light or dark from the background luminance.
        assertTrue(BackgroundPreset.light.all { it.page.luminance() > .5f && it.card.luminance() > .5f })
        assertTrue(BackgroundPreset.dark.all { it.page.luminance() < .5f && it.card.luminance() < .5f })
    }

    @Test fun `unknown or mismatched names fall back to the defaults`() {
        assertEquals(BackgroundPreset.LightDefault, BackgroundPreset.parse(null, dark = false))
        assertEquals(BackgroundPreset.DarkBlack, BackgroundPreset.parse("LightYellow", dark = true))
        assertEquals(BackgroundPreset.LightYellow, BackgroundPreset.parse("LightYellow", dark = false))
        assertEquals(BackgroundPreset.DarkGray, BackgroundPreset.parse("DarkGray", dark = true))
    }
}
