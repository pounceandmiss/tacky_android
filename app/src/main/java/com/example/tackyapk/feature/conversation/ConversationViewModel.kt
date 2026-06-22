package com.example.tackyapk.feature.conversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.tackyapk.TackyClient
import com.example.tackyapk.feature.omemo.OmemoRepository

/**
 * Single-chat screen state holder, following the AccountsViewModel pattern. It
 * owns its [ConversationRepository] (built on viewModelScope), so the repo's
 * lifetime matches the nav back-stack entry - each open chat gets its own, and
 * back-navigating to an earlier chat keeps showing that chat's messages.
 *
 * Sending is fire-and-forget (notify); the new row arrives back through the
 * repository's flow as a <Sent> event.
 */
class ConversationViewModel(
    private val acc: String,
    private val jid: String,
    client: TackyClient,
) : ViewModel() {
    private val repo = ConversationRepository(client, viewModelScope)
    val messages = repo.messages
    val atTail = repo.atTail
    val highlight = repo.highlight

    private val authorCache = AuthorCache(client, viewModelScope)
    val authors = authorCache.names

    // Per-chat OMEMO toggle (desktop's lock checkbutton). 1:1 only; encryption
    // for MUC isn't offered, matching the desktop GUI.
    private val omemo = OmemoRepository(client, viewModelScope)
    val encrypted = omemo.enabled
    val isMuc = jid.endsWith("?join")

    init {
        repo.load(acc, jid)
        authorCache.load(acc, jid)
        if (!isMuc) omemo.load(acc, jid)
    }

    fun send(body: String) {
        if (body.isNotBlank()) repo.send(acc, jid, body)
    }

    fun setEncrypted(value: Boolean) = omemo.setEnabled(acc, jid, value)

    fun loadOlder() = repo.loadOlder()

    fun loadNewer() = repo.loadNewer()

    fun jumpToBottom() = repo.jumpToBottom()

    fun gotoReply(replyId: String, replyTo: String) = repo.gotoReply(replyId, replyTo)

    fun clearHighlight() = repo.clearHighlight()

    companion object {
        fun factory(acc: String, jid: String, client: TackyClient) = viewModelFactory {
            initializer { ConversationViewModel(acc, jid, client) }
        }
    }
}
