package com.x8bit.bitwarden.ui.platform.feature.settings.accountsecurity.kdfsettings

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.bitwarden.crypto.Kdf
import com.bitwarden.ui.platform.base.BaseViewModelTest
import com.bitwarden.ui.platform.components.snackbar.model.BitwardenSnackbarData
import com.bitwarden.ui.platform.resource.BitwardenString
import com.bitwarden.ui.util.asText
import com.x8bit.bitwarden.data.auth.manager.KdfSettingsManager
import com.x8bit.bitwarden.data.auth.manager.model.ChangeKdfResult
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.auth.repository.model.ValidatePasswordResult
import com.x8bit.bitwarden.data.vault.manager.model.SyncVaultDataResult
import com.x8bit.bitwarden.data.vault.repository.VaultRepository
import com.x8bit.bitwarden.ui.platform.feature.settings.accountsecurity.kdfsettings.model.KdfAlgorithm
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KdfSettingsViewModelTest : BaseViewModelTest() {

    private val authRepository: AuthRepository = mockk {
        coEvery {
            validatePassword(password = PASSWORD)
        } returns ValidatePasswordResult.Success(isValid = true)
    }
    private val vaultRepository: VaultRepository = mockk {
        coEvery {
            syncForResult(forced = false)
        } returns SyncVaultDataResult.Success(itemsAvailable = true)
    }
    private val kdfSettingsManager: KdfSettingsManager = mockk {
        every { activeAccountKdf } returns CURRENT_KDF
        coEvery {
            changeKdf(masterPassword = PASSWORD, kdf = any())
        } returns ChangeKdfResult.Success
    }

    @Test
    fun `initial state should show the settings of the account once it synced`() {
        val viewModel = createViewModel()

        assertEquals(DEFAULT_STATE, viewModel.stateFlow.value)
        coVerifyOrder {
            vaultRepository.syncForResult(forced = false)
            kdfSettingsManager.activeAccountKdf
        }
    }

    @Test
    fun `initial state should be Loading until the account synced`() {
        val syncResult = CompletableDeferred<SyncVaultDataResult>()
        coEvery { vaultRepository.syncForResult(forced = false) } coAnswers { syncResult.await() }

        val viewModel = createViewModel()

        assertEquals(
            DEFAULT_STATE.copy(viewState = KdfSettingsState.ViewState.Loading),
            viewModel.stateFlow.value,
        )
        coVerify(exactly = 0) { kdfSettingsManager.activeAccountKdf }

        syncResult.complete(SyncVaultDataResult.Success(itemsAvailable = true))

        assertEquals(DEFAULT_STATE, viewModel.stateFlow.value)
    }

    @Test
    fun `initial state should show the settings of the app when the sync fails`() {
        coEvery {
            vaultRepository.syncForResult(forced = false)
        } returns SyncVaultDataResult.Error(throwable = Throwable("Fail!"))

        val viewModel = createViewModel()

        assertEquals(DEFAULT_STATE, viewModel.stateFlow.value)
    }

    @Test
    fun `initial state should show the Argon2id settings of the account`() {
        every {
            kdfSettingsManager.activeAccountKdf
        } returns Kdf.Argon2id(iterations = 3u, memory = 64u, parallelism = 4u)

        val viewModel = createViewModel()

        assertEquals(
            DEFAULT_STATE.copy(
                viewState = KdfSettingsState.ViewState.Content(
                    algorithm = KdfAlgorithm.ARGON2ID,
                    iterationsInput = "3",
                    memoryInput = "64",
                    parallelismInput = "4",
                ),
            ),
            viewModel.stateFlow.value,
        )
    }

    @Test
    fun `initial state should be Error when the account has no master password`() {
        every { kdfSettingsManager.activeAccountKdf } returns null

        val viewModel = createViewModel()

        assertEquals(
            DEFAULT_STATE.copy(viewState = KdfSettingsState.ViewState.Error),
            viewModel.stateFlow.value,
        )
    }

    @Test
    fun `initial state should be the saved state without syncing again`() {
        val state = DEFAULT_STATE.copy(
            viewState = CONTENT.copy(iterationsInput = "700000"),
            dialog = KdfSettingsState.DialogState.MasterPasswordPrompt,
        )

        val viewModel = createViewModel(state = state)

        assertEquals(state, viewModel.stateFlow.value)
        coVerify(exactly = 0) { vaultRepository.syncForResult(forced = any()) }
    }

    @Test
    fun `BackClick should emit NavigateBack`() = runTest {
        val viewModel = createViewModel()

        viewModel.eventFlow.test {
            viewModel.trySendAction(KdfSettingsAction.BackClick)
            assertEquals(KdfSettingsEvent.NavigateBack, awaitItem())
        }
    }

    @Test
    fun `AlgorithmSelect should set the settings that the algorithm is chosen with`() {
        val viewModel = createViewModel()

        viewModel.trySendAction(KdfSettingsAction.AlgorithmSelect(KdfAlgorithm.ARGON2ID))
        assertEquals(
            DEFAULT_STATE.copy(
                viewState = KdfSettingsState.ViewState.Content(
                    algorithm = KdfAlgorithm.ARGON2ID,
                    iterationsInput = "6",
                    memoryInput = "32",
                    parallelismInput = "4",
                ),
            ),
            viewModel.stateFlow.value,
        )

        viewModel.trySendAction(KdfSettingsAction.AlgorithmSelect(KdfAlgorithm.PBKDF2_SHA256))
        assertEquals(
            DEFAULT_STATE.copy(viewState = CONTENT.copy(iterationsInput = "600000")),
            viewModel.stateFlow.value,
        )
    }

    @Test
    fun `AlgorithmSelect of the algorithm that is chosen should keep what was entered`() {
        val viewModel = createViewModel()
        viewModel.trySendAction(KdfSettingsAction.IterationsInputChange(input = "700000"))

        viewModel.trySendAction(KdfSettingsAction.AlgorithmSelect(KdfAlgorithm.PBKDF2_SHA256))

        assertEquals(
            DEFAULT_STATE.copy(viewState = CONTENT.copy(iterationsInput = "700000")),
            viewModel.stateFlow.value,
        )
    }

    @Test
    fun `input changes should update what is entered`() {
        val viewModel = createViewModel()
        viewModel.trySendAction(KdfSettingsAction.AlgorithmSelect(KdfAlgorithm.ARGON2ID))

        viewModel.trySendAction(KdfSettingsAction.IterationsInputChange(input = "7"))
        viewModel.trySendAction(KdfSettingsAction.MemoryInputChange(input = "128"))
        viewModel.trySendAction(KdfSettingsAction.ParallelismInputChange(input = "2"))

        assertEquals(
            DEFAULT_STATE.copy(
                viewState = KdfSettingsState.ViewState.Content(
                    algorithm = KdfAlgorithm.ARGON2ID,
                    iterationsInput = "7",
                    memoryInput = "128",
                    parallelismInput = "2",
                ),
            ),
            viewModel.stateFlow.value,
        )
    }

    @Test
    fun `UpdateClick with settings that are allowed should ask for the master password`() {
        val viewModel = createViewModel()

        viewModel.trySendAction(KdfSettingsAction.UpdateClick)

        assertEquals(
            DEFAULT_STATE.copy(dialog = KdfSettingsState.DialogState.MasterPasswordPrompt),
            viewModel.stateFlow.value,
        )
    }

    @Test
    fun `UpdateClick with PBKDF2 iterations that are not allowed should show an error`() {
        val viewModel = createViewModel()

        listOf("599999", "2000001", "", "six hundred thousand").forEach { input ->
            viewModel.trySendAction(KdfSettingsAction.IterationsInputChange(input = input))
            viewModel.trySendAction(KdfSettingsAction.UpdateClick)

            assertEquals(
                DEFAULT_STATE.copy(
                    viewState = CONTENT.copy(iterationsInput = input),
                    dialog = KdfSettingsState.DialogState.Error(
                        title = BitwardenString.an_error_has_occurred.asText(),
                        message = BitwardenString
                            .kdf_iterations_must_be_between_x_and_y
                            .asText(600_000, 2_000_000),
                    ),
                ),
                viewModel.stateFlow.value,
            )
            viewModel.trySendAction(KdfSettingsAction.DismissDialog)
        }
    }

    @Test
    fun `UpdateClick with Argon2id settings that are not allowed should show an error`() {
        val viewModel = createViewModel()
        viewModel.trySendAction(KdfSettingsAction.AlgorithmSelect(KdfAlgorithm.ARGON2ID))
        val content = KdfSettingsState.ViewState.Content(
            algorithm = KdfAlgorithm.ARGON2ID,
            iterationsInput = "6",
            memoryInput = "32",
            parallelismInput = "4",
        )

        listOf(
            KdfSettingsAction.IterationsInputChange(input = "11") to BitwardenString
                .kdf_iterations_must_be_between_x_and_y
                .asText(2, 10),
            KdfSettingsAction.MemoryInputChange(input = "15") to BitwardenString
                .kdf_memory_must_be_between_x_and_y_mb
                .asText(16, 1024),
            KdfSettingsAction.ParallelismInputChange(input = "17") to BitwardenString
                .kdf_parallelism_must_be_between_x_and_y
                .asText(1, 16),
        ).forEach { (inputChange, message) ->
            viewModel.trySendAction(inputChange)
            viewModel.trySendAction(KdfSettingsAction.UpdateClick)

            assertEquals(
                KdfSettingsState.DialogState.Error(
                    title = BitwardenString.an_error_has_occurred.asText(),
                    message = message,
                ),
                viewModel.stateFlow.value.dialog,
            )
            viewModel.trySendAction(KdfSettingsAction.DismissDialog)
            // Puts back what was allowed, so that the next setting is the one that is checked.
            viewModel.trySendAction(KdfSettingsAction.IterationsInputChange(input = "6"))
            viewModel.trySendAction(KdfSettingsAction.MemoryInputChange(input = "32"))
            viewModel.trySendAction(KdfSettingsAction.ParallelismInputChange(input = "4"))
        }
        assertEquals(DEFAULT_STATE.copy(viewState = content), viewModel.stateFlow.value)
    }

    @Suppress("MaxLineLength")
    @Test
    fun `MasterPasswordConfirm should change the settings and show a snackbar once they are changed`() =
        runTest {
            val viewModel = createViewModel()
            viewModel.trySendAction(KdfSettingsAction.IterationsInputChange(input = "700000"))
            val state = DEFAULT_STATE.copy(viewState = CONTENT.copy(iterationsInput = "700000"))

            viewModel.stateEventFlow(backgroundScope) { stateFlow, eventFlow ->
                assertEquals(state, stateFlow.awaitItem())

                viewModel.trySendAction(
                    KdfSettingsAction.MasterPasswordConfirm(masterPassword = PASSWORD),
                )

                assertEquals(
                    state.copy(
                        dialog = KdfSettingsState.DialogState.Loading(
                            message = BitwardenString.saving.asText(),
                        ),
                    ),
                    stateFlow.awaitItem(),
                )
                assertEquals(state, stateFlow.awaitItem())
                assertEquals(
                    KdfSettingsEvent.ShowSnackbar(
                        data = BitwardenSnackbarData(
                            message = BitwardenString.encryption_settings_updated.asText(),
                        ),
                    ),
                    eventFlow.awaitItem(),
                )
            }
            coVerify(exactly = 1) {
                kdfSettingsManager.changeKdf(
                    masterPassword = PASSWORD,
                    kdf = Kdf.Pbkdf2(iterations = 700_000u),
                )
            }
        }

    @Test
    fun `MasterPasswordConfirm should change the settings to Argon2id`() {
        val viewModel = createViewModel()
        viewModel.trySendAction(KdfSettingsAction.AlgorithmSelect(KdfAlgorithm.ARGON2ID))

        viewModel.trySendAction(KdfSettingsAction.MasterPasswordConfirm(masterPassword = PASSWORD))

        coVerify(exactly = 1) {
            kdfSettingsManager.changeKdf(
                masterPassword = PASSWORD,
                kdf = Kdf.Argon2id(iterations = 6u, memory = 32u, parallelism = 4u),
            )
        }
    }

    @Suppress("MaxLineLength")
    @Test
    fun `MasterPasswordConfirm with a mistyped master password should show an error and change nothing`() {
        coEvery {
            authRepository.validatePassword(password = PASSWORD)
        } returns ValidatePasswordResult.Success(isValid = false)
        val viewModel = createViewModel()

        viewModel.trySendAction(KdfSettingsAction.MasterPasswordConfirm(masterPassword = PASSWORD))

        assertEquals(
            DEFAULT_STATE.copy(
                dialog = KdfSettingsState.DialogState.Error(
                    title = BitwardenString.an_error_has_occurred.asText(),
                    message = BitwardenString.invalid_master_password.asText(),
                ),
            ),
            viewModel.stateFlow.value,
        )
        coVerify(exactly = 0) {
            kdfSettingsManager.changeKdf(masterPassword = any(), kdf = any())
        }
    }

    @Suppress("MaxLineLength")
    @Test
    fun `MasterPasswordConfirm when the master password cannot be checked should leave it to the vault`() {
        coEvery {
            authRepository.validatePassword(password = PASSWORD)
        } returns ValidatePasswordResult.Error(error = Throwable("Fail!"))
        val viewModel = createViewModel()

        viewModel.trySendAction(KdfSettingsAction.MasterPasswordConfirm(masterPassword = PASSWORD))

        assertEquals(DEFAULT_STATE, viewModel.stateFlow.value)
        coVerify(exactly = 1) {
            kdfSettingsManager.changeKdf(masterPassword = PASSWORD, kdf = CURRENT_KDF)
        }
    }

    @Suppress("MaxLineLength")
    @Test
    fun `MasterPasswordConfirm when the vault refuses the master password should sync and show an error`() {
        coEvery {
            kdfSettingsManager.changeKdf(masterPassword = PASSWORD, kdf = CURRENT_KDF)
        } returns ChangeKdfResult.MasterPasswordRejected
        val viewModel = createViewModel()

        viewModel.trySendAction(KdfSettingsAction.MasterPasswordConfirm(masterPassword = PASSWORD))

        assertEquals(
            DEFAULT_STATE.copy(
                dialog = KdfSettingsState.DialogState.Error(
                    title = BitwardenString.an_error_has_occurred.asText(),
                    message = BitwardenString
                        .your_encryption_key_settings_were_changed_on_another_device_try_again
                        .asText(),
                ),
            ),
            viewModel.stateFlow.value,
        )
        // Once when the screen opens, and once more after the change was refused.
        coVerify(exactly = 2) { vaultRepository.syncForResult(forced = false) }
    }

    @Test
    fun `MasterPasswordConfirm when the change fails should show an error`() {
        val error = Throwable("Fail!")
        coEvery {
            kdfSettingsManager.changeKdf(masterPassword = PASSWORD, kdf = CURRENT_KDF)
        } returns ChangeKdfResult.Error(error = error)
        val viewModel = createViewModel()

        viewModel.trySendAction(KdfSettingsAction.MasterPasswordConfirm(masterPassword = PASSWORD))

        assertEquals(
            DEFAULT_STATE.copy(
                dialog = KdfSettingsState.DialogState.Error(
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
            state = DEFAULT_STATE.copy(dialog = KdfSettingsState.DialogState.MasterPasswordPrompt),
        )

        viewModel.trySendAction(KdfSettingsAction.DismissDialog)

        assertEquals(DEFAULT_STATE, viewModel.stateFlow.value)
    }

    private fun createViewModel(
        state: KdfSettingsState? = null,
    ): KdfSettingsViewModel = KdfSettingsViewModel(
        authRepository = authRepository,
        vaultRepository = vaultRepository,
        kdfSettingsManager = kdfSettingsManager,
        savedStateHandle = SavedStateHandle().apply { set("state", state) },
    )
}

private const val PASSWORD = "password"

private val CURRENT_KDF = Kdf.Pbkdf2(iterations = 650_000u)

private val CONTENT = KdfSettingsState.ViewState.Content(
    algorithm = KdfAlgorithm.PBKDF2_SHA256,
    iterationsInput = "650000",
    memoryInput = "",
    parallelismInput = "",
)

private val DEFAULT_STATE = KdfSettingsState(
    viewState = CONTENT,
    dialog = null,
)
