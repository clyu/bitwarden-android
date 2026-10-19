package com.x8bit.bitwarden.ui.platform.feature.settings.accountsecurity.kdfsettings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.bitwarden.core.data.repository.util.bufferedMutableSharedFlow
import com.bitwarden.ui.platform.components.snackbar.model.BitwardenSnackbarData
import com.bitwarden.ui.util.asText
import com.bitwarden.ui.util.assertNoDialogExists
import com.x8bit.bitwarden.ui.platform.base.BitwardenComposeTest
import com.x8bit.bitwarden.ui.platform.feature.settings.accountsecurity.kdfsettings.model.KdfAlgorithm
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KdfSettingsScreenTest : BitwardenComposeTest() {

    private var onNavigateBackCalled = false

    private val mutableEventFlow = bufferedMutableSharedFlow<KdfSettingsEvent>()
    private val mutableStateFlow = MutableStateFlow(DEFAULT_STATE)
    private val viewModel = mockk<KdfSettingsViewModel>(relaxed = true) {
        every { eventFlow } returns mutableEventFlow
        every { stateFlow } returns mutableStateFlow
    }

    @Before
    fun setUp() {
        setContent {
            KdfSettingsScreen(
                onNavigateBack = { onNavigateBackCalled = true },
                viewModel = viewModel,
            )
        }
    }

    @Test
    fun `on NavigateBack should call onNavigateBack`() {
        mutableEventFlow.tryEmit(KdfSettingsEvent.NavigateBack)

        assertTrue(onNavigateBackCalled)
    }

    @Test
    fun `on back click should send BackClick`() {
        composeTestRule.onNodeWithContentDescription("Back").performClick()

        verify { viewModel.trySendAction(KdfSettingsAction.BackClick) }
    }

    @Test
    fun `on ShowSnackbar should show the snackbar`() {
        val message = "Encryption settings updated"
        mutableEventFlow.tryEmit(
            KdfSettingsEvent.ShowSnackbar(data = BitwardenSnackbarData(message = message.asText())),
        )

        composeTestRule.onNodeWithText(message).assertIsDisplayed()
    }

    @Test
    fun `PBKDF2 settings should show the number of iterations only`() {
        composeTestRule
            .onNodeWithContentDescription("PBKDF2 SHA-256. Algorithm")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("KDF iterations").performScrollTo().assertIsDisplayed()
        composeTestRule
            .onNodeWithText("We recommend 600,000 or more.")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("KDF memory (MB)").assertDoesNotExist()
        composeTestRule.onNodeWithText("KDF parallelism").assertDoesNotExist()
    }

    @Test
    fun `Argon2id settings should show the iterations, the memory and the parallelism`() {
        mutableStateFlow.update { it.copy(viewState = ARGON2_CONTENT) }

        composeTestRule
            .onNodeWithContentDescription("Argon2id. Algorithm")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("KDF iterations").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("KDF memory (MB)").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("KDF parallelism").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("We recommend 600,000 or more.").assertDoesNotExist()
    }

    @Test
    fun `on algorithm selection should send AlgorithmSelect`() {
        composeTestRule
            .onNodeWithContentDescription("PBKDF2 SHA-256. Algorithm")
            .performScrollTo()
            .performClick()
        composeTestRule
            .onAllNodesWithText("Argon2id")
            .filterToOne(hasAnyAncestor(isDialog()))
            .performClick()

        verify {
            viewModel.trySendAction(KdfSettingsAction.AlgorithmSelect(KdfAlgorithm.ARGON2ID))
        }
    }

    @Test
    fun `on iterations input should send IterationsInputChange`() {
        composeTestRule.onNodeWithText("KDF iterations").performTextReplacement("700000")

        verify {
            viewModel.trySendAction(KdfSettingsAction.IterationsInputChange(input = "700000"))
        }
    }

    @Test
    fun `on memory input should send MemoryInputChange`() {
        mutableStateFlow.update { it.copy(viewState = ARGON2_CONTENT) }

        composeTestRule
            .onNodeWithText("KDF memory (MB)")
            .performScrollTo()
            .performTextReplacement("128")

        verify { viewModel.trySendAction(KdfSettingsAction.MemoryInputChange(input = "128")) }
    }

    @Test
    fun `on parallelism input should send ParallelismInputChange`() {
        mutableStateFlow.update { it.copy(viewState = ARGON2_CONTENT) }

        composeTestRule
            .onNodeWithText("KDF parallelism")
            .performScrollTo()
            .performTextReplacement("2")

        verify { viewModel.trySendAction(KdfSettingsAction.ParallelismInputChange(input = "2")) }
    }

    @Test
    fun `on update click should send UpdateClick`() {
        composeTestRule
            .onNodeWithText("Update encryption settings")
            .performScrollTo()
            .performClick()

        verify { viewModel.trySendAction(KdfSettingsAction.UpdateClick) }
    }

    @Test
    fun `master password prompt should send MasterPasswordConfirm with the master password`() {
        composeTestRule.assertNoDialogExists()
        mutableStateFlow.update {
            it.copy(dialog = KdfSettingsState.DialogState.MasterPasswordPrompt)
        }

        composeTestRule
            .onAllNodesWithText("Update your encryption settings")
            .filterToOne(hasAnyAncestor(isDialog()))
            .assertIsDisplayed()
        composeTestRule
            .onAllNodesWithText("Master password")
            .filterToOne(hasAnyAncestor(isDialog()))
            .performTextInput(PASSWORD)
        composeTestRule
            .onAllNodesWithText("Update settings")
            .filterToOne(hasAnyAncestor(isDialog()))
            .performClick()

        verify {
            viewModel.trySendAction(
                KdfSettingsAction.MasterPasswordConfirm(masterPassword = PASSWORD),
            )
        }
    }

    @Test
    fun `master password prompt cancel should send DismissDialog`() {
        mutableStateFlow.update {
            it.copy(dialog = KdfSettingsState.DialogState.MasterPasswordPrompt)
        }

        composeTestRule
            .onAllNodesWithText("Cancel")
            .filterToOne(hasAnyAncestor(isDialog()))
            .performClick()

        verify { viewModel.trySendAction(KdfSettingsAction.DismissDialog) }
    }

    @Test
    fun `loading dialog should be shown according to state`() {
        composeTestRule.assertNoDialogExists()

        mutableStateFlow.update {
            it.copy(dialog = KdfSettingsState.DialogState.Loading(message = "Saving…".asText()))
        }

        composeTestRule
            .onAllNodesWithText("Saving…")
            .filterToOne(hasAnyAncestor(isDialog()))
            .assertIsDisplayed()
    }

    @Test
    fun `error dialog should be shown according to state and dismissed with DismissDialog`() {
        composeTestRule.assertNoDialogExists()
        mutableStateFlow.update {
            it.copy(
                dialog = KdfSettingsState.DialogState.Error(
                    title = "An error has occurred".asText(),
                    message = "Invalid master password. Try again.".asText(),
                ),
            )
        }

        composeTestRule
            .onAllNodesWithText("Invalid master password. Try again.")
            .filterToOne(hasAnyAncestor(isDialog()))
            .assertIsDisplayed()
        composeTestRule
            .onAllNodesWithText("Okay")
            .filterToOne(hasAnyAncestor(isDialog()))
            .performClick()

        verify { viewModel.trySendAction(KdfSettingsAction.DismissDialog) }
    }

    @Test
    fun `loading view state should show no settings`() {
        mutableStateFlow.update { it.copy(viewState = KdfSettingsState.ViewState.Loading) }

        composeTestRule.onNodeWithText("KDF iterations").assertDoesNotExist()
        composeTestRule.onNodeWithText("Update encryption settings").assertDoesNotExist()
    }

    @Test
    fun `error view state should show an error message`() {
        mutableStateFlow.update { it.copy(viewState = KdfSettingsState.ViewState.Error) }

        composeTestRule
            .onNodeWithText(
                "We were unable to process your request. Please try again or contact us.",
            )
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Update encryption settings").assertDoesNotExist()
    }
}

private const val PASSWORD = "password"

private val ARGON2_CONTENT = KdfSettingsState.ViewState.Content(
    algorithm = KdfAlgorithm.ARGON2ID,
    iterationsInput = "6",
    memoryInput = "32",
    parallelismInput = "4",
)

private val DEFAULT_STATE = KdfSettingsState(
    viewState = KdfSettingsState.ViewState.Content(
        algorithm = KdfAlgorithm.PBKDF2_SHA256,
        iterationsInput = "650000",
        memoryInput = "",
        parallelismInput = "",
    ),
    dialog = null,
)
