package com.x8bit.bitwarden.ui.platform.feature.settings.accountsecurity.changemasterpassword

import android.os.Parcelable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.bitwarden.ui.platform.base.BaseViewModel
import com.bitwarden.ui.platform.base.util.orNullIfBlank
import com.bitwarden.ui.platform.resource.BitwardenPlurals
import com.bitwarden.ui.platform.resource.BitwardenString
import com.bitwarden.ui.util.Text
import com.bitwarden.ui.util.asPluralsText
import com.bitwarden.ui.util.asText
import com.x8bit.bitwarden.data.auth.datasource.sdk.model.PasswordStrength
import com.x8bit.bitwarden.data.auth.manager.MasterPasswordManager
import com.x8bit.bitwarden.data.auth.manager.model.ChangeMasterPasswordResult
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.auth.repository.model.BreachCountResult
import com.x8bit.bitwarden.data.auth.repository.model.PasswordStrengthResult
import com.x8bit.bitwarden.data.auth.repository.model.ValidatePasswordResult
import com.x8bit.bitwarden.data.vault.repository.VaultRepository
import com.x8bit.bitwarden.ui.auth.feature.completeregistration.PasswordStrengthState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import javax.inject.Inject

private const val KEY_STATE = "state"
private const val MIN_PASSWORD_LENGTH = 12

/**
 * View model for the [ChangeMasterPasswordScreen].
 */
@Suppress("TooManyFunctions")
@HiltViewModel
class ChangeMasterPasswordViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val vaultRepository: VaultRepository,
    private val masterPasswordManager: MasterPasswordManager,
    savedStateHandle: SavedStateHandle,
) : BaseViewModel<
    ChangeMasterPasswordState,
    ChangeMasterPasswordEvent,
    ChangeMasterPasswordAction,
    >(
    initialState = savedStateHandle[KEY_STATE] ?: ChangeMasterPasswordState(
        passwordHintInput = "",
        isCheckDataBreachesToggled = true,
        minimumPasswordLength = MIN_PASSWORD_LENGTH,
        isSyncing = true,
        dialog = null,
    ),
) {
    /**
     * Keeps track of the request for the strength of the new master password, which is cancelled
     * when the new master password is changed.
     */
    private var passwordStrengthJob: Job = Job().apply { complete() }

    init {
        stateFlow
            .onEach { savedStateHandle[KEY_STATE] = it }
            .launchIn(viewModelScope)
        if (state.isSyncing) {
            viewModelScope.launch {
                // Another device of the vault folder may have changed the encryption key
                // settings, which this one takes at a sync. A change of the master password with
                // the settings from before would be refused as if the master password were wrong.
                // A sync that takes a change of the master password logs the account out of this
                // device instead, and one that fails leaves what the app has.
                vaultRepository.syncForResult()
                sendAction(ChangeMasterPasswordAction.Internal.SyncComplete)
            }
        }
    }

    override fun handleAction(action: ChangeMasterPasswordAction) {
        when (action) {
            ChangeMasterPasswordAction.BackClick -> handleBackClick()
            is ChangeMasterPasswordAction.CurrentPasswordInputChange -> {
                handleCurrentPasswordInputChange(action)
            }

            is ChangeMasterPasswordAction.PasswordInputChange -> handlePasswordInputChange(action)
            is ChangeMasterPasswordAction.RetypePasswordInputChange -> {
                handleRetypePasswordInputChange(action)
            }

            is ChangeMasterPasswordAction.PasswordHintInputChange -> {
                handlePasswordHintInputChange(action)
            }

            is ChangeMasterPasswordAction.CheckDataBreachesToggle -> {
                handleCheckDataBreachesToggle(action)
            }

            ChangeMasterPasswordAction.ChangeMasterPasswordClick -> {
                handleChangeMasterPasswordClick()
            }

            ChangeMasterPasswordAction.ContinueWithBreachedPasswordClick -> {
                handleContinueWithBreachedPasswordClick()
            }

            ChangeMasterPasswordAction.DismissDialog -> handleDismissDialog()
            is ChangeMasterPasswordAction.Internal -> handleInternalAction(action)
        }
    }

    private fun handleBackClick() {
        sendEvent(ChangeMasterPasswordEvent.NavigateBack)
    }

    private fun handleCurrentPasswordInputChange(
        action: ChangeMasterPasswordAction.CurrentPasswordInputChange,
    ) {
        mutableStateFlow.update { it.copy(currentPasswordInput = action.input) }
    }

    private fun handlePasswordInputChange(action: ChangeMasterPasswordAction.PasswordInputChange) {
        mutableStateFlow.update { it.copy(passwordInput = action.input) }
        checkPasswordStrength(password = action.input)
    }

    private fun handleRetypePasswordInputChange(
        action: ChangeMasterPasswordAction.RetypePasswordInputChange,
    ) {
        mutableStateFlow.update { it.copy(retypePasswordInput = action.input) }
    }

    private fun handlePasswordHintInputChange(
        action: ChangeMasterPasswordAction.PasswordHintInputChange,
    ) {
        mutableStateFlow.update { it.copy(passwordHintInput = action.input) }
    }

    private fun handleCheckDataBreachesToggle(
        action: ChangeMasterPasswordAction.CheckDataBreachesToggle,
    ) {
        mutableStateFlow.update { it.copy(isCheckDataBreachesToggled = action.newState) }
    }

    private fun handleChangeMasterPasswordClick() {
        state.validationErrorOrNull()?.let { message ->
            showErrorDialog(message = message)
            return
        }
        changeMasterPassword(
            shouldCheckForDataBreaches = state.isCheckDataBreachesToggled,
            shouldIgnorePasswordStrength = false,
        )
    }

    private fun handleContinueWithBreachedPasswordClick() {
        changeMasterPassword(
            shouldCheckForDataBreaches = false,
            shouldIgnorePasswordStrength = true,
        )
    }

    /**
     * Changes the master password to the one that is entered, once the current one is confirmed.
     * A new one that is weak or known from a data breach is changed to only once the user confirms
     * it, as on the screen that creates an account.
     */
    private fun changeMasterPassword(
        shouldCheckForDataBreaches: Boolean,
        shouldIgnorePasswordStrength: Boolean,
    ) {
        val currentPassword = state.currentPasswordInput
        val newPassword = state.passwordInput
        val passwordHint = state.passwordHintInput.orNullIfBlank()
        val isPasswordWeak = !shouldIgnorePasswordStrength && !state.isMasterPasswordStrong
        mutableStateFlow.update {
            it.copy(
                dialog = ChangeMasterPasswordState.DialogState.Loading(
                    message = BitwardenString.updating_password.asText(),
                ),
            )
        }
        viewModelScope.launch {
            val validation = authRepository.validatePassword(password = currentPassword)
            // A current master password that is mistyped is told as such, and not sent to the
            // vault, nor is the new one checked against data breaches for it.
            if ((validation as? ValidatePasswordResult.Success)?.isValid == false) {
                sendAction(ChangeMasterPasswordAction.Internal.InvalidMasterPasswordReceive)
                return@launch
            }
            val isPasswordExposed = shouldCheckForDataBreaches &&
                hasPasswordBeenBreached(password = newPassword)
            if (isPasswordWeak || isPasswordExposed) {
                sendAction(
                    ChangeMasterPasswordAction.Internal.WeakOrExposedPasswordReceive(
                        isWeak = isPasswordWeak,
                        isExposed = isPasswordExposed,
                    ),
                )
                return@launch
            }
            val result = masterPasswordManager.changeMasterPassword(
                currentPassword = currentPassword,
                newPassword = newPassword,
                passwordHint = passwordHint,
            )
            if (result is ChangeMasterPasswordResult.MasterPasswordRejected) {
                // The vault knows the master password by the settings of another device, which
                // the next attempt proves it with once they are synced, or another device changed
                // the master password, which the sync logs the account out of this device for.
                vaultRepository.syncForResult()
            }
            sendAction(
                ChangeMasterPasswordAction.Internal.ChangeMasterPasswordResultReceive(
                    result = result,
                ),
            )
        }
    }

    private fun handleDismissDialog() {
        mutableStateFlow.update { it.copy(dialog = null) }
    }

    private fun handleInternalAction(action: ChangeMasterPasswordAction.Internal) {
        when (action) {
            ChangeMasterPasswordAction.Internal.SyncComplete -> handleSyncComplete()
            is ChangeMasterPasswordAction.Internal.PasswordStrengthResultReceive -> {
                handlePasswordStrengthResultReceive(action)
            }

            ChangeMasterPasswordAction.Internal.InvalidMasterPasswordReceive -> {
                handleInvalidMasterPasswordReceive()
            }

            is ChangeMasterPasswordAction.Internal.WeakOrExposedPasswordReceive -> {
                handleWeakOrExposedPasswordReceive(action)
            }

            is ChangeMasterPasswordAction.Internal.ChangeMasterPasswordResultReceive -> {
                handleChangeMasterPasswordResultReceive(action)
            }
        }
    }

    private fun handleSyncComplete() {
        mutableStateFlow.update { it.copy(isSyncing = false) }
    }

    private fun handlePasswordStrengthResultReceive(
        action: ChangeMasterPasswordAction.Internal.PasswordStrengthResultReceive,
    ) {
        when (val result = action.result) {
            is PasswordStrengthResult.Success -> {
                val passwordStrengthState = when (result.passwordStrength) {
                    PasswordStrength.LEVEL_0 -> PasswordStrengthState.WEAK_1
                    PasswordStrength.LEVEL_1 -> PasswordStrengthState.WEAK_2
                    PasswordStrength.LEVEL_2 -> PasswordStrengthState.WEAK_3
                    PasswordStrength.LEVEL_3 -> PasswordStrengthState.GOOD
                    PasswordStrength.LEVEL_4 -> PasswordStrengthState.STRONG
                }
                mutableStateFlow.update { it.copy(passwordStrengthState = passwordStrengthState) }
            }

            is PasswordStrengthResult.Error -> Unit
        }
    }

    private fun handleInvalidMasterPasswordReceive() {
        showErrorDialog(message = BitwardenString.invalid_master_password.asText())
    }

    private fun handleWeakOrExposedPasswordReceive(
        action: ChangeMasterPasswordAction.Internal.WeakOrExposedPasswordReceive,
    ) {
        val dialog = when {
            action.isWeak && action.isExposed -> {
                ChangeMasterPasswordState.DialogState.HaveIBeenPwned(
                    title = BitwardenString.weak_and_exposed_master_password.asText(),
                    message = BitwardenString
                        .weak_password_identified_and_found_in_a_data_breach_alert_description
                        .asText(),
                )
            }

            action.isExposed -> {
                ChangeMasterPasswordState.DialogState.HaveIBeenPwned(
                    title = BitwardenString.exposed_master_password.asText(),
                    message = BitwardenString
                        .password_found_in_a_data_breach_alert_description
                        .asText(),
                )
            }

            else -> {
                ChangeMasterPasswordState.DialogState.HaveIBeenPwned(
                    title = BitwardenString.weak_master_password.asText(),
                    message = BitwardenString
                        .weak_password_identified_use_a_strong_password_to_protect_your_account
                        .asText(),
                )
            }
        }
        mutableStateFlow.update { it.copy(dialog = dialog) }
    }

    private fun handleChangeMasterPasswordResultReceive(
        action: ChangeMasterPasswordAction.Internal.ChangeMasterPasswordResultReceive,
    ) {
        when (val result = action.result) {
            // The account was logged out of this device, which takes the app away from this
            // screen.
            ChangeMasterPasswordResult.Success -> {
                mutableStateFlow.update { it.copy(dialog = null) }
            }

            ChangeMasterPasswordResult.MasterPasswordRejected -> {
                showErrorDialog(
                    message = BitwardenString
                        .your_encryption_key_settings_were_changed_on_another_device_try_again
                        .asText(),
                )
            }

            is ChangeMasterPasswordResult.Error -> {
                showErrorDialog(
                    message = BitwardenString.generic_error_message.asText(),
                    error = result.error,
                )
            }
        }
    }

    private fun checkPasswordStrength(password: String) {
        passwordStrengthJob.cancel()
        if (password.isEmpty()) {
            mutableStateFlow.update { it.copy(passwordStrengthState = PasswordStrengthState.NONE) }
        } else {
            passwordStrengthJob = viewModelScope.launch {
                val result = authRepository.getPasswordStrength(password = password)
                sendAction(
                    ChangeMasterPasswordAction.Internal.PasswordStrengthResultReceive(
                        result = result,
                    ),
                )
            }
        }
    }

    /**
     * Returns `true` if Have I Been Pwned knows the [password] from a data breach. A check that
     * fails does not keep the master password from being changed, as it does not keep an account
     * from being created.
     */
    private suspend fun hasPasswordBeenBreached(password: String): Boolean =
        when (val result = authRepository.getPasswordBreachCount(password = password)) {
            is BreachCountResult.Success -> result.breachCount > 0
            is BreachCountResult.Error -> false
        }

    private fun showErrorDialog(message: Text, error: Throwable? = null) {
        mutableStateFlow.update {
            it.copy(
                dialog = ChangeMasterPasswordState.DialogState.Error(
                    title = BitwardenString.an_error_has_occurred.asText(),
                    message = message,
                    error = error,
                ),
            )
        }
    }
}

/**
 * Models the state of the [ChangeMasterPasswordScreen].
 *
 * @property currentPasswordInput The current master password, as it is entered.
 * @property passwordInput The new master password, as it is entered.
 * @property retypePasswordInput The new master password, as it is entered again.
 * @property passwordStrengthState How strong the new master password is.
 * @property passwordHintInput The hint of the new master password, as it is entered.
 * @property isCheckDataBreachesToggled Whether the new master password is checked against the
 * data breaches that Have I Been Pwned knows.
 * @property minimumPasswordLength The fewest characters that the new master password may have.
 * @property isSyncing Whether the vault is being synced, which it is before the master password
 * can be changed.
 * @property dialog The dialog that is shown, if any.
 */
@Parcelize
data class ChangeMasterPasswordState(
    // We never want these saved since the input is sensitive data.
    @IgnoredOnParcel val currentPasswordInput: String = "",
    @IgnoredOnParcel val passwordInput: String = "",
    @IgnoredOnParcel val retypePasswordInput: String = "",
    @IgnoredOnParcel val passwordStrengthState: PasswordStrengthState = PasswordStrengthState.NONE,
    val passwordHintInput: String,
    val isCheckDataBreachesToggled: Boolean,
    val minimumPasswordLength: Int,
    val isSyncing: Boolean,
    val dialog: DialogState?,
) : Parcelable {
    /**
     * Whether the new master password is considered strong.
     */
    val isMasterPasswordStrong: Boolean
        get() = when (passwordStrengthState) {
            PasswordStrengthState.NONE,
            PasswordStrengthState.WEAK_1,
            PasswordStrengthState.WEAK_2,
            PasswordStrengthState.WEAK_3,
                -> false

            PasswordStrengthState.GOOD,
            PasswordStrengthState.STRONG,
                -> true
        }

    /**
     * Represents the dialogs of the screen.
     */
    sealed class DialogState : Parcelable {
        /**
         * The master password is being changed.
         */
        @Parcelize
        data class Loading(
            val message: Text,
        ) : DialogState()

        /**
         * Asks the user whether to change the master password to the new one all the same, as it
         * is weak, known from a data breach, or both.
         */
        @Parcelize
        data class HaveIBeenPwned(
            val title: Text,
            val message: Text,
        ) : DialogState()

        /**
         * The master password was not changed.
         */
        @Parcelize
        data class Error(
            val title: Text?,
            val message: Text,
            val error: Throwable? = null,
        ) : DialogState()
    }
}

/**
 * Models events for the [ChangeMasterPasswordScreen].
 */
sealed class ChangeMasterPasswordEvent {
    /**
     * Navigate back.
     */
    data object NavigateBack : ChangeMasterPasswordEvent()
}

/**
 * Models actions for the [ChangeMasterPasswordScreen].
 */
sealed class ChangeMasterPasswordAction {
    /**
     * The user clicked the back button.
     */
    data object BackClick : ChangeMasterPasswordAction()

    /**
     * The user changed the current master password.
     */
    data class CurrentPasswordInputChange(
        val input: String,
    ) : ChangeMasterPasswordAction()

    /**
     * The user changed the new master password.
     */
    data class PasswordInputChange(
        val input: String,
    ) : ChangeMasterPasswordAction()

    /**
     * The user changed the new master password that is entered again.
     */
    data class RetypePasswordInputChange(
        val input: String,
    ) : ChangeMasterPasswordAction()

    /**
     * The user changed the hint of the new master password.
     */
    data class PasswordHintInputChange(
        val input: String,
    ) : ChangeMasterPasswordAction()

    /**
     * The user turned the check of the new master password against data breaches on or off.
     */
    data class CheckDataBreachesToggle(
        val newState: Boolean,
    ) : ChangeMasterPasswordAction()

    /**
     * The user clicked the button that changes the master password.
     */
    data object ChangeMasterPasswordClick : ChangeMasterPasswordAction()

    /**
     * The user confirmed that the master password is to be changed to the new one, which is weak
     * or known from a data breach.
     */
    data object ContinueWithBreachedPasswordClick : ChangeMasterPasswordAction()

    /**
     * The user dismissed the dialog.
     */
    data object DismissDialog : ChangeMasterPasswordAction()

    /**
     * Models actions that the [ChangeMasterPasswordViewModel] itself might send.
     */
    sealed class Internal : ChangeMasterPasswordAction() {
        /**
         * The vault was synced, or could not be.
         */
        data object SyncComplete : Internal()

        /**
         * How strong the new master password is was determined, or not, as the [result] tells.
         */
        data class PasswordStrengthResultReceive(
            val result: PasswordStrengthResult,
        ) : Internal()

        /**
         * The current master password that was entered is not that of the account.
         */
        data object InvalidMasterPasswordReceive : Internal()

        /**
         * The new master password is weak, known from a data breach, or both, as [isWeak] and
         * [isExposed] tell, which the user is asked to confirm it with.
         */
        data class WeakOrExposedPasswordReceive(
            val isWeak: Boolean,
            val isExposed: Boolean,
        ) : Internal()

        /**
         * The master password was changed, or not, as the [result] tells.
         */
        data class ChangeMasterPasswordResultReceive(
            val result: ChangeMasterPasswordResult,
        ) : Internal()
    }
}

/**
 * Returns the message for the first of what is entered that the master password cannot be
 * changed with, or `null` if it can be. Whether the current master password is that of the
 * account is checked afterwards.
 */
private fun ChangeMasterPasswordState.validationErrorOrNull(): Text? =
    when {
        passwordInput.length < minimumPasswordLength -> {
            BitwardenPlurals.master_password_length_val_message_x.asPluralsText(
                quantity = minimumPasswordLength,
                args = arrayOf(minimumPasswordLength),
            )
        }

        passwordInput != retypePasswordInput -> {
            BitwardenString.master_password_confirmation_val_message.asText()
        }

        passwordInput == currentPasswordInput -> {
            BitwardenString
                .your_new_master_password_cannot_be_the_same_as_your_current_one
                .asText()
        }

        // Whoever can read the vault folder can read the hint.
        passwordHintInput.equals(passwordInput, ignoreCase = true) -> {
            BitwardenString
                .your_master_password_hint_cannot_be_the_same_as_your_master_password
                .asText()
        }

        else -> null
    }
