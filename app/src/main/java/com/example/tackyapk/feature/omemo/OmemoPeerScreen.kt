package com.example.tackyapk.feature.omemo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.tackyapk.ui.BackButton
import com.example.tackyapk.util.formatFingerprint
import com.example.tackyapk.R

/**
 * Per-chat OMEMO key management. Reached from a chat via a nav route carrying acc
 * + jid; builds the ViewModel and delegates to the stateless [OmemoKeysContent].
 * Live <TrustList>/<TrustChanged>/<BlindTrust> events refresh it through the repo.
 */
@Composable
fun OmemoPeerScreen(acc: String, jid: String, repo: OmemoRepository, onBack: () -> Unit) {
    val vm: OmemoViewModel = viewModel(factory = OmemoViewModel.factory(acc, jid, repo))
    val entries by vm.trustList.collectAsStateWithLifecycle()
    val blindTrust by vm.blindTrust.collectAsStateWithLifecycle()
    val enabled by vm.enabled.collectAsStateWithLifecycle()

    OmemoKeysContent(
        entries = entries,
        blindTrust = blindTrust,
        enabled = enabled,
        onSetTrust = vm::setTrust,
        onSetBlindTrust = vm::setBlindTrust,
        onSetEnabled = vm::setEnabled,
        onBack = onBack,
    )
}

/**
 * Stateless key-management UI: a per-chat encryption toggle, the account-wide
 * blind-trust switch, and the peer's devices, each with a fingerprint and a 3-way
 * trust selector. A compromised device is read-only (system-set) - it shows a
 * label and no controls. All intent leaves as callbacks so this renders and tests
 * with no ViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OmemoKeysContent(
    entries: List<TrustEntry>,
    blindTrust: Boolean,
    enabled: Boolean = true,
    onSetTrust: (Long, String) -> Unit,
    onSetBlindTrust: (Boolean) -> Unit,
    onSetEnabled: (Boolean) -> Unit = {},
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Encryption keys") },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            SwitchRow(
                label = "Encrypt messages in this chat (OMEMO)",
                checked = enabled,
                onCheckedChange = onSetEnabled,
                tag = "encryptSwitch",
            )
            HorizontalDivider()
            SwitchRow(
                label = "Trust new devices automatically",
                checked = blindTrust,
                onCheckedChange = onSetBlindTrust,
                tag = "blindTrustSwitch",
            )
            HorizontalDivider()
            if (entries.isEmpty()) {
                Text(
                    "No device keys known for this contact yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(16.dp),
                )
            }
            LazyColumn(modifier = Modifier.fillMaxSize().testTag("deviceList")) {
                items(entries, key = { it.device }) { entry ->
                    OmemoDeviceRow(entry, onSetTrust)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(tag),
        )
    }
}

/**
 * One device row: fingerprint, an active/inactive marker, and a 3-way trust
 * selector (or a read-only "compromised" label). Shared by the per-chat peer keys
 * and the profile's own-devices list (own other-devices are trusted with the same
 * call, jid = own bare jid), mirroring the desktop omemokeyspanel.
 */
@Composable
fun OmemoDeviceRow(entry: TrustEntry, onSetTrust: (Long, String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = formatFingerprint(entry.fingerprint),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
            if (!entry.active) {
                Text(
                    "(inactive)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            if (entry.trust == "compromised") {
                Text(
                    "Compromised - key changed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        // A compromised device is system-set and read-only: no selector.
        if (entry.trust != "compromised") {
            TrustSelector(entry, onSetTrust)
        }
    }
}

@Composable
private fun TrustSelector(entry: TrustEntry, onSetTrust: (Long, String) -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TrustOption("Trusted", "trusted", painterResource(R.drawable.ic_verified_user), MaterialTheme.colorScheme.primary, entry, onSetTrust)
        TrustOption("Not trusted", "untrusted", painterResource(R.drawable.ic_gpp_bad), MaterialTheme.colorScheme.error, entry, onSetTrust)
        TrustOption("Undecided", "undecided", painterResource(R.drawable.ic_help_outline), MaterialTheme.colorScheme.onSurfaceVariant, entry, onSetTrust)
    }
}

// A symbolic trust choice: good / bad / don't-know, with the label as both the
// accessibility description and a long-press tooltip (the row is too narrow for
// text labels). The selected choice is tinted in its semantic colour and filled;
// the rest are muted outlines.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrustOption(
    label: String,
    state: String,
    icon: Painter,
    selectedColor: Color,
    entry: TrustEntry,
    onSetTrust: (Long, String) -> Unit,
) {
    val selected = entry.trust == state
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(
            onClick = { onSetTrust(entry.device, state) },
            colors = if (selected) {
                IconButtonDefaults.filledIconButtonColors(
                    containerColor = selectedColor.copy(alpha = 0.15f),
                    contentColor = selectedColor,
                )
            } else {
                IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.outline)
            },
        ) {
            Icon(painter = icon, contentDescription = label)
        }
    }
}
