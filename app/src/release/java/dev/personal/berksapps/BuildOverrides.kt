package dev.personal.berksapps

import android.content.Context
import dev.personal.berksapps.catalog.DownloadPolicy

object BuildOverrides {
    @Suppress("UNUSED_PARAMETER")
    fun catalogUrl(context: Context): String? = null

    @Suppress("UNUSED_PARAMETER")
    fun policy(context: Context): DownloadPolicy = DownloadPolicy.RELEASE
}
