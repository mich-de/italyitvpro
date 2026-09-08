package com.michde.italyitv.core

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.io.InputStream

/** Thin OkHttp wrapper shared by the whole app. */
object Http {
    const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
    const val OKHTTP_UA = "okhttp/4.12.0"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    /** Some IPTV CDNs ship an expired TLS cert but serve fine over plain HTTP. */
    fun preferHttp(url: String): String =
        if (url.startsWith("https://")) "http://" + url.substring("https://".length) else url

    @Throws(IOException::class)
    fun getText(url: String, ua: String = DEFAULT_UA, referer: String? = null): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", ua)
            .apply { referer?.let { header("Referer", it) } }
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url")
            return resp.body?.string() ?: ""
        }
    }

    @Throws(IOException::class)
    fun postJson(url: String, body: String, ua: String = OKHTTP_UA): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", ua)
            .header("Accept", "application/json")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url")
            return resp.body?.string() ?: ""
        }
    }

    /** Open a stream, transparently decompressing gzip. Caller closes it. */
    @Throws(IOException::class)
    fun openStream(url: String, ua: String = DEFAULT_UA): InputStream {
        val req = Request.Builder().url(url).header("User-Agent", ua).build()
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) {
            resp.close()
            throw IOException("HTTP ${resp.code} for $url")
        }
        val src = resp.body!!.byteStream()
        val gz = url.endsWith(".gz", true) ||
            resp.header("Content-Type")?.contains("gzip", true) == true
        return if (gz) GZIPInputStream(src) else src
    }
}
