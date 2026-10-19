package com.x8bit.bitwarden.ui.platform.feature.settings.accountsecurity.kdfsettings

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import com.bitwarden.ui.platform.base.util.composableWithPushTransitions
import kotlinx.serialization.Serializable

/**
 * The type-safe route for the screen that changes how the master key is derived.
 */
@Serializable
data object KdfSettingsRoute

/**
 * Add the screen that changes how the master key is derived to the nav graph.
 */
fun NavGraphBuilder.kdfSettingsDestination(
    onNavigateBack: () -> Unit,
) {
    composableWithPushTransitions<KdfSettingsRoute> {
        KdfSettingsScreen(onNavigateBack = onNavigateBack)
    }
}

/**
 * Navigate to the screen that changes how the master key is derived.
 */
fun NavController.navigateToKdfSettings(navOptions: NavOptions? = null) {
    this.navigate(route = KdfSettingsRoute, navOptions = navOptions)
}
