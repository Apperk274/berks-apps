package dev.personal.berksapps.adb

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AdbIdentity(val privateKey: PrivateKey, val certificate: Certificate, val freshlyCreated: Boolean)

object AdbKeys {
    private const val WRAP_ALIAS = "berksapps_adb_wrap"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TWENTY_YEARS_MS = 20L * 365 * 24 * 60 * 60 * 1000

    fun loadOrCreate(context: Context): AdbIdentity {
        val dir = File(context.filesDir, "adb").apply { mkdirs() }
        val keyFile = File(dir, "key.bin")
        val certFile = File(dir, "cert.der")
        if (keyFile.exists() && certFile.exists()) {
            try {
                val pkcs8 = unwrap(keyFile.readBytes())
                val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
                val cert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(certFile.readBytes()))
                return AdbIdentity(key, cert, false)
            } catch (e: Exception) {
                keyFile.delete()
                certFile.delete()
            }
        }
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048, SecureRandom())
        val pair = generator.generateKeyPair()
        val subject = X500Name("CN=Berks Apps")
        val now = System.currentTimeMillis()
        val holder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger(64, SecureRandom()).abs().add(BigInteger.ONE),
            Date(now - 24L * 60 * 60 * 1000),
            Date(now + TWENTY_YEARS_MS),
            subject,
            pair.public,
        ).build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private))
        val encodedCert = holder.encoded
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(encodedCert))
        keyFile.writeBytes(wrap(pair.private.encoded))
        certFile.writeBytes(encodedCert)
        return AdbIdentity(pair.private, cert, true)
    }

    private fun wrappingKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(WRAP_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                WRAP_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun unwrap(stored: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            wrappingKey(),
            GCMParameterSpec(128, stored, 0, IV_LENGTH),
        )
        return cipher.doFinal(stored, IV_LENGTH, stored.size - IV_LENGTH)
    }
}
