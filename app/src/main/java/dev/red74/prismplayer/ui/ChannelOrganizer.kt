package dev.red74.prismplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.red74.prismplayer.R
import dev.red74.prismplayer.data.Channel
import dev.red74.prismplayer.data.ChannelPrefs
import dev.red74.prismplayer.data.FavoriteKey
import dev.red74.prismplayer.data.HiddenChannel

/** Changes the arrangement of the live channels (see [ChannelPrefs]); the view model saves it. */
typealias PrefsChange = ((ChannelPrefs) -> ChannelPrefs) -> Unit

/**
 * What can be done with one live channel of the Live tab: hide it, hide its group, put it in a group of the viewer's
 * own (or take it out) or start a new group with it. It takes the place of the list while it is open; Back returns to
 * the list with the cursor on the channel (or, if it went away, on the one nearby).
 */
@Composable
fun OrganizePanel(
    channel: Channel,
    prefs: ChannelPrefs,
    onChange: PrefsChange,
    /** After the channel or its group was hidden: the list shown below changes under the cursor. */
    onHidden: (groupHidden: Boolean) -> Unit,
    onManage: () -> Unit,
    onClose: () -> Unit,
) {
    val key = FavoriteKey.of(channel)
    val group = channel.group.orEmpty()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestWhenReady() }
    var name by remember { mutableStateOf("") }
    val create = {
        val clean = ChannelPrefs.cleanName(name)
        if (clean.isNotEmpty()) {
            onChange { it.withGroup(clean).withChannelInGroup(clean, key, true) }
            name = ""
            onClose()
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).confineFocus(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PrismText(text = stringResource(R.string.organize_title), size = 20.sp, color = PrismColors.TextSecondary)
        PrismText(text = channel.name, size = 28.sp, weight = FontWeight.Bold, maxLines = 2)
        GlassButton(
            text = stringResource(R.string.organize_hide_channel),
            onClick = {
                onChange { it.withHiddenChannel(HiddenChannel(key, group, channel.name), true) }
                onHidden(false)
            },
            focusRequester = first,
            modifier = Modifier.fillMaxWidth(),
        )
        if (group.isNotBlank()) {
            GlassButton(
                text = stringResource(R.string.organize_hide_group, group),
                onClick = {
                    onChange { it.withHiddenGroup(group, true) }
                    onHidden(true)
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        for (custom in prefs.groups) {
            val member = key in custom.keys
            GlassButton(
                text = stringResource(if (member) R.string.organize_remove_from else R.string.organize_add_to, ChannelPrefs.shownName(custom.name)),
                onClick = {
                    onChange { it.withChannelInGroup(custom.name, key, !member) }
                    onClose()
                },
                modifier = Modifier.fillMaxWidth(),
                emphasized = member,
            )
        }
        if (prefs.groups.size < ChannelPrefs.MAX_GROUPS) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
                GlassTextField(
                    value = name,
                    onValueChange = { name = it.take(ChannelPrefs.MAX_NAME) },
                    label = stringResource(R.string.organize_group_name),
                    modifier = Modifier.weight(1f),
                    imeAction = ImeAction.Done,
                    onDone = { create() },
                )
                GlassButton(text = stringResource(R.string.organize_create), onClick = { create() })
            }
        }
        GlassButton(text = stringResource(R.string.organize_manage), onClick = onManage, modifier = Modifier.fillMaxWidth())
        GlassButton(text = stringResource(R.string.action_back), onClick = onClose, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * Everything the viewer hid and the groups of their own, in one page, each with its button to show it again or to
 * delete the group. After every change the cursor goes back to the Back button (the item it was on is gone).
 */
@Composable
fun ManageChannelsScreen(prefs: ChannelPrefs, onChange: PrefsChange, onBack: () -> Unit) {
    val backFocus = remember { FocusRequester() }
    RequestFocusOnEnter(backFocus)
    var changes by remember { mutableStateOf(0) }
    LaunchedEffect(changes) { if (changes > 0) backFocus.requestWhenReady() }
    PrismBackground {
        GlassPanel(Modifier.fillMaxSize().padding(32.dp)) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).confineFocus(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlassButton(text = stringResource(R.string.action_back), onClick = onBack, focusRequester = backFocus)
                    PrismText(text = stringResource(R.string.organize_manage), size = 30.sp, weight = FontWeight.Bold, maxLines = 1)
                }
                if (prefs.isEmpty) PrismText(text = stringResource(R.string.manage_empty), size = 20.sp, color = PrismColors.TextSecondary)
                if (prefs.groups.isNotEmpty()) {
                    Heading(R.string.manage_my_groups)
                    for (group in prefs.groups) {
                        GlassButton(
                            text = stringResource(R.string.manage_delete) + ": " + ChannelPrefs.shownName(group.name),
                            subtitle = stringResource(R.string.manage_channels_count, group.keys.size),
                            onClick = { onChange { it.withoutGroup(group.name) }; changes++ },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (prefs.hiddenGroups.isNotEmpty()) {
                    Heading(R.string.manage_hidden_groups)
                    for (group in prefs.hiddenGroups.sorted()) {
                        GlassButton(
                            text = stringResource(R.string.manage_show) + ": " + group.ifBlank { stringResource(R.string.category_other) },
                            onClick = { onChange { it.withHiddenGroup(group, false) }; changes++ },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (prefs.hiddenChannels.isNotEmpty()) {
                    Heading(R.string.manage_hidden_channels)
                    for (hidden in prefs.hiddenChannels) {
                        GlassButton(
                            text = stringResource(R.string.manage_show) + ": " + hidden.name,
                            subtitle = hidden.group.ifBlank { null },
                            onClick = { onChange { it.withHiddenChannel(hidden, false) }; changes++ },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Heading(text: Int) {
    PrismText(text = stringResource(text), size = 22.sp, weight = FontWeight.SemiBold, color = PrismColors.TextSecondary, modifier = Modifier.padding(top = 10.dp))
}
