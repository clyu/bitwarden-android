package com.x8bit.bitwarden.data.platform.manager.localvault

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.bitwarden.network.local.FileLocalVaultFileSystem
import com.bitwarden.network.local.LocalVaultFileSystem
import com.bitwarden.network.local.copyDeviceFilesTo
import com.bitwarden.network.local.copyMissingAccountsTo
import com.bitwarden.network.provider.AppIdProvider
import com.x8bit.bitwarden.data.platform.datasource.disk.LocalVaultDiskSource
import com.x8bit.bitwarden.data.platform.manager.localvault.model.SetVaultFolderResult
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

private const val VAULT_DIRECTORY: String = "vault"
private const val FOLDER_PERMISSIONS: Int =
    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

/**
 * Primary implementation of [LocalVaultStorageManager].
 *
 * @param appIdProvider Gives the ID of this installation of the app, which names the directory
 * of each account that the local vault writes the changes of the app to.
 */
class LocalVaultStorageManagerImpl(
    private val context: Context,
    private val localVaultDiskSource: LocalVaultDiskSource,
    private val appIdProvider: AppIdProvider,
    private val dispatcherManager: DispatcherManager,
) : LocalVaultStorageManager {
    /**
     * The storage that the vault is kept in until the user chooses a folder. It is the storage
     * of the app that can be reached from a computer, so that the vault can be copied from it.
     */
    private val appFileSystem: LocalVaultFileSystem by lazy {
        FileLocalVaultFileSystem(
            root = File(context.getExternalFilesDir(null) ?: context.filesDir, VAULT_DIRECTORY),
        )
    }

    @Volatile
    private var folderFileSystem: Pair<String, LocalVaultFileSystem>? = null

    override val fileSystem: LocalVaultFileSystem = CurrentLocalVaultFileSystem()

    override val vaultFolderName: String?
        get() = localVaultDiskSource.vaultFolderUri?.let { uri ->
            try {
                DocumentsContract.getTreeDocumentId(uri.toUri())
            } catch (_: IllegalArgumentException) {
                uri
            }
        }

    override suspend fun setVaultFolder(uri: Uri): SetVaultFolderResult =
        withContext(context = dispatcherManager.io) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, FOLDER_PERMISSIONS)
                val source = currentFileSystem()
                val target = SafLocalVaultFileSystem(
                    contentResolver = context.contentResolver,
                    treeUri = uri,
                )
                source.copyMissingAccountsTo(target = target)
                // Of the accounts that the folder already has, what the app wrote last, as when
                // the folder was filled with a copy of the vault from before its last changes.
                source.copyDeviceFilesTo(target = target, deviceId = appIdProvider.uniqueAppId)
                localVaultDiskSource.vaultFolderUri = uri.toString()
                SetVaultFolderResult.Success
            } catch (e: IOException) {
                SetVaultFolderResult.Error(error = e)
            } catch (e: SecurityException) {
                SetVaultFolderResult.Error(error = e)
            }
        }

    private fun currentFileSystem(): LocalVaultFileSystem {
        val uri = localVaultDiskSource.vaultFolderUri ?: return appFileSystem
        return folderFileSystem
            ?.takeIf { it.first == uri }
            ?.second
            ?: SafLocalVaultFileSystem(
                contentResolver = context.contentResolver,
                treeUri = uri.toUri(),
            )
                .also { folderFileSystem = uri to it }
    }

    /**
     * A [LocalVaultFileSystem] that is whichever one the vault is kept in at the time it is used.
     */
    private inner class CurrentLocalVaultFileSystem : LocalVaultFileSystem {
        override fun list(directory: List<String>): List<String> =
            currentFileSystem().list(directory = directory)

        override fun exists(path: List<String>): Boolean =
            currentFileSystem().exists(path = path)

        override fun openInput(path: List<String>): InputStream? =
            currentFileSystem().openInput(path = path)

        override fun openOutput(path: List<String>): OutputStream =
            currentFileSystem().openOutput(path = path)

        override fun rename(path: List<String>, newName: String): Boolean =
            currentFileSystem().rename(path = path, newName = newName)

        override fun delete(path: List<String>): Boolean =
            currentFileSystem().delete(path = path)
    }
}
