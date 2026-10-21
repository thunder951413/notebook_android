package io.github.notebook.android.ui

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp

object NotebookColors {
    val Accent = Color(0xFFFF8000)
    val AccentTextLight = Color(0xFFBC5900)
    val SidebarLight = Color(0xFFF0F1F2)
    val ListLight = Color.White
    val EditorLight = Color(0xFFF0F1F2)
    val SidebarDark = Color(0xFF101010)
    val ListDark = Color(0xFF1A1A1A)
    val EditorDark = Color.Black
}

private fun lightScheme(bg: BackgroundPreset) =
    lightColorScheme(
        primary = NotebookColors.AccentTextLight,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFFFE6D0),
        onPrimaryContainer = Color(0xFF452100),
        secondary = Color(0xFF191919),
        onSecondary = Color.White,
        secondaryContainer = bg.container,
        onSecondaryContainer = Color(0xFF191919),
        tertiary = NotebookColors.Accent,
        tertiaryContainer = Color(0xFFFFDDBE),
        background = bg.page,
        surface = bg.card,
        surfaceVariant = bg.side,
        onBackground = Color(0xFF191919),
        onSurface = Color(0xFF191919),
        onSurfaceVariant = Color(0xFF757575),
        outline = Color(0xFF989A9D),
        outlineVariant = bg.divider,
    )
private fun darkScheme(bg: BackgroundPreset) =
    darkColorScheme(
        primary = NotebookColors.Accent,
        onPrimary = Color.Black,
        primaryContainer = Color(0xFF51321D),
        onPrimaryContainer = Color(0xFFFFDDBE),
        secondary = Color(0xFFEAEAEA),
        onSecondary = Color(0xFF191919),
        secondaryContainer = bg.container,
        tertiary = NotebookColors.Accent,
        tertiaryContainer = Color(0xFF754414),
        background = bg.page,
        surface = bg.card,
        surfaceVariant = bg.side,
        onBackground = Color(0xFFF2F2F2),
        onSurface = Color(0xFFF2F2F2),
        onSurfaceVariant = Color(0xFFA4A5A7),
        outline = Color(0xFF7C7E81),
        outlineVariant = bg.divider,
    )

@Composable
fun NotebookTheme(content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    // Follows the system: the light and dark backgrounds are chosen separately.
    val colors = if (systemDark) darkScheme(Appearance.dark) else lightScheme(Appearance.light)
    // The window behind the first frame and the keyboard resize must match too.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect { (view.context as? Activity)?.window?.setBackgroundDrawable(ColorDrawable(colors.background.toArgb())) }
    }
    MaterialTheme(
        colorScheme = colors,
        typography = Typography(),
        shapes =
            Shapes(
                extraSmall = RoundedCornerShape(8.dp),
                small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(20.dp),
                large = RoundedCornerShape(26.dp),
                extraLarge = RoundedCornerShape(30.dp),
            ),
    ) {
        // Background-painted routes and translucent floating surfaces do not
        // automatically supply Material's foreground color.
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onBackground,
            content = content,
        )
    }
}
