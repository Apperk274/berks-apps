package dev.personal.berksapps.install

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import dev.personal.berksapps.catalog.ApkFacts
import dev.personal.berksapps.catalog.ApkVerifier
import dev.personal.berksapps.catalog.CatalogEntry
import dev.personal.berksapps.catalog.HttpsFetcher
import dev.personal.berksapps.catalog.Verification
import dev.personal.berksapps.core.Hashing
import java.io.File
import java.io.IOException

data class StagedApk(
    val facts: ApkFacts,
    val label: String,
    val entry: CatalogEntry?,
    val downloaded: Boolean,
)

sealed class StageResult {
    data class Ready(val staged: StagedApk) : StageResult()
    data class Refused(val reason: String) : StageResult()
}

class ApkStager(context: Context) {
    private val appContext = context.applicationContext
    private val dir = File(appContext.cacheDir, "staged")
    private val incoming = File(dir, "incoming.apk")

    val servedFile: File = File(dir, ApkProvider.FILE_NAME)

    fun fromUri(uri: Uri, catalog: List<CatalogEntry>): StageResult {
        prepare()
        try {
            val input = appContext.contentResolver.openInputStream(uri)
                ?: return StageResult.Refused("That file could not be opened.")
            input.use { source ->
                incoming.outputStream().use { out ->
                    val buffer = ByteArray(1 shl 16)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_APK_BYTES) throw IOException("too large")
                        out.write(buffer, 0, read)
                    }
                }
            }
        } catch (e: Exception) {
            incoming.delete()
            return StageResult.Refused("That file could not be read.")
        }
        return finish(catalog, null)
    }

    fun fromDownload(entry: CatalogEntry): StageResult {
        val url = entry.apkUrl ?: return StageResult.Refused("This app has no download address yet.")
        prepare()
        try {
            HttpsFetcher.download(url, incoming, MAX_APK_BYTES)
        } catch (e: Exception) {
            incoming.delete()
            return StageResult.Refused("The download did not work: ${e.message ?: "network error"}")
        }
        return finish(listOf(entry), entry)
    }

    fun clear() {
        incoming.delete()
        servedFile.delete()
    }

    private fun prepare() {
        dir.mkdirs()
        clear()
    }

    private fun finish(catalog: List<CatalogEntry>, downloadedFor: CatalogEntry?): StageResult {
        val inspected = inspect(incoming)
        if (inspected == null) {
            incoming.delete()
            return StageResult.Refused("That file is not an Android app package (APK).")
        }
        val (facts, label) = inspected
        val entry = downloadedFor ?: catalog.firstOrNull { it.packageName == facts.packageName }
        val verdict = ApkVerifier.verify(facts, entry, downloadedFor != null)
        if (verdict is Verification.Refused) {
            incoming.delete()
            return StageResult.Refused(verdict.reason)
        }
        if (!incoming.renameTo(servedFile)) {
            incoming.delete()
            return StageResult.Refused("The app package could not be prepared.")
        }
        return StageResult.Ready(StagedApk(facts, label, entry, downloadedFor != null))
    }

    private fun inspect(file: File): Pair<ApkFacts, String>? {
        val pm = appContext.packageManager
        val info: PackageInfo = try {
            pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        } catch (e: Exception) {
            null
        } ?: return null
        val signers = signerDigests(info)
        val label = info.applicationInfo?.let { app ->
            app.sourceDir = file.absolutePath
            app.publicSourceDir = file.absolutePath
            try {
                app.loadLabel(pm).toString()
            } catch (e: Exception) {
                null
            }
        } ?: info.packageName
        val facts = ApkFacts(
            packageName = info.packageName,
            versionName = info.versionName,
            versionCode = info.longVersionCode,
            fileSha256 = file.inputStream().use { Hashing.sha256(it) },
            signerSha256 = signers,
        )
        return facts to label
    }

    companion object {
        private const val MAX_APK_BYTES = 512L * 1024 * 1024

        fun signerDigests(info: PackageInfo): List<String> {
            val signing = info.signingInfo ?: return emptyList()
            val certs = if (signing.hasMultipleSigners()) {
                signing.apkContentsSigners
            } else {
                signing.signingCertificateHistory?.takeLast(1)?.toTypedArray()
                    ?: signing.apkContentsSigners
            }
            return certs?.map { Hashing.sha256(it.toByteArray()) } ?: emptyList()
        }
    }
}
