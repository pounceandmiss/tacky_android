package com.example.tackyapk.data

import com.example.tackyapk.TackyClient
import com.example.tackyapk.model.Account
import com.example.tackyapk.model.ConnState
import com.example.tackyapk.model.decodeOrNull
import com.example.tackyapk.model.jsonArgs
import com.example.tackyapk.model.str
import com.example.tackyapk.reloadOnRunning
import com.google.gson.JsonElement as GsonElement
import com.google.gson.JsonObject as GsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Typed domain state over [TackyClient], the layer ViewModels consume. Turns the
 * backend's request/reply + event stream into StateFlows.
 *
 * Protocol note: the `tackymethod`s (list/get/exists) reply via a token, so they
 * use [TackyClient.request]; the plain methods (add/enable/disable/remove) send no
 * reply, so they use [TackyClient.notify] and we observe their effect via events.
 */
class TackyRepository(
    private val client: TackyClient,
    private val state: StateFlow<String>,
    private val scope: CoroutineScope,
) {
    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    private val _connStates = MutableStateFlow<Map<String, ConnState>>(emptyMap())
    val connStates: StateFlow<Map<String, ConnState>> = _connStates.asStateFlow()

    fun start() {
        scope.reloadOnRunning(state) { refreshAccounts() }
        scope.launch {
            client.events.collect { ev ->
                when (ev.module) {
                    "account" -> runCatching { refreshAccounts() }
                    "conn" -> if (ev.event == "<State>") onConnState(ev.args)
                }
            }
        }
    }

    suspend fun refreshAccounts() {
        val jids: List<String> = client.request("account", "list").decodeOrNull() ?: emptyList()
        _accounts.value = jids.mapNotNull { jid ->
            client.request("account", "get", jsonArgs("acc" to jid)).decodeOrNull<Account>()
        }
    }

    fun addAccount(jid: String, password: String) =
        client.notify("account", "add", jsonArgs("acc" to jid, "password" to password))

    fun enable(jid: String) = client.notify("account", "enable", jsonArgs("acc" to jid))

    fun disable(jid: String) = client.notify("account", "disable", jsonArgs("acc" to jid))

    fun remove(jid: String) = client.notify("account", "remove", jsonArgs("acc" to jid))

    private fun onConnState(argsJson: GsonElement?) {
        val o = argsJson as? GsonObject ?: return
        val acc = o.str("acc") ?: return
        _connStates.update { it + (acc to ConnState.from(o.str("state"))) }
    }
}
