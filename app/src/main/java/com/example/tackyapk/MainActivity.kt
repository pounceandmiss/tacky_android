package com.example.tackyapk

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import com.example.tackyapk.feature.calls.CallOverlay
import com.example.tackyapk.feature.notifications.ChatKey
import com.example.tackyapk.feature.notifications.Notifications
import com.example.tackyapk.feature.chatlist.StartConversationScreen
import com.example.tackyapk.feature.conversation.ConversationScreen
import com.example.tackyapk.feature.omemo.OmemoPeerScreen
import com.example.tackyapk.feature.profile.ProfileScreen
import com.example.tackyapk.nav.BackstackHost
import com.example.tackyapk.nav.Screen
import com.example.tackyapk.nav.rememberBackstack
import com.example.tackyapk.ui.AccountsScreen
import com.example.tackyapk.ui.ConsoleScreen
import com.example.tackyapk.ui.HomeScreen
import com.example.tackyapk.ui.theme.TackyTheme

/**
 * A thin Compose host. The backend client lives at Application scope ([TackyApp]),
 * so the Activity just observes [TackyApp.deps] and renders the nav graph - an
 * Activity recreation never tears the client down.
 */
class MainActivity : ComponentActivity() {
    // The latest launch/notification intent, observed by the nav graph so a
    // notification deep link (open a chat, answer a call) can act once deps exist.
    private val pendingIntent = MutableStateFlow<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        // RECORD_AUDIO is requested just-in-time when a call is started or
        // answered (see rememberMicGatedAction), not eagerly at launch.
        pendingIntent.value = intent

        val app = application as TackyApp
        setContent {
            TackyTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val deps by app.deps.collectAsStateWithLifecycle()
                    AppNav(deps, pendingIntent) { pendingIntent.value = null }
                }
            }
        }
    }

    // launchMode=singleTop: a notification tap re-delivers here instead of a new
    // task, so re-publish the intent for the nav graph to consume.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingIntent.value = intent
    }
}

@Composable
private fun AppNav(
    deps: AppDeps?,
    intents: StateFlow<Intent?>,
    onIntentHandled: () -> Unit,
) {
    // A plain developer-owned back stack: the host renders only the top entry, so
    // it can never paint an empty container, and popping past the root simply lets
    // system back finish the Activity. Each branch falls back to Loading() until
    // `deps` is ready, so a cold-start null window shows a spinner, never a blank.
    val backstack = rememberBackstack(Screen.Loading)
    val intent by intents.collectAsStateWithLifecycle()
    // A pending "open chat" deep link suppresses the default start-screen pick so
    // the two don't race to reset the back stack; the effect below opens the chat.
    val hasChatDeepLink = intent?.action == Notifications.ACTION_OPEN_CHAT

    LaunchedEffect(intent, deps) {
        val i = intent ?: return@LaunchedEffect
        val d = deps ?: return@LaunchedEffect
        when (i.action) {
            Notifications.ACTION_OPEN_CHAT -> {
                d.state.first { it == "running" }
                val acc = i.getStringExtra(Notifications.EXTRA_ACC)
                val jid = i.getStringExtra(Notifications.EXTRA_JID)
                if (acc != null && jid != null) {
                    val name = i.getStringExtra(Notifications.EXTRA_NAME) ?: jid
                    d.notifications.clearChat(ChatKey(acc, jid))
                    backstack.resetTo(Screen.ChatList(acc))
                    backstack.push(Screen.Conversation(acc, jid, name))
                }
            }
            // The mic-gated answer runs in CallOverlay, which is always composed.
            Notifications.ACTION_ANSWER -> d.calls.requestAnswer()
            // ACTION_OPEN_CALL just brings the app forward; the overlay shows itself.
        }
        onIntentHandled()
    }

    Box(modifier = Modifier.fillMaxSize()) {
    BackstackHost(backstack) { screen ->
        when (screen) {
            // Decide the start screen once the backend is up and the account list
            // has loaded - the first account's chats, or onboarding if there are
            // none. The chosen account then rides as a typed arg (single source of
            // truth), so a transient empty list can't bounce back to onboarding.
            Screen.Loading -> {
                if (deps != null && !hasChatDeepLink) {
                    LaunchedEffect(deps) {
                        deps.state.first { it == "running" }
                        runCatching { deps.repo.refreshAccounts() }
                        val first = deps.repo.accounts.value.firstOrNull()?.jid
                        backstack.resetTo(
                            if (first != null) Screen.ChatList(first) else Screen.Accounts,
                        )
                    }
                }
                Loading()
            }
            is Screen.ChatList -> {
                val d = deps ?: return@BackstackHost Loading()
                HomeScreen(
                    deps = d,
                    acc = screen.acc,
                    onStartConversation = { backstack.push(Screen.StartConversation(screen.acc)) },
                    onOpenChat = { a, jid, name ->
                        backstack.push(Screen.Conversation(a, jid, name))
                    },
                    onConsole = { backstack.push(Screen.Console) },
                    onAccounts = { backstack.push(Screen.Accounts) },
                )
            }
            is Screen.StartConversation -> {
                val d = deps ?: return@BackstackHost Loading()
                StartConversationScreen(
                    client = d.client,
                    avatars = d.avatars,
                    acc = screen.acc,
                    onOpenChat = { a, jid, name ->
                        backstack.push(Screen.Conversation(a, jid, name))
                    },
                    onBack = { backstack.pop() },
                )
            }
            Screen.Accounts -> {
                val d = deps ?: return@BackstackHost Loading()
                AccountsScreen(
                    repo = d.repo,
                    onOpenChats = { acc -> backstack.resetTo(Screen.ChatList(acc)) },
                    onProfile = { acc -> backstack.push(Screen.Profile(acc)) },
                    onConsole = { backstack.push(Screen.Console) },
                    // Null when Accounts is the start screen (no accounts yet) - there
                    // is nothing under it to pop back to.
                    onBack = if (backstack.canPop) ({ backstack.pop() }) else null,
                )
            }
            is Screen.Conversation -> {
                val d = deps ?: return@BackstackHost Loading()
                // The conversation repo is owned by the screen's ViewModel, scoped
                // per back-stack entry by BackstackHost, so each open chat keeps
                // its own messages.
                ConversationScreen(
                    acc = screen.acc,
                    jid = screen.jid,
                    client = d.client,
                    peerName = screen.name,
                    avatars = d.avatars,
                    files = d.files,
                    notifications = d.notifications,
                    onBack = { backstack.pop() },
                    onOpenKeys = { backstack.push(Screen.OmemoPeer(screen.acc, screen.jid)) },
                    onStartCall = { d.calls.start(screen.acc, screen.jid) },
                )
            }
            is Screen.Profile -> {
                val d = deps ?: return@BackstackHost Loading()
                ProfileScreen(screen.acc, d.profile, d.avatars, onBack = { backstack.pop() })
            }
            is Screen.OmemoPeer -> {
                val d = deps ?: return@BackstackHost Loading()
                OmemoPeerScreen(screen.acc, screen.jid, d.omemo, onBack = { backstack.pop() })
            }
            Screen.Console -> {
                val d = deps ?: return@BackstackHost Loading()
                ConsoleScreen(d.client, d.state)
            }
        }
    }
        // Rendered above the back stack so an incoming call rings over any screen.
        if (deps != null) CallOverlay(deps.calls, deps.callAudio, deps.avatars)
    }
}

@Composable
private fun Loading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("connecting to backend...")
    }
}
