package dev.personal.berksapps

import dev.personal.berksapps.catalog.ApkFacts
import dev.personal.berksapps.catalog.ApkVerifier
import dev.personal.berksapps.catalog.Catalog
import dev.personal.berksapps.catalog.CatalogEntry
import dev.personal.berksapps.catalog.CatalogFormatException
import dev.personal.berksapps.catalog.CatalogMerge
import dev.personal.berksapps.catalog.CatalogParser
import dev.personal.berksapps.catalog.Verification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CatalogTest {
    private val certA = "89".repeat(32)
    private val certB = "ab".repeat(32)
    private val hash = "cd".repeat(32)

    private fun json(app: String) = """{"schemaVersion":1,"apps":[$app]}"""

    private fun app(
        pkg: String = "dev.personal.autocaster",
        extra: String = "",
        cert: String = certA,
    ) = """{"name":"Auto Caster","package":"$pkg","description":"d","version":"0.1.0",
        "signingCertSha256":"$cert"$extra}"""

    private fun entry(cert: String = certA, url: Boolean = false) = CatalogEntry(
        name = "Auto Caster",
        packageName = "dev.personal.autocaster",
        description = "",
        version = "0.1.0",
        versionCode = 1,
        apkUrl = if (url) "https://example.com/a.apk" else null,
        apkSha256 = if (url) hash else null,
        signingCertSha256 = cert,
    )

    private fun facts(
        pkg: String = "dev.personal.autocaster",
        fileHash: String = hash,
        signers: List<String> = listOf(certA),
    ) = ApkFacts(pkg, "0.1.0", 1, fileHash, signers)

    @Test
    fun parsesFileOnlyEntry() {
        val catalog = CatalogParser.parse(json(app(extra = ""","versionCode":7""")))
        val e = catalog.entries.single()
        assertEquals("dev.personal.autocaster", e.packageName)
        assertEquals(7L, e.versionCode)
        assertNull(e.apkUrl)
        assertFalse(e.downloadable)
        assertEquals(certA, e.signingCertSha256)
    }

    @Test
    fun parsesDownloadableEntryAndNormalizesDigests() {
        val colonCert = certA.chunked(2).joinToString(":").uppercase()
        val catalog = CatalogParser.parse(
            json(app(cert = colonCert, extra = ""","apkUrl":"https://example.com/a.apk","apkSha256":"${hash.uppercase()}""""))
        )
        val e = catalog.entries.single()
        assertTrue(e.downloadable)
        assertEquals(certA, e.signingCertSha256)
        assertEquals(hash, e.apkSha256)
    }

    @Test
    fun bundledCatalogParses() {
        val file = listOf("src/main/assets/catalog.json", "app/src/main/assets/catalog.json")
            .map { File(it) }.first { it.exists() }
        val catalog = CatalogParser.parse(file.readText())
        assertTrue(catalog.entries.any { it.packageName == "dev.personal.autocaster" })
    }

    @Test
    fun rejectsHttpUrl() {
        assertThrows(CatalogFormatException::class.java) {
            CatalogParser.parse(json(app(extra = ""","apkUrl":"http://example.com/a.apk","apkSha256":"$hash"""")))
        }
    }

    @Test
    fun rejectsUrlWithoutHash() {
        assertThrows(CatalogFormatException::class.java) {
            CatalogParser.parse(json(app(extra = ""","apkUrl":"https://example.com/a.apk"""")))
        }
    }

    @Test
    fun rejectsBadPackageBadCertWrongSchemaAndDuplicates() {
        assertThrows(CatalogFormatException::class.java) {
            CatalogParser.parse(json(app(pkg = "bad pkg; rm -rf /")))
        }
        assertThrows(CatalogFormatException::class.java) {
            CatalogParser.parse(json(app(cert = "1234")))
        }
        assertThrows(CatalogFormatException::class.java) {
            CatalogParser.parse("""{"schemaVersion":2,"apps":[]}""")
        }
        assertThrows(CatalogFormatException::class.java) {
            CatalogParser.parse(json(app() + "," + app()))
        }
        assertThrows(CatalogFormatException::class.java) {
            CatalogParser.parse("not json")
        }
    }

    @Test
    fun httpsCheck() {
        assertTrue(CatalogParser.isHttpsUrl("https://example.com/x"))
        assertFalse(CatalogParser.isHttpsUrl("http://example.com/x"))
        assertFalse(CatalogParser.isHttpsUrl("file:///sdcard/x.apk"))
        assertFalse(CatalogParser.isHttpsUrl("https://user:pw@example.com/x"))
        assertFalse(CatalogParser.isHttpsUrl("https:///nohost"))
    }

    @Test
    fun mergeKeepsBundledPinAgainstRemoteCertChange() {
        val bundled = Catalog(listOf(entry()))
        val other = entry().copy(packageName = "dev.personal.other", name = "Other", signingCertSha256 = certB)
        val remote = Catalog(listOf(entry(cert = certB, url = true), other))
        val merged = CatalogMerge.merge(bundled, remote)
        assertEquals(listOf("dev.personal.autocaster"), merged.rejected)
        assertEquals(certA, merged.entries.first { it.packageName == "dev.personal.autocaster" }.signingCertSha256)
        assertTrue(merged.entries.any { it.packageName == "dev.personal.other" })
    }

    @Test
    fun mergeTakesRemoteEntryWithSameCert() {
        val merged = CatalogMerge.merge(Catalog(listOf(entry())), Catalog(listOf(entry(url = true))))
        assertTrue(merged.rejected.isEmpty())
        assertTrue(merged.entries.single().downloadable)
        assertEquals(1, CatalogMerge.merge(Catalog(listOf(entry())), null).entries.size)
    }

    @Test
    fun verifyAcceptsMatchingFileInstall() {
        assertEquals(Verification.Ok, ApkVerifier.verify(facts(fileHash = "00".repeat(32)), entry(), false))
    }

    @Test
    fun verifyAcceptsUnknownPackageFromFileOnly() {
        assertEquals(Verification.Ok, ApkVerifier.verify(facts(pkg = "com.example.x"), null, false))
        assertTrue(ApkVerifier.verify(facts(pkg = "com.example.x"), null, true) is Verification.Refused)
    }

    @Test
    fun verifyRefusesWrongCert() {
        assertTrue(ApkVerifier.verify(facts(signers = listOf(certB)), entry(), false) is Verification.Refused)
        assertTrue(ApkVerifier.verify(facts(signers = listOf(certA, certB)), entry(), false) is Verification.Refused)
        assertTrue(ApkVerifier.verify(facts(signers = emptyList()), null, false) is Verification.Refused)
    }

    @Test
    fun verifyRefusesWrongHashOnDownload() {
        assertEquals(Verification.Ok, ApkVerifier.verify(facts(), entry(url = true), true))
        assertTrue(
            ApkVerifier.verify(facts(fileHash = "00".repeat(32)), entry(url = true), true) is Verification.Refused
        )
        assertTrue(ApkVerifier.verify(facts(), entry(url = false), true) is Verification.Refused)
    }

    @Test
    fun verifyRefusesWrongPackage() {
        assertTrue(ApkVerifier.verify(facts(pkg = "com.evil.app"), entry(), false) is Verification.Refused)
    }
}
