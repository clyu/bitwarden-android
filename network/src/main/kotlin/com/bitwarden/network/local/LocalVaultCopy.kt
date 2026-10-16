package com.bitwarden.network.local

import java.io.IOException

/**
 * What a name that the local vault uses looks like: that of the directory of an account, of a
 * device, of an item, and of the content of an attachment. Anything else in a vault folder is
 * none of these.
 */
private val SAFE_NAME_REGEX: Regex = Regex("^[A-Za-z0-9_-]{1,128}$")

private const val ATTACHMENTS_DIRECTORY: String = "attachments"
private const val DEVICES_DIRECTORY: String = "devices"

/**
 * The files of the directory of a device, in the order that they are copied in: `vault.json`
 * last, so that a copy that is interrupted leaves the earlier file in its place.
 */
private val DEVICE_FILES: List<String> = listOf("vault.json.bak", "vault.json.tmp", "vault.json")

/**
 * Copies every account that exists in this [LocalVaultFileSystem] but not in the [target]. An
 * account that the [target] already has is left untouched.
 */
@Throws(IOException::class)
fun LocalVaultFileSystem.copyMissingAccountsTo(target: LocalVaultFileSystem) {
    missingAccountsIn(target = target).forEach { copyTree(path = listOf(it), target = target) }
}

/**
 * Returns the accounts that this [LocalVaultFileSystem] has and the [target] does not, sorted.
 *
 * It is `accounts_missing_in` of the local vault, which the tests check it against. It is not
 * called from here, so that moving the vault does not need the library of the local vault, which
 * the unit tests of the app do not load.
 */
@Throws(IOException::class)
internal fun LocalVaultFileSystem.missingAccountsIn(target: LocalVaultFileSystem): List<String> =
    safeNames(directory = emptyList()).filterNot { target.exists(path = listOf(it)) }

/**
 * Copies, of every account that both this [LocalVaultFileSystem] and the [target] have, what the
 * [target] does not have yet of what the device with the [deviceId] wrote: the files of its own
 * directory of the account that the [target] has otherwise or not at all, and the content of
 * attachments that the [target] does not have. No other device writes that directory, so its
 * files here are the ones that the device wrote last. The files of other devices are never
 * copied, as those of the [target] are on their way to it.
 */
@Throws(IOException::class)
fun LocalVaultFileSystem.copyDeviceFilesTo(target: LocalVaultFileSystem, deviceId: String) {
    missingDeviceFilesIn(target = target, deviceId = deviceId)
        .forEach { copyTree(path = it, target = target) }
}

/**
 * Returns the files that [copyDeviceFilesTo] copies, in the order that it copies them in: the
 * content of attachments before the file that lists them, and the `vault.json` of the device last.
 *
 * It is `device_files_missing_in` of the local vault, which the tests check it against.
 */
@Throws(IOException::class)
internal fun LocalVaultFileSystem.missingDeviceFilesIn(
    target: LocalVaultFileSystem,
    deviceId: String,
): List<List<String>> {
    if (!SAFE_NAME_REGEX.matches(deviceId)) return emptyList()
    return safeNames(directory = emptyList())
        .filter { target.exists(path = listOf(it)) }
        .flatMap { accountId ->
            missingAttachmentsIn(target = target, accountId = accountId) +
                changedDeviceFilesIn(target = target, accountId = accountId, deviceId = deviceId)
        }
}

/**
 * Returns the content of the attachments of the account that the [target] does not have.
 */
private fun LocalVaultFileSystem.missingAttachmentsIn(
    target: LocalVaultFileSystem,
    accountId: String,
): List<List<String>> =
    safeNames(directory = listOf(accountId, ATTACHMENTS_DIRECTORY)).flatMap { cipherId ->
        val directory = listOf(accountId, ATTACHMENTS_DIRECTORY, cipherId)
        safeNames(directory = directory)
            .map { directory + it }
            .filterNot { target.exists(path = it) }
    }

/**
 * Returns the files of the directory of the device in the account that the [target] has
 * otherwise or not at all.
 */
private fun LocalVaultFileSystem.changedDeviceFilesIn(
    target: LocalVaultFileSystem,
    accountId: String,
    deviceId: String,
): List<List<String>> =
    DEVICE_FILES
        .map { listOf(accountId, DEVICES_DIRECTORY, deviceId, it) }
        .filter { path ->
            val content = readFile(path = path) ?: return@filter false
            !content.contentEquals(target.readFile(path = path))
        }

/**
 * Returns the names in the [directory] that the local vault could have given an entry, sorted, as
 * the order that a directory is listed in is not the same everywhere.
 */
private fun LocalVaultFileSystem.safeNames(directory: List<String>): List<String> =
    list(directory).filter { SAFE_NAME_REGEX.matches(it) }.sorted()

private fun LocalVaultFileSystem.readFile(path: List<String>): ByteArray? =
    openInput(path = path)?.use { it.readBytes() }

private fun LocalVaultFileSystem.copyTree(path: List<String>, target: LocalVaultFileSystem) {
    val input = openInput(path = path)
    if (input == null) {
        list(path).forEach { copyTree(path = path + it, target = target) }
        return
    }
    input.use { source ->
        target.openOutput(path = path).use { source.copyTo(it) }
    }
}
