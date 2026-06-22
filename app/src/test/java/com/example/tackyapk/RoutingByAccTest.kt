package com.example.tackyapk

import com.example.tackyapk.data.TackyRepository
import com.example.tackyapk.feature.chatlist.ChatListRepository
import com.example.tackyapk.feature.conversation.ConversationRepository
import com.example.tackyapk.model.ConnState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * With one backend multiplexing several accounts, every account's events land on
 * the single shared stream. These pin down that each consumer keys off `acc` so a
 * second account can't bleed into the one on screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutingByAccTest {

    private fun connEvent(acc: String, state: String) =
        """["event","conn","<State>",{"acc":"$acc","state":"$state"}]"""

    private fun chatItem(acc: String, section: String, jid: String) =
        """["event","chatlist","<Item>",{"acc":"$acc","section":"$section",""" +
            """"jid":"$jid","item":{"jid":"$jid","name":"$jid"}}]"""

    private fun received(acc: String, jid: String, ts: Long, body: String) =
        """["event","message","<Received>",{"acc":"$acc","jid":"$jid",""" +
            """"message":{"timestamp":$ts,"chat_jid":"$jid","body":"$body"}}]"""

    @Test
    fun connStateIsKeyedPerAccount() = runTest(UnconfinedTestDispatcher()) {
        val t = FakeTransport(initialState = "starting")
        val client = TackyClient(t, backgroundScope).apply { start() }
        val repo = TackyRepository(client, t.state, backgroundScope).apply { start() }

        t.emit(connEvent("a1@x", "connected"))
        t.emit(connEvent("a2@x", "connecting"))
        assertEquals(ConnState.CONNECTED, repo.connStates.value["a1@x"])
        assertEquals(ConnState.CONNECTING, repo.connStates.value["a2@x"])

        // The second account dropping must not touch the first.
        t.emit(connEvent("a2@x", "disconnected"))
        assertEquals(ConnState.CONNECTED, repo.connStates.value["a1@x"])
        assertEquals(ConnState.DISCONNECTED, repo.connStates.value["a2@x"])
    }

    @Test
    fun chatListIgnoresOtherAccountsEvents() = runTest(UnconfinedTestDispatcher()) {
        val t = FakeTransport(initialState = "starting")
        val client = TackyClient(t, backgroundScope).apply { start() }
        val repo = ChatListRepository(client, t.state, backgroundScope).apply { start() }
        repo.load("a1@x") // active account; its refresh request is left unanswered

        // A different account's chat-list patch must not appear in this list.
        t.emit(chatItem("a2@x", "recent", "intruder@x"))
        assertTrue(repo.chatList.value.recent.isEmpty())

        // The active account's patch does.
        t.emit(chatItem("a1@x", "recent", "friend@x"))
        assertEquals(listOf("friend@x"), repo.chatList.value.recent.map { it.jid })
    }

    @Test
    fun conversationIgnoresOtherAccountsMessages() = runTest(UnconfinedTestDispatcher()) {
        val t = FakeTransport(initialState = "starting")
        val client = TackyClient(t, backgroundScope).apply { start() }
        val repo = ConversationRepository(client, backgroundScope)
        repo.load("a1@x", "bob@x") // its history request is left unanswered

        // Same chat JID but a different account: not ours.
        t.emit(received("a2@x", "bob@x", 1, "from other account"))
        assertTrue(repo.messages.value.isEmpty())

        t.emit(received("a1@x", "bob@x", 2, "ours"))
        assertEquals(listOf(2L), repo.messages.value.map { it.timestamp })
    }
}
