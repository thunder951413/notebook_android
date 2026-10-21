package io.github.notebook.android.tts

import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val SPEAK_FROM_HERE = 0x7701
private const val SPEAK_SELECTION = 0x7702

/**
 * Adds "朗读" (from the long-pressed spot to the end) and "朗读所选" to the text
 * selection toolbar. The callback gets the view and whether only the selection
 * should be read.
 */
fun TextView.installSpeakMenu(onSpeak: (TextView, Boolean) -> Unit) {
    customSelectionActionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(Menu.NONE, SPEAK_FROM_HERE, 0, "朗读").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.add(Menu.NONE, SPEAK_SELECTION, 1, "朗读所选").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId != SPEAK_FROM_HERE && item.itemId != SPEAK_SELECTION) return false
            onSpeak(this@installSpeakMenu, item.itemId == SPEAK_SELECTION)
            mode.finish()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) = Unit
    }
}

/** Shared between the in-note bar and the app-wide mini player. */
internal object TtsOverlayState {
    /** How many in-note bars are on screen; the mini player hides while one is. */
    var inlineBars by mutableIntStateOf(0)
    /** Where the user dragged the mini player icon, kept across screens. */
    var miniOffset by mutableStateOf(Offset.Zero)
}

private fun formatSpeed(speed: Float) =
    speed.toBigDecimal().setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "×"

/** Controls inside the note that is being read (or why reading stopped). */
@Composable
fun TtsReaderBar(noteId: String?, modifier: Modifier = Modifier, onSpeedChosen: (Float) -> Unit) {
    val state by TtsPlayer.state.collectAsState()
    if (state.noteId != noteId || (state.phase == TtsPhase.Idle && state.error == null)) return
    DisposableEffect(Unit) {
        TtsOverlayState.inlineBars++
        onDispose { TtsOverlayState.inlineBars-- }
    }
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, tonalElevation = 6.dp, shadowElevation = 4.dp) {
        if (state.phase == TtsPhase.Idle) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("朗读失败：${state.error}", Modifier.weight(1f).padding(vertical = 10.dp), color = MaterialTheme.colorScheme.error, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                IconButton(TtsPlayer::dismissError) { Icon(Icons.Default.Close, "关闭") }
            }
        } else {
            TtsControls(state, onSpeedChosen)
        }
    }
}

/** One compact row: progress, speed menu, previous / play-pause / next / stop. */
@Composable
private fun TtsControls(state: TtsState, onSpeedChosen: (Float) -> Unit) {
    Row(Modifier.padding(start = 14.dp, end = 4.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (state.phase == TtsPhase.Loading) "合成中…" else "${state.index + 1}/${state.total}",
            maxLines = 1,
            softWrap = false,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        SpeedMenu(state.speed, onSpeedChosen)
        IconButton(TtsPlayer::previous, Modifier.size(40.dp)) { Icon(Icons.Default.SkipPrevious, "上一段") }
        IconButton(TtsPlayer::toggle, Modifier.size(40.dp)) {
            Icon(if (state.phase == TtsPhase.Paused) Icons.Default.PlayArrow else Icons.Default.Pause, if (state.phase == TtsPhase.Paused) "继续" else "暂停")
        }
        IconButton(TtsPlayer::next, Modifier.size(40.dp)) { Icon(Icons.Default.SkipNext, "下一段") }
        IconButton(TtsPlayer::stop, Modifier.size(40.dp)) { Icon(Icons.Default.Close, "停止朗读") }
    }
}

@Composable
private fun SpeedMenu(speed: Float, onSpeedChosen: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton({ open = true }, contentPadding = PaddingValues(start = 8.dp, end = 2.dp)) {
            Text(formatSpeed(speed), fontSize = 14.sp)
            Icon(Icons.Default.ArrowDropDown, "选择朗读速度", Modifier.size(18.dp))
        }
        DropdownMenu(open, { open = false }) {
            TtsSettings.SPEEDS.forEach { value ->
                val current = kotlin.math.abs(value - speed) < 0.01f
                DropdownMenuItem(
                    text = { Text(formatSpeed(value), fontWeight = if (current) FontWeight.SemiBold else null) },
                    onClick = {
                        open = false
                        TtsPlayer.setSpeed(value)
                        onSpeedChosen(value)
                    },
                    trailingIcon = { if (current) Icon(Icons.Default.Check, null, Modifier.size(18.dp)) },
                )
            }
        }
    }
}

/**
 * App-wide player while something is being read and its note is not on screen:
 * a small draggable icon that opens into the full controls.
 */
@Composable
fun TtsMiniPlayer(onOpenNote: (String) -> Unit, onSpeedChosen: (Float) -> Unit) {
    val state by TtsPlayer.state.collectAsState()
    if (state.phase == TtsPhase.Idle || TtsOverlayState.inlineBars > 0) return
    var expanded by remember { mutableStateOf(false) }
    BackHandler(expanded) { expanded = false }
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val density = LocalDensity.current
        val iconSize = 52.dp
        val endGap = 16.dp
        val bottomGap = 160.dp
        if (expanded) {
            Surface(
                Modifier.align(Alignment.BottomCenter).padding(12.dp).widthIn(max = 560.dp).fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                tonalElevation = 6.dp,
                shadowElevation = 6.dp,
            ) {
                Column {
                    Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(state.title.ifBlank { "朗读" }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        state.noteId?.let { id ->
                            TextButton({ expanded = false; onOpenNote(id) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("打开笔记", fontSize = 13.sp) }
                        }
                        IconButton({ expanded = false }, Modifier.size(40.dp)) { Icon(Icons.Default.KeyboardArrowDown, "收起") }
                    }
                    TtsControls(state, onSpeedChosen)
                }
            }
        } else {
            val (minX, maxX, minY, maxY) = with(density) {
                listOf(-(maxWidth - iconSize - endGap * 2).toPx(), 0f, -(maxHeight - iconSize - bottomGap - endGap).toPx(), (bottomGap - endGap).toPx())
            }
            val offset = TtsOverlayState.miniOffset
            Surface(
                onClick = { expanded = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = endGap, bottom = bottomGap)
                    .offset { IntOffset(offset.x.coerceIn(minX, maxX).roundToInt(), offset.y.coerceIn(minY, maxY).roundToInt()) }
                    .pointerInput(minX, maxX, minY, maxY) {
                        detectDragGestures { change, amount ->
                            change.consume()
                            val now = TtsOverlayState.miniOffset + amount
                            TtsOverlayState.miniOffset = Offset(now.x.coerceIn(minX, maxX), now.y.coerceIn(minY, maxY))
                        }
                    }
                    .size(iconSize),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                tonalElevation = 6.dp,
                shadowElevation = 6.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    when (state.phase) {
                        TtsPhase.Loading -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        TtsPhase.Paused -> Icon(Icons.Default.PlayArrow, "朗读已暂停，点开控制")
                        else -> Icon(Icons.Default.GraphicEq, "正在朗读，点开控制")
                    }
                }
            }
        }
    }
}
