package com.sinxn.teledrive.ui.screens.settings

import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToStorageSelection: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val selectedChannelName by viewModel.selectedChannelName.collectAsState()
    val authOnLaunch by viewModel.authOnLaunch.collectAsState()
    val biometricUnlock by viewModel.biometricUnlock.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

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
        ) {
            ListItem(
                headlineContent = { Text("Storage Location") },
                supportingContent = { Text(selectedChannelName) },
                leadingContent = {
                    Icon(androidx.compose.material.icons.Icons.Default.Storage, contentDescription = "Storage Location")
                },
                modifier = Modifier.clickable {
                    onNavigateToStorageSelection()
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
