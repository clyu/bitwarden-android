package com.x8bit.bitwarden.ui.platform.feature.settings.accountsecurity.vaultdevices

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitwarden.ui.platform.base.util.EventsEffect
import com.bitwarden.ui.platform.base.util.cardStyle
import com.bitwarden.ui.platform.base.util.standardHorizontalMargin
import com.bitwarden.ui.platform.base.util.toListItemCardStyle
import com.bitwarden.ui.platform.components.appbar.BitwardenTopAppBar
import com.bitwarden.ui.platform.components.badge.BitwardenStatusBadge
import com.bitwarden.ui.platform.components.button.BitwardenOutlinedButton
import com.bitwarden.ui.platform.components.button.model.BitwardenButtonData
import com.bitwarden.ui.platform.components.content.BitwardenErrorContent
import com.bitwarden.ui.platform.components.content.BitwardenLoadingContent
import com.bitwarden.ui.platform.components.dialog.BitwardenBasicDialog
import com.bitwarden.ui.platform.components.dialog.BitwardenLoadingDialog
import com.bitwarden.ui.platform.components.dialog.BitwardenTwoButtonDialog
import com.bitwarden.ui.platform.components.model.CardStyle
import com.bitwarden.ui.platform.components.scaffold.BitwardenScaffold
import com.bitwarden.ui.platform.components.snackbar.BitwardenSnackbarHost
import com.bitwarden.ui.platform.components.snackbar.model.rememberBitwardenSnackbarHostState
import com.bitwarden.ui.platform.components.util.rememberVectorPainter
import com.bitwarden.ui.platform.resource.BitwardenDrawable
import com.bitwarden.ui.platform.resource.BitwardenString
import com.bitwarden.ui.platform.theme.BitwardenTheme
import com.bitwarden.ui.util.asText

/**
 * Displays the devices that share the vault folder, by their types and their names, with when each
 * last changed the vault, and retires one that is gone for good.
 */
@Suppress("LongMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultDevicesScreen(
    onNavigateBack: () -> Unit,
    viewModel: VaultDevicesViewModel = hiltViewModel(),
) {
    val state by viewModel.stateFlow.collectAsStateWithLifecycle()
    val snackbarHostState = rememberBitwardenSnackbarHostState()
    EventsEffect(viewModel = viewModel) { event ->
        when (event) {
            VaultDevicesEvent.NavigateBack -> onNavigateBack()
            is VaultDevicesEvent.ShowSnackbar -> snackbarHostState.showSnackbar(event.data)
        }
    }

    VaultDevicesDialogs(
        dialogState = state.dialog,
        onRetireConfirm = {
            viewModel.trySendAction(VaultDevicesAction.RetireConfirm(deviceId = it))
        },
        onDismissRequest = { viewModel.trySendAction(VaultDevicesAction.DismissDialog) },
    )

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    BitwardenScaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            BitwardenTopAppBar(
                title = stringResource(id = BitwardenString.devices_sharing_the_vault_folder),
                scrollBehavior = scrollBehavior,
                navigationIcon = rememberVectorPainter(id = BitwardenDrawable.ic_back),
                navigationIconContentDescription = stringResource(id = BitwardenString.back),
                onNavigationIconClick = { viewModel.trySendAction(VaultDevicesAction.BackClick) },
            )
        },
        snackbarHost = {
            BitwardenSnackbarHost(bitwardenHostState = snackbarHostState)
        },
    ) {
        when (val viewState = state.viewState) {
            is VaultDevicesState.ViewState.Content -> {
                VaultDevicesContent(
                    devices = viewState.devices,
                    onRetireClick = {
                        viewModel.trySendAction(VaultDevicesAction.RetireClick(deviceId = it))
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            VaultDevicesState.ViewState.Error -> {
                BitwardenErrorContent(
                    message = stringResource(id = BitwardenString.generic_error_message),
                    buttonData = BitwardenButtonData(
                        label = BitwardenString.try_again.asText(),
                        onClick = { viewModel.trySendAction(VaultDevicesAction.TryAgainClick) },
                    ),
                    modifier = Modifier.fillMaxSize(),
                )
            }

            VaultDevicesState.ViewState.Loading -> {
                BitwardenLoadingContent(modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun VaultDevicesDialogs(
    dialogState: VaultDevicesState.DialogState?,
    onRetireConfirm: (deviceId: String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    when (dialogState) {
        is VaultDevicesState.DialogState.ConfirmRetire -> {
            BitwardenTwoButtonDialog(
                title = stringResource(id = BitwardenString.retire_this_device),
                message = stringResource(
                    id = BitwardenString
                        .retire_a_device_only_if_it_will_not_be_used_again_desc_long,
                ),
                confirmButtonText = stringResource(id = BitwardenString.retire),
                dismissButtonText = stringResource(id = BitwardenString.cancel),
                onConfirmClick = { onRetireConfirm(dialogState.deviceId) },
                onDismissClick = onDismissRequest,
                onDismissRequest = onDismissRequest,
            )
        }

        is VaultDevicesState.DialogState.Loading -> {
            BitwardenLoadingDialog(text = dialogState.message())
        }

        is VaultDevicesState.DialogState.Error -> {
            BitwardenBasicDialog(
                title = dialogState.title?.invoke(),
                message = dialogState.message(),
                throwable = dialogState.error,
                onDismissRequest = onDismissRequest,
            )
        }

        null -> Unit
    }
}

@Composable
private fun VaultDevicesContent(
    devices: List<VaultDeviceItem>,
    onRetireClick: (deviceId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
    ) {
        item {
            Spacer(modifier = Modifier.height(height = 12.dp))
            Text(
                text = stringResource(
                    id = BitwardenString
                        .each_device_that_shares_the_vault_folder_keeps_its_changes_desc_long,
                ),
                style = BitwardenTheme.typography.bodyMedium,
                color = BitwardenTheme.colorScheme.text.primary,
                modifier = Modifier
                    .standardHorizontalMargin()
                    .fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(height = 16.dp))
        }
        itemsIndexed(devices) { index, device ->
            VaultDeviceRow(
                device = device,
                onRetireClick = { onRetireClick(device.id) },
                cardStyle = devices.toListItemCardStyle(index = index, dividerPadding = 0.dp),
                modifier = Modifier
                    .testTag("VaultDeviceCell")
                    .fillMaxWidth()
                    .standardHorizontalMargin(),
            )
        }
        item {
            Spacer(modifier = Modifier.height(height = 16.dp))
            Spacer(modifier = Modifier.navigationBarsPadding())
        }
    }
}

/**
 * A device that shares the vault folder, by its type, its name and its ID, with whether it is this
 * one, when it last changed the vault, and when it was retired, or a button that retires it.
 */
@Suppress("LongMethod")
@Composable
private fun VaultDeviceRow(
    device: VaultDeviceItem,
    onRetireClick: () -> Unit,
    cardStyle: CardStyle,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .defaultMinSize(minHeight = 60.dp)
            .cardStyle(
                cardStyle = cardStyle,
                paddingHorizontal = 16.dp,
            ),
        horizontalAlignment = Alignment.Start,
    ) {
        if (device.isCurrentDevice) {
            BitwardenStatusBadge(
                label = stringResource(id = BitwardenString.this_device),
                colors = BitwardenTheme.colorScheme.statusBadge.primary,
            )
            Spacer(Modifier.height(height = 8.dp))
        } else if (device.retiredOn != null) {
            BitwardenStatusBadge(
                label = stringResource(id = BitwardenString.retired),
                colors = BitwardenTheme.colorScheme.statusBadge.warning,
            )
            Spacer(Modifier.height(height = 8.dp))
        }
        Text(
            text = device.typeName(),
            style = BitwardenTheme.typography.titleMedium,
            color = BitwardenTheme.colorScheme.text.primary,
            textAlign = TextAlign.Start,
        )
        device.name?.let { VaultDeviceLabel(text = it) }
        VaultDeviceLabel(text = device.id)
        VaultDeviceLabel(
            text = device.lastChanged
                ?.let { stringResource(id = BitwardenString.last_changed_the_vault_on_x, it) }
                ?: stringResource(id = BitwardenString.has_not_changed_the_vault),
        )
        device.retiredOn?.let { retiredOn ->
            VaultDeviceLabel(
                text = if (device.isCurrentDevice) {
                    stringResource(
                        id = BitwardenString
                            .retired_on_x_from_another_device_no_longer_retired_once_it_syncs,
                        retiredOn,
                    )
                } else {
                    stringResource(
                        id = BitwardenString
                            .retired_on_x_its_folder_is_deleted_after_y_unless_it_is_used_again,
                        retiredOn,
                        device.removedAfter.orEmpty(),
                    )
                },
            )
        }
        if (device.canRetire) {
            Spacer(Modifier.height(height = 8.dp))
            BitwardenOutlinedButton(
                label = stringResource(id = BitwardenString.retire),
                onClick = onRetireClick,
                modifier = Modifier.testTag("RetireDeviceButton"),
            )
        }
    }
}

@Composable
private fun VaultDeviceLabel(text: String) {
    Text(
        text = text,
        style = BitwardenTheme.typography.bodySmall,
        color = BitwardenTheme.colorScheme.text.secondary,
        textAlign = TextAlign.Start,
    )
}
