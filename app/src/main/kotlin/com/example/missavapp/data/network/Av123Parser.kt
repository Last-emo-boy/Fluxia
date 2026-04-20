package com.example.missavapp.data.network

import android.util.Base64
import com.example.missavapp.data.model.Actress
import com.example.missavapp.data.model.VideoCard
import com.example.missavapp.data.model.VideoDetail
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

private const val AV123_BASE_URL = "https://123av.com"
private const val AV123_SOURCE_NAME = "123AV"
private const val AV123_IFRAME_KEY = "QgYgkSJJnpAAWy31"
private const val AV123_STREAM_KEY = "ym1eS4t0jTLakZYQ"
private const val AV123_SURRIT_STREAM_URL = "https://surrit.store/stream"

private val AV123_VIDEO_PATH_PATTERN = Regex("""/(?:[a-z]{2,3}/)?v/([a-z0-9-]+)""", RegexOption.IGNORE_CASE)
private val AV123_CODE_PATTERN = Regex("""(?i)\b[a-z0-9]+-[a-z0-9]+(?:-[a-z0-9]+)*\b""")
private val AV123_MOVIE_ID_PATTERN = Regex("""Movie\(\{id:\s*(\d+)""", RegexOption.IGNORE_CASE)
private val AV123_EMBED_ID_PATTERN = Regex("""/e/([A-Za-z0-9_-]+)""")
private val AV123_LOCALE_PATTERN = Regex("""^/([a-z]{2,3})(?:/|$)""", RegexOption.IGNORE_CASE)
private val AV123_STREAM_URL_PATTERN =
    Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:\?[^"'\\s<>]*)?""", RegexOption.IGNORE_CASE)

data class Av123StreamRequest(
    val iframeUrl: String,
    val streamApiUrl: String
)

object Av123Parser {
    fun parseSearchList(html: String, baseUrl: String): List<VideoCard> {
        val doc = Jsoup.parse(html, baseUrl)
        val cards = linkedMapOf<String, VideoCard>()

        doc.select(".box-item").forEach { box ->
            val anchor = box.selectFirst(".thumb a[href], .detail a[href], a[href]")
                ?: return@forEach
            val href = resolveUrl(baseUrl, anchor.attr("href"))
            if (!href.contains("/v/", ignoreCase = true)) return@forEach

            val code = extractCodeFromUrl(href).ifBlank {
                extractCodeFromText(
                    chooseFirstNonBlank(
                        anchor.attr("title"),
                        box.selectFirst("img")?.attr("title"),
                        box.selectFirst("img")?.attr("alt"),
                        box.text()
                    )
                )
            }
            if (code.isBlank()) return@forEach

            val title = chooseFirstNonBlank(
                box.selectFirst(".detail a")?.text(),
                anchor.attr("title"),
                box.selectFirst("img")?.attr("alt"),
                box.selectFirst("img")?.attr("title")
            ).ifBlank { code.uppercase(Locale.ROOT) }

            val thumbnail = chooseFirstNonBlank(
                box.selectFirst("img")?.attr("data-src"),
                box.selectFirst("img")?.attr("src")
            ).let { resolveUrl(baseUrl, it) }.ifBlank { null }

            val candidate = VideoCard(
                code = code,
                title = title,
                href = href,
                thumbnail = thumbnail,
                sourceSite = AV123_SOURCE_NAME
            )

            val key = href.lowercase(Locale.ROOT)
            val current = cards[key]
            if (current == null || shouldReplaceCard(current, candidate)) {
                cards[key] = candidate
            }
        }

        return cards.values.toList()
    }

    fun parseVideoDetail(
        html: String,
        baseUrl: String,
        ajaxJson: String,
        streamJson: String?
    ): VideoDetail {
        val doc = Jsoup.parse(html, baseUrl)
        val code = extractCodeFromUrl(baseUrl).ifBlank {
            extractCodeFromText(
                chooseFirstNonBlank(
                    doc.selectFirst("h1")?.text(),
                    doc.selectFirst("meta[property=og:title]")?.attr("content")
                )
            )
        }
        val title = chooseFirstNonBlank(
            doc.selectFirst("h1")?.text(),
            doc.selectFirst("meta[property=og:title]")?.attr("content")?.replace(
                Regex("""\s*-\s*123AV\s*$""", RegexOption.IGNORE_CASE),
                ""
            )
        ).ifBlank { code.uppercase(Locale.ROOT) }

        val thumbnails = buildList {
            listOf(
                extractPosterUrl(html, baseUrl),
                doc.selectFirst("meta[property=og:image]")?.attr("content"),
                doc.selectFirst("meta[name=twitter:image]")?.attr("content")
            ).forEach { value ->
                resolveUrl(baseUrl, value).takeIf { it.isNotBlank() }?.let(::add)
            }
        }.distinct()

        val actresses = doc.select("#details a[href*=\"actresses/\"]")
            .mapNotNull { anchor ->
                val name = anchor.text().trim()
                val href = resolveUrl(baseUrl, anchor.attr("href"))
                if (name.isBlank() || href.isBlank()) null else Actress(name = name, url = href)
            }
            .distinctBy { it.name }

        val tags = doc.select("#details a[href]")
            .mapNotNull { anchor ->
                val href = resolveUrl(baseUrl, anchor.attr("href"))
                val text = anchor.text().trim()
                if (text.isBlank()) return@mapNotNull null
                when {
                    href.contains("/genres/", ignoreCase = true) -> text
                    href.contains("/makers/", ignoreCase = true) -> text
                    href.contains("/censored", ignoreCase = true) -> text
                    else -> null
                }
            }
            .distinct()

        val recommendations = parseSearchList(html, baseUrl)
            .filterNot { it.href.equals(baseUrl, ignoreCase = true) || it.code.equals(code, ignoreCase = true) }
            .distinctBy { it.href.lowercase(Locale.ROOT) }

        return VideoDetail(
            code = code,
            title = title,
            href = baseUrl,
            hlsUrl = parseStreamUrl(streamJson),
            thumbnails = thumbnails,
            actresses = actresses,
            tags = tags,
            recommendations = recommendations,
            sourceSite = AV123_SOURCE_NAME,
            sourceUrl = baseUrl
        )
    }

    fun extractMovieId(html: String): String? {
        return AV123_MOVIE_ID_PATTERN.find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
    }

    fun buildAjaxUrl(detailUrl: String, movieId: String): String {
        val locale = extractLocale(detailUrl)
        return "$AV123_BASE_URL/$locale/ajax/v/$movieId/videos"
    }

    fun extractPosterUrl(html: String, baseUrl: String): String? {
        val doc = Jsoup.parse(html, baseUrl)
        return listOf(
            doc.selectFirst("#player")?.attr("data-poster"),
            doc.selectFirst("meta[property=og:image]")?.attr("content"),
            doc.selectFirst("meta[name=twitter:image]")?.attr("content")
        ).asSequence()
            .map { resolveUrl(baseUrl, it) }
            .firstOrNull { it.isNotBlank() }
    }

    fun buildStreamRequest(ajaxJson: String, posterUrl: String?): Av123StreamRequest? {
        val encodedWatchUrl = extractEncodedWatchUrl(ajaxJson) ?: return null
        val iframeUrl = decodeIframeUrl(encodedWatchUrl) ?: return null
        val embedId = AV123_EMBED_ID_PATTERN.find(iframeUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val token = encodeToken(embedId)
        val encodedPoster = posterUrl
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { URLEncoder.encode(it, Charsets.UTF_8.name()).replace("+", "%20") }
        val streamApiUrl = buildString {
            append(AV123_SURRIT_STREAM_URL)
            append("?token=")
            append(URLEncoder.encode(token, Charsets.UTF_8.name()).replace("+", "%20"))
            if (!encodedPoster.isNullOrBlank()) {
                append("&poster=")
                append(encodedPoster)
            }
        }
        val iframeWithPoster = if (encodedPoster.isNullOrBlank()) {
            iframeUrl
        } else {
            iframeUrl.substringBefore('?') + "?poster=$encodedPoster"
        }

        return Av123StreamRequest(
            iframeUrl = iframeWithPoster,
            streamApiUrl = streamApiUrl
        )
    }

    private fun parseStreamUrl(streamJson: String?): String? {
        if (streamJson.isNullOrBlank()) return null
        val media = runCatching {
            val root = JSONObject(streamJson)
            val result = root.optJSONObject("result") ?: root
            result.optString("media")
        }.getOrNull().orEmpty()
        if (media.isBlank()) return null

        val decodedBytes = decodeBase64(media) ?: return null
        val jsonText = xorBytes(decodedBytes, AV123_STREAM_KEY.toByteArray(Charsets.UTF_8))
            .toString(Charsets.UTF_8)
        val direct = runCatching {
            val payload = JSONObject(jsonText)
            payload.optString("stream")
        }.getOrNull().orEmpty()
        if (direct.isNotBlank()) {
            return direct.replace("\\/", "/")
        }

        return AV123_STREAM_URL_PATTERN.find(jsonText)?.value?.replace("\\/", "/")
    }

    private fun extractEncodedWatchUrl(ajaxJson: String): String? {
        val root = runCatching { JSONObject(ajaxJson) }.getOrNull() ?: return null
        val arrays = buildList {
            add(root.optJSONArray("watch"))
            add(root.optJSONArray("videos"))
            root.optJSONObject("result")?.let { result ->
                add(result.optJSONArray("watch"))
                add(result.optJSONArray("videos"))
            }
            root.optJSONObject("data")?.let { data ->
                add(data.optJSONArray("watch"))
                add(data.optJSONArray("videos"))
            }
        }

        arrays.forEach { array ->
            val value = extractWatchUrlFromArray(array)
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun extractWatchUrlFromArray(array: JSONArray?): String? {
        if (array == null) return null
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val value = chooseFirstNonBlank(
                item.optString("url"),
                item.optString("src"),
                item.optString("iframe"),
                item.optString("file")
            )
            if (value.isNotBlank()) return value
        }
        return null
    }

    private fun decodeIframeUrl(encoded: String): String? {
        val payload = decodeBase64(encoded) ?: return null
        val decoded = xorBytes(payload, AV123_IFRAME_KEY.toByteArray(Charsets.UTF_8))
            .toString(Charsets.UTF_8)
            .trim()
        return resolveUrl("https://surrit.store", decoded).takeIf { it.startsWith("http", ignoreCase = true) }
    }

    private fun encodeToken(embedId: String): String {
        val xored = xorBytes(embedId.toByteArray(Charsets.UTF_8), AV123_STREAM_KEY.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(xored, Base64.NO_WRAP)
    }

    private fun xorBytes(data: ByteArray, key: ByteArray): ByteArray {
        return ByteArray(data.size) { index ->
            (data[index].toInt() xor key[index % key.size].toInt()).toByte()
        }
    }

    private fun decodeBase64(value: String): ByteArray? {
        return runCatching {
            Base64.decode(value.trim(), Base64.DEFAULT)
        }.getOrNull()
    }

    private fun extractLocale(url: String): String {
        val path = runCatching { URI(url).path.orEmpty() }.getOrNull().orEmpty()
        return AV123_LOCALE_PATTERN.find(path)
            ?.groupValues
            ?.getOrNull(1)
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() }
            ?: "zh"
    }

    private fun extractCodeFromText(value: String?): String {
        return AV123_CODE_PATTERN.find(value.orEmpty())
            ?.value
            ?.lowercase(Locale.ROOT)
            .orEmpty()
    }

    private fun extractCodeFromUrl(url: String): String {
        return AV123_VIDEO_PATH_PATTERN.find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.lowercase(Locale.ROOT)
            .orEmpty()
    }

    private fun shouldReplaceCard(current: VideoCard, candidate: VideoCard): Boolean {
        val currentScore = current.title.length + if (current.thumbnail.isNullOrBlank()) 0 else 1
        val candidateScore = candidate.title.length + if (candidate.thumbnail.isNullOrBlank()) 0 else 1
        return candidateScore > currentScore
    }

    private fun chooseFirstNonBlank(vararg values: String?): String {
        return values.firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
    }

    private fun resolveUrl(baseUrl: String, href: String?): String {
        val trimmed = href.orEmpty().trim()
        if (trimmed.isBlank()) return ""
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        if (trimmed.startsWith("//")) return "https:$trimmed"

        val normalizedBase = runCatching {
            URI(baseUrl).run {
                if (scheme.isNullOrBlank() || host.isNullOrBlank()) return@run AV123_BASE_URL
                "${scheme}://${host}"
            }
        }.getOrDefault(AV123_BASE_URL)

        return if (trimmed.startsWith("/")) {
            normalizedBase + trimmed
        } else {
            "$normalizedBase/$trimmed"
        }
    }
}
