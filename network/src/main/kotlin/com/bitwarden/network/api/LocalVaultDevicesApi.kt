package com.bitwarden.network.api

import androidx.annotation.Keep
import com.bitwarden.network.model.LocalVaultDeviceJson
import com.bitwarden.network.model.LocalVaultDevicesResponseJson
import com.bitwarden.network.model.NetworkResult
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Defines raw calls under the /local-vault/devices API: the devices that share the vault folder,
 * which the local vault answers and a Bitwarden server does not have.
 */
@Keep
internal interface LocalVaultDevicesApi {
    @GET("/local-vault/devices")
    suspend fun getDevices(): NetworkResult<LocalVaultDevicesResponseJson>

    @POST("/local-vault/devices/{deviceId}/retire")
    suspend fun retireDevice(
        @Path(value = "deviceId") deviceId: String,
    ): NetworkResult<LocalVaultDeviceJson>
}
