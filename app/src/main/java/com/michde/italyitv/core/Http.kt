package com.michde.italyitv.core

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Thin OkHttp wrapper shared by the whole app. */
object Http {
    const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
    const val OKHTTP_UA = "okhttp/4.12.0"

    @Volatile
    var useHageziDns: Boolean = true

    /**
     * Many Italian IPTV CDNs ship long-expired / host-mismatched TLS certs but
     * serve the video fine. We are a personal media player, the payload is public
     * video, and cleartext is already allowed by the network security config —
     * so trust every cert rather than fight it with fragile http downgrades.
     */
    val trustAllManager: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    val trustAllSocketFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAllManager), null)
        }.socketFactory
    }

    private val allowAnyHost = HostnameVerifier { _, _ -> true }

    /** Route every `HttpURLConnection` (Media3 DefaultHttpDataSource, DliveResolver) through the permissive trust. */
    fun installPermissiveTls() {
        runCatching {
            HttpsURLConnection.setDefaultSSLSocketFactory(trustAllSocketFactory)
            HttpsURLConnection.setDefaultHostnameVerifier(allowAnyHost)
        }
    }

    /** HaGeZi DoH (DNS-over-HTTPS) resolver: blocks malware, trackers, adware. */
    val hageziDns: Dns by lazy {
        val bootstrapClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .sslSocketFactory(trustAllSocketFactory, trustAllManager)
            .hostnameVerifier(allowAnyHost)
            .build()
        DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url("https://root.hagezi.org/dns-query".toHttpUrl())
            .bootstrapDnsHosts(
                InetAddress.getByName("188.34.161.210"),
                InetAddress.getByName("159.69.155.94")
            )
            .includeIPv6(false)
            .build()
    }

    val resilientDns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (!useHageziDns) return Dns.SYSTEM.lookup(hostname)
            return try {
                hageziDns.lookup(hostname).ifEmpty { Dns.SYSTEM.lookup(hostname) }
            } catch (_: Throwable) {
                Dns.SYSTEM.lookup(hostname)
            }
        }
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .dns(resilientDns)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .sslSocketFactory(trustAllSocketFactory, trustAllManager)
        .hostnameVerifier(allowAnyHost)
        .build()

    /** Kept for callers that still want a plain-HTTP variant to try as a fallback. */
    fun preferHttp(url: String): String =
        if (url.startsWith("https://")) "http://" + url.substring("https://".length) else url

    /** `url` plus, when it is https, the same url over plain http — deduped, order preserved. */
    fun withHttpFallback(url: String): List<String> = buildList {
        add(url)
        if (url.startsWith("https://")) add(preferHttp(url))
    }.distinct()

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

    private val fastClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
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

    @Throws(IOException::class)
    fun postJsonFast(url: String, body: String, ua: String = OKHTTP_UA): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", ua)
            .header("Accept", "application/json")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
            .build()
        fastClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url")
            return resp.body?.string() ?: ""
        }
    }

    @Throws(IOException::class)
    fun getBytes(url: String, ua: String = DEFAULT_UA): ByteArray {
        val req = Request.Builder().url(url).header("User-Agent", ua).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url")
            return resp.body?.bytes() ?: ByteArray(0)
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
