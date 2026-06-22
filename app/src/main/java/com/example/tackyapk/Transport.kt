package com.example.tackyapk

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The byte-transport [TackyClient] speaks over: incoming JSON frames, the
 * backend's lifecycle state, and a way to write a frame. TackydService is the
 * production implementation (a supervised child process); tests supply a fake so
 * the client's framing and acc-routing can run on the plain JVM.
 */
interface Transport {
    val frames: SharedFlow<String>
    val state: StateFlow<String>
    fun send(json: String)
}
