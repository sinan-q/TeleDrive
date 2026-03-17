package com.sinxn.teledrive.ui.screens.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import com.sinxn.teledrive.ui.screens.home.components.FileListView
import com.sinxn.teledrive.ui.screens.home.components.FileGridView
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToFolder: (String) -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val isGridView by viewModel.isGridView.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TeleDrive") },
                actions = {
                    IconButton(onClick = { viewModel.toggleViewMode() }) {
                        Icon(
                            imageVector = if (isGridView) Icons.Default.List else Icons.Default.GridView,
                            contentDescription = "Toggle View"
                        )
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (val state = uiState) {
                is HomeUiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                is HomeUiState.Error -> {
                    Text(
                        text = "Error: ${state.message}",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center).padding(16.dp)
                    )
                }
                is HomeUiState.Success -> {
                    if (state.files.isEmpty()) {
                        Text(
                            text = "No saved files found.",
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else {
                        AnimatedContent(targetState = isGridView, label = "view_toggle") { gridMode ->
                            if (gridMode) {
                                FileGridView(
                                    files = state.files,
                                    onFileClick = { file ->
                                        if (file.type == "folder") onNavigateToFolder(file.id)
                                    }
                                )
                            } else {
                                FileListView(
                                    files = state.files,
                                    onFileClick = { file ->
                                        if (file.type == "folder") onNavigateToFolder(file.id)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
