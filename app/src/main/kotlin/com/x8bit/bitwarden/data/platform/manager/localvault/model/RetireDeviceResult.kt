package com.x8bit.bitwarden.data.platform.manager.localvault.model

/**
 * Models the result of retiring a device that shares the vault folder.
 */
sealed class RetireDeviceResult {
    /**
     * The device is retired.
     */
    data object Success : RetireDeviceResult()

    /**
     * The vault did not retire the device, as its newer file has not fully come to this device yet:
     * the file that stands in for it can be an older one. Nothing was changed. The vault refuses
     * to retire this device the same way, which the app does not ask it to.
     */
    data object NotComplete : RetireDeviceResult()

    /**
     * The device could not be retired, and nothing was changed.
     */
    data class Error(
        val error: Throwable,
    ) : RetireDeviceResult()
}
