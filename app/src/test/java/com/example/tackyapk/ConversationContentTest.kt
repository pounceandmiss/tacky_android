package com.example.tackyapk

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import com.example.tackyapk.feature.conversation.ConversationContent
import com.example.tackyapk.feature.conversation.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger

/**
 * Renders the real conversation UI headlessly (Robolectric) and drives it like the
 * Tk wish tests - this is where the scroll/paging decisions live, the part the
 * repository unit tests can't see.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ConversationContentTest {

    @get:Rule
    val rule = createComposeRule()

    private fun msg(ts: Long, body: String) = Message(timestamp = ts, chat_jid = "j", body = body)

    @Test
    fun rendersBubbles() {
        rule.setContent {
            ConversationContent(listOf(msg(1, "hello-bubble")), true, {}, {}, {}, {}, { "" }, {})
        }
        rule.onNodeWithText("hello-bubble").assertIsDisplayed()
    }

    /** The bug we hit by hand: a chat too short to scroll must still auto-page,
     *  not sit there showing a couple of messages. */
    @Test
    fun shortChatAutoLoadsOlder() {
        val older = AtomicInteger(0)
        rule.setContent {
            ConversationContent(
                messages = listOf(msg(1, "a"), msg(2, "b")),
                atTail = true,
                onLoadOlder = { older.incrementAndGet() },
                onLoadNewer = {}, onJumpToBottom = {}, onSend = {},
                resolveName = { "" }, avatarSlot = {},
            )
        }
        rule.waitForIdle()
        assertTrue("short chat should auto-load older", older.get() >= 1)
    }

    @Test
    fun scrollingUpLoadsOlder() {
        val older = AtomicInteger(0)
        val msgs = (1..60L).map { msg(it, "m$it") }
        rule.setContent {
            ConversationContent(msgs, true, { older.incrementAndGet() }, {}, {}, {}, { "" }, {})
        }
        rule.waitForIdle()
        val before = older.get() // parked at the bottom: should not be paging older
        // Deterministic scroll to the top (a fling gesture never settles under
        // Robolectric's clock); this drives the same firstVisibleItemIndex -> 0 path.
        rule.onNodeWithTag("messageList").performScrollToIndex(0)
        rule.waitForIdle()
        assertTrue("scrolling up should load older", older.get() > before)
    }

    @Test
    fun jumpToLatestShownAndInvokedWhenOffTail() {
        val jumped = AtomicInteger(0)
        rule.setContent {
            ConversationContent(
                messages = listOf(msg(1, "x")),
                atTail = false,
                onLoadOlder = {}, onLoadNewer = {},
                onJumpToBottom = { jumped.incrementAndGet() }, onSend = {},
                resolveName = { "" }, avatarSlot = {},
            )
        }
        rule.onNodeWithText("Jump to latest").assertIsDisplayed().performClick()
        assertEquals(1, jumped.get())
    }

    /** An incoming row shows its resolved nickname and gets an avatar slot. */
    @Test
    fun incomingShowsNicknameAndAvatar() {
        val incoming = Message(timestamp = 1, chat_jid = "j", body = "hi-there", is_outgoing = false)
        val avatared = mutableListOf<Long>()
        rule.setContent {
            ConversationContent(
                messages = listOf(incoming),
                atTail = true,
                onLoadOlder = {}, onLoadNewer = {}, onJumpToBottom = {}, onSend = {},
                resolveName = { "Nick-Name" },
                avatarSlot = { avatared.add(it.timestamp) },
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("Nick-Name").assertIsDisplayed()
        assertTrue("avatar slot called for incoming", avatared.contains(1L))
    }

    /** An outgoing row shows neither a nickname nor an avatar slot. */
    @Test
    fun outgoingShowsNeitherNicknameNorAvatar() {
        val outgoing = Message(timestamp = 2, chat_jid = "j", body = "my-msg", is_outgoing = true)
        val avatared = mutableListOf<Long>()
        rule.setContent {
            ConversationContent(
                messages = listOf(outgoing),
                atTail = true,
                onLoadOlder = {}, onLoadNewer = {}, onJumpToBottom = {}, onSend = {},
                resolveName = { "Should-Not-Show" },
                avatarSlot = { avatared.add(it.timestamp) },
            )
        }
        rule.waitForIdle()
        rule.onAllNodesWithText("Should-Not-Show").assertCountEquals(0)
        assertTrue("avatar slot not called for outgoing", avatared.isEmpty())
    }
}
