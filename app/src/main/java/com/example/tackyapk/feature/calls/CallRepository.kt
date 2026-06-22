package com.example.tackyapk.feature.calls

import com.example.tackyapk.TackyClient
import com.example.tackyapk.collectModule
import com.example.tackyapk.model.jsonArgs
import com.example.tackyapk.model.str
import com.google.gson.JsonObject as GsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where a 1:1 audio call is in its lifecycle, mirroring the daemon's `calls`
 *  events (see lib/taco/modules/calls.tcl). RINGING is caller-side only (the
 *  peer device is alerting); CONNECTING is callee-side after answering but before
 *  RTP (no daemon event - set locally on accept so the button row flips off the
 *  ringing screen immediately); ACTIVE means RTP is flowing. ENDED/FAILED are
 *  terminal - the overlay shows them briefly before [CallRepository] clears. */
enum class CallStatus { OUTGOING, INCOMING, RINGING, CONNECTING, ACTIVE, ENDED, FAILED }

/** One call's worth of state. [sid] is the daemon-assigned session id every
 *  control method (accept/reject/hangup) keys off. [incoming] flips the UI
 *  between the answer (accept/reject) and the cancel (hangup) affordance. */
data class Call(
    val sid: String,
    val acc: String,
    val peer: String,
    val incoming: Boolean,
    val status: CallStatus,
    val reason: String = "",
    // Wall-clock millis of the first <Active>; the overlay ticks a timer off it.
    val activeSince: Long? = null,
) {
    val terminal: Boolean get() = status == CallStatus.ENDED || status == CallStatus.FAILED
}

/**
 * Process-global view of the one in-flight audio call, the analogue of
 * [com.example.tackyapk.data.TackyRepository] for the `calls` module. The daemon
 * does all the WebRTC/Jingle work (libdatachannel + rtc-ma in the native child);
 * this just turns its event stream into a [Call] StateFlow and forwards the four
 * control methods, each carrying the call's `acc` so the daemon routes to the
 * right account's client.
 *
 * Calls are observed across every account (no `acc` filter on [collectModule]) so
 * an incoming call rings no matter which account's chat is open. We model a single
 * concurrent call - a second <Incoming>/<Outgoing> while one is live is ignored
 * (the user is busy), matching the daemon's one-call-at-a-time ergonomics.
 */
class CallRepository(
    private val client: TackyClient,
    private val scope: CoroutineScope,
) {
    private val _call = MutableStateFlow<Call?>(null)
    val call: StateFlow<Call?> = _call.asStateFlow()

    // Fired when the user answers from the incoming-call notification. The overlay
    // collects it and runs the same mic-gated accept its on-screen button does, so
    // a notification answer still asks for the mic before audio starts.
    private val _answerRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val answerRequests: SharedFlow<Unit> = _answerRequests.asSharedFlow()

    // Mic mute. The daemon has no per-call mute verb, so we mute by driving the
    // global capture volume to 0 (audio/setVolume) and restore it on unmute or
    // call-end. Capture volume is a persisted global pref, so a call that ends
    // while muted would otherwise leave the next call silent - hence the restore.
    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()
    // The user's real capture level, tracked from audio/<Volume> so unmute puts it
    // back where they had it rather than assuming full scale.
    private var preMuteVolume = 1.0

    /** Ask the UI to answer the ringing call (from the notification action). */
    fun requestAnswer() {
        _answerRequests.tryEmit(Unit)
    }

    // Holds the delayed auto-clear of a terminal call so a fresh call cancels it.
    private var clearJob: Job? = null

    init {
        scope.collectModule(client, "calls") { ev -> onEvent(ev) }
        scope.collectModule(client, "audio") { ev -> onAudioEvent(ev) }
    }

    /** Place an outgoing call. State arrives via the <Outgoing> event (which
     *  carries the daemon's sid), so this is fire-and-forget. */
    fun start(acc: String, jid: String) =
        client.notify("calls", "start", jsonArgs("acc" to acc, "to" to jid))

    /** Answer the ringing incoming call. Flip to CONNECTING right away (no daemon
     *  event marks "answered but no RTP yet") so the UI leaves the ringing screen. */
    fun accept() = active()?.let {
        client.notify("calls", "accept", jsonArgs("acc" to it.acc, "sid" to it.sid))
        _call.update { c ->
            if (c?.sid == it.sid && c.status == CallStatus.INCOMING) c.copy(status = CallStatus.CONNECTING) else c
        }
    }

    /** Toggle the mic. See [_muted]: mute is capture-volume 0, unmute restores. */
    fun toggleMute() {
        if (_muted.value) {
            _muted.value = false
            client.notify("audio", "setVolume", jsonArgs("kind" to "capture", "volume" to preMuteVolume))
        } else {
            _muted.value = true
            client.notify("audio", "setVolume", jsonArgs("kind" to "capture", "volume" to 0.0))
        }
    }

    /** Restore capture volume if we muted, so no call leaves the mic wedged at 0. */
    private fun clearMute() {
        if (!_muted.value) return
        _muted.value = false
        client.notify("audio", "setVolume", jsonArgs("kind" to "capture", "volume" to preMuteVolume))
    }

    /** Decline a ringing incoming call. */
    fun reject() = active()?.let {
        client.notify("calls", "reject", jsonArgs("acc" to it.acc, "sid" to it.sid))
        dismiss()
    }

    /** Cancel an outgoing call or hang up an active/answered one. */
    fun hangup() = active()?.let {
        client.notify("calls", "hangup", jsonArgs("acc" to it.acc, "sid" to it.sid))
        dismiss()
    }

    /** Clear the call from the UI immediately. Used for the terminal-banner close
     *  button and for any user-initiated dismiss (reject/hangup): the daemon does
     *  not emit <Ended> for every teardown path (e.g. a JMI <finish>), so the
     *  overlay must not depend on an event to close - otherwise a stale call can
     *  wedge the screen. A later <Ended> for the same sid is then a no-op. */
    fun dismiss() {
        clearMute()
        clearJob?.cancel()
        _call.value = null
    }

    private fun active(): Call? = _call.value?.takeIf { !it.terminal }

    private fun onEvent(ev: TackyClient.Event) {
        val o = ev.args as? GsonObject ?: return
        val acc = o.str("acc") ?: return
        val sid = o.str("sid") ?: return
        when (ev.event) {
            "<Outgoing>" ->
                establish(Call(sid, acc, o.str("to") ?: "", incoming = false, CallStatus.OUTGOING))
            "<Incoming>" ->
                establish(Call(sid, acc, o.str("from") ?: "", incoming = true, CallStatus.INCOMING))
            "<Ringing>" -> transition(sid, CallStatus.RINGING)
            "<Active>" -> transition(sid, CallStatus.ACTIVE)
            "<Ended>" -> terminate(sid, CallStatus.ENDED, "")
            "<Failed>" -> terminate(sid, CallStatus.FAILED, o.str("reason") ?: "")
            // <Warning> is non-fatal (device hot-swap hiccups); the call continues.
        }
    }

    private fun onAudioEvent(ev: TackyClient.Event) {
        if (ev.event != "<Volume>") return
        val o = ev.args as? GsonObject ?: return
        if (o.str("kind") != "capture") return
        val v = runCatching { o.get("volume").asDouble }.getOrNull() ?: return
        // Track the user's level, but skip the 0 we set ourselves when muting.
        if (!_muted.value) preMuteVolume = v
    }

    /** Adopt a new call unless one is already live with a different sid (busy). */
    private fun establish(call: Call) {
        val cur = _call.value
        if (cur != null && !cur.terminal && cur.sid != call.sid) return
        clearMute()
        clearJob?.cancel()
        _call.value = call
    }

    private fun transition(sid: String, status: CallStatus) =
        _call.update {
            if (it?.sid != sid || it.terminal) return@update it
            val since = if (status == CallStatus.ACTIVE && it.activeSince == null)
                System.currentTimeMillis() else it.activeSince
            it.copy(status = status, activeSince = since)
        }

    private fun terminate(sid: String, status: CallStatus, reason: String) {
        val cur = _call.value ?: return
        if (cur.sid != sid) return
        clearMute()
        _call.value = cur.copy(status = status, reason = reason)
        clearJob?.cancel()
        clearJob = scope.launch {
            delay(TERMINAL_LINGER_MS)
            if (_call.value?.sid == sid) _call.value = null
        }
    }

    companion object {
        // How long a hung-up/failed call stays on screen before auto-dismiss.
        private const val TERMINAL_LINGER_MS = 2500L
    }
}
