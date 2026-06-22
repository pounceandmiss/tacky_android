package com.example.tackyapk.feature.chatlist

import com.example.tackyapk.model.FlexibleBooleanSerializer
import kotlinx.serialization.Serializable

/**
 * One row of the chat list, as the backend's `chatlist search` and its patch
 * events deliver it. The recent/roster sections come back as roster_item and the
 * bookmarks section as bookmark, so this is the union of both shapes - every
 * field carries a default and the unused ones simply stay empty.
 *
 * jid is always a chat JID, opened verbatim: bare = 1:1, room@host?join = group
 * chat, room@host/nick = MUC PM. The `?join` suffix (see [isMuc]) is the tell for
 * group vs 1:1; `source` only says where the counterpart is known from.
 */
@Serializable
data class ChatListEntry(
    val jid: String = "",
    val name: String = "",
    // roster_item fields
    val subscription: String = "",
    val ask: String = "",
    // A bookmark can appear in the roster_item-typed recent section, so its bool
    // fields fall outside the active schema and arrive as "0"/"1" strings.
    @Serializable(with = FlexibleBooleanSerializer::class)
    val approved: Boolean = false,
    val groups: List<String> = emptyList(),
    // bookmark fields
    @Serializable(with = FlexibleBooleanSerializer::class)
    val autojoin: Boolean = false,
    val nick: String = "",
    // recent-section override: roster|bookmark|both|none
    val source: String = "none",
) {
    val isMuc: Boolean get() = jid.endsWith("?join")

    /** What a row shows: the resolved name, falling back to the JID. */
    val display: String get() = name.ifEmpty { jid }
}

/** The three backend-driven sections of `chatlist search`, kept in wire order. */
@Serializable
data class ChatList(
    val recent: List<ChatListEntry> = emptyList(),
    val roster: List<ChatListEntry> = emptyList(),
    val bookmarks: List<ChatListEntry> = emptyList(),
)
