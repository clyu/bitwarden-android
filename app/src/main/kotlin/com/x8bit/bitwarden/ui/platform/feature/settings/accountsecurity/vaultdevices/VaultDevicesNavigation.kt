package com.x8bit.bitwarden.ui.platform.feature.settings.accountsecurity.vaultdevices

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import com.bitwarden.ui.platform.base.util.composableWithPushTransitions
import kotlinx.serialization.Serializable

/**
 * The type-safe route for the screen with the devices that share the vault folder.
 */
@Serializable
data object VaultDevicesRoute

/**
 * Add the screen with the devices that share the vault folder to the nav graph.
 */
fun NavGraphBuilder.vaultDevicesDestination(
    onNavigateBack: () -> Unit,
) {
    composableWithPushTransitions<VaultDevicesRoute> {
        VaultDevicesScreen(onNavigateBack = onNavigateBack)
    }
}

/**
 * Navigate to the screen with the devices that share the vault folder.
 */
fun NavController.navigateToVaultDevices(navOptions: NavOptions? = null) {
    this.navigate(route = VaultDevicesRoute, navOptions = navOptions)
}
