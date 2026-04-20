package com.example.missavapp.data.network

import com.example.missavapp.data.model.Actress
import com.example.missavapp.data.model.VideoCard
import com.example.missavapp.data.model.VideoDetail
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Locale

private val JABLE_VIDEO_URL = Regex("(?:https?:\\/\\/|https://)?jable\\.tv/videos/([a-z0-9-]+)", RegexOption.IGNORE_CASE)
private val HLS_URL = Regex("var\\s+hlsUrl\\s*=\\s*['\\\"]([^'\\\"]+)", RegexOption.IGNORE_CASE)

object JableParser {
    fun parseVideoList(html: String, baseUrl: String): List<VideoCard> {
        val doc = Jsoup.parse(html, baseUrl)
        val cards = mutableMapOf<String, VideoCard>()

        doc.select("a[href*=\"/videos/\"]").forEach { a ->
            val href = a.absUrl("href")
            if (!href.contains("/videos/")) return@forEach
            val code = extractCodeFromUrl(href)
            if (code.isBlank()) return@forEach
            val title = extractCandidateTitle(a, code)
            val thumb = extractCandidateThumbnail(a)
            val candidate = VideoCard(
                code = code,
                title = title.ifBlank { code.uppercase(Locale.ROOT) },
                href = href,
                thumbnail = thumb,
                sourceSite = "Jable.tv"
            )
            val key = code.lowercase(Locale.ROOT)
            val existing = cards[key]
            if (existing == null || shouldReplaceCard(existing, candidate)) {
                cards[key] = candidate
            }
        }

        return cards.values.toList()
    }

    fun parseVideoDetail(html: String, baseUrl: String): VideoDetail {
        val doc: Document = Jsoup.parse(html, baseUrl)
        val code = extractCodeFromUrl(baseUrl)

        val title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
            .ifBlank {
                doc.selectFirst("meta[property=og:title]")?.attr("content")
                    ?.trim()
                    .orEmpty()
            }.ifBlank {
                doc.title().trim()
            }

        val actresses = doc.select("a[href*=\"/models/\"]").mapNotNull { a ->
            val name = a.text().trim()
            val href = a.absUrl("href")
            if (name.isBlank() || href.isBlank()) null
            else Actress(name = name, url = href)
        }.distinctBy { it.name }
        val tags = parseTags(doc)
        val recommendations = parseVideoList(html, baseUrl)
            .filterNot { sameVideo(it, code, baseUrl) }
            .distinctBy { it.href.lowercase(Locale.ROOT) }
            .take(12)

        return VideoDetail(
            code = code,
            title = title,
            href = baseUrl,
            hlsUrl = parseHlsUrl(html),
            thumbnails = parseThumbnailCandidates(doc),
            sourceSite = "Jable.tv",
            sourceUrl = baseUrl,
            actresses = actresses,
            tags = tags,
            recommendations = recommendations
        )
    }

    fun parseSearchEndpoint(query: String): String {
        return "/search/?q=$query"
    }

    private fun parseHlsUrl(html: String): String? {
        return HLS_URL.find(html)?.groupValues?.getOrNull(1)
    }

    private fun parseThumbnailCandidates(doc: Document): List<String> {
        return buildList {
            listOf(
                doc.selectFirst("meta[property=og:image]")?.attr("content"),
                doc.selectFirst("meta[name=twitter:image]")?.attr("content"),
                doc.selectFirst("video")?.attr("poster"),
                doc.selectFirst("img[src]")?.absUrl("src"),
                doc.selectFirst("img[data-src]")?.attr("data-src")
            ).forEach { value ->
                if (!value.isNullOrBlank()) add(value)
            }
        }.filter { it.isNotBlank() }.distinct()
    }

    private fun extractCandidateTitle(anchor: Element, code: String): String {
        val candidates = linkedSetOf<String>()
        fun collect(value: String?) {
            val normalized = value.orEmpty()
                .replace(Regex("\\s+"), " ")
                .trim()
            if (normalized.isNotBlank()) {
                candidates.add(normalized)
            }
        }

        collect(anchor.attr("title"))
        collect(anchor.text())

        anchor.parents().take(5).forEach { parent ->
            collect(parent.selectFirst("h1, h2, h3, h4, h5, h6, .title")?.text())
            parent.select("a[href*=\"/videos/\"]").forEach { sibling ->
                if (sibling.absUrl("href") == anchor.absUrl("href")) {
                    collect(sibling.attr("title"))
                    collect(sibling.text())
                }
            }
        }

        return candidates.firstOrNull { isMeaningfulVideoTitle(it, code) }.orEmpty()
    }

    private fun extractCandidateThumbnail(anchor: Element): String? {
        val candidates = linkedSetOf<String>()

        fun collectImage(img: Element?) {
            if (img == null) return
            listOf(
                img.absUrl("data-src"),
                img.absUrl("src"),
                img.attr("data-src"),
                img.attr("src")
            ).forEach { value ->
                if (isMeaningfulThumbnail(value)) {
                    candidates.add(value)
                }
            }
        }

        collectImage(anchor.selectFirst("img"))
        anchor.parents().take(5).forEach { parent ->
            parent.select("img").take(4).forEach(::collectImage)
        }

        return candidates.firstOrNull()
    }

    private fun parseTags(doc: Document): List<String> {
        return doc.select("a[href*=\"/categories/\"], a[href*=\"/tags/\"], .header-tags a[href]")
            .map { it.text().trim() }
            .filter(::isMeaningfulTag)
            .distinct()
    }

    private fun isMeaningfulTag(value: String): Boolean {
        val normalized = value.trim()
        if (normalized.isBlank() || normalized.length > 24) return false
        return normalized !in setOf("登入", "登录", "Login", "更多", "留言", "問題回報")
    }

    private fun sameVideo(card: VideoCard, code: String, url: String): Boolean {
        if (card.href.equals(url, ignoreCase = true)) return true
        return code.isNotBlank() && card.code.equals(code, ignoreCase = true)
    }

    private fun shouldReplaceCard(current: VideoCard, candidate: VideoCard): Boolean {
        val currentScore = cardQualityScore(current)
        val candidateScore = cardQualityScore(candidate)
        if (candidateScore != currentScore) {
            return candidateScore > currentScore
        }
        return candidate.title.length > current.title.length
    }

    private fun cardQualityScore(card: VideoCard): Int {
        var score = 0
        if (isMeaningfulVideoTitle(card.title, card.code)) score += 2
        if (!card.thumbnail.isNullOrBlank()) score += 1
        return score
    }

    private fun isMeaningfulVideoTitle(value: String, code: String): Boolean {
        val normalized = value.replace(Regex("\\s+"), " ").trim()
        if (normalized.isBlank()) return false
        if (normalized.equals(code, ignoreCase = true)) return false
        if (normalized.matches(Regex("\\d{1,2}:\\d{2}(?::\\d{2})?"))) return false
        if (normalized.length <= code.length + 1) return false
        return true
    }

    private fun isMeaningfulThumbnail(value: String?): Boolean {
        val normalized = value.orEmpty().trim()
        if (normalized.isBlank()) return false
        if (normalized.startsWith("data:", ignoreCase = true)) return false
        if (normalized.endsWith(".svg", ignoreCase = true)) return false
        return normalized.startsWith("http://") || normalized.startsWith("https://")
    }

    private fun extractCodeFromUrl(url: String): String {
        return JABLE_VIDEO_URL.find(url)?.groupValues?.getOrNull(1)?.lowercase(Locale.ROOT).orEmpty()
    }
}
