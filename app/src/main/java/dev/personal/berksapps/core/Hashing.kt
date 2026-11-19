package dev.personal.berksapps.core

import java.io.InputStream
import java.security.MessageDigest

object Hashing {
    private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
    private const val HEX = "0123456789abcdef"

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return toHex(digest.digest())
    }

    fun sha256(bytes: ByteArray): String =
        toHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun toHex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return out.toString()
    }

    fun normalizeSha256(value: String?): String? {
        if (value == null) return null
        val cleaned = value.trim().replace(":", "").replace(" ", "").lowercase()
        return if (SHA256_HEX.matches(cleaned)) cleaned else null
    }

    fun sameDigest(a: String?, b: String?): Boolean {
        val left = normalizeSha256(a) ?: return false
        val right = normalizeSha256(b) ?: return false
        return MessageDigest.isEqual(left.toByteArray(), right.toByteArray())
    }
}
