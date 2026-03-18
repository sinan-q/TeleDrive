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
    onFileClick: (FileEntity) -> Unit,
    onDownloadClick: (FileEntity) -> Unit,
    onDeleteClick: (FileEntity) -> Unit,
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
                isGrid = false,
                onClick = { onFileClick(file) },
                onDownloadClick = { onDownloadClick(file) },
                onDeleteClick = { onDeleteClick(file) }
            )
        }
    }
}
