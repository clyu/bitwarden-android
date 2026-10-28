package com.bitwarden.network.service

import com.bitwarden.network.api.LocalVaultDevicesApi
import com.bitwarden.network.model.LocalVaultDeviceJson
import com.bitwarden.network.util.toResult

/**
 * The default implementation of the [LocalVaultDevicesService].
 */
internal class LocalVaultDevicesServiceImpl(
    private val localVaultDevicesApi: LocalVaultDevicesApi,
) : LocalVaultDevicesService {
    override suspend fun getDevices(): Result<List<LocalVaultDeviceJson>> =
        localVaultDevicesApi
            .getDevices()
            .toResult()
            .map { it.devices }

    override suspend fun retireDevice(deviceId: String): Result<LocalVaultDeviceJson> =
        localVaultDevicesApi
            .retireDevice(deviceId = deviceId)
            .toResult()
}
