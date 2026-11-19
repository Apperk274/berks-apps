package dev.personal.berksapps.catalog

import android.content.Context
import dev.personal.berksapps.BuildOverrides
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class CatalogSource { Online, Saved, Bundled }

data class LoadedCatalog(
    val entries: List<CatalogEntry>,
    val source: CatalogSource,
    val rejected: List<String>,
)

class CatalogRepository(context: Context) {
    private val appContext = context.applicationContext
    private val cacheFile = File(appContext.filesDir, "catalog-cache.json")

    fun bundled(): Catalog =
        CatalogParser.parse(appContext.assets.open("catalog.json").bufferedReader().use { it.readText() })

    suspend fun load(): LoadedCatalog = withContext(Dispatchers.IO) {
        val bundled = bundled()
        val policy = BuildOverrides.policy(appContext)
        val url = BuildOverrides.catalogUrl(appContext) ?: CATALOG_URL
        val online = try {
            val text = HttpsFetcher.fetchText(url, MAX_CATALOG_BYTES, policy)
            val parsed = CatalogParser.parse(text, policy)
            cacheFile.writeText(text)
            parsed
        } catch (e: Exception) {
            null
        }
        if (online != null) {
            val merged = CatalogMerge.merge(bundled, online)
            return@withContext LoadedCatalog(merged.entries, CatalogSource.Online, merged.rejected)
        }
        val saved = try {
            if (cacheFile.exists()) CatalogParser.parse(cacheFile.readText(), policy) else null
        } catch (e: Exception) {
            null
        }
        val merged = CatalogMerge.merge(bundled, saved)
        LoadedCatalog(
            merged.entries,
            if (saved != null) CatalogSource.Saved else CatalogSource.Bundled,
            merged.rejected,
        )
    }

    companion object {
        const val CATALOG_URL =
            "https://raw.githubusercontent.com/Apperk274/berks-apps/main/app/src/main/assets/catalog.json"
        const val RELEASES_PAGE = "https://github.com/Apperk274/berks-apps/releases"
        private const val MAX_CATALOG_BYTES = 256 * 1024
    }
}
