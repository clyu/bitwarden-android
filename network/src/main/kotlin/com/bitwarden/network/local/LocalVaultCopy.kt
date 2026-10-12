package com.bitwarden.network.local

import java.io.IOException

/**
 * What the name of the directory of an account looks like, which is the rule of the local vault
 * for every name that it uses. Anything else in a vault folder is not an account.
 */
private val ACCOUNT_DIRECTORY_REGEX: Regex = Regex("^[A-Za-z0-9_-]{1,128}$")

/**
 * Copies every account that exists in this [LocalVaultFileSystem] but not in the [target]. An
 * account that the [target] already has is left untouched.
 */
@Throws(IOException::class)
fun LocalVaultFileSystem.copyMissingAccountsTo(target: LocalVaultFileSystem) {
    missingAccountsIn(target = target).forEach { copyTree(path = listOf(it), target = target) }
}

/**
 * Returns the accounts that this [LocalVaultFileSystem] has and the [target] does not, sorted.
 *
 * It is `accounts_missing_in` of the local vault, which the tests check it against. It is not
 * called from here, so that moving the vault does not need the library of the local vault, which
 * the unit tests of the app do not load.
 */
@Throws(IOException::class)
internal fun LocalVaultFileSystem.missingAccountsIn(target: LocalVaultFileSystem): List<String> =
    this
        .list(emptyList())
        .filter { ACCOUNT_DIRECTORY_REGEX.matches(it) && !target.exists(path = listOf(it)) }
        .sorted()

private fun LocalVaultFileSystem.copyTree(path: List<String>, target: LocalVaultFileSystem) {
    val input = openInput(path = path)
    if (input == null) {
        list(path).forEach { copyTree(path = path + it, target = target) }
        return
    }
    input.use { source ->
        target.openOutput(path = path).use { source.copyTo(it) }
    }
}
