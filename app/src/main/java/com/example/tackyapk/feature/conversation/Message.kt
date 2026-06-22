package com.example.tackyapk.feature.conversation

import com.example.tackyapk.model.FlexibleBooleanSerializer
import com.example.tackyapk.model.FlexibleLongSerializer
import kotlinx.serialization.Serializable

/**
 * One chat message as the backend's messagestore returns it (via `message
 * history` and the message/<Received>|<Sent>|<Patch> events). Fields mirror the
 * stored row plus the enrichments RowToDict adds; only the ones this feature
 * reads are declared, the rest are skipped by ignoreUnknownKeys.
 *
 * Direction is a protocol fact, not a display choice: the backend sets
 * is_outgoing iff the row carries an own_id (set on our sends and on echoes of
 * our own JID). We read that flag rather than re-deriving it.
 *
 * timestamp is the primary key (microseconds) and also the in-list identity;
 * server_status "" means confirmed, "pending"/"failed" otherwise.
 */
@Serializable
data class Message(
    // TODO: drop FlexibleLongSerializer once tacky is rebuilt to type the
    // <Patch> timestamp/newtimestamp on the wire; a patch row sits outside the
    // active schema, so the stamp can arrive as a "123" string today.
    @Serializable(with = FlexibleLongSerializer::class)
    val timestamp: Long = 0,
    // A timestamp-move <Patch> (server re-stamps a confirmed own message) carries
    // the new stamp here; 0 means no move. Also out-of-schema -> may be a string.
    @Serializable(with = FlexibleLongSerializer::class)
    val newtimestamp: Long = 0,
    val chat_jid: String = "",
    val from_jid: String = "",
    val from_resource: String = "",
    val body: String = "",
    val server_id: String = "",
    val own_id: String = "",
    val reply_id: String = "",
    val reply_to: String = "",
    val reply_body: String = "",
    val server_status: String = "",
    val encryption: String = "",
    val fail_reason: String = "",
    val caption: String = "",
    @Serializable(with = FlexibleBooleanSerializer::class)
    val is_outgoing: Boolean = false,
    val patch: Boolean = false,
    val prev: Long = 0,
    val attachments: List<Attachment> = emptyList(),
    // Styling spans the backend parsed from the body/caption (XEP-0393); offsets
    // index into the displayed text (caption when present, else body).
    val formatting: List<FormatSpan> = emptyList(),
)

@Serializable
data class Attachment(
    val url: String = "",
    val type: String = "",
    val name: String = "",
    val size: Long = 0,
    val mime: String = "",
)
