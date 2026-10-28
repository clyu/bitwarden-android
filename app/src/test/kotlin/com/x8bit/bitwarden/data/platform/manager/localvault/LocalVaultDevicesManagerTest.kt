package com.x8bit.bitwarden.data.platform.manager.localvault

import com.bitwarden.core.data.util.asFailure
import com.bitwarden.core.data.util.asSuccess
import com.bitwarden.network.local.createMockBadRequestThrowable
import com.bitwarden.network.model.DeviceType
import com.bitwarden.network.model.LocalVaultDeviceJson
import com.bitwarden.network.service.LocalVaultDevicesService
import com.x8bit.bitwarden.data.platform.manager.localvault.model.RetireDeviceResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class LocalVaultDevicesManagerTest {

    private val localVaultDevicesService: LocalVaultDevicesService = mockk()

    private val manager: LocalVaultDevicesManager = LocalVaultDevicesManagerImpl(
        localVaultDevicesService = localVaultDevicesService,
    )

    @Test
    fun `getDevices should return the devices that the vault lists`() = runTest {
        val devices = listOf(THIS_DEVICE, OTHER_DEVICE)
        coEvery { localVaultDevicesService.getDevices() } returns devices.asSuccess()

        assertEquals(devices.asSuccess(), manager.getDevices())
    }

    @Test
    fun `getDevices should fail when the devices cannot be listed`() = runTest {
        val error = Throwable("Fail")
        coEvery { localVaultDevicesService.getDevices() } returns error.asFailure()

        assertEquals(error.asFailure(), manager.getDevices())
    }

    @Test
    fun `retireDevice should return Success when the vault retired the device`() = runTest {
        coEvery {
            localVaultDevicesService.retireDevice(deviceId = OTHER_DEVICE.id)
        } returns OTHER_DEVICE.copy(retiredDate = RETIRED_AT).asSuccess()

        val result = manager.retireDevice(deviceId = OTHER_DEVICE.id)

        assertEquals(RetireDeviceResult.Success, result)
        coVerify(exactly = 1) {
            localVaultDevicesService.retireDevice(deviceId = OTHER_DEVICE.id)
        }
    }

    @Test
    fun `retireDevice should return NotComplete when the vault refuses the device`() = runTest {
        val message =
            "The file of the device has not fully come to this device yet. Try again later."
        coEvery {
            localVaultDevicesService.retireDevice(deviceId = OTHER_DEVICE.id)
        } returns createMockBadRequestThrowable(message = message).asFailure()

        val result = manager.retireDevice(deviceId = OTHER_DEVICE.id)

        assertEquals(RetireDeviceResult.NotComplete, result)
    }

    @Test
    fun `retireDevice should return Error when the device could not be retired`() = runTest {
        val error = Throwable("Fail")
        coEvery {
            localVaultDevicesService.retireDevice(deviceId = OTHER_DEVICE.id)
        } returns error.asFailure()

        val result = manager.retireDevice(deviceId = OTHER_DEVICE.id)

        assertEquals(RetireDeviceResult.Error(error = error), result)
    }
}

private val RETIRED_AT: Instant = Instant.parse("2026-10-11T12:00:00Z")

private val THIS_DEVICE = LocalVaultDeviceJson(
    id = "mockDeviceId-1",
    name = "mockDeviceName-1",
    type = DeviceType.ANDROID,
    isCurrentDevice = true,
    revisionDate = Instant.parse("2026-10-01T12:00:00Z"),
    retiredDate = null,
    removalDate = null,
)

private val OTHER_DEVICE = LocalVaultDeviceJson(
    id = "mockDeviceId-2",
    name = "chrome",
    type = DeviceType.CHROME_EXTENSION,
    isCurrentDevice = false,
    revisionDate = Instant.parse("2025-01-01T12:00:00Z"),
    retiredDate = null,
    removalDate = null,
)
