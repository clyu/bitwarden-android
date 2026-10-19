package com.x8bit.bitwarden.ui.platform.feature.settings.accountsecurity.kdfsettings

import android.os.Parcelable
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.bitwarden.crypto.Kdf
import com.bitwarden.ui.platform.base.BaseViewModel
import com.bitwarden.ui.platform.components.snackbar.model.BitwardenSnackbarData
import com.bitwarden.ui.platform.resource.BitwardenString
import com.bitwarden.ui.util.Text
import com.bitwarden.ui.util.asText
import com.x8bit.bitwarden.data.auth.manager.KdfSettingsManager
import com.x8bit.bitwarden.data.auth.manager.model.ChangeKdfResult
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.auth.repository.model.ValidatePasswordResult
import com.x8bit.bitwarden.data.auth.util.KdfSettingsConstants
import com.x8bit.bitwarden.data.vault.repository.VaultRepository
import com.x8bit.bitwarden.ui.platform.feature.settings.accountsecurity.kdfsettings.model.KdfAlgorithm
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import javax.inject.Inject

private const val KEY_STATE = "state"

/**
 * View model for the [KdfSettingsScreen].
 */
@Suppress("TooManyFunctions")
@HiltViewModel
class KdfSettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val vaultRepository: VaultRepository,
    private val kdfSettingsManager: KdfSettingsManager,
    savedStateHandle: SavedStateHandle,
) : BaseViewModel<KdfSettingsState, KdfSettingsEvent, KdfSettingsAction>(
    initialState = savedStateHandle[KEY_STATE] ?: KdfSettingsState(
        viewState = KdfSettingsState.ViewState.Loading,
        dialog = null,
    ),
) {
    init {
        stateFlow
            .onEach { savedStateHandle[KEY_STATE] = it }
            .launchIn(viewModelScope)
        if (state.viewState is KdfSettingsState.ViewState.Loading) {
            viewModelScope.launch {
                // Another device of the vault folder may have changed the settings, which this
                // one takes at a sync. A change from the settings before would be refused as if
                // the master password were wrong. A sync that fails leaves the settings that the
                // app has.
                vaultRepository.syncForResult()
                sendAction(
                    KdfSettingsAction.Internal.KdfReceive(
                        kdf = kdfSettingsManager.activeAccountKdf,
                    ),
                )
            }
        }
    }

    override fun handleAction(action: KdfSettingsAction) {
        when (action) {
            KdfSettingsAction.BackClick -> handleBackClick()
            is KdfSettingsAction.AlgorithmSelect -> handleAlgorithmSelect(action)
            is KdfSettingsAction.IterationsInputChange -> handleIterationsInputChange(action)
            is KdfSettingsAction.MemoryInputChange -> handleMemoryInputChange(action)
            is KdfSettingsAction.ParallelismInputChange -> handleParallelismInputChange(action)
            KdfSettingsAction.UpdateClick -> handleUpdateClick()
            is KdfSettingsAction.MasterPasswordConfirm -> handleMasterPasswordConfirm(action)
            KdfSettingsAction.DismissDialog -> handleDismissDialog()
            is KdfSettingsAction.Internal -> handleInternalAction(action)
        }
    }

    private fun handleBackClick() {
        sendEvent(KdfSettingsEvent.NavigateBack)
    }

    private fun handleAlgorithmSelect(action: KdfSettingsAction.AlgorithmSelect) {
        updateContent { content ->
            content
                .takeIf { it.algorithm == action.algorithm }
                ?: action.algorithm.toDefaultContent()
        }
    }

    private fun handleIterationsInputChange(action: KdfSettingsAction.IterationsInputChange) {
        updateContent { it.copy(iterationsInput = action.input) }
    }

    private fun handleMemoryInputChange(action: KdfSettingsAction.MemoryInputChange) {
        updateContent { it.copy(memoryInput = action.input) }
    }

    private fun handleParallelismInputChange(action: KdfSettingsAction.ParallelismInputChange) {
        updateContent { it.copy(parallelismInput = action.input) }
    }

    private fun handleUpdateClick() {
        val content = state.viewState as? KdfSettingsState.ViewState.Content ?: return
        val dialog = content
            .validationErrorOrNull()
            ?.let { message ->
                KdfSettingsState.DialogState.Error(
                    title = BitwardenString.an_error_has_occurred.asText(),
                    message = message,
                )
            }
            ?: KdfSettingsState.DialogState.MasterPasswordPrompt
        mutableStateFlow.update { it.copy(dialog = dialog) }
    }

    private fun handleMasterPasswordConfirm(action: KdfSettingsAction.MasterPasswordConfirm) {
        val content = state.viewState as? KdfSettingsState.ViewState.Content ?: return
        val kdf = content.toKdfOrNull() ?: return
        mutableStateFlow.update {
            it.copy(
                dialog = KdfSettingsState.DialogState.Loading(
                    message = BitwardenString.saving.asText(),
                ),
            )
        }
        viewModelScope.launch {
            val validation = authRepository.validatePassword(password = action.masterPassword)
            // A master password that is mistyped is told as such, and not sent to the vault.
            if ((validation as? ValidatePasswordResult.Success)?.isValid == false) {
                sendAction(KdfSettingsAction.Internal.InvalidMasterPasswordReceive)
                return@launch
            }
            val result = kdfSettingsManager.changeKdf(
                masterPassword = action.masterPassword,
                kdf = kdf,
            )
            if (result is ChangeKdfResult.MasterPasswordRejected) {
                // The vault knows the master password by the settings of another device, which
                // the next attempt proves the master password with once they are synced.
                vaultRepository.syncForResult()
            }
            sendAction(KdfSettingsAction.Internal.ChangeKdfResultReceive(result = result))
        }
    }

    private fun handleDismissDialog() {
        mutableStateFlow.update { it.copy(dialog = null) }
    }

    private fun handleInternalAction(action: KdfSettingsAction.Internal) {
        when (action) {
            is KdfSettingsAction.Internal.KdfReceive -> handleKdfReceive(action)
            is KdfSettingsAction.Internal.ChangeKdfResultReceive -> {
                handleChangeKdfResultReceive(action)
            }

            KdfSettingsAction.Internal.InvalidMasterPasswordReceive -> {
                handleInvalidMasterPasswordReceive()
            }
        }
    }

    private fun handleKdfReceive(action: KdfSettingsAction.Internal.KdfReceive) {
        mutableStateFlow.update {
            it.copy(viewState = action.kdf?.toContent() ?: KdfSettingsState.ViewState.Error)
        }
    }

    private fun handleChangeKdfResultReceive(
        action: KdfSettingsAction.Internal.ChangeKdfResultReceive,
    ) {
        when (val result = action.result) {
            ChangeKdfResult.Success -> {
                mutableStateFlow.update { it.copy(dialog = null) }
                sendEvent(
                    KdfSettingsEvent.ShowSnackbar(
                        data = BitwardenSnackbarData(
                            message = BitwardenString.encryption_settings_updated.asText(),
                        ),
                    ),
                )
            }

            ChangeKdfResult.MasterPasswordRejected -> {
                showErrorDialog(
                    message = BitwardenString
                        .your_encryption_key_settings_were_changed_on_another_device_try_again
                        .asText(),
                )
            }

            is ChangeKdfResult.Error -> {
                showErrorDialog(
                    message = BitwardenString.generic_error_message.asText(),
                    error = result.error,
                )
            }
        }
    }

    private fun handleInvalidMasterPasswordReceive() {
        showErrorDialog(message = BitwardenString.invalid_master_password.asText())
    }

    private fun showErrorDialog(message: Text, error: Throwable? = null) {
        mutableStateFlow.update {
            it.copy(
                dialog = KdfSettingsState.DialogState.Error(
                    title = BitwardenString.an_error_has_occurred.asText(),
                    message = message,
                    error = error,
                ),
            )
        }
    }

    private inline fun updateContent(
        crossinline block: (
            KdfSettingsState.ViewState.Content,
        ) -> KdfSettingsState.ViewState.Content,
    ) {
        val content = state.viewState as? KdfSettingsState.ViewState.Content ?: return
        mutableStateFlow.update { it.copy(viewState = block(content)) }
    }
}

/**
 * Models the state of the [KdfSettingsScreen].
 *
 * @property viewState What the screen shows.
 * @property dialog The dialog that is shown, if any.
 */
@Parcelize
data class KdfSettingsState(
    val viewState: ViewState,
    val dialog: DialogState?,
) : Parcelable {
    /**
     * Represents what the screen shows.
     */
    sealed class ViewState : Parcelable {
        /**
         * The settings of the account are being loaded.
         */
        @Parcelize
        data object Loading : ViewState()

        /**
         * The account has no settings to change, as it has no master password.
         */
        @Parcelize
        data object Error : ViewState()

        /**
         * The settings that the master key is to be derived with, as they are entered.
         *
         * @property algorithm The algorithm that the master key is derived with.
         * @property iterationsInput The number of iterations.
         * @property memoryInput The amount of memory in MB, which only [KdfAlgorithm.ARGON2ID]
         * uses.
         * @property parallelismInput The number of threads, which only [KdfAlgorithm.ARGON2ID]
         * uses.
         */
        @Parcelize
        data class Content(
            val algorithm: KdfAlgorithm,
            val iterationsInput: String,
            val memoryInput: String,
            val parallelismInput: String,
        ) : ViewState()
    }

    /**
     * Represents the dialogs of the screen.
     */
    sealed class DialogState : Parcelable {
        /**
         * Asks for the master password before the settings are changed.
         */
        @Parcelize
        data object MasterPasswordPrompt : DialogState()

        /**
         * The settings are being changed.
         */
        @Parcelize
        data class Loading(
            val message: Text,
        ) : DialogState()

        /**
         * The settings were not changed.
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
 * Models events for the [KdfSettingsScreen].
 */
sealed class KdfSettingsEvent {
    /**
     * Navigate back.
     */
    data object NavigateBack : KdfSettingsEvent()

    /**
     * Shows a snackbar with the given [data].
     */
    data class ShowSnackbar(
        val data: BitwardenSnackbarData,
    ) : KdfSettingsEvent()
}

/**
 * Models actions for the [KdfSettingsScreen].
 */
sealed class KdfSettingsAction {
    /**
     * The user clicked the back button.
     */
    data object BackClick : KdfSettingsAction()

    /**
     * The user chose the [algorithm] that the master key is derived with.
     */
    data class AlgorithmSelect(
        val algorithm: KdfAlgorithm,
    ) : KdfSettingsAction()

    /**
     * The user changed the number of iterations.
     */
    data class IterationsInputChange(
        val input: String,
    ) : KdfSettingsAction()

    /**
     * The user changed the amount of memory.
     */
    data class MemoryInputChange(
        val input: String,
    ) : KdfSettingsAction()

    /**
     * The user changed the number of threads.
     */
    data class ParallelismInputChange(
        val input: String,
    ) : KdfSettingsAction()

    /**
     * The user clicked the button that changes the settings.
     */
    data object UpdateClick : KdfSettingsAction()

    /**
     * The user confirmed the change with the [masterPassword].
     */
    data class MasterPasswordConfirm(
        val masterPassword: String,
    ) : KdfSettingsAction()

    /**
     * The user dismissed the dialog.
     */
    data object DismissDialog : KdfSettingsAction()

    /**
     * Models actions that the [KdfSettingsViewModel] itself might send.
     */
    sealed class Internal : KdfSettingsAction() {
        /**
         * The settings that the master key of the account is derived with were loaded, which
         * are `null` if it has no master password.
         */
        data class KdfReceive(
            val kdf: Kdf?,
        ) : Internal()

        /**
         * The master password that the change was confirmed with is not that of the account.
         */
        data object InvalidMasterPasswordReceive : Internal()

        /**
         * The settings were changed, or not, as the [result] tells.
         */
        data class ChangeKdfResultReceive(
            val result: ChangeKdfResult,
        ) : Internal()
    }
}

/**
 * Returns what the screen shows for these settings.
 */
private fun Kdf.toContent(): KdfSettingsState.ViewState.Content =
    when (this) {
        is Kdf.Pbkdf2 -> {
            KdfSettingsState.ViewState.Content(
                algorithm = KdfAlgorithm.PBKDF2_SHA256,
                iterationsInput = iterations.toString(),
                memoryInput = "",
                parallelismInput = "",
            )
        }

        is Kdf.Argon2id -> {
            KdfSettingsState.ViewState.Content(
                algorithm = KdfAlgorithm.ARGON2ID,
                iterationsInput = iterations.toString(),
                memoryInput = memory.toString(),
                parallelismInput = parallelism.toString(),
            )
        }
    }

/**
 * Returns what the screen shows once the algorithm is chosen: the settings that it is set to
 * when it is chosen.
 */
private fun KdfAlgorithm.toDefaultContent(): KdfSettingsState.ViewState.Content =
    when (this) {
        KdfAlgorithm.PBKDF2_SHA256 -> {
            KdfSettingsState.ViewState.Content(
                algorithm = this,
                iterationsInput = KdfSettingsConstants.DEFAULT_PBKDF2_ITERATIONS.toString(),
                memoryInput = "",
                parallelismInput = "",
            )
        }

        KdfAlgorithm.ARGON2ID -> {
            KdfSettingsState.ViewState.Content(
                algorithm = this,
                iterationsInput = KdfSettingsConstants.DEFAULT_ARGON2_ITERATIONS.toString(),
                memoryInput = KdfSettingsConstants.DEFAULT_ARGON2_MEMORY.toString(),
                parallelismInput = KdfSettingsConstants.DEFAULT_ARGON2_PARALLELISM.toString(),
            )
        }
    }

/**
 * Returns the message for the first setting that is not one that it may be changed to, or `null`
 * if every setting may be.
 */
private fun KdfSettingsState.ViewState.Content.validationErrorOrNull(): Text? =
    when (algorithm) {
        KdfAlgorithm.PBKDF2_SHA256 -> {
            iterationsInput.rangeErrorOrNull(
                range = KdfSettingsConstants.PBKDF2_ITERATIONS,
                message = BitwardenString.kdf_iterations_must_be_between_x_and_y,
            )
        }

        KdfAlgorithm.ARGON2ID -> {
            iterationsInput
                .rangeErrorOrNull(
                    range = KdfSettingsConstants.ARGON2_ITERATIONS,
                    message = BitwardenString.kdf_iterations_must_be_between_x_and_y,
                )
                ?: memoryInput.rangeErrorOrNull(
                    range = KdfSettingsConstants.ARGON2_MEMORY,
                    message = BitwardenString.kdf_memory_must_be_between_x_and_y_mb,
                )
                ?: parallelismInput.rangeErrorOrNull(
                    range = KdfSettingsConstants.ARGON2_PARALLELISM,
                    message = BitwardenString.kdf_parallelism_must_be_between_x_and_y,
                )
        }
    }

/**
 * Returns the [message] with the bounds of the [range] if this input is not a number in it, or
 * `null` if it is.
 */
private fun String.rangeErrorOrNull(range: IntRange, @StringRes message: Int): Text? {
    val number = toIntOrNull()
    return if (number != null && number in range) {
        null
    } else {
        message.asText(range.first, range.last)
    }
}

/**
 * Returns the settings that are entered, or `null` if a number that they take is not one.
 */
private fun KdfSettingsState.ViewState.Content.toKdfOrNull(): Kdf? {
    val iterations = iterationsInput.toIntOrNull()?.toUInt() ?: return null
    return when (algorithm) {
        KdfAlgorithm.PBKDF2_SHA256 -> Kdf.Pbkdf2(iterations = iterations)
        KdfAlgorithm.ARGON2ID -> {
            Kdf.Argon2id(
                iterations = iterations,
                memory = memoryInput.toIntOrNull()?.toUInt() ?: return null,
                parallelism = parallelismInput.toIntOrNull()?.toUInt() ?: return null,
            )
        }
    }
}
