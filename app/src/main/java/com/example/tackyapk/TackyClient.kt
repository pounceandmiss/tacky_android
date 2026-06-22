package com.example.tackyapk

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Speaks the tackyd-json protocol on top of a [TackydService] transport.
 *
 * Wire forms (length framing is handled by the service):
 *   out  ["module","method",{args}]            notify (fire-and-forget)
 *        ["module","method",{args},token]       request
 *   in   ["event","module","<Event>",{args}]
 *        ["result",token,data]
 *        ["error",token,message]
 *
 * [request] suspends and returns the result payload, or throws [TackyError].
 * Args go on the wire un-dashed (the daemon prepends the dashes); event/result
 * payloads come back un-dashed. Deserialize payloads with
 * gson.fromJson(data, SomeType::class.java).
 */
class TackyClient(
    private val service: Transport,
    private val scope: CoroutineScope,
) {
    class TackyError(message: String) : Exception(message)

    data class Event(val module: String, val event: String, val args: JsonElement?)

    private val tokenSeq = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement?>>()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 256)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private val jobs = mutableListOf<Job>()

    /** Start consuming the transport. Spawns long-lived collectors in [scope]. */
    fun start() {
        jobs += scope.launch { service.frames.collect { route(it) } }
        jobs += scope.launch {
            // A respawned backend has a fresh token space, so requests in flight
            // when it died never get a reply. Fail them on the running->down edge.
            var connected = false
            service.state.collect { state ->
                val now = state == "running"
                if (connected && !now) failAll("backend disconnected")
                connected = now
            }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        failAll("client stopped")
    }

    /** Fire-and-forget: ["module","method",{args}]. */
    fun notify(module: String, method: String, args: JsonObject? = null) {
        service.send(frame(module, method, args, null).toString())
    }

    /**
     * Request: ["module","method",{args},token]. Suspends until the backend
     * replies, returning the result payload or throwing [TackyError]. Numeric
     * tokens so the daemon can echo them back as valid JSON. Cancelling the
     * caller cancels the wait and drops the pending entry.
     */
    suspend fun request(module: String, method: String, args: JsonObject? = null): JsonElement? {
        val token = tokenSeq.getAndIncrement()
        val deferred = CompletableDeferred<JsonElement?>()
        pending[token] = deferred
        return try {
            service.send(frame(module, method, args, token).toString())
            deferred.await()
        } finally {
            pending.remove(token)
        }
    }

    /** Fail and drop every pending request. complete/remove are atomic, so this
     *  races safely with a real reply arriving in [route]. */
    private fun failAll(reason: String) {
        pending.keys.toList().forEach { token ->
            pending.remove(token)?.completeExceptionally(TackyError(reason))
        }
    }

    private fun route(json: String) {
        val arr: JsonArray = try {
            JsonParser.parseString(json).asJsonArray
        } catch (e: RuntimeException) {
            Log.w(TAG, "unparseable frame: $json")
            return
        }
        when (str(at(arr, 0))) {
            "event" -> _events.tryEmit(Event(str(at(arr, 1)), str(at(arr, 2)), at(arr, 3)))
            "result" -> tokenKey(at(arr, 1))?.let { pending.remove(it)?.complete(at(arr, 2)) }
            "error" -> tokenKey(at(arr, 1))?.let {
                pending.remove(it)?.completeExceptionally(TackyError(str(at(arr, 2))))
            }
            else -> Log.w(TAG, "unknown frame tag: $json")
        }
    }

    private fun frame(module: String, method: String, args: JsonObject?, token: Long?): JsonArray =
        JsonArray().apply {
            add(module)
            add(method)
            add(args ?: JsonObject())
            if (token != null) add(token)
        }

    private fun at(a: JsonArray, i: Int): JsonElement? = if (i < a.size()) a[i] else null

    private fun str(e: JsonElement?): String =
        if (e != null && e.isJsonPrimitive) e.asString else ""

    private fun tokenKey(t: JsonElement?): Long? {
        if (t == null || t.isJsonNull || !t.isJsonPrimitive) return null
        val p = t.asJsonPrimitive
        return if (p.isNumber) p.asLong else p.asString.toLongOrNull()
    }

    companion object {
        private const val TAG = "TackyClient"
    }
}
