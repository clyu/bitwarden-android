package com.bitwarden.network.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response body for the devices that share the vault folder of the active account.
 *
 * @property devices The devices that have a file in the vault of the account.
 */
@Serializable
data class LocalVaultDevicesResponseJson(
    @SerialName("data") val devices: List<LocalVaultDeviceJson>,
)
