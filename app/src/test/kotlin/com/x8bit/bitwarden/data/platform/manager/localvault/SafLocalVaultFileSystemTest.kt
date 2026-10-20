package com.x8bit.bitwarden.data.platform.manager.localvault

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Tests the [SafLocalVaultFileSystem] against a folder that only exists in this test, which
 * stands in for what the Storage Access Framework and a documents provider do on a device.
 */
class SafLocalVaultFileSystemTest {
    private val treeUri: Uri = mockk()
    private val contentResolver: ContentResolver = mockk()

    /**
     * Everything that is in the folder by its ID, in the order that it was added.
     */
    private val documents: MutableMap<String, FakeDocument> = linkedMapOf()
    private val documentUris: MutableMap<String, Uri> = mutableMapOf()
    private val childrenUris: MutableMap<String, Uri> = mutableMapOf()
    private var lastDocumentNumber: Int = 0

    /**
     * What the [cursor] returns. A query is read to its end before the next one is made, so one
     * cursor does for all of them.
     */
    private var cursorRows: List<FakeDocument> = emptyList()
    private var cursorIndex: Int = -1
    private val cursor: Cursor = mockk {
        every { moveToNext() } answers { ++cursorIndex < cursorRows.size }
        every { getString(0) } answers { cursorRows[cursorIndex].id }
        every { getString(1) } answers { cursorRows[cursorIndex].name }
        every { getString(2) } answers { cursorRows[cursorIndex].mimeType }
        every { close() } just runs
    }

    private val fileSystem = SafLocalVaultFileSystem(
        contentResolver = contentResolver,
        treeUri = treeUri,
    )

    @BeforeEach
    fun setup() {
        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.getTreeDocumentId(treeUri) } returns ROOT_ID
        every {
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, any())
        } answers {
            childrenUris.getOrPut(secondArg<String>()) { mockk<Uri>() }
        }
        every {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, any())
        } answers {
            documentUri(documentId = secondArg<String>())
        }
        every { DocumentsContract.getDocumentId(any()) } answers {
            firstArg<Uri>().toDocumentId()
        }
        every {
            DocumentsContract.createDocument(contentResolver, any(), any(), any())
        } answers {
            val document = addDocument(
                parentId = secondArg<Uri>().toDocumentId(),
                name = arg<String>(3),
                isDirectory = thirdArg<String>() == DocumentsContract.Document.MIME_TYPE_DIR,
            )
            documentUri(documentId = document.id)
        }
        every {
            DocumentsContract.renameDocument(contentResolver, any(), any())
        } answers {
            val uri = secondArg<Uri>()
            documents.getValue(uri.toDocumentId()).name = thirdArg<String>()
            uri
        }
        every { DocumentsContract.deleteDocument(contentResolver, any()) } answers {
            removeDocument(documentId = secondArg<Uri>().toDocumentId())
            true
        }
        every { contentResolver.query(any(), any(), any(), any(), any()) } answers {
            val uri = firstArg<Uri>()
            val parentId = childrenUris.entries.first { it.value === uri }.key
            cursorRows = documents.values.filter { it.parentId == parentId }
            cursorIndex = -1
            cursor
        }
        every { contentResolver.openInputStream(any()) } answers {
            ByteArrayInputStream(documents.getValue(firstArg<Uri>().toDocumentId()).content)
        }
        every { contentResolver.openOutputStream(any(), WRITE_MODE) } answers {
            val document = documents.getValue(firstArg<Uri>().toDocumentId())
            object : ByteArrayOutputStream() {
                override fun close() {
                    document.content = toByteArray()
                }
            }
        }
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(DocumentsContract::class)
    }

    @Test
    fun `list should return the names of what is in a directory`() {
        val account = addDirectory(name = ACCOUNT_ID)
        addFile(parentId = account.id, name = VAULT_FILE)
        addDirectory(name = OTHER_ACCOUNT_ID)

        assertEquals(
            listOf(ACCOUNT_ID, OTHER_ACCOUNT_ID),
            fileSystem.list(directory = emptyList()),
        )
        assertEquals(listOf(VAULT_FILE), fileSystem.list(directory = listOf(ACCOUNT_ID)))
    }

    @Test
    fun `list should return nothing for what is not a directory`() {
        val account = addDirectory(name = ACCOUNT_ID)
        addFile(parentId = account.id, name = VAULT_FILE)

        assertEquals(
            emptyList<String>(),
            fileSystem.list(directory = listOf(ACCOUNT_ID, VAULT_FILE)),
        )
        assertEquals(emptyList<String>(), fileSystem.list(directory = listOf(OTHER_ACCOUNT_ID)))
    }

    @Test
    fun `exists should tell whether there is anything at a path`() {
        val account = addDirectory(name = ACCOUNT_ID)
        addFile(parentId = account.id, name = VAULT_FILE)

        assertTrue(fileSystem.exists(path = emptyList()))
        assertTrue(fileSystem.exists(path = listOf(ACCOUNT_ID)))
        assertTrue(fileSystem.exists(path = listOf(ACCOUNT_ID, VAULT_FILE)))
        assertFalse(fileSystem.exists(path = listOf(ACCOUNT_ID, TEMP_FILE)))
        // Nothing is inside of a file.
        assertFalse(fileSystem.exists(path = listOf(ACCOUNT_ID, VAULT_FILE, TEMP_FILE)))
    }

    @Test
    fun `openInput should return the content of a file`() {
        val account = addDirectory(name = ACCOUNT_ID)
        addFile(parentId = account.id, name = VAULT_FILE, content = CONTENT)

        assertArrayEquals(
            CONTENT,
            fileSystem.openInput(path = listOf(ACCOUNT_ID, VAULT_FILE))?.use { it.readBytes() },
        )
    }

    @Test
    fun `openInput should return null for what is not a file`() {
        addDirectory(name = ACCOUNT_ID)

        assertNull(fileSystem.openInput(path = listOf(ACCOUNT_ID)))
        assertNull(fileSystem.openInput(path = listOf(ACCOUNT_ID, VAULT_FILE)))
    }

    @Test
    fun `openOutput should create the file and the directories that lead to it`() {
        fileSystem.openOutput(path = ATTACHMENT_PATH).use { it.write(CONTENT) }

        val attachment = requireNotNull(documentAt(path = ATTACHMENT_PATH))
        assertFalse(attachment.isDirectory)
        assertArrayEquals(CONTENT, attachment.content)
        assertEquals(true, documentAt(path = ATTACHMENT_PATH.dropLast(n = 1))?.isDirectory)
        assertEquals(ATTACHMENT_PATH.size, documents.size)
    }

    @Test
    fun `openOutput should replace the content of a file that exists`() {
        val account = addDirectory(name = ACCOUNT_ID)
        val vault = addFile(parentId = account.id, name = VAULT_FILE, content = OTHER_CONTENT)

        fileSystem.openOutput(path = listOf(ACCOUNT_ID, VAULT_FILE)).use { it.write(CONTENT) }

        assertArrayEquals(CONTENT, vault.content)
        assertEquals(2, documents.size)
        verify(exactly = 0) { DocumentsContract.createDocument(any(), any(), any(), any()) }
    }

    @Test
    fun `openOutput should throw an IOException when the file cannot be created`() {
        every {
            DocumentsContract.createDocument(contentResolver, any(), any(), any())
        } returns null

        assertThrows<IOException> { fileSystem.openOutput(path = listOf(VAULT_FILE)) }
    }

    @Test
    fun `openOutput should throw an IOException when the file cannot be written to`() {
        every { contentResolver.openOutputStream(any(), WRITE_MODE) } returns null

        assertThrows<IOException> { fileSystem.openOutput(path = listOf(VAULT_FILE)) }
    }

    @Test
    fun `openOutput should throw an IOException for a path without a file name`() {
        assertThrows<IOException> { fileSystem.openOutput(path = emptyList()) }
    }

    @Test
    fun `rename should give a file its new name`() {
        val account = addDirectory(name = ACCOUNT_ID)
        val temp = addFile(parentId = account.id, name = TEMP_FILE)

        assertTrue(fileSystem.rename(path = listOf(ACCOUNT_ID, TEMP_FILE), newName = VAULT_FILE))

        assertEquals(VAULT_FILE, temp.name)
    }

    @Test
    fun `rename should not replace a file that has the new name`() {
        val account = addDirectory(name = ACCOUNT_ID)
        val temp = addFile(parentId = account.id, name = TEMP_FILE)
        addFile(parentId = account.id, name = VAULT_FILE)

        assertFalse(fileSystem.rename(path = listOf(ACCOUNT_ID, TEMP_FILE), newName = VAULT_FILE))

        assertEquals(TEMP_FILE, temp.name)
        verify(exactly = 0) { DocumentsContract.renameDocument(any(), any(), any()) }
    }

    @Test
    fun `rename should return false for a file that does not exist`() {
        addDirectory(name = ACCOUNT_ID)

        assertFalse(fileSystem.rename(path = listOf(ACCOUNT_ID, TEMP_FILE), newName = VAULT_FILE))
    }

    @Test
    fun `rename should return false when the folder does not rename the file`() {
        val account = addDirectory(name = ACCOUNT_ID)
        addFile(parentId = account.id, name = TEMP_FILE)
        every { DocumentsContract.renameDocument(contentResolver, any(), any()) } returns null

        assertFalse(fileSystem.rename(path = listOf(ACCOUNT_ID, TEMP_FILE), newName = VAULT_FILE))
    }

    @Test
    fun `delete should remove a directory along with what is in it`() {
        val account = addDirectory(name = ACCOUNT_ID)
        addFile(parentId = account.id, name = VAULT_FILE)

        assertTrue(fileSystem.delete(path = listOf(ACCOUNT_ID)))

        assertEquals(0, documents.size)
        assertFalse(fileSystem.exists(path = listOf(ACCOUNT_ID)))
    }

    @Test
    fun `delete should return true when there is nothing to delete`() {
        assertTrue(fileSystem.delete(path = listOf(ACCOUNT_ID)))

        verify(exactly = 0) { DocumentsContract.deleteDocument(any(), any()) }
    }

    @Test
    fun `failures of the folder should be reported as an IOException`() {
        listOf(
            SecurityException("mockMessage"),
            IllegalArgumentException("mockMessage"),
            IllegalStateException("mockMessage"),
            UnsupportedOperationException("mockMessage"),
        )
            .forEach { exception ->
                every {
                    contentResolver.query(any(), any(), any(), any(), any())
                } throws exception

                val ioException = assertThrows<IOException> {
                    fileSystem.list(directory = emptyList())
                }

                assertEquals(exception, ioException.cause)
            }
    }

    @Test
    fun `openOutput should create a directory that files share only once`() {
        fileSystem.openOutput(path = listOf(ACCOUNT_ID, VAULT_FILE)).use { it.write(CONTENT) }
        fileSystem.openOutput(path = ATTACHMENT_PATH).use { it.write(OTHER_CONTENT) }

        assertArrayEquals(CONTENT, documentAt(path = listOf(ACCOUNT_ID, VAULT_FILE))?.content)
        assertArrayEquals(OTHER_CONTENT, documentAt(path = ATTACHMENT_PATH)?.content)
        // The account directory, the vault file, and the attachment with its two directories.
        assertEquals(ATTACHMENT_PATH.size + 1, documents.size)
    }

    private fun addDirectory(name: String, parentId: String = ROOT_ID): FakeDocument =
        addDocument(parentId = parentId, name = name, isDirectory = true)

    private fun addFile(
        parentId: String,
        name: String,
        content: ByteArray = CONTENT,
    ): FakeDocument =
        addDocument(parentId = parentId, name = name, isDirectory = false)
            .also { it.content = content }

    private fun addDocument(parentId: String, name: String, isDirectory: Boolean): FakeDocument {
        val document = FakeDocument(
            id = "mockDocumentId-${++lastDocumentNumber}",
            parentId = parentId,
            name = name,
            isDirectory = isDirectory,
        )
        documents[document.id] = document
        return document
    }

    private fun removeDocument(documentId: String) {
        documents
            .values
            .filter { it.parentId == documentId }
            .forEach { removeDocument(documentId = it.id) }
        documents.remove(documentId)
    }

    private fun documentAt(path: List<String>): FakeDocument? {
        var parentId = ROOT_ID
        var document: FakeDocument? = null
        for (name in path) {
            document = documents
                .values
                .firstOrNull { it.parentId == parentId && it.name == name }
                ?: return null
            parentId = document.id
        }
        return document
    }

    private fun documentUri(documentId: String): Uri =
        documentUris.getOrPut(documentId) { mockk<Uri>() }

    private fun Uri.toDocumentId(): String =
        documentUris.entries.first { it.value === this }.key
}

/**
 * A file or a directory in the folder.
 */
private class FakeDocument(
    val id: String,
    val parentId: String,
    var name: String,
    val isDirectory: Boolean,
) {
    var content: ByteArray = byteArrayOf()

    val mimeType: String
        get() = if (isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else FILE_MIME_TYPE
}

private const val ROOT_ID: String = "mockRootId"
private const val ACCOUNT_ID: String = "mockAccountId-1"
private const val OTHER_ACCOUNT_ID: String = "mockAccountId-2"
private const val VAULT_FILE: String = "vault.json"
private const val TEMP_FILE: String = "vault.json.tmp"
private const val WRITE_MODE: String = "wt"
private const val FILE_MIME_TYPE: String = "application/octet-stream"

private val ATTACHMENT_PATH: List<String> =
    listOf(ACCOUNT_ID, "attachments", "mockCipherId", "mockAttachmentId")
private val CONTENT: ByteArray = byteArrayOf(1, 2, 3)
private val OTHER_CONTENT: ByteArray = byteArrayOf(4, 5)
