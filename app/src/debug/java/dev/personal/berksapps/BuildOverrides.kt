package dev.personal.berksapps

import android.content.Context
import dev.personal.berksapps.catalog.DownloadPolicy
import java.io.File
import java.net.URI

object BuildOverrides {
    private const val OVERRIDE_FILE = "catalog-override.txt"

    fun catalogUrl(context: Context): String? = try {
        File(context.filesDir, OVERRIDE_FILE).takeIf { it.isFile }
            ?.readLines()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }

    fun policy(context: Context): DownloadPolicy {
        val override = catalogUrl(context) ?: return DownloadPolicy.RELEASE
        val uri = try {
            URI(override)
        } catch (e: Exception) {
            return DownloadPolicy.RELEASE
        }
        val host = uri.host ?: return DownloadPolicy.RELEASE
        return if (uri.scheme == "http") {
            DownloadPolicy(DownloadPolicy.GITHUB_HOSTS, setOf(host))
        } else {
            DownloadPolicy(DownloadPolicy.GITHUB_HOSTS + host)
        }
    }
}
