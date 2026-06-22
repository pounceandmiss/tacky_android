package com.example.tackyapk.feature.chatlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/**
 * Chat-list screen state holder, following the exemplar ViewModel pattern: thin
 * over the repository, exposing its flow and forwarding the account selection.
 * The repository owns the list, so [load] just hands it the account and updates
 * flow back through [chatList].
 */
class ChatListViewModel(private val repo: ChatListRepository) : ViewModel() {
    val chatList = repo.chatList

    fun load(acc: String) = repo.load(acc)

    companion object {
        fun factory(repo: ChatListRepository) = viewModelFactory {
            initializer { ChatListViewModel(repo) }
        }
    }
}
