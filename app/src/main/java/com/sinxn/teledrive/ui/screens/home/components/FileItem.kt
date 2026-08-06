package com.sinxn.teledrive.ui.screens.home.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sinxn.teledrive.data.local.entity.FileEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FileItem(
    file: FileEntity,
    transferProgress: Float?,
    isTransferActive: Boolean,
    transferStatus: String?,
    isGrid: Boolean,
    onClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onRenameClick: () -> Unit,
    onMoveClick: () -> Unit,
    onReplaceClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    val icon = getIconForMimeType(file)
    val sizeText = formatFileSize(file.size)
    val uploadedDateText = formatUploadedDate(file.uploadedAt)

    if (isGrid) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = icon.first,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = icon.second
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    if (file.type == "file") {
                        if (isTransferActive && (transferProgress == null || transferProgress <= 0f)) {
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                            transferStatus?.let {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else if (transferProgress != null && transferProgress < 1.0f) {
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { transferProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                            transferStatus?.let {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = sizeText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                uploadedDateText?.let { dateText ->
                                    Text(
                                        text = dateText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                Box(modifier = Modifier.align(Alignment.TopEnd)) {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More Options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        if (file.type == "file") {
                            DropdownMenuItem(
                                text = { Text("Download") },
                                onClick = {
                                    showMenu = false
                                    onDownloadClick()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Replace") },
                                onClick = {
                                    showMenu = false
                                    onReplaceClick()
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            onClick = {
                                showMenu = false
                                onRenameClick()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Move") },
                            onClick = {
                                showMenu = false
                                onMoveClick()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            onClick = {
                                showMenu = false
                                onDeleteClick()
                            }
                        )
                    }
                }
            }
        }
    } else {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon.first,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = icon.second
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (file.type == "file") {
                    if (isTransferActive && (transferProgress == null || transferProgress <= 0f)) {
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        transferStatus?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    } else if (transferProgress != null && transferProgress < 1.0f) {
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { transferProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        transferStatus?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    } else {
                        Text(
                            text = buildMetadataLine(sizeText, uploadedDateText, file.mimeType),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More Options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    if (file.type == "file") {
                        DropdownMenuItem(
                            text = { Text("Download") },
                            onClick = {
                                showMenu = false
                                onDownloadClick()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Replace") },
                            onClick = {
                                showMenu = false
                                onReplaceClick()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = {
                            showMenu = false
                            onRenameClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Move") },
                        onClick = {
                            showMenu = false
                            onMoveClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = {
                            showMenu = false
                            onDeleteClick()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun getIconForMimeType(file: FileEntity): Pair<ImageVector, Color> {
    if (file.type == "folder") return Pair(Icons.Default.Folder, MaterialTheme.colorScheme.primary)

    val mimeType = file.mimeType ?: ""
    return when {
        mimeType.startsWith("image/") -> Pair(Icons.Default.Image, Color(0xFF4CAF50))
        mimeType.startsWith("video/") -> Pair(Icons.Default.VideoFile, Color(0xFFF44336))
        mimeType.startsWith("audio/") -> Pair(Icons.Default.AudioFile, Color(0xFFFF9800))
        mimeType == "application/pdf" -> Pair(Icons.Default.PictureAsPdf, Color(0xFFB71C1C))
        mimeType.contains("word") || mimeType.contains("document") -> {
            Pair(Icons.Default.Description, Color(0xFF2196F3))
        }
        else -> Pair(Icons.Default.InsertDriveFile, MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatFileSize(size: Long?): String {
    if (size == null || size == 0L) return "Unknown"
    if (size < 1024) return "$size B"
    val sizeBucket = (63 - java.lang.Long.numberOfLeadingZeros(size)) / 10
    return String.format(
        Locale.getDefault(),
        "%.1f %sB",
        size.toDouble() / (1L shl (sizeBucket * 10)),
        " KMGTPE"[sizeBucket]
    )
}

private fun formatUploadedDate(uploadedAt: Long?): String? {
    if (uploadedAt == null || uploadedAt <= 0L) return null
    return SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(uploadedAt))
}

private fun buildMetadataLine(sizeText: String, uploadedDateText: String?, mimeType: String?): String {
    val parts = mutableListOf(sizeText)
    uploadedDateText?.let(parts::add)
    parts.add(mimeType ?: "Unknown")
    return parts.joinToString(" | ")
}
