package com.x8bit.bitwarden.ui.platform.feature.settings.accountsecurity.kdfsettings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitwarden.ui.platform.base.util.EventsEffect
import com.bitwarden.ui.platform.base.util.standardHorizontalMargin
import com.bitwarden.ui.platform.components.appbar.BitwardenTopAppBar
import com.bitwarden.ui.platform.components.button.BitwardenFilledButton
import com.bitwarden.ui.platform.components.card.BitwardenInfoCalloutCard
import com.bitwarden.ui.platform.components.content.BitwardenErrorContent
import com.bitwarden.ui.platform.components.content.BitwardenLoadingContent
import com.bitwarden.ui.platform.components.dialog.BitwardenBasicDialog
import com.bitwarden.ui.platform.components.dialog.BitwardenLoadingDialog
import com.bitwarden.ui.platform.components.dropdown.BitwardenMultiSelectButton
import com.bitwarden.ui.platform.components.field.BitwardenTextField
import com.bitwarden.ui.platform.components.icon.model.IconData
import com.bitwarden.ui.platform.components.model.CardStyle
import com.bitwarden.ui.platform.components.scaffold.BitwardenScaffold
import com.bitwarden.ui.platform.components.snackbar.BitwardenSnackbarHost
import com.bitwarden.ui.platform.components.snackbar.model.rememberBitwardenSnackbarHostState
import com.bitwarden.ui.platform.components.util.rememberVectorPainter
import com.bitwarden.ui.platform.resource.BitwardenDrawable
import com.bitwarden.ui.platform.resource.BitwardenString
import com.bitwarden.ui.platform.theme.BitwardenTheme
import com.x8bit.bitwarden.data.auth.util.KdfSettingsConstants
import com.x8bit.bitwarden.ui.platform.components.dialog.BitwardenMasterPasswordDialog
import com.x8bit.bitwarden.ui.platform.feature.settings.accountsecurity.kdfsettings.model.KdfAlgorithm
import kotlinx.collections.immutable.toImmutableList

/**
 * Displays the screen that changes how the master key is derived from the master password.
 */
@Suppress("LongMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KdfSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: KdfSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.stateFlow.collectAsStateWithLifecycle()
    val snackbarHostState = rememberBitwardenSnackbarHostState()
    EventsEffect(viewModel = viewModel) { event ->
        when (event) {
            KdfSettingsEvent.NavigateBack -> onNavigateBack()
            is KdfSettingsEvent.ShowSnackbar -> snackbarHostState.showSnackbar(event.data)
        }
    }

    KdfSettingsDialogs(
        dialogState = state.dialog,
        onMasterPasswordConfirm = {
            viewModel.trySendAction(KdfSettingsAction.MasterPasswordConfirm(masterPassword = it))
        },
        onDismissRequest = { viewModel.trySendAction(KdfSettingsAction.DismissDialog) },
    )

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    BitwardenScaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            BitwardenTopAppBar(
                title = stringResource(id = BitwardenString.encryption_key_settings),
                scrollBehavior = scrollBehavior,
                navigationIcon = rememberVectorPainter(id = BitwardenDrawable.ic_back),
                navigationIconContentDescription = stringResource(id = BitwardenString.back),
                onNavigationIconClick = { viewModel.trySendAction(KdfSettingsAction.BackClick) },
            )
        },
        snackbarHost = {
            BitwardenSnackbarHost(bitwardenHostState = snackbarHostState)
        },
    ) {
        when (val viewState = state.viewState) {
            is KdfSettingsState.ViewState.Content -> {
                KdfSettingsContent(
                    state = viewState,
                    onAlgorithmSelect = {
                        viewModel.trySendAction(KdfSettingsAction.AlgorithmSelect(algorithm = it))
                    },
                    onIterationsInputChange = {
                        viewModel.trySendAction(KdfSettingsAction.IterationsInputChange(input = it))
                    },
                    onMemoryInputChange = {
                        viewModel.trySendAction(KdfSettingsAction.MemoryInputChange(input = it))
                    },
                    onParallelismInputChange = {
                        viewModel.trySendAction(
                            KdfSettingsAction.ParallelismInputChange(input = it),
                        )
                    },
                    onUpdateClick = { viewModel.trySendAction(KdfSettingsAction.UpdateClick) },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            KdfSettingsState.ViewState.Error -> {
                BitwardenErrorContent(
                    message = stringResource(id = BitwardenString.generic_error_message),
                    modifier = Modifier.fillMaxSize(),
                )
            }

            KdfSettingsState.ViewState.Loading -> {
                BitwardenLoadingContent(modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun KdfSettingsDialogs(
    dialogState: KdfSettingsState.DialogState?,
    onMasterPasswordConfirm: (masterPassword: String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    when (dialogState) {
        KdfSettingsState.DialogState.MasterPasswordPrompt -> {
            BitwardenMasterPasswordDialog(
                title = stringResource(id = BitwardenString.update_your_encryption_settings),
                message = stringResource(id = BitwardenString.confirm_your_identity),
                confirmButtonText = stringResource(id = BitwardenString.update_settings),
                onConfirmClick = onMasterPasswordConfirm,
                onDismissRequest = onDismissRequest,
            )
        }

        is KdfSettingsState.DialogState.Loading -> {
            BitwardenLoadingDialog(text = dialogState.message())
        }

        is KdfSettingsState.DialogState.Error -> {
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

@Suppress("LongMethod")
@Composable
private fun KdfSettingsContent(
    state: KdfSettingsState.ViewState.Content,
    onAlgorithmSelect: (KdfAlgorithm) -> Unit,
    onIterationsInputChange: (String) -> Unit,
    onMemoryInputChange: (String) -> Unit,
    onParallelismInputChange: (String) -> Unit,
    onUpdateClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(state = rememberScrollState()),
    ) {
        Spacer(modifier = Modifier.height(height = 12.dp))
        Text(
            text = stringResource(
                id = BitwardenString
                    .choose_how_the_key_that_unlocks_your_vault_is_derived_desc_long,
            ),
            style = BitwardenTheme.typography.bodyMedium,
            color = BitwardenTheme.colorScheme.text.primary,
            modifier = Modifier
                .standardHorizontalMargin()
                .fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(height = 16.dp))
        BitwardenMultiSelectButton(
            label = stringResource(id = BitwardenString.algorithm),
            options = KdfAlgorithm.entries.map { it.label }.toImmutableList(),
            selectedOption = state.algorithm.label,
            onOptionSelected = { label ->
                onAlgorithmSelect(KdfAlgorithm.entries.first { it.label == label })
            },
            cardStyle = CardStyle.Top(),
            textFieldTestTag = "KdfAlgorithmPicker",
            modifier = Modifier
                .standardHorizontalMargin()
                .fillMaxWidth(),
        )
        when (state.algorithm) {
            KdfAlgorithm.PBKDF2_SHA256 -> {
                KdfNumberField(
                    label = stringResource(id = BitwardenString.kdf_iterations),
                    value = state.iterationsInput,
                    onValueChange = onIterationsInputChange,
                    supportingText = stringResource(
                        id = BitwardenString.we_recommend_x_or_more,
                        KdfSettingsConstants.DEFAULT_PBKDF2_ITERATIONS,
                    ),
                    cardStyle = CardStyle.Bottom,
                    testTag = "KdfIterationsEntry",
                )
            }

            KdfAlgorithm.ARGON2ID -> {
                KdfNumberField(
                    label = stringResource(id = BitwardenString.kdf_iterations),
                    value = state.iterationsInput,
                    onValueChange = onIterationsInputChange,
                    cardStyle = CardStyle.Middle(),
                    testTag = "KdfIterationsEntry",
                )
                KdfNumberField(
                    label = stringResource(id = BitwardenString.kdf_memory_mb),
                    value = state.memoryInput,
                    onValueChange = onMemoryInputChange,
                    cardStyle = CardStyle.Middle(),
                    testTag = "KdfMemoryEntry",
                )
                KdfNumberField(
                    label = stringResource(id = BitwardenString.kdf_parallelism),
                    value = state.parallelismInput,
                    onValueChange = onParallelismInputChange,
                    cardStyle = CardStyle.Bottom,
                    testTag = "KdfParallelismEntry",
                )
            }
        }

        Spacer(modifier = Modifier.height(height = 16.dp))
        BitwardenInfoCalloutCard(
            text = stringResource(
                id = BitwardenString.values_above_the_default_improve_security_desc_long,
            ),
            startIcon = IconData.Local(iconRes = BitwardenDrawable.ic_warning),
            modifier = Modifier
                .standardHorizontalMargin()
                .fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(height = 16.dp))
        Text(
            text = stringResource(id = BitwardenString.no_device_is_logged_out_desc_long),
            style = BitwardenTheme.typography.bodyMedium,
            color = BitwardenTheme.colorScheme.text.primary,
            modifier = Modifier
                .standardHorizontalMargin()
                .fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(height = 24.dp))
        BitwardenFilledButton(
            label = stringResource(id = BitwardenString.update_encryption_settings),
            onClick = onUpdateClick,
            modifier = Modifier
                .testTag(tag = "UpdateEncryptionSettingsButton")
                .standardHorizontalMargin()
                .fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(height = 16.dp))
        Spacer(modifier = Modifier.navigationBarsPadding())
    }
}

@Composable
private fun KdfNumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    cardStyle: CardStyle,
    testTag: String,
    supportingText: String? = null,
) {
    BitwardenTextField(
        label = label,
        value = value,
        onValueChange = onValueChange,
        supportingText = supportingText,
        keyboardType = KeyboardType.Number,
        cardStyle = cardStyle,
        textFieldTestTag = testTag,
        modifier = Modifier
            .standardHorizontalMargin()
            .fillMaxWidth(),
    )
}
