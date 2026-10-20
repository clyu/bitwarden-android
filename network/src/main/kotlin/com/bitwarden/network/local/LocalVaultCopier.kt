package com.bitwarden.network.local

import java.io.IOException

/**
 * Copies the vault to another vault folder, for the vault to be kept there from then on.
 */
interface LocalVaultCopier {
    /**
     * Copies to the [target] what it does not have yet of the vault in the [source], for the
     * installation of the app with the [deviceId]: every account that the [target] does not
     * have, with everything in it, and of every account that it has, the files that the
     * installation wrote in it that the [target] has otherwise or not at all, and the content of
     * attachments that the [target] does not have. Nothing else of an account that the [target]
     * has is copied: no other device writes the files of the installation, and what the other
     * devices wrote is in the [target], or on its way there.
     */
    @Throws(IOException::class)
    fun copy(source: LocalVaultFileSystem, target: LocalVaultFileSystem, deviceId: String)
}
