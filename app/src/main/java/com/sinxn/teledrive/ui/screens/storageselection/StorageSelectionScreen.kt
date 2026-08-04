package com.sinxn.teledrive.ui.screens.storageselection

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageSelectionScreen(
    onContinue: (isSyncMode: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StorageSelectionViewModel = hiltViewModel()
) {
    val selectedChannelName by viewModel.selectedChannelName.collectAsState()
    val selectedChannelKind by viewModel.selectedChannelKind.collectAsState()
    val selectedChannelPhotoPath by viewModel.selectedChannelPhotoPath.collectAsState()
    var showChannelPicker by remember { mutableStateOf(false) }
    var isCheckingVault by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Choose Storage") }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = {
                        isCheckingVault = true
                        errorMessage = null
                        viewModel.validateStorageLocation { validation ->
                            isCheckingVault = false
                            when (validation) {
                                is com.sinxn.teledrive.data.repository.StorageValidation.Empty -> {
                                    viewModel.saveSetupProgress(false)
                                    onContinue(false)
                                }
                                is com.sinxn.teledrive.data.repository.StorageValidation.ExistingVault -> {
                                    viewModel.saveSetupProgress(true)
                                    onContinue(true)
                                }
                                is com.sinxn.teledrive.data.repository.StorageValidation.Invalid -> {
                                    errorMessage = "This location contains existing non-TeleDrive messages. Please select an empty chat or a valid TeleDrive vault."
                                }
                            }
                        }
                    },
                    enabled = !isCheckingVault,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                        .navigationBarsPadding()
                ) {
                    if (isCheckingVault) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Continue")
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            StorageLocationAvatar(
                photoPath = selectedChannelPhotoPath,
                kind = selectedChannelKind,
                modifier = Modifier.size(72.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Where should TeleDrive store your files?",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Pick a Telegram chat, group, or channel. You can change this later in Settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        showChannelPicker = true
                        viewModel.loadChats("")
                    },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                ListItem(
                    headlineContent = { Text("Storage Location") },
                    supportingContent = { Text(selectedChannelName) },
                    leadingContent = {
                        StorageLocationAvatar(
                            photoPath = selectedChannelPhotoPath,
                            kind = selectedChannelKind,
                            modifier = Modifier.size(32.dp)
                        )
                    },
                    trailingContent = {
                        Icon(Icons.Default.ChevronRight, contentDescription = "Change")
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Spacer(modifier = Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "If the selected location contains an existing TeleDrive vault, the master password you set on the next screen must match the one used to encrypt those files.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }

            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = errorMessage!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }

        if (showChannelPicker) {
            StoragePickerBottomSheet(
                viewModel = viewModel,
                onDismiss = { showChannelPicker = false },
                onChannelSelected = { id, title, kind ->
                    viewModel.selectChannel(id, title, kind)
                    showChannelPicker = false
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StoragePickerBottomSheet(
    viewModel: StorageSelectionViewModel,
    onDismiss: () -> Unit,
    onChannelSelected: (Long, String, StorageLocationKind) -> Unit
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
                            supportingContent = { Text(chat.kind.label()) },
                            leadingContent = {
                                StorageLocationAvatar(
                                    photoPath = chat.photoPath,
                                    kind = chat.kind,
                                    modifier = Modifier.size(40.dp)
                                )
                            },
                            modifier = Modifier.clickable {
                                onChannelSelected(chat.id, chat.title, chat.kind)
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun StorageLocationAvatar(
    photoPath: String?,
    kind: StorageLocationKind,
    modifier: Modifier = Modifier
) {
    val bitmap = produceState<android.graphics.Bitmap?>(initialValue = null, photoPath) {
        value = if (photoPath.isNullOrBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    val file = File(photoPath)
                    if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
                }.getOrNull()
            }
        }
    }.value

    Surface(
        modifier = modifier.clip(MaterialTheme.shapes.extraLarge),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = kind.icon(),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(6.dp)
                )
            }
        }
    }
}
