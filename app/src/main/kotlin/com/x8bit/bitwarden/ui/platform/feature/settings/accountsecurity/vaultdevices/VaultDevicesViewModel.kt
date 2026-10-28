package com.x8bit.bitwarden.ui.platform.feature.settings.accountsecurity.vaultdevices

import android.os.Parcelable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.bitwarden.core.data.util.toFormattedDateStyle
import com.bitwarden.network.model.LocalVaultDeviceJson
import com.bitwarden.ui.platform.base.BaseViewModel
import com.bitwarden.ui.platform.components.snackbar.model.BitwardenSnackbarData
import com.bitwarden.ui.platform.resource.BitwardenString
import com.bitwarden.ui.util.Text
import com.bitwarden.ui.util.asText
import com.x8bit.bitwarden.data.platform.manager.localvault.LocalVaultDevicesManager
import com.x8bit.bitwarden.data.platform.manager.localvault.model.RetireDeviceResult
import com.x8bit.bitwarden.ui.platform.feature.settings.accountsecurity.managedevices.util.readableDeviceTypeName
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import java.time.Clock
import java.time.Instant
import java.time.format.FormatStyle
import javax.inject.Inject

private const val KEY_STATE = "state"

/**
 * View model for the [VaultDevicesScreen].
 */
@Suppress("TooManyFunctions")
@HiltViewModel
class VaultDevicesViewModel @Inject constructor(
    private val localVaultDevicesManager: LocalVaultDevicesManager,
    private val clock: Clock,
    savedStateHandle: SavedStateHandle,
) : BaseViewModel<VaultDevicesState, VaultDevicesEvent, VaultDevicesAction>(
    initialState = savedStateHandle[KEY_STATE] ?: VaultDevicesState(
        viewState = VaultDevicesState.ViewState.Loading,
        dialog = null,
    ),
) {
    init {
        stateFlow
            .onEach { savedStateHandle[KEY_STATE] = it }
            .launchIn(viewModelScope)
        if (state.viewState is VaultDevicesState.ViewState.Loading) {
            loadDevices()
        }
    }

    override fun handleAction(action: VaultDevicesAction) {
        when (action) {
            VaultDevicesAction.BackClick -> handleBackClick()
            is VaultDevicesAction.RetireClick -> handleRetireClick(action)
            is VaultDevicesAction.RetireConfirm -> handleRetireConfirm(action)
            VaultDevicesAction.DismissDialog -> handleDismissDialog()
            VaultDevicesAction.TryAgainClick -> handleTryAgainClick()
            is VaultDevicesAction.Internal -> handleInternalAction(action)
        }
    }

    private fun handleBackClick() {
        sendEvent(VaultDevicesEvent.NavigateBack)
    }

    private fun handleRetireClick(action: VaultDevicesAction.RetireClick) {
        mutableStateFlow.update {
            it.copy(
                dialog = VaultDevicesState.DialogState.ConfirmRetire(deviceId = action.deviceId),
            )
        }
    }

    private fun handleRetireConfirm(action: VaultDevicesAction.RetireConfirm) {
        mutableStateFlow.update {
            it.copy(
                dialog = VaultDevicesState.DialogState.Loading(
                    message = BitwardenString.retiring_device.asText(),
                ),
            )
        }
        viewModelScope.launch {
            val result = localVaultDevicesManager.retireDevice(deviceId = action.deviceId)
            sendAction(VaultDevicesAction.Internal.RetireDeviceResultReceive(result = result))
        }
    }

    private fun handleDismissDialog() {
        mutableStateFlow.update { it.copy(dialog = null) }
    }

    private fun handleTryAgainClick() {
        mutableStateFlow.update { it.copy(viewState = VaultDevicesState.ViewState.Loading) }
        loadDevices()
    }

    private fun handleInternalAction(action: VaultDevicesAction.Internal) {
        when (action) {
            is VaultDevicesAction.Internal.DevicesResultReceive -> {
                handleDevicesResultReceive(action)
            }

            is VaultDevicesAction.Internal.RetireDeviceResultReceive -> {
                handleRetireDeviceResultReceive(action)
            }
        }
    }

    private fun handleDevicesResultReceive(
        action: VaultDevicesAction.Internal.DevicesResultReceive,
    ) {
        val viewState = action.result.fold(
            onSuccess = { VaultDevicesState.ViewState.Content(devices = it.toDeviceItems()) },
            onFailure = { VaultDevicesState.ViewState.Error },
        )
        mutableStateFlow.update { it.copy(viewState = viewState) }
    }

    private fun handleRetireDeviceResultReceive(
        action: VaultDevicesAction.Internal.RetireDeviceResultReceive,
    ) {
        when (val result = action.result) {
            RetireDeviceResult.Success -> {
                mutableStateFlow.update { it.copy(dialog = null) }
                sendEvent(
                    VaultDevicesEvent.ShowSnackbar(
                        data = BitwardenSnackbarData(
                            message = BitwardenString.device_retired.asText(),
                        ),
                    ),
                )
                loadDevices()
            }

            RetireDeviceResult.NotComplete -> {
                showErrorDialog(
                    message = BitwardenString
                        .the_file_of_the_device_has_not_fully_come_to_this_device_yet
                        .asText(),
                )
            }

            is RetireDeviceResult.Error -> {
                showErrorDialog(
                    message = BitwardenString.generic_error_message.asText(),
                    error = result.error,
                )
            }
        }
    }

    private fun loadDevices() {
        viewModelScope.launch {
            val result = localVaultDevicesManager.getDevices()
            sendAction(VaultDevicesAction.Internal.DevicesResultReceive(result = result))
        }
    }

    private fun showErrorDialog(message: Text, error: Throwable? = null) {
        mutableStateFlow.update {
            it.copy(
                dialog = VaultDevicesState.DialogState.Error(
                    title = BitwardenString.an_error_has_occurred.asText(),
                    message = message,
                    error = error,
                ),
            )
        }
    }

    /**
     * The devices as they are listed: this one first, and the others by when they last changed the
     * vault, the latest first, so that those that are gone for good come last.
     */
    private fun List<LocalVaultDeviceJson>.toDeviceItems(): List<VaultDeviceItem> =
        this
            .sortedWith(
                compareByDescending<LocalVaultDeviceJson> { it.isCurrentDevice }
                    .thenByDescending { it.revisionDate ?: Instant.MIN },
            )
            .map { device ->
                VaultDeviceItem(
                    id = device.id,
                    name = device.name,
                    typeName = device.type?.readableDeviceTypeName
                        ?: BitwardenString.unknown_device.asText(),
                    isCurrentDevice = device.isCurrentDevice,
                    lastChanged = device.revisionDate?.toDisplayDate(),
                    retiredOn = device.retiredDate?.toDisplayDate(),
                    removedAfter = device.removalDate?.toDisplayDate(),
                )
            }

    private fun Instant.toDisplayDate(): String =
        toFormattedDateStyle(dateStyle = FormatStyle.MEDIUM, clock = clock)
}

/**
 * Models the state of the [VaultDevicesScreen].
 *
 * @property viewState What the screen shows.
 * @property dialog The dialog that is shown, if any.
 */
@Parcelize
data class VaultDevicesState(
    val viewState: ViewState,
    val dialog: DialogState?,
) : Parcelable {
    /**
     * Represents what the screen shows.
     */
    sealed class ViewState : Parcelable {
        /**
         * The devices are being listed.
         */
        @Parcelize
        data object Loading : ViewState()

        /**
         * The devices could not be listed.
         */
        @Parcelize
        data object Error : ViewState()

        /**
         * The devices that share the vault folder, this one first.
         */
        @Parcelize
        data class Content(
            val devices: List<VaultDeviceItem>,
        ) : ViewState()
    }

    /**
     * Represents the dialogs of the screen.
     */
    sealed class DialogState : Parcelable {
        /**
         * Asks whether the device with the [deviceId] is to be retired.
         */
        @Parcelize
        data class ConfirmRetire(
            val deviceId: String,
        ) : DialogState()

        /**
         * The device is being retired.
         */
        @Parcelize
        data class Loading(
            val message: Text,
        ) : DialogState()

        /**
         * The device was not retired.
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
 * A device that shares the vault folder, as the screen lists it.
 *
 * @property id The ID of the device, which names the folder that it keeps its changes in.
 * @property name The name of the device, such as the model of a phone, if its file says it.
 * @property typeName The type of the device, such as "Mobile - Android", or "Unknown device" if its
 * file does not say it, as one that an earlier version of the app wrote does not.
 * @property isCurrentDevice Whether it is this device.
 * @property lastChanged When the device last changed the vault, if it did.
 * @property retiredOn When the device was retired, if it was.
 * @property removedAfter When the folder of the device is deleted, if it was retired.
 */
@Parcelize
data class VaultDeviceItem(
    val id: String,
    val name: String?,
    val typeName: Text,
    val isCurrentDevice: Boolean,
    val lastChanged: String?,
    val retiredOn: String?,
    val removedAfter: String?,
) : Parcelable {
    /**
     * Whether the device can be retired from this one: another one, that is not retired yet.
     */
    val canRetire: Boolean
        get() = !isCurrentDevice && retiredOn == null
}

/**
 * Models events for the [VaultDevicesScreen].
 */
sealed class VaultDevicesEvent {
    /**
     * Navigate back.
     */
    data object NavigateBack : VaultDevicesEvent()

    /**
     * Shows a snackbar with the given [data].
     */
    data class ShowSnackbar(
        val data: BitwardenSnackbarData,
    ) : VaultDevicesEvent()
}

/**
 * Models actions for the [VaultDevicesScreen].
 */
sealed class VaultDevicesAction {
    /**
     * The user clicked the back button.
     */
    data object BackClick : VaultDevicesAction()

    /**
     * The user clicked to retire the device with the [deviceId].
     */
    data class RetireClick(
        val deviceId: String,
    ) : VaultDevicesAction()

    /**
     * The user confirmed that the device with the [deviceId] is to be retired.
     */
    data class RetireConfirm(
        val deviceId: String,
    ) : VaultDevicesAction()

    /**
     * The user dismissed the dialog.
     */
    data object DismissDialog : VaultDevicesAction()

    /**
     * The user clicked to list the devices again, after they could not be listed.
     */
    data object TryAgainClick : VaultDevicesAction()

    /**
     * Models actions that the [VaultDevicesViewModel] sends itself.
     */
    sealed class Internal : VaultDevicesAction() {
        /**
         * The devices were listed, or could not be.
         */
        data class DevicesResultReceive(
            val result: Result<List<LocalVaultDeviceJson>>,
        ) : Internal()

        /**
         * The device was retired, or could not be.
         */
        data class RetireDeviceResultReceive(
            val result: RetireDeviceResult,
        ) : Internal()
    }
}
