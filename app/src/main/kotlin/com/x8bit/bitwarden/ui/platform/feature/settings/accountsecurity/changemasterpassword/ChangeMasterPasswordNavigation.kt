package com.x8bit.bitwarden.ui.platform.feature.settings.accountsecurity.changemasterpassword

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import com.bitwarden.ui.platform.base.util.composableWithPushTransitions
import kotlinx.serialization.Serializable

/**
 * The type-safe route for the screen that changes the master password.
 */
@Serializable
data object ChangeMasterPasswordRoute

/**
 * Add the screen that changes the master password to the nav graph.
 */
fun NavGraphBuilder.changeMasterPasswordDestination(
    onNavigateBack: () -> Unit,
) {
    composableWithPushTransitions<ChangeMasterPasswordRoute> {
        ChangeMasterPasswordScreen(onNavigateBack = onNavigateBack)
    }
}

/**
 * Navigate to the screen that changes the master password.
 */
fun NavController.navigateToChangeMasterPassword(navOptions: NavOptions? = null) {
    this.navigate(route = ChangeMasterPasswordRoute, navOptions = navOptions)
}
