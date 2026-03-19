package com.sinxn.teledrive.ui.screens.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sinxn.teledrive.data.local.entity.FileEntity

@Composable
fun FileListView(
    files: List<FileEntity>,
    downloadProgress: Map<String, Float>,
    activeDownloads: Set<String>,
    transferStatusText: Map<String, String>,
    onFileClick: (FileEntity) -> Unit,
    onDownloadClick: (FileEntity) -> Unit,
    onDeleteClick: (FileEntity) -> Unit,
    onRenameClick: (FileEntity) -> Unit,
    onMoveClick: (FileEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(files, key = { it.id }) { file ->
            FileItem(
                file = file,
                transferProgress = downloadProgress[file.id] ?: file.uploadProgress,
                isTransferActive = activeDownloads.contains(file.id),
                transferStatus = transferStatusText[file.id],
                isGrid = false,
                onClick = { onFileClick(file) },
                onDownloadClick = { onDownloadClick(file) },
                onDeleteClick = { onDeleteClick(file) },
                onRenameClick = { onRenameClick(file) },
                onMoveClick = { onMoveClick(file) }
            )
        }
    }
}
