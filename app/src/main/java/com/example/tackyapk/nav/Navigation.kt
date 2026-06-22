package com.example.tackyapk.nav

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The set of screens the app can show. Args ride as typed fields - no string
 * routes, no Uri encoding. Serializable so the back stack survives process death.
 */
@Serializable
sealed interface Screen {
    @Serializable
    data object Loading : Screen

    @Serializable
    data class ChatList(val acc: String) : Screen

    @Serializable
    data object Accounts : Screen

    @Serializable
    data class StartConversation(val acc: String) : Screen

    @Serializable
    data class Conversation(val acc: String, val jid: String, val name: String) : Screen

    @Serializable
    data class Profile(val acc: String) : Screen

    @Serializable
    data class OmemoPeer(val acc: String, val jid: String) : Screen

    @Serializable
    data object Console : Screen
}

/** A back-stack slot. [id] is a per-push unique key scoping the entry's state. */
@Serializable
data class NavEntry(val id: String, val screen: Screen)

/**
 * A plain, developer-owned navigation back stack - the whole UI renders the top
 * entry only, so it never shows an empty container (the failure that blanked the
 * old NavHost). Popping past the root is a no-op; the host lets system back fall
 * through to finish the Activity.
 */
class Backstack(initial: List<NavEntry>) {
    val entries = mutableStateListOf<NavEntry>().also { it.addAll(initial) }
    private var nextId = entries.size

    val current: NavEntry get() = entries.last()
    val canPop: Boolean get() = entries.size > 1

    private fun entry(screen: Screen) = NavEntry("e${nextId++}", screen)

    /** Push a new screen. A repeat of the current top is ignored (dedupes double taps). */
    fun push(screen: Screen) {
        if (current.screen != screen) entries.add(entry(screen))
    }

    fun pop(): Boolean {
        if (!canPop) return false
        entries.removeAt(entries.lastIndex)
        return true
    }

    /** Clear the whole stack down to a single root (the old popUpTo-inclusive flows). */
    fun resetTo(screen: Screen) {
        entries.clear()
        entries.add(entry(screen))
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val listSerializer = ListSerializer(NavEntry.serializer())

        val Saver: Saver<Backstack, String> = Saver(
            save = { json.encodeToString(listSerializer, it.entries.toList()) },
            restore = { Backstack(json.decodeFromString(listSerializer, it)) },
        )
    }
}

@Composable
fun rememberBackstack(initial: Screen): Backstack =
    rememberSaveable(saver = Backstack.Saver) { Backstack(listOf(NavEntry("e0", initial))) }

private class EntryViewModelStoreOwner : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}

/**
 * Renders the top of [backstack], reproducing the per-entry scoping a NavHost
 * gives each destination: every entry gets its own [ViewModelStore] and saveable
 * state slot, so an off-top chat keeps its ViewModel (messages) + scroll position
 * and clears (`onCleared`) only when popped. ViewModels intentionally do not
 * outlive process death; screens refetch from the backend on recompose.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun BackstackHost(backstack: Backstack, content: @Composable (Screen) -> Unit) {
    BackHandler(enabled = backstack.canPop) { backstack.pop() }

    val holder = rememberSaveableStateHolder()
    val stores = remember { mutableMapOf<String, EntryViewModelStoreOwner>() }

    DisposableEffect(Unit) {
        onDispose { stores.values.forEach { it.viewModelStore.clear() } }
    }

    // Animate forward (push) or back (pop) by watching stack depth: a deeper (or
    // equal, e.g. resetTo) stack slides the new screen in from the right, a
    // shallower one reverses it.
    var prevDepth by remember { mutableStateOf(backstack.entries.size) }
    val forward = backstack.entries.size >= prevDepth
    SideEffect { prevDepth = backstack.entries.size }

    // updateTransition (not the bare AnimatedContent) so we can read currentState
    // vs targetState below and only tear an entry down once its exit has settled.
    val transition = updateTransition(backstack.current, label = "nav")
    transition.AnimatedContent(
        transitionSpec = {
            val dir = if (forward) 1 else -1
            (slideInHorizontally(tween(250)) { w -> dir * w } + fadeIn(tween(250)))
                .togetherWith(
                    slideOutHorizontally(tween(250)) { w -> -dir * w } + fadeOut(tween(250)),
                )
        },
        contentKey = { it.id },
    ) { target ->
        // Per-entry scoping (own ViewModelStore + saveable slot), reproducing what
        // a NavHost gives each destination. Keyed on the entry AnimatedContent is
        // currently drawing, so the outgoing screen keeps its store while it exits.
        val owner = stores.getOrPut(target.id) { EntryViewModelStoreOwner() }
        holder.SaveableStateProvider(target.id) {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                content(target.screen)
            }
        }
    }

    // Drop state for entries that have left the stack, but only once the animation
    // is idle - clearing a popped entry's ViewModelStore mid-exit would yank the
    // screen still animating out. Matches a NavHost's onCleared timing otherwise.
    if (transition.currentState == transition.targetState) {
        val live = backstack.entries.map { it.id }.toSet()
        for (id in stores.keys - live) {
            stores.remove(id)?.viewModelStore?.clear()
            holder.removeState(id)
        }
    }
}
