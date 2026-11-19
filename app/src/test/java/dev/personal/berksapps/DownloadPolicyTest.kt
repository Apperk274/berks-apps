package dev.personal.berksapps

import dev.personal.berksapps.catalog.CatalogFormatException
import dev.personal.berksapps.catalog.CatalogParser
import dev.personal.berksapps.catalog.DownloadPolicy
import dev.personal.berksapps.core.Versions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class DownloadPolicyTest {
    private val policy = DownloadPolicy.RELEASE
    private val release = "https://github.com/Apperk274/berks-apps/releases/download/autocaster-v0.1.0/autocaster-0.1.0.apk"

    @Test
    fun allowsOnlyTheGitHubHostsOverHttps() {
        assertTrue(policy.allows(release))
        assertTrue(policy.allows("https://raw.githubusercontent.com/Apperk274/berks-apps/main/app/src/main/assets/catalog.json"))
        assertTrue(policy.allows("https://objects.githubusercontent.com/github-production-release-asset/1?X-Amz=1"))
        assertTrue(policy.allows("https://release-assets.githubusercontent.com/x/y?sig=1"))
        assertTrue(policy.allows("https://GitHub.com/a"))
        assertTrue(policy.allows("https://github.com:443/a"))
    }

    @Test
    fun refusesEverythingElse() {
        assertFalse(policy.allows("http://github.com/a"))
        assertFalse(policy.allows("https://example.com/a.apk"))
        assertFalse(policy.allows("https://github.com.evil.example/a"))
        assertFalse(policy.allows("https://evilgithub.com/a"))
        assertFalse(policy.allows("https://user:pw@github.com/a"))
        assertFalse(policy.allows("https://github.com@evil.example/a"))
        assertFalse(policy.allows("https://github.com:8443/a"))
        assertFalse(policy.allows("file:///sdcard/a.apk"))
        assertFalse(policy.allows("content://x/y"))
        assertFalse(policy.allows("https:///nohost"))
        assertFalse(policy.allows("not a url"))
        assertFalse(policy.allows(""))
    }

    @Test
    fun cleartextOnlyForExplicitlyListedHosts() {
        val debug = DownloadPolicy(DownloadPolicy.GITHUB_HOSTS, setOf("10.0.2.2"))
        assertTrue(debug.allows("http://10.0.2.2:8000/catalog.json"))
        assertFalse(debug.allows("http://10.0.2.3:8000/catalog.json"))
        assertFalse(debug.allows("http://github.com/a"))
        assertFalse(policy.allows("http://10.0.2.2:8000/catalog.json"))
    }

    @Test
    fun followsGitHubReleaseRedirect() {
        val target = "https://release-assets.githubusercontent.com/github-production-release-asset/1/2?sp=r&sig=abc"
        assertEquals(target, policy.resolveRedirect(release, target))
        val observed = "https://release-assets.githubusercontent.com/github-production-release-asset/" +
            "212613049/82d5bc71-7921-4a42-bce0-29b120158ce5?sp=r&sv=2018-11-09&sr=b&spr=https" +
            "&se=2026-11-20T01%3A00%3A00Z&rscd=attachment%3B+filename%3Dautocaster-0.1.0.apk" +
            "&rsct=application%2Foctet-stream&skoid=a-b&sktid=c-d&skt=2026-11-20T00%3A00%3A00Z" +
            "&ske=2026-11-20T01%3A00%3A00Z&sks=b&skv=2018-11-09&sig=ab%2Bcd%2Fef%3D&jwt=aa.bb.cc_dd-ee" +
            "&response-content-disposition=attachment%3B%20filename%3Dautocaster-0.1.0.apk" +
            "&response-content-type=application%2Foctet-stream"
        assertEquals(observed, policy.resolveRedirect(release, observed))
        assertEquals(
            "https://github.com/login",
            policy.resolveRedirect(release, "/login"),
        )
    }

    @Test
    fun acceptsAwkwardCharactersInAnAllowedRedirect() {
        val awkward = "https://release-assets.githubusercontent.com/a b/c?filename=x|y{z}[1]^`\"q\"&t=<1>&u=%zz"
        assertEquals(awkward, policy.resolveRedirect(release, awkward))
        assertEquals(
            "https://github.com/Apperk274/berks-apps/releases/download/autocaster-v0.1.0/other file.apk?x=|",
            policy.resolveRedirect(release, "other file.apk?x=|"),
        )
    }

    @Test
    fun refusesTrickyHostSpellings() {
        assertFalse(policy.allows("https://github.com\\@evil.example/a"))
        assertFalse(policy.allows("https://evil.example\\.github.com/a"))
        assertFalse(policy.allows("https://github.com%2eevil.example/a"))
        assertFalse(policy.allows("https://evil.example#@github.com/a"))
        assertFalse(policy.allows("https://github.com\n.evil.example/a"))
        assertThrows(IOException::class.java) {
            policy.resolveRedirect(release, "https://evil.example/?next=https://github.com/")
        }
        assertThrows(IOException::class.java) { policy.resolveRedirect(release, "ftp://github.com/a") }
    }

    @Test
    fun refusesRedirectsThatLeaveTheAllowList() {
        assertThrows(IOException::class.java) { policy.resolveRedirect(release, "https://evil.example/a.apk") }
        assertThrows(IOException::class.java) { policy.resolveRedirect(release, "http://github.com/a.apk") }
        assertThrows(IOException::class.java) { policy.resolveRedirect(release, "//evil.example/a.apk") }
        assertThrows(IOException::class.java) { policy.resolveRedirect(release, null) }
        assertThrows(IOException::class.java) { policy.resolveRedirect(release, " ") }
    }

    @Test
    fun catalogRefusesDownloadsFromOtherHosts() {
        val cert = "97".repeat(32)
        fun catalog(url: String) = """{"schemaVersion":1,"apps":[{"name":"A","package":"dev.personal.a",
            "version":"1.0","versionCode":1,"apkUrl":"$url","apkSha256":"$cert","signingCertSha256":"$cert"}]}"""
        assertTrue(CatalogParser.parse(catalog(release)).entries.single().downloadable)
        assertThrows(CatalogFormatException::class.java) {
            CatalogParser.parse(catalog("https://example.com/a.apk"))
        }
        assertThrows(CatalogFormatException::class.java) {
            CatalogParser.parse(catalog("http://10.0.2.2:8000/a.apk"))
        }
        val debug = DownloadPolicy(DownloadPolicy.GITHUB_HOSTS, setOf("10.0.2.2"))
        assertTrue(CatalogParser.parse(catalog("http://10.0.2.2:8000/a.apk"), debug).entries.single().downloadable)
    }

    @Test
    fun catalogVersionDecidesUpdate() {
        assertTrue(Versions.updateAvailable(1, "0.1.0", 2, "0.1.1"))
        assertFalse(Versions.updateAvailable(2, "0.1.1", 2, "0.1.1"))
        assertFalse(Versions.updateAvailable(3, "0.2.0", 2, "0.1.1"))
        assertFalse(Versions.updateAvailable(null, null, 2, "0.1.1"))
        assertTrue(Versions.updateAvailable(null, "0.1.0", null, "0.1.1"))
        assertFalse(Versions.updateAvailable(null, "0.1.10", null, "0.1.9"))
    }
}
