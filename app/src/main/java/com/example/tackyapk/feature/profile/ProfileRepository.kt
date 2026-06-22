package com.example.tackyapk.feature.profile

import com.example.tackyapk.TackyClient
import com.example.tackyapk.collectModule
import com.example.tackyapk.feature.omemo.TrustEntry
import com.example.tackyapk.model.asBool
import com.example.tackyapk.model.asLongOr
import com.example.tackyapk.model.asStringOr
import com.example.tackyapk.model.decodeOrNull
import com.example.tackyapk.model.jsonArgs
import com.example.tackyapk.model.long
import com.example.tackyapk.model.str
import com.example.tackyapk.reloadOnRunning
import com.google.gson.JsonElement as GsonElement
import com.google.gson.JsonObject as GsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Typed own-account / profile state over [TackyClient]: the display name, this
 * device's OMEMO identity (fingerprint + device id), its sibling devices, and the
 * account-wide blind-trust (BTBV) setting.
 *
 * Protocol note: the reads reply via a token so they use [TackyClient.request];
 * the writes (nick set, omemo setBlindTrust) send no reply, so they use
 * [TackyClient.notify] and we observe blind-trust changes via the omemo
 * <BlindTrust> event. trustList is queried for the own bare jid (= acc); the row
 * whose device == [deviceId] is this device.
 */
class ProfileRepository(
    private val client: TackyClient,
    private val state: StateFlow<String>,
    private val scope: CoroutineScope,
) {
    private val _displayName = MutableStateFlow("")
    val displayName: StateFlow<String> = _displayName.asStateFlow()

    private val _ownFingerprint = MutableStateFlow("")
    val ownFingerprint: StateFlow<String> = _ownFingerprint.asStateFlow()

    private val _deviceId = MutableStateFlow(0L)
    val deviceId: StateFlow<Long> = _deviceId.asStateFlow()

    private val _ownDevices = MutableStateFlow<List<TrustEntry>>(emptyList())
    val ownDevices: StateFlow<List<TrustEntry>> = _ownDevices.asStateFlow()

    private val _blindTrust = MutableStateFlow(false)
    val blindTrust: StateFlow<Boolean> = _blindTrust.asStateFlow()

    /** The account whose profile is shown. Null until [load] picks one. */
    @Volatile
    var acc: String? = null
        private set

    fun start() {
        scope.reloadOnRunning(state) { reload() }
        scope.collectModule(client, "omemo", acc = { acc }) { ev ->
            when (ev.event) {
                "<BlindTrust>" -> onBlindTrust(ev.args)
                "<TrustList>" -> onTrustList(ev.args)
                "<TrustChanged>" -> onTrustChanged(ev.args)
            }
        }
    }

    fun load(acc: String) {
        this.acc = acc
        scope.launch { runCatching { reload() } }
    }

    private suspend fun reload() {
        val acc = acc ?: return
        val a = jsonArgs("acc" to acc)
        val ownJid = jsonArgs("acc" to acc, "jid" to acc)
        _displayName.value = client.request("nick", "get", ownJid).asStringOr()
        _ownFingerprint.value = client.request("omemo", "own_fingerprint", a).asStringOr()
        _deviceId.value = client.request("omemo", "device_id", a).asLongOr()
        _blindTrust.value = client.request("omemo", "blindTrust", a).asBool()
        _ownDevices.value = client.request("omemo", "trustList", ownJid).decodeOrNull() ?: emptyList()
    }

    fun setDisplayName(acc: String, name: String) =
        client.notify("nick", "set", jsonArgs("acc" to acc, "nick" to name))

    fun setBlindTrust(acc: String, value: Boolean) =
        client.notify("omemo", "setBlindTrust", jsonArgs("acc" to acc, "value" to if (value) 1 else 0))

    /** Trust/distrust one of this account's *other* devices (jid = own bare jid). */
    fun setOwnDeviceTrust(acc: String, device: Long, state: String) =
        client.notify(
            "omemo", "trust",
            jsonArgs("acc" to acc, "jid" to acc, "device" to device, "state" to state),
        )

    private fun onBlindTrust(argsJson: GsonElement?) {
        val o = argsJson as? GsonObject ?: return
        _blindTrust.value = o.get("value").asBool()
    }

    // The own-device list is the trust list for our own bare jid; the backend
    // already excludes this device. EmitTrustList re-broadcasts the whole list on
    // any change, so a full replace keeps it live.
    private fun onTrustList(argsJson: GsonElement?) {
        val o = argsJson as? GsonObject ?: return
        if (o.str("jid") != acc) return
        _ownDevices.value = o.get("trustList").decodeOrNull() ?: emptyList()
    }

    private fun onTrustChanged(argsJson: GsonElement?) {
        val o = argsJson as? GsonObject ?: return
        if (o.str("jid") != acc) return
        val device = o.long("device") ?: return
        val state = o.str("state") ?: return
        _ownDevices.value = _ownDevices.value.map {
            if (it.device == device) it.copy(trust = state) else it
        }
    }
}
