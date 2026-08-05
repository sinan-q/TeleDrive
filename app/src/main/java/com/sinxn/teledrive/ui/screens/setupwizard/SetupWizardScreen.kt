package com.sinxn.teledrive.ui.screens.setupwizard

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sinxn.teledrive.data.local.prefs.SetupStep
import com.sinxn.teledrive.ui.screens.masterpassword.SetupPasswordContent
import com.sinxn.teledrive.ui.screens.securitypreferences.SecurityPreferencesContent
import com.sinxn.teledrive.ui.screens.storageselection.StorageSelectionContent
import com.sinxn.teledrive.ui.screens.sync.SyncProgressContent

@RequiresApi(Build.VERSION_CODES.P)
@Composable
fun SetupWizardScreen(
    onSetupComplete: () -> Unit,
    viewModel: SetupWizardViewModel = hiltViewModel()
) {
    val currentStep by viewModel.currentStep.collectAsState()
    val isSyncMode by viewModel.isSyncMode.collectAsState()

    // Handle system back button
    BackHandler(enabled = viewModel.canGoBack) {
        viewModel.goBack()
    }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
            ) {
                // Header navigation bar: Back button + Segmented Progress Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (viewModel.canGoBack) {
                        IconButton(onClick = { viewModel.goBack() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    } else {
                        // Placeholder box to maintain progress bar alignment & prevent sudden jumps
                        Spacer(modifier = Modifier.size(48.dp))
                    }

                    SegmentedProgressBar(
                        currentIndex = viewModel.stepIndex(currentStep),
                        totalSteps = viewModel.totalSteps,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 24.dp)
                    )
                }
            }
        }
    ) { innerPadding ->
        AnimatedContent(
            targetState = currentStep,
            label = "wizard_step",
            transitionSpec = {
                val forward = viewModel.stepIndex(targetState) >= viewModel.stepIndex(initialState)
                if (forward) {
                    slideInHorizontally { fullWidth -> fullWidth } togetherWith
                            slideOutHorizontally { fullWidth -> -fullWidth }
                } else {
                    slideInHorizontally { fullWidth -> -fullWidth } togetherWith
                            slideOutHorizontally { fullWidth -> fullWidth }
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) { step ->
            when (step) {
                SetupStep.STORAGE_SELECTION -> {
                    StorageSelectionContent(
                        onContinue = { syncMode ->
                            viewModel.advanceFromStorageSelection(syncMode)
                        }
                    )
                }

                SetupStep.MASTER_PASSWORD -> {
                    SetupPasswordContent(
                        isSyncMode = isSyncMode,
                        onPasswordSet = {
                            viewModel.advanceFromMasterPassword()
                        }
                    )
                }

                SetupStep.SYNC_PROGRESS -> {
                    SyncProgressContent(
                        onContinue = {
                            viewModel.advanceFromSyncProgress()
                        }
                    )
                }

                SetupStep.SECURITY_PREFERENCES -> {
                    SecurityPreferencesContent(
                        onContinue = {
                            viewModel.completeSetup()
                            onSetupComplete()
                        }
                    )
                }

                else -> {
                    // Fallback
                    StorageSelectionContent(
                        onContinue = { syncMode ->
                            viewModel.advanceFromStorageSelection(syncMode)
                        }
                    )
                }
            }
        }
    }
}

/**
 * A modern segmented horizontal progress bar for multi-step onboarding/wizards.
 */
@Composable
private fun SegmentedProgressBar(
    currentIndex: Int,
    totalSteps: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(totalSteps) { index ->
            val progress = when {
                index < currentIndex -> 1f
                index == currentIndex -> 1f
                else -> 0f
            }

            val animatedProgress by animateFloatAsState(
                targetValue = progress,
                animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
                label = "step_progress"
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            ) {
                if (animatedProgress > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                if (index <= currentIndex) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                }
                            )
                    )
                }
            }
        }
    }
}
