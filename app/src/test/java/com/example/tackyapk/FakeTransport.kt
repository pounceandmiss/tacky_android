package com.example.tackyapk

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory [Transport] for JVM tests: feed frames in, inspect what was sent. */
class FakeTransport(initialState: String = "running") : Transport {
    private val _frames = MutableSharedFlow<String>(replay = 100, extraBufferCapacity = 100)
    override val frames: SharedFlow<String> = _frames.asSharedFlow()

    private val _state = MutableStateFlow(initialState)
    override val state: StateFlow<String> = _state.asStateFlow()

    /** Frames the client wrote, in order. */
    val sent = mutableListOf<String>()

    override fun send(json: String) { sent.add(json) }

    suspend fun emit(frame: String) = _frames.emit(frame)

    fun setState(s: String) { _state.value = s }
}
