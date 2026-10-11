package io.github.notebook.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
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

private val Light =
    lightColorScheme(
        primary = NotebookColors.AccentTextLight,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFFFE6D0),
        onPrimaryContainer = Color(0xFF452100),
        secondary = Color(0xFF191919),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFE6E7E8),
        onSecondaryContainer = Color(0xFF191919),
        tertiary = NotebookColors.Accent,
        tertiaryContainer = Color(0xFFFFDDBE),
        background = NotebookColors.EditorLight,
        surface = Color.White,
        surfaceVariant = NotebookColors.SidebarLight,
        onBackground = Color(0xFF191919),
        onSurface = Color(0xFF191919),
        onSurfaceVariant = Color(0xFF757575),
        outline = Color(0xFF989A9D),
        outlineVariant = Color(0xFFE4E5E6),
    )
private val Dark =
    darkColorScheme(
        primary = NotebookColors.Accent,
        onPrimary = Color.Black,
        primaryContainer = Color(0xFF51321D),
        onPrimaryContainer = Color(0xFFFFDDBE),
        secondary = Color(0xFFEAEAEA),
        onSecondary = Color(0xFF191919),
        secondaryContainer = Color(0xFF37383A),
        tertiary = NotebookColors.Accent,
        tertiaryContainer = Color(0xFF754414),
        background = NotebookColors.EditorDark,
        surface = NotebookColors.ListDark,
        surfaceVariant = NotebookColors.SidebarDark,
        onBackground = Color(0xFFF2F2F2),
        onSurface = Color(0xFFF2F2F2),
        onSurfaceVariant = Color(0xFFA4A5A7),
        outline = Color(0xFF7C7E81),
        outlineVariant = Color(0xFF393A3C),
    )

@Composable
fun NotebookTheme(content: @Composable () -> Unit) =
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
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
