package com.x8bit.bitwarden.data.platform.manager.localvault

import com.bitwarden.network.model.LocalVaultDeviceJson
import com.x8bit.bitwarden.data.platform.manager.localvault.model.RetireDeviceResult

/**
 * Manages the devices that share the vault folder: the installations of a client that have a file
 * in the vault of the active account, one of which can be retired once it is gone for good.
 */
interface LocalVaultDevicesManager {
    /**
     * Lists the devices that have a file in the vault of the active account, this one among them
     * whether or not it has one yet.
     */
    suspend fun getDevices(): Result<List<LocalVaultDeviceJson>>

    /**
     * Retires the device with the [deviceId], which is gone for good: this device takes its file
     * over, and its directory is deleted 90 days later, unless it turns out to be still in use.
     */
    suspend fun retireDevice(deviceId: String): RetireDeviceResult
}
