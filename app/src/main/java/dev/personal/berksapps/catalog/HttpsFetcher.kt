package dev.personal.berksapps.catalog

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

object HttpsFetcher {
    private const val MAX_REDIRECTS = 5
    private const val CONNECT_TIMEOUT_MS = 10000
    private const val READ_TIMEOUT_MS = 20000

    fun fetchText(url: String, maxBytes: Int): String {
        val connection = open(url)
        try {
            val out = ByteArrayOutputStream()
            connection.inputStream.use { copy(it, out, maxBytes.toLong()) }
            return out.toString(Charsets.UTF_8.name())
        } finally {
            connection.disconnect()
        }
    }

    fun download(url: String, destination: File, maxBytes: Long) {
        val connection = open(url)
        try {
            destination.outputStream().use { out ->
                connection.inputStream.use { copy(it, out, maxBytes) }
            }
        } catch (e: IOException) {
            destination.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }

    private fun open(start: String): HttpsURLConnection {
        var current = start
        repeat(MAX_REDIRECTS + 1) {
            if (!CatalogParser.isHttpsUrl(current)) {
                throw IOException("Refusing a non-HTTPS address")
            }
            val connection = URL(current).openConnection() as? HttpsURLConnection
                ?: throw IOException("Refusing a non-HTTPS connection")
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "*/*")
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrEmpty()) throw IOException("Redirect without a target")
                current = URL(URL(current), location).toString()
                return@repeat
            }
            if (code != 200) {
                connection.disconnect()
                throw IOException("Server answered $code")
            }
            return connection
        }
        throw IOException("Too many redirects")
    }

    private fun copy(input: InputStream, output: OutputStream, maxBytes: Long) {
        val buffer = ByteArray(1 shl 16)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw IOException("The file is larger than allowed")
            output.write(buffer, 0, read)
        }
    }
}
