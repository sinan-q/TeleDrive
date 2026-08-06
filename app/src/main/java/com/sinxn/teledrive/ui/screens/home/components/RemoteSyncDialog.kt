package com.sinxn.teledrive.ui.screens.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sinxn.teledrive.ui.screens.home.RemoteSyncInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RemoteSyncDialog(
    syncInfo: RemoteSyncInfo,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
    val remoteDateStr = dateFormat.format(Date(syncInfo.remoteExportedAt))
    val localDateStr = syncInfo.localExportedAt?.let { dateFormat.format(Date(it)) } ?: "Never"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "Remote Vault Updated")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Newer metadata was found in your Telegram vault from another session or device.",
                    style = MaterialTheme.typography.bodyMedium
                )

                Text(
                    text = "• Remote: $remoteDateStr\n• Local: $localDateStr",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Changes detected:",
                    style = MaterialTheme.typography.labelLarge
                )

                if (syncInfo.diff.addedFiles.isNotEmpty()) {
                    Text(
                        text = "+ ${syncInfo.diff.addedFiles.size} added: ${syncInfo.diff.addedFiles.take(3).joinToString(", ")}${if (syncInfo.diff.addedFiles.size > 3) "..." else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (syncInfo.diff.modifiedFiles.isNotEmpty()) {
                    Text(
                        text = "• ${syncInfo.diff.modifiedFiles.size} updated: ${syncInfo.diff.modifiedFiles.take(3).joinToString(", ")}${if (syncInfo.diff.modifiedFiles.size > 3) "..." else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }

                if (syncInfo.diff.removedFiles.isNotEmpty()) {
                    Text(
                        text = "- ${syncInfo.diff.removedFiles.size} removed: ${syncInfo.diff.removedFiles.take(3).joinToString(", ")}${if (syncInfo.diff.removedFiles.size > 3) "..." else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Sync Now")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Not Now")
            }
        }
    )
}
