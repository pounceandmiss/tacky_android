package com.example.tackyapk

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.tackyapk.model.jsonArgs
import com.example.tackyapk.model.long
import com.example.tackyapk.model.str
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** Snapshot of a tracked attachment's download. */
data class FileState(
    val state: String = "idle",
    val localPath: String? = null,
    val thumbPath: String? = null,
    val loaded: Long = 0,
    val total: Long = 0,
)

/**
 * Shared, refcounted attachment-download cache over [TackyClient], modelled on
 * [AvatarCache]. The same attachment may show in several slots; the download is
 * cache-backed per (acc, url): the first slot kicks the backend's `file download`
 * (unless a terminal result is already cached), later slots share it, and an
 * <Update> for the url fans out to every listener.
 *
 * Runs on the main thread (track/untrack come from composition, events resume on
 * the scope's main dispatcher), so the maps need no locks.
 */
class FileCache(private val client: TackyClient, private val scope: CoroutineScope) {

    fun interface Listener {
        fun onFile(state: FileState)
    }

    /** Opaque registration returned by [track] and passed back to [untrack]. */
    class Subscription internal constructor(internal val key: Key, internal val listener: Listener)

    internal data class Key(val acc: String, val url: String)

    private class Entry {
        var refs = 0
        var state = FileState()
        val listeners = mutableSetOf<Listener>()
    }

    private val entries = HashMap<Key, Entry>()

    // Terminal "done" states survive untrack in a small LRU, so re-tracking a
    // downloaded attachment repaints instantly from memory instead of re-issuing
    // the download (mirrors AvatarCache.bitmaps).
    private val terminal = object : LinkedHashMap<Key, FileState>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, FileState>) =
            size > MAX_CACHED
    }

    fun start() {
        scope.launch {
            client.events
                .filter { it.module == "file" && it.event == "<Update>" }
                .collect { onUpdate(it.args) }
        }
    }

    /** Register interest in an attachment's download. [listener] fires with the
     *  current state immediately and again whenever it changes. */
    fun track(acc: String, url: String, listener: Listener): Subscription {
        val key = Key(acc, url)
        val entry = entries.getOrPut(key) { Entry().also { it.state = terminal[key] ?: FileState() } }
        entry.refs++
        entry.listeners += listener
        listener.onFile(entry.state)
        if (entry.refs == 1 && entry.state.state != "done") {
            client.notify("file", "download", args(key))
        }
        return Subscription(key, listener)
    }

    fun untrack(sub: Subscription) {
        val entry = entries[sub.key] ?: return
        entry.listeners -= sub.listener
        if (--entry.refs <= 0) entries.remove(sub.key)
    }

    private fun onUpdate(argsJson: JsonElement?) {
        val o = argsJson as? JsonObject ?: return
        val key = Key(o.str("acc") ?: return, o.str("url") ?: return)
        val state = FileState(
            state = o.str("state") ?: "idle",
            localPath = o.str("localpath"),
            thumbPath = o.str("thumbpath"),
            loaded = o.long("loaded") ?: 0,
            total = o.long("total") ?: 0,
        )
        if (state.state == "done") terminal[key] = state
        val entry = entries[key] ?: return
        entry.state = state
        entry.listeners.forEach { it.onFile(state) }
    }

    private fun args(key: Key) = jsonArgs("acc" to key.acc, "url" to key.url)

    private companion object {
        const val MAX_CACHED = 64
    }
}

/** Tracks the download for [acc]/[url] for as long as it's in composition.
 *  Returns the current [FileState]. */
@Composable
fun rememberFile(cache: FileCache, acc: String, url: String): FileState {
    var state by remember(cache, acc, url) { mutableStateOf(FileState()) }
    DisposableEffect(cache, acc, url) {
        val sub = cache.track(acc, url) { state = it }
        onDispose { cache.untrack(sub) }
    }
    return state
}
