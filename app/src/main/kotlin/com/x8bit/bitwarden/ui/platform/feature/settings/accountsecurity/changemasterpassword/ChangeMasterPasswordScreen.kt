package com.x8bit.bitwarden.ui.platform.feature.settings.accountsecurity.changemasterpassword

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitwarden.ui.platform.base.util.EventsEffect
import com.bitwarden.ui.platform.base.util.standardHorizontalMargin
import com.bitwarden.ui.platform.components.appbar.BitwardenTopAppBar
import com.bitwarden.ui.platform.components.button.BitwardenFilledButton
import com.bitwarden.ui.platform.components.card.BitwardenInfoCalloutCard
import com.bitwarden.ui.platform.components.content.BitwardenLoadingContent
import com.bitwarden.ui.platform.components.dialog.BitwardenBasicDialog
import com.bitwarden.ui.platform.components.dialog.BitwardenLoadingDialog
import com.bitwarden.ui.platform.components.dialog.BitwardenTwoButtonDialog
import com.bitwarden.ui.platform.components.field.BitwardenPasswordField
import com.bitwarden.ui.platform.components.field.BitwardenTextField
import com.bitwarden.ui.platform.components.icon.model.IconData
import com.bitwarden.ui.platform.components.model.CardStyle
import com.bitwarden.ui.platform.components.scaffold.BitwardenScaffold
import com.bitwarden.ui.platform.components.toggle.BitwardenSwitch
import com.bitwarden.ui.platform.components.util.rememberVectorPainter
import com.bitwarden.ui.platform.resource.BitwardenDrawable
import com.bitwarden.ui.platform.resource.BitwardenString
import com.bitwarden.ui.platform.theme.BitwardenTheme
import com.x8bit.bitwarden.ui.auth.feature.completeregistration.PasswordStrengthIndicator

/**
 * Displays the screen that changes the master password.
 */
@Suppress("LongMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangeMasterPasswordScreen(
    onNavigateBack: () -> Unit,
    viewModel: ChangeMasterPasswordViewModel = hiltViewModel(),
) {
    val state by viewModel.stateFlow.collectAsStateWithLifecycle()
    EventsEffect(viewModel = viewModel) { event ->
        when (event) {
            ChangeMasterPasswordEvent.NavigateBack -> onNavigateBack()
        }
    }

    ChangeMasterPasswordDialogs(
        dialogState = state.dialog,
        onContinueWithBreachedPasswordClick = {
            viewModel.trySendAction(ChangeMasterPasswordAction.ContinueWithBreachedPasswordClick)
        },
        onDismissRequest = { viewModel.trySendAction(ChangeMasterPasswordAction.DismissDialog) },
    )

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    BitwardenScaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            BitwardenTopAppBar(
                title = stringResource(id = BitwardenString.change_master_password),
                scrollBehavior = scrollBehavior,
                navigationIcon = rememberVectorPainter(id = BitwardenDrawable.ic_back),
                navigationIconContentDescription = stringResource(id = BitwardenString.back),
                onNavigationIconClick = {
                    viewModel.trySendAction(ChangeMasterPasswordAction.BackClick)
                },
            )
        },
    ) {
        if (state.isSyncing) {
            BitwardenLoadingContent(modifier = Modifier.fillMaxSize())
        } else {
            ChangeMasterPasswordContent(
                state = state,
                onCurrentPasswordInputChange = {
                    viewModel.trySendAction(
                        ChangeMasterPasswordAction.CurrentPasswordInputChange(input = it),
                    )
                },
                onPasswordInputChange = {
                    viewModel.trySendAction(
                        ChangeMasterPasswordAction.PasswordInputChange(input = it),
                    )
                },
                onRetypePasswordInputChange = {
                    viewModel.trySendAction(
                        ChangeMasterPasswordAction.RetypePasswordInputChange(input = it),
                    )
                },
                onPasswordHintInputChange = {
                    viewModel.trySendAction(
                        ChangeMasterPasswordAction.PasswordHintInputChange(input = it),
                    )
                },
                onCheckDataBreachesToggle = {
                    viewModel.trySendAction(
                        ChangeMasterPasswordAction.CheckDataBreachesToggle(newState = it),
                    )
                },
                onChangeMasterPasswordClick = {
                    viewModel.trySendAction(ChangeMasterPasswordAction.ChangeMasterPasswordClick)
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ChangeMasterPasswordDialogs(
    dialogState: ChangeMasterPasswordState.DialogState?,
    onContinueWithBreachedPasswordClick: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    when (dialogState) {
        is ChangeMasterPasswordState.DialogState.Loading -> {
            BitwardenLoadingDialog(text = dialogState.message())
        }

        is ChangeMasterPasswordState.DialogState.HaveIBeenPwned -> {
            BitwardenTwoButtonDialog(
                title = dialogState.title(),
                message = dialogState.message(),
                confirmButtonText = stringResource(id = BitwardenString.yes),
                dismissButtonText = stringResource(id = BitwardenString.no),
                onConfirmClick = onContinueWithBreachedPasswordClick,
                onDismissClick = onDismissRequest,
                onDismissRequest = onDismissRequest,
            )
        }

        is ChangeMasterPasswordState.DialogState.Error -> {
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
private fun ChangeMasterPasswordContent(
    state: ChangeMasterPasswordState,
    onCurrentPasswordInputChange: (String) -> Unit,
    onPasswordInputChange: (String) -> Unit,
    onRetypePasswordInputChange: (String) -> Unit,
    onPasswordHintInputChange: (String) -> Unit,
    onCheckDataBreachesToggle: (Boolean) -> Unit,
    onChangeMasterPasswordClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(state = rememberScrollState()),
    ) {
        Spacer(modifier = Modifier.height(height = 12.dp))
        Text(
            text = stringResource(
                id = BitwardenString.you_will_be_logged_out_of_this_device_desc_long,
            ),
            style = BitwardenTheme.typography.bodyMedium,
            color = BitwardenTheme.colorScheme.text.primary,
            modifier = Modifier
                .standardHorizontalMargin()
                .fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(height = 16.dp))
        BitwardenPasswordField(
            label = stringResource(id = BitwardenString.current_master_password_required),
            value = state.currentPasswordInput,
            onValueChange = onCurrentPasswordInputChange,
            passwordFieldTestTag = "CurrentMasterPasswordEntry",
            cardStyle = CardStyle.Top(dividerPadding = 0.dp),
            modifier = Modifier
                .standardHorizontalMargin()
                .fillMaxWidth(),
        )
        var isNewPasswordVisible by rememberSaveable { mutableStateOf(false) }
        BitwardenPasswordField(
            label = stringResource(id = BitwardenString.new_master_password_required),
            value = state.passwordInput,
            onValueChange = onPasswordInputChange,
            showPassword = isNewPasswordVisible,
            showPasswordChange = { isNewPasswordVisible = it },
            supportingContent = {
                PasswordStrengthIndicator(
                    state = state.passwordStrengthState,
                    currentCharacterCount = state.passwordInput.length,
                    minimumCharacterCount = state.minimumPasswordLength,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            passwordFieldTestTag = "NewMasterPasswordEntry",
            cardStyle = CardStyle.Middle(dividerPadding = 0.dp),
            modifier = Modifier
                .standardHorizontalMargin()
                .fillMaxWidth(),
        )
        BitwardenPasswordField(
            label = stringResource(id = BitwardenString.retype_new_master_password_required),
            value = state.retypePasswordInput,
            onValueChange = onRetypePasswordInputChange,
            showPassword = isNewPasswordVisible,
            showPasswordChange = { isNewPasswordVisible = it },
            passwordFieldTestTag = "RetypeNewMasterPasswordEntry",
            cardStyle = CardStyle.Middle(dividerPadding = 0.dp),
            modifier = Modifier
                .standardHorizontalMargin()
                .fillMaxWidth(),
        )
        BitwardenTextField(
            label = stringResource(id = BitwardenString.master_password_hint),
            value = state.passwordHintInput,
            onValueChange = onPasswordHintInputChange,
            supportingText = stringResource(
                id = BitwardenString.anyone_who_can_read_the_vault_folder_can_read_the_hint,
            ),
            textFieldTestTag = "MasterPasswordHintEntry",
            cardStyle = CardStyle.Bottom,
            modifier = Modifier
                .standardHorizontalMargin()
                .fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(height = 16.dp))
        BitwardenSwitch(
            label = stringResource(
                id = BitwardenString.check_known_data_breaches_for_this_password,
            ),
            isChecked = state.isCheckDataBreachesToggled,
            onCheckedChange = onCheckDataBreachesToggle,
            cardStyle = CardStyle.Full,
            modifier = Modifier
                .testTag("CheckExposedMasterPasswordToggle")
                .standardHorizontalMargin()
                .fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(height = 16.dp))
        BitwardenInfoCalloutCard(
            text = stringResource(
                id = BitwardenString.changing_your_master_password_does_not_lock_out_desc_long,
            ),
            startIcon = IconData.Local(iconRes = BitwardenDrawable.ic_warning),
            modifier = Modifier
                .standardHorizontalMargin()
                .fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(height = 24.dp))
        BitwardenFilledButton(
            label = stringResource(id = BitwardenString.change_master_password),
            onClick = onChangeMasterPasswordClick,
            modifier = Modifier
                .testTag(tag = "ChangeMasterPasswordButton")
                .standardHorizontalMargin()
                .fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(height = 16.dp))
        Spacer(modifier = Modifier.navigationBarsPadding())
    }
}
