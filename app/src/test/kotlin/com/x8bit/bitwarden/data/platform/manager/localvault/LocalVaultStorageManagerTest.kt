package com.x8bit.bitwarden.data.platform.manager.localvault

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.bitwarden.core.data.manager.dispatcher.FakeDispatcherManager
import com.x8bit.bitwarden.data.platform.datasource.disk.LocalVaultDiskSource
import com.x8bit.bitwarden.data.platform.manager.localvault.model.SetVaultFolderResult
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files

class LocalVaultStorageManagerTest {
    private val externalFilesDirectory: File = Files.createTempDirectory("external").toFile()
    private val internalFilesDirectory: File = Files.createTempDirectory("internal").toFile()
    private val folderUri: Uri = mockk()
    private val mockContentResolver: ContentResolver = mockk {
        every { takePersistableUriPermission(any(), any()) } just runs
    }
    private val context: Context = mockk {
        every { getExternalFilesDir(null) } returns externalFilesDirectory
        every { filesDir } returns internalFilesDirectory
        every { contentResolver } returns mockContentResolver
    }
    private val localVaultDiskSource = FakeLocalVaultDiskSource()

    private val localVaultStorageManager: LocalVaultStorageManager = LocalVaultStorageManagerImpl(
        context = context,
        localVaultDiskSource = localVaultDiskSource,
        appIdProvider = mockk {
            every { uniqueAppId } returns DEVICE_ID
        },
        dispatcherManager = FakeDispatcherManager(),
    )

    @BeforeEach
    fun setup() {
        mockkStatic(Uri::class, DocumentsContract::class)
        mockkConstructor(SafLocalVaultFileSystem::class)
        every { Uri.parse(FOLDER_URI) } returns folderUri
        every { folderUri.toString() } returns FOLDER_URI
        every { DocumentsContract.getTreeDocumentId(folderUri) } returns FOLDER_ID
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Uri::class, DocumentsContract::class)
        unmockkConstructor(SafLocalVaultFileSystem::class)
        externalFilesDirectory.deleteRecursively()
        internalFilesDirectory.deleteRecursively()
    }

    @Test
    fun `fileSystem should keep the vault in the storage of the app when no folder was chosen`() {
        val fileSystem = localVaultStorageManager.fileSystem
        val accountDirectory = File(externalFilesDirectory, "vault/$ACCOUNT_ID")

        fileSystem.openOutput(path = VAULT_PATH).use { it.write(VAULT_CONTENT) }

        assertArrayEquals(VAULT_CONTENT, File(accountDirectory, VAULT_FILE).readBytes())
        assertEquals(listOf(ACCOUNT_ID), fileSystem.list(directory = emptyList()))
        assertTrue(fileSystem.exists(path = VAULT_PATH))
        assertArrayEquals(
            VAULT_CONTENT,
            fileSystem.openInput(path = VAULT_PATH)?.use { it.readBytes() },
        )
        assertTrue(fileSystem.rename(path = VAULT_PATH, newName = BACKUP_FILE))
        assertTrue(File(accountDirectory, BACKUP_FILE).isFile)
        assertTrue(fileSystem.delete(path = listOf(ACCOUNT_ID)))
        assertFalse(accountDirectory.exists())
    }

    @Test
    fun `fileSystem should keep the vault in the internal storage when there is no other`() {
        every { context.getExternalFilesDir(null) } returns null

        localVaultStorageManager
            .fileSystem
            .openOutput(path = VAULT_PATH)
            .use { it.write(VAULT_CONTENT) }

        assertArrayEquals(
            VAULT_CONTENT,
            File(internalFilesDirectory, "vault/$ACCOUNT_ID/$VAULT_FILE").readBytes(),
        )
    }

    @Test
    fun `vaultFolderName should be null when no folder was chosen`() {
        assertNull(localVaultStorageManager.vaultFolderName)
    }

    @Test
    fun `vaultFolderName should be the ID of the folder that was chosen`() {
        localVaultDiskSource.vaultFolderUri = FOLDER_URI

        assertEquals(FOLDER_ID, localVaultStorageManager.vaultFolderName)
    }

    @Test
    fun `vaultFolderName should be the URI of the folder when it has no ID`() {
        localVaultDiskSource.vaultFolderUri = FOLDER_URI
        every { DocumentsContract.getTreeDocumentId(folderUri) } throws IllegalArgumentException()

        assertEquals(FOLDER_URI, localVaultStorageManager.vaultFolderName)
    }

    @Test
    fun `setVaultFolder should copy the accounts to the folder and keep the vault in it`() =
        runTest {
            val folderOutput = ByteArrayOutputStream()
            localVaultStorageManager
                .fileSystem
                .openOutput(path = VAULT_PATH)
                .use { it.write(VAULT_CONTENT) }
            every {
                anyConstructed<SafLocalVaultFileSystem>().exists(path = listOf(ACCOUNT_ID))
            } returns false
            every {
                anyConstructed<SafLocalVaultFileSystem>().openOutput(path = VAULT_PATH)
            } returns folderOutput
            every {
                anyConstructed<SafLocalVaultFileSystem>().list(directory = emptyList())
            } returns listOf(OTHER_ACCOUNT_ID)

            val result = localVaultStorageManager.setVaultFolder(uri = folderUri)

            assertEquals(SetVaultFolderResult.Success, result)
            assertArrayEquals(VAULT_CONTENT, folderOutput.toByteArray())
            assertEquals(FOLDER_URI, localVaultDiskSource.vaultFolderUri)
            verify(exactly = 1) {
                mockContentResolver.takePersistableUriPermission(folderUri, FOLDER_PERMISSIONS)
            }
            // From here on the vault is the one that is in the folder.
            assertEquals(
                listOf(OTHER_ACCOUNT_ID),
                localVaultStorageManager.fileSystem.list(directory = emptyList()),
            )
        }

    @Test
    fun `setVaultFolder should not copy an account that the folder already has`() = runTest {
        localVaultStorageManager
            .fileSystem
            .openOutput(path = VAULT_PATH)
            .use { it.write(VAULT_CONTENT) }
        every {
            anyConstructed<SafLocalVaultFileSystem>().exists(path = listOf(ACCOUNT_ID))
        } returns true

        val result = localVaultStorageManager.setVaultFolder(uri = folderUri)

        assertEquals(SetVaultFolderResult.Success, result)
        assertEquals(FOLDER_URI, localVaultDiskSource.vaultFolderUri)
        verify(exactly = 0) {
            anyConstructed<SafLocalVaultFileSystem>().openOutput(path = any())
        }
    }

    @Test
    fun `setVaultFolder should copy the file of this device to an account that the folder has`() =
        runTest {
            val folderOutput = ByteArrayOutputStream()
            localVaultStorageManager
                .fileSystem
                .openOutput(path = DEVICE_FILE_PATH)
                .use { it.write(VAULT_CONTENT) }
            every {
                anyConstructed<SafLocalVaultFileSystem>().exists(path = listOf(ACCOUNT_ID))
            } returns true
            // The folder has the account without the file that the app wrote last.
            every {
                anyConstructed<SafLocalVaultFileSystem>().openInput(path = DEVICE_FILE_PATH)
            } returns null
            every {
                anyConstructed<SafLocalVaultFileSystem>().openOutput(path = DEVICE_FILE_PATH)
            } returns folderOutput

            val result = localVaultStorageManager.setVaultFolder(uri = folderUri)

            assertEquals(SetVaultFolderResult.Success, result)
            assertArrayEquals(VAULT_CONTENT, folderOutput.toByteArray())
            assertEquals(FOLDER_URI, localVaultDiskSource.vaultFolderUri)
        }

    @Test
    fun `setVaultFolder should return an error when the folder cannot be written to`() = runTest {
        val error = IOException("mockMessage")
        localVaultStorageManager
            .fileSystem
            .openOutput(path = VAULT_PATH)
            .use { it.write(VAULT_CONTENT) }
        every {
            anyConstructed<SafLocalVaultFileSystem>().exists(path = listOf(ACCOUNT_ID))
        } returns false
        every {
            anyConstructed<SafLocalVaultFileSystem>().openOutput(path = any())
        } throws error

        val result = localVaultStorageManager.setVaultFolder(uri = folderUri)

        assertEquals(SetVaultFolderResult.Error(error = error), result)
        assertNull(localVaultDiskSource.vaultFolderUri)
    }

    @Test
    fun `setVaultFolder should return an error when the folder may not be used`() = runTest {
        val error = SecurityException("mockMessage")
        every { mockContentResolver.takePersistableUriPermission(any(), any()) } throws error

        val result = localVaultStorageManager.setVaultFolder(uri = folderUri)

        assertEquals(SetVaultFolderResult.Error(error = error), result)
        assertNull(localVaultDiskSource.vaultFolderUri)
    }
}

/**
 * A [LocalVaultDiskSource] that keeps what it is given in memory.
 */
private class FakeLocalVaultDiskSource : LocalVaultDiskSource {
    override var vaultFolderUri: String? = null
}

private const val ACCOUNT_ID: String = "mockAccountId-1"
private const val OTHER_ACCOUNT_ID: String = "mockAccountId-2"
private const val DEVICE_ID: String = "mockDeviceId"
private const val VAULT_FILE: String = "vault.json"
private const val BACKUP_FILE: String = "vault.json.bak"
private const val FOLDER_URI: String = "content://mockAuthority/tree/mockFolderId"
private const val FOLDER_ID: String = "mockFolderId"
private const val FOLDER_PERMISSIONS: Int =
    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

private val VAULT_PATH: List<String> = listOf(ACCOUNT_ID, VAULT_FILE)
private val DEVICE_FILE_PATH: List<String> = listOf(ACCOUNT_ID, "devices", DEVICE_ID, VAULT_FILE)
private val VAULT_CONTENT: ByteArray = byteArrayOf(1, 2, 3)
