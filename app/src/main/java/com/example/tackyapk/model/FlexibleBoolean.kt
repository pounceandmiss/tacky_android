package com.example.tackyapk.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The backend's jsonify serializes any field NOT named in the active schema as a
 * JSON string, so boolean values arrive as "0"/"1" rather than a JSON bool
 * whenever the field falls outside the schema for its position (e.g. a bookmark's
 * `autojoin` sitting in the roster_item-typed `recent` section, or the
 * `is_outgoing` message enrichment). kotlinx would reject that into a Boolean and
 * fail the whole object - and, for a list, the whole list. This accepts either a
 * real bool or the "0"/"1"/"true"/"false" string form.
 */
object FlexibleBooleanSerializer : KSerializer<Boolean> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleBoolean", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Boolean {
        val prim = (decoder as JsonDecoder).decodeJsonElement() as? JsonPrimitive ?: return false
        prim.booleanOrNull?.let { return it }
        val s = prim.content.trim()
        return s == "1" || s.equals("true", ignoreCase = true)
    }

    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)
}
