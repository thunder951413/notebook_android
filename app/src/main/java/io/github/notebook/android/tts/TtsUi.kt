package io.github.notebook.android.tts

import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/** Floating controls while a note is being read aloud (or why it stopped). */
@Composable
fun TtsReaderBar(modifier: Modifier = Modifier, onSpeedChosen: (Float) -> Unit) {
    val state by TtsPlayer.state.collectAsState()
    if (state.phase == TtsPhase.Idle && state.error == null) return
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, tonalElevation = 6.dp, shadowElevation = 4.dp) {
        if (state.phase == TtsPhase.Idle) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("朗读失败：${state.error}", Modifier.weight(1f).padding(vertical = 10.dp), color = MaterialTheme.colorScheme.error, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                IconButton(TtsPlayer::dismissError) { Icon(Icons.Default.Close, "关闭") }
            }
            return@Surface
        }
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(state.title.ifBlank { "朗读" }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                Text(
                    if (state.phase == TtsPhase.Loading) "正在合成语音…" else "${if (state.phase == TtsPhase.Paused) "已暂停" else "朗读中"} · ${state.index + 1}/${state.total}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton({
                val next = TtsSettings.SPEEDS.firstOrNull { it > state.speed + 0.01f } ?: TtsSettings.SPEEDS.first()
                TtsPlayer.setSpeed(next)
                onSpeedChosen(next)
            }) { Text(state.speed.toBigDecimal().setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "×") }
            IconButton(TtsPlayer::previous) { Icon(Icons.Default.SkipPrevious, "上一段") }
            IconButton(TtsPlayer::toggle) {
                Icon(if (state.phase == TtsPhase.Paused) Icons.Default.PlayArrow else Icons.Default.Pause, if (state.phase == TtsPhase.Paused) "继续" else "暂停")
            }
            IconButton(TtsPlayer::next) { Icon(Icons.Default.SkipNext, "下一段") }
            IconButton(TtsPlayer::stop) { Icon(Icons.Default.Close, "停止朗读") }
        }
    }
}
