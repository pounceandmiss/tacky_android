package com.example.tackyapk.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.tackyapk.data.TackyRepository

/**
 * Accounts screen state holder - the exemplar ViewModel pattern the other feature
 * screens follow: thin over the repository, exposing its flows and forwarding
 * actions. The repository's mutating ops are fire-and-forget (notify), so these
 * are plain calls; state updates arrive back through the repository's flows.
 */
class AccountsViewModel(private val repo: TackyRepository) : ViewModel() {
    val accounts = repo.accounts
    val connStates = repo.connStates

    fun add(jid: String, password: String) = repo.addAccount(jid, password)
    fun setEnabled(jid: String, enabled: Boolean) =
        if (enabled) repo.enable(jid) else repo.disable(jid)
    fun remove(jid: String) = repo.remove(jid)

    companion object {
        fun factory(repo: TackyRepository) = viewModelFactory {
            initializer { AccountsViewModel(repo) }
        }
    }
}
