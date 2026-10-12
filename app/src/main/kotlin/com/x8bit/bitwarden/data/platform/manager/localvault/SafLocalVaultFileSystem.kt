package com.x8bit.bitwarden.data.platform.manager.localvault

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.bitwarden.network.local.LocalVaultFileSystem
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

private const val BINARY_MIME_TYPE: String = "application/octet-stream"
private const val MODE_WRITE_TRUNCATE: String = "wt"

private val DOCUMENT_PROJECTION: Array<String> = arrayOf(
    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
    DocumentsContract.Document.COLUMN_MIME_TYPE,
)

/**
 * A [LocalVaultFileSystem] that keeps the vault in a folder that the user chose with the system
 * folder picker, which is reached through the Storage Access Framework and not as files.
 *
 * @param treeUri The URI that the folder picker returned for the folder.
 */
class SafLocalVaultFileSystem(
    private val contentResolver: ContentResolver,
    private val treeUri: Uri,
) : LocalVaultFileSystem {
    override fun list(directory: List<String>): List<String> =
        withIoExceptions {
            resolve(path = directory)
                ?.takeIf { it.isDirectory }
                ?.let { document -> children(parentId = document.id).map { it.name } }
                .orEmpty()
        }

    override fun exists(path: List<String>): Boolean =
        withIoExceptions { resolve(path = path) != null }

    override fun openInput(path: List<String>): InputStream? =
        withIoExceptions {
            resolve(path = path)
                ?.takeUnless { it.isDirectory }
                ?.let { contentResolver.openInputStream(documentUri(documentId = it.id)) }
        }

    override fun openOutput(path: List<String>): OutputStream =
        withIoExceptions {
            val name = path.lastOrNull() ?: throw IOException("A file name is required")
            val parent = path
                .dropLast(n = 1)
                .fold(initial = root()) { directory, directoryName ->
                    children(parentId = directory.id)
                        .firstOrNull { it.name == directoryName }
                        ?: create(
                            parentId = directory.id,
                            mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                            name = directoryName,
                        )
                }
            val document = children(parentId = parent.id)
                .firstOrNull { it.name == name }
                ?: create(parentId = parent.id, mimeType = BINARY_MIME_TYPE, name = name)
            contentResolver
                .openOutputStream(documentUri(documentId = document.id), MODE_WRITE_TRUNCATE)
                ?: throw IOException("Unable to write to the vault folder")
        }

    override fun rename(path: List<String>, newName: String): Boolean =
        withIoExceptions {
            val siblings = resolve(path = path.dropLast(n = 1))
                ?.let { children(parentId = it.id) }
                .orEmpty()
            val document = siblings.firstOrNull { it.name == path.lastOrNull() }
            // A name that is taken is not replaced: the document would be given another name.
            if (document == null || siblings.any { it.name == newName }) {
                false
            } else {
                DocumentsContract.renameDocument(
                    contentResolver,
                    documentUri(documentId = document.id),
                    newName,
                ) != null
            }
        }

    override fun delete(path: List<String>): Boolean =
        withIoExceptions {
            resolve(path = path)
                ?.let {
                    DocumentsContract.deleteDocument(
                        contentResolver,
                        documentUri(documentId = it.id),
                    )
                }
                ?: true
        }

    private fun root(): Document =
        Document(
            id = DocumentsContract.getTreeDocumentId(treeUri),
            name = "",
            isDirectory = true,
        )

    private fun resolve(path: List<String>): Document? {
        var document = root()
        for (name in path) {
            if (!document.isDirectory) return null
            document = children(parentId = document.id)
                .firstOrNull { it.name == name }
                ?: return null
        }
        return document
    }

    private fun children(parentId: String): List<Document> =
        contentResolver
            .query(
                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId),
                DOCUMENT_PROJECTION,
                null,
                null,
                null,
            )
            ?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            Document(
                                id = cursor.getString(0),
                                name = cursor.getString(1).orEmpty(),
                                isDirectory = cursor.getString(2) ==
                                    DocumentsContract.Document.MIME_TYPE_DIR,
                            ),
                        )
                    }
                }
            }
            .orEmpty()

    private fun create(parentId: String, mimeType: String, name: String): Document {
        val uri = DocumentsContract
            .createDocument(
                contentResolver,
                documentUri(documentId = parentId),
                mimeType,
                name,
            )
            ?: throw IOException("Unable to create a file in the vault folder")
        return Document(
            id = DocumentsContract.getDocumentId(uri),
            name = name,
            isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR,
        )
    }

    private fun documentUri(documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    /**
     * An entry of the folder.
     */
    private data class Document(
        val id: String,
        val name: String,
        val isDirectory: Boolean,
    )
}

/**
 * Runs the given [block] and reports what a documents provider can fail with as an
 * [IOException], which is what a caller of a [LocalVaultFileSystem] expects: a folder that is
 * gone or that the app is not allowed in anymore is no different from one that cannot be read.
 */
@Throws(IOException::class)
private inline fun <T> withIoExceptions(block: () -> T): T =
    try {
        block()
    } catch (e: SecurityException) {
        throw IOException(e)
    } catch (e: IllegalArgumentException) {
        throw IOException(e)
    } catch (e: IllegalStateException) {
        throw IOException(e)
    } catch (e: UnsupportedOperationException) {
        throw IOException(e)
    }
