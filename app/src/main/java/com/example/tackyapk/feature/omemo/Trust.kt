package com.example.tackyapk.feature.omemo

import com.example.tackyapk.model.FlexibleBooleanSerializer
import com.example.tackyapk.model.FlexibleLongSerializer
import kotlinx.serialization.Serializable

/**
 * One device row from omemo/trustList. `device` and `active` sit outside the
 * active jsonify schema, so they arrive as JSON strings ("123"/"1") - the
 * flexible serializers accept both the string and native forms.
 */
@Serializable
data class TrustEntry(
    @Serializable(with = FlexibleLongSerializer::class) val device: Long = 0,
    val trust: String = "undecided",
    @Serializable(with = FlexibleBooleanSerializer::class) val active: Boolean = false,
    val fingerprint: String = "",
)
