package com.sinxn.teledrive.ui.screens.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sinxn.teledrive.data.local.entity.FileEntity

@Composable
fun FileGridView(
    files: List<FileEntity>,
    onFileClick: (FileEntity) -> Unit,
    onDownloadClick: (FileEntity) -> Unit,
    onDeleteClick: (FileEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 100.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(files, key = { it.id }) { file ->
            FileItem(
                file = file,
                isGrid = true,
                onClick = { onFileClick(file) },
                onDownloadClick = { onDownloadClick(file) },
                onDeleteClick = { onDeleteClick(file) },
                modifier = Modifier.animateItem()
            )
        }
    }
}
