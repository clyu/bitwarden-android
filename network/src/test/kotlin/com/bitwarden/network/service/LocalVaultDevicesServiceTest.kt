package com.bitwarden.network.service

import com.bitwarden.core.data.util.asSuccess
import com.bitwarden.network.api.LocalVaultDevicesApi
import com.bitwarden.network.base.BaseServiceTest
import com.bitwarden.network.model.DeviceType
import com.bitwarden.network.model.LocalVaultDeviceJson
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import retrofit2.create
import java.time.Instant

class LocalVaultDevicesServiceTest : BaseServiceTest() {

    private val localVaultDevicesApi: LocalVaultDevicesApi = retrofit.create()
    private val service = LocalVaultDevicesServiceImpl(
        localVaultDevicesApi = localVaultDevicesApi,
    )

    @Test
    fun `getDevices when request response is Failure should return Failure`() = runTest {
        val response = MockResponse().setResponseCode(400)
        server.enqueue(response)
        val actual = service.getDevices()
        assertTrue(actual.isFailure)
    }

    @Test
    fun `getDevices when request response is Success should return Success`() = runTest {
        val response = MockResponse().setBody(GET_DEVICES_RESPONSE_JSON).setResponseCode(200)
        server.enqueue(response)
        val actual = service.getDevices()
        assertEquals(listOf(THIS_DEVICE, RETIRED_DEVICE).asSuccess(), actual)
    }

    @Test
    fun `retireDevice when request response is Failure should return Failure`() = runTest {
        val response = MockResponse().setResponseCode(400)
        server.enqueue(response)
        val actual = service.retireDevice(deviceId = "mockDeviceId-2")
        assertTrue(actual.isFailure)
    }

    @Test
    fun `retireDevice when request response is Success should return Success`() = runTest {
        val response = MockResponse().setBody(RETIRE_DEVICE_RESPONSE_JSON).setResponseCode(200)
        server.enqueue(response)
        val actual = service.retireDevice(deviceId = "mockDeviceId-2")
        assertEquals(RETIRED_DEVICE.asSuccess(), actual)
        assertEquals(
            "/local-vault/devices/mockDeviceId-2/retire",
            server.takeRequest().path,
        )
    }
}

private const val RETIRE_DEVICE_RESPONSE_JSON = """
{
  "object": "localVaultDevice",
  "id": "mockDeviceId-2",
  "name": "chrome",
  "type": 2,
  "isCurrentDevice": false,
  "revisionDate": "2025-01-01T12:00:00.000Z",
  "retiredDate": "2026-10-11T12:00:00.000Z",
  "removalDate": "2027-01-09T12:00:00.000Z"
}
"""

private const val GET_DEVICES_RESPONSE_JSON = """
{
  "object": "list",
  "data": [
    {
      "object": "localVaultDevice",
      "id": "mockDeviceId-1",
      "name": "mockDeviceName-1",
      "type": 0,
      "isCurrentDevice": true,
      "revisionDate": null,
      "retiredDate": null,
      "removalDate": null
    },
    $RETIRE_DEVICE_RESPONSE_JSON
  ],
  "continuationToken": null
}
"""

private val THIS_DEVICE = LocalVaultDeviceJson(
    id = "mockDeviceId-1",
    name = "mockDeviceName-1",
    type = DeviceType.ANDROID,
    isCurrentDevice = true,
    revisionDate = null,
    retiredDate = null,
    removalDate = null,
)

private val RETIRED_DEVICE = LocalVaultDeviceJson(
    id = "mockDeviceId-2",
    name = "chrome",
    type = DeviceType.CHROME_EXTENSION,
    isCurrentDevice = false,
    revisionDate = Instant.parse("2025-01-01T12:00:00Z"),
    retiredDate = Instant.parse("2026-10-11T12:00:00Z"),
    removalDate = Instant.parse("2027-01-09T12:00:00Z"),
)
