package dev.personal.berksapps.catalog

import dev.personal.berksapps.core.Hashing

data class ApkFacts(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val fileSha256: String,
    val signerSha256: List<String>,
)

sealed class Verification {
    data object Ok : Verification()
    data class Refused(val reason: String) : Verification()
}

object ApkVerifier {
    fun verify(facts: ApkFacts, entry: CatalogEntry?, downloaded: Boolean): Verification {
        if (facts.signerSha256.isEmpty()) {
            return Verification.Refused("The APK has no readable signing certificate.")
        }
        if (entry == null) {
            return if (downloaded) {
                Verification.Refused("A downloaded APK must belong to a catalog entry.")
            } else {
                Verification.Ok
            }
        }
        if (facts.packageName != entry.packageName) {
            return Verification.Refused(
                "The APK is ${facts.packageName}, but the catalog entry is ${entry.packageName}."
            )
        }
        if (downloaded &&
            (entry.apkSha256 == null || !Hashing.sameDigest(entry.apkSha256, facts.fileSha256))
        ) {
            return Verification.Refused(
                "The downloaded file does not match the SHA-256 in the catalog."
            )
        }
        if (facts.signerSha256.size != 1 ||
            !Hashing.sameDigest(entry.signingCertSha256, facts.signerSha256[0])
        ) {
            return Verification.Refused(
                "The APK is not signed with the certificate the catalog expects for ${entry.name}."
            )
        }
        return Verification.Ok
    }
}
