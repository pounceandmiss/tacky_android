package com.example.tackyapk.feature.conversation

import com.example.tackyapk.TackyClient
import com.example.tackyapk.model.decodeOrNull
import com.example.tackyapk.model.jsonArgs
import com.example.tackyapk.model.str
import com.google.gson.JsonElement as GsonElement
import com.google.gson.JsonObject as GsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The open chat's sender display names, mirroring taco_author. [names] maps a
 * stored from_jid to its display name (MUC: room@host/nick; 1:1: bare from_jid).
 * [load] seeds from `author/get` and keeps the map live by collecting
 * `author/<Changed>` for this account and chat.
 */
class AuthorCache(
    private val client: TackyClient,
    private val scope: CoroutineScope,
) {
    private val _names = MutableStateFlow<Map<String, String>>(emptyMap())
    val names: StateFlow<Map<String, String>> = _names.asStateFlow()

    private var acc: String = ""
    private var chat: String = ""
    private var listenJob: Job? = null

    fun load(acc: String, chat: String) {
        this.acc = acc
        this.chat = chat
        listenJob?.cancel()
        _names.value = emptyMap()
        listenJob = scope.launch {
            _names.value = fetch()
            client.events.collect { ev ->
                if (ev.module != "author" || ev.event != "<Changed>") return@collect
                onChanged(ev.args)
            }
        }
    }

    private suspend fun fetch(): Map<String, String> = runCatching {
        client.request("author", "get", jsonArgs("acc" to acc, "chat" to chat))
            .decodeOrNull<Map<String, String>>()
    }.getOrNull() ?: emptyMap()

    private fun onChanged(argsJson: GsonElement?) {
        val o = argsJson as? GsonObject ?: return
        if (o.str("acc") != acc || o.str("chat") != chat) return
        val from = o.str("from") ?: return
        val name = o.str("name") ?: return
        _names.update { it + (from to name) }
    }
}
