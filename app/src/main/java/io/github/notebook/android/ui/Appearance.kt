package io.github.notebook.android.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * Background colours the user can choose for the whole app. The light and dark
 * choices are stored separately, so the phone switches to the matching one when
 * the system switches between light and dark.
 *
 * [page] is the screen background, [card] the raised cards and sheets,
 * [side] the secondary areas (chips, drawers), [divider] hairlines and borders.
 */
enum class BackgroundPreset(
    val label: String,
    val dark: Boolean,
    val page: Color,
    val card: Color,
    val side: Color,
    val container: Color,
    val divider: Color,
) {
    LightDefault("灰白", false, Color(0xFFF0F1F2), Color.White, Color(0xFFF0F1F2), Color(0xFFE6E7E8), Color(0xFFE4E5E6)),
    LightWhite("纯白", false, Color.White, Color(0xFFF4F4F5), Color(0xFFF4F4F5), Color(0xFFE9E9EA), Color(0xFFE6E6E7)),
    LightYellow("淡黄", false, Color(0xFFF7EFD2), Color(0xFFFCF8E8), Color(0xFFF0E6C4), Color(0xFFEADFB9), Color(0xFFE3D8B2)),
    LightCream("米白", false, Color(0xFFF5F0E6), Color(0xFFFBF8F1), Color(0xFFEEE8DA), Color(0xFFE6DFCE), Color(0xFFDFD8C7)),
    LightGreen("护眼绿", false, Color(0xFFDDEBD9), Color(0xFFEBF4E8), Color(0xFFD1E2CD), Color(0xFFC6D9C2), Color(0xFFBCD0B8)),
    LightBlue("浅蓝", false, Color(0xFFE2ECF5), Color(0xFFEEF4FA), Color(0xFFD6E3F0), Color(0xFFCBDAEA), Color(0xFFC1D1E2)),
    LightPink("浅粉", false, Color(0xFFF7E8E6), Color(0xFFFCF2F0), Color(0xFFF0DCD9), Color(0xFFE8D0CD), Color(0xFFE0C6C3)),

    DarkBlack("纯黑", true, Color.Black, Color(0xFF1A1A1A), Color(0xFF101010), Color(0xFF37383A), Color(0xFF393A3C)),
    DarkGray("深灰", true, Color(0xFF2B2B2D), Color(0xFF3A3A3C), Color(0xFF242426), Color(0xFF48484B), Color(0xFF4C4C4F)),
    DarkGraphite("石墨", true, Color(0xFF1C1C1E), Color(0xFF2C2C2E), Color(0xFF252527), Color(0xFF3A3A3C), Color(0xFF3F3F42)),
    DarkBlue("夜蓝", true, Color(0xFF1B2230), Color(0xFF263044), Color(0xFF212A3B), Color(0xFF30394D), Color(0xFF384258)),
    DarkBrown("深褐", true, Color(0xFF211D18), Color(0xFF2E2923), Color(0xFF27231E), Color(0xFF3A342C), Color(0xFF433C33)),
    DarkGreen("墨绿", true, Color(0xFF18211C), Color(0xFF232E27), Color(0xFF1D2721), Color(0xFF2D3A32), Color(0xFF364439));

    companion object {
        val light = entries.filter { !it.dark }
        val dark = entries.filter { it.dark }
        fun parse(name: String?, dark: Boolean): BackgroundPreset =
            entries.firstOrNull { it.name == name && it.dark == dark } ?: if (dark) DarkBlack else LightDefault
    }
}

/** The user's current background choices; changing one recomposes the theme at once. */
object Appearance {
    private const val PREFS = "appearance"
    private const val LIGHT = "lightBackground"
    private const val DARK = "darkBackground"

    var light by mutableStateOf(BackgroundPreset.LightDefault)
        private set
    var dark by mutableStateOf(BackgroundPreset.DarkBlack)
        private set
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        light = BackgroundPreset.parse(prefs.getString(LIGHT, null), dark = false)
        dark = BackgroundPreset.parse(prefs.getString(DARK, null), dark = true)
        loaded = true
    }

    fun choose(context: Context, preset: BackgroundPreset) {
        if (preset.dark) dark = preset else light = preset
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(if (preset.dark) DARK else LIGHT, preset.name).apply()
    }
}
