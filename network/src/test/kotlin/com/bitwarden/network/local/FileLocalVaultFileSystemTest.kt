package com.bitwarden.network.local

import com.bitwarden.localvault.checkFileSystem
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class FileLocalVaultFileSystemTest {
    private val root: File = Files.createTempDirectory("local-vault").toFile()

    @AfterEach
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `the file system should keep every rule that the local vault counts on`() {
        val fileSystem = FileSystemCallback(fileSystem = FileLocalVaultFileSystem(root = root))

        assertEquals(
            emptyList<String>(),
            checkFileSystem(fileSystem = fileSystem, scratch = listOf("mockScratch")),
        )
    }
}
