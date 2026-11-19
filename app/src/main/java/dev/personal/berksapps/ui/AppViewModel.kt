package dev.personal.berksapps.ui

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.personal.berksapps.BerksApp
import dev.personal.berksapps.adb.AdbDiscovery
import dev.personal.berksapps.adb.PairResult
import dev.personal.berksapps.adb.PairingProgress
import dev.personal.berksapps.adb.PairingService
import dev.personal.berksapps.catalog.CatalogEntry
import dev.personal.berksapps.catalog.CatalogSource
import dev.personal.berksapps.catalog.LoadedCatalog
import dev.personal.berksapps.install.InstalledState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SetupSnapshot(
    val developerOptions: Boolean = false,
    val wirelessDebugging: Boolean? = null,
    val wifi: Boolean = false,
    val notificationsAllowed: Boolean = true,
    val localNetworkAllowed: Boolean = true,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as BerksApp
    val adb = app.adb
    val installer = app.installer

    private val catalogState = MutableStateFlow(
        LoadedCatalog(app.catalog.bundled().entries, CatalogSource.Bundled, emptyList())
    )
    private val installedState = MutableStateFlow<Map<String, InstalledState?>>(emptyMap())
    private val setupState = MutableStateFlow(SetupSnapshot())
    private val pairingMessageState = MutableStateFlow<String?>(null)
    private val catalogLoadingState = MutableStateFlow(false)

    val catalog: StateFlow<LoadedCatalog> get() = catalogState
    val installed: StateFlow<Map<String, InstalledState?>> get() = installedState
    val setup: StateFlow<SetupSnapshot> get() = setupState
    val pairingMessage: StateFlow<String?> get() = pairingMessageState
    val catalogLoading: StateFlow<Boolean> get() = catalogLoadingState

    init {
        refreshCatalog()
        refreshDeviceState()
    }

    fun refreshCatalog() {
        viewModelScope.launch {
            catalogLoadingState.value = true
            catalogState.value = app.catalog.load()
            catalogLoadingState.value = false
            refreshInstalled()
        }
    }

    fun refreshDeviceState() {
        setupState.value = readSetup()
        refreshInstalled()
    }

    fun onResume(activity: Activity) {
        refreshDeviceState()
        viewModelScope.launch {
            installer.onResume(activity)
            refreshInstalled()
            if (adb.status.value.paired && !adb.isConnected() &&
                setupState.value.wirelessDebugging != false
            ) {
                adb.connect()
            }
        }
    }

    fun connect(manualPort: Int? = null) {
        viewModelScope.launch { adb.connect(manualPort) }
    }

    fun startPairing() {
        pairingMessageState.value = null
        PairingService.reset()
        PairingService.start(app)
    }

    fun pairManually(code: String, typedPort: Int?) {
        viewModelScope.launch {
            pairingMessageState.value = "Pairing…"
            val port = typedPort
                ?: (PairingService.state.value as? PairingProgress.Found)?.port
                ?: AdbDiscovery(app, AdbDiscovery.TYPE_PAIRING).awaitPorts(3000).lastOrNull()
            if (port == null) {
                pairingMessageState.value =
                    "The pairing dialog was not found. Type the port shown in the dialog as well."
                return@launch
            }
            when (val result = adb.pair(code.trim(), port)) {
                is PairResult.Paired -> {
                    pairingMessageState.value = "Paired."
                    PairingService.stop(app)
                    adb.connect()
                }
                is PairResult.Failed -> pairingMessageState.value = result.reason
            }
        }
    }

    fun stageFile(uri: Uri) {
        viewModelScope.launch { installer.stageFromUri(uri, catalogState.value.entries) }
    }

    fun download(entry: CatalogEntry) {
        viewModelScope.launch { installer.stageFromDownload(entry) }
    }

    fun install(activity: Activity) {
        viewModelScope.launch {
            installer.install(activity)
            refreshInstalled()
        }
    }

    fun checkAgain() {
        viewModelScope.launch {
            installer.checkAgain()
            refreshInstalled()
        }
    }

    private fun refreshInstalled() {
        viewModelScope.launch {
            val packages = catalogState.value.entries.map { it.packageName }
            installedState.value = withContext(Dispatchers.Default) {
                packages.associateWith { installer.installedState(it) }
            }
        }
    }

    private fun readSetup(): SetupSnapshot {
        val resolver = app.contentResolver
        val developer = try {
            Settings.Global.getInt(resolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        } catch (e: Exception) {
            false
        }
        val wireless: Boolean? = try {
            Settings.Global.getInt(resolver, "adb_wifi_enabled", 0) == 1
        } catch (e: Exception) {
            null
        }
        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifi = try {
            @Suppress("DEPRECATION")
            connectivity.allNetworks.any { network ->
                connectivity.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
        } catch (e: Exception) {
            false
        }
        return SetupSnapshot(
            developerOptions = developer,
            wirelessDebugging = wireless,
            wifi = wifi,
            notificationsAllowed = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS),
            localNetworkAllowed = Build.VERSION.SDK_INT < LOCAL_NETWORK_SDK || granted(LOCAL_NETWORK_PERMISSION),
        )
    }

    private fun granted(permission: String): Boolean =
        app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val LOCAL_NETWORK_SDK = 37
        const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
