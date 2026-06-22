package com.example.tackyapk.feature.omemo

import com.example.tackyapk.TackyClient
import com.example.tackyapk.collectModule
import com.example.tackyapk.model.asBool
import com.example.tackyapk.model.decodeOrNull
import com.example.tackyapk.model.jsonArgs
import com.example.tackyapk.model.long
import com.example.tackyapk.model.str
import com.google.gson.JsonElement as GsonElement
import com.google.gson.JsonObject as GsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Per-chat OMEMO key state over [TackyClient]: the device trust list for one
 * (acc, jid) plus the account-wide blind-trust (BTBV) setting. Mirrors the other
 * repositories - queries via [TackyClient.request], mutations via
 * [TackyClient.notify], and live refresh from the omemo event stream.
 */
class OmemoRepository(
    private val client: TackyClient,
    private val scope: CoroutineScope,
) {
    private val _trustList = MutableStateFlow<List<TrustEntry>>(emptyList())
    val trustList: StateFlow<List<TrustEntry>> = _trustList.asStateFlow()

    private val _blindTrust = MutableStateFlow(false)
    val blindTrust: StateFlow<Boolean> = _blindTrust.asStateFlow()

    // Per-chat OMEMO toggle. There's no direct getter (IsEnabled is backend-
    // internal), so we pull the <Enabled> event for the current state; defaults on.
    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    @Volatile
    private var acc: String? = null

    @Volatile
    private var jid: String? = null

    init {
        scope.collectModule(client, "omemo", acc = { acc }) { ev ->
            when (ev.event) {
                "<TrustList>" -> onTrustList(ev.args)
                "<TrustChanged>" -> onTrustChanged(ev.args)
                "<BlindTrust>" -> onBlindTrust(ev.args)
                "<Enabled>" -> onEnabled(ev.args)
            }
        }
    }

    /** Point at a chat and pull its trust list, blind-trust, and enabled setting. */
    fun load(acc: String, jid: String) {
        this.acc = acc
        this.jid = jid
        _trustList.value = emptyList()
        scope.launch { runCatching { refresh(acc, jid) } }
    }

    private suspend fun refresh(acc: String, jid: String) {
        val rows: List<TrustEntry> =
            client.request("omemo", "trustList", jsonArgs("acc" to acc, "jid" to jid))
                .decodeOrNull() ?: emptyList()
        _trustList.value = rows
        _blindTrust.value = client.request("omemo", "blindTrust", jsonArgs("acc" to acc)).asBool()
        // pull re-emits <Enabled> with the current per-chat state (no direct getter).
        client.notify("omemo", "pull", jsonArgs("acc" to acc, "event" to "<Enabled>", "jid" to jid))
    }

    fun setTrust(acc: String, jid: String, device: Long, state: String) =
        client.notify(
            "omemo", "trust",
            jsonArgs("acc" to acc, "jid" to jid, "device" to device, "state" to state),
        )

    fun setBlindTrust(acc: String, value: Boolean) =
        client.notify("omemo", "setBlindTrust", jsonArgs("acc" to acc, "value" to if (value) 1 else 0))

    fun setEnabled(acc: String, jid: String, value: Boolean) =
        client.notify(
            "omemo", "setEnabled",
            jsonArgs("acc" to acc, "jid" to jid, "value" to if (value) 1 else 0),
        )

    private fun onTrustList(argsJson: GsonElement?) {
        val o = argsJson as? GsonObject ?: return
        // Only replace if this is the chat we're showing.
        if (o.str("jid") != jid) return
        _trustList.value = o.get("trustList").decodeOrNull() ?: emptyList()
    }

    private fun onTrustChanged(argsJson: GsonElement?) {
        val o = argsJson as? GsonObject ?: return
        if (o.str("jid") != jid) return
        val device = o.long("device") ?: return
        val state = o.str("state") ?: return
        _trustList.update { rows ->
            rows.map { if (it.device == device) it.copy(trust = state) else it }
        }
    }

    private fun onBlindTrust(argsJson: GsonElement?) {
        val o = argsJson as? GsonObject ?: return
        _blindTrust.value = o.get("value").asBool()
    }

    private fun onEnabled(argsJson: GsonElement?) {
        val o = argsJson as? GsonObject ?: return
        if (o.str("jid") != jid) return
        _enabled.value = o.get("value").asBool()
    }
}
