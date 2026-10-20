package com.bitwarden.network.local

import com.bitwarden.localvault.InternalException
import com.bitwarden.localvault.LocalVaultException
import com.bitwarden.localvault.accountsMissingIn
import com.bitwarden.localvault.deviceFilesMissingIn
import java.io.IOException

/**
 * Primary implementation of [LocalVaultCopier].
 *
 * What is copied is worked out by the local vault (`accounts_missing_in` and
 * `device_files_missing_in`), as it is for every client that moves a vault. This only copies it,
 * as a stream, as the content of an attachment can be large.
 */
class LocalVaultCopierImpl : LocalVaultCopier {
    override fun copy(
        source: LocalVaultFileSystem,
        target: LocalVaultFileSystem,
        deviceId: String,
    ) {
        val sourceFolder = FileSystemCallback(fileSystem = source)
        val targetFolder = FileSystemCallback(fileSystem = target)
        val accountIds = withLocalVaultFailures {
            accountsMissingIn(source = sourceFolder, target = targetFolder)
        }
        accountIds.forEach { source.copyTree(path = listOf(it), target = target) }
        // In the order that the local vault gives them in: the content of attachments before the
        // file that lists them, and the `vault.json` of the installation last, so that a copy
        // that is interrupted leaves the earlier file in its place.
        val files = withLocalVaultFailures {
            deviceFilesMissingIn(
                source = sourceFolder,
                target = targetFolder,
                deviceId = deviceId,
            )
        }
        files.forEach { source.copyTree(path = it, target = target) }
    }
}

/**
 * Runs the given [block] of the local vault, and reports what fails in it as an [IOException]:
 * the local vault fails when a vault folder cannot be read.
 */
@Throws(IOException::class)
private inline fun <T> withLocalVaultFailures(block: () -> T): T =
    try {
        block()
    } catch (e: LocalVaultException) {
        throw IOException(e.reason(), e)
    } catch (e: InternalException) {
        // A failure of the local vault itself fails the copy as well, and not the app.
        throw IOException(e.message, e)
    }

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
