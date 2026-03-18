package com.sinxn.teledrive.ui.screens.securitypreferences

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sinxn.teledrive.data.local.prefs.SecurePrefs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityPreferencesScreen(
    securePrefs: SecurePrefs,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier
) {
    var authOnLaunch by remember { mutableStateOf(securePrefs.isAuthOnLaunchEnabled()) }
    var biometricUnlock by remember { mutableStateOf(securePrefs.isBiometricUnlockEnabled()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Security") }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
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
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
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
                                    authOnLaunch = enabled
                                    securePrefs.setAuthOnLaunch(enabled)
                                    if (!enabled) {
                                        biometricUnlock = false
                                        securePrefs.setBiometricUnlock(false)
                                    }
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
                                        biometricUnlock = enabled
                                        securePrefs.setBiometricUnlock(enabled)
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
    }
}
