package com.example.tackyapk

import com.example.tackyapk.feature.profile.ProfileRepository
import com.google.gson.JsonParser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ProfileRepository: own profile reads correlate by token, writes frame as notify,
 *  and the omemo <BlindTrust> event flips the flow. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileRepositoryTest {

    private val acc = "me@x"

    /** Find the request token for the first un-replied (module, method) frame and
     *  emit a matching result for it. */
    private suspend fun reply(t: FakeTransport, module: String, method: String, payload: String) {
        val token = t.sent
            .map { JsonParser.parseString(it).asJsonArray }
            .first { it.size() == 4 && it[0].asString == module && it[1].asString == method }[3].asLong
        t.emit("""["result",$token,$payload]""")
    }

    private suspend fun replyAll(t: FakeTransport) {
        reply(t, "nick", "get", "\"Tacky Me\"")
        reply(t, "omemo", "own_fingerprint", "\"${"ab".repeat(64)}\"")
        reply(t, "omemo", "device_id", "4242")
        reply(t, "omemo", "blindTrust", "1")
        reply(
            t, "omemo", "trustList",
            """[{"device":"4242","trust":"verified","active":"1","fingerprint":"${"cd".repeat(64)}"},
                {"device":"99","trust":"undecided","active":"0","fingerprint":"${"ef".repeat(64)}"}]""",
        )
    }

    @Test
    fun loadPopulatesAllFlows() = runTest(UnconfinedTestDispatcher()) {
        val t = FakeTransport()
        val client = TackyClient(t, backgroundScope).apply { start() }
        val state = MutableStateFlow("running")
        val repo = ProfileRepository(client, state, backgroundScope).apply { start() }

        repo.load(acc)
        replyAll(t)

        assertEquals("Tacky Me", repo.displayName.value)
        assertEquals("ab".repeat(64), repo.ownFingerprint.value)
        assertEquals(4242L, repo.deviceId.value)
        assertTrue(repo.blindTrust.value)
        assertEquals(2, repo.ownDevices.value.size)
        assertEquals(4242L, repo.ownDevices.value[0].device)
        assertEquals("cd".repeat(64), repo.ownDevices.value[0].fingerprint)
    }

    @Test
    fun loadDecodesNativeWireForm() = runTest(UnconfinedTestDispatcher()) {
        // The verified daemon output: own_fingerprint a plain hex string, device_id
        // and the own trustList rows native-typed (number device, bool active). The
        // schema-less stale build left trustList a Tcl string -> empty own devices.
        val t = FakeTransport()
        val client = TackyClient(t, backgroundScope).apply { start() }
        val repo = ProfileRepository(client, MutableStateFlow("running"), backgroundScope).apply { start() }

        repo.load(acc)
        reply(t, "nick", "get", "\"Tacky Me\"")
        reply(t, "omemo", "own_fingerprint", "\"${"ab".repeat(64)}\"")
        reply(t, "omemo", "device_id", "620384671")
        reply(t, "omemo", "blindTrust", "true")
        reply(
            t, "omemo", "trustList",
            """[{"device":1879211046,"trust":"trusted","active":true,"fingerprint":"${"cd".repeat(64)}"}]""",
        )

        assertEquals("ab".repeat(64), repo.ownFingerprint.value)
        assertEquals(620384671L, repo.deviceId.value)
        assertTrue(repo.blindTrust.value)
        assertEquals(1, repo.ownDevices.value.size)
        assertEquals(1879211046L, repo.ownDevices.value[0].device)
        assertTrue(repo.ownDevices.value[0].active)
    }

    @Test
    fun setDisplayNameFramesNickSet() = runTest(UnconfinedTestDispatcher()) {
        val t = FakeTransport()
        val client = TackyClient(t, backgroundScope).apply { start() }
        val repo = ProfileRepository(client, MutableStateFlow("running"), backgroundScope)

        repo.setDisplayName(acc, "New Name")

        val frame = JsonParser.parseString(t.sent.last()).asJsonArray
        assertEquals(3, frame.size()) // notify: no token
        assertEquals("nick", frame[0].asString)
        assertEquals("set", frame[1].asString)
        val args = frame[2].asJsonObject
        assertEquals(acc, args.get("acc").asString)
        assertEquals("New Name", args.get("nick").asString)
    }

    @Test
    fun setBlindTrustFramesNotify() = runTest(UnconfinedTestDispatcher()) {
        val t = FakeTransport()
        val client = TackyClient(t, backgroundScope).apply { start() }
        val repo = ProfileRepository(client, MutableStateFlow("running"), backgroundScope)

        repo.setBlindTrust(acc, false)

        val frame = JsonParser.parseString(t.sent.last()).asJsonArray
        assertEquals(3, frame.size())
        assertEquals("omemo", frame[0].asString)
        assertEquals("setBlindTrust", frame[1].asString)
        val args = frame[2].asJsonObject
        assertEquals(acc, args.get("acc").asString)
        assertEquals(0, args.get("value").asInt)
    }

    @Test
    fun setOwnDeviceTrustFramesTrustForOwnJid() = runTest(UnconfinedTestDispatcher()) {
        val t = FakeTransport()
        val client = TackyClient(t, backgroundScope).apply { start() }
        val repo = ProfileRepository(client, MutableStateFlow("running"), backgroundScope)
        repo.load(acc)

        repo.setOwnDeviceTrust(acc, 99L, "untrusted")

        val frame = JsonParser.parseString(t.sent.last()).asJsonArray
        assertEquals(3, frame.size()) // notify: no token
        assertEquals("omemo", frame[0].asString)
        assertEquals("trust", frame[1].asString)
        val args = frame[2].asJsonObject
        assertEquals(acc, args.get("acc").asString)
        // jid is our own bare jid - that's what makes it own-device trust.
        assertEquals(acc, args.get("jid").asString)
        assertEquals(99L, args.get("device").asLong)
        assertEquals("untrusted", args.get("state").asString)
    }

    @Test
    fun trustListEventForOwnJidUpdatesOwnDevices() = runTest(UnconfinedTestDispatcher()) {
        val t = FakeTransport()
        val client = TackyClient(t, backgroundScope).apply { start() }
        val repo = ProfileRepository(client, MutableStateFlow("running"), backgroundScope).apply { start() }
        repo.load(acc)
        replyAll(t)
        assertEquals(2, repo.ownDevices.value.size)

        // EmitTrustList re-broadcasts the whole list for our own bare jid.
        t.emit(
            """["event","omemo","<TrustList>",
                {"acc":"$acc","jid":"$acc","trustList":[
                    {"device":99,"trust":"untrusted","active":true,"fingerprint":"${"ef".repeat(64)}"}
                ]}]""",
        )

        assertEquals(1, repo.ownDevices.value.size)
        assertEquals(99L, repo.ownDevices.value[0].device)
        assertEquals("untrusted", repo.ownDevices.value[0].trust)
    }

    @Test
    fun trustListEventForOtherJidIgnored() = runTest(UnconfinedTestDispatcher()) {
        val t = FakeTransport()
        val client = TackyClient(t, backgroundScope).apply { start() }
        val repo = ProfileRepository(client, MutableStateFlow("running"), backgroundScope).apply { start() }
        repo.load(acc)
        replyAll(t)
        assertEquals(2, repo.ownDevices.value.size)

        // A peer's trust list must not clobber the own-devices flow.
        t.emit(
            """["event","omemo","<TrustList>",
                {"acc":"$acc","jid":"peer@x","trustList":[
                    {"device":7,"trust":"trusted","active":true,"fingerprint":"aa"}
                ]}]""",
        )

        assertEquals(2, repo.ownDevices.value.size)
    }

    @Test
    fun blindTrustEventFlipsFlow() = runTest(UnconfinedTestDispatcher()) {
        val t = FakeTransport()
        val client = TackyClient(t, backgroundScope).apply { start() }
        val repo = ProfileRepository(client, MutableStateFlow("running"), backgroundScope).apply { start() }
        repo.load(acc)
        replyAll(t)
        assertTrue(repo.blindTrust.value)

        t.emit("""["event","omemo","<BlindTrust>",{"acc":"$acc","value":0}]""")
        assertEquals(false, repo.blindTrust.value)
    }

    @Test
    fun blindTrustEventForOtherAccountIgnored() = runTest(UnconfinedTestDispatcher()) {
        val t = FakeTransport()
        val client = TackyClient(t, backgroundScope).apply { start() }
        val repo = ProfileRepository(client, MutableStateFlow("running"), backgroundScope).apply { start() }
        repo.load(acc)
        replyAll(t)
        assertTrue(repo.blindTrust.value)

        t.emit("""["event","omemo","<BlindTrust>",{"acc":"other@x","value":0}]""")
        assertTrue("event for another account must not flip this flow", repo.blindTrust.value)
    }
}
