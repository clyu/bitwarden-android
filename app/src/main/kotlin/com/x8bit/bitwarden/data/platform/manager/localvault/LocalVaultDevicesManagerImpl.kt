package com.x8bit.bitwarden.data.platform.manager.localvault

import com.bitwarden.network.local.isBadRequest
import com.bitwarden.network.model.LocalVaultDeviceJson
import com.bitwarden.network.service.LocalVaultDevicesService
import com.x8bit.bitwarden.data.platform.manager.localvault.model.RetireDeviceResult

/**
 * Primary implementation of [LocalVaultDevicesManager].
 */
class LocalVaultDevicesManagerImpl(
    private val localVaultDevicesService: LocalVaultDevicesService,
) : LocalVaultDevicesManager {
    override suspend fun getDevices(): Result<List<LocalVaultDeviceJson>> =
        localVaultDevicesService.getDevices()

    override suspend fun retireDevice(deviceId: String): RetireDeviceResult =
        localVaultDevicesService
            .retireDevice(deviceId = deviceId)
            .fold(
                onSuccess = { RetireDeviceResult.Success },
                onFailure = { error ->
                    if (error.isBadRequest()) {
                        RetireDeviceResult.NotComplete
                    } else {
                        RetireDeviceResult.Error(error = error)
                    }
                },
            )
}
