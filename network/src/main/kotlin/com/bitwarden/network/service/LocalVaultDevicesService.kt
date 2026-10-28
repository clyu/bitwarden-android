package com.bitwarden.network.service

import com.bitwarden.network.model.LocalVaultDeviceJson

/**
 * Provides an API for the devices that share the vault folder, which the local vault answers and
 * a Bitwarden server does not have.
 */
interface LocalVaultDevicesService {
    /**
     * Lists the devices that have a file in the vault of the active account, this one among them
     * whether or not it has one yet.
     */
    suspend fun getDevices(): Result<List<LocalVaultDeviceJson>>

    /**
     * Retires the device with the [deviceId], which is gone for good: this device takes its file
     * over, and its directory is deleted 90 days later, unless it turns out to be still in use.
     * Returns the device as it is listed then. The local vault refuses it with `400 Bad Request`
     * for this device, and for one whose file has not fully come to this device yet.
     */
    suspend fun retireDevice(deviceId: String): Result<LocalVaultDeviceJson>
}
