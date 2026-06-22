package com.example.tackyapk

import com.example.tackyapk.feature.conversation.ConversationRepository
import com.google.gson.JsonParser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bounded sliding window: paging, culling, the at-tail gate, and patches. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationWindowTest {

    private fun received(acc: String, jid: String, ts: Long, body: String) =
        """["event","message","<Received>",{"acc":"$acc","jid":"$jid",""" +
            """"message":{"timestamp":$ts,"chat_jid":"$jid","body":"$body"}}]"""

    private fun patch(acc: String, jid: String, body: String) =
        """["event","message","<Patch>",{"acc":"$acc","jid":"$jid","messages":[$body]}]"""

    private fun msgs(vararg ts: Long) =
        ts.joinToString(",", "[", "]") { """{"timestamp":$it,"chat_jid":"bob@x","body":"b$it"}""" }

    /** Answer the most recent request frame (last array element is its token). */
    private suspend fun answer(t: FakeTransport, payload: String) {
        val token = JsonParser.parseString(t.sent.last()).asJsonArray.last().asLong
        t.emit("""["result",$token,$payload]""")
    }

    private fun TestScope.newRepo(t: FakeTransport, page: Int = 50, max: Int = 200) =
        ConversationRepository(TackyClient(t, backgroundScope).apply { start() },
            backgroundScope, pageLimit = page, maxWindow = max)

    @Test
    fun patchMergesStringTimestampInPlace() = runTest(UnconfinedTestDispatcher()) {
        val t = FakeTransport(initialState = "starting")
        val repo = newRepo(t)
        repo.load("a@x", "bob@x") // initial history request left unanswered

        t.emit(received("a@x", "bob@x", 5, "hello"))
        // Out-of-schema patch stamp arrives as a string; body must survive.
        t.emit(patch("a@x", "bob@x", """{"timestamp":"5","server_status":"pending"}"""))

        val m = repo.messages.value.single()
        assertEquals("hello", m.body)
        assertEquals("pending", m.server_status)
    }

    @Test
    fun patchForUndisplayedTargetIsDropped() = runTest(UnconfinedTestDispatcher()) {
        val t = FakeTransport(initialState = "starting")
        val repo = newRepo(t)
        repo.load("a@x", "bob@x")

        t.emit(received("a@x", "bob@x", 5, "hello"))
        t.emit(patch("a@x", "bob@x", """{"timestamp":"999","server_status":"failed"}"""))

        assertEquals(listOf(5L), repo.messages.value.map { it.timestamp })
    }

    @Test
    fun timestampMovePatchRepositions() = runTest(UnconfinedTestDispatcher()) {
        val t = FakeTransport(initialState = "starting")
        val repo = newRepo(t)
        repo.load("a@x", "bob@x")

        t.emit(received("a@x", "bob@x", 5, "hello"))
        t.emit(patch("a@x", "bob@x",
            """{"timestamp":"5","newtimestamp":"8","server_status":""}"""))

        val m = repo.messages.value.single()
        assertEquals(8L, m.timestamp)
        assertEquals("hello", m.body)
    }

    @Test
    fun pagingOlderCullsTailAndGatesLiveInserts() = runTest(UnconfinedTestDispatcher()) {
        val t = FakeTransport(initialState = "starting")
        val repo = newRepo(t, page = 2, max = 3)

        repo.load("a@x", "bob@x")
        answer(t, msgs(10, 11)) // newest page
        assertEquals(listOf(10L, 11L), repo.messages.value.map { it.timestamp })
        assertTrue(repo.atTail.value)

        repo.loadOlder()
        answer(t, msgs(8, 9)) // older page; window [8,9,10,11] culls newest -> [8,9,10]
        assertEquals(listOf(8L, 9L, 10L), repo.messages.value.map { it.timestamp })
        assertFalse(repo.atTail.value)

        // Off-tail: a live message must be dropped (would leave a gap).
        t.emit(received("a@x", "bob@x", 12, "x"))
        assertEquals(3, repo.messages.value.size)
    }

    @Test
    fun pagingNewerReachesTailAgain() = runTest(UnconfinedTestDispatcher()) {
        val t = FakeTransport(initialState = "starting")
        val repo = newRepo(t, page = 2, max = 3)

        repo.load("a@x", "bob@x")
        answer(t, msgs(10, 11))
        repo.loadOlder()
        answer(t, msgs(8, 9)) // -> [8,9,10], atTail false

        repo.loadNewer()
        answer(t, msgs(11, 12)) // after=10; window [8,9,10,11,12] culls oldest -> [10,11,12]
        answer(t, "12") // chats maxTimestamp: newest 12 >= 12 -> back at tail

        assertEquals(listOf(10L, 11L, 12L), repo.messages.value.map { it.timestamp })
        assertTrue(repo.atTail.value)
    }
}
