package com.x8bit.bitwarden.ui.platform.feature.settings.accountsecurity.changemasterpassword

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.bitwarden.ui.platform.base.BaseViewModelTest
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
import com.x8bit.bitwarden.data.vault.manager.model.SyncVaultDataResult
import com.x8bit.bitwarden.data.vault.repository.VaultRepository
import com.x8bit.bitwarden.ui.auth.feature.completeregistration.PasswordStrengthState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChangeMasterPasswordViewModelTest : BaseViewModelTest() {

    private val authRepository: AuthRepository = mockk {
        coEvery {
            validatePassword(password = PASSWORD)
        } returns ValidatePasswordResult.Success(isValid = true)
        every {
            getPasswordStrength(password = any())
        } returns PasswordStrengthResult.Success(passwordStrength = PasswordStrength.LEVEL_4)
        coEvery {
            getPasswordBreachCount(password = any())
        } returns BreachCountResult.Success(breachCount = 0)
    }
    private val vaultRepository: VaultRepository = mockk {
        coEvery {
            syncForResult(forced = false)
        } returns SyncVaultDataResult.Success(itemsAvailable = true)
    }
    private val masterPasswordManager: MasterPasswordManager = mockk {
        coEvery {
            changeMasterPassword(
                currentPassword = PASSWORD,
                newPassword = NEW_PASSWORD,
                passwordHint = any(),
            )
        } returns ChangeMasterPasswordResult.Success
    }

    @Test
    fun `initial state should show the form once the vault synced`() {
        val viewModel = createViewModel()

        assertEquals(DEFAULT_STATE, viewModel.stateFlow.value)
        coVerify(exactly = 1) { vaultRepository.syncForResult(forced = false) }
    }

    @Test
    fun `initial state should be syncing until the vault synced`() {
        val syncResult = CompletableDeferred<SyncVaultDataResult>()
        coEvery { vaultRepository.syncForResult(forced = false) } coAnswers { syncResult.await() }

        val viewModel = createViewModel()

        assertEquals(DEFAULT_STATE.copy(isSyncing = true), viewModel.stateFlow.value)

        syncResult.complete(SyncVaultDataResult.Success(itemsAvailable = true))

        assertEquals(DEFAULT_STATE, viewModel.stateFlow.value)
    }

    @Test
    fun `initial state should show the form when the sync fails`() {
        coEvery {
            vaultRepository.syncForResult(forced = false)
        } returns SyncVaultDataResult.Error(throwable = Throwable("Fail!"))

        val viewModel = createViewModel()

        assertEquals(DEFAULT_STATE, viewModel.stateFlow.value)
    }

    @Test
    fun `initial state should be the saved state without syncing again`() {
        val state = DEFAULT_STATE.copy(passwordHintInput = PASSWORD_HINT)

        val viewModel = createViewModel(state = state)

        assertEquals(state, viewModel.stateFlow.value)
        coVerify(exactly = 0) { vaultRepository.syncForResult(forced = any()) }
    }

    @Test
    fun `BackClick should emit NavigateBack`() = runTest {
        val viewModel = createViewModel()

        viewModel.eventFlow.test {
            viewModel.trySendAction(ChangeMasterPasswordAction.BackClick)
            assertEquals(ChangeMasterPasswordEvent.NavigateBack, awaitItem())
        }
    }

    @Test
    fun `input changes should update what is entered`() {
        val viewModel = createViewModel()

        viewModel.enterInputs()

        assertEquals(FILLED_STATE, viewModel.stateFlow.value)
    }

    @Test
    fun `PasswordInputChange should update how strong the new master password is`() {
        every {
            authRepository.getPasswordStrength(password = "weak")
        } returns PasswordStrengthResult.Success(passwordStrength = PasswordStrength.LEVEL_1)
        val viewModel = createViewModel()

        viewModel.trySendAction(ChangeMasterPasswordAction.PasswordInputChange(input = "weak"))

        assertEquals(
            DEFAULT_STATE.copy(
                passwordInput = "weak",
                passwordStrengthState = PasswordStrengthState.WEAK_2,
            ),
            viewModel.stateFlow.value,
        )

        viewModel.trySendAction(ChangeMasterPasswordAction.PasswordInputChange(input = ""))

        assertEquals(DEFAULT_STATE, viewModel.stateFlow.value)
    }

    @Test
    fun `CheckDataBreachesToggle should update whether data breaches are checked`() {
        val viewModel = createViewModel()

        viewModel.trySendAction(
            ChangeMasterPasswordAction.CheckDataBreachesToggle(newState = false),
        )

        assertEquals(
            DEFAULT_STATE.copy(isCheckDataBreachesToggled = false),
            viewModel.stateFlow.value,
        )
    }

    @Suppress("MaxLineLength")
    @Test
    fun `ChangeMasterPasswordClick with a new master password that is too short should show an error`() {
        val viewModel = createViewModel()
        viewModel.enterInputs(newPassword = "short")

        viewModel.trySendAction(ChangeMasterPasswordAction.ChangeMasterPasswordClick)

        assertErrorDialog(
            viewModel = viewModel,
            message = BitwardenPlurals.master_password_length_val_message_x.asPluralsText(
                quantity = 12,
                args = arrayOf(12),
            ),
        )
    }

    @Suppress("MaxLineLength")
    @Test
    fun `ChangeMasterPasswordClick with a new master password that is entered differently again should show an error`() {
        val viewModel = createViewModel()
        viewModel.enterInputs()
        viewModel.trySendAction(
            ChangeMasterPasswordAction.RetypePasswordInputChange(input = "$NEW_PASSWORD!"),
        )

        viewModel.trySendAction(ChangeMasterPasswordAction.ChangeMasterPasswordClick)

        assertErrorDialog(
            viewModel = viewModel,
            message = BitwardenString.master_password_confirmation_val_message.asText(),
        )
    }

    @Suppress("MaxLineLength")
    @Test
    fun `ChangeMasterPasswordClick with a new master password that is the current one should show an error`() {
        val viewModel = createViewModel()
        viewModel.enterInputs(currentPassword = NEW_PASSWORD)

        viewModel.trySendAction(ChangeMasterPasswordAction.ChangeMasterPasswordClick)

        assertErrorDialog(
            viewModel = viewModel,
            message = BitwardenString
                .your_new_master_password_cannot_be_the_same_as_your_current_one
                .asText(),
        )
    }

    @Suppress("MaxLineLength")
    @Test
    fun `ChangeMasterPasswordClick with a hint that is the new master password should show an error`() {
        val viewModel = createViewModel()
        viewModel.enterInputs(passwordHint = NEW_PASSWORD.uppercase())

        viewModel.trySendAction(ChangeMasterPasswordAction.ChangeMasterPasswordClick)

        assertErrorDialog(
            viewModel = viewModel,
            message = BitwardenString
                .your_master_password_hint_cannot_be_the_same_as_your_master_password
                .asText(),
        )
    }

    @Test
    fun `ChangeMasterPasswordClick should change the master password with what is entered`() =
        runTest {
            val viewModel = createViewModel()
            viewModel.enterInputs()

            viewModel.stateFlow.test {
                assertEquals(FILLED_STATE, awaitItem())

                viewModel.trySendAction(ChangeMasterPasswordAction.ChangeMasterPasswordClick)

                assertEquals(
                    FILLED_STATE.copy(
                        dialog = ChangeMasterPasswordState.DialogState.Loading(
                            message = BitwardenString.updating_password.asText(),
                        ),
                    ),
                    awaitItem(),
                )
                // The account is logged out of this device, which takes the app away from the
                // screen.
                assertEquals(FILLED_STATE, awaitItem())
            }
            coVerify(exactly = 1) {
                masterPasswordManager.changeMasterPassword(
                    currentPassword = PASSWORD,
                    newPassword = NEW_PASSWORD,
                    passwordHint = PASSWORD_HINT,
                )
            }
        }

    @Test
    fun `ChangeMasterPasswordClick should check data breaches before the change`() {
        val viewModel = createViewModel()
        viewModel.enterInputs()

        viewModel.trySendAction(ChangeMasterPasswordAction.ChangeMasterPasswordClick)

        assertEquals(FILLED_STATE, viewModel.stateFlow.value)
        coVerifyOrder {
            authRepository.validatePassword(password = PASSWORD)
            authRepository.getPasswordBreachCount(password = NEW_PASSWORD)
            masterPasswordManager.changeMasterPassword(
                currentPassword = PASSWORD,
                newPassword = NEW_PASSWORD,
                passwordHint = PASSWORD_HINT,
            )
        }
    }

    @Suppress("MaxLineLength")
    @Test
    fun `ChangeMasterPasswordClick with a weak new master password should ask to confirm it`() {
        every {
            authRepository.getPasswordStrength(password = NEW_PASSWORD)
        } returns PasswordStrengthResult.Success(passwordStrength = PasswordStrength.LEVEL_2)
        val viewModel = createViewModel()
        viewModel.enterInputs()

        viewModel.trySendAction(ChangeMasterPasswordAction.ChangeMasterPasswordClick)

        assertWarningDialog(
            viewModel = viewModel,
            title = BitwardenString.weak_master_password.asText(),
            message = BitwardenString
                .weak_password_identified_use_a_strong_password_to_protect_your_account
                .asText(),
        )
    }

    @Suppress("MaxLineLength")
    @Test
    fun `ChangeMasterPasswordClick with a new master password from a data breach should ask to confirm it`() {
        coEvery {
            authRepository.getPasswordBreachCount(password = NEW_PASSWORD)
        } returns BreachCountResult.Success(breachCount = 3)
        val viewModel = createViewModel()
        viewModel.enterInputs()

        viewModel.trySendAction(ChangeMasterPasswordAction.ChangeMasterPasswordClick)

        assertWarningDialog(
            viewModel = viewModel,
            title = BitwardenString.exposed_master_password.asText(),
            message = BitwardenString.password_found_in_a_data_breach_alert_description.asText(),
        )
    }

    @Suppress("MaxLineLength")
    @Test
    fun `ChangeMasterPasswordClick with a weak new master password from a data breach should ask to confirm it`() {
        every {
            authRepository.getPasswordStrength(password = NEW_PASSWORD)
        } returns PasswordStrengthResult.Success(passwordStrength = PasswordStrength.LEVEL_1)
        coEvery {
            authRepository.getPasswordBreachCount(password = NEW_PASSWORD)
        } returns BreachCountResult.Success(breachCount = 3)
        val viewModel = createViewModel()
        viewModel.enterInputs()

        viewModel.trySendAction(ChangeMasterPasswordAction.ChangeMasterPasswordClick)

        assertWarningDialog(
            viewModel = viewModel,
            title = BitwardenString.weak_and_exposed_master_password.asText(),
            message = BitwardenString
                .weak_password_identified_and_found_in_a_data_breach_alert_description
                .asText(),
        )
    }

    @Suppress("MaxLineLength")
    @Test
    fun `ChangeMasterPasswordClick without checking data breaches should change the master password`() {
        coEvery {
            authRepository.getPasswordBreachCount(password = NEW_PASSWORD)
        } returns BreachCountResult.Success(breachCount = 3)
        val viewModel = createViewModel()
        viewModel.enterInputs()
        viewModel.trySendAction(
            ChangeMasterPasswordAction.CheckDataBreachesToggle(newState = false),
        )

        viewModel.trySendAction(ChangeMasterPasswordAction.ChangeMasterPasswordClick)

        assertEquals(
            FILLED_STATE.copy(isCheckDataBreachesToggled = false),
            viewModel.stateFlow.value,
        )
        coVerify(exactly = 0) { authRepository.getPasswordBreachCount(password = any()) }
        coVerify(exactly = 1) {
            masterPasswordManager.changeMasterPassword(
                currentPassword = PASSWORD,
                newPassword = NEW_PASSWORD,
                passwordHint = PASSWORD_HINT,
            )
        }
    }

    @Suppress("MaxLineLength")
    @Test
    fun `ChangeMasterPasswordClick when data breaches cannot be checked should change the master password`() {
        coEvery {
            authRepository.getPasswordBreachCount(password = NEW_PASSWORD)
        } returns BreachCountResult.Error(error = Throwable("Fail!"))
        val viewModel = createViewModel()
        viewModel.enterInputs()

        viewModel.trySendAction(ChangeMasterPasswordAction.ChangeMasterPasswordClick)

        assertEquals(FILLED_STATE, viewModel.stateFlow.value)
        coVerify(exactly = 1) {
            masterPasswordManager.changeMasterPassword(
                currentPassword = PASSWORD,
                newPassword = NEW_PASSWORD,
                passwordHint = PASSWORD_HINT,
            )
        }
    }

    @Suppress("MaxLineLength")
    @Test
    fun `ContinueWithBreachedPasswordClick should change the master password without checking it again`() {
        every {
            authRepository.getPasswordStrength(password = NEW_PASSWORD)
        } returns PasswordStrengthResult.Success(passwordStrength = PasswordStrength.LEVEL_1)
        coEvery {
            authRepository.getPasswordBreachCount(password = NEW_PASSWORD)
        } returns BreachCountResult.Success(breachCount = 3)
        val viewModel = createViewModel()
        viewModel.enterInputs()
        viewModel.trySendAction(ChangeMasterPasswordAction.ChangeMasterPasswordClick)

        viewModel.trySendAction(ChangeMasterPasswordAction.ContinueWithBreachedPasswordClick)

        assertEquals(
            FILLED_STATE.copy(passwordStrengthState = PasswordStrengthState.WEAK_2),
            viewModel.stateFlow.value,
        )
        // Once for the click, and not again once the user confirmed the new master password.
        coVerify(exactly = 1) {
            authRepository.getPasswordBreachCount(password = NEW_PASSWORD)
            masterPasswordManager.changeMasterPassword(
                currentPassword = PASSWORD,
                newPassword = NEW_PASSWORD,
                passwordHint = PASSWORD_HINT,
            )
        }
    }

    @Test
    fun `ChangeMasterPasswordClick without a hint should change the master password`() {
        val viewModel = createViewModel()
        viewModel.enterInputs(passwordHint = " ")

        viewModel.trySendAction(ChangeMasterPasswordAction.ChangeMasterPasswordClick)

        coVerify(exactly = 1) {
            masterPasswordManager.changeMasterPassword(
                currentPassword = PASSWORD,
                newPassword = NEW_PASSWORD,
                passwordHint = null,
            )
        }
    }

    @Suppress("MaxLineLength")
    @Test
    fun `ChangeMasterPasswordClick with a mistyped current master password should show an error and change nothing`() {
        coEvery {
            authRepository.validatePassword(password = PASSWORD)
        } returns ValidatePasswordResult.Success(isValid = false)
        val viewModel = createViewModel()
        viewModel.enterInputs()

        viewModel.trySendAction(ChangeMasterPasswordAction.ChangeMasterPasswordClick)

        assertEquals(
            FILLED_STATE.copy(
                dialog = ChangeMasterPasswordState.DialogState.Error(
                    title = BitwardenString.an_error_has_occurred.asText(),
                    message = BitwardenString.invalid_master_password.asText(),
                ),
            ),
            viewModel.stateFlow.value,
        )
        coVerify(exactly = 0) {
            authRepository.getPasswordBreachCount(password = any())
            masterPasswordManager.changeMasterPassword(
                currentPassword = any(),
                newPassword = any(),
                passwordHint = any(),
            )
        }
    }

    @Suppress("MaxLineLength")
    @Test
    fun `ChangeMasterPasswordClick when the current master password cannot be checked should leave it to the vault`() {
        coEvery {
            authRepository.validatePassword(password = PASSWORD)
        } returns ValidatePasswordResult.Error(error = Throwable("Fail!"))
        val viewModel = createViewModel()
        viewModel.enterInputs()

        viewModel.trySendAction(ChangeMasterPasswordAction.ChangeMasterPasswordClick)

        assertEquals(FILLED_STATE, viewModel.stateFlow.value)
        coVerify(exactly = 1) {
            masterPasswordManager.changeMasterPassword(
                currentPassword = PASSWORD,
                newPassword = NEW_PASSWORD,
                passwordHint = PASSWORD_HINT,
            )
        }
    }

    @Suppress("MaxLineLength")
    @Test
    fun `ChangeMasterPasswordClick when the vault refuses the master password should sync and show an error`() {
        coEvery {
            masterPasswordManager.changeMasterPassword(
                currentPassword = PASSWORD,
                newPassword = NEW_PASSWORD,
                passwordHint = PASSWORD_HINT,
            )
        } returns ChangeMasterPasswordResult.MasterPasswordRejected
        val viewModel = createViewModel()
        viewModel.enterInputs()

        viewModel.trySendAction(ChangeMasterPasswordAction.ChangeMasterPasswordClick)

        assertErrorDialog(
            viewModel = viewModel,
            message = BitwardenString
                .your_encryption_key_settings_were_changed_on_another_device_try_again
                .asText(),
            shouldChange = true,
        )
        // Once when the screen opens, and once more after the change was refused.
        coVerify(exactly = 2) { vaultRepository.syncForResult(forced = false) }
    }

    @Test
    fun `ChangeMasterPasswordClick when the change fails should show an error`() {
        val error = Throwable("Fail!")
        coEvery {
            masterPasswordManager.changeMasterPassword(
                currentPassword = PASSWORD,
                newPassword = NEW_PASSWORD,
                passwordHint = PASSWORD_HINT,
            )
        } returns ChangeMasterPasswordResult.Error(error = error)
        val viewModel = createViewModel()
        viewModel.enterInputs()

        viewModel.trySendAction(ChangeMasterPasswordAction.ChangeMasterPasswordClick)

        assertEquals(
            FILLED_STATE.copy(
                dialog = ChangeMasterPasswordState.DialogState.Error(
                    title = BitwardenString.an_error_has_occurred.asText(),
                    message = BitwardenString.generic_error_message.asText(),
                    error = error,
                ),
            ),
            viewModel.stateFlow.value,
        )
        coVerify(exactly = 1) { vaultRepository.syncForResult(forced = false) }
    }

    @Test
    fun `DismissDialog should remove the dialog`() {
        val viewModel = createViewModel(
            state = DEFAULT_STATE.copy(
                dialog = ChangeMasterPasswordState.DialogState.Error(
                    title = BitwardenString.an_error_has_occurred.asText(),
                    message = BitwardenString.generic_error_message.asText(),
                ),
            ),
        )

        viewModel.trySendAction(ChangeMasterPasswordAction.DismissDialog)

        assertEquals(DEFAULT_STATE, viewModel.stateFlow.value)
    }

    /**
     * Asserts that the error dialog with the [message] is shown over what was entered, and that
     * the master password was changed only if [shouldChange].
     */
    private fun assertErrorDialog(
        viewModel: ChangeMasterPasswordViewModel,
        message: Text,
        shouldChange: Boolean = false,
    ) {
        assertEquals(
            ChangeMasterPasswordState.DialogState.Error(
                title = BitwardenString.an_error_has_occurred.asText(),
                message = message,
            ),
            viewModel.stateFlow.value.dialog,
        )
        coVerify(exactly = if (shouldChange) 1 else 0) {
            masterPasswordManager.changeMasterPassword(
                currentPassword = any(),
                newPassword = any(),
                passwordHint = any(),
            )
        }
    }

    /**
     * Asserts that the user is asked to confirm the new master password with the [title] and the
     * [message], and that the master password was not changed.
     */
    private fun assertWarningDialog(
        viewModel: ChangeMasterPasswordViewModel,
        title: Text,
        message: Text,
    ) {
        assertEquals(
            ChangeMasterPasswordState.DialogState.HaveIBeenPwned(title = title, message = message),
            viewModel.stateFlow.value.dialog,
        )
        coVerify(exactly = 0) {
            masterPasswordManager.changeMasterPassword(
                currentPassword = any(),
                newPassword = any(),
                passwordHint = any(),
            )
        }
    }

    private fun ChangeMasterPasswordViewModel.enterInputs(
        currentPassword: String = PASSWORD,
        newPassword: String = NEW_PASSWORD,
        passwordHint: String = PASSWORD_HINT,
    ) {
        trySendAction(
            ChangeMasterPasswordAction.CurrentPasswordInputChange(input = currentPassword),
        )
        trySendAction(ChangeMasterPasswordAction.PasswordInputChange(input = newPassword))
        trySendAction(ChangeMasterPasswordAction.RetypePasswordInputChange(input = newPassword))
        trySendAction(ChangeMasterPasswordAction.PasswordHintInputChange(input = passwordHint))
    }

    private fun createViewModel(
        state: ChangeMasterPasswordState? = null,
    ): ChangeMasterPasswordViewModel = ChangeMasterPasswordViewModel(
        authRepository = authRepository,
        vaultRepository = vaultRepository,
        masterPasswordManager = masterPasswordManager,
        savedStateHandle = SavedStateHandle().apply { set("state", state) },
    )
}

private const val NEW_PASSWORD = "newMasterPassword"
private const val PASSWORD = "password"
private const val PASSWORD_HINT = "mockPasswordHint"

private val DEFAULT_STATE = ChangeMasterPasswordState(
    passwordHintInput = "",
    isCheckDataBreachesToggled = true,
    minimumPasswordLength = 12,
    isSyncing = false,
    dialog = null,
)

private val FILLED_STATE = DEFAULT_STATE.copy(
    currentPasswordInput = PASSWORD,
    passwordInput = NEW_PASSWORD,
    retypePasswordInput = NEW_PASSWORD,
    passwordStrengthState = PasswordStrengthState.STRONG,
    passwordHintInput = PASSWORD_HINT,
)
