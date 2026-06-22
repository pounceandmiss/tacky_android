package com.example.tackyapk

import com.example.tackyapk.feature.calls.CallRepository
import com.example.tackyapk.feature.calls.CallStatus
import com.google.gson.JsonParser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The calls repo over a fake transport: events (with the daemon-injected `acc`)
 * drive the single-call StateFlow through its lifecycle, and the control methods
 * key off the current call's sid.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallRepositoryTest {

    private val acc = "me@x"
    private val peer = "peer@x"

    private fun lastFrame(t: FakeTransport) = JsonParser.parseString(t.sent.last()).asJsonArray

    @Test
    fun outgoingEventEstablishesCallAndTransitions() = runTest(UnconfinedTestDispatcher()) {
        val t = FakeTransport()
        val client = TackyClient(t, backgroundScope).apply { start() }
        val repo = CallRepository(client, backgroundScope)

        t.emit("""["event","calls","<Outgoing>",{"acc":"$acc","sid":"s1","to":"$peer"}]""")
        repo.call.value!!.let {
            assertEquals("s1", it.sid)
            assertEquals(acc, it.acc)
            assertEquals(peer, it.peer)
            assertTrue(!it.incoming)
            assertEquals(CallStatus.OUTGOING, it.status)
        }

        t.emit("""["event","calls","<Ringing>",{"acc":"$acc","sid":"s1"}]""")
        assertEquals(CallStatus.RINGING, repo.call.value!!.status)

        t.emit("""["event","calls","<Active>",{"acc":"$acc","sid":"s1"}]""")
        assertEquals(CallStatus.ACTIVE, repo.call.value!!.status)
    }

    @Test
    fun incomingEventEstablishesIncomingCall() = runTest(UnconfinedTestDispatcher()) {
        val t = FakeTransport()
        val client = TackyClient(t, backgroundScope).apply { start() }
        val repo = CallRepository(client, backgroundScope)

        t.emit("""["event","calls","<Incoming>",{"acc":"$acc","sid":"s2","from":"$peer"}]""")
        repo.call.value!!.let {
            assertEquals("s2", it.sid)
            assertEquals(peer, it.peer)
            assertTrue(it.incoming)
            assertEquals(CallStatus.INCOMING, it.status)
        }
    }

    @Test
    fun secondCallIgnoredWhileBusy() = runTest(UnconfinedTestDispatcher()) {
        val t = FakeTransport()
        val client = TackyClient(t, backgroundScope).apply { start() }
        val repo = CallRepository(client, backgroundScope)

        t.emit("""["event","calls","<Active>",{"acc":"$acc","sid":"s1"}]""")
        // No call existed for s1, so a bare <Active> establishes nothing.
        assertNull(repo.call.value)

        t.emit("""["event","calls","<Outgoing>",{"acc":"$acc","sid":"s1","to":"$peer"}]""")
        t.emit("""["event","calls","<Incoming>",{"acc":"$acc","sid":"s2","from":"other@x"}]""")
        // The second, different call is dropped while s1 is live.
        assertEquals("s1", repo.call.value!!.sid)
    }

    @Test
    fun startWritesExpectedFrame() = runTest(UnconfinedTestDispatcher()) {
        val t = FakeTransport()
        val client = TackyClient(t, backgroundScope).apply { start() }
        val repo = CallRepository(client, backgroundScope)

        repo.start(acc, peer)
        val frame = lastFrame(t)
        assertEquals(3, frame.size()) // notify: no token
        assertEquals("calls", frame[0].asString)
        assertEquals("start", frame[1].asString)
        val a = frame[2].asJsonObject
        assertEquals(acc, a.get("acc").asString)
        assertEquals(peer, a.get("to").asString)
    }

    @Test
    fun acceptAndHangupUseCurrentSid() = runTest(UnconfinedTestDispatcher()) {
        val t = FakeTransport()
        val client = TackyClient(t, backgroundScope).apply { start() }
        val repo = CallRepository(client, backgroundScope)

        t.emit("""["event","calls","<Incoming>",{"acc":"$acc","sid":"s9","from":"$peer"}]""")

        repo.accept()
        lastFrame(t).let {
            assertEquals("accept", it[1].asString)
            assertEquals("s9", it[2].asJsonObject.get("sid").asString)
            assertEquals(acc, it[2].asJsonObject.get("acc").asString)
        }

        repo.hangup()
        lastFrame(t).let {
            assertEquals("hangup", it[1].asString)
            assertEquals("s9", it[2].asJsonObject.get("sid").asString)
        }
    }

    @Test
    fun failedSetsStatusAndReason() = runTest(UnconfinedTestDispatcher()) {
        val t = FakeTransport()
        val client = TackyClient(t, backgroundScope).apply { start() }
        val repo = CallRepository(client, backgroundScope)

        t.emit("""["event","calls","<Outgoing>",{"acc":"$acc","sid":"s1","to":"$peer"}]""")
        t.emit("""["event","calls","<Failed>",{"acc":"$acc","sid":"s1","reason":"ICE failed"}]""")
        repo.call.value!!.let {
            assertEquals(CallStatus.FAILED, it.status)
            assertEquals("ICE failed", it.reason)
            assertTrue(it.terminal)
        }
    }

    @Test
    fun controlMethodsNoopWithoutCall() = runTest(UnconfinedTestDispatcher()) {
        val t = FakeTransport()
        val client = TackyClient(t, backgroundScope).apply { start() }
        val repo = CallRepository(client, backgroundScope)

        repo.accept()
        repo.hangup()
        repo.reject()
        assertTrue(t.sent.isEmpty())
    }
}
