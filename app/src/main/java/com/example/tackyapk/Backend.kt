package com.example.tackyapk

import com.example.tackyapk.model.str
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The two collectors every repository repeats: re-pull when the backend comes up,
 * and fold a module's events into state. Both [TackyRepository], [ChatListRepository],
 * the omemo/profile repos, etc. drive their StateFlows through these.
 */

/** Run [reload] every time the backend reports "running" (a request issued before
 *  it's up would never get a reply), swallowing failures. */
fun CoroutineScope.reloadOnRunning(state: StateFlow<String>, reload: suspend () -> Unit): Job =
    launch { state.collect { if (it == "running") runCatching { reload() } } }

/**
 * Collect [module]'s events. When [acc] is given, events whose injected `acc` is
 * for another account are dropped (every account shares the one event stream);
 * events that carry no `acc` always pass. [acc] is a lambda so it tracks the
 * repository's current account.
 */
fun CoroutineScope.collectModule(
    client: TackyClient,
    module: String,
    acc: (() -> String?)? = null,
    onEvent: (TackyClient.Event) -> Unit,
): Job = launch {
    client.events.collect { ev ->
        if (ev.module != module) return@collect
        if (acc != null) {
            val evAcc = (ev.args as? JsonObject)?.str("acc")
            if (evAcc != null && evAcc != acc()) return@collect
        }
        onEvent(ev)
    }
}
