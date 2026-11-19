package dev.personal.berksapps

import android.app.Application
import dev.personal.berksapps.adb.AdbRepository
import dev.personal.berksapps.catalog.CatalogRepository
import dev.personal.berksapps.install.PlayInstaller

class BerksApp : Application() {
    val adb: AdbRepository by lazy { AdbRepository(this) }
    val catalog: CatalogRepository by lazy { CatalogRepository(this) }
    val installer: PlayInstaller by lazy { PlayInstaller(this, adb) }
}
