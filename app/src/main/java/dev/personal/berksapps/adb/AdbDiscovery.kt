package dev.personal.berksapps.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

class AdbDiscovery(context: Context, private val serviceType: String) {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
    private val found = ConcurrentHashMap<String, Int>()
    private val state = MutableStateFlow<List<Int>>(emptyList())
    private var listener: NsdManager.DiscoveryListener? = null

    val ports: StateFlow<List<Int>> get() = state

    @Synchronized
    fun start() {
        if (listener != null) return
        val created = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStopDiscoveryFailed(type: String, errorCode: Int) = Unit

            override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
                synchronized(this@AdbDiscovery) { listener = null }
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                resolve(info, 0)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                found.remove(info.serviceName)
                publish()
            }
        }
        try {
            nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, created)
            listener = created
        } catch (e: RuntimeException) {
            listener = null
        }
    }

    @Synchronized
    fun stop() {
        val current = listener ?: return
        listener = null
        try {
            nsd.stopServiceDiscovery(current)
        } catch (e: RuntimeException) {
            return
        } finally {
            found.clear()
            publish()
        }
    }

    suspend fun awaitPorts(timeoutMs: Long): List<Int> {
        start()
        try {
            withTimeoutOrNull(timeoutMs) { state.first { it.isNotEmpty() } } ?: return emptyList()
            delay(SETTLE_MS)
            return state.value
        } finally {
            stop()
        }
    }

    @Suppress("DEPRECATION")
    private fun resolve(info: NsdServiceInfo, attempt: Int) {
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) {
                    if (attempt < MAX_RESOLVE_ATTEMPTS && listener != null) {
                        Thread {
                            Thread.sleep(RESOLVE_RETRY_MS)
                            resolve(info, attempt + 1)
                        }.start()
                    }
                }

                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    val host = resolved.host?.hostAddress ?: return
                    if (listener != null && resolved.port > 0 && isLocalAddress(host)) {
                        found[resolved.serviceName] = resolved.port
                        publish()
                    }
                }
            })
        } catch (e: RuntimeException) {
            return
        }
    }

    private fun publish() {
        state.value = found.values.distinct()
    }

    private fun isLocalAddress(host: String): Boolean = try {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .flatMap { it.inetAddresses.asSequence() }
            .any { it.hostAddress?.substringBefore('%') == host.substringBefore('%') }
    } catch (e: Exception) {
        false
    }

    companion object {
        const val TYPE_PAIRING = "_adb-tls-pairing._tcp"
        const val TYPE_CONNECT = "_adb-tls-connect._tcp"
        private const val SETTLE_MS = 400L
        private const val RESOLVE_RETRY_MS = 300L
        private const val MAX_RESOLVE_ATTEMPTS = 3
    }
}
