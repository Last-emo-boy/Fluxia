package com.example.missavapp.data.network

import com.example.missavapp.data.model.Actress
import com.example.missavapp.data.model.VideoCard
import com.example.missavapp.data.model.VideoDetail
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale

private val AV01_VIDEO_ID_PATTERN = Regex("""/(?:[a-z]{2}/)?video/(\d+)""", RegexOption.IGNORE_CASE)
private val AV01_VIDEO_CODE_PATTERN = Regex("""/(?:[a-z]{2}/)?video/\d+/([a-z0-9-]+)""", RegexOption.IGNORE_CASE)
private val AV01_CODE_FALLBACK_PATTERN = Regex("""(?i)\b[a-z0-9]+-[a-z0-9]+(?:-[a-z0-9]+)*\b""")

object Av01Parser {
    fun parseSearchJson(rawJson: String, geoJson: String? = null): List<VideoCard> {
        val root = JSONObject(rawJson)
        val geo = parseGeo(geoJson)
        val videos = root.optJSONArray("videos")
            ?: root.optJSONObject("result")?.optJSONArray("videos")
            ?: root.optJSONObject("data")?.optJSONArray("videos")
            ?: root.optJSONArray("items")
            ?: root.optJSONObject("result")?.optJSONArray("items")
            ?: root.optJSONObject("data")?.optJSONArray("items")
            ?: JSONArray()

        return buildList {
            for (index in 0 until videos.length()) {
                val item = videos.optJSONObject(index) ?: continue
                val code = chooseFirstNonBlank(
                    item.optString("dvd_id"),
                    item.optString("dmm_id"),
                    item.optString("slug")
                )
                if (code.isBlank()) continue

                add(
                    VideoCard(
                        code = code.lowercase(Locale.ROOT),
                        title = chooseFirstNonBlank(
                            pickTranslatedTitle(item.optJSONObject("title_translations")),
                            item.optString("title"),
                            code.uppercase(Locale.ROOT)
                        ),
                        href = buildDetailUrl(item, code),
                        thumbnail = buildThumbnail(item, geo),
                        sourceSite = "AV01"
                    )
                )
            }
        }.distinctBy { it.href.lowercase(Locale.ROOT) }
    }

    fun parseSearchList(html: String, baseUrl: String): List<VideoCard> {
        val doc = Jsoup.parse(html, baseUrl)
        val cards = linkedMapOf<String, VideoCard>()

        doc.select("a[href*=\"/jp/video/\"]").forEach { anchor ->
            val href = resolveUrl(baseUrl, anchor.attr("href"))
            val code = extractCodeFromUrl(href).ifBlank { extractCandidateCode(anchor) }
            if (href.isBlank() || code.isBlank()) return@forEach

            val candidate = VideoCard(
                code = code,
                title = extractCandidateTitle(anchor, code).ifBlank { code.uppercase(Locale.ROOT) },
                href = href,
                thumbnail = extractCandidateThumbnail(anchor, baseUrl),
                sourceSite = "AV01"
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
        detailJson: String,
        similarsJson: String?,
        geoJson: String? = null,
        playlistSource: String? = null
    ): VideoDetail {
        val doc = Jsoup.parse(html, baseUrl)
        val video = unwrapVideoObject(JSONObject(detailJson))
        val geo = parseGeo(geoJson)
        val videoId = extractVideoId(baseUrl).orEmpty()
        val code = chooseFirstNonBlank(
            video.optString("dvd_id"),
            extractCodeFromUrl(baseUrl)
        )

        val title = chooseFirstNonBlank(
            pickTranslatedTitle(video.optJSONObject("title_translations")),
            video.optString("title"),
            doc.selectFirst("h1")?.text()?.trim().orEmpty(),
            doc.title().trim()
        )

        val thumbnails = buildList {
            listOf(
                doc.selectFirst("meta[property=og:image]")?.attr("content"),
                doc.selectFirst("meta[name=twitter:image]")?.attr("content"),
                usableUrlField(video.optString("thumbnail_url")),
                usableUrlField(video.optString("image")),
                usableUrlField(video.optString("poster"))
            ).forEach { value ->
                resolveUrl(baseUrl, value).takeIf { it.isNotBlank() }?.let(::add)
            }

            buildThumbnail(video, geo)?.let(::add)
        }.distinct()

        val recommendations = parseSimilarVideos(similarsJson, code, geo)
        val actresses = parseActresses(video.optJSONArray("actresses"))
        val tags = parseTags(video.optJSONArray("tags"))
        val hlsUrl = playlistSource?.takeIf { it.isPlayablePlaylistSource() }

        return VideoDetail(
            code = code,
            title = title.ifBlank { code.uppercase(Locale.ROOT) },
            href = baseUrl,
            hlsUrl = hlsUrl,
            thumbnails = thumbnails,
            actresses = actresses,
            tags = tags,
            recommendations = recommendations,
            sourceSite = "AV01",
            sourceUrl = baseUrl
        )
    }

    fun extractVideoId(url: String): String? {
        return AV01_VIDEO_ID_PATTERN.find(url)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    fun buildSignedPlaylistUrl(videoId: String, geoJson: String?): String? {
        val geo = parseGeo(geoJson) ?: return null
        val expires = encodeQueryValue(geo.expires)
        val ip = encodeQueryValue(geo.ip)
        val credential = if (geo.tokenV2.isNotBlank()) {
            "token_v2=${encodeQueryValue(geo.tokenV2)}"
        } else {
            "token=${encodeQueryValue(geo.token)}"
        }
        return "https://www.av01.media/api/v1/videos/$videoId/playlist?expires=$expires&ip=$ip&$credential"
    }

    fun extractPlaylistSource(playlistJson: String?): String? {
        if (playlistJson.isNullOrBlank()) return null
        return runCatching {
            val root = JSONObject(playlistJson)
            chooseFirstNonBlank(
                root.optString("src"),
                root.optString("url"),
                root.optString("playlist")
            ).takeIf { it.isPlayablePlaylistSource() }
        }.getOrNull()
    }

    private fun unwrapVideoObject(root: JSONObject): JSONObject {
        val result = root.optJSONObject("result") ?: root
        return result.optJSONObject("video") ?: result
    }

    private fun parseSimilarVideos(raw: String?, currentCode: String, geo: Av01Geo?): List<VideoCard> {
        if (raw.isNullOrBlank()) return emptyList()
        val root = JSONObject(raw)
        val result = root.optJSONObject("result") ?: root
        val videos = result.optJSONArray("videos") ?: result.optJSONArray("items") ?: return emptyList()

        return buildList {
            for (index in 0 until videos.length()) {
                val item = videos.optJSONObject(index) ?: continue
                val code = chooseFirstNonBlank(
                    item.optString("dvd_id"),
                    item.optString("slug")
                )
                if (code.isBlank()) continue
                if (code.equals(currentCode, ignoreCase = true)) continue

                val href = buildDetailUrl(item, code)
                add(
                    VideoCard(
                        code = code,
                        title = chooseFirstNonBlank(
                            pickTranslatedTitle(item.optJSONObject("title_translations")),
                            item.optString("title"),
                            code.uppercase(Locale.ROOT)
                        ),
                        href = href,
                        thumbnail = buildThumbnail(item, geo),
                        sourceSite = "AV01"
                    )
                )
            }
        }.distinctBy { it.href.lowercase(Locale.ROOT) }
    }

    private fun parseActresses(array: JSONArray?): List<Actress> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val name = chooseFirstNonBlank(
                    item.optString("name"),
                    item.optString("name_ja"),
                    item.optString("title")
                )
                if (name.isBlank()) continue
                val path = chooseFirstNonBlank(
                    item.optString("url"),
                    item.optString("path")
                )
                add(Actress(name = name, url = resolveUrl("https://www.av01.media", path)))
            }
        }.distinctBy { it.name }
    }

    private fun parseTags(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.opt(index)
                val value = when (item) {
                    is JSONObject -> chooseFirstNonBlank(
                        item.optString("name"),
                        item.optString("name_ja"),
                        item.optString("title")
                    )
                    else -> item?.toString().orEmpty()
                }
                if (value.isNotBlank()) {
                    add(value.trim())
                }
            }
        }.distinct()
    }

    private fun buildDetailUrl(item: JSONObject, fallbackCode: String): String {
        val direct = chooseFirstNonBlank(
            item.optString("url"),
            item.optString("path"),
            item.optString("href")
        )
        if (direct.isNotBlank()) return resolveUrl("https://www.av01.media", direct)

        val id = item.optLong("id")
        val slug = chooseFirstNonBlank(item.optString("slug"), fallbackCode.lowercase(Locale.ROOT))
        return if (id > 0) {
            "https://www.av01.media/jp/video/$id/$slug"
        } else {
            "https://www.av01.media/jp/search?q=${java.net.URLEncoder.encode(fallbackCode, Charsets.UTF_8.name()).replace("+", "%20")}"
        }
    }

    private fun buildThumbnail(item: JSONObject, geo: Av01Geo?): String? {
        val direct = chooseFirstNonBlank(
            usableUrlField(item.optString("thumbnail_url")),
            usableUrlField(item.optString("image")),
            usableUrlField(item.optString("poster"))
        )
        if (direct.isNotBlank()) return resolveUrl("https://www.av01.media", direct)

        val id = item.optLong("id").takeIf { it > 0 } ?: return null
        if (geo == null) return null

        val r2Status = item.optString("r2_status").trim().uppercase(Locale.ROOT)
        return if (geo.r2Cover && geo.tokenV2.isNotBlank() && (r2Status == "COVER_ONLY" || r2Status == "COMPLETE")) {
            "https://files.iw01.xyz/covers/$id/800.webp?token_v2=${geo.tokenV2}&expires=${geo.expires}&ip=${geo.ip}"
        } else if (geo.token.isNotBlank()) {
            val host = if (geo.continent.equals("EU", ignoreCase = true)) "https://static2.av01.tv" else "https://static.av01.tv"
            "$host/media/videos/tmb/$id/1.jpg/format=webp/wlv=800?t=${geo.token}&e=${geo.expires}&ip=${geo.ip}"
        } else {
            null
        }
    }

    private fun pickTranslatedTitle(translations: JSONObject?): String {
        if (translations == null) return ""
        return chooseFirstNonBlank(
            translations.optString("jp"),
            translations.optString("ja"),
            translations.optString("zh"),
            translations.optString("cn"),
            translations.optString("en")
        )
    }

    private fun extractCodeFromUrl(url: String): String {
        return AV01_VIDEO_CODE_PATTERN.find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.lowercase(Locale.ROOT)
            .orEmpty()
    }

    private fun extractCandidateCode(anchor: Element): String {
        val text = buildString {
            append(anchor.text())
            anchor.parents().take(3).forEach { parent ->
                append(' ')
                append(parent.text())
            }
        }
        return AV01_CODE_FALLBACK_PATTERN.find(text)?.value?.lowercase(Locale.ROOT).orEmpty()
    }

    private fun extractCandidateTitle(anchor: Element, code: String): String {
        val candidates = linkedSetOf<String>()

        fun collect(value: String?) {
            val normalized = value.orEmpty().replace(Regex("\\s+"), " ").trim()
            if (normalized.isNotBlank()) {
                candidates.add(normalized)
            }
        }

        collect(anchor.attr("title"))
        collect(anchor.attr("aria-label"))
        collect(anchor.selectFirst("h1, h2, h3, h4, h5, h6")?.text())
        collect(anchor.selectFirst("img[alt]")?.attr("alt"))
        collect(anchor.text())

        anchor.parents().take(3).forEach { parent ->
            collect(parent.selectFirst("h1, h2, h3, h4, h5, h6")?.text())
        }

        return candidates.firstOrNull { candidate ->
            candidate.isNotBlank() && !candidate.equals(code, ignoreCase = true) && candidate.length > code.length
        }.orEmpty()
    }

    private fun extractCandidateThumbnail(anchor: Element, baseUrl: String): String? {
        val candidates = linkedSetOf<String>()

        fun collect(img: Element?) {
            if (img == null) return
            listOf(
                img.absUrl("src"),
                img.absUrl("data-src"),
                img.absUrl("srcset").substringBefore(' '),
                resolveUrl(baseUrl, img.attr("src")),
                resolveUrl(baseUrl, img.attr("data-src"))
            ).forEach { value ->
                if (value.isNotBlank() && value.startsWith("http", ignoreCase = true)) {
                    candidates.add(value)
                }
            }
        }

        collect(anchor.selectFirst("img"))
        anchor.parents().take(3).forEach { parent ->
            parent.select("img").take(3).forEach(::collect)
        }
        return candidates.firstOrNull()
    }

    private fun shouldReplaceCard(current: VideoCard, candidate: VideoCard): Boolean {
        val currentScore = (if (current.thumbnail.isNullOrBlank()) 0 else 1) + current.title.length
        val candidateScore = (if (candidate.thumbnail.isNullOrBlank()) 0 else 1) + candidate.title.length
        return candidateScore > currentScore
    }

    private fun chooseFirstNonBlank(vararg values: String): String {
        return values.firstOrNull { it.trim().isNotBlank() }.orEmpty().trim()
    }

    private fun usableUrlField(value: String?): String {
        val trimmed = value.orEmpty().trim()
        if (trimmed.isBlank()) return ""
        if (trimmed.equals("true", ignoreCase = true) || trimmed.equals("false", ignoreCase = true)) return ""
        return trimmed
    }

    private fun parseGeo(raw: String?): Av01Geo? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val obj = JSONObject(raw)
            Av01Geo(
                token = obj.optString("token"),
                tokenV2 = obj.optString("token_v2"),
                expires = obj.optString("expires"),
                ip = obj.optString("ip"),
                continent = obj.optString("continent"),
                r2Cover = obj.optBoolean("r2_cover", false)
            )
        }.getOrNull()?.takeIf {
            it.expires.isNotBlank() && it.ip.isNotBlank() && (it.token.isNotBlank() || it.tokenV2.isNotBlank())
        }
    }

    private fun resolveUrl(baseUrl: String, href: String?): String {
        val trimmed = href.orEmpty().trim()
        if (trimmed.isBlank()) return ""
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        if (trimmed.startsWith("//")) return "https:$trimmed"

        val normalizedBase = runCatching {
            URI(baseUrl).run {
                if (scheme.isNullOrBlank() || host.isNullOrBlank()) return@run "https://www.av01.media"
                "${scheme}://${host}"
            }
        }.getOrDefault("https://www.av01.media")

        return if (trimmed.startsWith("/")) {
            normalizedBase + trimmed
        } else {
            "$normalizedBase/$trimmed"
        }
    }

    private fun String.isPlayablePlaylistSource(): Boolean {
        return startsWith("data:application/x-mpegurl", ignoreCase = true) ||
            startsWith("data:application/vnd.apple.mpegurl", ignoreCase = true) ||
            startsWith("data:audio/mpegurl", ignoreCase = true) ||
            contains(".m3u8", ignoreCase = true)
    }

    private fun encodeQueryValue(value: String): String {
        return java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
    }
}

private data class Av01Geo(
    val token: String,
    val tokenV2: String,
    val expires: String,
    val ip: String,
    val continent: String,
    val r2Cover: Boolean
)
