package com.example.tackyapk.feature.calls

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.tackyapk.AvatarCache
import com.example.tackyapk.rememberAvatar
import com.example.tackyapk.ui.Avatar
import com.example.tackyapk.ui.StatusAmber
import com.example.tackyapk.ui.StatusGreen
import com.example.tackyapk.ui.StatusRed
import com.example.tackyapk.R
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * The single full-screen call surface, hosted at the app root so an incoming call
 * rings over whatever screen is showing. Renders nothing when there's no call;
 * otherwise it lays out like Conversations' RtpSessionActivity - peer name + JID
 * up top, a large contact avatar centred with the status/duration beneath it, and
 * the controls for the current lifecycle phase pinned to the bottom (answer/decline
 * while ringing in, mute/hang-up/speaker once connected). All state comes from
 * [CallRepository]; the daemon does the actual audio.
 */
@Composable
fun CallOverlay(calls: CallRepository, audio: CallAudioManager, avatars: AvatarCache) {
    val call by calls.call.collectAsStateWithLifecycle()
    // Answering needs the mic; ask for it on tap if not already granted. Defined
    // before the early return so the notification-answer collector below can run
    // regardless of whether a call is currently on screen.
    val answer = rememberMicGatedAction(calls::accept)
    // A "Answer" tap on the incoming-call notification routes here so it goes
    // through the same mic gate as the on-screen button.
    LaunchedEffect(Unit) {
        calls.answerRequests.collect { answer() }
    }
    val c = call ?: return

    val bare = c.peer.substringBefore('/')
    val name = bare.substringBefore('@').ifEmpty { "Unknown" }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Header: friendly name + the bare JID under it, the RtpSession toolbar.
            Spacer(Modifier.height(24.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (bare.contains('@')) {
                Text(
                    text = bare,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Centre: the large contact photo with status/duration beneath it.
            Spacer(Modifier.weight(1f))
            Avatar(rememberAvatar(avatars, c.acc, bare), size = 144.dp)
            Spacer(Modifier.height(24.dp))
            if (c.status == CallStatus.ACTIVE && c.activeSince != null) {
                CallDuration(c.activeSince)
            } else {
                Text(
                    text = statusLabel(c),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.weight(1f))

            // Controls, pinned to the bottom like the FAB row in RtpSessionActivity.
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                when {
                    // Ringing inbound: decline (left) + answer (right).
                    c.incoming && c.status == CallStatus.INCOMING -> {
                        CallButton("Decline", painterResource(R.drawable.ic_call_end), StatusRed, calls::reject)
                        CallButton("Answer", painterResource(R.drawable.ic_call), StatusGreen, answer)
                    }
                    // Connecting/live/dialing: mute + hang-up + speaker toggle.
                    !c.terminal -> {
                        val muted by calls.muted.collectAsStateWithLifecycle()
                        val speaker by audio.speakerphone.collectAsStateWithLifecycle()
                        CallButton(
                            if (muted) "Unmute" else "Mute",
                            if (muted) painterResource(R.drawable.ic_mic_off) else painterResource(R.drawable.ic_mic),
                            if (muted) StatusAmber else SpeakerOff,
                            calls::toggleMute)
                        CallButton("Hang up", painterResource(R.drawable.ic_call_end), StatusRed, calls::hangup)
                        CallButton(
                            if (speaker) "Speaker on" else "Speaker",
                            painterResource(R.drawable.ic_volume_up),
                            if (speaker) StatusGreen else SpeakerOff,
                            audio::toggleSpeakerphone)
                    }
                    // Terminal: a brief banner that also auto-clears; let the user
                    // dismiss early.
                    else -> {
                        CallButton("Close", painterResource(R.drawable.ic_call_end), StatusRed, calls::dismiss)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = "using ${c.acc}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val SpeakerOff = Color(0xFF555555)

private fun statusLabel(c: Call): String = when (c.status) {
    CallStatus.OUTGOING -> "Calling…"
    CallStatus.INCOMING -> "Incoming call"
    CallStatus.RINGING -> "Ringing…"
    CallStatus.CONNECTING -> "Connecting…"
    CallStatus.ACTIVE -> "In call"
    CallStatus.ENDED -> "Call ended"
    CallStatus.FAILED -> "Call failed" + if (c.reason.isNotEmpty()) ": ${c.reason}" else ""
}

/** Ticks an mm:ss timer off [since] (the first <Active>) once a second. */
@Composable
private fun CallDuration(since: Long) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val secs = ((now - since).coerceAtLeast(0L) / 1000L).toInt()
    Text(
        text = String.format(Locale.US, "%d:%02d", secs / 60, secs % 60),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CallButton(label: String, icon: Painter, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(color)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = Color.White)
        }
        Spacer(Modifier.height(8.dp))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
