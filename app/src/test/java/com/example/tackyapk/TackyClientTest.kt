package com.example.tackyapk

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "talker": framing, token correlation, and event decode over [Transport]. */
@OptIn(ExperimentalCoroutinesApi::class)
class TackyClientTest {

    private fun lastFrame(t: FakeTransport) = JsonParser.parseString(t.sent.last()).asJsonArray

    @Test
    fun requestCorrelatesResultByToken() = runTest(UnconfinedTestDispatcher()) {
        val t = FakeTransport()
        val client = TackyClient(t, backgroundScope).apply { start() }

        val reply = async { client.request("account", "list") }
        val frame = lastFrame(t)
        assertEquals("account", frame[0].asString)
        assertEquals("list", frame[1].asString)
        val token = frame[3].asLong

        t.emit("""["result",$token,["a@b","c@d"]]""")
        assertEquals("""["a@b","c@d"]""", reply.await().toString())
    }

    @Test
    fun requestThrowsOnErrorFrame() = runTest(UnconfinedTestDispatcher()) {
        val t = FakeTransport()
        val client = TackyClient(t, backgroundScope).apply { start() }

        val reply = async { runCatching { client.request("x", "y") } }
        val token = lastFrame(t)[3].asLong
        t.emit("""["error",$token,"boom"]""")

        val r = reply.await()
        assertTrue(r.isFailure)
        assertEquals("boom", (r.exceptionOrNull() as TackyClient.TackyError).message)
    }

    @Test
    fun notifyHasNoToken() = runTest(UnconfinedTestDispatcher()) {
        val t = FakeTransport()
        val client = TackyClient(t, backgroundScope).apply { start() }

        client.notify("account", "add", JsonObject().apply { addProperty("acc", "a@b") })
        val frame = lastFrame(t)
        assertEquals(3, frame.size())
        assertEquals("account", frame[0].asString)
        assertEquals("add", frame[1].asString)
    }

    @Test
    fun eventFramesBecomeEvents() = runTest(UnconfinedTestDispatcher()) {
        val t = FakeTransport()
        val client = TackyClient(t, backgroundScope).apply { start() }

        val events = mutableListOf<TackyClient.Event>()
        backgroundScope.launch { client.events.collect { events.add(it) } }

        t.emit("""["event","conn","<State>",{"acc":"a@b","state":"connected"}]""")

        assertEquals(1, events.size)
        assertEquals("conn", events[0].module)
        assertEquals("<State>", events[0].event)
        assertEquals("a@b", events[0].args!!.asJsonObject.get("acc").asString)
    }

    @Test
    fun pendingRequestFailsWhenBackendGoesDown() = runTest(UnconfinedTestDispatcher()) {
        val t = FakeTransport(initialState = "running")
        val client = TackyClient(t, backgroundScope).apply { start() }

        val reply = async { runCatching { client.request("x", "y") } }
        t.setState("stopped") // running -> down edge fails everything in flight

        assertTrue(reply.await().isFailure)
    }

    @Test
    fun unparseableFrameIsIgnored() = runTest(UnconfinedTestDispatcher()) {
        val t = FakeTransport()
        val client = TackyClient(t, backgroundScope).apply { start() }

        val events = mutableListOf<TackyClient.Event>()
        backgroundScope.launch { client.events.collect { events.add(it) } }

        t.emit("not json at all")
        t.emit("""["event","conn","<State>",{"acc":"a@b"}]""")

        // The bad frame neither crashed the collector nor produced an event.
        assertEquals(1, events.size)
        assertEquals("conn", events[0].module)
    }
}
