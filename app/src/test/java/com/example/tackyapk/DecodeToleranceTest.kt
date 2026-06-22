package com.example.tackyapk

import com.example.tackyapk.feature.chatlist.ChatList
import com.example.tackyapk.feature.conversation.Message
import com.example.tackyapk.model.AppJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * jsonify serializes fields outside the active schema as JSON strings, so booleans
 * can arrive as "0"/"1". These pin down that such payloads still decode (and that
 * one bad element doesn't nuke a whole list).
 */
class DecodeToleranceTest {

    private inline fun <reified T> decode(json: String): T? =
        runCatching { AppJson.decodeFromString<T>(json) }.getOrNull()

    @Test
    fun chatListDecodesStringBooleans() {
        // A bookmark in the roster_item-typed recent section: autojoin is "1".
        val json = """
            {"recent":[{"jid":"room@x?join","name":"Room","autojoin":"1","nick":"me",
                        "room-state":"disconnected","source":"book"}],
             "roster":[{"jid":"bob@x","name":"Bob","approved":"0"}],
             "bookmarks":[]}
        """.trimIndent()

        val list = decode<ChatList>(json)
        assertTrue("list should decode, not collapse to null", list != null)
        assertEquals(1, list!!.recent.size)
        assertEquals(1, list.roster.size)
        assertTrue(list.recent[0].autojoin)
        assertFalse(list.roster[0].approved)
    }

    @Test
    fun messageDecodesStringIsOutgoing() {
        val outgoing = decode<Message>("""{"timestamp":1,"body":"hi","is_outgoing":"1"}""")
        val incoming = decode<Message>("""{"timestamp":2,"body":"yo","is_outgoing":"0"}""")
        assertTrue(outgoing != null && outgoing.is_outgoing)
        assertTrue(incoming != null && !incoming.is_outgoing)
    }
}
