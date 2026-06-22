package com.example.tackyapk.model

import kotlinx.serialization.Serializable

/** An XMPP account as the backend's `account get` returns it (password omitted). */
@Serializable
data class Account(
    val jid: String = "",
    val username: String = "",
    val domain: String = "",
    val enabled: Boolean = false,
)

/** Live connection state from `conn/<State>` events. */
enum class ConnState(val label: String) {
    DISCONNECTED("disconnected"),
    CONNECTING("connecting"),
    WAITING("waiting"),
    AUTHENTICATING("authenticating"),
    BINDING("binding"),
    CONNECTED("connected"),
    UNKNOWN("unknown");

    val isBusy: Boolean
        get() = this == CONNECTING || this == WAITING || this == AUTHENTICATING || this == BINDING

    companion object {
        fun from(s: String?): ConnState = entries.firstOrNull { it.label == s } ?: UNKNOWN
    }
}
