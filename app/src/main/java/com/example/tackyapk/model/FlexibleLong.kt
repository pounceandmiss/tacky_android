package com.example.tackyapk.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Accepts a Long whether it arrives as a JSON number or a string. jsonify only
 * types schema-listed fields, so a numeric value sitting outside the active
 * schema (e.g. a <Patch>'s timestamp/newtimestamp) comes over as "123" rather
 * than 123, which a plain Long field would reject.
 */
object FlexibleLongSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleLong", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Long {
        val prim = (decoder as JsonDecoder).decodeJsonElement() as? JsonPrimitive ?: return 0L
        prim.longOrNull?.let { return it }
        return prim.content.trim().toLongOrNull() ?: 0L
    }

    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)
}
