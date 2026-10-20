package com.x8bit.bitwarden.ui.platform.feature.settings.accountsecurity.changemasterpassword

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.bitwarden.core.data.repository.util.bufferedMutableSharedFlow
import com.bitwarden.ui.util.asText
import com.bitwarden.ui.util.assertNoDialogExists
import com.x8bit.bitwarden.ui.platform.base.BitwardenComposeTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChangeMasterPasswordScreenTest : BitwardenComposeTest() {

    private var onNavigateBackCalled = false

    private val mutableEventFlow = bufferedMutableSharedFlow<ChangeMasterPasswordEvent>()
    private val mutableStateFlow = MutableStateFlow(DEFAULT_STATE)
    private val viewModel = mockk<ChangeMasterPasswordViewModel>(relaxed = true) {
        every { eventFlow } returns mutableEventFlow
        every { stateFlow } returns mutableStateFlow
    }

    @Before
    fun setUp() {
        setContent {
            ChangeMasterPasswordScreen(
                onNavigateBack = { onNavigateBackCalled = true },
                viewModel = viewModel,
            )
        }
    }

    @Test
    fun `on NavigateBack should call onNavigateBack`() {
        mutableEventFlow.tryEmit(ChangeMasterPasswordEvent.NavigateBack)

        assertTrue(onNavigateBackCalled)
    }

    @Test
    fun `on back click should send BackClick`() {
        composeTestRule.onNodeWithContentDescription("Back").performClick()

        verify { viewModel.trySendAction(ChangeMasterPasswordAction.BackClick) }
    }

    @Test
    fun `content should say what changing the master password does`() {
        composeTestRule
            .onNodeWithText(
                text = "You will be logged out of this device and need to log in again",
                substring = true,
            )
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(text = "Anyone who can read the vault folder can read the hint.")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(
                text = "Changing your master password does not lock out someone who knows the old",
                substring = true,
            )
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `on current master password input should send CurrentPasswordInputChange`() {
        composeTestRule
            .onNodeWithText("Current master password (required)")
            .performScrollTo()
            .performTextInput(PASSWORD)

        verify {
            viewModel.trySendAction(
                ChangeMasterPasswordAction.CurrentPasswordInputChange(input = PASSWORD),
            )
        }
    }

    @Test
    fun `on new master password input should send PasswordInputChange`() {
        composeTestRule
            .onNodeWithText("New master password (required)")
            .performScrollTo()
            .performTextInput(NEW_PASSWORD)

        verify {
            viewModel.trySendAction(
                ChangeMasterPasswordAction.PasswordInputChange(input = NEW_PASSWORD),
            )
        }
    }

    @Test
    fun `on new master password input again should send RetypePasswordInputChange`() {
        composeTestRule
            .onNodeWithText("Re-type new master password (required)")
            .performScrollTo()
            .performTextInput(NEW_PASSWORD)

        verify {
            viewModel.trySendAction(
                ChangeMasterPasswordAction.RetypePasswordInputChange(input = NEW_PASSWORD),
            )
        }
    }

    @Test
    fun `on master password hint input should send PasswordHintInputChange`() {
        composeTestRule
            .onNodeWithText("Master password hint (optional)")
            .performScrollTo()
            .performTextInput(PASSWORD_HINT)

        verify {
            viewModel.trySendAction(
                ChangeMasterPasswordAction.PasswordHintInputChange(input = PASSWORD_HINT),
            )
        }
    }

    @Test
    fun `on check data breaches toggle should send CheckDataBreachesToggle`() {
        composeTestRule
            .onNodeWithText("Check known data breaches for this password")
            .performScrollTo()
            .performClick()

        verify {
            viewModel.trySendAction(
                ChangeMasterPasswordAction.CheckDataBreachesToggle(newState = false),
            )
        }
    }

    @Suppress("MaxLineLength")
    @Test
    fun `weak or exposed password dialog Yes click should send ContinueWithBreachedPasswordClick`() {
        mutableStateFlow.update { it.copy(dialog = WEAK_PASSWORD_DIALOG) }

        composeTestRule
            .onAllNodesWithText("Weak Master Password")
            .filterToOne(hasAnyAncestor(isDialog()))
            .assertIsDisplayed()
        composeTestRule
            .onAllNodesWithText("Yes")
            .filterToOne(hasAnyAncestor(isDialog()))
            .performClick()

        verify {
            viewModel.trySendAction(ChangeMasterPasswordAction.ContinueWithBreachedPasswordClick)
        }
    }

    @Test
    fun `weak or exposed password dialog No click should send DismissDialog`() {
        mutableStateFlow.update { it.copy(dialog = WEAK_PASSWORD_DIALOG) }

        composeTestRule
            .onAllNodesWithText("No")
            .filterToOne(hasAnyAncestor(isDialog()))
            .performClick()

        verify { viewModel.trySendAction(ChangeMasterPasswordAction.DismissDialog) }
    }

    @Test
    fun `on change master password click should send ChangeMasterPasswordClick`() {
        composeTestRule
            .onNodeWithTag("ChangeMasterPasswordButton")
            .performScrollTo()
            .performClick()

        verify { viewModel.trySendAction(ChangeMasterPasswordAction.ChangeMasterPasswordClick) }
    }

    @Test
    fun `syncing should show no form`() {
        mutableStateFlow.update { it.copy(isSyncing = true) }

        composeTestRule.onNodeWithText("Current master password (required)").assertDoesNotExist()
        composeTestRule.onNodeWithTag("ChangeMasterPasswordButton").assertDoesNotExist()
    }

    @Test
    fun `loading dialog should be shown according to state`() {
        composeTestRule.assertNoDialogExists()

        mutableStateFlow.update {
            it.copy(
                dialog = ChangeMasterPasswordState.DialogState.Loading(
                    message = "Updating password".asText(),
                ),
            )
        }

        composeTestRule
            .onAllNodesWithText("Updating password")
            .filterToOne(hasAnyAncestor(isDialog()))
            .assertIsDisplayed()
    }

    @Test
    fun `error dialog should be shown according to state and dismissed with DismissDialog`() {
        composeTestRule.assertNoDialogExists()
        mutableStateFlow.update {
            it.copy(
                dialog = ChangeMasterPasswordState.DialogState.Error(
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

        verify { viewModel.trySendAction(ChangeMasterPasswordAction.DismissDialog) }
    }
}

private const val NEW_PASSWORD = "newMasterPassword"
private const val PASSWORD = "password"
private const val PASSWORD_HINT = "mockPasswordHint"

private val WEAK_PASSWORD_DIALOG = ChangeMasterPasswordState.DialogState.HaveIBeenPwned(
    title = "Weak Master Password".asText(),
    message = "Are you sure you want to use a weak password?".asText(),
)

private val DEFAULT_STATE = ChangeMasterPasswordState(
    passwordHintInput = "",
    isCheckDataBreachesToggled = true,
    minimumPasswordLength = 12,
    isSyncing = false,
    dialog = null,
)
