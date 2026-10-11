package io.github.notebook.android

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver

/** Lives above document subcomposition; only presentation primitives enter saved instance state. */
internal class EditorSurfaceState {
    val panel = mutableStateOf<String?>(null)
    val sourceMode = mutableStateOf(false)
    val restoreKeyboard = mutableStateOf(false)
    val caretStart = mutableIntStateOf(0)
    val caretEnd = mutableIntStateOf(0)
    val tableStart = mutableIntStateOf(-1)
    val tableEnd = mutableIntStateOf(0)
}

internal val EditorSurfaceStatesSaver =
    Saver<MutableMap<String, EditorSurfaceState>, List<Any>>(
        save = { states ->
            listOf<Any>(2) +
                states.flatMap { (id, state) ->
                    listOf(
                        id,
                        state.panel.value.orEmpty(),
                        state.sourceMode.value,
                        state.restoreKeyboard.value,
                        state.caretStart.intValue,
                        state.caretEnd.intValue,
                        state.tableStart.intValue,
                        state.tableEnd.intValue,
                    )
                }
        },
        restore = { values ->
            val versioned = values.firstOrNull() == 2
            (if (versioned) values.drop(1) else values)
                .chunked(if (versioned) 8 else 6)
                .associate { row ->
                    row[0] as String to
                        EditorSurfaceState().apply {
                            panel.value = (row[1] as String).ifEmpty { null }
                            sourceMode.value = row[2] as Boolean
                            restoreKeyboard.value = row[3] as Boolean
                            caretStart.intValue = row[4] as Int
                            caretEnd.intValue = row[5] as Int
                            if (versioned) {
                                tableStart.intValue = row[6] as Int
                                tableEnd.intValue = row[7] as Int
                            }
                        }
                }
                .toMutableMap()
        },
    )
