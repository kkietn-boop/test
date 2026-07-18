package com.lagradost

import android.util.Base64
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.JsonNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

object PipeClient {
    private const val KEY = "71951034f8fbcf53d89db52ceb3dc22c"

    private fun decrypt(encrypted: ByteArray): ByteArray {
        val keyBytes = KEY.toByteArray(Charsets.US_ASCII)
        val decrypted = ByteArray(encrypted.size)
        for (i in encrypted.indices) {
            decrypted[i] = (encrypted[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
        }
        return decrypted
    }

    private fun gunzip(bytes: ByteArray): String {
        return ByteArrayInputStream(bytes).use { inputStream ->
            GZIPInputStream(inputStream).use { gzipStream ->
                ByteArrayOutputStream().use { outputStream ->
                    val buffer = ByteArray(1024)
                    var len: Int
                    while (gzipStream.read(buffer).also { len = it } > 0) {
                        outputStream.write(buffer, 0, len)
                    }
                    outputStream.toString("UTF-8")
                }
            }
        }
    }

    private suspend fun fetchPipe(jsonString: String): String {
        val jsonBytes = jsonString.toByteArray(Charsets.UTF_8)
        val base64UrlEncoded = Base64.encodeToString(
            jsonBytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val url = "https://www.miruro.to/api/secure/pipe?e=$base64UrlEncoded"
        val response = PipeBridge.fetch(url)
        val xObfuscated = response.headers.entries.firstOrNull {
            it.key.equals("x-obfuscated", ignoreCase = true)
        }?.value

        return if (xObfuscated == "2") {
            val decodedBytes = Base64.decode(
                response.body,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
            val decryptedBytes = decrypt(decodedBytes)
            gunzip(decryptedBytes)
        } else {
            response.body
        }
    }

    suspend fun getEpisodes(anilistId: Int): EpisodesResult {
        val envelope = buildJsonObject {
            put("path", "/anilist/episodes")
            put("method", "GET")
            putJsonObject("query") {
                put("id", anilistId)
            }
            put("body", JsonNull)
        }
        val responseBody = fetchPipe(envelope.toString())
        return jsonParser.decodeFromString(responseBody)
    }

    suspend fun getSources(pipeId: String, provider: String, category: String): SourcesResult {
        val envelope = buildJsonObject {
            put("path", "/watch/sources")
            put("method", "GET")
            putJsonObject("query") {
                put("pipeId", pipeId)
                put("provider", provider)
                put("category", category)
            }
            put("body", JsonNull)
        }
        val responseBody = fetchPipe(envelope.toString())
        return jsonParser.decodeFromString(responseBody)
    }
}
