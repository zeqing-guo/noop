package com.noop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.ai.CoachLanServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The coach page's "Computer access" card: starts and stops a [CoachLanServer] window and shows where
 * it is, how long it has left and which computers were allowed. The countdown reads the window's one
 * deadline ([CoachLanServer.State.expiresAtMillis]) against one ticking clock; the foreground
 * notification counts down from the same deadline.
 *
 * Approving a computer is NOT here but in [CoachLanApprovalDialog], which AppRoot hosts, so a request
 * is answered wherever in the app the wearer happens to be.
 */
@Composable
internal fun CoachLanCard() {
    val context = LocalContext.current
    val state by CoachLanServer.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var confirmStart by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.running, state.expiresAtMillis) {
        while (state.running) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    NoopCard(padding = Metrics.space14) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space10)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Computer,
                    contentDescription = null,
                    tint = if (state.running) Palette.accent else Palette.textSecondary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(Metrics.space8))
                Text(
                    stringResource(R.string.coach_lan_title),
                    style = NoopType.headline,
                    color = Palette.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (state.running) {
                    StatePill(
                        title = stringResource(R.string.coach_lan_remaining, formatRemaining(state.expiresAtMillis - now)),
                        tone = StrandTone.Accent,
                        showsDot = true,
                    )
                }
            }

            if (!state.running) {
                Text(stringResource(R.string.coach_lan_body), style = NoopType.subhead, color = Palette.textSecondary)
                when (state.lastError) {
                    CoachLanServer.StartError.NO_WIFI ->
                        Text(stringResource(R.string.coach_lan_no_wifi), style = NoopType.caption, color = Palette.statusCritical)
                    CoachLanServer.StartError.BIND_FAILED ->
                        Text(stringResource(R.string.coach_lan_bind_failed), style = NoopType.caption, color = Palette.statusCritical)
                    null -> Unit
                }
                CoachPrimaryButton(
                    label = stringResource(R.string.coach_lan_start),
                    enabled = !starting,
                    onClick = { confirmStart = true },
                )
            } else {
                val url = state.url.orEmpty()
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
                    Text(stringResource(R.string.coach_lan_address), style = NoopType.caption, color = Palette.textTertiary)
                    Text(url, style = NoopType.mono, color = Palette.textPrimary)
                }
                val copyInteraction = remember { MutableInteractionSource() }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(Metrics.cornerPill))
                        .liquidPress(copyInteraction)
                        .clickable(interactionSource = copyInteraction, indication = null) {
                            clipboard.setText(AnnotatedString(CoachLanServer.agentInstructions(url)))
                        }
                        .padding(vertical = Metrics.space4),
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, tint = Palette.accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(Metrics.space6))
                    Text(stringResource(R.string.coach_lan_copy), style = NoopType.subhead, color = Palette.accent)
                }
                if (state.approved.isEmpty()) {
                    Text(stringResource(R.string.coach_lan_waiting), style = NoopType.subhead, color = Palette.textSecondary)
                } else {
                    state.approved.forEach { c ->
                        Text(
                            stringResource(R.string.coach_lan_allowed, c.client, c.remoteAddress),
                            style = NoopType.subhead,
                            color = Palette.textPrimary,
                        )
                    }
                }
                Text(
                    pluralStringResource(R.plurals.coach_lan_queries, state.queriesServed, state.queriesServed),
                    style = NoopType.caption,
                    color = Palette.textTertiary,
                )
                val stopInteraction = remember { MutableInteractionSource() }
                Text(
                    stringResource(R.string.coach_lan_stop),
                    style = NoopType.headline,
                    color = Palette.statusCritical,
                    modifier = Modifier
                        .clip(RoundedCornerShape(Metrics.cornerPill))
                        .liquidPress(stopInteraction)
                        .clickable(interactionSource = stopInteraction, indication = null) {
                            CoachLanServer.stop(context)
                        }
                        .padding(vertical = Metrics.space6),
                )
            }
        }
    }

    if (confirmStart) {
        AlertDialog(
            onDismissRequest = { confirmStart = false },
            title = { Text(stringResource(R.string.coach_lan_confirm_title)) },
            text = { Text(stringResource(R.string.coach_lan_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmStart = false
                    starting = true
                    scope.launch {
                        withContext(Dispatchers.IO) { CoachLanServer.start(context) }
                        starting = false
                    }
                }) { Text(stringResource(R.string.coach_lan_confirm_start)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmStart = false }) {
                    Text(stringResource(R.string.l10n_coach_screen_cancel_77dfd213))
                }
            },
        )
    }
}

/**
 * Asks the wearer about the oldest waiting computer. Hosted once, by AppRoot, so it shows on any
 * screen; a request is never answered by default, and closing the dialog without a choice denies it.
 */
@Composable
internal fun CoachLanApprovalDialog() {
    val state by CoachLanServer.state.collectAsStateWithLifecycle()
    val request = state.pending.firstOrNull() ?: return
    AlertDialog(
        onDismissRequest = { CoachLanServer.deny(request.id) },
        title = { Text(stringResource(R.string.coach_lan_request_title)) },
        text = { Text(stringResource(R.string.coach_lan_request_body, request.client, request.remoteAddress)) },
        confirmButton = {
            TextButton(onClick = { CoachLanServer.approve(request.id) }) {
                Text(stringResource(R.string.coach_lan_allow))
            }
        },
        dismissButton = {
            TextButton(onClick = { CoachLanServer.deny(request.id) }) {
                Text(stringResource(R.string.coach_lan_deny), color = Palette.statusCritical)
            }
        },
    )
}

/** `m:ss` for the countdown pill; never negative. */
internal fun formatRemaining(millis: Long): String {
    val total = (millis.coerceAtLeast(0) + 999) / 1000
    return "%d:%02d".format(java.util.Locale.ROOT, total / 60, total % 60)
}
