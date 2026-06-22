package com.example.tackyapk.feature.chatlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.tackyapk.AvatarCache
import com.example.tackyapk.rememberAvatar
import com.example.tackyapk.ui.Avatar
import com.example.tackyapk.ui.EmptyState
import com.example.tackyapk.R

/**
 * Backend-driven chat list for one account, grouped into recent / contacts /
 * group chats. Tapping a row hands ([acc], jid) to [onOpenChat]; the conversation
 * screen is built separately, this only fires the lambda.
 */
@Composable
fun ChatListScreen(
    repo: ChatListRepository,
    avatars: AvatarCache,
    acc: String,
    onOpenChat: (acc: String, jid: String, name: String) -> Unit,
) {
    val vm: ChatListViewModel = viewModel(factory = ChatListViewModel.factory(repo))
    val list by vm.chatList.collectAsStateWithLifecycle()

    // Point the repository at this account whenever it changes.
    LaunchedEffect(acc) { vm.load(acc) }

    val empty = list.recent.isEmpty() && list.roster.isEmpty() && list.bookmarks.isEmpty()
    Box(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        if (empty) {
            EmptyState(
                icon = painterResource(R.drawable.ic_chat_bubble_outline),
                text = "No chats yet",
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                section("Recent", list.recent, avatars, acc, onOpenChat)
                section("Contacts", list.roster, avatars, acc, onOpenChat)
                section("Group chats", list.bookmarks, avatars, acc, onOpenChat)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String,
    entries: List<ChatListEntry>,
    avatars: AvatarCache,
    acc: String,
    onOpenChat: (acc: String, jid: String, name: String) -> Unit,
) {
    if (entries.isEmpty()) return
    item(key = "header:$title") {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
    }
    items(entries, key = { "$title:${it.jid}" }) { entry ->
        ChatRow(
            entry = entry,
            avatars = avatars,
            acc = acc,
            onClick = { onOpenChat(acc, entry.jid, entry.display) },
        )
        HorizontalDivider()
    }
}

@Composable
private fun ChatRow(
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
