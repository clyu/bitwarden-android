package com.x8bit.bitwarden.ui.platform.feature.settings.accountsecurity.kdfsettings.model

/**
 * The algorithms that the master key can be derived with.
 *
 * @property label The name that the algorithm is shown by, which is not translated.
 */
enum class KdfAlgorithm(val label: String) {
    PBKDF2_SHA256(label = "PBKDF2 SHA-256"),
    ARGON2ID(label = "Argon2id"),
}
