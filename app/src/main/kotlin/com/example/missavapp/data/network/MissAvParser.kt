package com.example.missavapp.data.network

import com.example.missavapp.data.model.Actress
import com.example.missavapp.data.model.VideoCard
import com.example.missavapp.data.model.VideoDetail
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale
import java.net.URI

private val VIDEO_PATH_PATTERN =
    Regex("(?i)(?:^|/)(?:(?:[a-z]{2}/)?(?:dm\\d+/)?)?([a-z0-9]+-[a-z0-9]+(?:-[a-z0-9]+)*)(?:/|\\?|#|$)")

private val VIDEO_CODE_PATTERN = Regex("(?i)[a-z0-9]+-[a-z0-9]+(?:-[a-z0-9]+)*")

private val BLOCKED_VIDEO_SLUGS = setOf(
    "new",
    "release",
    "chinese-subtitle",
    "uncensored-leak",
    "actresses",
    "genres",
    "makers",
    "directors",
    "labels",
    "playlist",
    "search"
)

private val SEEK_PATTERN = Regex("(?:https?:\\/\\/|https://)(?:nineyu|surrit)\\.com(?:/|\\/)\\?([a-z0-9-]{36})(?:/|/)seek(?:/|)", RegexOption.IGNORE_CASE)
private val DIRECT_MEDIA_URL_PATTERN =
    Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:\?[^"'\\s<>]*)?""", RegexOption.IGNORE_CASE)
private val MEDIA_ASSIGN_PATTERN =
    Regex("""(?:hlsUrl|playlist(?:Url)?|play(?:list|back)?Url|streamUrl|videoUrl|file|src|source(?:\d+)?)\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE)
private val SURRIT_PATH_PATTERN =
    Regex("""(?:https?://)?(?:nineyu|surrit)\.com/([a-z0-9-]{36})/(?:playlist|master)\.m3u8""", RegexOption.IGNORE_CASE)
private val SURRIT_SEEK_PATTERN =
    Regex("""(?:https?://)?(?:nineyu|surrit)\.com(?:/\?|/\\\?|/\?=|\?)([a-z0-9-]{36})(?:/|\\?/)?seek""", RegexOption.IGNORE_CASE)
private val SURRIT_ID_PATTERN = Regex("""[a-z0-9-]{36}""", RegexOption.IGNORE_CASE)
private val PACKER_PATTERN =
    Regex(
        """eval\(function\(p,a,c,k,e,(?:r|d)\)\{[\s\S]+?\}\(\s*'((?:\\.|[^'])*)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'((?:\\.|[^'])*)'\.split\('\|'\)\s*,\s*\d+\s*,\s*\{\}\s*\)\)""",
        RegexOption.IGNORE_CASE
    )

private val SAVE_API_PATTERN = Regex("(?:https?:\\/\\/|/)?api/items/[a-z0-9]+/save", RegexOption.IGNORE_CASE)
private val VIEW_API_PATTERN = Regex("(?:https?:\\/\\/|/)?api/items/[a-z0-9]+/view", RegexOption.IGNORE_CASE)

object MissAvParser {
    fun parseVideoList(html: String, baseUrl: String): List<VideoCard> {
        val doc = Jsoup.parse(html, baseUrl)
        val cards = mutableMapOf<String, VideoCard>()

        doc.select("a[href]").forEach { a ->
            val href = a.absUrl("href")
            if (!isVideoUrl(href)) return@forEach

            val code = extractCodeFromUrl(href)
            if (code.isBlank()) return@forEach

            val title = extractCandidateTitle(a, code)
            val thumbnail = extractCandidateThumbnail(a, baseUrl)
            val candidate = VideoCard(
                code = code,
                title = title.ifBlank { code.uppercase(Locale.ROOT) },
                href = href,
                thumbnail = thumbnail,
                sourceSite = "MissAV"
            )

            val existing = cards[code]
            if (existing == null || shouldReplaceCard(existing, candidate)) {
                cards[code] = candidate
            }
        }

        return cards.values.toList()
    }

    fun parseSearchList(raw: String, baseUrl: String): List<VideoCard> {
        val rawTrimmed = raw.trim()
        if (rawTrimmed.startsWith("{") || rawTrimmed.startsWith("[")) {
            val jsonParsed = parseSearchJson(rawTrimmed, baseUrl)
            if (jsonParsed.isNotEmpty()) return jsonParsed
        }

        val embeddedParsed = parseSearchJsonFromEmbeddedState(rawTrimmed, baseUrl)
        if (embeddedParsed.isNotEmpty()) return embeddedParsed

        return parseVideoList(rawTrimmed, baseUrl)
    }

    fun parseVideoDetail(html: String, baseUrl: String): VideoDetail {
        val doc: Document = Jsoup.parse(html, baseUrl)
        val code = extractCodeFromUrl(baseUrl)

        val title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
            .ifBlank {
                doc.selectFirst("meta[property=og:title]")?.attr("content")?.replace(Regex("\\s*-\\s*MissAV[\\s\\S]*$", RegexOption.IGNORE_CASE), "")
                    ?.trim()
                    .orEmpty()
            }.ifBlank {
                doc.title().replace(Regex("\\s*-\\s*MissAV[\\s\\S]*$", RegexOption.IGNORE_CASE), "").trim()
            }

        val hlsUrl = parseHlsUrl(html, baseUrl)
        val actresses = parseActresses(doc)
        val saveApi = parseSaveApi(html)
        val thumbnails = parseThumbnailCandidates(doc, html, baseUrl)
        val tags = parseTags(doc, baseUrl)
        val recommendations = parseVideoList(html, baseUrl)
            .filterNot { sameVideo(it, code, baseUrl) }
            .distinctBy { it.href.lowercase(Locale.ROOT) }

        return VideoDetail(
            code = code,
            title = title,
            href = baseUrl,
            hlsUrl = hlsUrl,
            thumbnails = thumbnails,
            sourceSite = "MissAV",
            sourceUrl = baseUrl,
            actresses = actresses,
            tags = tags,
            recommendations = recommendations,
            saveApiUrl = saveApi,
        )
    }

    fun isCloudflareChallenge(html: String): Boolean {
        return html.contains("Just a moment", ignoreCase = true)
    }

    fun extractCodeFromUrl(url: String): String {
        if (url.isBlank()) return ""

        val path = runCatching {
            URI(url).path
        }.getOrNull()?.let { it.trimEnd('/') } ?: url.substringAfter("://", "").substringAfter('/').substringBefore('?')

        path.split('/').filter { it.isNotBlank() }.forEach { rawSegment ->
            val segment = rawSegment
                .substringBefore('?')
                .substringBefore('#')
                .lowercase(Locale.ROOT)
                .replace(".html", "")
                .trim('-')
                .replace(Regex("-(uncensored-leak|ch-sub|chinese-subtitle|leak|uncensored)$", RegexOption.IGNORE_CASE), "")
            if (isLikelyVideoCode(segment)) {
                return segment
            }
        }

        return VIDEO_CODE_PATTERN.find(url.lowercase(Locale.ROOT))
            ?.value
            ?.replace(Regex("-(uncensored-leak|ch-sub|chinese-subtitle|leak|uncensored)$", RegexOption.IGNORE_CASE), "")
            ?: ""
    }

    private fun parseSearchJsonFromEmbeddedState(raw: String, baseUrl: String): List<VideoCard> {
        val candidates = mutableListOf<String>()

        val scriptDocs = Jsoup.parse(raw).select("script")
        scriptDocs.forEach { script ->
            val type = script.attr("type").lowercase(Locale.ROOT)
            val text = script.data().trim()
            if (text.isBlank()) return@forEach

            if (text.contains("__NUXT__", ignoreCase = true)) {
                val keyIndex = text.indexOf("__NUXT__", ignoreCase = true)
                val assign = text.indexOf("=", keyIndex)
                if (assign > -1) {
                    val brace = text.indexOf("{", assign)
                    if (brace > -1) {
                        val body = text.substring(brace).trim().removeSuffix(";").trim()
                        if (body.isNotBlank()) {
                            candidates.add(body)
                        }
                    }
                }
            }

            if (type == "application/ld+json" && (text.startsWith("{") || text.startsWith("["))) {
                candidates.add(text)
            }
        }

        if (candidates.isEmpty()) {
            return emptyList()
        }

        for (candidate in candidates) {
            val parsed = parseSearchJson(candidate, baseUrl)
            if (parsed.isNotEmpty()) {
                return parsed
            }

            val schemaParsed = parseSchemaJson(candidate, baseUrl)
            if (schemaParsed.isNotEmpty()) {
                return schemaParsed
            }
        }

        return emptyList()
    }

    private fun parseHlsUrl(html: String, baseUrl: String): String? {
        val normalized = html
            .replace("\\u002F", "/")
            .replace("\\u003A", ":")
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("&amp;", "&")
        val decoded = appendPackedScriptPayloads(normalized)

        MEDIA_ASSIGN_PATTERN.findAll(decoded).forEach { match ->
            normalizeMediaCandidate(baseUrl, match.groupValues.getOrNull(1).orEmpty())?.let { return it }
        }

        DIRECT_MEDIA_URL_PATTERN.findAll(decoded).forEach { match ->
            normalizeMediaCandidate(baseUrl, match.value)?.let { return it }
        }

        SURRIT_PATH_PATTERN.find(decoded)?.groupValues?.getOrNull(1)?.let { id ->
            return "https://surrit.com/$id/playlist.m3u8"
        }

        SURRIT_SEEK_PATTERN.find(decoded)?.groupValues?.getOrNull(1)?.let { id ->
            return "https://surrit.com/$id/playlist.m3u8"
        }

        SEEK_PATTERN.find(html)?.groupValues?.getOrNull(1)?.let { id ->
            return "https://surrit.com/$id/playlist.m3u8"
        }

        return extractSeekBasedMediaUrl(decoded)
    }

    private fun extractSeekBasedMediaUrl(normalizedHtml: String): String? {
        val seekHints = listOf("playlist.m3u8", "master.m3u8", "seek", "hls", "stream")
        seekHints.forEach { hint ->
            var fromIndex = 0
            while (true) {
                val idx = normalizedHtml.indexOf(hint, fromIndex, ignoreCase = true)
                if (idx < 0) break
                val start = (idx - 120).coerceAtLeast(0)
                val end = (idx + 120).coerceAtMost(normalizedHtml.length)
                val window = normalizedHtml.substring(start, end)
                SURRIT_ID_PATTERN.find(window)?.value?.let { id ->
                    return "https://surrit.com/$id/playlist.m3u8"
                }
                fromIndex = idx + hint.length
            }
        }
        return null
    }

    private fun normalizeMediaCandidate(baseUrl: String, raw: String): String? {
        val candidate = raw.trim()
            .trim('"', '\'')
            .replace("\\u002F", "/")
            .replace("\\u003A", ":")
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("&amp;", "&")

        if (candidate.isBlank()) return null

        SURRIT_SEEK_PATTERN.find(candidate)?.groupValues?.getOrNull(1)?.let { id ->
            return "https://surrit.com/$id/playlist.m3u8"
        }

        if (candidate.contains("/seek", ignoreCase = true)) {
            SURRIT_ID_PATTERN.find(candidate)?.value?.let { id ->
                return "https://surrit.com/$id/playlist.m3u8"
            }
        }

        val resolved = when {
            candidate.startsWith("http://") || candidate.startsWith("https://") -> candidate
            candidate.startsWith("//") -> "https:$candidate"
            candidate.startsWith("/") -> resolveUrl(baseUrl, candidate)
            candidate.contains(".m3u8", ignoreCase = true) || candidate.contains(".mp4", ignoreCase = true) -> resolveUrl(baseUrl, candidate)
            else -> return null
        }

        return resolved.takeIf {
            it.contains(".m3u8", ignoreCase = true) || it.contains(".mp4", ignoreCase = true)
        }
    }

    private fun appendPackedScriptPayloads(html: String): String {
        val decodedBlocks = PACKER_PATTERN.findAll(html)
            .mapNotNull(::unpackDeanEdwardsPacker)
            .filter { it.isNotBlank() }
            .toList()
        if (decodedBlocks.isEmpty()) return html

        return buildString {
            append(html)
            decodedBlocks.forEach { block ->
                append('\n')
                append(block)
            }
        }
    }

    private fun unpackDeanEdwardsPacker(match: MatchResult): String? {
        val payload = unescapePackedLiteral(match.groupValues.getOrNull(1).orEmpty())
        val radix = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return null
        val count = match.groupValues.getOrNull(3)?.toIntOrNull() ?: return null
        val tokens = unescapePackedLiteral(match.groupValues.getOrNull(4).orEmpty()).split('|')
        if (payload.isBlank() || radix !in 2..62 || count <= 0 || tokens.isEmpty()) return null

        val dictionary = buildMap<String, String> {
            val limit = minOf(count, tokens.size)
            for (index in 0 until limit) {
                val value = tokens[index]
                if (value.isNotBlank()) {
                    put(encodePackedIndex(index, radix), value)
                }
            }
        }
        if (dictionary.isEmpty()) return payload

        return Regex("""\b\w+\b""").replace(payload) { token ->
            dictionary[token.value] ?: token.value
        }
    }

    private fun unescapePackedLiteral(input: String): String {
        if (input.isBlank()) return input
        val output = StringBuilder(input.length)
        var index = 0
        while (index < input.length) {
            val current = input[index]
            if (current == '\\' && index + 1 < input.length) {
                when (val next = input[index + 1]) {
                    '\\' -> {
                        output.append('\\')
                        index += 2
                    }
                    '\'' -> {
                        output.append('\'')
                        index += 2
                    }
                    '"' -> {
                        output.append('"')
                        index += 2
                    }
                    'n' -> {
                        output.append('\n')
                        index += 2
                    }
                    'r' -> {
                        output.append('\r')
                        index += 2
                    }
                    't' -> {
                        output.append('\t')
                        index += 2
                    }
                    'u' -> {
                        val end = (index + 6).coerceAtMost(input.length)
                        val hex = input.substring(index + 2, end)
                        val char = hex.toIntOrNull(16)?.toChar()
                        if (char != null && hex.length == 4) {
                            output.append(char)
                            index += 6
                        } else {
                            output.append(next)
                            index += 2
                        }
                    }
                    else -> {
                        output.append(next)
                        index += 2
                    }
                }
            } else {
                output.append(current)
                index += 1
            }
        }
        return output.toString()
    }

    private fun encodePackedIndex(value: Int, radix: Int): String {
        if (value == 0) return "0"
        val alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        require(radix in 2..alphabet.length)

        var remaining = value
        val encoded = StringBuilder()
        while (remaining > 0) {
            encoded.append(alphabet[remaining % radix])
            remaining /= radix
        }
        return encoded.reverse().toString()
    }

    fun parseSaveApi(html: String): String? {
        val normalized = html.replace("\\/", "/").replace("&amp;", "&")
        val match = SAVE_API_PATTERN.find(normalized)
        return match?.value?.let {
            if (it.startsWith("http://") || it.startsWith("https://")) {
                it
            } else {
                "https://missav.ws$it"
            }
        }
    }

    fun parseViewApi(html: String): String? {
        val normalized = html.replace("\\/", "/").replace("&amp;", "&")
        return VIEW_API_PATTERN.find(normalized)?.value
    }

    private fun parseActresses(doc: Document): List<Actress> {
        val actresses = mutableListOf<Actress>()

        doc.select("a[href*=\"/actresses/\"]").forEach { a ->
            val name = a.text().trim()
            val url = a.absUrl("href")
            if (name.isNotBlank() && url.isNotBlank()) {
                actresses.add(Actress(name, url))
            }
        }

        if (actresses.isNotEmpty()) {
            return actresses.distinctBy { it.name }
        }

        doc.select("div.text-secondary").forEach { block ->
            val first = block.selectFirst("span")?.text()?.trim() ?: return@forEach
            if (first.contains("女优", ignoreCase = true) || first.contains("actress", ignoreCase = true)) {
                block.select("a.text-nord13.font-medium").forEach { a ->
                    val name = a.text().trim()
                    val url = a.absUrl("href")
                    if (name.isNotBlank() && url.isNotBlank()) {
                        actresses.add(Actress(name, url))
                    }
                }
            }
        }

        return actresses.distinctBy { it.name }
    }

    private fun parseTags(doc: Document, baseUrl: String): List<String> {
        val allowedSegments = listOf("/genres/", "/makers/", "/labels/", "/series/", "/directors/")
        return doc.select("a[href]").mapNotNull { anchor ->
            val text = anchor.text().trim()
            val href = resolveUrl(baseUrl, anchor.attr("href"))
            if (text.isBlank()) return@mapNotNull null
            if (allowedSegments.none { href.contains(it, ignoreCase = true) }) return@mapNotNull null
            text
        }.filter(::isMeaningfulTag).distinct()
    }

    private fun isMeaningfulTag(value: String): Boolean {
        val normalized = value.trim()
        if (normalized.isBlank() || normalized.length > 24) return false
        return normalized !in setOf("MissAV", "女优", "Actress", "演员", "女優")
    }

    private fun parseThumbnailCandidates(doc: Document, html: String, baseUrl: String): List<String> {
        val urls = buildList {
            listOf(
                doc.selectFirst("meta[property=og:image]")?.attr("content"),
                doc.selectFirst("meta[name=twitter:image]")?.attr("content"),
                doc.selectFirst("video")?.attr("poster"),
                doc.selectFirst("img[src]")?.absUrl("src"),
                doc.selectFirst("img[data-src]")?.attr("data-src")
            ).forEach { value ->
                if (!value.isNullOrBlank()) add(resolveUrl(baseUrl, value))
            }

            Regex("""https?:\/\/[^"' ]+\.(?:jpg|jpeg|png|webp)""", RegexOption.IGNORE_CASE)
                .findAll(html)
                .take(4)
                .forEach { add(it.value) }
        }
        return urls.filter { it.isNotBlank() }.distinct()
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
            collect(parent.selectFirst("h1, h2, h3, h4, h5, h6, .title, .my-2")?.text())
            parent.select("a[href]").forEach { sibling ->
                if (sibling.absUrl("href") == anchor.absUrl("href")) {
                    collect(sibling.attr("title"))
                    collect(sibling.text())
                }
            }
        }

        return candidates.firstOrNull { isMeaningfulVideoTitle(it, code) }.orEmpty()
    }

    private fun extractCandidateThumbnail(anchor: Element, baseUrl: String): String? {
        val candidates = linkedSetOf<String>()

        fun collectImage(img: Element?) {
            if (img == null) return
            listOf(
                img.absUrl("data-src"),
                resolveUrl(baseUrl, img.attr("data-src")),
                img.absUrl("data-original"),
                resolveUrl(baseUrl, img.attr("data-original")),
                img.absUrl("src"),
                resolveUrl(baseUrl, img.attr("src"))
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

    private fun isVideoUrl(href: String): Boolean {
        val slug = extractCodeFromUrl(href)
        if (slug.isBlank()) return false
        if (BLOCKED_VIDEO_SLUGS.contains(slug)) return false
        return isLikelyVideoCode(slug)
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
        return normalized.any { it.isLetter() || Character.UnicodeScript.of(it.code) != Character.UnicodeScript.LATIN }
    }

    private fun isMeaningfulThumbnail(value: String?): Boolean {
        val normalized = value.orEmpty().trim()
        if (normalized.isBlank()) return false
        if (normalized.startsWith("data:", ignoreCase = true)) return false
        if (normalized.endsWith(".svg", ignoreCase = true)) return false
        return normalized.startsWith("http://") || normalized.startsWith("https://")
    }

    private fun isLikelyVideoCode(value: String): Boolean {
        val code = value.trim().lowercase(Locale.ROOT)
        if (code.isBlank() || BLOCKED_VIDEO_SLUGS.contains(code)) return false
        if (!VIDEO_CODE_PATTERN.matches(code)) return false
        return code.any { it.isDigit() }
    }

    private fun sameVideo(card: VideoCard, code: String, url: String): Boolean {
        if (card.href.equals(url, ignoreCase = true)) return true
        return code.isNotBlank() && card.code.equals(code, ignoreCase = true)
    }

    private fun parseSearchJson(raw: String, baseUrl: String): List<VideoCard> {
        val root = runCatching { JSONTokener(raw).nextValue() }.getOrNull() ?: return emptyList()
        val array = extractVideoArray(root) ?: return emptyList()

        val cards = mutableMapOf<String, VideoCard>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val normalized = unwrapSearchItem(item) ?: item
            val href = chooseFirstNonBlank(
                normalized.optString("url"),
                normalized.optString("link"),
                normalized.optString("href"),
                normalized.optString("permalink"),
                normalized.optString("path"),
                normalized.optString("uri"),
                normalized.optString("url_to_watch"),
                normalized.optString("detail_url")
            )
            if (href.isBlank()) continue

            val code = chooseFirstNonBlank(
                normalized.optString("code"),
                normalized.optString("slug"),
                normalized.optString("id")
            ).ifBlank { extractCodeFromUrl(href) }
            val title = chooseFirstNonBlank(
                normalized.optString("title"),
                normalized.optString("name"),
                normalized.optString("name_ch"),
                normalized.optString("name_en"),
                normalized.optString("subject"),
                normalized.optString("code")
            )
            if (code.isBlank() && title.isBlank()) continue

            val thumb = chooseFirstNonBlank(
                normalized.optString("cover"),
                normalized.optString("poster"),
                normalized.optString("thumbnail"),
                normalized.optString("image"),
                normalized.optString("thumb"),
                normalized.optString("image_url"),
                normalized.optString("cover_url")
            )
            val source = normalized.optString("source").ifBlank { "MissAV" }

            cards[code.lowercase(Locale.ROOT)] = VideoCard(
                code = code,
                title = title,
                href = resolveUrl(baseUrl = baseUrl, href = href),
                thumbnail = thumb.ifBlank { null },
                sourceSite = source.ifBlank { "MissAV" }
            )
        }
        return cards.values.toList()
    }

    private fun parseSchemaJson(raw: String, baseUrl: String): List<VideoCard> {
        val root = runCatching { JSONTokener(raw).nextValue() }.getOrNull() as? JSONObject ?: return emptyList()
        val list = root.optJSONArray("itemListElement") ?: return emptyList()
        val cards = mutableMapOf<String, VideoCard>()

        for (i in 0 until list.length()) {
            val node = list.optJSONObject(i) ?: continue
            val item = node.optJSONObject("item") ?: node
            val href = chooseFirstNonBlank(item.optString("url"))
            if (href.isBlank()) continue

            val code = extractCodeFromUrl(href)
            if (code.isBlank()) continue

            val title = chooseFirstNonBlank(item.optString("name"), item.optString("headline"), item.optString("title"))
            val thumb = chooseFirstNonBlank(
                item.optString("image"),
                item.optJSONObject("image")?.optString("url").orEmpty()
            )

            cards[code.lowercase(Locale.ROOT)] = VideoCard(
                code = code,
                title = title.ifBlank { code.uppercase(Locale.ROOT) },
                href = resolveUrl(baseUrl, href),
                thumbnail = thumb.ifBlank { null },
                sourceSite = "MissAV"
            )
        }

        return cards.values.toList()
    }

    private fun extractVideoArray(value: Any?): JSONArray? {
        val root = when (value) {
            is JSONArray -> return value
            is JSONObject -> value
            else -> return null
        }

        val candidateKeys = listOf(
            "data",
            "result",
            "results",
            "items",
            "rows",
            "payload",
            "list",
            "videos",
            "video",
            "items_list",
            "rowsData"
        )

        for (key in candidateKeys) {
            if (!root.has(key)) continue
            toJSONArray(root.opt(key))?.let { return it }
        }

        root.keys().forEach { key ->
            toJSONArray(root.opt(key))?.let { return it }
            val nestedObject = root.optJSONObject(key) ?: return@forEach
            val nestedCandidate = extractVideoArray(nestedObject)
            if (nestedCandidate != null) return nestedCandidate
        }

        return null
    }

    private fun toJSONArray(value: Any?): JSONArray? {
        return when (value) {
            is JSONArray -> value
            is JSONObject -> value.optJSONArray("items") ?: value.optJSONArray("rows") ?: value.optJSONArray("list")
            else -> null
        }
    }

    private fun chooseFirstNonBlank(vararg values: String): String {
        return values.firstOrNull { it.trim().isNotBlank() }.orEmpty().trim()
    }

    private fun unwrapSearchItem(item: JSONObject): JSONObject? {
        val nestedKeys = listOf("item", "video", "data", "result", "payload")
        for (key in nestedKeys) {
            if (!item.has(key)) continue
            val value = item.opt(key)
            if (value is JSONObject) return value
            if (value is JSONArray && value.length() == 1) {
                value.optJSONObject(0)?.let { return it }
            }
        }
        return item
    }

    private fun resolveUrl(baseUrl: String, href: String): String {
        val trimmed = href.trim()
        if (trimmed.isBlank()) return ""
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        if (trimmed.startsWith("//")) return "https:$trimmed"
        val normalizedBase = runCatching {
            URI(baseUrl).run {
                if (scheme.isNullOrBlank() || host.isNullOrBlank()) return@run baseUrl
                "${scheme}://${host}"
            }
        }.getOrDefault("https://missav.ws")
        return when {
            trimmed.startsWith("/") -> "$normalizedBase$trimmed"
            else -> "$normalizedBase/$trimmed"
        }
    }
}
