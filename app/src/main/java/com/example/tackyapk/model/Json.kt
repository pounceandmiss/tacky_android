package com.example.tackyapk.model

import com.google.gson.JsonElement as GsonElement
import kotlinx.serialization.json.Json

/**
 * The wire is parsed by TackyClient with Gson (JsonElement); typed models are
 * decoded with kotlinx.serialization. This bridges the two: a Gson element's
 * toString() is valid JSON, which kotlinx then deserializes into a data class.
 * ignoreUnknownKeys lets models declare only the fields they use (e.g. Account
 * skips the password the backend returns).
 */
val AppJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

inline fun <reified T> GsonElement?.decodeOrNull(): T? =
    runCatching { AppJson.decodeFromString<T>(this.toString()) }.getOrNull()
