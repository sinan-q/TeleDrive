package com.sinxn.teledrive.ui.screens.home.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sinxn.teledrive.data.local.entity.FileEntity

@Composable
fun MoveToFolderDialog(
    folders: List<FileEntity>,
    currentParentId: String?,
    onDismissRequest: () -> Unit,
    onMoveToFolder: (String?) -> Unit
) {
    var selectedFolderId by remember { mutableStateOf(currentParentId) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = "Move to...") },
        text = {
            LazyColumn {
                // Root option
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedFolderId = null }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Home,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = if (selectedFolderId == null) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Root",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (selectedFolderId == null) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                items(folders, key = { it.id }) { folder ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedFolderId = folder.id }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = if (selectedFolderId == folder.id) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = folder.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (selectedFolderId == folder.id) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onMoveToFolder(selectedFolderId)
                    onDismissRequest()
                },
                enabled = selectedFolderId != currentParentId
            ) {
                Text("Move")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Cancel")
            }
        }
    )
}
