package com.sinxn.teledrive.ui.screens.settings

import android.graphics.BitmapFactory
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import com.sinxn.teledrive.data.local.entity.UserEntity
import com.sinxn.teledrive.ui.screens.storageselection.icon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToStorageSelection: () -> Unit,
    onNavigateToBackup: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val selectedChannelName by viewModel.selectedChannelName.collectAsState()
    val selectedChannelKind by viewModel.selectedChannelKind.collectAsState()
    val authOnLaunch by viewModel.authOnLaunch.collectAsState()
    val biometricUnlock by viewModel.biometricUnlock.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val fileFolderSummary by viewModel.fileFolderSummary.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.refreshStorageSelection()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            ConnectedProfileSection(
                user = currentUser,
                fileFolderSummary = fileFolderSummary,
                onLogout = {
                    viewModel.logout()
                    onLogout()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
            )

            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                )
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = { Text("Storage Location") },
                        supportingContent = { Text(selectedChannelName) },
                        leadingContent = {
                            Icon(selectedChannelKind.icon(), contentDescription = "Storage Location")
                        },
                        modifier = Modifier.clickable {
                            onNavigateToStorageSelection()
                        }
                    )

                    HorizontalDivider()

                    ListItem(
                        headlineContent = { Text("Backup & Restore") },
                        supportingContent = { Text("Export or restore your local TeleDrive metadata") },
                        leadingContent = {
                            Icon(androidx.compose.material.icons.Icons.Default.SettingsBackupRestore, contentDescription = null)
                        },
                        modifier = Modifier.clickable {
                            onNavigateToBackup()
                        }
                    )

                    HorizontalDivider()

                    ListItem(
                        headlineContent = { Text("Require authentication on launch") },
                        supportingContent = { Text("Ask for password or biometric on every app launch") },
                        leadingContent = {
                            Icon(androidx.compose.material.icons.Icons.Default.Security, contentDescription = null)
                        },
                        trailingContent = {
                            Switch(
                                checked = authOnLaunch,
                                onCheckedChange = { viewModel.setAuthOnLaunch(it) }
                            )
                        }
                    )

                    if (authOnLaunch) {
                        HorizontalDivider()

                        ListItem(
                            headlineContent = { Text("Unlock with face or fingerprint") },
                            supportingContent = { Text("Use device biometrics instead of typing password") },
                            leadingContent = {
                                Icon(androidx.compose.material.icons.Icons.Default.Fingerprint, contentDescription = null)
                            },
                            trailingContent = {
                                Switch(
                                    checked = biometricUnlock,
                                    onCheckedChange = { enabled ->
                                        if (!enabled) {
                                            viewModel.disableBiometricUnlock()
                                            return@Switch
                                        }

                                        val fragmentActivity = context as? FragmentActivity
                                        val cipher = viewModel.prepareBiometricEnrollmentCipher()
                                        if (fragmentActivity == null || cipher == null) {
                                            scope.launch {
                                                snackbarHostState.showSnackbar("Unlock the vault first to enable biometric unlock.")
                                            }
                                            return@Switch
                                        }

                                        val prompt = BiometricPrompt(
                                            fragmentActivity,
                                            ContextCompat.getMainExecutor(context),
                                            object : BiometricPrompt.AuthenticationCallback() {
                                                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                                    super.onAuthenticationSucceeded(result)
                                                    val didEnable = result.cryptoObject?.cipher?.let(viewModel::enableBiometricUnlock) == true
                                                    if (!didEnable) {
                                                        scope.launch {
                                                            snackbarHostState.showSnackbar("Could not enable biometric unlock.")
                                                        }
                                                    }
                                                }

                                                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                                                    super.onAuthenticationError(errorCode, errString)
                                                    scope.launch {
                                                        snackbarHostState.showSnackbar(errString.toString())
                                                    }
                                                }
                                            }
                                        )

                                        val promptInfo = BiometricPrompt.PromptInfo.Builder()
                                            .setTitle("Enable biometric unlock")
                                            .setSubtitle("Use your fingerprint or face to protect the stored unlock secret")
                                            .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)
                                            .setNegativeButtonText("Cancel")
                                            .build()

                                        prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
                                    }
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectedProfileSection(
    user: UserEntity?,
    fileFolderSummary: com.sinxn.teledrive.data.repository.FileFolderSummary,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    val profilePhotoPath = user?.photoPath

    ElevatedCard(
        modifier = modifier,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        )
    ) {
        val bitmap = produceState<android.graphics.Bitmap?>(initialValue = null, profilePhotoPath) {
            value = if (profilePhotoPath.isNullOrBlank()) {
                null
            } else {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val file = File(profilePhotoPath)
                        if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
                    }.getOrNull()
                }
            }
        }.value

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Connected profile",
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.Person,
                        contentDescription = "Connected profile",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            Text(
                text = user?.name?.takeIf { it.isNotBlank() } ?: "Connected account",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )

            val secondaryText = when {
                user != null && user.username.isNotBlank() -> "@${user.username}"
                user != null && !user.phone.isNullOrBlank() -> user.phone.orEmpty()
                else -> "Telegram"
            }

            Text(
                text = secondaryText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Text(
                text = "${fileFolderSummary.fileCount} files, ${fileFolderSummary.folderCount} folders",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Button(
                onClick = onLogout,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                modifier = Modifier.padding(top = 6.dp)
            ) {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Default.Logout,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Logout")
            }
        }
    }
}
