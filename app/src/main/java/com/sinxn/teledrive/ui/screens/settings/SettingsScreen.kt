package com.sinxn.teledrive.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val selectedChannelName by viewModel.selectedChannelName.collectAsState()
    val authOnLaunch by viewModel.authOnLaunch.collectAsState()
    val biometricUnlock by viewModel.biometricUnlock.collectAsState()
    var showChannelPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            ListItem(
                headlineContent = { Text("Storage Location") },
                supportingContent = { Text(selectedChannelName) },
                leadingContent = {
                    Icon(Icons.Default.Storage, contentDescription = "Storage Location")
                },
                modifier = Modifier.clickable {
                    showChannelPicker = true
                    viewModel.loadChats("")
                }
            )

            HorizontalDivider()

            ListItem(
                headlineContent = { Text("Require authentication on launch") },
                supportingContent = { Text("Ask for password or biometric on every app launch") },
                leadingContent = {
                    Icon(Icons.Default.Security, contentDescription = null)
                },
                trailingContent = {
                    Switch(
                        checked = authOnLaunch,
                        onCheckedChange = { viewModel.setAuthOnLaunch(it) }
                    )
                }
            )

            if (authOnLaunch) {
                ListItem(
                    headlineContent = { Text("Unlock with face or fingerprint") },
                    supportingContent = { Text("Use device biometrics instead of typing password") },
                    leadingContent = {
                        Icon(Icons.Default.Fingerprint, contentDescription = null)
                    },
                    trailingContent = {
                        Switch(
                            checked = biometricUnlock,
                            onCheckedChange = { viewModel.setBiometricUnlock(it) }
                        )
                    }
                )
            }
        }

        if (showChannelPicker) {
            ChannelPickerBottomSheet(
                viewModel = viewModel,
                onDismiss = { showChannelPicker = false },
                onChannelSelected = { id, title ->
                    viewModel.selectChannel(id, title)
                    showChannelPicker = false
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelPickerBottomSheet(
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit,
    onChannelSelected: (Long, String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val chats by viewModel.chats.collectAsState()
    val isLoading by viewModel.isSearchLoading.collectAsState()
    var searchQuery by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = "Select Storage Location",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { query ->
                    searchQuery = query
                    viewModel.loadChats(query)
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search chats...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (isLoading && chats.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(chats, key = { it.id }) { chat ->
                        ListItem(
                            headlineContent = { Text(chat.title) },
                            supportingContent = { 
                                if (chat.id == 0L) Text("Your private cloud")
                                else if (chat.isChannel) Text("Channel")
                                else Text("Group/Chat")
                            },
                            modifier = Modifier.clickable {
                                onChannelSelected(chat.id, chat.title)
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
