package com.sinxn.teledrive.ui.screens.sync

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sinxn.teledrive.data.model.SyncStatus

@Composable
fun SyncProgressScreen(
    onContinue: () -> Unit,
    viewModel: SyncProgressViewModel = hiltViewModel()
) {
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AnimatedContent(targetState = syncStatus, label = "SyncStatus") { status ->
                when (status) {
                    is SyncStatus.Idle, is SyncStatus.Syncing -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(64.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                text = "Importing Vault Metadata...",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Please wait while we load your encrypted drive metadata.",
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }

                    is SyncStatus.MetadataImported -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Metadata Imported",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                text = "Metadata Imported!",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Your vault layout has been successfully restored from your storage channel.",
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(24.dp))

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("Imported Vault Summary", style = MaterialTheme.typography.labelLarge)
                                    Spacer(modifier = Modifier.height(12.dp))
                                    DetailRow("Files Restored", status.metrics.filesImported.toString())
                                    DetailRow("Folders Restored", status.metrics.foldersImported.toString())
                                }
                            }

                            Spacer(modifier = Modifier.height(32.dp))
                            Button(
                                onClick = onContinue,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Continue to App")
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = { viewModel.crossVerify() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Cross-Verify with Telegram Storage")
                            }
                        }
                    }

                    is SyncStatus.Reconciling -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(64.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                text = "Cross-Verifying with Telegram...",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Scanned ${status.metrics.messagesScanned} messages...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Scanning chat history to find any orphaned files or missing uploads.",
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }

                    is SyncStatus.Completed -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Verification Complete",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                text = "Verification Complete!",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Telegram storage cross-verification complete.",
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))

                            // Diagnostic stats
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("Diagnostic Info", style = MaterialTheme.typography.labelLarge)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    DetailRow("Messages Scanned", status.metrics.messagesScanned.toString())
                                    DetailRow("Files Restored", status.metrics.filesImported.toString())
                                    DetailRow("Folders Restored", status.metrics.foldersImported.toString())

                                    if (status.metrics.additionalFiles.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text("Orphaned Files (in TG, not in Metadata):", style = MaterialTheme.typography.labelMedium)
                                        status.metrics.additionalFiles.take(15).forEach { name ->
                                            Text(
                                                text = "• $name",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                        }
                                        if (status.metrics.additionalFiles.size > 15) {
                                            Text("...and ${status.metrics.additionalFiles.size - 15} more", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }

                                    if (status.metrics.deletedFiles.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text("Deleted Files (in Metadata, not in TG):", style = MaterialTheme.typography.labelMedium)
                                        status.metrics.deletedFiles.take(15).forEach { name ->
                                            Text(
                                                text = "• $name",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                        if (status.metrics.deletedFiles.size > 15) {
                                            Text("...and ${status.metrics.deletedFiles.size - 15} more", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(32.dp))
                            Button(
                                onClick = onContinue,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Continue")
                            }
                        }
                    }

                    is SyncStatus.Error -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Sync Error",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                text = "Sync Failed",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = status.message,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(32.dp))
                            Button(
                                onClick = { viewModel.startSync() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Retry Sync")
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = onContinue,
                                modifier = Modifier.fillMaxWidth(),
                                colors = androidx.compose.material3.ButtonDefaults.textButtonColors()
                            ) {
                                Text("Skip for now")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
    }
}

