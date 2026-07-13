package io.github.notebook.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object NotebookColors {
    val Accent = Color(0xFFF3BA00)
    val SidebarLight = Color(0xFFF7F5F0)
    val ListLight = Color(0xFFFAF8F4)
    val EditorLight = Color(0xFFFCFAF6)
    val SidebarDark = Color(0xFF1E1E1E)
    val ListDark = Color(0xFF282828)
    val EditorDark = Color(0xFF2D2D2D)
}

private val Light = lightColorScheme(primary=NotebookColors.Accent,onPrimary=Color(0xFF352800),primaryContainer=Color(0xFFFFE8A3),onPrimaryContainer=Color(0xFF352800),secondary=NotebookColors.Accent,secondaryContainer=Color(0xFFFFF1C2),onSecondaryContainer=Color(0xFF352800),background=NotebookColors.EditorLight,surface=NotebookColors.ListLight,surfaceVariant=NotebookColors.SidebarLight,outlineVariant=Color(0x14000000))
private val Dark = darkColorScheme(primary=NotebookColors.Accent,onPrimary=Color.Black,primaryContainer=Color(0xFF604A00),secondary=NotebookColors.Accent,secondaryContainer=Color(0xFF4A3A00),onSecondaryContainer=Color(0xFFFFE8A3),background=NotebookColors.EditorDark,surface=NotebookColors.ListDark,surfaceVariant=NotebookColors.SidebarDark,outlineVariant=Color(0x14FFFFFF))

@Composable fun NotebookTheme(content: @Composable () -> Unit)=MaterialTheme(colorScheme=if(isSystemInDarkTheme())Dark else Light,typography=Typography(),content=content)
