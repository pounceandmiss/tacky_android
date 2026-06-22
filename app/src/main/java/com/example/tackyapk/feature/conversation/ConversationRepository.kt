package com.example.tackyapk.feature.conversation

import com.example.tackyapk.TackyClient
import com.example.tackyapk.collectModule
import com.example.tackyapk.model.decodeOrNull
import com.example.tackyapk.model.jsonArgs
import com.example.tackyapk.model.str
import com.example.tackyapk.model.FlexibleLongSerializer
import com.google.gson.JsonElement as GsonElement
import com.google.gson.JsonObject as GsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * A bounded sliding window over one (acc, jid) chat, following doc/chat.md's
 * contract. [messages] holds a contiguous run sorted oldest-first; the window
 * grows by paging older ([loadOlder]) or newer ([loadNewer]) and is capped at
 * [maxWindow] by culling the far end. [atTail] tracks whether the window still
 * holds the conversation tail - live <Received>/<Sent> events only insert while
 * at tail (otherwise they'd advance the cursor past an unfetched range and leave
 * a gap), and the screen offers a jump-to-bottom when it's false.
 *
 * Wire args go un-dashed (the daemon adds the dashes); every call carries `acc`
 * so the daemon picks the right account's client.
 */
class ConversationRepository(
    private val client: TackyClient,
    private val scope: CoroutineScope,
    private val pageLimit: Int = 50,
    private val maxWindow: Int = 200,
) {
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _atTail = MutableStateFlow(true)
    val atTail: StateFlow<Boolean> = _atTail.asStateFlow()

    // The timestamp of a message the screen should scroll to and flash (e.g. a
    // reply target after a jump), or null. The screen clears it once shown.
    private val _highlight = MutableStateFlow<Long?>(null)
    val highlight: StateFlow<Long?> = _highlight.asStateFlow()

    private var acc: String = ""
    private var jid: String = ""

    private var initialJob: Job? = null
    private var olderJob: Job? = null
    private var newerJob: Job? = null
    private var olderExhausted = false

    init {
        scope.collectModule(client, "message") { ev ->
            when (ev.event) {
                "<Received>", "<Sent>" -> onMessage(ev.args)
                "<Patch>" -> onPatch(ev.args)
            }
        }
    }

    /** Point at a chat and seed it with the newest page (which contains the tail). */
    fun load(acc: String, jid: String) {
        this.acc = acc
        this.jid = jid
        cancelPaging()
        olderExhausted = false
        _atTail.value = true
        _messages.value = emptyList()
        android.util.Log.w("ConvDbg", "load acc=$acc jid=$jid repo=${System.identityHashCode(this)}")
        initialJob = scope.launch {
            val rows = history(before = null, after = null)
            android.util.Log.w("ConvDbg", "seed rows=${rows.size} jid=$jid repo=${System.identityHashCode(this@ConversationRepository)}")
            upsertAll(rows)
        }
    }

    /** Re-seed from the tail (a "scroll to bottom" affordance when not at tail). */
    fun jumpToBottom() = load(acc, jid)

    /**
     * Jump to the message a reply quotes (XEP-0461). The backend resolves the
     * reply's origin-id to its stored target and returns a slice centred on it
     * plus the target's timestamp ([GotoResult.anchor]); we swap the window to
     * that slice and flag the anchor so the screen scrolls to and flashes it. A
     * target the local store doesn't hold comes back empty (no remote fetch) -
     * we leave the view untouched. Mirrors the desktop's OnReplyJump/goto path.
     */
    fun gotoReply(replyId: String, replyTo: String) {
        if (replyId.isEmpty()) return
        cancelPaging()
        initialJob = scope.launch {
            val a = jsonArgs(
                "acc" to acc,
                "chat" to jid,
                "reply_id" to replyId,
                "reply_to" to replyTo,
            ).apply { addProperty("limit", pageLimit) }
            val result = runCatching {
                client.request("message", "gotoReply", a).decodeOrNull<GotoResult>()
            }.getOrNull() ?: return@launch
            if (result.anchor == 0L || result.messages.isEmpty()) return@launch
            olderExhausted = false
            _messages.value = result.messages.sortedBy { it.timestamp }
            _atTail.value = isTail(result.messages)
            _highlight.value = result.anchor
        }
    }

    /** The screen calls this once it has scrolled to and flashed the highlight. */
    fun clearHighlight() {
        _highlight.value = null
    }

    /** Whether a slice reaches the conversation tail (so live inserts may resume). */
    private suspend fun isTail(rows: List<Message>): Boolean {
        val newest = rows.maxOfOrNull { it.timestamp } ?: return true
        val max = maxTimestamp() ?: return false
        return newest >= max
    }

    fun loadOlder() {
        if (olderExhausted || olderJob?.isActive == true) return
        val oldest = _messages.value.firstOrNull()?.timestamp ?: return
        olderJob = scope.launch {
            val rows = history(before = oldest, after = null)
            if (!upsertAll(rows)) {
                olderExhausted = true
                return@launch
            }
            cullNewerIfNeeded()
        }
    }

    fun loadNewer() {
        if (_atTail.value || newerJob?.isActive == true) return
        val newest = _messages.value.lastOrNull()?.timestamp ?: return
        newerJob = scope.launch {
            upsertAll(history(before = null, after = newest))
            cullOlderIfNeeded()
            val max = maxTimestamp()
            if (max != null && (_messages.value.lastOrNull()?.timestamp ?: Long.MIN_VALUE) >= max) {
                _atTail.value = true
            }
        }
    }

    /** Fire-and-forget send; the row returns as a <Sent> event we then display. */
    fun send(acc: String, jid: String, body: String) =
        client.notify("message", "send", jsonArgs("acc" to acc, "chat_jid" to jid, "body" to body))

    private suspend fun history(before: Long?, after: Long?): List<Message> {
        val a = jsonArgs("acc" to acc, "chat" to jid).apply {
            addProperty("limit", pageLimit)
            if (before != null) addProperty("before", before)
            if (after != null) addProperty("after", after)
        }
        return runCatching {
            client.request("message", "history", a).decodeOrNull<List<Message>>()
        }.onFailure {
            android.util.Log.w("ConvDbg", "history FAILED jid=$jid before=$before after=$after err=$it")
        }.getOrNull() ?: emptyList()
    }

    private suspend fun maxTimestamp(): Long? = runCatching {
        client.request("chats", "maxTimestamp", jsonArgs("acc" to acc, "chat" to jid))
            ?.takeIf { it.isJsonPrimitive }?.asLong
    }.getOrNull()

    private fun onMessage(argsJson: GsonElement?) {
        val o = argsJson as? GsonObject ?: return
        if (!belongsHere(o)) return
        if (!_atTail.value) return // gate: inserting off-tail would leave a gap
        val msg = o.get("message").decodeOrNull<Message>() ?: return
        upsert(msg)
        cullOlderIfNeeded()
    }

    private fun onPatch(argsJson: GsonElement?) {
        val o = argsJson as? GsonObject ?: return
        if (!belongsHere(o)) return
        val patches = o.get("messages").decodeOrNull<List<Message>>() ?: return
        patches.forEach { applyPatch(it) }
    }

    /** A <Patch> carries the target timestamp plus only the changed status fields
     *  (and newtimestamp on a move). Merge in place if the target is displayed;
     *  drop otherwise - inserting a non-displayed row would break contiguity. */
    private fun applyPatch(patch: Message) = _messages.update { list ->
        val idx = list.indexOfFirst { it.timestamp == patch.timestamp }
        if (idx < 0) return@update list
        val moved = patch.newtimestamp != 0L && patch.newtimestamp != patch.timestamp
        val updated = list[idx].copy(
            timestamp = if (moved) patch.newtimestamp else list[idx].timestamp,
            server_status = patch.server_status,
            fail_reason = patch.fail_reason,
        )
        (list.filterIndexed { i, _ -> i != idx } + updated).sortedBy { it.timestamp }
    }

    /** Loaded older: keep the oldest [maxWindow], drop the newest end (the tail
     *  the user scrolled away from) and cancel any newer fetch chasing it. */
    private fun cullNewerIfNeeded() {
        val list = _messages.value
        if (list.size <= maxWindow) return
        _messages.value = list.subList(0, maxWindow).toList()
        _atTail.value = false
        newerJob?.cancel()
    }

    /** Loaded newer (or a live insert): keep the newest [maxWindow], drop the
     *  oldest end and let older paging refetch it. */
    private fun cullOlderIfNeeded() {
        val list = _messages.value
        if (list.size <= maxWindow) return
        _messages.value = list.subList(list.size - maxWindow, list.size).toList()
        olderJob?.cancel()
        olderExhausted = false
    }

    private fun cancelPaging() {
        initialJob?.cancel()
        olderJob?.cancel()
        newerJob?.cancel()
    }

    private fun belongsHere(o: GsonObject): Boolean = o.str("acc") == acc && o.str("jid") == jid

    /** The `message gotoReply` reply: a slice of messages plus the target's
     *  timestamp ([anchor], "" / 0 when the target wasn't found locally). */
    @Serializable
    private data class GotoResult(
        val messages: List<Message> = emptyList(),
        @Serializable(with = FlexibleLongSerializer::class)
        val anchor: Long = 0,
    )

    private fun upsert(msg: Message) = _messages.update { list ->
        (list.filterNot { it.timestamp == msg.timestamp } + msg).sortedBy { it.timestamp }
    }

    /** Merge a batch by timestamp identity; returns whether any new row landed
     *  (an all-duplicate or empty batch means there's nothing more on that side). */
    private fun upsertAll(rows: List<Message>): Boolean {
        if (rows.isEmpty()) return false
        val existing = _messages.value.mapTo(HashSet()) { it.timestamp }
        val added = rows.any { it.timestamp !in existing }
        _messages.update { list ->
            val byTs = LinkedHashMap<Long, Message>()
            list.forEach { byTs[it.timestamp] = it }
            rows.forEach { byTs[it.timestamp] = it }
            byTs.values.sortedBy { it.timestamp }
        }
        return added
    }
}
