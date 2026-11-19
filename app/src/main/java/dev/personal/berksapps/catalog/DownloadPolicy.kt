package dev.personal.berksapps.catalog

import java.io.IOException
import java.net.URL

class DownloadPolicy(httpsHosts: Set<String>, cleartextHosts: Set<String> = emptySet()) {
    private val secure = httpsHosts.map { it.lowercase() }.toSet()
    private val cleartext = cleartextHosts.map { it.lowercase() }.toSet()

    fun allows(url: String): Boolean {
        if (url.any { it == '\\' || it.code < 0x20 || it.code == 0x7f }) return false
        val parsed = try {
            URL(url)
        } catch (e: Exception) {
            return false
        }
        val host = parsed.host?.lowercase().orEmpty()
        if (host.isEmpty() || !HOST.matches(host)) return false
        if (parsed.userInfo != null) return false
        return when (parsed.protocol?.lowercase()) {
            "https" -> host in secure && (parsed.port == -1 || parsed.port == 443)
            "http" -> host in cleartext
            else -> false
        }
    }

    fun resolveRedirect(current: String, location: String?): String {
        if (location.isNullOrBlank()) throw IOException("Redirect without a target")
        val target = try {
            URL(URL(current), location.trim()).toString()
        } catch (e: Exception) {
            throw IOException("Redirect to an unreadable address")
        }
        if (!allows(target)) throw IOException("Redirect to a host that is not allowed")
        return target
    }

    companion object {
        private val HOST = Regex("^[a-z0-9.-]+$")
        val GITHUB_HOSTS = setOf(
            "github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
            "raw.githubusercontent.com",
        )
        val RELEASE = DownloadPolicy(GITHUB_HOSTS)
    }
}
