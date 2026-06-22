package com.example.tackyapk.model

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Shared helpers for the tackyd-json wire: building the un-dashed args object the
 * daemon expects, and reading scalars back out tolerantly. jsonify serializes any
 * field outside the active schema as a JSON string, so the scalar readers accept
 * both the typed form and the "0"/"1"/"123" string form (the same tolerance the
 * Flexible*Serializers give the typed models).
 */

/** An args object built in pair order; the daemon adds the dashes. */
fun jsonArgs(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject().apply {
    pairs.forEach { (k, v) ->
        when (v) {
            null -> {}
            is String -> addProperty(k, v)
            is Number -> addProperty(k, v)
            is Boolean -> addProperty(k, v)
            else -> addProperty(k, v.toString())
        }
    }
}

/** A string property, or null when absent / not a primitive. */
fun JsonObject.str(name: String): String? =
    get(name)?.takeIf { it.isJsonPrimitive }?.asString

/** A long property, tolerant of the string form, or null when absent. */
fun JsonObject.long(name: String): Long? {
    val p = get(name)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
    return if (p.isNumber) p.asLong else p.asString.trim().toLongOrNull()
}

/** This element as a String, or [fallback]. */
fun JsonElement?.asStringOr(fallback: String = ""): String =
    this?.takeIf { it.isJsonPrimitive }?.asString ?: fallback

/** This element as a Long, tolerant of the string form, or [fallback]. */
fun JsonElement?.asLongOr(fallback: Long = 0L): Long {
    val p = this?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return fallback
    return if (p.isNumber) p.asLong else p.asString.trim().toLongOrNull() ?: fallback
}

/** This element as a Boolean, tolerant of jsonify's "0"/"1"/"true" string form. */
fun JsonElement?.asBool(): Boolean {
    val p = this?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return false
    if (p.isBoolean) return p.asBoolean
    val s = p.asString.trim()
    return s == "1" || s.equals("true", ignoreCase = true)
}
