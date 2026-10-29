package dev.red74.prismplayer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import dev.red74.prismplayer.PrismApp
import dev.red74.prismplayer.R
import dev.red74.prismplayer.data.Shortcuts
import dev.red74.prismplayer.device.InstalledApp
import dev.red74.prismplayer.device.InstalledApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Shortcuts to other apps installed on the TV. Left: the pinned apps (OK opens one, the button next to it removes it).
 * Right: every installed app that is not pinned yet (OK adds it).
 */
@Composable
fun ShortcutsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { (context.applicationContext as PrismApp).container.settings }
    var pinned by remember { mutableStateOf(settings.shortcuts) }
    var message by remember { mutableStateOf<String?>(null) }
    val firstFocus = remember { FocusRequester() }
    RequestFocusOnEnter(firstFocus)

    val installed by produceState<List<InstalledApp>?>(null) {
        value = withContext(Dispatchers.Default) { InstalledApps.list(context) }
    }
    val byPackage = remember(installed) { installed.orEmpty().associateBy { it.packageName } }

    fun save(next: List<String>) {
        pinned = next
        settings.shortcuts = next
    }

    LaunchedEffect(message) {
        if (message != null) {
            delay(2_500)
            message = null
        }
    }
    val cannotOpen = stringResource(R.string.shortcuts_cannot_open)

    PrismBackground {
        Column(Modifier.fillMaxSize().padding(40.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassButton(
                    text = stringResource(R.string.action_back),
                    onClick = onBack,
                    focusRequester = if (pinned.isEmpty()) firstFocus else null,
                )
                PrismText(text = stringResource(R.string.shortcuts_title), size = 30.sp, weight = FontWeight.Bold)
                message?.let { PrismText(text = it, size = 18.sp, color = PrismColors.Warn) }
            }
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                GlassPanel(Modifier.weight(1f).fillMaxHeight().depthFocus()) {
                    LazyColumn(
                        Modifier.fillMaxSize().confineFocus().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item { PrismText(text = stringResource(R.string.shortcuts_mine), size = 24.sp, weight = FontWeight.SemiBold) }
                        if (pinned.isEmpty()) {
                            item {
                                PrismText(
                                    text = stringResource(R.string.shortcuts_empty),
                                    size = 18.sp,
                                    color = PrismColors.TextSecondary,
                                )
                            }
                        }
                        items(pinned, key = { it }) { pkg ->
                            val app = byPackage[pkg]
                            var confirming by remember { mutableStateOf(false) }
                            LaunchedEffect(confirming) {
                                if (confirming) {
                                    delay(3_000)
                                    confirming = false
                                }
                            }
                            Row(
                                Modifier.fillMaxWidth().animateItem(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AppIcon(app)
                                GlassButton(
                                    text = app?.label ?: pkg,
                                    subtitle = if (app == null && installed != null) stringResource(R.string.shortcuts_missing) else null,
                                    onClick = { if (!InstalledApps.open(context, pkg)) message = cannotOpen },
                                    modifier = Modifier.weight(1f).focusMemory("app:$pkg"),
                                    focusRequester = if (pkg == pinned.first()) firstFocus else null,
                                )
                                GlassButton(
                                    text = stringResource(if (confirming) R.string.action_delete_confirm else R.string.shortcuts_remove),
                                    onClick = { if (confirming) save(Shortcuts.remove(pinned, pkg)) else confirming = true },
                                    danger = confirming,
                                )
                            }
                        }
                    }
                }
                GlassPanel(Modifier.weight(1f).fillMaxHeight().depthFocus()) {
                    val available = installed?.filter { it.packageName !in pinned }
                    LazyColumn(
                        Modifier.fillMaxSize().confineFocus().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item { PrismText(text = stringResource(R.string.shortcuts_add), size = 24.sp, weight = FontWeight.SemiBold) }
                        when {
                            available == null -> item {
                                PrismText(text = stringResource(R.string.shortcuts_loading), size = 18.sp, color = PrismColors.TextSecondary)
                            }
                            available.isEmpty() -> item {
                                PrismText(text = stringResource(R.string.shortcuts_none_left), size = 18.sp, color = PrismColors.TextSecondary)
                            }
                            else -> items(available, key = { it.packageName }) { app ->
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    AppIcon(app)
                                    GlassButton(
                                        text = app.label,
                                        subtitle = stringResource(R.string.shortcuts_press_to_add),
                                        onClick = { save(Shortcuts.add(pinned, app.packageName)) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppIcon(app: InstalledApp?) {
    val bitmap: ImageBitmap? = remember(app) { app?.icon?.let { runCatching { it.toBitmap(96, 96).asImageBitmap() }.getOrNull() } }
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(48.dp))
    } else {
        androidx.compose.foundation.layout.Spacer(Modifier.size(48.dp))
    }
}
