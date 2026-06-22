package com.example.tackyapk

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.tackyapk.model.jsonArgs
import com.example.tackyapk.model.str
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Shared, refcounted avatar cache over [TackyClient], modelled on the backend's
 * avatarcache_base. The same avatar shows in many slots, so images are cached
 * per (acc, jid): the first slot marks the JID visible and fetches the thumb,
 * later slots share it, and the last slot to leave marks it invisible.
 *
 * Runs on the main thread (track/untrack are called from composition, events and
 * thumb fetches resume on the scope's main dispatcher), so the maps need no locks.
 */
class AvatarCache(private val client: TackyClient, private val scope: CoroutineScope) {

    fun interface Listener {
        fun onAvatar(bitmap: Bitmap?)
    }

    /** Opaque registration returned by [track] and passed back to [untrack]. */
    class Subscription internal constructor(internal val key: Key, internal val listener: Listener)

    internal data class Key(val acc: String, val jid: String)

    private class Entry {
        var refs = 0
        var bitmap: Bitmap? = null
        val listeners = mutableSetOf<Listener>()
    }

    private val entries = HashMap<Key, Entry>()

    // Decoded thumbs survive untrack (refcount 0) in a small LRU, so navigating
    // back to a list repaints instantly from memory instead of re-issuing a thumb
    // request and a visible/invisible churn on every navigation. refs/visible still
    // drive what the backend keeps warm; this is purely the display cache (the
    // desktop avatarcache keeps images the same way).
    private val bitmaps = object : LinkedHashMap<Key, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Bitmap>) =
            size > MAX_CACHED
    }

    fun start() {
        scope.launch {
            client.events
                .filter { it.module == "avatar" && it.event == "<Update>" }
                .collect { onUpdate(it.args) }
        }
    }

    /** Register interest in a JID's avatar. [listener] fires with the current
     *  image (null = placeholder) and again whenever it changes. */
    fun track(acc: String, jid: String, listener: Listener): Subscription {
        val key = Key(normJid(acc), normJid(jid))
        val entry = entries.getOrPut(key) { Entry().also { it.bitmap = bitmaps[key] } }
        entry.refs++
        entry.listeners += listener
        listener.onAvatar(entry.bitmap) // immediate paint from the cache (or null)
        if (entry.refs == 1) {
            client.notify("avatar", "visible", args(key))
            fetchThumb(key)
        }
        return Subscription(key, listener)
    }

    fun untrack(sub: Subscription) {
        val entry = entries[sub.key] ?: return
        entry.listeners -= sub.listener
        if (--entry.refs <= 0) {
            entries.remove(sub.key)
            client.notify("avatar", "invisible", args(sub.key))
        }
    }

    private fun onUpdate(argsJson: JsonElement?) {
        val o = argsJson as? JsonObject ?: return
        val key = Key(normJid(o.str("acc") ?: return), normJid(o.str("jid") ?: return))
        if (o.str("action") == "disabled") {
            bitmaps.remove(key)
            entries[key]?.let { e -> e.bitmap = null; e.listeners.forEach { it.onAvatar(null) } }
            return
        }
        // The avatar changed: refetch if it's on screen, otherwise drop the stale
        // cache entry so the next track re-fetches the new image.
        if (entries.containsKey(key)) fetchThumb(key) else bitmaps.remove(key)
    }

    private fun fetchThumb(key: Key) {
        scope.launch {
            val bitmap = try {
                decode(client.request("avatar", "thumb", args(key)))
            } catch (e: TackyClient.TackyError) {
                Log.w("AvatarCache", "thumb error $key", e)
                return@launch
            }
            if (bitmap == null) return@launch
            bitmaps[key] = bitmap
            val entry = entries[key] ?: return@launch
            entry.bitmap = bitmap
            entry.listeners.forEach { it.onAvatar(bitmap) }
        }
    }

    private fun args(key: Key) = jsonArgs("acc" to key.acc, "jid" to key.jid)

    // Mirror avatarcache_base (tacky.tcl): strip a chat JID's ?... query, then
    // normalize - localpart@domain are case-insensitive; the resource is kept.
    private fun normJid(jid: String): String {
        val noQuery = jid.substringBefore("?")
        val slash = noQuery.indexOf('/')
        return if (slash < 0) noQuery.lowercase()
        else noQuery.substring(0, slash).lowercase() + noQuery.substring(slash)
    }

    private fun decode(data: JsonElement?): Bitmap? {
        val b64 = (data?.takeIf { it.isJsonPrimitive }?.asString).orEmpty()
        if (b64.isEmpty()) return null
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private companion object {
        const val MAX_CACHED = 128
    }
}

/** Tracks the avatar for [acc]/[jid] for as long as it's in composition. Returns
 *  the current bitmap, or null for the placeholder. */
@Composable
fun rememberAvatar(cache: AvatarCache, acc: String, jid: String): Bitmap? {
    var bitmap by remember(cache, acc, jid) { mutableStateOf<Bitmap?>(null) }
    DisposableEffect(cache, acc, jid) {
        val sub = cache.track(acc, jid) { bitmap = it }
        onDispose { cache.untrack(sub) }
    }
    return bitmap
}
