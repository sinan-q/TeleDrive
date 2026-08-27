package com.sinxn.teledrive.ui.screens.login

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lightspark.composeqr.QrCodeView
import com.sinxn.teledrive.data.telegram.AuthState

@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel()
) {
    val authState by viewModel.authState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Navigate to home when authenticated
    LaunchedEffect(authState) {
        if (authState is AuthState.Ready) {
            onLoginSuccess()
        }
        if (authState is AuthState.Error) {
            snackbarHostState.showSnackbar((authState as AuthState.Error).message)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // App Title
            Text(
                text = "TeleDrive",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Your cloud storage on Telegram",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(48.dp))

            AnimatedContent(
                targetState = authState,
                label = "auth_state_transition"
            ) { state ->
                when (state) {
                    is AuthState.Idle, is AuthState.Loading -> {
                        LoadingContent()
                    }
                    is AuthState.WaitPhoneNumber -> {
                        PhoneNumberInput(
                            onSubmit = { viewModel.sendPhoneNumber(it) },
                            onRequestQrCode = { viewModel.requestQrCodeLogin() }
                        )
                    }
                    is AuthState.WaitQrCodeConfirmation -> {
                        QrCodeInput(
                            link = state.link
                        )
                    }
                    is AuthState.WaitCode -> {
                        CodeInput(
                            codeType = state.codeType,
                            onSubmit = { viewModel.sendCode(it) }
                        )
                    }
                    is AuthState.WaitPassword -> {
                        PasswordInput(
                            hint = state.passwordHint,
                            hasRecoveryEmail = state.hasRecoveryEmail,
                            errorMessage = state.errorMessage,
                            onSubmit = { viewModel.sendPassword(it) },
                            onRequestRecovery = { viewModel.requestPasswordRecovery() }
                        )
                    }
                    is AuthState.WaitPasswordRecoveryCode -> {
                        RecoveryCodeInput(
                            emailPattern = state.emailPattern,
                            onSubmit = { viewModel.recoverPassword(it) }
                        )
                    }
                    is AuthState.Error -> {
                        // Show the last input step again based on what makes sense
                        PhoneNumberInput(
                            onSubmit = { viewModel.sendPhoneNumber(it) },
                            onRequestQrCode = { viewModel.requestQrCodeLogin() }
                        )
                    }
                    else -> {
                        LoadingContent()
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Connecting to Telegram...",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun PhoneNumberInput(
    onSubmit: (String) -> Unit,
    onRequestQrCode: () -> Unit
) {
    var phoneNumber by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Phone,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Enter your phone number",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Include country code (e.g., +1234567890)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        OutlinedTextField(
            value = phoneNumber,
            onValueChange = { phoneNumber = it },
            label = { Text("Phone Number") },
            placeholder = { Text("+1234567890") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Phone,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { if (phoneNumber.isNotBlank()) onSubmit(phoneNumber) }
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { onSubmit(phoneNumber) },
            enabled = phoneNumber.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Continue")
        }

        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = onRequestQrCode) {
            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.size(8.dp))
            Text("Log in by QR Code")
        }
    }
}

@Composable
private fun CodeInput(codeType: String, onSubmit: (String) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Check your $codeType",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Enter the verification code you received",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text("Verification Code") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { if (code.isNotBlank()) onSubmit(code) }
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { onSubmit(code) },
            enabled = code.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Verify")
        }
    }
}

@Composable
private fun PasswordInput(
    hint: String,
    hasRecoveryEmail: Boolean,
    errorMessage: String? = null,
    onSubmit: (String) -> Unit,
    onRequestRecovery: () -> Unit
) {
    var password by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Two-Step Verification",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        if (hint.isNotBlank()) {
            Text(
                text = "Hint: $hint",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!errorMessage.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { if (password.isNotBlank()) onSubmit(password) }
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { onSubmit(password) },
            enabled = password.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Submit")
        }

        if (hasRecoveryEmail) {
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onRequestRecovery) {
                Text("Forgot Password?")
            }
        }
    }
}

@Composable
private fun RecoveryCodeInput(emailPattern: String, onSubmit: (String) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Email,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Password Recovery",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "We sent a recovery code to $emailPattern",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text("Recovery Code") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { if (code.isNotBlank()) onSubmit(code) }
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { onSubmit(code) },
            enabled = code.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Submit Code")
        }
    }
}

@Composable
private fun QrCodeInput(link: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Share,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Log in to TeleDrive",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Open Telegram on your phone -> Settings -> Devices -> Link Desktop Device, and scan this code.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(32.dp))
        
        // Render the TDLib QR Code link
        QrCodeView(
            data = link,
            modifier = Modifier.size(240.dp)
        )
        
        Spacer(modifier = Modifier.height(32.dp))
    }
}
