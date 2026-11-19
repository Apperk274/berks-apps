package dev.personal.berksapps.catalog

import dev.personal.berksapps.core.Hashing
import dev.personal.berksapps.core.Names
import org.json.JSONException
import org.json.JSONObject
import java.net.URI

data class CatalogEntry(
    val name: String,
    val packageName: String,
    val description: String,
    val version: String,
    val versionCode: Long?,
    val apkUrl: String?,
    val apkSha256: String?,
    val signingCertSha256: String,
) {
    val downloadable: Boolean get() = apkUrl != null && apkSha256 != null
}

data class Catalog(val entries: List<CatalogEntry>)

class CatalogFormatException(message: String) : Exception(message)

object CatalogParser {
    const val SCHEMA_VERSION = 1
    private const val MAX_ENTRIES = 200
    private const val MAX_TEXT = 2000

    fun parse(json: String): Catalog {
        try {
            val root = JSONObject(json)
            val schema = root.optInt("schemaVersion", -1)
            if (schema != SCHEMA_VERSION) {
                throw CatalogFormatException("Unsupported catalog schemaVersion: $schema")
            }
            val apps = root.getJSONArray("apps")
            if (apps.length() > MAX_ENTRIES) {
                throw CatalogFormatException("Catalog has too many entries")
            }
            val entries = ArrayList<CatalogEntry>()
            val seen = HashSet<String>()
            for (i in 0 until apps.length()) {
                val entry = parseEntry(apps.getJSONObject(i))
                if (!seen.add(entry.packageName)) {
                    throw CatalogFormatException("Duplicate package: ${entry.packageName}")
                }
                entries.add(entry)
            }
            return Catalog(entries)
        } catch (e: JSONException) {
            throw CatalogFormatException("Catalog is not valid JSON: ${e.message}")
        }
    }

    fun isHttpsUrl(url: String): Boolean = try {
        val uri = URI(url)
        "https".equals(uri.scheme, ignoreCase = true) &&
            !uri.host.isNullOrEmpty() &&
            uri.userInfo == null
    } catch (e: Exception) {
        false
    }

    private fun parseEntry(o: JSONObject): CatalogEntry {
        val pkg = text(o, "package")
        if (!Names.isPackageName(pkg)) {
            throw CatalogFormatException("Invalid package name: $pkg")
        }
        val name = text(o, "name")
        val cert = Hashing.normalizeSha256(text(o, "signingCertSha256"))
            ?: throw CatalogFormatException("$pkg: signingCertSha256 must be 64 hex digits")
        val url = optionalText(o, "apkUrl")
        val rawHash = optionalText(o, "apkSha256")
        val hash = if (rawHash == null) {
            null
        } else {
            Hashing.normalizeSha256(rawHash)
                ?: throw CatalogFormatException("$pkg: apkSha256 must be 64 hex digits")
        }
        if (url != null) {
            if (!isHttpsUrl(url)) {
                throw CatalogFormatException("$pkg: apkUrl must be an https:// URL")
            }
            if (hash == null) {
                throw CatalogFormatException("$pkg: apkUrl needs apkSha256")
            }
        }
        val code = if (o.has("versionCode") && !o.isNull("versionCode")) {
            val value = o.getLong("versionCode")
            if (value < 0) throw CatalogFormatException("$pkg: versionCode must not be negative")
            value
        } else {
            null
        }
        return CatalogEntry(
            name = name,
            packageName = pkg,
            description = optionalText(o, "description") ?: "",
            version = text(o, "version"),
            versionCode = code,
            apkUrl = url,
            apkSha256 = if (url == null) null else hash,
            signingCertSha256 = cert,
        )
    }

    private fun text(o: JSONObject, key: String): String {
        val value = optionalText(o, key)
        if (value.isNullOrEmpty()) throw CatalogFormatException("Missing field: $key")
        return value
    }

    private fun optionalText(o: JSONObject, key: String): String? {
        if (!o.has(key) || o.isNull(key)) return null
        val value = o.get(key) as? String ?: throw CatalogFormatException("Field $key must be text")
        val trimmed = value.trim()
        if (trimmed.length > MAX_TEXT) throw CatalogFormatException("Field $key is too long")
        return trimmed.ifEmpty { null }
    }
}

data class MergedCatalog(val entries: List<CatalogEntry>, val rejected: List<String>)

object CatalogMerge {
    fun merge(bundled: Catalog, remote: Catalog?): MergedCatalog {
        if (remote == null) return MergedCatalog(bundled.entries, emptyList())
        val pins = bundled.entries.associateBy { it.packageName }
        val rejected = ArrayList<String>()
        val accepted = LinkedHashMap<String, CatalogEntry>()
        for (entry in bundled.entries) accepted[entry.packageName] = entry
        for (entry in remote.entries) {
            val pin = pins[entry.packageName]
            if (pin != null && !Hashing.sameDigest(pin.signingCertSha256, entry.signingCertSha256)) {
                rejected.add(entry.packageName)
                continue
            }
            accepted[entry.packageName] = entry
        }
        return MergedCatalog(accepted.values.toList(), rejected)
    }
}
