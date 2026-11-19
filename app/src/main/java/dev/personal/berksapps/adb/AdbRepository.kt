package dev.personal.berksapps.adb

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbAuthenticationFailedException
import io.github.muntashirakon.adb.AdbPairingRequiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.concurrent.TimeUnit

enum class Link { Disconnected, Connecting, Connected }

data class AdbStatus(
    val paired: Boolean,
    val link: Link,
    val message: String? = null,
    val port: Int? = null,
)

sealed class PairResult {
    data object Paired : PairResult()
    data class Failed(val reason: String) : PairResult()
}

class AdbRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("adb", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val state = MutableStateFlow(
        AdbStatus(paired = prefs.getBoolean(KEY_PAIRED, false), link = Link.Disconnected)
    )
    private val manager: Manager by lazy {
        val identity = AdbKeys.loadOrCreate(appContext)
        if (identity.freshlyCreated) setPaired(false)
        Manager(identity)
    }

    val status: StateFlow<AdbStatus> get() = state

    suspend fun pair(code: String, port: Int): PairResult = withContext(Dispatchers.IO) {
        if (!PAIRING_CODE.matches(code)) {
            return@withContext PairResult.Failed("The pairing code is six digits.")
        }
        if (port !in 1..65535) {
            return@withContext PairResult.Failed("That is not a valid port.")
        }
        mutex.withLock {
            try {
                runInterruptible { manager.pair(LOCALHOST, port, code) }
                setPaired(true)
                state.value = state.value.copy(paired = true, message = null)
                PairResult.Paired
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PairResult.Failed(
                    "Pairing did not work. Check the code, and keep the pairing dialog open " +
                        "while you enter it."
                )
            }
        }
    }

    suspend fun connect(manualPort: Int? = null): Boolean = withContext(Dispatchers.IO) {
        if (manualPort != null && manualPort !in 1..65535) {
            state.value = state.value.copy(
                message = "That is not a valid port. Type the number after the colon, between 1 and 65535.",
            )
            return@withContext false
        }
        mutex.withLock {
            try {
                connectLocked(manualPort)
            } finally {
                if (state.value.link == Link.Connecting) {
                    state.value = state.value.copy(link = Link.Disconnected, port = null)
                }
            }
        }
    }

    private suspend fun connectLocked(manualPort: Int?): Boolean {
        if (manualPort == null && manager.isConnected && ping()) {
            state.value = state.value.copy(link = Link.Connected, message = null)
            return true
        }
        state.value = state.value.copy(link = Link.Connecting, message = null)
        val candidates = LinkedHashSet<Int>()
        if (manualPort != null) candidates += manualPort
        if (manualPort == null) {
            candidates += AdbDiscovery(appContext, AdbDiscovery.TYPE_CONNECT)
                .awaitPorts(DISCOVERY_TIMEOUT_MS).reversed()
            val last = prefs.getInt(KEY_LAST_PORT, -1)
            if (last > 0) candidates += last
        }
        var needsPairing = false
        for (port in candidates) {
            try {
                disconnectQuietly()
                val connected = runInterruptible { manager.connect(LOCALHOST, port) }
                if (connected && ping()) {
                    prefs.edit { putInt(KEY_LAST_PORT, port) }
                    setPaired(true)
                    state.value = AdbStatus(true, Link.Connected, null, port)
                    return true
                }
            } catch (e: CancellationException) {
                disconnectQuietly()
                throw e
            } catch (e: AdbPairingRequiredException) {
                needsPairing = true
            } catch (e: AdbAuthenticationFailedException) {
                needsPairing = true
            } catch (e: Exception) {
                continue
            }
        }
        disconnectQuietly()
        if (needsPairing) setPaired(false)
        val message = when {
            needsPairing ->
                "This phone no longer accepts Berk's Apps. Pair again with a new pairing code."
            candidates.isEmpty() ->
                "Wireless debugging was not found. Turn it on (it switches off after every " +
                    "restart) and make sure Wi-Fi is connected."
            else ->
                "Could not connect to Wireless debugging. Check that it is on. If it is, " +
                    "pair again."
        }
        state.value = AdbStatus(prefs.getBoolean(KEY_PAIRED, false), Link.Disconnected, message)
        return false
    }

    suspend fun run(command: ShellCommand, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ShellOutput? =
        withContext(Dispatchers.IO) {
            mutex.withLock { execute(command, timeoutMs) }
        }

    fun isConnected(): Boolean = state.value.link == Link.Connected

    private suspend fun ping(): Boolean = execute(ShellCommand.identity(), PING_TIMEOUT_MS)?.ok == true

    private suspend fun execute(command: ShellCommand, timeoutMs: Long): ShellOutput? {
        if (!manager.isConnected) {
            markDisconnected()
            return null
        }
        val collected = ByteArrayOutputStream()
        val finished = try {
            withTimeoutOrNull(timeoutMs) {
                runInterruptible {
                    manager.openStream("shell:" + command.line).use { stream ->
                        val buffer = ByteArray(8192)
                        var tail = ""
                        while (!markerComplete(tail)) {
                            val read = try {
                                stream.read(buffer, 0, buffer.size)
                            } catch (e: IOException) {
                                break
                            }
                            if (read > 0) {
                                val room = MAX_OUTPUT_BYTES - collected.size()
                                if (room <= 0) break
                                collected.write(buffer, 0, minOf(read, room))
                                tail = (tail + String(buffer, 0, read, Charsets.ISO_8859_1))
                                    .takeLast(TAIL_CHARS)
                            } else if (stream.isClosed) {
                                break
                            }
                        }
                    }
                    true
                }
            }
        } catch (e: CancellationException) {
            disconnectQuietly()
            markDisconnected()
            throw e
        } catch (e: Exception) {
            null
        }
        val output = if (finished == null) {
            null
        } else {
            ShellOutput.parse(collected.toString(Charsets.UTF_8.name()))
        }
        if (output == null || output.exitCode == null) {
            disconnectQuietly()
            markDisconnected()
            return null
        }
        return output
    }

    private fun markerComplete(tail: String): Boolean {
        val at = tail.lastIndexOf(ShellCommand.EXIT_MARKER)
        return at >= 0 && tail.indexOf('\n', at) > 0
    }

    private fun markDisconnected() {
        if (state.value.link != Link.Disconnected) {
            state.value = state.value.copy(link = Link.Disconnected, port = null)
        }
    }

    private fun disconnectQuietly() {
        try {
            manager.disconnect()
        } catch (e: Exception) {
            return
        }
    }

    private fun setPaired(paired: Boolean) {
        prefs.edit { putBoolean(KEY_PAIRED, paired) }
    }

    private class Manager(private val identity: AdbIdentity) : AbsAdbConnectionManager() {
        init {
            api = Build.VERSION.SDK_INT
            setTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            isThrowOnUnauthorised = true
        }

        override fun getPrivateKey(): PrivateKey = identity.privateKey
        override fun getCertificate(): Certificate = identity.certificate
        override fun getDeviceName(): String = "Berks Apps"
    }

    companion object {
        private const val LOCALHOST = "127.0.0.1"
        private const val KEY_PAIRED = "paired"
        private const val KEY_LAST_PORT = "lastPort"
        private const val DISCOVERY_TIMEOUT_MS = 4000L
        private const val CONNECT_TIMEOUT_S = 8L
        private const val PING_TIMEOUT_MS = 5000L
        private const val DEFAULT_TIMEOUT_MS = 20000L
        private const val MAX_OUTPUT_BYTES = 2 * 1024 * 1024
        private const val TAIL_CHARS = 96
        private val PAIRING_CODE = Regex("^[0-9]{6}$")
    }
}
