package com.bitwarden.network.local

import com.bitwarden.localvault.accountsMissingIn
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

private val ATTACHMENT_CONTENT: ByteArray = byteArrayOf(1, 2, 3)
