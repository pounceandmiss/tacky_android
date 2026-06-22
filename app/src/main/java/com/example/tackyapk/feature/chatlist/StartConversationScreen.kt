package com.example.tackyapk.feature.chatlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import com.example.tackyapk.AvatarCache
import com.example.tackyapk.TackyClient
import com.example.tackyapk.model.decodeOrNull
import com.example.tackyapk.model.jsonArgs
import com.example.tackyapk.rememberAvatar
import com.example.tackyapk.ui.Avatar
import com.example.tackyapk.ui.BackButton
import com.example.tackyapk.ui.EmptyState
import com.example.tackyapk.ui.SearchField
import com.example.tackyapk.R
import kotlinx.coroutines.delay

/**
 * The Conversations-style "start a conversation" hub: reached from the chat-list
 * FAB, it splits the account's roster and bookmarks into Contacts / Group chats
 * tabs with a top-bar search, and tapping a row opens (or starts) that chat. It
 * fetches its own [ChatList] one-shot via [client] so it stays independent of the
 * home list's live search.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StartConversationScreen(
    client: TackyClient,
    avatars: AvatarCache,
    acc: String,
    onOpenChat: (acc: String, jid: String, name: String) -> Unit,
    onBack: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var list by remember { mutableStateOf(ChatList()) }
    var fabMenu by remember { mutableStateOf(false) }
    var showAddContact by rememberSaveable { mutableStateOf(false) }
    var showJoinGroup by rememberSaveable { mutableStateOf(false) }

    // Start a 1:1 chat with an arbitrary JID: add the roster item so it shows in
    // Contacts (fire-and-forget; the daemon pushes the roster update), then open
    // the conversation so the user can message right away.
    fun addContact(jid: String, name: String) {
        val bare = jid.trim()
        client.notify(
            "roster", "item",
            jsonArgs("acc" to acc, "jid" to bare, "name" to name.trim().ifBlank { null }),
        )
        onOpenChat(acc, bare, name.trim().ifBlank { bare })
    }

    // Join a group chat via the bookmarks API: publishing a bookmark with
    // autojoin=1 makes the daemon join the room; the chat opens on its `?join`
    // MUC JID (the bookmark itself is keyed by the bare room JID server-side).
    fun joinGroup(room: String, nick: String) {
        val bare = room.trim()
        client.notify(
            "bookmarks", "item",
            jsonArgs("acc" to acc, "jid" to bare, "autojoin" to 1, "nick" to nick.trim().ifBlank { null }),
        )
        onOpenChat(acc, "$bare?join", bare)
    }

    // The backend filters server-side, same as the home list; debounce the query
    // so each keystroke doesn't fire a request.
    LaunchedEffect(acc, query) {
        delay(250)
        val args =
            if (query.isBlank()) jsonArgs("acc" to acc)
            else jsonArgs("acc" to acc, "query" to query.trim())
        list = client.request("chatlist", "search", args).decodeOrNull() ?: ChatList()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        SearchField(query, { query = it }, "Search")
                    } else {
                        Text("New conversation")
                    }
                },
                navigationIcon = {
                    if (searching) {
                        IconButton(onClick = { searching = false; query = "" }) {
                            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Close search")
                        }
                    } else {
                        BackButton(onBack)
                    }
                },
                actions = {
                    if (searching) {
                        IconButton(onClick = { query = "" }) {
                            Icon(painterResource(R.drawable.ic_close), contentDescription = "Clear")
                        }
                    } else {
                        IconButton(onClick = { searching = true }) {
                            Icon(painterResource(R.drawable.ic_search), contentDescription = "Search")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { fabMenu = true }) {
                    Icon(painterResource(R.drawable.ic_add), contentDescription = "Start something new")
                }
                DropdownMenu(expanded = fabMenu, onDismissRequest = { fabMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Add contact") },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_person_add), contentDescription = null) },
                        onClick = { fabMenu = false; showAddContact = true },
                    )
                    DropdownMenuItem(
                        text = { Text("Join group chat") },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_group), contentDescription = null) },
                        onClick = { fabMenu = false; showJoinGroup = true },
                    )
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Contacts") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Group chats") })
            }
            val entries = if (tab == 0) list.roster else list.bookmarks
            val emptyIcon = if (tab == 0) painterResource(R.drawable.ic_person_outlined) else painterResource(R.drawable.ic_group_outlined)
            val emptyText = if (tab == 0) "No contacts" else "No group chats"
            Box(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                if (entries.isEmpty()) {
                    EmptyState(
                        icon = emptyIcon,
                        text = emptyText,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(entries, key = { it.jid }) { entry ->
                            PickerRow(
                                entry = entry,
                                avatars = avatars,
                                acc = acc,
                                onClick = { onOpenChat(acc, entry.jid, entry.display) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    if (showAddContact) {
        EnterJidDialog(
            title = "Add contact",
            jidLabel = "Contact JID (user@host)",
            secondLabel = "Name (optional)",
            confirmLabel = "Add",
            onDismiss = { showAddContact = false },
            onConfirm = { jid, name -> showAddContact = false; addContact(jid, name) },
        )
    }
    if (showJoinGroup) {
        EnterJidDialog(
            title = "Join group chat",
            jidLabel = "Group address (room@host)",
            secondLabel = "Nickname (optional)",
            confirmLabel = "Join",
            onDismiss = { showJoinGroup = false },
            onConfirm = { jid, nick -> showJoinGroup = false; joinGroup(jid, nick) },
        )
    }
}

/**
 * A small two-field entry dialog shared by the add-contact and join-group flows:
 * a required JID plus an optional secondary field (display name / nickname). The
 * confirm button stays disabled until the JID looks like a bare address.
 */
@Composable
private fun EnterJidDialog(
    title: String,
    jidLabel: String,
    secondLabel: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (jid: String, second: String) -> Unit,
) {
    var jid by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val valid = jid.trim().let { it.contains('@') && !it.startsWith('@') && !it.endsWith('@') }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = jid,
                    onValueChange = { jid = it },
                    label = { Text(jidLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = second,
                    onValueChange = { second = it },
                    label = { Text(secondLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm(jid, second) }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PickerRow(
    entry: ChatListEntry,
    avatars: AvatarCache,
    acc: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(rememberAvatar(avatars, acc, entry.jid), size = 40.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.display,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                entry.jid,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
