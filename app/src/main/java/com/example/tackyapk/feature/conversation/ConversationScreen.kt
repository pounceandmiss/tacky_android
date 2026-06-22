package com.example.tackyapk.feature.conversation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.tackyapk.AvatarCache
import com.example.tackyapk.FileCache
import com.example.tackyapk.TackyClient
import com.example.tackyapk.feature.calls.rememberMicGatedAction
import com.example.tackyapk.feature.notifications.ChatKey
import com.example.tackyapk.feature.notifications.ChatPresence
import com.example.tackyapk.feature.notifications.Notifications
import com.example.tackyapk.rememberAvatar
import com.example.tackyapk.ui.Avatar
import com.example.tackyapk.ui.BackButton
import com.example.tackyapk.ui.EmptyState
import com.example.tackyapk.ui.StatusGrey
import com.example.tackyapk.ui.StatusRed
import com.example.tackyapk.R
import kotlinx.coroutines.delay

/**
 * A single chat: a scrolling list of bubbles (outgoing right, incoming left) over
 * a message-entry row. Reached from the chat list via a nav route carrying acc +
 * jid. Live <Received>/<Sent>/<Patch> events flow in through the repository, so
 * new messages appear without a manual refresh; the list auto-scrolls to newest.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    acc: String,
    jid: String,
    client: TackyClient,
    peerName: String,
    avatars: AvatarCache,
    files: FileCache,
    notifications: Notifications,
    onBack: () -> Unit,
    onOpenKeys: () -> Unit,
    onStartCall: () -> Unit,
) {
    val vm: ConversationViewModel =
        viewModel(factory = ConversationViewModel.factory(acc, jid, client))
    // Mark this chat visible while it's on screen so its messages don't ring, and
    // clear any pending notification for it (on open and on each fresh message).
    DisposableEffect(acc, jid) {
        val key = ChatKey(acc, jid)
        ChatPresence.visibleChat = key
        notifications.clearChat(key)
        onDispose { if (ChatPresence.visibleChat == key) ChatPresence.visibleChat = null }
    }
    // Asks for the mic only on tap, then places the call (no-op if denied).
    val startCall = rememberMicGatedAction(onStartCall)
    val messages by vm.messages.collectAsStateWithLifecycle()
    val atTail by vm.atTail.collectAsStateWithLifecycle()
    val authors by vm.authors.collectAsStateWithLifecycle()
    val encrypted by vm.encrypted.collectAsStateWithLifecycle()
    val highlight by vm.highlight.collectAsStateWithLifecycle()

    // Sender display name: author map keyed by stored from_jid, falling back to
    // the resource (MUC nick) then the from_jid - mirrors enrich_store_message.
    val resolveName: (Message) -> String = { msg ->
        authors[msg.from_jid]
            ?: msg.from_resource.ifEmpty { msg.from_jid }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton(onBack) },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(rememberAvatar(avatars, acc, jid), size = 32.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            peerName.ifEmpty { jid },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    // Audio call: 1:1 only (no group calling). Starts an outgoing
                    // call; the app-root CallOverlay then drives the UI.
                    if (!vm.isMuc) {
                        IconButton(onClick = startCall) {
                            Icon(painterResource(R.drawable.ic_call), contentDescription = "Audio call")
                        }
                    }
                    // OMEMO lock: 1:1 only (desktop hides it for MUC). Closed +
                    // tinted when on, open + muted when off.
                    if (!vm.isMuc) {
                        IconButton(onClick = { vm.setEncrypted(!encrypted) }) {
                            Icon(
                                if (encrypted) painterResource(R.drawable.ic_lock) else painterResource(R.drawable.ic_lock_open),
                                contentDescription =
                                    if (encrypted) "Encryption on" else "Encryption off",
                                tint = if (encrypted) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                    var menuOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Encryption keys") },
                            onClick = {
                                menuOpen = false
                                onOpenKeys()
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            ConversationContent(
                messages = messages,
                atTail = atTail,
                highlight = highlight,
                onLoadOlder = vm::loadOlder,
                onLoadNewer = vm::loadNewer,
                onJumpToBottom = vm::jumpToBottom,
                onSend = vm::send,
                onReplyJump = { msg -> vm.gotoReply(msg.reply_id, msg.reply_to) },
                onHighlightShown = vm::clearHighlight,
                resolveName = resolveName,
                avatarSlot = { msg ->
                    Avatar(rememberAvatar(avatars, acc, msg.from_jid), size = 32.dp)
                },
                attachmentSlot = { msg ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        msg.attachments.forEach { AttachmentView(files, acc, it) }
                    }
                },
            )
        }
    }
}

/**
 * Stateless conversation UI: the bubble list with scroll-driven paging. All state
 * comes in as params and all backend intent goes out as callbacks, so it renders
 * (and its paging/scroll behaviour tests) with no ViewModel or client.
 */
@Composable
fun ConversationContent(
    messages: List<Message>,
    atTail: Boolean,
    onLoadOlder: () -> Unit,
    onLoadNewer: () -> Unit,
    onJumpToBottom: () -> Unit,
    onSend: (String) -> Unit,
    resolveName: (Message) -> String = { it.from_jid },
    avatarSlot: @Composable (Message) -> Unit = {},
    attachmentSlot: @Composable (Message) -> Unit = {},
    highlight: Long? = null,
    onReplyJump: (Message) -> Unit = {},
    onHighlightShown: () -> Unit = {},
) {
    val listState = rememberLazyListState()

    // Viewport state: is the newest row on screen (user parked at the bottom)?
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 1
        }
    }
    val newestTs = messages.lastOrNull()?.timestamp
    var seeded by remember { mutableStateOf(false) }

    // Seed (initial open / jump-to-latest): force to the bottom once the fresh
    // page lands. load()/jumpToBottom() clear the list first, so the empty ->
    // populated edge is the reliable trigger - unconditional, since at open the
    // list has often already laid out at the top and atBottom would read false.
    LaunchedEffect(messages.isEmpty()) {
        if (messages.isEmpty()) seeded = false
        else if (!seeded) {
            seeded = true
            listState.scrollToItem(messages.size - 1)
        }
    }

    // Follow the tail on a *newer* message (key = newest stamp, so a prepended
    // older page doesn't trigger it), but only while parked at the bottom. A
    // prepend keeps its place via LazyColumn's stable-key (timestamp) anchoring.
    LaunchedEffect(newestTs) {
        if (seeded && atBottom && newestTs != null) listState.scrollToItem(messages.size - 1)
    }

    // A reply jump lands a new slice plus the target's timestamp: scroll it into
    // view, let the bubble flash, then clear so the highlight doesn't persist. The
    // slice replaces a non-empty list, so the seed effect above stays put.
    LaunchedEffect(highlight) {
        val ts = highlight ?: return@LaunchedEffect
        val idx = messages.indexOfFirst { it.timestamp == ts }
        if (idx >= 0) listState.scrollToItem(idx)
        delay(1500)
        onHighlightShown()
    }

    // Edge paging + auto-fill. snapshotFlow re-fires on every scroll AND every
    // content change, so it keeps paging until the viewport fills or the archive
    // end is reached - a transition-keyed effect never re-fires when a short chat
    // sits perpetually at the top. Auto-fill (when the list doesn't fill the
    // viewport) covers the backend returning only a small local tail; the
    // repository's in-flight / exhausted guards serialize the requests.
    LaunchedEffect(seeded) {
        if (!seeded) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            PageProbe(
                first = listState.firstVisibleItemIndex,
                lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1,
                total = info.totalItemsCount,
                scrollable = info.visibleItemsInfo.size < info.totalItemsCount,
            )
        }.collect { p ->
            if (p.total > 0 && (p.first <= 2 || !p.scrollable)) onLoadOlder()
            if (p.scrollable && !atTail && p.lastVisible >= p.total - 3) onLoadNewer()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) {
                EmptyState(
                    icon = painterResource(R.drawable.ic_chat_bubble_outline),
                    text = "No messages yet",
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().testTag("messageList"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(messages, key = { it.timestamp }) { msg ->
                    MessageBubble(
                        msg,
                        resolveName,
                        avatarSlot,
                        attachmentSlot,
                        highlighted = msg.timestamp == highlight,
                        onReplyJump = onReplyJump,
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            if (!atTail) {
                FilledTonalButton(
                    onClick = onJumpToBottom,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                ) { Text("Jump to latest") }
            }
        }
        MessageEntry(onSend = onSend)
    }
}

/** Scroll/extent snapshot the paging effect reacts to. */
private data class PageProbe(
    val first: Int,
    val lastVisible: Int,
    val total: Int,
    val scrollable: Boolean,
)

@Composable
private fun MessageBubble(
    msg: Message,
    resolveName: (Message) -> String,
    avatarSlot: @Composable (Message) -> Unit,
    attachmentSlot: @Composable (Message) -> Unit,
    highlighted: Boolean = false,
    onReplyJump: (Message) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val outgoing = msg.is_outgoing
    // Flash the target of a reply jump: animate to a tint and back so the eye
    // catches it, then settle to the normal bubble colour once unhighlighted.
    val baseColor = if (outgoing) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant
    val bubbleColor by animateColorAsState(
        targetValue = if (highlighted) MaterialTheme.colorScheme.tertiaryContainer else baseColor,
        animationSpec = tween(500),
        label = "bubbleHighlight",
    )
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!outgoing) {
            avatarSlot(msg)
            Spacer(modifier = Modifier.width(8.dp))
        }
        Surface(
            color = bubbleColor,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.widthIn(max = 280.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                if (!outgoing) {
                    Text(
                        resolveName(msg),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (msg.reply_body.isNotEmpty()) {
                    // The quoted message: tap to jump to it (when it carries a
                    // resolvable reply target). Rendered as an inset quote bar.
                    val replyModifier = if (msg.reply_id.isNotEmpty()) {
                        Modifier.clickable { onReplyJump(msg) }
                    } else {
                        Modifier
                    }
                    Row(
                        modifier = replyModifier.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(28.dp)
                                .clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            msg.reply_body,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                attachmentSlot(msg)
                val text = bubbleText(msg)
                if (text.isNotEmpty()) {
                    Text(
                        formattedBody(
                            text,
                            msg.formatting,
                            quoteColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            linkColor = MaterialTheme.colorScheme.primary,
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                MessageFooter(msg)
            }
        }
    }
}

/** A trailing row: the send time, a lock marker on OMEMO messages, and the send
 *  status, so each message shows its time and (no lock = cleartext) at a glance. */
@Composable
private fun MessageFooter(msg: Message) {
    val encrypted = msg.encryption == "omemo"
    val time = formatMessageTime(msg.timestamp)
    if (!encrypted && time.isEmpty() && msg.server_status.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (encrypted) {
            Icon(
                painter = painterResource(R.drawable.ic_lock),
                contentDescription = "encrypted",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(13.dp),
            )
        }
        if (time.isNotEmpty()) {
            if (encrypted) Spacer(modifier = Modifier.width(4.dp))
            Text(
                time,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (msg.server_status.isNotEmpty()) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                msg.server_status,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor(msg.server_status),
            )
        }
    }
}

/**
 * The bubble's time-of-day label. The stored stamp is microseconds since epoch
 * (Message.timestamp); 0 means unstamped (e.g. a not-yet-confirmed local send),
 * which renders blank. Uses the device's 12/24-hour preference.
 */
@Composable
private fun formatMessageTime(micros: Long): String {
    if (micros <= 0) return ""
    val context = LocalContext.current
    val ms = micros / 1000
    return remember(ms) {
        android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(ms))
    }
}

/**
 * The text to show under a bubble. For attachment messages the backend supplies
 * `caption` (body with the redundant share URL stripped), so we render that and
 * never the raw body - otherwise an image/file whose body was only the share URL
 * would print the bare `aesgcm://`/`https://` link. Plain messages have no
 * caption and show their body. Mirrors the desktop GUI (gui/chat.tcl).
 */
private fun bubbleText(msg: Message): String =
    if (msg.attachments.isNotEmpty()) msg.caption else msg.body

private fun statusColor(status: String): Color =
    if (status == "failed") StatusRed else StatusGrey

@Composable
private fun MessageEntry(onSend: (String) -> Unit) {
    var body by remember { mutableStateOf("") }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = body,
            onValueChange = { body = it },
            placeholder = { Text("Message") },
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = {
                if (body.isNotBlank()) {
                    onSend(body)
                    body = ""
                }
            },
        ) { Text("Send") }
    }
}
