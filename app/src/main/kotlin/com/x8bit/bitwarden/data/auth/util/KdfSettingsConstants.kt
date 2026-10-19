package com.x8bit.bitwarden.data.auth.util

import com.bitwarden.crypto.Kdf

private const val MIN_PBKDF2_ITERATIONS: Int = 600_000
private const val MAX_PBKDF2_ITERATIONS: Int = 2_000_000
private const val MIN_ARGON2_ITERATIONS: Int = 2
private const val MAX_ARGON2_ITERATIONS: Int = 10
private const val MIN_ARGON2_MEMORY: Int = 16
private const val MAX_ARGON2_MEMORY: Int = 1024
private const val MIN_ARGON2_PARALLELISM: Int = 1
private const val MAX_ARGON2_PARALLELISM: Int = 16

/**
 * What the settings that the master key is derived with may be changed to, and what they are set
 * to when an algorithm is chosen. They are those of the browser extension (`PBKDF2KdfConfig` and
 * `Argon2KdfConfig` of the clients), so that both offer the same settings for a vault folder that
 * they share.
 */
object KdfSettingsConstants {
    /**
     * The numbers of iterations that [Kdf.Pbkdf2] may be set to.
     */
    val PBKDF2_ITERATIONS: IntRange = MIN_PBKDF2_ITERATIONS..MAX_PBKDF2_ITERATIONS

    /**
     * The number of iterations of [Kdf.Pbkdf2] when it is chosen.
     */
    const val DEFAULT_PBKDF2_ITERATIONS: Int = 600_000

    /**
     * The numbers of iterations that [Kdf.Argon2id] may be set to.
     */
    val ARGON2_ITERATIONS: IntRange = MIN_ARGON2_ITERATIONS..MAX_ARGON2_ITERATIONS

    /**
     * The number of iterations of [Kdf.Argon2id] when it is chosen.
     */
    const val DEFAULT_ARGON2_ITERATIONS: Int = 6

    /**
     * The amounts of memory, in MB, that [Kdf.Argon2id] may be set to use.
     */
    val ARGON2_MEMORY: IntRange = MIN_ARGON2_MEMORY..MAX_ARGON2_MEMORY

    /**
     * The amount of memory, in MB, that [Kdf.Argon2id] uses when it is chosen.
     */
    const val DEFAULT_ARGON2_MEMORY: Int = 32

    /**
     * The numbers of threads that [Kdf.Argon2id] may be set to use.
     */
    val ARGON2_PARALLELISM: IntRange = MIN_ARGON2_PARALLELISM..MAX_ARGON2_PARALLELISM

    /**
     * The number of threads that [Kdf.Argon2id] uses when it is chosen.
     */
    const val DEFAULT_ARGON2_PARALLELISM: Int = 4
}

/**
 * Returns `true` if every setting of this [Kdf] is one that it may be changed to
 * ([KdfSettingsConstants]).
 */
fun Kdf.isAllowedSetting(): Boolean =
    when (this) {
        is Kdf.Pbkdf2 -> iterations.toInt() in KdfSettingsConstants.PBKDF2_ITERATIONS
        is Kdf.Argon2id -> {
            iterations.toInt() in KdfSettingsConstants.ARGON2_ITERATIONS &&
                memory.toInt() in KdfSettingsConstants.ARGON2_MEMORY &&
                parallelism.toInt() in KdfSettingsConstants.ARGON2_PARALLELISM
        }
    }
