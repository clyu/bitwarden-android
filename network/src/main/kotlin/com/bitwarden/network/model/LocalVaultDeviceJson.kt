package com.bitwarden.network.model

import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * A device that has a file in the vault of the active account, as the local vault lists it: an
 * installation of a client that shares the vault folder.
 *
 * @property id The ID of the installation, which names the directory that it writes its changes
 * to.
 * @property name The name of the device, as its client names it to a Bitwarden server: the model
 * of a phone, or the browser that an extension is in. `null` if its file does not say, as one
 * that an earlier version of a client wrote does not.
 * @property type The type of the device, as its client names it to a Bitwarden server, if its file
 * says.
 * @property isCurrentDevice Whether it is this installation of the app.
 * @property revisionDate When the device last changed the vault, if its file says so.
 * @property retiredDate When the device was retired, if it was.
 * @property removalDate When the directory of the device is deleted, if it was retired.
 */
@Serializable
data class LocalVaultDeviceJson(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String?,
    @SerialName("type") val type: DeviceType?,
    @SerialName("isCurrentDevice") val isCurrentDevice: Boolean,
    @Contextual @SerialName("revisionDate") val revisionDate: Instant?,
    @Contextual @SerialName("retiredDate") val retiredDate: Instant?,
    @Contextual @SerialName("removalDate") val removalDate: Instant?,
)
