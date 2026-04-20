package com.example.missavapp.data.network

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.Locale
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import java.nio.charset.Charset
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private const val PREF_NAME = "missav_session"
private const val KEY_COOKIE_PREFIX = "cookie:"

private const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 13; Pixel 7 Build/TQ3A.230901.001) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

private val COOKIE_ATTRIBUTE_KEYS = setOf(
    "path",
    "expires",
    "domain",
    "secure",
    "httponly",
    "samesite",
    "max-age",
    "sameparty",
    "priority",
    "partitioned"
)

private fun isCloudflareChallenge(html: String): Boolean {
    val lower = html.lowercase(Locale.ROOT)
    return lower.contains("just a moment") ||
        lower.contains("_cf_chl") ||
        lower.contains("cf-challenge") ||
        lower.contains("cloudflare")
}

class MissAvHttpClient(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val jsonType = "application/json; charset=utf-8".toMediaTypeOrNull()

    var hosts: List<String> = listOf(
        "https://missav.ai",
        "https://missav.ws",
        "https://missav.live",
        "https://missav123.com"
    )

    private var activeHost = hosts.first()

    private val logger = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            val host = request.url.host
            val reqBuilder = request.newBuilder()
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Accept", "text/html,application/json;q=0.9,*/*;q=0.8")

            val cookies = getCookiesForHost(host)
            if (!cookies.isNullOrBlank()) {
                reqBuilder.header("Cookie", cookies)
            }
            chain.proceed(reqBuilder.build())
        }
        .addInterceptor(logger)
        .build()

    fun setActiveHost(host: String) {
        val normalized = host.trim().trimEnd('/')
        activeHost = if (normalized.isBlank()) activeHost else normalized
    }

    fun getActiveHost(): String = activeHost

    suspend fun performGet(path: String, refererUrl: String? = null): Result<RawResponse> {
        return doRequest("GET", path, null, refererUrl)
    }

    suspend fun performPostJson(path: String, payload: JSONObject): Result<RawResponse> {
        return doRequest("POST", path, payload.toString().toRequestBody(jsonType))
    }

    suspend fun performDelete(path: String): Result<RawResponse> {
        return doRequest("DELETE", path, null)
    }

    suspend fun performPut(path: String): Result<RawResponse> {
        val body: RequestBody = "".toRequestBody("text/plain".toMediaTypeOrNull())
        return doRequest("PUT", path, body)
    }

    suspend fun doRequest(
        method: String,
        path: String,
        body: RequestBody? = null,
        refererUrl: String? = null
    ): Result<RawResponse> {
        val request = buildRequest(method, path, body, refererUrl)

        return suspendCoroutine { cont ->
            client.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    cont.resume(Result.failure(e))
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val responseBytes = response.body?.bytes()
                    val text = responseBytes?.let { rawBytes -> String(rawBytes, Charset.defaultCharset()) } ?: ""
                    syncSetCookies(response.request.url.host, response.headers)
                    cont.resume(Result.success(RawResponse(response.code, text, response.headers)))
                }
            })
        }
    }

    private fun buildUrl(path: String): String {
        val trimmed = path.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed
        }
        val normalizedPath = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        return "$activeHost$normalizedPath"
    }

    fun getCookiesForHost(host: String): String? {
        val normalized = normalizeHost(host)
        val merged = mutableMapOf<String, String>()
        hostAliases(normalized).forEach { alias ->
            addCookiePairs(merged, getSavedCookies(alias))
            addCookiePairs(merged, CookieManager.getInstance().getCookie(buildHostUrl(alias)))
        }
        return merged.toCookieString().ifBlank { null }
    }

    fun syncCookiesFromWebCookies(host: String): Boolean {
        val normalizedHost = normalizeHost(host)
        if (normalizedHost.isBlank()) return false

        val merged = mutableMapOf<String, String>()
        addCookiePairs(merged, getSavedCookies(normalizedHost))

        var hasWebCookie = false
        hostAliases(normalizedHost).forEach { alias ->
            val cookies = CookieManager.getInstance().getCookie(buildHostUrl(alias)) ?: return@forEach
            if (cookies.isNotBlank()) {
                addCookiePairs(merged, cookies)
                hasWebCookie = true
            }
        }

        if (!hasWebCookie) {
            return false
        }

        val saved = merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
        prefs.edit().putString(KEY_COOKIE_PREFIX + normalizedHost, saved).apply()
        return true
    }

    private fun normalizeHost(input: String): String {
        return input.trim()
            .lowercase(Locale.ROOT)
            .trimEnd('/')
            .removePrefix("https://")
            .removePrefix("http://")
    }

    private fun syncSetCookies(host: String, headers: Headers) {
        val setCookie = headers.values("Set-Cookie")
        if (setCookie.isEmpty()) return

        val normalized = normalizeHost(host)
        val existing = mutableMapOf<String, String>()
        addCookiePairs(existing, getSavedCookies(normalized))

        setCookie.forEach { raw ->
            parseCookieToken(raw.substringBefore(';'))?.let { (key, value) ->
                existing.setCookiePair(key, value)
            }
        }

        val saved = existing.entries.joinToString("; ") { "${it.key}=${it.value}" }
        prefs.edit().putString(KEY_COOKIE_PREFIX + normalized, saved).apply()
    }

    private fun parseCookieToken(token: String): Pair<String, String>? {
        val trimmed = token.trim()
        val idx = trimmed.indexOf('=')
        if (idx <= 0) return null
        val name = trimmed.substring(0, idx).trim()
        if (name.isBlank() || isCookieAttr(name)) return null
        return Pair(name, trimmed.substring(idx + 1).trim())
    }

    private fun getSavedCookies(normalizedHost: String): String? = prefs.getString(KEY_COOKIE_PREFIX + normalizedHost, null)

    private fun hostAliases(host: String): List<String> {
        val normalized = normalizeHost(host)
        if (normalized.isBlank()) return emptyList()
        return buildList {
            add(normalized)
            if (normalized.startsWith("www.")) {
                add(normalized.removePrefix("www."))
            } else {
                add("www.$normalized")
            }
        }.distinct()
    }

    private fun addCookiePairs(
        target: MutableMap<String, String>,
        rawCookie: String?
    ) {
        if (rawCookie.isNullOrBlank()) return
        rawCookie.split(';').forEach { token ->
            parseCookieToken(token)?.let { (name, value) ->
                target.setCookiePair(name, value)
            }
        }
    }

    private fun MutableMap<String, String>.setCookiePair(name: String, value: String) {
        if (value.isBlank()) {
            remove(name)
        } else {
            this[name] = value
        }
    }

    private fun Map<String, String>.toCookieString(): String {
        return entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun isCookieAttr(name: String): Boolean =
        COOKIE_ATTRIBUTE_KEYS.contains(name.lowercase(Locale.ROOT))

    private fun buildHostUrl(host: String): String = if (host.startsWith("http://") || host.startsWith("https://")) {
        host
    } else {
        "https://$host"
    }

    suspend fun downloadToFile(
        url: String,
        file: File,
        refererUrl: String? = null,
        onProgress: (Long, Long) -> Unit
    ): Result<Unit> {
        return withContext(Dispatchers.IO) {
            val request = buildRequest("GET", url, null, refererUrl)
            suspendCoroutine { cont ->
                client.newCall(request).enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: IOException) {
                        cont.resume(Result.failure(e))
                    }

                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        if (!response.isSuccessful) {
                            response.close()
                            cont.resume(Result.failure(IOException("download failed HTTP=${response.code}")))
                            return
                        }

                        val body = response.body ?: run {
                            response.close()
                            cont.resume(Result.failure(IOException("download response empty")))
                            return
                        }

                        file.parentFile?.mkdirs()
                        runCatching {
                            var downloaded = 0L
                            val total = body.contentLength()

                            body.byteStream().use { input ->
                                file.outputStream().use { output ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        val read = input.read(buffer)
                                        if (read <= 0) break
                                        output.write(buffer, 0, read)
                                        downloaded += read.toLong()
                                        onProgress(downloaded, total)
                                    }
                                    output.flush()
                                }
                            }
                        }.onSuccess {
                            cont.resume(Result.success(Unit))
                        }.onFailure { err ->
                            cont.resume(Result.failure(err))
                        }
                    }
                })
            }
        }
    }

    fun clearSession() {
        prefs.edit().apply {
            hosts.forEach { host ->
                remove(KEY_COOKIE_PREFIX + host.removePrefix("https://"))
                remove(KEY_COOKIE_PREFIX + host)
            }
        }.apply()
    }

    private fun buildRequest(
        method: String,
        path: String,
        body: RequestBody? = null,
        refererUrl: String? = null
    ): Request {
        val url = if (path.startsWith("http://") || path.startsWith("https://")) {
            path
        } else {
            buildUrl(path)
        }
        val builder = Request.Builder()
            .url(url)
            .method(method, body)

        val referer = normalizeReferer(refererUrl, url)
        if (!referer.isNullOrBlank()) {
            builder.header("Referer", referer)
            extractOrigin(referer)?.let { builder.header("Origin", it) }
        }
        return builder.build()
    }

    private fun normalizeReferer(refererUrl: String?, fallbackUrl: String): String? {
        val raw = refererUrl?.trim().takeUnless { it.isNullOrBlank() }
            ?: runCatching {
                val uri = URI(fallbackUrl)
                "${uri.scheme}://${uri.host}/"
            }.getOrNull()
        return raw?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun extractOrigin(url: String): String? {
        return runCatching {
            val uri = URI(url)
            "${uri.scheme}://${uri.host}"
        }.getOrNull()
    }
}

data class RawResponse(
    val code: Int,
    val body: String,
    val headers: Headers,
)

fun String.isCloudflareChallengePage(): Boolean = isCloudflareChallenge(this)
