package dev.personal.berksapps.catalog

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

class DownloadCancelledException : IOException("Cancelled")

class DownloadControl {
    @Volatile
    private var flagged = false

    @Volatile
    private var connection: HttpURLConnection? = null

    val cancelled: Boolean get() = flagged

    fun cancel() {
        flagged = true
        val current = connection ?: return
        Thread {
            try {
                current.disconnect()
            } catch (e: Exception) {
                return@Thread
            }
        }.start()
    }

    internal fun attach(target: HttpURLConnection?) {
        connection = target
        if (flagged && target != null) cancel()
    }

    internal fun check() {
        if (flagged) throw DownloadCancelledException()
    }
}

object HttpsFetcher {
    private const val MAX_REDIRECTS = 5
    private const val CONNECT_TIMEOUT_MS = 10000
    private const val READ_TIMEOUT_MS = 20000

    fun fetchText(url: String, maxBytes: Int, policy: DownloadPolicy): String {
        val connection = open(url, policy, null)
        try {
            val out = ByteArrayOutputStream()
            connection.inputStream.use { copy(it, out, maxBytes.toLong(), -1L, null, null) }
            return out.toString(Charsets.UTF_8.name())
        } finally {
            connection.disconnect()
        }
    }

    fun download(
        url: String,
        destination: File,
        maxBytes: Long,
        policy: DownloadPolicy,
        control: DownloadControl,
        onProgress: (done: Long, total: Long) -> Unit,
    ) {
        var complete = false
        try {
            val connection = open(url, policy, control)
            try {
                val total = connection.contentLengthLong
                if (total > maxBytes) throw IOException("The file is larger than allowed")
                destination.outputStream().use { out ->
                    connection.inputStream.use { copy(it, out, maxBytes, total, onProgress, control) }
                }
                control.check()
                if (total >= 0 && destination.length() != total) {
                    throw IOException("The download stopped early")
                }
                complete = true
            } finally {
                control.attach(null)
                connection.disconnect()
            }
        } catch (e: IOException) {
            if (control.cancelled) throw DownloadCancelledException()
            throw e
        } finally {
            if (!complete) destination.delete()
        }
    }

    private fun open(start: String, policy: DownloadPolicy, control: DownloadControl?): HttpURLConnection {
        var current = start
        repeat(MAX_REDIRECTS + 1) {
            control?.check()
            if (!policy.allows(current)) {
                throw IOException("Refusing an address that is not allowed")
            }
            val connection = URL(current).openConnection() as? HttpURLConnection
                ?: throw IOException("Refusing a connection that is not HTTP")
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "*/*")
            connection.setRequestProperty("Accept-Encoding", "identity")
            control?.attach(connection)
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                control?.attach(null)
                connection.disconnect()
                current = policy.resolveRedirect(current, location)
                return@repeat
            }
            if (code != 200) {
                control?.attach(null)
                connection.disconnect()
                throw IOException("The server answered $code")
            }
            return connection
        }
        throw IOException("Too many redirects")
    }

    private fun copy(
        input: InputStream,
        output: OutputStream,
        maxBytes: Long,
        total: Long,
        onProgress: ((Long, Long) -> Unit)?,
        control: DownloadControl?,
    ) {
        val buffer = ByteArray(1 shl 16)
        var done = 0L
        while (true) {
            control?.check()
            val read = input.read(buffer)
            if (read < 0) break
            done += read
            if (done > maxBytes) throw IOException("The file is larger than allowed")
            output.write(buffer, 0, read)
            onProgress?.invoke(done, total)
        }
    }
}
