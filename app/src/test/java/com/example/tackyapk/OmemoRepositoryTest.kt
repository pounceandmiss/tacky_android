package com.example.tackyapk

import com.example.tackyapk.feature.omemo.OmemoRepository
import com.google.gson.JsonParser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The OMEMO repo over a fake transport: tolerant decode of string-typed
 * device/active, live <TrustChanged> patching, and the mutation frames.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OmemoRepositoryTest {

    private val acc = "me@x"
    private val jid = "peer@x"

    /** Find the request frame for (module, method) and return its token. */
    private fun tokenFor(t: FakeTransport, method: String): Long {
        val frame = t.sent
            .map { JsonParser.parseString(it).asJsonArray }
            .last { it[1].asString == method && it.size() == 4 }
        return frame[3].asLong
    }

    @Test
    fun loadDecodesTolerantlyAndPullsBlindTrust() = runTest(UnconfinedTestDispatcher()) {
        val t = FakeTransport()
        val client = TackyClient(t, backgroundScope).apply { start() }
        val repo = OmemoRepository(client, backgroundScope)

        repo.load(acc, jid)

        // device/active arrive string-typed (outside the active jsonify schema).
        val trustToken = tokenFor(t, "trustList")
        t.emit(
            """["result",$trustToken,[
                {"device":"111","trust":"trusted","active":"1","fingerprint":"aabbccdd"},
                {"device":"222","trust":"undecided","active":"0","fingerprint":"eeff0011"}
            ]]""",
        )
        val blindToken = tokenFor(t, "blindTrust")
        t.emit("""["result",$blindToken,"1"]""")

        val list = repo.trustList.value
        assertEquals(2, list.size)
        assertEquals(111L, list[0].device)
        assertEquals("trusted", list[0].trust)
        assertTrue(list[0].active)
        assertEquals(222L, list[1].device)
        assertTrue(!list[1].active)
        assertTrue(repo.blindTrust.value)
    }

    @Test
    fun loadDecodesNativeWireForm() = runTest(UnconfinedTestDispatcher()) {
        // The verified jsonify output (omemo/trustList schema applied): device is a
        // real JSON number, active a real bool, fingerprint contiguous hex. This is
        // the exact shape the fixed daemon emits - the schema-less stale build sent
        // a flat Tcl string instead, which decoded to an empty list.
        val t = FakeTransport()
        val client = TackyClient(t, backgroundScope).apply { start() }
        val repo = OmemoRepository(client, backgroundScope)

        repo.load(acc, jid)
        val trustToken = tokenFor(t, "trustList")
        t.emit(
            """["result",$trustToken,[
                {"device":620384671,"trust":"undecided","active":true,"fingerprint":"9fdaecbe5d2fc571123646b64a15333ef436cd204b00c8d439f718683dad383b"},
                {"device":1879211046,"trust":"trusted","active":false,"fingerprint":"29d92e83179e677cf583b64e5b5ed959688dc4b270068f358534a6e2b3f67515"}
            ]]""",
        )

        val list = repo.trustList.value
        assertEquals(2, list.size)
        assertEquals(620384671L, list[0].device)
        assertEquals("undecided", list[0].trust)
        assertTrue(list[0].active)
        assertEquals(64, list[0].fingerprint.length)
        assertEquals(1879211046L, list[1].device)
        assertTrue(!list[1].active)
    }

    @Test
    fun trustListEventDecodesNativeWireForm() = runTest(UnconfinedTestDispatcher()) {
        // The <TrustList> event with the schema applied: trustList is a real JSON
        // array of objects, not a string. The stale daemon emitted it as a Tcl
        // string ("{device ... } {device ...}") which decoded to nothing.
        val t = FakeTransport()
        val client = TackyClient(t, backgroundScope).apply { start() }
        val repo = OmemoRepository(client, backgroundScope)

        repo.load(acc, jid)
        t.emit(
            """["event","omemo","<TrustList>",
                {"acc":"$acc","jid":"$jid","trustList":[
                    {"device":771760481,"trust":"undecided","active":true,"fingerprint":"733be11baaeaa9b0"},
                    {"device":1055368378,"trust":"trusted","active":true,"fingerprint":"7a37482f6993699b"}
                ]}]""",
        )

        val list = repo.trustList.value
        assertEquals(2, list.size)
        assertEquals(771760481L, list[0].device)
        assertEquals(1055368378L, list[1].device)
        assertEquals("trusted", list[1].trust)
    }

    @Test
    fun trustChangedEventUpdatesOneRow() = runTest(UnconfinedTestDispatcher()) {
        val t = FakeTransport()
        val client = TackyClient(t, backgroundScope).apply { start() }
        val repo = OmemoRepository(client, backgroundScope)

        repo.load(acc, jid)
        val trustToken = tokenFor(t, "trustList")
        t.emit(
            """["result",$trustToken,[
                {"device":"111","trust":"undecided","active":"1","fingerprint":"aa"},
                {"device":"222","trust":"undecided","active":"1","fingerprint":"bb"}
            ]]""",
        )

        t.emit(
            """["event","omemo","<TrustChanged>",
                {"acc":"$acc","jid":"$jid","device":"111","state":"trusted"}]""",
        )

        val list = repo.trustList.value
        assertEquals("trusted", list.first { it.device == 111L }.trust)
        assertEquals("undecided", list.first { it.device == 222L }.trust)
    }

    @Test
    fun trustListEventReplacesForCurrentJid() = runTest(UnconfinedTestDispatcher()) {
        val t = FakeTransport()
        val client = TackyClient(t, backgroundScope).apply { start() }
        val repo = OmemoRepository(client, backgroundScope)

        repo.load(acc, jid)
        t.emit(
            """["event","omemo","<TrustList>",
                {"acc":"$acc","jid":"$jid","trustList":[
                    {"device":"333","trust":"untrusted","active":"1","fingerprint":"cc"}
                ]}]""",
        )

        val list = repo.trustList.value
        assertEquals(1, list.size)
        assertEquals(333L, list[0].device)
        assertEquals("untrusted", list[0].trust)
    }

    @Test
    fun blindTrustEventUpdates() = runTest(UnconfinedTestDispatcher()) {
        val t = FakeTransport()
        val client = TackyClient(t, backgroundScope).apply { start() }
        val repo = OmemoRepository(client, backgroundScope)

        repo.load(acc, jid)
        t.emit("""["event","omemo","<BlindTrust>",{"acc":"$acc","value":"0"}]""")
        assertTrue(!repo.blindTrust.value)
        t.emit("""["event","omemo","<BlindTrust>",{"acc":"$acc","value":"1"}]""")
        assertTrue(repo.blindTrust.value)
    }

    @Test
    fun setTrustWritesExpectedFrame() = runTest(UnconfinedTestDispatcher()) {
        val t = FakeTransport()
        val client = TackyClient(t, backgroundScope).apply { start() }
        val repo = OmemoRepository(client, backgroundScope)

        repo.setTrust(acc, jid, 111L, "trusted")
        val frame = JsonParser.parseString(t.sent.last()).asJsonArray
        assertEquals(3, frame.size()) // notify: no token
        assertEquals("omemo", frame[0].asString)
        assertEquals("trust", frame[1].asString)
        val a = frame[2].asJsonObject
        assertEquals(acc, a.get("acc").asString)
        assertEquals(jid, a.get("jid").asString)
        assertEquals(111L, a.get("device").asLong)
        assertEquals("trusted", a.get("state").asString)
    }

    @Test
    fun setBlindTrustWritesExpectedFrame() = runTest(UnconfinedTestDispatcher()) {
        val t = FakeTransport()
        val client = TackyClient(t, backgroundScope).apply { start() }
        val repo = OmemoRepository(client, backgroundScope)

        repo.setBlindTrust(acc, true)
        val frame = JsonParser.parseString(t.sent.last()).asJsonArray
        assertEquals(3, frame.size())
        assertEquals("omemo", frame[0].asString)
        assertEquals("setBlindTrust", frame[1].asString)
        val a = frame[2].asJsonObject
        assertEquals(acc, a.get("acc").asString)
        assertEquals(1, a.get("value").asInt)
    }
}
