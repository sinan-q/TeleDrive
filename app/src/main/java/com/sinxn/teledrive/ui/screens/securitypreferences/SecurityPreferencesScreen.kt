package com.sinxn.teledrive.ui.screens.securitypreferences

import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityPreferencesScreen(
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SecurityPreferencesViewModel = hiltViewModel()
) {
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Security") }
            )
        },
        bottomBar = {
            Button(
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .navigationBarsPadding()
            ) {
                Text("Continue")
            }
        }
    ) { innerPadding ->
        SecurityPreferencesContent(
            onContinue = onContinue,
            modifier = modifier.padding(innerPadding),
            viewModel = viewModel
        )
    }
}

/**
 * Extracted content composable for security preferences.
 * Can be used standalone inside a Scaffold or embedded into the Setup Wizard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityPreferencesContent(
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SecurityPreferencesViewModel = hiltViewModel()
) {
    val authOnLaunch by viewModel.authOnLaunch.collectAsState()
    val biometricUnlock by viewModel.biometricUnlock.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Main content (centered)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Snackbar host
            SnackbarHost(snackbarHostState)

            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "How should TeleDrive protect your vault?",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "You can change these later in Settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    ListItem(
                        headlineContent = { Text("Require authentication on launch") },
                        supportingContent = { Text("Ask for password or biometric every time you open the app") },
                        trailingContent = {
                            Switch(
                                checked = authOnLaunch,
                                onCheckedChange = { enabled ->
                                    viewModel.setAuthOnLaunch(enabled)
                                }
                            )
                        },
                        leadingContent = {
                            Icon(Icons.Default.Security, contentDescription = null)
                        }
                    )

                    if (authOnLaunch) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                        ListItem(
                            headlineContent = { Text("Unlock with face or fingerprint") },
                            supportingContent = { Text("Use your device biometrics instead of typing the password") },
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
                            },
                            leadingContent = {
                                Icon(Icons.Default.Fingerprint, contentDescription = null)
                            }
                        )
                    }
                }
            }
        }

        // Bottom button
        Button(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
                .navigationBarsPadding()
        ) {
            Text("Continue")
        }
    }
}
