package com.example.tackyapk.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/**
 * Profile-screen state holder, thin over [ProfileRepository] (mirrors
 * ConversationViewModel). The repo outlives the screen, so this just points it at
 * [acc] and re-exposes its flows; mutations forward to the repo as notify writes.
 */
class ProfileViewModel(
    private val acc: String,
    private val repo: ProfileRepository,
) : ViewModel() {
    val displayName = repo.displayName
    val ownFingerprint = repo.ownFingerprint
    val deviceId = repo.deviceId
    val ownDevices = repo.ownDevices
    val blindTrust = repo.blindTrust

    init {
        repo.load(acc)
    }

    fun setDisplayName(name: String) = repo.setDisplayName(acc, name)

    fun setBlindTrust(value: Boolean) = repo.setBlindTrust(acc, value)

    fun setOwnDeviceTrust(device: Long, state: String) = repo.setOwnDeviceTrust(acc, device, state)

    companion object {
        fun factory(acc: String, repo: ProfileRepository) = viewModelFactory {
            initializer { ProfileViewModel(acc, repo) }
        }
    }
}
