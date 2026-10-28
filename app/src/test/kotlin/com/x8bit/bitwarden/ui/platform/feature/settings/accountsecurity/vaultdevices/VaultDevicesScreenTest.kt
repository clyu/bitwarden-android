package com.x8bit.bitwarden.ui.platform.feature.settings.accountsecurity.vaultdevices

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.bitwarden.core.data.repository.util.bufferedMutableSharedFlow
import com.bitwarden.ui.platform.components.snackbar.model.BitwardenSnackbarData
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

class VaultDevicesScreenTest : BitwardenComposeTest() {

    private var onNavigateBackCalled = false

    private val mutableEventFlow = bufferedMutableSharedFlow<VaultDevicesEvent>()
    private val mutableStateFlow = MutableStateFlow(DEFAULT_STATE)
    private val viewModel = mockk<VaultDevicesViewModel>(relaxed = true) {
        every { eventFlow } returns mutableEventFlow
        every { stateFlow } returns mutableStateFlow
    }

    @Before
    fun setUp() {
        setContent {
            VaultDevicesScreen(
                onNavigateBack = { onNavigateBackCalled = true },
                viewModel = viewModel,
            )
        }
    }

    @Test
    fun `on NavigateBack should call onNavigateBack`() {
        mutableEventFlow.tryEmit(VaultDevicesEvent.NavigateBack)

        assertTrue(onNavigateBackCalled)
    }

    @Test
    fun `on back click should send BackClick`() {
        composeTestRule.onNodeWithContentDescription("Back").performClick()

        verify { viewModel.trySendAction(VaultDevicesAction.BackClick) }
    }

    @Test
    fun `on ShowSnackbar should show the snackbar`() {
        mutableEventFlow.tryEmit(
            VaultDevicesEvent.ShowSnackbar(
                data = BitwardenSnackbarData(message = "Device retired".asText()),
            ),
        )

        composeTestRule.onNodeWithText("Device retired").assertIsDisplayed()
    }

    @Test
    fun `devices should be listed with this one marked and when each last changed the vault`() {
        composeTestRule.onNodeWithText("This device").assertIsDisplayed()
        composeTestRule.onNodeWithText("Mobile - Android").assertIsDisplayed()
        composeTestRule.onNodeWithText("mockDeviceName-1").assertIsDisplayed()
        composeTestRule.onNodeWithText("mockDeviceId-1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Has not changed the vault").assertIsDisplayed()
        composeTestRule.onNodeWithText("Extension - Chrome").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("chrome").performScrollTo().assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Last changed the vault on Jan 1, 2025")
            .performScrollTo()
            .assertIsDisplayed()
        // A device whose file does not say what it is, which the list has yet to scroll to.
        composeTestRule
            .onNode(hasScrollToNodeAction())
            .performScrollToNode(hasText("Unknown device"))
        composeTestRule.onNodeWithText("Unknown device").assertIsDisplayed()
        composeTestRule.onNodeWithText("mockDeviceId-3").performScrollTo().assertIsDisplayed()
        composeTestRule
            .onNodeWithText(
                "Retired on Oct 28, 2026. Its folder is deleted after Jan 26, 2027, " +
                    "unless it is used again.",
            )
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `on retire click should send RetireClick for the only device that can be retired`() {
        composeTestRule.onNodeWithText("Retire").performScrollTo().performClick()

        verify {
            viewModel.trySendAction(VaultDevicesAction.RetireClick(deviceId = "mockDeviceId-2"))
        }
    }

    @Test
    fun `confirmation dialog should send RetireConfirm, or DismissDialog when it is cancelled`() {
        composeTestRule.assertNoDialogExists()
        mutableStateFlow.update {
            it.copy(
                dialog = VaultDevicesState.DialogState.ConfirmRetire(deviceId = "mockDeviceId-2"),
            )
        }

        composeTestRule
            .onAllNodesWithText("Retire this device?")
            .filterToOne(hasAnyAncestor(isDialog()))
            .assertIsDisplayed()
        composeTestRule
            .onAllNodesWithText("Retire")
            .filterToOne(hasAnyAncestor(isDialog()))
            .performClick()
        verify {
            viewModel.trySendAction(VaultDevicesAction.RetireConfirm(deviceId = "mockDeviceId-2"))
        }
        composeTestRule
            .onAllNodesWithText("Cancel")
            .filterToOne(hasAnyAncestor(isDialog()))
            .performClick()
        verify { viewModel.trySendAction(VaultDevicesAction.DismissDialog) }
    }

    @Test
    fun `error dialog should be shown according to state and dismissed with DismissDialog`() {
        val message =
            "The file of the device has not fully come to this device yet. Try again later."
        mutableStateFlow.update {
            it.copy(
                dialog = VaultDevicesState.DialogState.Error(
                    title = "An error has occurred".asText(),
                    message = message.asText(),
                ),
            )
        }

        composeTestRule
            .onAllNodesWithText(message)
            .filterToOne(hasAnyAncestor(isDialog()))
            .assertIsDisplayed()
        composeTestRule
            .onAllNodesWithText("Okay")
            .filterToOne(hasAnyAncestor(isDialog()))
            .performClick()

        verify { viewModel.trySendAction(VaultDevicesAction.DismissDialog) }
    }

    @Test
    fun `error view state should offer to list the devices again`() {
        mutableStateFlow.update { it.copy(viewState = VaultDevicesState.ViewState.Error) }

        composeTestRule.onNodeWithText("This device").assertDoesNotExist()
        composeTestRule.onNodeWithText("Try again").performClick()

        verify { viewModel.trySendAction(VaultDevicesAction.TryAgainClick) }
    }
}

private val DEFAULT_STATE = VaultDevicesState(
    viewState = VaultDevicesState.ViewState.Content(
        devices = listOf(
            VaultDeviceItem(
                id = "mockDeviceId-1",
                name = "mockDeviceName-1",
                typeName = "Mobile - Android".asText(),
                isCurrentDevice = true,
                lastChanged = null,
                retiredOn = null,
                removedAfter = null,
            ),
            VaultDeviceItem(
                id = "mockDeviceId-2",
                name = "chrome",
                typeName = "Extension - Chrome".asText(),
                isCurrentDevice = false,
                lastChanged = "Jan 1, 2025",
                retiredOn = null,
                removedAfter = null,
            ),
            VaultDeviceItem(
                id = "mockDeviceId-3",
                name = null,
                typeName = "Unknown device".asText(),
                isCurrentDevice = false,
                lastChanged = "Dec 1, 2024",
                retiredOn = "Oct 28, 2026",
                removedAfter = "Jan 26, 2027",
            ),
        ),
    ),
    dialog = null,
)
