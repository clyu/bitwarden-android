package com.bitwarden.network.local

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files

/**
 * Tests the [LocalVaultCopierImpl] with the local vault, which works out what it copies.
 */
class LocalVaultCopierTest {
    private val root: File = Files.createTempDirectory("local-vault").toFile()
    private val targetRoot: File = Files.createTempDirectory("local-vault-target").toFile()
    private val source = FileLocalVaultFileSystem(root = root)
    private val target = FileLocalVaultFileSystem(root = targetRoot)

    private val localVaultCopier: LocalVaultCopier = LocalVaultCopierImpl()

    @AfterEach
    fun tearDown() {
        root.deleteRecursively()
        targetRoot.deleteRecursively()
    }

    @Test
    fun `copy should copy the accounts that the target does not have`() {
        writeFile(root = root, path = "$ACCOUNT_ID/vault.json", content = "first")
        writeFile(root = root, path = "$ACCOUNT_ID/attachments/$CIPHER_ID/$ATTACHMENT_ID")
        writeFile(root = root, path = "$OTHER_ACCOUNT_ID/vault.json", content = "source")
        writeFile(root = targetRoot, path = "$OTHER_ACCOUNT_ID/vault.json", content = "target")
        // Not an account, as its name could not be the ID of one.
        writeFile(root = root, path = "notes.txt", content = "mockNotes")

        localVaultCopier.copy(source = source, target = target, deviceId = DEVICE_ID)

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
    fun `copy should copy what the device wrote to an account that the target has`() {
        writeDeviceAccount()

        localVaultCopier.copy(source = source, target = target, deviceId = DEVICE_ID)

        assertEquals("latest", File(targetRoot, "$DEVICE_DIRECTORY/vault.json").readText())
        assertEquals("older", File(targetRoot, "$DEVICE_DIRECTORY/vault.json.bak").readText())
        assertArrayEquals(
            ATTACHMENT_CONTENT,
            File(targetRoot, "$ACCOUNT_ID/attachments/$CIPHER_ID/$OTHER_ATTACHMENT_ID").readBytes(),
        )
        // The file of another device is the one that the target had.
        assertEquals("newer", File(targetRoot, "$OTHER_DEVICE_DIRECTORY/vault.json").readText())
    }

    @Test
    fun `copy should write the files in the order that the local vault gives them`() {
        writeDeviceAccount()
        val written = mutableListOf<List<String>>()
        val recordingTarget = object : LocalVaultFileSystem by target {
            override fun openOutput(path: List<String>): OutputStream =
                target.openOutput(path = path).also { written.add(path) }
        }

        localVaultCopier.copy(source = source, target = recordingTarget, deviceId = DEVICE_ID)

        assertEquals(
            listOf(
                // The account that the target does not have, with everything in it.
                listOf(OTHER_ACCOUNT_ID, "devices", DEVICE_ID, "vault.json"),
                // The content of an attachment before the file that lists it.
                listOf(ACCOUNT_ID, "attachments", CIPHER_ID, OTHER_ATTACHMENT_ID),
                listOf(ACCOUNT_ID, "devices", DEVICE_ID, "vault.json"),
            ),
            written,
        )
    }

    @Test
    fun `copy should copy nothing of an account that the target has for an invalid device ID`() {
        writeDeviceAccount()

        localVaultCopier.copy(source = source, target = target, deviceId = "mock/DeviceId")

        assertEquals("earlier", File(targetRoot, "$DEVICE_DIRECTORY/vault.json").readText())
        val attachment = File(targetRoot, "$ACCOUNT_ID/attachments/$CIPHER_ID/$OTHER_ATTACHMENT_ID")
        assertFalse(attachment.exists())
    }

    @Test
    fun `copy should throw an IOException when the target cannot be reached`() {
        writeFile(root = root, path = "$ACCOUNT_ID/vault.json", content = "first")
        val unreachableTarget: LocalVaultFileSystem = mockk {
            every { exists(path = any()) } throws IOException("mockMessage")
        }

        val exception = assertThrows<IOException> {
            localVaultCopier.copy(
                source = source,
                target = unreachableTarget,
                deviceId = DEVICE_ID,
            )
        }

        assertEquals("mockMessage", exception.message)
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
