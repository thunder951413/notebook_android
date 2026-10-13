package io.github.notebook.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val LibraryPalette =
    listOf(
        "#FFB4B7",
        "#F3C5AD",
        "#F7DCA5",
        "#E7EAA8",
        "#C2DFC1",
        "#AAD6CF",
        "#AAD6EC",
        "#B7C5ED",
        "#CEC0E8",
        "#E2BDDC",
        "#D8CBC2",
        "#B8C0CC",
    )

internal fun libraryColor(value: String?): Color =
    runCatching { Color(android.graphics.Color.parseColor(value ?: LibraryPalette[6])) }
        .getOrDefault(Color(0xFFAAD6EC))

@Composable
internal fun notePageBackground(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < .5f) MaterialTheme.colorScheme.background
    else MaterialTheme.colorScheme.surface

@Composable
internal fun ReferenceMetaChip(label: String, icon: ImageVector?, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 48.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (icon != null)
                Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface)
            Text(
                label,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
internal fun RoundTool(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = true,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val base = MaterialTheme.colorScheme.surface
    IconButton(onClick, modifier.size(48.dp), enabled = enabled) {
        Box(
            Modifier.size(40.dp)
                .then(
                    if (filled)
                        Modifier.shadow(2.dp, CircleShape)
                            .background(floatingBrush(base, dark), CircleShape)
                            .border(
                                1.dp,
                                if (dark) Color.White.copy(alpha = .08f)
                                else Color.Black.copy(alpha = .025f),
                                CircleShape,
                            )
                    else Modifier
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, Modifier.size(22.dp))
        }
    }
}

@Composable
internal fun FloatingPill(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    Surface(
        modifier.height(56.dp),
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 6.dp,
    ) {
        Row(
            Modifier.background(floatingBrush(MaterialTheme.colorScheme.surface, dark), CircleShape)
                .border(
                    1.dp,
                    if (dark) Color.White.copy(alpha = .08f) else Color.Black.copy(alpha = .025f),
                    CircleShape,
                )
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            content = content,
        )
    }
}

@Composable
internal fun FloatingRound(prominent: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val base =
        if (prominent) {
            if (dark) Color(0xFF868686) else Color(0xFF191919)
        } else MaterialTheme.colorScheme.surface
    Surface(
        Modifier.size(56.dp),
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = if (prominent) Color.White else MaterialTheme.colorScheme.onSurface,
        shadowElevation = 6.dp,
    ) {
        Box(
            Modifier.fillMaxSize()
                .background(floatingBrush(base, dark), CircleShape)
                .border(1.dp, Color.White.copy(alpha = if (dark) .12f else .05f), CircleShape),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

private fun floatingBrush(base: Color, dark: Boolean): Brush =
    Brush.verticalGradient(listOf(lerp(base, Color.White, if (dark) .08f else .02f), base))

@Composable
internal fun LibraryCover(color: Color, modifier: Modifier = Modifier, locked: Boolean = false) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(6.dp)) {
            val w = size.width
            val h = size.height
            val back =
                Path().apply {
                    moveTo(0f, h * .22f)
                    quadraticBezierTo(0f, h * .1f, w * .1f, h * .1f)
                    lineTo(w * .4f, h * .1f)
                    lineTo(w * .5f, h * .22f)
                    lineTo(w * .91f, h * .22f)
                    quadraticBezierTo(w, h * .22f, w, h * .32f)
                    lineTo(w, h * .86f)
                    quadraticBezierTo(w, h * .96f, w * .9f, h * .96f)
                    lineTo(w * .1f, h * .96f)
                    quadraticBezierTo(0f, h * .96f, 0f, h * .86f)
                    close()
                }
            drawPath(back, color)
            drawRoundRect(
                Color.White.copy(alpha = .75f),
                topLeft = androidx.compose.ui.geometry.Offset(w * .04f, h * .36f),
                size = androidx.compose.ui.geometry.Size(w * .92f, h * .57f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .08f),
            )
        }
        if (locked)
            Icon(Icons.Default.Lock, "私密笔记库", Modifier.size(22.dp), tint = Color(0xFF62717C))
    }
}

@Composable
internal fun EmptyNotes(
    title: String,
    description: String,
    library: Boolean = false,
    onCreate: (() -> Unit)? = null,
    /** New device: offer restoring an existing 坚果云 library first. */
    onRestore: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 72.dp, horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (library) LibraryCover(Color(0xFFBDDDEC), Modifier.size(128.dp, 108.dp))
        else
            Box(
                Modifier.size(104.dp).background(MaterialTheme.colorScheme.surface, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.EditNote,
                    null,
                    Modifier.size(54.dp),
                    tint = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        if (onCreate != null)
            Button(
                onCreate,
                shape = CircleShape,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onSurface,
                        contentColor = MaterialTheme.colorScheme.surface,
                    ),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(if (library) "创建笔记库" else "新建笔记")
            }
        if (onRestore != null)
            OutlinedButton(onRestore, shape = CircleShape, modifier = Modifier.testTag("restore-from-cloud")) {
                Icon(Icons.Default.CloudDownload, null, Modifier.size(18.dp))
                Text("连接坚果云，恢复已有笔记", Modifier.padding(start = 8.dp))
            }
    }
}

@Composable
internal fun SheetTitle(
    title: String,
    onClose: () -> Unit,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        action?.invoke()
        RoundTool(Icons.Default.Close, "关闭$title", onClose)
    }
}

@Composable
internal fun SettingsGroup(title: String, items: List<Pair<String, () -> Unit>>) {
    Text(
        title,
        Modifier.padding(start = 20.dp, top = 24.dp, bottom = 10.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 13.sp,
    )
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            items.forEachIndexed { index, item ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(onClick = item.second)
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(item.first, Modifier.weight(1f))
                    Icon(
                        Icons.Default.ChevronRight,
                        null,
                        Modifier.size(19.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (index < items.lastIndex)
                    HorizontalDivider(
                        Modifier.padding(horizontal = 20.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
            }
        }
    }
}
