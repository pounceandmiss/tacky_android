package com.example.tackyapk.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.tackyapk.AvatarCache
import com.example.tackyapk.feature.omemo.OmemoDeviceRow
import com.example.tackyapk.feature.omemo.TrustEntry
import com.example.tackyapk.rememberAvatar
import com.example.tackyapk.ui.Avatar
import com.example.tackyapk.ui.BackButton
import com.example.tackyapk.util.formatFingerprint

/**
 * Own-account profile: avatar, editable display name, the account JID, and an
 * Encryption section (account-wide blind-trust toggle + this account's own OMEMO
 * devices). Builds its ViewModel over the shared [repo] and renders the stateless
 * [ProfileContent]; the avatar is passed in as a composable slot so the content
 * stays client-free for tests.
 */
@Composable
fun ProfileScreen(
    acc: String,
    repo: ProfileRepository,
    avatars: AvatarCache,
    onBack: () -> Unit,
) {
    val vm: ProfileViewModel = viewModel(factory = ProfileViewModel.factory(acc, repo))
    val displayName by vm.displayName.collectAsStateWithLifecycle()
    val ownFingerprint by vm.ownFingerprint.collectAsStateWithLifecycle()
    val deviceId by vm.deviceId.collectAsStateWithLifecycle()
    val ownDevices by vm.ownDevices.collectAsStateWithLifecycle()
    val blindTrust by vm.blindTrust.collectAsStateWithLifecycle()

    LaunchedEffect(acc) { repo.load(acc) }

    ProfileContent(
        acc = acc,
        displayName = displayName,
        onSaveName = vm::setDisplayName,
        ownFingerprint = ownFingerprint,
        deviceId = deviceId,
        ownDevices = ownDevices,
        blindTrust = blindTrust,
        onSetBlindTrust = vm::setBlindTrust,
        onSetOwnDeviceTrust = vm::setOwnDeviceTrust,
        avatarSlot = { Avatar(rememberAvatar(avatars, acc, acc), size = 96.dp) },
        onBack = onBack,
    )
}

/**
 * Stateless profile UI: all state comes in as params, all intent goes out as
 * callbacks, and the avatar arrives as a composable slot, so it renders with no
 * ViewModel or client.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileContent(
    acc: String,
    displayName: String,
    onSaveName: (String) -> Unit,
    ownFingerprint: String,
    deviceId: Long,
    ownDevices: List<TrustEntry>,
    blindTrust: Boolean,
    onSetBlindTrust: (Boolean) -> Unit,
    onSetOwnDeviceTrust: (Long, String) -> Unit,
    avatarSlot: @Composable () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profile") },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner).padding(horizontal = 16.dp).testTag("profileList"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
                    avatarSlot()
                }
            }
            item { NameEditor(displayName, onSaveName) }
            item {
                Text(
                    acc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.testTag("accountJid"),
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }
            item {
                Text("Encryption", style = MaterialTheme.typography.titleMedium)
            }
            item { BlindTrustRow(blindTrust, onSetBlindTrust) }
            item {
                Text(
                    "This device",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            // This device's key comes from own_fingerprint, not the trust list
            // (which only holds the account's *other* devices).
            item { CurrentDeviceRow(ownFingerprint) }
            // The backend already excludes this device from the own trust list;
            // filter defensively in case it ever leaks in.
            val otherDevices = ownDevices.filter { it.device != deviceId }
            if (otherDevices.isNotEmpty()) {
                item {
                    Text(
                        "Other devices",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                items(otherDevices, key = { it.device }) { entry ->
                    OmemoDeviceRow(entry, onSetOwnDeviceTrust)
                }
            }
        }
    }
}

@Composable
private fun NameEditor(displayName: String, onSaveName: (String) -> Unit) {
    // Seed the field from the backend value and re-seed when it changes underneath
    // (e.g. a nick published from another client), but keep local edits otherwise.
    var name by remember(displayName) { mutableStateOf(displayName) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Display name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("displayName"),
        )
        Button(onClick = { onSaveName(name) }, modifier = Modifier.align(Alignment.End)) {
            Text("Save")
        }
    }
}

@Composable
private fun BlindTrustRow(blindTrust: Boolean, onSetBlindTrust: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Trust new devices automatically (blind trust)",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = blindTrust, onCheckedChange = onSetBlindTrust)
    }
}

@Composable
private fun CurrentDeviceRow(fingerprint: String) {
    if (fingerprint.isEmpty()) {
        Text(
            "OMEMO key not ready yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        return
    }
    Text(
        formatFingerprint(fingerprint),
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}
