package com.bitwarden.network.local

import com.bitwarden.localvault.accountsMissingIn
import com.bitwarden.localvault.deviceFilesMissingIn
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class LocalVaultCopyTest {
    private val root: File = Files.createTempDirectory("local-vault").toFile()
    private val targetRoot: File = Files.createTempDirectory("local-vault-target").toFile()

    @AfterEach
    fun tearDown() {
        root.deleteRecursively()
        targetRoot.deleteRecursively()
    }

    @Test
    fun `copyMissingAccountsTo should copy the accounts that the target does not have`() {
        writeFile(root = root, path = "$ACCOUNT_ID/vault.json", content = "first")
        writeFile(root = root, path = "$ACCOUNT_ID/attachments/$CIPHER_ID/$ATTACHMENT_ID")
        writeFile(root = root, path = "$OTHER_ACCOUNT_ID/vault.json", content = "source")
        writeFile(root = targetRoot, path = "$OTHER_ACCOUNT_ID/vault.json", content = "target")
        // Not an account, as its name could not be the ID of one.
        writeFile(root = root, path = "notes.txt", content = "mockNotes")

        FileLocalVaultFileSystem(root = root)
            .copyMissingAccountsTo(target = FileLocalVaultFileSystem(root = targetRoot))

        assertEquals("first", File(targetRoot, "$ACCOUNT_ID/vault.json").readText())
        assertArrayEquals(
            ATTACHMENT_CONTENT,
            File(targetRoot, "$ACCOUNT_ID/attachments/$CIPHER_ID/$ATTACHMENT_ID").readBytes(),
        )
        // The account that the target already had is the one it had.
        assertEquals("target", File(targetRoot, "$OTHER_ACCOUNT_ID/vault.json").readText())
        assertEquals(
            listOf(ACCOUNT_ID, OTHER_ACCOUNT_ID),
            targetRoot.list().orEmpty().sorted(),
        )
    }

    @Test
    fun `missingAccountsIn should find the accounts that the local vault finds`() {
        // Names that the local vault could have given an account, up to the longest one.
        val longestAccountId = "x".repeat(n = 128)
        listOf(ACCOUNT_ID, OTHER_ACCOUNT_ID, "MOCK_ACCOUNT_ID_3", longestAccountId).forEach {
            writeFile(root = root, path = "$it/vault.json", content = "source")
        }
        writeFile(root = targetRoot, path = "$OTHER_ACCOUNT_ID/vault.json", content = "target")
        // Names that it could not have: too long, or with a character that it does not use.
        listOf("x".repeat(n = 129), "mockAccountId~4", "mockAccountId 5", "mockAccountIdé")
            .forEach { writeFile(root = root, path = "$it/vault.json", content = "mockOther") }
        writeFile(root = root, path = "notes.txt", content = "mockNotes")
        val source = FileLocalVaultFileSystem(root = root)
        val target = FileLocalVaultFileSystem(root = targetRoot)

        val missing = source.missingAccountsIn(target = target)

        assertEquals(listOf("MOCK_ACCOUNT_ID_3", ACCOUNT_ID, longestAccountId), missing)
        assertEquals(
            accountsMissingIn(
                source = FileSystemCallback(fileSystem = source),
                target = FileSystemCallback(fileSystem = target),
            ),
            missing,
        )
    }

    @Test
    fun `copyDeviceFilesTo should copy what the device wrote to an account that the target has`() {
        writeDeviceAccount()

        FileLocalVaultFileSystem(root = root).copyDeviceFilesTo(
            target = FileLocalVaultFileSystem(root = targetRoot),
            deviceId = DEVICE_ID,
        )

        assertEquals("latest", File(targetRoot, "$DEVICE_DIRECTORY/vault.json").readText())
        assertEquals("older", File(targetRoot, "$DEVICE_DIRECTORY/vault.json.bak").readText())
        assertArrayEquals(
            ATTACHMENT_CONTENT,
            File(targetRoot, "$ACCOUNT_ID/attachments/$CIPHER_ID/$OTHER_ATTACHMENT_ID").readBytes(),
        )
        // The file of another device is the one that the target had.
        assertEquals("newer", File(targetRoot, "$OTHER_DEVICE_DIRECTORY/vault.json").readText())
        // An account that the target does not have is left to copyMissingAccountsTo.
        assertEquals(listOf(ACCOUNT_ID), targetRoot.list().orEmpty().toList())
    }

    @Test
    fun `missingDeviceFilesIn should find the files that the local vault finds`() {
        writeDeviceAccount()
        val source = FileLocalVaultFileSystem(root = root)
        val target = FileLocalVaultFileSystem(root = targetRoot)

        val missing = source.missingDeviceFilesIn(target = target, deviceId = DEVICE_ID)

        assertEquals(
            listOf(
                listOf(ACCOUNT_ID, "attachments", CIPHER_ID, OTHER_ATTACHMENT_ID),
                listOf(ACCOUNT_ID, "devices", DEVICE_ID, "vault.json"),
            ),
            missing,
        )
        assertEquals(
            deviceFilesMissingIn(
                source = FileSystemCallback(fileSystem = source),
                target = FileSystemCallback(fileSystem = target),
                deviceId = DEVICE_ID,
            ),
            missing,
        )
        // Nothing for an ID that could not be the name of a directory.
        assertEquals(
            emptyList<List<String>>(),
            source.missingDeviceFilesIn(target = target, deviceId = "mock/DeviceId"),
        )
    }

    /**
     * Writes an account that both vault folders have, which the device changed in the source
     * since the target was filled, and an account that only the source has.
     */
    private fun writeDeviceAccount() {
        listOf(root, targetRoot).forEach {
            writeFile(root = it, path = "$DEVICE_DIRECTORY/vault.json.bak", content = "older")
            writeFile(root = it, path = "$ACCOUNT_ID/attachments/$CIPHER_ID/$ATTACHMENT_ID")
        }
        writeFile(root = root, path = "$DEVICE_DIRECTORY/vault.json", content = "latest")
        writeFile(root = targetRoot, path = "$DEVICE_DIRECTORY/vault.json", content = "earlier")
        writeFile(root = root, path = "$ACCOUNT_ID/attachments/$CIPHER_ID/$OTHER_ATTACHMENT_ID")
        writeFile(root = root, path = "$OTHER_DEVICE_DIRECTORY/vault.json", content = "other")
        writeFile(root = targetRoot, path = "$OTHER_DEVICE_DIRECTORY/vault.json", content = "newer")
        writeFile(root = root, path = "$OTHER_ACCOUNT_ID/devices/$DEVICE_ID/vault.json")
    }

    private fun writeFile(root: File, path: String, content: String? = null) {
        val file = File(root, path)
        file.parentFile?.mkdirs()
        if (content == null) file.writeBytes(ATTACHMENT_CONTENT) else file.writeText(content)
    }
}

private const val ACCOUNT_ID: String = "mockAccountId-1"
private const val OTHER_ACCOUNT_ID: String = "mockAccountId-2"
private const val CIPHER_ID: String = "mockCipherId"
private const val ATTACHMENT_ID: String = "mockAttachmentId"
private const val OTHER_ATTACHMENT_ID: String = "mockAttachmentId-2"
private const val DEVICE_ID: String = "mockDeviceId"
private const val DEVICE_DIRECTORY: String = "$ACCOUNT_ID/devices/$DEVICE_ID"
private const val OTHER_DEVICE_DIRECTORY: String = "$ACCOUNT_ID/devices/mockDeviceId-2"

private val ATTACHMENT_CONTENT: ByteArray = byteArrayOf(1, 2, 3)
