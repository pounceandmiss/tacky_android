package com.example.tackyapk.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.example.tackyapk.AppDeps
import com.example.tackyapk.feature.chatlist.ChatListScreen
import com.example.tackyapk.R
import kotlinx.coroutines.delay

/**
 * The chat-list home for one account, styled after Conversations: no drawer - a
 * plain top bar carries search plus a 3-dot overflow (accounts, console), and an
 * extended FAB opens the start-conversation picker. The shown account rides as
 * [acc]; switching happens through the accounts screen, not an inline switcher.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    deps: AppDeps,
    acc: String,
    onStartConversation: () -> Unit,
    onOpenChat: (acc: String, jid: String, name: String) -> Unit,
    onConsole: () -> Unit,
    onAccounts: () -> Unit,
) {
    // Search lives in the top bar; the typed text is debounced into the chat-list
    // repository, which asks the backend to filter. Keyed by acc so switching
    // accounts resets the search (the repository also clears its query on load).
    var searching by rememberSaveable(acc) { mutableStateOf(false) }
    var queryText by rememberSaveable(acc) { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(queryText) {
        delay(250)
        deps.chatList.setQuery(queryText.trim())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        SearchField(queryText, { queryText = it }, "Search chats")
                    } else {
                        Text("Chats")
                    }
                },
                navigationIcon = {
                    if (searching) {
                        IconButton(onClick = { searching = false; queryText = "" }) {
                            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Close search")
                        }
                    }
                },
                actions = {
                    if (searching) {
                        IconButton(onClick = { queryText = "" }) {
                            Icon(painterResource(R.drawable.ic_close), contentDescription = "Clear")
                        }
                    } else {
                        IconButton(onClick = { searching = true }) {
                            Icon(painterResource(R.drawable.ic_search), contentDescription = "Search")
                        }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            // Opens the accounts list: add, switch, or manage accounts.
                            DropdownMenuItem(
                                text = { Text("Accounts") },
                                onClick = { menuOpen = false; onAccounts() },
                            )
                            DropdownMenuItem(
                                text = { Text("Console") },
                                onClick = { menuOpen = false; onConsole() },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (!searching) {
                ExtendedFloatingActionButton(
                    onClick = onStartConversation,
                    icon = { Icon(painterResource(R.drawable.ic_edit), contentDescription = null) },
                    text = { Text("New chat") },
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            ChatListScreen(
                repo = deps.chatList,
                avatars = deps.avatars,
                acc = acc,
                onOpenChat = onOpenChat,
            )
        }
    }
}
