package com.x8bit.bitwarden.ui.platform.feature.settings.accountsecurity.vaultdevices

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.bitwarden.core.data.util.asFailure
import com.bitwarden.core.data.util.asSuccess
import com.bitwarden.core.data.util.toFormattedDateStyle
import com.bitwarden.network.model.DeviceType
import com.bitwarden.network.model.LocalVaultDeviceJson
import com.bitwarden.ui.platform.base.BaseViewModelTest
import com.bitwarden.ui.platform.components.snackbar.model.BitwardenSnackbarData
import com.bitwarden.ui.platform.resource.BitwardenString
import com.bitwarden.ui.util.asText
import com.x8bit.bitwarden.data.platform.manager.localvault.LocalVaultDevicesManager
import com.x8bit.bitwarden.data.platform.manager.localvault.model.RetireDeviceResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.FormatStyle

class VaultDevicesViewModelTest : BaseViewModelTest() {

    private val localVaultDevicesManager: LocalVaultDevicesManager = mockk {
        coEvery { getDevices() } returns listOf(GONE_DEVICE, THIS_DEVICE, OTHER_DEVICE).asSuccess()
        coEvery { retireDevice(deviceId = GONE_DEVICE.id) } returns RetireDeviceResult.Success
    }

    @Test
    fun `initial state should list this device first, and the others by their last change`() {
        val viewModel = createViewModel()

        assertEquals(DEFAULT_STATE, viewModel.stateFlow.value)
    }

    @Test
    fun `initial state should be Error when the devices cannot be listed`() {
        coEvery { localVaultDevicesManager.getDevices() } returns Throwable("Fail").asFailure()

        val viewModel = createViewModel()

        assertEquals(
            DEFAULT_STATE.copy(viewState = VaultDevicesState.ViewState.Error),
            viewModel.stateFlow.value,
        )
    }

    @Test
    fun `TryAgainClick should list the devices again`() {
        coEvery { localVaultDevicesManager.getDevices() } returns Throwable("Fail").asFailure()
        val viewModel = createViewModel()
        coEvery {
            localVaultDevicesManager.getDevices()
        } returns listOf(GONE_DEVICE, THIS_DEVICE, OTHER_DEVICE).asSuccess()

        viewModel.trySendAction(VaultDevicesAction.TryAgainClick)

        assertEquals(DEFAULT_STATE, viewModel.stateFlow.value)
        coVerify(exactly = 2) { localVaultDevicesManager.getDevices() }
    }

    @Test
    fun `BackClick should emit NavigateBack`() = runTest {
        val viewModel = createViewModel()
        viewModel.eventFlow.test {
            viewModel.trySendAction(VaultDevicesAction.BackClick)
            assertEquals(VaultDevicesEvent.NavigateBack, awaitItem())
        }
    }

    @Test
    fun `RetireClick should ask whether the device is to be retired`() {
        val viewModel = createViewModel()

        viewModel.trySendAction(VaultDevicesAction.RetireClick(deviceId = GONE_DEVICE.id))

        assertEquals(
            DEFAULT_STATE.copy(
                dialog = VaultDevicesState.DialogState.ConfirmRetire(deviceId = GONE_DEVICE.id),
            ),
            viewModel.stateFlow.value,
        )
    }

    @Test
    fun `DismissDialog should close the dialog`() {
        val viewModel = createViewModel()
        viewModel.trySendAction(VaultDevicesAction.RetireClick(deviceId = GONE_DEVICE.id))

        viewModel.trySendAction(VaultDevicesAction.DismissDialog)

        assertEquals(DEFAULT_STATE, viewModel.stateFlow.value)
    }

    @Test
    fun `RetireConfirm should retire the device, show a snackbar and list the devices again`() =
        runTest {
            val viewModel = createViewModel()
            coEvery {
                localVaultDevicesManager.getDevices()
            } returns listOf(GONE_DEVICE.retired(), THIS_DEVICE, OTHER_DEVICE).asSuccess()

            viewModel.eventFlow.test {
                viewModel.trySendAction(
                    VaultDevicesAction.RetireConfirm(deviceId = GONE_DEVICE.id),
                )
                assertEquals(
                    VaultDevicesEvent.ShowSnackbar(
                        data = BitwardenSnackbarData(
                            message = BitwardenString.device_retired.asText(),
                        ),
                    ),
                    awaitItem(),
                )
            }

            coVerify(exactly = 1) { localVaultDevicesManager.retireDevice(GONE_DEVICE.id) }
            val content = viewModel.stateFlow.value.viewState as VaultDevicesState.ViewState.Content
            val devices = content.devices
            assertNull(viewModel.stateFlow.value.dialog)
            assertEquals(RETIRED_AT.toDisplayDate(), devices.last().retiredOn)
            assertEquals(REMOVAL_AT.toDisplayDate(), devices.last().removedAfter)
            assertFalse(devices.last().canRetire)
        }

    @Test
    fun `RetireConfirm should show an error when the file of the device has not fully come`() {
        coEvery {
            localVaultDevicesManager.retireDevice(deviceId = GONE_DEVICE.id)
        } returns RetireDeviceResult.NotComplete
        val viewModel = createViewModel()

        viewModel.trySendAction(VaultDevicesAction.RetireConfirm(deviceId = GONE_DEVICE.id))

        assertEquals(
            DEFAULT_STATE.copy(
                dialog = VaultDevicesState.DialogState.Error(
                    title = BitwardenString.an_error_has_occurred.asText(),
                    message = BitwardenString
                        .the_file_of_the_device_has_not_fully_come_to_this_device_yet
                        .asText(),
                ),
            ),
            viewModel.stateFlow.value,
        )
    }

    @Test
    fun `RetireConfirm should show an error when the device could not be retired`() {
        val error = Throwable("Fail")
        coEvery {
            localVaultDevicesManager.retireDevice(deviceId = GONE_DEVICE.id)
        } returns RetireDeviceResult.Error(error = error)
        val viewModel = createViewModel()

        viewModel.trySendAction(VaultDevicesAction.RetireConfirm(deviceId = GONE_DEVICE.id))

        assertEquals(
            DEFAULT_STATE.copy(
                dialog = VaultDevicesState.DialogState.Error(
                    title = BitwardenString.an_error_has_occurred.asText(),
                    message = BitwardenString.generic_error_message.asText(),
                    error = error,
                ),
            ),
            viewModel.stateFlow.value,
        )
    }

    private fun createViewModel(
        state: VaultDevicesState? = null,
    ): VaultDevicesViewModel = VaultDevicesViewModel(
        localVaultDevicesManager = localVaultDevicesManager,
        clock = FIXED_CLOCK,
        savedStateHandle = SavedStateHandle().apply { set("state", state) },
    )
}

private val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-28T12:00:00Z"), ZoneOffset.UTC)

private val RETIRED_AT: Instant = Instant.parse("2026-10-28T12:00:00Z")

private val REMOVAL_AT: Instant = Instant.parse("2027-01-26T12:00:00Z")

private fun Instant.toDisplayDate(): String =
    toFormattedDateStyle(dateStyle = FormatStyle.MEDIUM, clock = FIXED_CLOCK)

private fun LocalVaultDeviceJson.retired(): LocalVaultDeviceJson =
    copy(retiredDate = RETIRED_AT, removalDate = REMOVAL_AT)

private val THIS_DEVICE = LocalVaultDeviceJson(
    id = "mockDeviceId-1",
    name = "mockDeviceName-1",
    type = DeviceType.ANDROID,
    isCurrentDevice = true,
    revisionDate = null,
    retiredDate = null,
    removalDate = null,
)

private val OTHER_DEVICE = LocalVaultDeviceJson(
    id = "mockDeviceId-2",
    name = "chrome",
    type = DeviceType.CHROME_EXTENSION,
    isCurrentDevice = false,
    revisionDate = Instant.parse("2026-10-20T12:00:00Z"),
    retiredDate = null,
    removalDate = null,
)

// Its file does not say what it is, as one that an earlier version of the app wrote does not.
private val GONE_DEVICE = LocalVaultDeviceJson(
    id = "mockDeviceId-3",
    name = null,
    type = null,
    isCurrentDevice = false,
    revisionDate = Instant.parse("2025-01-01T12:00:00Z"),
    retiredDate = null,
    removalDate = null,
)

private val DEFAULT_STATE = VaultDevicesState(
    viewState = VaultDevicesState.ViewState.Content(
        devices = listOf(
            VaultDeviceItem(
                id = "mockDeviceId-1",
                name = "mockDeviceName-1",
                typeName = BitwardenString.mobile_platform.asText("Android"),
                isCurrentDevice = true,
                lastChanged = null,
                retiredOn = null,
                removedAfter = null,
            ),
            VaultDeviceItem(
                id = "mockDeviceId-2",
                name = "chrome",
                typeName = BitwardenString.extension_platform.asText("Chrome"),
                isCurrentDevice = false,
                lastChanged = Instant.parse("2026-10-20T12:00:00Z").toDisplayDate(),
                retiredOn = null,
                removedAfter = null,
            ),
            VaultDeviceItem(
                id = "mockDeviceId-3",
                name = null,
                typeName = BitwardenString.unknown_device.asText(),
                isCurrentDevice = false,
                lastChanged = Instant.parse("2025-01-01T12:00:00Z").toDisplayDate(),
                retiredOn = null,
                removedAfter = null,
            ),
        ),
    ),
    dialog = null,
)
