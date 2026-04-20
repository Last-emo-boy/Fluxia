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

private val AV01_VIDEO_ID_PATTERN = Regex("""/jp/video/(\d+)""", RegexOption.IGNORE_CASE)
private val AV01_VIDEO_CODE_PATTERN = Regex("""/jp/video/\d+/([a-z0-9-]+)""", RegexOption.IGNORE_CASE)
private val AV01_CODE_FALLBACK_PATTERN = Regex("""(?i)\b[a-z0-9]+-[a-z0-9]+(?:-[a-z0-9]+)*\b""")

object Av01Parser {
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
        similarsJson: String?
    ): VideoDetail {
        val doc = Jsoup.parse(html, baseUrl)
        val video = unwrapVideoObject(JSONObject(detailJson))
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
                video.optString("thumbnail_url"),
                video.optString("image"),
                video.optString("poster")
            ).forEach { value ->
                resolveUrl(baseUrl, value).takeIf { it.isNotBlank() }?.let(::add)
            }

            val storageBase = video.optString("storage_base").trim().trimEnd('/')
            if (storageBase.isNotBlank()) {
                listOf(
                    "$storageBase/800.webp",
                    "$storageBase/cover.webp",
                    "$storageBase/cover.jpg",
                    "$storageBase/poster.webp"
                ).forEach(::add)
            }
        }.distinct()

        val recommendations = parseSimilarVideos(similarsJson, code)
        val actresses = parseActresses(video.optJSONArray("actresses"))
        val tags = parseTags(video.optJSONArray("tags"))
        val hlsUrl = videoId.takeIf { it.isNotBlank() }?.let {
            "https://www.av01.media/api/v1/videos/$it/manifest/master.m3u8"
        }

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

    private fun unwrapVideoObject(root: JSONObject): JSONObject {
        val result = root.optJSONObject("result") ?: root
        return result.optJSONObject("video") ?: result
    }

    private fun parseSimilarVideos(raw: String?, currentCode: String): List<VideoCard> {
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
                        thumbnail = buildThumbnail(item),
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

    private fun buildThumbnail(item: JSONObject): String? {
        val direct = chooseFirstNonBlank(
            item.optString("thumbnail_url"),
            item.optString("image"),
            item.optString("poster")
        )
        if (direct.isNotBlank()) return resolveUrl("https://www.av01.media", direct)

        val storageBase = item.optString("storage_base").trim().trimEnd('/')
        if (storageBase.isBlank()) return null
        return listOf(
            "$storageBase/800.webp",
            "$storageBase/cover.webp",
            "$storageBase/cover.jpg"
        ).firstOrNull()
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
}
