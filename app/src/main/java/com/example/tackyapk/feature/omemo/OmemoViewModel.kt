package com.example.tackyapk.feature.omemo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/**
 * Thin state holder over [OmemoRepository] for the per-chat key screen. Loads the
 * trust list on init and forwards trust mutations carrying the screen's acc/jid.
 */
class OmemoViewModel(
    private val acc: String,
    private val jid: String,
    private val repo: OmemoRepository,
) : ViewModel() {
    val trustList = repo.trustList
    val blindTrust = repo.blindTrust
    val enabled = repo.enabled

    init {
        repo.load(acc, jid)
    }

    fun setTrust(device: Long, state: String) = repo.setTrust(acc, jid, device, state)

    fun setBlindTrust(value: Boolean) = repo.setBlindTrust(acc, value)

    fun setEnabled(value: Boolean) = repo.setEnabled(acc, jid, value)

    companion object {
        fun factory(acc: String, jid: String, repo: OmemoRepository) = viewModelFactory {
            initializer { OmemoViewModel(acc, jid, repo) }
        }
    }
}
