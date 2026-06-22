package com.example.tackyapk.ui

import androidx.compose.ui.graphics.Color
import com.example.tackyapk.model.ConnState

// Semantic status colours, shared so the connection dot, account state label, call
// buttons, and message send-status read from one palette instead of scattered hex.
val StatusGreen = Color(0xFF2E7D32)
val StatusRed = Color(0xFFC62828)
val StatusGrey = Color(0xFF9E9E9E)
val StatusAmber = Color(0xFFF9A825)

/** Connection-state colour: green connected, grey down/unknown, amber in-between. */
fun connStateColor(state: ConnState): Color = when (state) {
    ConnState.CONNECTED -> StatusGreen
    ConnState.DISCONNECTED, ConnState.UNKNOWN -> StatusGrey
    else -> StatusAmber
}
