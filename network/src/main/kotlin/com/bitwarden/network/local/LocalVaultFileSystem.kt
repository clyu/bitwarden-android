package com.bitwarden.network.local

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A minimal hierarchical file store that holds the local vault.
 *
 * Every path is a list of name segments relative to the root of the vault, so an empty list
 * refers to the root itself.
 */
interface LocalVaultFileSystem {
    /**
     * Returns the names of the entries directly inside the [directory], or an empty list if the
     * [directory] does not exist.
     */
    @Throws(IOException::class)
    fun list(directory: List<String>): List<String>

    /**
     * Returns `true` if a file or a directory exists at the given [path].
     */
    @Throws(IOException::class)
    fun exists(path: List<String>): Boolean

    /**
     * Opens the file at the given [path] for reading, or returns `null` if there is no file at
     * that [path].
     */
    @Throws(IOException::class)
    fun openInput(path: List<String>): InputStream?

    /**
     * Opens the file at the given [path] for writing. Any existing content is replaced and any
     * missing parent directories are created.
     */
    @Throws(IOException::class)
    fun openOutput(path: List<String>): OutputStream

    /**
     * Renames the entry at the given [path] to [newName], keeping it in the same directory.
     * Returns `true` if the entry was renamed.
     */
    @Throws(IOException::class)
    fun rename(path: List<String>, newName: String): Boolean

    /**
     * Deletes the entry at the given [path], including everything inside it if it is a
     * directory. Returns `true` if nothing remains at that [path].
     */
    @Throws(IOException::class)
    fun delete(path: List<String>): Boolean
}
