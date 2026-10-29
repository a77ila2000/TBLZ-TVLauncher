package com.tblz.tvlauncher.engine

import android.util.Base64
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object TblzCipher {
    private const val EPOCH_DIVISOR = 10_000_000L
    private val outerIv = "6yhlJ4WF9ZIj6I8n".toByteArray(StandardCharsets.US_ASCII)
    private val innerKey = "kss2lym0kdw1lks3".toByteArray(StandardCharsets.US_ASCII)
    private val innerIv = byteArrayOf(
        0x2a, 0x07, 0x72, 0x3b, 0x37, 0x05, 0x1e, 0x01,
        0x6e, 0x44, 0x02, 0x33, 0x2a, 0x61, 0x26, 0x3c
    )

    fun decrypt(
        channelId: String,
        encryptedUrl: String,
        epochSeconds: Long = System.currentTimeMillis() / 1000
    ): Array<String> {
        require(channelId.length > 3) { "Invalid TVING channel ID" }
        require(encryptedUrl.isNotBlank()) { "Empty TVING stream value" }

        val plainUrl = if ('|' in encryptedUrl) {
            decryptWrapped(channelId, encryptedUrl.substringAfter('|'), epochSeconds)
        } else {
            decryptLegacy(channelId, encryptedUrl, epochSeconds)
        }
        return splitCloudFrontCookie(plainUrl)
    }

    private fun decryptWrapped(channelId: String, encoded: String, epochSeconds: Long): String {
        val key = formatKey("cj*tving/%s/%03d", channelId, epochSeconds)
        val outerJson = decryptAes(encoded, key, outerIv)
        val json = JsonParser.parseString(outerJson).asJsonObject
        val inner = json.get("broad_url")?.asString?.takeIf(String::isNotEmpty)
            ?: json.get("url")?.asString?.takeIf(String::isNotEmpty)
            ?: error("TVING stream URL is missing")
        return decryptAes(inner, innerKey, innerIv)
    }

    private fun decryptLegacy(channelId: String, encoded: String, epochSeconds: Long): String {
        val key = formatKey("cjhv*tving**good/%s/%03d", channelId, epochSeconds)
        val cipher = Cipher.getInstance("DESede/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "DESede"))
        return String(cipher.doFinal(decode(encoded)), StandardCharsets.UTF_8)
    }

    private fun decryptAes(encoded: String, key: ByteArray, iv: ByteArray): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return String(cipher.doFinal(decode(encoded)), StandardCharsets.UTF_8)
    }

    private fun formatKey(format: String, channelId: String, epochSeconds: Long): ByteArray {
        val key = String.format(
            Locale.US,
            format,
            channelId.substring(3),
            epochSeconds / EPOCH_DIVISOR
        ).toByteArray(StandardCharsets.US_ASCII)
        require(key.size == if (format.startsWith("cjhv")) 24 else 16) {
            "Unexpected TVING key length"
        }
        return key
    }

    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.DEFAULT)

    private fun splitCloudFrontCookie(url: String): Array<String> {
        if ("Policy=" !in url) return arrayOf(url, "")

        val queryStart = url.indexOf('?')
        if (queryStart < 0) return arrayOf(url, "")

        val cookie = url.substring(queryStart + 1)
            .split('&')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .joinToString(separator = "; ", postfix = "; ") { "CloudFront-$it" }
        return arrayOf(url.substring(0, queryStart).trim(), cookie)
    }
}
