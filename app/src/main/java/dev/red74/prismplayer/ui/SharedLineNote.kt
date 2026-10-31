package dev.red74.prismplayer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.red74.prismplayer.data.SharedLine

/** A small line at the top while the app waits for a list's turn shared with iPrism (see [SharedLine]). */
@Composable
fun SharedLineNote() {
    val note by SharedLine.note.collectAsState()
    val text = note ?: return
    Box(Modifier.fillMaxSize().padding(top = 24.dp), contentAlignment = Alignment.TopCenter) {
        GlassPanel {
            PrismText(text = text, size = 17.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
        }
    }
}
