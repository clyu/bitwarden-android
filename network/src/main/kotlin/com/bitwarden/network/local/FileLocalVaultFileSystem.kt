package com.bitwarden.network.local

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A [LocalVaultFileSystem] that keeps the vault in the given [root] directory.
 */
class FileLocalVaultFileSystem(
    private val root: File,
) : LocalVaultFileSystem {
    override fun list(directory: List<String>): List<String> =
        resolve(path = directory).list()?.toList().orEmpty()

    override fun exists(path: List<String>): Boolean = resolve(path = path).exists()

    override fun openInput(path: List<String>): InputStream? =
        resolve(path = path).takeIf { it.isFile }?.inputStream()

    override fun openOutput(path: List<String>): OutputStream {
        val file = resolve(path = path)
        file.parentFile?.mkdirs()
        return SyncedFileOutputStream(file = file)
    }

    override fun rename(path: List<String>, newName: String): Boolean {
        val file = resolve(path = path)
        return file.renameTo(File(file.parentFile, newName.toFileName()))
    }

    override fun delete(path: List<String>): Boolean {
        val file = resolve(path = path)
        return !file.exists() || file.deleteRecursively()
    }

    private fun resolve(path: List<String>): File =
        path.fold(initial = root) { file, name -> File(file, name.toFileName()) }
}

/**
 * A [FileOutputStream] that has what was written to it on storage by the time it is closed, so
 * that a file that replaces another one is complete even if the device loses power right after.
 */
private class SyncedFileOutputStream(file: File) : FileOutputStream(file) {
    override fun close() {
        try {
            fd.sync()
        } catch (_: IOException) {
            // The stream was closed before, or the storage does not support it.
        }
        super.close()
    }
}

/**
 * Returns this name if it names an entry of the directory it is used in, and not the directory
 * itself or anything outside of it.
 */
@Throws(IOException::class)
private fun String.toFileName(): String =
    takeUnless { it.isEmpty() || it == "." || it == ".." || it.contains('/') || it.contains('\\') }
        ?: throw IOException("Invalid file name")
