package com.example.missavapp.data

import android.content.Context
import androidx.annotation.StringRes
import com.example.missavapp.R
import com.example.missavapp.data.model.LoadState
import com.example.missavapp.data.model.LoginResult
import com.example.missavapp.data.model.MissAvSection
import com.example.missavapp.data.model.SiteProfile
import com.example.missavapp.data.model.UserInfo
import com.example.missavapp.data.model.VideoCard
import com.example.missavapp.data.model.VideoDetail
import com.example.missavapp.data.network.Av01Parser
import com.example.missavapp.data.network.Av123Parser
import com.example.missavapp.data.network.JableParser
import com.example.missavapp.data.network.MissAvHttpClient
import com.example.missavapp.data.network.MissAvParser
import com.example.missavapp.data.network.isCloudflareChallengePage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

private const val CHECK_USER_ID = 1016525
private const val SEARCH_SITE_MISSAV = "missav"
private const val SEARCH_SITE_JABLE = "jable"
private const val SEARCH_SITE_AV01 = "av01"
private const val SEARCH_SITE_123AV = "123av"
private val VIDEO_CODE_QUERY = Regex("(?i)^[a-z0-9]+-[a-z0-9]+(?:-[a-z0-9]+)*$")

class MissAvRepository(context: Context) {
    private val appContext: Context = context.applicationContext
    private val client = MissAvHttpClient(appContext)
    private var enabledSearchSites: Set<String> =
        setOf(SEARCH_SITE_MISSAV, SEARCH_SITE_JABLE, SEARCH_SITE_AV01, SEARCH_SITE_123AV)
    private var enabledMissavSearchHosts: List<String> = client.hosts.map(::normalizeSearchHost).filter { it.isNotBlank() }

    fun setHost(host: String) {
        client.setActiveHost(host)
    }

    fun setSite(site: SiteProfile) {
        if (!site.enabled) return
        if (site.id != SEARCH_SITE_MISSAV) return
        site.hosts.firstOrNull()?.let { client.setActiveHost(it) }
    }

    fun getActiveHost(): String = client.getActiveHost()

    fun syncCookiesFromWebCookies(host: String = getActiveHost()): Boolean = client.syncCookiesFromWebCookies(host)

    fun getHttpClient(): MissAvHttpClient = client

    fun updateSearchPreferences(siteIds: Set<String>, missavHosts: List<String>) {
        enabledSearchSites = siteIds.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotBlank() }.toSet()
        enabledMissavSearchHosts = missavHosts.map(::normalizeSearchHost).filter { it.isNotBlank() }.distinct()
    }

    suspend fun login(email: String, password: String, locale: String = "cn"): LoginResult {
        return withContext(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put("email", email)
                put("password", password)
                put("remember", true)
            }

            val result = client.performPostJson("/" + locale + "/api/login", payload)
            if (result.isFailure) {
                return@withContext LoginResult(false, result.exceptionOrNull()?.message)
            }

            val response = result.getOrThrow()
            if (response.code !in 200..299) {
                return@withContext LoginResult(false, text(R.string.error_login_failed_http, response.code))
            }
            return@withContext LoginResult(true, null)
        }
    }

    suspend fun fetchMe(): UserInfo? = withContext(Dispatchers.IO) {
        val response = client.performGet("/api/actresses/${CHECK_USER_ID}/view").getOrNull() ?: return@withContext null
        if (response.code !in 200..299 || response.body.isBlank()) return@withContext null

        val obj = JSONObject(response.body)
        if (!obj.has("user") || obj.isNull("user")) return@withContext null
        val user = obj.getJSONObject("user")
        val id = user.optString("id", user.optString("_id", "")).takeIf { it.isNotBlank() }
        val email = user.optString("email", "").takeIf { it.isNotBlank() }
        UserInfo(id = id, email = email)
    }

    suspend fun fetchSection(section: MissAvSection, locale: String = "cn", page: Int = 1): LoadState<List<VideoCard>> {
        val relativePath = "/" + locale + "/" + section.slug + if (page > 1) "?page=${page}" else ""
        return fetchMissavListWithHostFallback(relativePath)
    }

    suspend fun fetchJableFeed(page: Int = 1): LoadState<List<VideoCard>> {
        val url = if (page <= 1) {
            "https://jable.tv/latest-updates/"
        } else {
            "https://jable.tv/latest-updates/$page/"
        }

        return withContext(Dispatchers.IO) {
            val response = client.performGet(url)
            if (response.isFailure) {
                return@withContext LoadState.Error(response.exceptionOrNull()?.message ?: text(R.string.error_network_generic))
            }

            val raw = response.getOrThrow()
            if (raw.body.isCloudflareChallengePage()) {
                return@withContext LoadState.CloudflareChallenge
            }
            if (raw.code !in 200..299 || raw.body.isBlank()) {
                return@withContext LoadState.Error(text(R.string.error_load_failed_http, raw.code))
            }

            val list = JableParser.parseVideoList(raw.body, url)
            return@withContext LoadState.Success(list)
        }
    }

    suspend fun fetchAv01Feed(page: Int = 1): LoadState<List<VideoCard>> {
        return withContext(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put(
                    "pagination",
                    JSONObject().apply {
                        put("page", page.coerceAtLeast(1))
                        put("limit", 24)
                    }
                )
            }

            val response = client.performPostJson(
                "https://www.av01.media/api/v1/videos/search?lang=ja",
                payload
            )
            if (response.isFailure) {
                return@withContext LoadState.Error(response.exceptionOrNull()?.message ?: text(R.string.error_network_generic))
            }

            val raw = response.getOrThrow()
            if (raw.body.isCloudflareChallengePage()) {
                return@withContext LoadState.CloudflareChallenge
            }
            if (raw.code !in 200..299 || raw.body.isBlank()) {
                return@withContext LoadState.Error(text(R.string.error_load_failed_http, raw.code))
            }

            val geoJson = fetchAv01GeoJson()
            return@withContext LoadState.Success(Av01Parser.parseSearchJson(raw.body, geoJson))
        }
    }

    suspend fun fetch123AvFeed(page: Int = 1): LoadState<List<VideoCard>> {
        val url = if (page <= 1) {
            "https://123av.com/zh/dm9/recent-update"
        } else {
            "https://123av.com/zh/dm9/recent-update?page=${page.coerceAtLeast(1)}"
        }

        return withContext(Dispatchers.IO) {
            val response = client.performGet(url, refererUrl = "https://123av.com/zh/dm9")
            if (response.isFailure) {
                return@withContext LoadState.Error(response.exceptionOrNull()?.message ?: text(R.string.error_network_generic))
            }

            val raw = response.getOrThrow()
            if (raw.body.isCloudflareChallengePage()) {
                return@withContext LoadState.CloudflareChallenge
            }
            if (raw.code !in 200..299 || raw.body.isBlank()) {
                return@withContext LoadState.Error(text(R.string.error_load_failed_http, raw.code))
            }

            return@withContext LoadState.Success(Av123Parser.parseSearchList(raw.body, url))
        }
    }

    suspend fun search(keyword: String, locale: String = "cn"): LoadState<List<VideoCard>> {
        return searchAggregated(keyword, locale)
    }

    suspend fun searchAggregated(keyword: String, locale: String = "cn"): LoadState<List<VideoCard>> {
        val query = keyword.trim()
        if (query.isBlank()) {
            return LoadState.Error(text(R.string.error_search_keyword_empty))
        }
        if (enabledSearchSites.isEmpty()) {
            return LoadState.Error(text(R.string.error_search_source_empty))
        }
        val localeTag = locale.trim().ifBlank { "cn" }
        val encoded = java.net.URLEncoder.encode(query, Charsets.UTF_8.name())
        val encodedSpaceAsPercent = encoded.replace("+", "%20")

        return coroutineScope {
            val missavDeferred = if (enabledSearchSites.contains(SEARCH_SITE_MISSAV)) {
                async(Dispatchers.IO) {
                    searchMissav(query, encoded, encodedSpaceAsPercent, localeTag)
                }
            } else {
                null
            }
            val jableDeferred = if (enabledSearchSites.contains(SEARCH_SITE_JABLE)) {
                async(Dispatchers.IO) {
                    searchJable(encoded)
                }
            } else {
                null
            }
            val av01Deferred = if (enabledSearchSites.contains(SEARCH_SITE_AV01)) {
                async(Dispatchers.IO) {
                    searchAv01(encodedSpaceAsPercent)
                }
            } else {
                null
            }
            val av123Deferred = if (enabledSearchSites.contains(SEARCH_SITE_123AV)) {
                async(Dispatchers.IO) {
                    search123Av(encoded)
                }
            } else {
                null
            }

            mergeSearchStates(
                candidates = buildList {
                    missavDeferred?.await()?.let(::add)
                    jableDeferred?.await()?.let(::add)
                    av01Deferred?.await()?.let(::add)
                    av123Deferred?.await()?.let(::add)
                },
                query = keyword
            )
        }
    }

    fun buildMissavWebSearchUrl(keyword: String, locale: String = "cn"): String? {
        val query = keyword.trim()
        if (query.isBlank()) return null

        val encodedSegment = java.net.URLEncoder.encode(query, Charsets.UTF_8.name()).replace("+", "%20")
        if (encodedSegment.isBlank()) return null

        val preferredHost = buildMissavSearchHostCandidates().firstOrNull()
            ?: normalizeSearchHost(getActiveHost()).takeIf { it.isNotBlank() }
            ?: return null

        return "$preferredHost/search/$encodedSegment"
    }

    fun parseMissavSearchHtml(html: String, baseUrl: String): List<VideoCard> {
        return MissAvParser.parseSearchList(html, baseUrl)
    }

    suspend fun getVideoDetail(url: String): LoadState<VideoDetail> {
        return withContext(Dispatchers.IO) {
            if (isJableUrl(url)) {
                return@withContext fetchSingleDetail(url)
            }
            if (isAv01Url(url)) {
                return@withContext fetchAv01Detail(url)
            }
            if (is123AvUrl(url)) {
                return@withContext fetch123AvDetail(url)
            }

            val detailUrls = buildMissavDetailUrls(url)
            var seenCloudflare = false
            var lastError: String? = null

            for (candidateUrl in detailUrls) {
                when (val result = fetchSingleDetail(candidateUrl)) {
                    is LoadState.Success -> {
                        client.setActiveHost(extractHost(candidateUrl))
                        return@withContext result
                    }
                    is LoadState.CloudflareChallenge -> seenCloudflare = true
                    is LoadState.Error -> lastError = result.message
                    is LoadState.Loading, is LoadState.Idle -> {}
                }
            }

            return@withContext if (seenCloudflare) {
                LoadState.CloudflareChallenge
            } else {
                LoadState.Error(lastError ?: text(R.string.error_detail_unavailable))
            }
        }
    }

    private suspend fun searchMissav(
        keyword: String,
        encodedQuery: String,
        encodedQueryPercent: String,
        locale: String
    ): LoadState<List<VideoCard>> {
        val segment = encodedQueryPercent.ifBlank { keyword }
        val qPath = encodedQuery.ifBlank { keyword }
        val rawKeyword = keyword.trim()
        val localeCandidates = buildMissavSearchLocales(locale)

        if (looksLikeVideoCode(rawKeyword)) {
            val directMatch = fetchDirectMissavCodeResult(rawKeyword, localeCandidates)
            if (directMatch is LoadState.Success && directMatch.data.isNotEmpty()) {
                return directMatch
            }
        }

        val paths = buildMissavSearchPaths(qPath, segment, rawKeyword, localeCandidates)
        if (paths.isEmpty()) return LoadState.Error(text(R.string.error_search_keyword_empty))

        val requestUrls = buildMissavSearchUrls(paths)
        return fetchFirstSuccessfulSearchList(requestUrls.distinct())
    }

    private fun buildMissavSearchPaths(
        encodedQuery: String,
        encodedPathQuery: String,
        rawQuery: String,
        locales: List<String>
    ): List<String> {
        val encodedQueryOrRaw = encodedQuery.ifBlank { rawQuery }
        val encodedPathOrRaw = encodedPathQuery.ifBlank { rawQuery }
        val raw = rawQuery.trim()
        val candidates = mutableListOf<String>()

        candidates.addAll(
            listOf(
                "/search/$encodedPathOrRaw",
                "/search/$encodedPathOrRaw/",
                "/search/$raw",
                "/search/$raw/",
                "/search?q=$encodedQueryOrRaw",
                "/search/?q=$encodedQueryOrRaw",
                "/search?keyword=$encodedQueryOrRaw"
            )
        )

        locales.forEach { locale ->
            candidates.addAll(
                listOf(
                    "/$locale/search/$encodedPathOrRaw",
                    "/$locale/search/$encodedPathOrRaw/",
                    "/$locale/search/$raw",
                    "/$locale/search/$raw/",
                    "/$locale/search?q=$encodedQueryOrRaw",
                    "/$locale/search/?q=$encodedQueryOrRaw",
                    "/$locale/search?keyword=$encodedQueryOrRaw",
                    "/$locale/search?term=$encodedQueryOrRaw",
                    "/$locale/?s=$encodedQueryOrRaw",
                    "/$locale/api/search/videos?q=$encodedQueryOrRaw",
                    "/$locale/api/search/videos?keyword=$encodedQueryOrRaw",
                    "/$locale/api/v1/search/videos?q=$encodedQueryOrRaw",
                    "/$locale/api/v1/search/videos?keyword=$encodedQueryOrRaw",
                    "/$locale/api/videos?query=$encodedQueryOrRaw",
                    "/$locale/legacy?query=$encodedQueryOrRaw",
                    "/$locale/legacy?keyword=$encodedQueryOrRaw",
                    "/$locale/legacy?term=$encodedQueryOrRaw",
                    "/$locale/legacy/search/$encodedPathOrRaw"
                )
            )
        }

        candidates.addAll(
            listOf(
                "/search?term=$encodedQueryOrRaw",
                "/api/search/videos?q=$encodedQueryOrRaw",
                "/api/search/videos?keyword=$encodedQueryOrRaw",
                "/api/v1/search/videos?q=$encodedQueryOrRaw",
                "/api/v1/search/videos?keyword=$encodedQueryOrRaw",
                "/api/videos?query=$encodedQueryOrRaw",
                "/search/videos?query=$encodedQueryOrRaw",
                "/search/videos?keyword=$encodedQueryOrRaw",
                "/api/videos?search=$encodedQueryOrRaw",
                "/api/videos?term=$encodedQueryOrRaw",
                "/legacy?query=$encodedQueryOrRaw",
                "/legacy?keyword=$encodedQueryOrRaw",
                "/legacy?term=$encodedQueryOrRaw",
                "/legacy/search/$encodedPathOrRaw"
            )
        )

        if (raw.length < 2) {
            candidates.add("/search/$raw")
            locales.forEach { locale ->
                candidates.add("/$locale/search/$encodedPathOrRaw")
                candidates.add("/$locale/search/$raw")
            }
        }

        return candidates.distinct()
    }

    private fun buildMissavSearchUrls(relativePaths: List<String>): List<String> {
        val orderedHosts = buildMissavSearchHostCandidates()
        return relativePaths.flatMap { path -> orderedHosts.map { host -> host + path } }
    }

    private fun buildMissavSearchHostCandidates(): List<String> {
        val configuredHosts = enabledMissavSearchHosts.ifEmpty {
            client.hosts.map(::normalizeSearchHost).filter { it.isNotBlank() }
        }
        val active = normalizeSearchHost(getActiveHost())
        val cookieBackedHosts = configuredHosts.filter { host ->
            val hostName = runCatching { java.net.URI(host).host }.getOrNull().orEmpty()
            hostName.isNotBlank() && !client.getCookiesForHost(hostName).isNullOrBlank()
        }
        return buildList {
            if (active.isNotBlank() && configuredHosts.contains(active)) add(active)
            addAll(cookieBackedHosts.filter { it != active })
            addAll(configuredHosts.filter { it != active })
        }.distinct()
    }

    private fun normalizeSearchHost(input: String): String {
        val trimmed = input.trim().trimEnd('/')
        return if (trimmed.isBlank()) "" else if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
    }

    private suspend fun fetchFirstSuccessfulSearchList(paths: List<String>): LoadState<List<VideoCard>> {
        var lastErrorMessage: String? = null
        var seenCloudflare = false

        for (path in paths) {
            when (val result = fetchSearchList(path)) {
                is LoadState.Success -> {
                    if (result.data.isNotEmpty()) {
                        return result
                    }
                    if (lastErrorMessage == null) {
                        lastErrorMessage = text(R.string.error_missav_search_empty)
                    }
                }

                is LoadState.Error -> {
                    lastErrorMessage = result.message
                }

                is LoadState.CloudflareChallenge -> {
                    seenCloudflare = true
                }

                is LoadState.Loading,
                is LoadState.Idle -> {}
            }
        }

        return when {
            seenCloudflare -> LoadState.CloudflareChallenge
            else -> LoadState.Error(lastErrorMessage ?: text(R.string.error_missav_search_failed))
        }
    }

    private suspend fun fetchDirectMissavCodeResult(keyword: String, locales: List<String>): LoadState<List<VideoCard>> {
        val normalizedCode = keyword.trim().lowercase(Locale.ROOT)
        if (normalizedCode.isBlank()) return LoadState.Error(text(R.string.error_search_keyword_empty))

        val candidateUrls = buildMissavSearchUrls(
            buildList {
                locales.forEach { locale ->
                    add("/$locale/$normalizedCode")
                }
                add("/$normalizedCode")
            }
        ).distinct()

        var seenCloudflare = false
        var lastError: String? = null

        for (url in candidateUrls) {
            val response = withContext(Dispatchers.IO) { client.performGet(url) }
            if (response.isFailure) {
                lastError = response.exceptionOrNull()?.message ?: text(R.string.error_network_generic)
                continue
            }

            val raw = response.getOrThrow()
            if (raw.body.isCloudflareChallengePage()) {
                seenCloudflare = true
                continue
            }
            if (raw.code !in 200..299 || raw.body.isBlank()) {
                lastError = text(R.string.error_load_failed_http, raw.code)
                continue
            }

            val detail = MissAvParser.parseVideoDetail(raw.body, url)
            val parsedCode = detail.code.ifBlank { normalizedCode }
            if (!parsedCode.equals(normalizedCode, ignoreCase = true) && !raw.body.contains(normalizedCode, ignoreCase = true)) {
                continue
            }

            return LoadState.Success(
                listOf(
                    VideoCard(
                        code = parsedCode,
                        title = detail.title.ifBlank { parsedCode.uppercase(Locale.ROOT) },
                        href = url,
                        thumbnail = detail.thumbnails.firstOrNull(),
                        sourceSite = "MissAV"
                    )
                )
            )
        }

        return when {
            seenCloudflare -> LoadState.CloudflareChallenge
            else -> LoadState.Error(lastError ?: text(R.string.error_missav_code_not_found))
        }
    }

    private suspend fun fetchSearchList(urlPath: String): LoadState<List<VideoCard>> {
        return withContext(Dispatchers.IO) {
            val response = client.performGet(urlPath)
            if (response.isFailure) {
                return@withContext LoadState.Error(response.exceptionOrNull()?.message ?: text(R.string.error_network_generic))
            }

            val raw = response.getOrThrow()
            if (raw.body.isCloudflareChallengePage()) {
                return@withContext LoadState.CloudflareChallenge
            }
            if (raw.code !in 200..299) {
                return@withContext LoadState.Error(text(R.string.error_load_failed_http, raw.code))
            }

            val list = MissAvParser.parseSearchList(raw.body, urlPath)
            return@withContext if (list.isNotEmpty()) {
                LoadState.Success(list)
            } else {
                LoadState.Error(text(R.string.error_missav_search_empty))
            }
        }
    }

    private suspend fun searchJable(encodedQuery: String): LoadState<List<VideoCard>> {
        val rawQuery = java.net.URLDecoder.decode(encodedQuery, Charsets.UTF_8.name()).trim()
        if (looksLikeVideoCode(rawQuery)) {
            val direct = fetchDirectJableCodeResult(rawQuery)
            if (direct is LoadState.Success && direct.data.isNotEmpty()) {
                return direct
            }
        }

        val candidateUrls = listOf(
            "https://jable.tv/search/$encodedQuery/",
            "https://jable.tv/search/?q=$encodedQuery"
        )

        var lastError: String? = null
        for (url in candidateUrls) {
            val response = withContext(Dispatchers.IO) {
                client.performGet(url)
            }

            if (response.isFailure) {
                lastError = response.exceptionOrNull()?.message ?: text(R.string.error_network_generic)
                continue
            }

            val raw = response.getOrThrow()
            if (raw.body.isCloudflareChallengePage()) {
                return LoadState.CloudflareChallenge
            }
            if (raw.code !in 200..299) {
                lastError = text(R.string.error_load_failed_http, raw.code)
                continue
            }

            val list = JableParser.parseVideoList(raw.body, url)
            if (list.isNotEmpty()) {
                return LoadState.Success(list)
            }
            lastError = text(R.string.error_jable_no_search_result)
        }

        return LoadState.Error(lastError ?: text(R.string.error_jable_no_search_result))
    }

    private suspend fun searchAv01(encodedQuery: String): LoadState<List<VideoCard>> {
        val rawQuery = java.net.URLDecoder.decode(encodedQuery, Charsets.UTF_8.name()).trim()
        val url = "https://www.av01.media/api/v1/videos/search?lang=ja"
        val payload = JSONObject().apply {
            put("query", rawQuery)
            put(
                "pagination",
                JSONObject().apply {
                    put("page", 1)
                    put("limit", 24)
                }
            )
        }

        return withContext(Dispatchers.IO) {
            val response = client.performPostJson(url, payload)
            if (response.isFailure) {
                return@withContext LoadState.Error(response.exceptionOrNull()?.message ?: text(R.string.error_network_generic))
            }

            val raw = response.getOrThrow()
            if (raw.body.isCloudflareChallengePage()) {
                return@withContext LoadState.CloudflareChallenge
            }
            if (raw.code !in 200..299 || raw.body.isBlank()) {
                return@withContext LoadState.Error(text(R.string.error_load_failed_http, raw.code))
            }

            val geoJson = fetchAv01GeoJson()
            val list = runCatching { Av01Parser.parseSearchJson(raw.body, geoJson) }.getOrDefault(emptyList())
            return@withContext if (list.isNotEmpty()) {
                LoadState.Success(list)
            } else {
                LoadState.Error(text(R.string.error_no_results_for_query, rawQuery))
            }
        }
    }

    private suspend fun search123Av(encodedQuery: String): LoadState<List<VideoCard>> {
        val rawQuery = java.net.URLDecoder.decode(encodedQuery, Charsets.UTF_8.name()).trim()
        val url = "https://123av.com/zh/search?keyword=$encodedQuery"
        return withContext(Dispatchers.IO) {
            val response = client.performGet(url, refererUrl = "https://123av.com/zh/")
            if (response.isFailure) {
                return@withContext LoadState.Error(response.exceptionOrNull()?.message ?: text(R.string.error_network_generic))
            }

            val raw = response.getOrThrow()
            if (raw.body.isCloudflareChallengePage()) {
                return@withContext LoadState.CloudflareChallenge
            }
            if (raw.code !in 200..299 || raw.body.isBlank()) {
                return@withContext LoadState.Error(text(R.string.error_load_failed_http, raw.code))
            }

            val list = Av123Parser.parseSearchList(raw.body, url)
            return@withContext if (list.isNotEmpty()) {
                LoadState.Success(list)
            } else if (looksLikeVideoCode(rawQuery)) {
                fetchDirect123AvCodeResult(rawQuery)
            } else {
                LoadState.Error(text(R.string.error_no_results_for_query, rawQuery))
            }
        }
    }

    private suspend fun fetchList(urlPath: String): LoadState<List<VideoCard>> {
        return withContext(Dispatchers.IO) {
            val response = client.performGet(urlPath)
            if (response.isFailure) {
                return@withContext LoadState.Error(response.exceptionOrNull()?.message ?: text(R.string.error_network_generic))
            }

            val raw = response.getOrThrow()
            if (raw.body.isCloudflareChallengePage()) {
                return@withContext LoadState.CloudflareChallenge
            }
            if (raw.code !in 200..299) {
                return@withContext LoadState.Error(text(R.string.error_load_failed_http, raw.code))
            }
            val list = MissAvParser.parseVideoList(raw.body, urlPath)
            LoadState.Success(list)
        }
    }

    private suspend fun fetchMissavListWithHostFallback(relativePath: String): LoadState<List<VideoCard>> {
        val candidates = buildMissavSearchUrls(listOf(relativePath)).distinct()
        var seenCloudflare = false
        var lastError: String? = null

        for (url in candidates) {
            when (val result = fetchList(url)) {
                is LoadState.Success -> {
                    client.setActiveHost(extractHost(url))
                    return result
                }
                is LoadState.CloudflareChallenge -> seenCloudflare = true
                is LoadState.Error -> lastError = result.message
                is LoadState.Loading, is LoadState.Idle -> {}
            }
        }

        return if (seenCloudflare) {
            LoadState.CloudflareChallenge
        } else {
            LoadState.Error(lastError ?: text(R.string.error_list_unavailable))
        }
    }

    private suspend fun fetchSingleDetail(url: String): LoadState<VideoDetail> {
        val response = client.performGet(url)
        if (response.isFailure) {
            return LoadState.Error(response.exceptionOrNull()?.message ?: text(R.string.error_network_generic))
        }

        val raw = response.getOrThrow()
        val body = raw.body
        if (body.isCloudflareChallengePage()) {
            return LoadState.CloudflareChallenge
        }
        if (raw.code !in 200..299 || body.isBlank()) {
            return LoadState.Error(text(R.string.error_load_failed_http, raw.code))
        }

        val detail = if (isJableUrl(url)) {
            JableParser.parseVideoDetail(body, url)
        } else {
            MissAvParser.parseVideoDetail(body, url)
        }
        return LoadState.Success(detail)
    }

    private suspend fun fetchAv01Detail(url: String): LoadState<VideoDetail> {
        val videoId = Av01Parser.extractVideoId(url)
            ?: return LoadState.Error(text(R.string.error_detail_unavailable))

        val pageBody = client.performGet(url, refererUrl = "https://www.av01.media/jp")
            .getOrNull()
            ?.takeIf { it.code in 200..299 && it.body.isNotBlank() && !it.body.isCloudflareChallengePage() }
            ?.body
            .orEmpty()

        val apiUrl = "https://www.av01.media/api/v1/videos/$videoId"
        val apiResponse = client.performGet(apiUrl, refererUrl = url)
        if (apiResponse.isFailure) {
            return LoadState.Error(apiResponse.exceptionOrNull()?.message ?: text(R.string.error_network_generic))
        }

        val rawApi = apiResponse.getOrThrow()
        if (rawApi.body.isCloudflareChallengePage()) {
            return LoadState.CloudflareChallenge
        }
        if (rawApi.code !in 200..299 || rawApi.body.isBlank()) {
            return LoadState.Error(text(R.string.error_load_failed_http, rawApi.code))
        }

        val similarsUrl = "https://www.av01.media/api/v1/videos/$videoId/similars?page=1&limit=12"
        val similarsBody = client.performGet(similarsUrl, refererUrl = url)
            .getOrNull()
            ?.takeIf { it.code in 200..299 && it.body.isNotBlank() && !it.body.isCloudflareChallengePage() }
            ?.body
        val geoJson = fetchAv01GeoJson()
        val playlistSource = Av01Parser.buildSignedPlaylistUrl(videoId, geoJson)
            ?.let { playlistUrl ->
                client.performGet(playlistUrl, refererUrl = url)
                    .getOrNull()
                    ?.takeIf { it.code in 200..299 && it.body.isNotBlank() && !it.body.isCloudflareChallengePage() }
                    ?.body
            }
            ?.let { Av01Parser.extractPlaylistSource(it) }

        return LoadState.Success(
            Av01Parser.parseVideoDetail(
                html = pageBody,
                baseUrl = url,
                detailJson = rawApi.body,
                similarsJson = similarsBody,
                geoJson = geoJson,
                playlistSource = playlistSource
            )
        )
    }

    private suspend fun fetchAv01GeoJson(): String? {
        return client.performGet("https://files.iw01.xyz/edge/geo.js?json", refererUrl = "https://www.av01.media/")
            .getOrNull()
            ?.takeIf { it.code in 200..299 && it.body.isNotBlank() && !it.body.isCloudflareChallengePage() }
            ?.body
    }

    private suspend fun fetch123AvDetail(url: String): LoadState<VideoDetail> {
        val pageResponse = client.performGet(url, refererUrl = "https://123av.com/zh/")
        if (pageResponse.isFailure) {
            return LoadState.Error(pageResponse.exceptionOrNull()?.message ?: text(R.string.error_network_generic))
        }

        val rawPage = pageResponse.getOrThrow()
        if (rawPage.body.isCloudflareChallengePage()) {
            return LoadState.CloudflareChallenge
        }
        if (rawPage.code !in 200..299 || rawPage.body.isBlank()) {
            return LoadState.Error(text(R.string.error_load_failed_http, rawPage.code))
        }

        val movieId = Av123Parser.extractMovieId(rawPage.body)
            ?: return LoadState.Error(text(R.string.error_detail_unavailable))
        val ajaxUrl = Av123Parser.buildAjaxUrl(url, movieId)
        val ajaxResponse = client.performGet(ajaxUrl, refererUrl = url)
        if (ajaxResponse.isFailure) {
            return LoadState.Error(ajaxResponse.exceptionOrNull()?.message ?: text(R.string.error_network_generic))
        }

        val rawAjax = ajaxResponse.getOrThrow()
        if (rawAjax.body.isCloudflareChallengePage()) {
            return LoadState.CloudflareChallenge
        }
        if (rawAjax.code !in 200..299 || rawAjax.body.isBlank()) {
            return LoadState.Error(text(R.string.error_load_failed_http, rawAjax.code))
        }

        val posterUrl = Av123Parser.extractPosterUrl(rawPage.body, url)
        val streamRequest = Av123Parser.buildStreamRequest(rawAjax.body, posterUrl)
        val streamBody = streamRequest?.let { request ->
            var body: String? = null
            repeat(2) {
                if (body == null) {
                    body = client.performGet(request.streamApiUrl, refererUrl = request.iframeUrl)
                        .getOrNull()
                        ?.takeIf { it.code in 200..299 && it.body.isNotBlank() && !it.body.isCloudflareChallengePage() }
                        ?.body
                }
            }
            body
        }

        return LoadState.Success(
            Av123Parser.parseVideoDetail(
                html = rawPage.body,
                baseUrl = url,
                streamJson = streamBody
            )
        )
    }

    private suspend fun fetchListWithFallback(primary: String, fallback: String): LoadState<List<VideoCard>> {
        val first = fetchList(primary)
        return if (first is LoadState.Success && first.data.isNotEmpty()) {
            first
        } else {
            when (first) {
                is LoadState.CloudflareChallenge -> first
                is LoadState.Error -> {
                    val fallbackResult = fetchList(fallback)
                    if (fallbackResult is LoadState.Success && fallbackResult.data.isNotEmpty()) {
                        fallbackResult
                    } else if (fallbackResult is LoadState.Error) {
                        first
                    } else {
                        fallbackResult
                    }
                }
                is LoadState.Loading -> first
                is LoadState.Idle -> first
                is LoadState.Success -> first
            }
        }
    }

    private fun mergeSearchStates(
        candidates: List<LoadState<List<VideoCard>>>,
        query: String
    ): LoadState<List<VideoCard>> {
        val normalizedQuery = query.trim().lowercase(Locale.ROOT)
        val merged = mutableMapOf<String, VideoCard>()
        var hasCloudflare = false
        val errors = mutableListOf<String>()

        for (state in candidates) {
            when (state) {
                is LoadState.Success -> {
                    for (card in state.data) {
                        val source = card.sourceSite.ifBlank { "MissAV" }
                        val code = card.code.ifBlank { card.href.substringAfterLast('/').substringBefore('?') }
                            .lowercase(Locale.ROOT)
                        if (code.isBlank()) continue

                        val normalizedCard = if (card.sourceSite.isBlank()) {
                            card.copy(sourceSite = source)
                        } else {
                            card
                        }
                        val key = "${source.lowercase(Locale.ROOT)}|$code"
                        val current = merged[key]
                        if (current == null || isPreferredSearchCard(normalizedCard, current, normalizedQuery)) {
                            merged[key] = normalizedCard
                        }
                    }
                }
                is LoadState.CloudflareChallenge -> hasCloudflare = true
                is LoadState.Error -> errors.add(state.message)
                is LoadState.Loading, is LoadState.Idle -> {}
            }
        }

        if (merged.isNotEmpty()) {
            val ordered = merged.values.sortedWith(
                compareBy<VideoCard> { searchRank(it, normalizedQuery) }
                    .thenByDescending { if (it.thumbnail.isNullOrBlank()) 0 else 1 }
                    .thenByDescending { if (it.title.equals(it.code, ignoreCase = true)) 0 else 1 }
                    .thenBy { it.title.lowercase(Locale.ROOT).trim() }
                    .thenBy { it.sourceSite.lowercase(Locale.ROOT).trim() }
                    .thenBy { it.code.lowercase(Locale.ROOT).trim() }
            )
            return LoadState.Success(ordered)
        }

        return if (errors.isNotEmpty()) {
            if (hasCloudflare) {
                LoadState.Error(text(R.string.error_partial_sites_cloudflare, errors.joinToString("； ")))
            } else {
                LoadState.Error(errors.joinToString("； "))
            }
        } else if (hasCloudflare) {
            LoadState.CloudflareChallenge
        } else {
            LoadState.Error(text(R.string.error_no_results_for_query, query))
        }
    }

    suspend fun setFavFromDetail(detail: VideoDetail, save: Boolean): LoadState<Boolean> {
        val url = detail.saveApiUrl ?: return LoadState.Error(text(R.string.error_missing_favorite_api))
        return withContext(Dispatchers.IO) {
            val resp = if (save) client.performPostJson(url, JSONObject()) else client.performDelete(url)
            if (resp.isFailure) {
                return@withContext LoadState.Error(resp.exceptionOrNull()?.message ?: text(R.string.error_network_generic))
            }
            val code = resp.getOrThrow().code
            if (code in 200..299) {
                LoadState.Success(true)
            } else {
                LoadState.Error(text(R.string.error_action_failed_http, code))
            }
        }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        client.clearSession()
    }

    private fun isJableUrl(url: String): Boolean {
        return url.contains("jable.tv", ignoreCase = true)
    }

    private fun isAv01Url(url: String): Boolean {
        return url.contains("av01.media", ignoreCase = true) || url.contains("av01.tv", ignoreCase = true)
    }

    private fun is123AvUrl(url: String): Boolean {
        return url.contains("123av.com", ignoreCase = true) || url.contains("njav.tv", ignoreCase = true)
    }

    private fun looksLikeVideoCode(keyword: String): Boolean {
        return VIDEO_CODE_QUERY.matches(keyword.trim())
    }

    private fun buildMissavSearchLocales(locale: String): List<String> {
        val normalized = locale.trim().lowercase(Locale.ROOT)
        return buildList {
            add("ja")
            if (normalized.isNotBlank() && normalized != "ja") {
                add(normalized)
            }
            add("cn")
        }.distinct()
    }

    private fun buildMissavDetailUrls(url: String): List<String> {
        val pathAndQuery = runCatching {
            val uri = java.net.URI(url)
            buildString {
                append(uri.rawPath ?: "")
                if (!uri.rawQuery.isNullOrBlank()) {
                    append('?')
                    append(uri.rawQuery)
                }
            }
        }.getOrNull().orEmpty()

        if (pathAndQuery.isBlank()) return listOf(url)
        return buildList {
            add(url)
            addAll(
                buildMissavSearchHostCandidates()
                    .map { host -> host + pathAndQuery }
            )
        }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun extractHost(url: String): String {
        return runCatching {
            val uri = java.net.URI(url)
            "${uri.scheme}://${uri.host}"
        }.getOrDefault(getActiveHost())
    }

    private fun isPreferredSearchCard(candidate: VideoCard, current: VideoCard, normalizedQuery: String): Boolean {
        val candidateRank = searchRank(candidate, normalizedQuery)
        val currentRank = searchRank(current, normalizedQuery)
        if (candidateRank != currentRank) return candidateRank < currentRank

        val candidateThumb = !candidate.thumbnail.isNullOrBlank()
        val currentThumb = !current.thumbnail.isNullOrBlank()
        if (candidateThumb != currentThumb) return candidateThumb

        val candidateTitle = candidate.title.trim()
        val currentTitle = current.title.trim()
        if (candidateTitle.length != currentTitle.length) return candidateTitle.length > currentTitle.length

        return candidate.href.length < current.href.length
    }

    private fun searchRank(card: VideoCard, normalizedQuery: String): Int {
        if (normalizedQuery.isBlank()) return 4

        val code = card.code.trim().lowercase(Locale.ROOT)
        val title = card.title.trim().lowercase(Locale.ROOT)
        return when {
            code == normalizedQuery -> 0
            title == normalizedQuery -> 1
            code.startsWith(normalizedQuery) -> 2
            title.contains(normalizedQuery) -> 3
            else -> 4
        }
    }

    private suspend fun fetchDirectJableCodeResult(keyword: String): LoadState<List<VideoCard>> {
        val normalizedCode = keyword.trim().lowercase(Locale.ROOT)
        if (normalizedCode.isBlank()) return LoadState.Error(text(R.string.error_search_keyword_empty))

        val url = "https://jable.tv/videos/$normalizedCode/"
        val response = withContext(Dispatchers.IO) {
            client.performGet(url)
        }
        if (response.isFailure) {
            return LoadState.Error(response.exceptionOrNull()?.message ?: text(R.string.error_network_generic))
        }

        val raw = response.getOrThrow()
        if (raw.body.isCloudflareChallengePage()) {
            return LoadState.CloudflareChallenge
        }
        if (raw.code !in 200..299 || raw.body.isBlank()) {
            return LoadState.Error(text(R.string.error_load_failed_http, raw.code))
        }

        val detail = JableParser.parseVideoDetail(raw.body, url)
        val parsedCode = detail.code.ifBlank { normalizedCode }
        if (!parsedCode.equals(normalizedCode, ignoreCase = true) && !raw.body.contains(normalizedCode, ignoreCase = true)) {
            return LoadState.Error(text(R.string.error_jable_code_not_found))
        }

        return LoadState.Success(
            listOf(
                VideoCard(
                    code = parsedCode,
                    title = detail.title.ifBlank { parsedCode.uppercase(Locale.ROOT) },
                    href = url,
                    thumbnail = detail.thumbnails.firstOrNull(),
                    sourceSite = "Jable.tv"
                )
            )
        )
    }

    private suspend fun fetchDirect123AvCodeResult(keyword: String): LoadState<List<VideoCard>> {
        val normalizedCode = keyword.trim().lowercase(Locale.ROOT)
        if (normalizedCode.isBlank()) return LoadState.Error(text(R.string.error_search_keyword_empty))

        return when (val detailState = fetch123AvDetail("https://123av.com/zh/v/$normalizedCode")) {
            is LoadState.Success -> {
                val detail = detailState.data
                LoadState.Success(
                    listOf(
                        VideoCard(
                            code = detail.code.ifBlank { normalizedCode },
                            title = detail.title.ifBlank { normalizedCode.uppercase(Locale.ROOT) },
                            href = detail.href,
                            thumbnail = detail.thumbnails.firstOrNull(),
                            sourceSite = "123AV"
                        )
                    )
                )
            }
            is LoadState.Error -> detailState
            is LoadState.CloudflareChallenge -> detailState
            is LoadState.Loading -> detailState
            is LoadState.Idle -> detailState
        }
    }

    private fun text(@StringRes resId: Int, vararg args: Any): String = appContext.getString(resId, *args)
}
