package com.example.tackyapk.feature.chatlist

import com.example.tackyapk.TackyClient
import com.example.tackyapk.collectModule
import com.example.tackyapk.model.decodeOrNull
import com.example.tackyapk.model.jsonArgs
import com.example.tackyapk.model.str
import com.example.tackyapk.reloadOnRunning
import com.google.gson.JsonElement as GsonElement
import com.google.gson.JsonObject as GsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Typed chat-list state over [TackyClient], mirroring TackyRepository: the
 * backend owns the list, this turns its request/reply + event stream into a
 * single [chatList] StateFlow.
 *
 * Protocol note: `chatlist search` is a `tackymethod` (replies via a token) so it
 * uses [TackyClient.request]; the live updates arrive as chatlist patch events
 * (<Item>/<Remove> patch one entry, <Changed> means clear-and-refetch a section,
 * <RecentTop>/<RecentDrop> nudge the recent section). The account to query is
 * carried per request as the un-dashed `acc` arg, set via [load].
 */
class ChatListRepository(
    private val client: TackyClient,
    private val state: StateFlow<String>,
    private val scope: CoroutineScope,
) {
    private val _chatList = MutableStateFlow(ChatList())
    val chatList: StateFlow<ChatList> = _chatList.asStateFlow()

    /** The account whose chat list is shown. Null until [load] picks one. */
    @Volatile
    var acc: String? = null
        private set

    /** The active search term; empty means show the full list. The backend filters. */
    @Volatile
    var query: String = ""
        private set

    fun start() {
        scope.reloadOnRunning(state) { refresh() }
        // Every account's chatlist events share one stream; collectModule drops any
        // not for the account currently shown so a second account can't patch this
        // list. (acc is always injected by the backend.)
        scope.collectModule(client, "chatlist", acc = { acc }) { ev ->
            // While a search is active the patch events carry unfiltered entries, so
            // applying them granularly would corrupt the filtered list; re-run the
            // server search instead and let it re-filter.
            if (query.isNotEmpty()) {
                scope.launch { runCatching { refresh() } }
                return@collectModule
            }
            when (ev.event) {
                "<Changed>" -> scope.launch { runCatching { refresh() } }
                "<Item>" -> onItem(ev.args)
                "<Remove>" -> onRemove(ev.args)
                "<RecentDrop>" -> onRecentDrop(ev.args)
                // <RecentTop> only flags an already-present recent JID as freshly
                // active; a fresh entry still arrives via <Item>.
            }
        }
    }

    /** Switch the active account and reload, clearing any active search. */
    fun load(acc: String) {
        this.acc = acc
        this.query = ""
        scope.launch { runCatching { refresh() } }
    }

    /** Set the search term and refetch; the backend does the filtering. */
    fun setQuery(query: String) {
        if (query == this.query) return
        this.query = query
        scope.launch { runCatching { refresh() } }
    }

    suspend fun refresh() {
        val acc = acc ?: return
        val args =
            if (query.isEmpty()) jsonArgs("acc" to acc)
            else jsonArgs("acc" to acc, "query" to query)
        _chatList.value =
            client.request("chatlist", "search", args).decodeOrNull() ?: ChatList()
    }

    private fun onItem(argsJson: GsonElement?) {
        val o = argsJson as? GsonObject ?: return
        val section = o.str("section") ?: return
        val jid = o.str("jid") ?: return
        val entry = o.get("item").decodeOrNull<ChatListEntry>() ?: return
        _chatList.update { list ->
            list.patch(section) { rows ->
                // recent keeps insertion (recency) order with new JIDs on top;
                // roster/bookmarks re-sort by display name to match the backend.
                if (section == "recent") {
                    if (rows.any { it.jid == jid }) rows.map { if (it.jid == jid) entry else it }
                    else listOf(entry) + rows
                } else {
                    (rows.filterNot { it.jid == jid } + entry).sortedBy { it.display.lowercase() }
                }
            }
        }
    }

    private fun onRemove(argsJson: GsonElement?) {
        val o = argsJson as? GsonObject ?: return
        val section = o.str("section") ?: return
        val jid = o.str("jid") ?: return
        _chatList.update { list -> list.patch(section) { rows -> rows.filterNot { it.jid == jid } } }
    }

    private fun onRecentDrop(argsJson: GsonElement?) {
        val o = argsJson as? GsonObject ?: return
        val jid = o.str("jid") ?: return
        _chatList.update { list ->
            list.patch("recent") { rows -> rows.filterNot { it.jid == jid } }
        }
    }

    private inline fun ChatList.patch(
        section: String,
        transform: (List<ChatListEntry>) -> List<ChatListEntry>,
    ): ChatList = when (section) {
        "recent" -> copy(recent = transform(recent))
        "roster" -> copy(roster = transform(roster))
        "bookmarks" -> copy(bookmarks = transform(bookmarks))
        else -> this
    }
}
