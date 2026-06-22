package com.example.tackyapk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.tackyapk.data.TackyRepository
import com.example.tackyapk.model.Account
import com.example.tackyapk.model.ConnState
import com.example.tackyapk.R

/**
 * The accounts list: one row per account (status dot, enable switch, Profile/Remove
 * menu) plus a FAB to add one. Tapping a row opens that account's chats; the app
 * stays account-scoped, so there's no merged conversation list.
 *
 * [onBack] is null when this is the root screen (no accounts yet, nothing to pop).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    repo: TackyRepository,
    onOpenChats: (String) -> Unit,
    onProfile: (String) -> Unit,
    onConsole: () -> Unit,
    onBack: (() -> Unit)?,
) {
    val vm: AccountsViewModel = viewModel(factory = AccountsViewModel.factory(repo))
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val conns by vm.connStates.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Accounts") },
                navigationIcon = { if (onBack != null) BackButton(onBack) },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Console") },
                            onClick = { menuOpen = false; onConsole() },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = "Add account")
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (accounts.isEmpty()) {
                EmptyState(
                    icon = painterResource(R.drawable.ic_account_circle),
                    text = "No accounts yet",
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(accounts, key = { it.jid }) { acc ->
                        AccountRow(
                            account = acc,
                            state = conns[acc.jid] ?: ConnState.DISCONNECTED,
                            onOpen = { onOpenChats(acc.jid) },
                            onToggle = { vm.setEnabled(acc.jid, it) },
                            onProfile = { onProfile(acc.jid) },
                            onRemove = { vm.remove(acc.jid) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddAccountDialog(
            onDismiss = { showAdd = false },
            onAdd = { jid, password -> showAdd = false; vm.add(jid, password) },
        )
    }
}

@Composable
private fun AccountRow(
    account: Account,
    state: ConnState,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onProfile: () -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(connStateColor(state)))
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(account.jid, style = MaterialTheme.typography.bodyLarge)
            Text(state.label, style = MaterialTheme.typography.bodySmall, color = connStateColor(state))
        }
        Switch(checked = account.enabled, onCheckedChange = onToggle)
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "Account options")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Profile") }, onClick = { menu = false; onProfile() })
                DropdownMenuItem(text = { Text("Remove") }, onClick = { menu = false; onRemove() })
            }
        }
    }
}

@Composable
private fun AddAccountDialog(onDismiss: () -> Unit, onAdd: (jid: String, password: String) -> Unit) {
    var jid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val valid = jid.trim().let { it.contains('@') && !it.startsWith('@') && !it.endsWith('@') }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add account") },
        text = {
            Column {
                OutlinedTextField(
                    value = jid,
                    onValueChange = { jid = it },
                    label = { Text("JID (user@host)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onAdd(jid.trim(), password) }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
