package com.sinxn.teledrive.ui.screens.setupwizard

import androidx.lifecycle.ViewModel
import com.sinxn.teledrive.data.local.prefs.SecurePrefs
import com.sinxn.teledrive.data.local.prefs.SetupStep
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * Orchestrates the setup wizard step navigation.
 * Each step's content composable uses its own Hilt ViewModel for business logic.
 * This ViewModel only manages the current step and step transitions.
 */
@HiltViewModel
class SetupWizardViewModel @Inject constructor(
    private val securePrefs: SecurePrefs
) : ViewModel() {

    private val _currentStep = MutableStateFlow(resolveInitialStep())
    val currentStep: StateFlow<SetupStep> = _currentStep.asStateFlow()

    private val _isSyncMode = MutableStateFlow(securePrefs.isSyncMode())
    val isSyncMode: StateFlow<Boolean> = _isSyncMode.asStateFlow()

    /**
     * Resolve the initial step from persisted setup progress.
     * This allows the wizard to resume from where the user left off after an app kill.
     */
    private fun resolveInitialStep(): SetupStep {
        return when (securePrefs.getSetupStep()) {
            SetupStep.COMPLETED -> SetupStep.STORAGE_SELECTION // Shouldn't happen, but fallback
            SetupStep.LOGIN -> SetupStep.STORAGE_SELECTION // Wizard starts at storage selection
            else -> securePrefs.getSetupStep()
        }
    }

    /**
     * Navigate to the next step based on the current step and sync mode.
     */
    fun advanceFromStorageSelection(isSyncMode: Boolean) {
        _isSyncMode.value = isSyncMode
        _currentStep.value = SetupStep.MASTER_PASSWORD
        securePrefs.setSetupStep(SetupStep.MASTER_PASSWORD)
    }

    fun advanceFromMasterPassword() {
        if (_isSyncMode.value) {
            _currentStep.value = SetupStep.SYNC_PROGRESS
            securePrefs.setSetupStep(SetupStep.SYNC_PROGRESS)
        } else {
            _currentStep.value = SetupStep.SECURITY_PREFERENCES
            securePrefs.setSetupStep(SetupStep.SECURITY_PREFERENCES)
        }
    }

    fun advanceFromSyncProgress() {
        _currentStep.value = SetupStep.SECURITY_PREFERENCES
        securePrefs.setSetupStep(SetupStep.SECURITY_PREFERENCES)
    }

    fun completeSetup() {
        securePrefs.setSetupStep(SetupStep.COMPLETED)
    }

    /**
     * Navigate back to the previous step. Returns false if already at the first step.
     */
    fun goBack(): Boolean {
        return when (_currentStep.value) {
            SetupStep.MASTER_PASSWORD -> {
                _currentStep.value = SetupStep.STORAGE_SELECTION
                securePrefs.setSetupStep(SetupStep.STORAGE_SELECTION)
                true
            }
            // Sync progress and security preferences are non-reversible
            else -> false
        }
    }

    /**
     * Whether the back button should be shown for the current step.
     */
    val canGoBack: Boolean
        get() = _currentStep.value == SetupStep.MASTER_PASSWORD

    /**
     * Returns the step index (0-based) for the dot indicator.
     */
    fun stepIndex(step: SetupStep): Int {
        return when (step) {
            SetupStep.STORAGE_SELECTION -> 0
            SetupStep.MASTER_PASSWORD -> 1
            SetupStep.SYNC_PROGRESS -> 2
            SetupStep.SECURITY_PREFERENCES -> if (_isSyncMode.value) 3 else 2
            else -> 0
        }
    }

    /**
     * Returns the total number of steps for the dot indicator.
     */
    val totalSteps: Int
        get() = if (_isSyncMode.value) 4 else 3

    /**
     * Returns the title for the current step.
     */
    fun stepTitle(step: SetupStep): String {
        return when (step) {
            SetupStep.STORAGE_SELECTION -> "Choose Storage"
            SetupStep.MASTER_PASSWORD -> "Master Password"
            SetupStep.SYNC_PROGRESS -> "Sync Vault"
            SetupStep.SECURITY_PREFERENCES -> "Security"
            else -> "Setup"
        }
    }
}
