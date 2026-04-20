package com.example.missavapp

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.missavapp.data.MissAvRepository
import com.example.missavapp.data.download.DownloadCoordinator
import com.example.missavapp.data.model.DownloadTask
import com.example.missavapp.data.model.HistoryItem
import com.example.missavapp.data.model.LoadState
import com.example.missavapp.data.model.MissAvSection
import com.example.missavapp.data.model.SiteProfile
import com.example.missavapp.data.model.VideoCard
import com.example.missavapp.data.model.VideoDetail
import com.example.missavapp.data.model.VideoSourceOption
import com.example.missavapp.data.site.SiteRegistry
import com.example.missavapp.data.update.GitHubReleaseManager
import com.example.missavapp.data.update.GitHubUpdateResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private companion object {
        private const val CF_BYPASS_TTL_MS = 10 * 60 * 1000L
        private const val CF_RETRY_GAP_MS = 8_000L
        private const val CF_AUTO_PROMPT_PREF = "cf_auto_prompt_enabled"
        private const val CF_PERSIST_PREF = "cf_bypass_state"
        private const val CF_PREF_KEY_PREFIX = "cf_bypass_until_"
        private const val LOCAL_LIBRARY_PREF = "fluxia_local_library"
        private const val RECENT_SEARCHES_KEY = "recent_searches"
        private const val FAVORITES_KEY = "favorites"
        private const val HISTORY_KEY = "history"
        private const val SEARCH_SITES_KEY = "search_sites"
        private const val SEARCH_MISSAV_HOSTS_KEY = "search_missav_hosts"
        private const val GITHUB_UPDATE_REPO_KEY = "github_update_repo"
        private const val DEFAULT_GITHUB_UPDATE_REPO = "Last-emo-boy/Fluxia"
        private const val MAX_SEARCH_HISTORY = 16
        private const val MAX_FAVORITE = 300
        private const val MAX_HISTORY = 300
    }

    private val repo = MissAvRepository(application)
    private val downloadCoordinator = DownloadCoordinator(application.applicationContext, repo.getHttpClient())
    private val githubReleaseManager = GitHubReleaseManager(application.applicationContext)
    private val appContext = application.applicationContext
    private val cfBypassPrefs = appContext.getSharedPreferences(CF_PERSIST_PREF, Context.MODE_PRIVATE)
    private val localLibraryPrefs = appContext.getSharedPreferences(LOCAL_LIBRARY_PREF, Context.MODE_PRIVATE)
    private val downloadCollectJob: Job
    private val cloudflareBypassUntil = mutableMapOf<String, Long>()
    private val cloudflareRetryAt = mutableMapOf<String, Long>()

    var activeSite by mutableStateOf(SiteRegistry.missav)
        private set

    var hostState by mutableStateOf(repo.getActiveHost())
        private set

    private var _locale by mutableStateOf(activeSite.locale)
        private set

    var videos by mutableStateOf<LoadState<List<VideoCard>>>(LoadState.Idle)
        private set

    val filteredVideos: List<VideoCard>
        get() = when (val state = videos) {
            is LoadState.Success -> state.data
            else -> emptyList()
        }

    var currentSection by mutableStateOf(MissAvSection.New)
        private set

    var currentSectionPage by mutableStateOf(1)
        private set

    var canLoadMoreSection by mutableStateOf(true)
        private set

    var isLoadingMoreSection by mutableStateOf(false)
        private set

    private var _searchKeyword by mutableStateOf("")
        private set

    var detailState by mutableStateOf<LoadState<VideoDetail>>(LoadState.Idle)
        private set

    var downloads by mutableStateOf<List<DownloadTask>>(emptyList())
        private set

    var uiMessage by mutableStateOf<String?>(null)
        private set

    var autoCloudflareEnabled by mutableStateOf(true)
        private set

    private var _darkMode by mutableStateOf(false)

    private var _privacyMode by mutableStateOf(false)

    val darkMode: Boolean
        get() = _darkMode

    val privacyMode: Boolean
        get() = _privacyMode

    var recentSearches by mutableStateOf<List<String>>(emptyList())
        private set

    var favoriteVideos by mutableStateOf<List<VideoCard>>(emptyList())
        private set

    var watchHistory by mutableStateOf<List<HistoryItem>>(emptyList())
        private set

    var searchSiteStates by mutableStateOf<Map<String, Boolean>>(emptyMap())
        private set

    var missavSearchHostStates by mutableStateOf<Map<String, Boolean>>(emptyMap())
        private set

    var githubReleaseRepo by mutableStateOf("")
        private set

    var githubUpdateInProgress by mutableStateOf(false)
        private set

    val currentVersionName: String = BuildConfig.VERSION_NAME

    val locale: String
        get() = _locale

    val searchKeyword: String
        get() = _searchKeyword

    val availableSites: List<SiteProfile> = SiteRegistry.all

    init {
        restoreCloudflareBypassFromStorage(hostState)
        autoCloudflareEnabled = cfBypassPrefs.getBoolean(CF_AUTO_PROMPT_PREF, true)
        searchSiteStates = restoreSearchSiteStates()
        missavSearchHostStates = restoreMissavSearchHostStates()
        githubReleaseRepo = localLibraryPrefs.getString(GITHUB_UPDATE_REPO_KEY, DEFAULT_GITHUB_UPDATE_REPO)
            .orEmpty()
            .ifBlank { DEFAULT_GITHUB_UPDATE_REPO }
        applySearchPreferences()
        recentSearches = restoreRecentSearches()
        favoriteVideos = restoreFavoriteVideos()
        watchHistory = restoreWatchHistory()
        loadSection(MissAvSection.New)

        downloadCollectJob = downloadCoordinator.tasks
            .onEach { downloads = it }
            .launchIn(viewModelScope)
    }

    fun setLocale(locale: String) {
        _locale = locale
        loadSection(currentSection)
    }

    fun setSite(site: SiteProfile) {
        if (!site.enabled) {
            uiMessage = appContext.getString(R.string.site_disabled_format, site.name)
            return
        }
        activeSite = site
        _locale = site.locale
        setHost(site.hosts.firstOrNull() ?: hostState)
    }

    fun setHost(host: String) {
        hostState = host
        repo.setHost(host)
        restoreCloudflareBypassFromStorage(host)
        loadSection(currentSection)
    }

    fun updateAutoCloudflareEnabled(enabled: Boolean) {
        autoCloudflareEnabled = enabled
        cfBypassPrefs.edit().putBoolean(CF_AUTO_PROMPT_PREF, enabled).apply()
    }

    fun setDarkMode(enabled: Boolean) {
        _darkMode = enabled
    }

    fun setPrivacyMode(enabled: Boolean) {
        _privacyMode = enabled
    }

    fun setSection(section: MissAvSection) {
        currentSection = section
        _searchKeyword = ""
        detailState = LoadState.Idle
        loadSection(section)
    }

    fun setSearchKeyword(query: String) {
        _searchKeyword = query
    }

    fun isSearchSiteEnabled(siteId: String): Boolean {
        return searchSiteStates[siteId] != false
    }

    fun isMissavSearchHostEnabled(host: String): Boolean {
        return missavSearchHostStates[host] != false
    }

    fun updateSearchSiteEnabled(siteId: String, enabled: Boolean) {
        val current = isSearchSiteEnabled(siteId)
        if (current == enabled) return

        val enabledCount = availableSites.count { isSearchSiteEnabled(it.id) }
        if (!enabled && current && enabledCount <= 1) {
            uiMessage = appContext.getString(R.string.settings_search_source_keep_one)
            return
        }

        searchSiteStates = searchSiteStates.toMutableMap().apply {
            put(siteId, enabled)
        }
        persistSearchSiteStates()
        applySearchPreferences()
        if (_searchKeyword.isNotBlank()) {
            search()
        } else {
            loadSection(currentSection)
        }
    }

    fun updateMissavSearchHostEnabled(host: String, enabled: Boolean) {
        val current = isMissavSearchHostEnabled(host)
        if (current == enabled) return

        val enabledHosts = SiteRegistry.missav.hosts.count { isMissavSearchHostEnabled(it) }
        if (!enabled && current && enabledHosts <= 1) {
            uiMessage = appContext.getString(R.string.settings_search_missav_keep_one)
            return
        }

        missavSearchHostStates = missavSearchHostStates.toMutableMap().apply {
            put(host, enabled)
        }
        persistMissavSearchHostStates()
        applySearchPreferences()
        if (_searchKeyword.isNotBlank() && isSearchSiteEnabled(SiteRegistry.missav.id)) {
            search()
        }
    }

    fun updateGithubReleaseRepo(repo: String) {
        githubReleaseRepo = repo
        localLibraryPrefs.edit().putString(GITHUB_UPDATE_REPO_KEY, repo.trim()).apply()
    }

    fun checkGithubReleaseAndInstall() {
        val repoInput = githubReleaseRepo.trim()
        if (repoInput.isBlank()) {
            uiMessage = appContext.getString(R.string.github_update_repo_empty)
            return
        }
        if (githubUpdateInProgress) return

        githubUpdateInProgress = true
        uiMessage = appContext.getString(R.string.github_update_checking)
        viewModelScope.launch {
            runCatching {
                githubReleaseManager.downloadLatestApkAndInstall(repoInput, currentVersionName)
            }.onSuccess { result ->
                uiMessage = when (result) {
                    is GitHubUpdateResult.UpToDate -> appContext.getString(
                        R.string.github_update_latest,
                        result.tagName
                    )
                    is GitHubUpdateResult.InstallStarted -> appContext.getString(
                        R.string.github_update_install_started,
                        result.tagName,
                        result.assetName
                    )
                }
            }.onFailure { error ->
                uiMessage = appContext.getString(
                    R.string.github_update_failed,
                    error.message ?: appContext.getString(R.string.error_network_generic)
                )
            }
            githubUpdateInProgress = false
        }
    }

    fun loadSection(section: MissAvSection) {
        currentSection = section
        currentSectionPage = 1
        canLoadMoreSection = true
        isLoadingMoreSection = false
        if (!isMissavFeedEnabled() && !isJableFeedEnabled()) {
            videos = LoadState.Success(emptyList())
            canLoadMoreSection = false
            uiMessage = appContext.getString(R.string.error_search_source_empty)
            return
        }
        videos = LoadState.Loading
        uiMessage = null
        viewModelScope.launch {
            videos = fetchCurrentFeedPage(section, 1)
            canLoadMoreSection = (videos as? LoadState.Success)?.data?.isNotEmpty() == true
        }
    }

    fun loadNextSectionPage() {
        if (_searchKeyword.isNotBlank() || isLoadingMoreSection || !canLoadMoreSection) return
        val current = videos as? LoadState.Success ?: return
        val nextPage = currentSectionPage + 1
        isLoadingMoreSection = true

        viewModelScope.launch {
            when (val result = fetchCurrentFeedPage(currentSection, nextPage)) {
                is LoadState.Success -> {
                    val merged = mergePagedVideos(current.data, result.data)
                    val appended = merged.size > current.data.size
                    videos = LoadState.Success(merged)
                    if (appended) {
                        currentSectionPage = nextPage
                    }
                    canLoadMoreSection = result.data.isNotEmpty() && appended
                }
                is LoadState.Error -> {
                    uiMessage = result.message
                    canLoadMoreSection = false
                }
                is LoadState.CloudflareChallenge -> {
                    uiMessage = appContext.getString(R.string.message_content_unavailable_retry)
                    canLoadMoreSection = false
                }
                else -> {}
            }
            isLoadingMoreSection = false
        }
    }

    fun search() {
        val query = _searchKeyword.trim()
        if (query.isBlank()) {
            loadSection(currentSection)
            return
        }
        currentSectionPage = 1
        canLoadMoreSection = false
        isLoadingMoreSection = false
        addRecentSearch(query)

        detailState = LoadState.Idle
        videos = LoadState.Loading
        uiMessage = null
        viewModelScope.launch {
            videos = withCloudflareRecovery(hostState) {
                repo.search(query, _locale)
            }
        }
    }

    fun buildMissavSearchFallbackUrl(query: String = _searchKeyword): String? {
        val normalized = query.trim()
        if (normalized.isBlank() || !isSearchSiteEnabled(SiteRegistry.missav.id)) return null
        return repo.buildMissavWebSearchUrl(normalized, _locale)
    }

    fun applyMissavSearchFallbackHtml(query: String, html: String, pageUrl: String) {
        val expectedQuery = query.trim()
        if (expectedQuery.isBlank() || _searchKeyword.trim() != expectedQuery) return

        val baseUrl = pageUrl.trim().ifBlank { hostState }
        val parsed = repo.parseMissavSearchHtml(html.trim(), baseUrl)
            .mapNotNull(::sanitizeVideo)
        if (parsed.isEmpty()) return

        val current = (videos as? LoadState.Success)?.data.orEmpty()
        videos = LoadState.Success(mergePagedVideos(current, parsed))

        val normalizedHost = normalizeHostInput(baseUrl)
        if (repo.syncCookiesFromWebCookies(normalizedHost)) {
            markCloudflareBypass(normalizedHost)
        }
    }

    fun triggerSearch(query: String) {
        _searchKeyword = query.trim()
        search()
    }

    fun removeRecentSearch(query: String) {
        recentSearches = recentSearches.filterNot { it.equals(query, ignoreCase = true) }
        persistRecentSearches()
    }

    fun clearRecentSearches() {
        recentSearches = emptyList()
        persistRecentSearches()
    }

    fun loadVideoDetail(item: VideoCard) {
        if (item.href.isBlank()) {
            detailState = LoadState.Error(appContext.getString(R.string.error_invalid_content_link))
            return
        }

        detailState = LoadState.Loading
        val source = item.sourceSite.ifBlank { inferSource(item.href) }
        val sourcesFromList = buildAvailableSourceOptions(item)
        val thumbnails = buildDetailThumbnails(item)

        viewModelScope.launch {
            val result = withCloudflareRecovery(item.href) {
                repo.getVideoDetail(item.href)
            }
            detailState = when (result) {
                is LoadState.Success -> {
                    val mergedThumbs = (result.data.thumbnails + thumbnails).filter { it.isNotBlank() }.distinct()
                    val mergedSources = mergeSourceOptions(item, source, sourcesFromList)
                    val resolved = result.data.copy(
                        sourceSite = source,
                        sourceUrl = item.href,
                        availableSources = mergedSources,
                        thumbnails = mergedThumbs
                    )

                    addWatchHistory(
                        VideoCard(
                            code = resolved.code.ifBlank { item.code },
                            title = resolved.title.ifBlank { item.title },
                            href = item.href,
                            thumbnail = mergedThumbs.firstOrNull() ?: item.thumbnail,
                            sourceSite = source
                        )
                    )

                    LoadState.Success(resolved)
                }
                is LoadState.Error,
                is LoadState.CloudflareChallenge -> LoadState.Success(
                    buildFallbackDetail(item, source, sourcesFromList, thumbnails)
                )
                else -> result
            }
        }
    }

    fun loadVideoDetail(url: String) {
        if (url.isBlank()) {
            detailState = LoadState.Error(appContext.getString(R.string.error_invalid_video_url))
            return
        }

        val source = inferSource(url)
        detailState = LoadState.Loading
        viewModelScope.launch {
            val knownCard = findKnownVideoCard(url)
            val result = withCloudflareRecovery(url) {
                repo.getVideoDetail(url)
            }
            detailState = when (result) {
                is LoadState.Success -> {
                    val resolved = result.data.copy(
                        sourceSite = source,
                        sourceUrl = url,
                        availableSources = listOf(VideoSourceOption(source, url)).distinctBy { it.url }
                    )
                    addWatchHistory(
                        VideoCard(
                            code = resolved.code,
                            title = resolved.title,
                            href = url,
                            thumbnail = resolved.thumbnails.firstOrNull(),
                            sourceSite = source
                        )
                    )
                    LoadState.Success(resolved)
                }
                is LoadState.Error,
                is LoadState.CloudflareChallenge -> {
                    val fallbackCard = knownCard ?: VideoCard(
                        code = extractCodeFromUrl(url),
                        title = extractCodeFromUrl(url).ifBlank { url.substringBefore('?').trimEnd('/').substringAfterLast('/') },
                        href = url,
                        thumbnail = null,
                        sourceSite = source
                    )
                    LoadState.Success(
                        buildFallbackDetail(
                            item = fallbackCard,
                            source = source,
                            sourcesFromList = buildAvailableSourceOptions(fallbackCard) + listOf(VideoSourceOption(source, url)),
                            thumbnails = buildDetailThumbnails(fallbackCard)
                        )
                    )
                }
                else -> result
            }
        }
    }

    fun retryCloudflareFix() {
        syncCloudflareCookies()
    }

    fun onCloudflareWarmupReturned(solved: Boolean, host: String = hostState) {
        if (solved) {
            val normalized = normalizeHostInput(host)
            if (normalized.isNotBlank()) {
                hostState = normalized
                repo.setHost(normalized)
            }
            val synced = repo.syncCookiesFromWebCookies(hostState)
            markCloudflareBypass(hostState)
            uiMessage = if (synced) {
                appContext.getString(R.string.message_access_refreshed)
            } else {
                appContext.getString(R.string.message_access_refresh_attempted)
            }
            loadSection(currentSection)
        } else {
            uiMessage = appContext.getString(R.string.message_access_not_updated)
        }
    }

    fun syncCloudflareCookies(host: String = hostState): Boolean {
        val ok = repo.syncCookiesFromWebCookies(host)
        if (ok) {
            markCloudflareBypass(host)
        }
        uiMessage = if (ok) {
            appContext.getString(R.string.message_access_refreshed)
        } else {
            appContext.getString(R.string.message_access_refresh_failed)
        }
        if (ok) {
            loadSection(currentSection)
        }
        return ok
    }

    fun startDownloadFromDetail(detail: VideoDetail) {
        val source = resolveDownloadSource(detail)
        if (source.isNullOrBlank()) {
            uiMessage = appContext.getString(R.string.message_download_source_unavailable)
            return
        }
        val referer = detail.sourceUrl.ifBlank { detail.href }
        downloadCoordinator.startDownload(
            detail.title.ifBlank { detail.code },
            detail.code,
            source,
            referer
        )
        uiMessage = appContext.getString(R.string.message_download_added)
    }

    fun showMessage(message: String) {
        uiMessage = message
    }

    fun toggleFavorite(video: VideoCard) {
        val normalized = sanitizeVideo(video) ?: return
        val key = videoIdentityKey(normalized)
        favoriteVideos = if (favoriteVideos.any { videoIdentityKey(it) == key }) {
            favoriteVideos.filterNot { videoIdentityKey(it) == key }
        } else {
            buildList {
                add(normalized)
                favoriteVideos
                    .filterNot { videoIdentityKey(it) == key }
                    .take(MAX_FAVORITE - 1)
                    .forEach(::add)
            }
        }
        persistFavorites()
    }

    fun clearFavorites() {
        favoriteVideos = emptyList()
        persistFavorites()
    }

    fun removeFavorite(video: VideoCard) {
        val key = videoIdentityKey(video)
        favoriteVideos = favoriteVideos.filterNot { videoIdentityKey(it) == key }
        persistFavorites()
    }

    fun isFavorite(video: VideoCard): Boolean {
        val key = videoIdentityKey(video)
        return favoriteVideos.any { videoIdentityKey(it) == key }
    }

    fun cancelDownload(id: String) {
        downloadCoordinator.cancel(id)
    }

    fun clearFinishedDownloads() {
        downloadCoordinator.clearFinished()
    }

    fun addWatchHistory(video: VideoCard) {
        val normalized = sanitizeVideo(video) ?: return
        val key = videoIdentityKey(normalized)
        val updated = buildList {
            add(HistoryItem(video = normalized, playedAt = System.currentTimeMillis()))
            watchHistory
                .filterNot { videoIdentityKey(it.video) == key }
                .take(MAX_HISTORY - 1)
                .forEach(::add)
        }
        watchHistory = updated
        persistWatchHistory()
    }

    fun updateWatchHistoryProgress(video: VideoCard, progressPercent: Int) {
        val normalized = sanitizeVideo(video) ?: return
        val key = videoIdentityKey(normalized)
        val boundedProgress = progressPercent.coerceIn(0, 100)
        watchHistory = buildList {
            add(
                HistoryItem(
                    video = normalized,
                    playedAt = System.currentTimeMillis(),
                    progressPercent = boundedProgress
                )
            )
            watchHistory
                .filterNot { videoIdentityKey(it.video) == key }
                .take(MAX_HISTORY - 1)
                .forEach(::add)
        }
        persistWatchHistory()
    }

    fun clearWatchHistory() {
        watchHistory = emptyList()
        persistWatchHistory()
    }

    fun removeWatchHistory(video: VideoCard) {
        val key = videoIdentityKey(video)
        watchHistory = watchHistory.filterNot { videoIdentityKey(it.video) == key }
        persistWatchHistory()
    }

    fun clearMessage() {
        uiMessage = null
    }

    fun updateDetailResolvedStream(streamUrl: String) {
        val normalized = streamUrl.trim()
        if (normalized.isBlank()) return

        val current = detailState as? LoadState.Success ?: return
        val detail = current.data
        if (detail.hlsUrl.equals(normalized, ignoreCase = true)) return

        val mergedSources = buildList {
            add(VideoSourceOption(detail.sourceSite, normalized, detail.title.ifBlank { detail.code }))
            detail.availableSources.forEach(::add)
        }.distinctBy { it.url }

        detailState = LoadState.Success(
            detail.copy(
                hlsUrl = normalized,
                availableSources = mergedSources
            )
        )
    }

    fun clearDetail() {
        detailState = LoadState.Idle
    }

    private suspend fun <T> withCloudflareRecovery(host: String, request: suspend () -> LoadState<T>): LoadState<T> {
        val normalizedHost = normalizeHostInput(host)
        if (hasCloudflareRecovery(normalizedHost)) {
            repo.syncCookiesFromWebCookies(normalizedHost)
        }

        val first = request()
        if (first is LoadState.Success) {
            markCloudflareBypass(normalizedHost)
            return first
        }

        if (first !is LoadState.CloudflareChallenge) return first

        if (!autoCloudflareEnabled) {
            return LoadState.Error(appContext.getString(R.string.message_content_unavailable_retry))
        }

        if (!shouldRetryCloudflare(host) && !hasCloudflareRecovery(host)) {
            return LoadState.Error(appContext.getString(R.string.message_content_unavailable_retry))
        }

        if (hasCloudflareRecovery(normalizedHost)) {
            repo.syncCookiesFromWebCookies(normalizedHost)
        }

        val synced = repo.syncCookiesFromWebCookies(normalizedHost)
        if (!synced) return LoadState.Error(appContext.getString(R.string.message_content_unavailable_retry))

        markCloudflareBypass(normalizedHost)
        val retried = request()
        return if (retried is LoadState.CloudflareChallenge) {
            LoadState.Error(appContext.getString(R.string.message_content_unavailable_retry))
        } else {
            markCloudflareBypass(normalizedHost)
            retried
        }
    }

    private fun normalizeHostInput(host: String): String {
        val trimmed = host.trim().trimEnd('/')
        if (trimmed.isBlank()) return hostState

        val normalized = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
        val uri = runCatching { URI(normalized) }.getOrNull()
        val scheme = uri?.scheme
        val authority = uri?.host ?: uri?.authority
        return if (!scheme.isNullOrBlank() && !authority.isNullOrBlank()) {
            "$scheme://$authority"
        } else {
            val sanitizedHost = trimmed
                .removePrefix("https://")
                .removePrefix("http://")
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
            if (sanitizedHost.isBlank()) hostState else "https://$sanitizedHost"
        }
    }

    private fun normalizeHostKey(host: String): String {
        return host.trim()
            .lowercase(Locale.ROOT)
            .trimEnd('/')
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore("/")
    }

    private fun inferSource(url: String): String {
        return when {
            url.contains("jable", ignoreCase = true) -> "Jable.tv"
            url.contains("av01.media", ignoreCase = true) || url.contains("av01.tv", ignoreCase = true) -> "AV01"
            url.contains("123av.com", ignoreCase = true) || url.contains("njav.tv", ignoreCase = true) -> "123AV"
            else -> "MissAV"
        }
    }

    private fun isMissavFeedEnabled(): Boolean = isSearchSiteEnabled(SiteRegistry.missav.id)

    private fun isJableFeedEnabled(): Boolean = isSearchSiteEnabled(SiteRegistry.jable.id)

    private suspend fun fetchCurrentFeedPage(section: MissAvSection, page: Int): LoadState<List<VideoCard>> {
        return when {
            isMissavFeedEnabled() -> withCloudflareRecovery(hostState) {
                repo.fetchSection(section, _locale, page)
            }
            isJableFeedEnabled() -> repo.fetchJableFeed(page)
            else -> LoadState.Success(emptyList())
        }
    }

    private fun applySearchPreferences() {
        repo.updateSearchPreferences(
            siteIds = availableSites.filter { isSearchSiteEnabled(it.id) }.map { it.id }.toSet(),
            missavHosts = SiteRegistry.missav.hosts.filter { isMissavSearchHostEnabled(it) }
        )
    }

    private fun resolveDownloadSource(detail: VideoDetail): String? {
        return detail.hlsUrl?.takeIf { it.isNotBlank() }
            ?: detail.availableSources
                .asSequence()
                .map { it.url }
                .firstOrNull { url ->
                    url.contains(".m3u8", ignoreCase = true) ||
                        url.contains(".mp4", ignoreCase = true) ||
                        url.contains(".mkv", ignoreCase = true)
                }
    }

    private fun addRecentSearch(query: String) {
        val safeQuery = query.trim()
        if (safeQuery.isBlank()) return

        val normalized = safeQuery.lowercase(Locale.ROOT)
        recentSearches = buildList {
            add(safeQuery)
            recentSearches.filterNot { it.lowercase(Locale.ROOT) == normalized }.forEach(::add)
        }.take(MAX_SEARCH_HISTORY)
        persistRecentSearches()
    }

    private fun buildAvailableSourceOptions(item: VideoCard): List<VideoSourceOption> {
        val code = item.code.lowercase(Locale.ROOT).trim()
        val base = mutableListOf<VideoSourceOption>().apply {
            add(VideoSourceOption(item.sourceSite.ifBlank { inferSource(item.href) }, item.href, item.title.ifBlank { item.code }))
        }
        if (code.isBlank()) return base

        val list = when (val current = videos) {
            is LoadState.Success -> current.data.filter {
                it.code.lowercase(Locale.ROOT).trim() == code
            }
            else -> emptyList()
        }
        if (list.isEmpty()) return base

        base.addAll(
            list.distinctBy { it.href }.map {
                VideoSourceOption(it.sourceSite.ifBlank { inferSource(it.href) }, it.href, it.title.ifBlank { it.code })
            }
        )

        return base.distinctBy { it.url }
    }

    private fun buildDetailThumbnails(item: VideoCard): List<String> {
        val code = item.code.lowercase(Locale.ROOT).trim()
        val collected = buildList {
            item.thumbnail?.takeIf { it.isNotBlank() }?.let(::add)
            val list = when (val current = videos) {
                is LoadState.Success -> current.data
                else -> emptyList()
            }
            list.forEach { card ->
                if (card.href == item.href || (code.isNotBlank() && card.code.lowercase(Locale.ROOT).trim() == code)) {
                    card.thumbnail?.takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }
        return collected.distinct()
    }

    private fun buildFallbackRecommendations(item: VideoCard): List<VideoCard> {
        val current = videos as? LoadState.Success ?: return emptyList()
        val itemCode = item.code.lowercase(Locale.ROOT).trim()
        return current.data
            .asSequence()
            .filterNot { card ->
                card.href.equals(item.href, ignoreCase = true) ||
                    (itemCode.isNotBlank() && card.code.lowercase(Locale.ROOT).trim() == itemCode)
            }
            .take(12)
            .toList()
    }

    private fun mergePagedVideos(existing: List<VideoCard>, incoming: List<VideoCard>): List<VideoCard> {
        val merged = linkedMapOf<String, VideoCard>()
        existing.forEach { video ->
            merged[pagedVideoKey(video)] = video
        }
        incoming.forEach { video ->
            val key = pagedVideoKey(video)
            val current = merged[key]
            merged[key] = if (current == null || shouldPreferPagedVideo(current, video)) video else current
        }
        return merged.values.toList()
    }

    private fun pagedVideoKey(video: VideoCard): String {
        return if (video.href.isNotBlank()) {
            video.href.lowercase(Locale.ROOT).trim()
        } else {
            "${video.sourceSite.lowercase(Locale.ROOT)}|${video.code.lowercase(Locale.ROOT)}"
        }
    }

    private fun shouldPreferPagedVideo(current: VideoCard, candidate: VideoCard): Boolean {
        val currentHasThumb = !current.thumbnail.isNullOrBlank()
        val candidateHasThumb = !candidate.thumbnail.isNullOrBlank()
        if (candidateHasThumb != currentHasThumb) return candidateHasThumb
        return candidate.title.trim().length > current.title.trim().length
    }

    private fun buildFallbackDetail(
        item: VideoCard,
        source: String,
        sourcesFromList: List<VideoSourceOption>,
        thumbnails: List<String>
    ): VideoDetail {
        val fallbackCode = item.code.ifBlank { extractCodeFromUrl(item.href) }
        val fallbackThumbs = thumbnails.filter { it.isNotBlank() }.ifEmpty {
            item.thumbnail?.takeIf { it.isNotBlank() }?.let(::listOf) ?: emptyList()
        }
        return VideoDetail(
            code = fallbackCode,
            title = item.title.ifBlank { fallbackCode.ifBlank { appContext.getString(R.string.video_untitled) } },
            href = item.href,
            thumbnails = fallbackThumbs,
            sourceSite = source,
            sourceUrl = item.href,
            availableSources = mergeSourceOptions(item, source, sourcesFromList),
            recommendations = buildFallbackRecommendations(item)
        )
    }

    private fun findKnownVideoCard(url: String): VideoCard? {
        val normalizedUrl = url.trim()
        if (normalizedUrl.isBlank()) return null
        val targetCode = extractCodeFromUrl(normalizedUrl)
        val pool = buildList {
            addAll((videos as? LoadState.Success)?.data.orEmpty())
            addAll(favoriteVideos)
            addAll(watchHistory.map { it.video })
        }
        return pool.firstOrNull { it.href.equals(normalizedUrl, ignoreCase = true) }
            ?: pool.firstOrNull { targetCode.isNotBlank() && it.code.equals(targetCode, ignoreCase = true) }
    }

    private fun mergeSourceOptions(
        item: VideoCard,
        source: String,
        sourcesFromList: List<VideoSourceOption>
    ): List<VideoSourceOption> {
        val dedupSources = linkedMapOf<String, VideoSourceOption>()
        sourcesFromList.forEach { option ->
            if (option.url.isNotBlank()) {
                dedupSources[option.url] = option
            }
        }
        if (!dedupSources.containsKey(item.href)) {
            dedupSources[item.href] = VideoSourceOption(source, item.href, item.title.ifBlank { item.code })
        }
        return dedupSources.values.toList()
    }

    private fun extractCodeFromUrl(url: String): String {
        return url.substringBefore('?')
            .substringBefore('#')
            .trimEnd('/')
            .substringAfterLast('/')
            .lowercase(Locale.ROOT)
    }

    private fun markCloudflareBypass(host: String) {
        val key = normalizeHostKey(host)
        if (key.isBlank()) return
        val until = System.currentTimeMillis() + CF_BYPASS_TTL_MS
        cloudflareBypassUntil[key] = until
        cfBypassPrefs.edit().putLong(CF_PREF_KEY_PREFIX + key, until).apply()
    }

    private fun restoreCloudflareBypassFromStorage(host: String) {
        val key = normalizeHostKey(host)
        if (key.isBlank()) return
        val now = System.currentTimeMillis()
        val persisted = cfBypassPrefs.getLong(CF_PREF_KEY_PREFIX + key, 0L)
        if (persisted > now) {
            cloudflareBypassUntil[key] = persisted
            uiMessage = null
        }
    }

    private fun hasCloudflareRecovery(host: String): Boolean {
        val key = normalizeHostKey(host)
        if (key.isBlank()) return false
        val now = System.currentTimeMillis()
        val persisted = cfBypassPrefs.getLong(CF_PREF_KEY_PREFIX + key, 0L)
        val local = cloudflareBypassUntil[key] ?: 0L
        val until = maxOf(local, persisted)
        if (until > now) {
            cloudflareBypassUntil[key] = until
            return true
        }
        if (local != 0L) {
            cloudflareBypassUntil.remove(key)
        }
        if (persisted != 0L) {
            cfBypassPrefs.edit().remove(CF_PREF_KEY_PREFIX + key).apply()
        }
        return false
    }

    private fun shouldRetryCloudflare(host: String): Boolean {
        val key = normalizeHostKey(host)
        if (key.isBlank()) return false
        val now = System.currentTimeMillis()
        val lastRetry = cloudflareRetryAt[key] ?: 0L
        if (now - lastRetry < CF_RETRY_GAP_MS) return false
        cloudflareRetryAt[key] = now
        return true
    }

    private fun sanitizeVideo(video: VideoCard): VideoCard? {
        val href = video.href.trim()
        if (href.isBlank()) return null
        val source = video.sourceSite.ifBlank { inferSource(href) }
        val code = video.code.trim()
        val title = video.title.trim().ifBlank {
            code.ifBlank {
                href.substringAfterLast('/').substringBefore('?').ifBlank { appContext.getString(R.string.content_unnamed) }
            }
        }
        val thumbnail = video.thumbnail?.trim()?.takeIf { it.isNotBlank() }
        return video.copy(
            code = code,
            title = title,
            href = href,
            thumbnail = thumbnail,
            sourceSite = source
        )
    }

    private fun persistRecentSearches() {
        val array = JSONArray()
        recentSearches.forEach(array::put)
        persistJsonArray(RECENT_SEARCHES_KEY, array)
    }

    private fun persistSearchSiteStates() {
        persistJsonObject(
            SEARCH_SITES_KEY,
            JSONObject().apply {
                availableSites.forEach { put(it.id, isSearchSiteEnabled(it.id)) }
            }
        )
    }

    private fun restoreSearchSiteStates(): Map<String, Boolean> {
        val defaults = availableSites.associate { it.id to true }
        return runCatching {
            val raw = localLibraryPrefs.getString(SEARCH_SITES_KEY, null).orEmpty()
            if (raw.isBlank()) return@runCatching defaults
            val json = JSONObject(raw)
            defaults.mapValues { (siteId, defaultValue) ->
                if (json.has(siteId)) json.optBoolean(siteId, defaultValue) else defaultValue
            }
        }.getOrElse { defaults }
    }

    private fun persistMissavSearchHostStates() {
        persistJsonObject(
            SEARCH_MISSAV_HOSTS_KEY,
            JSONObject().apply {
                SiteRegistry.missav.hosts.forEach { put(it, isMissavSearchHostEnabled(it)) }
            }
        )
    }

    private fun restoreMissavSearchHostStates(): Map<String, Boolean> {
        val defaults = SiteRegistry.missav.hosts.associateWith { true }
        return runCatching {
            val raw = localLibraryPrefs.getString(SEARCH_MISSAV_HOSTS_KEY, null).orEmpty()
            if (raw.isBlank()) return@runCatching defaults
            val json = JSONObject(raw)
            defaults.mapValues { (host, defaultValue) ->
                if (json.has(host)) json.optBoolean(host, defaultValue) else defaultValue
            }
        }.getOrElse { defaults }
    }

    private fun restoreRecentSearches(): List<String> {
        return runCatching {
            val raw = localLibraryPrefs.getString(RECENT_SEARCHES_KEY, null).orEmpty()
            if (raw.isBlank()) return@runCatching emptyList()
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    array.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
                }
            }.distinct().take(MAX_SEARCH_HISTORY)
        }.getOrElse { emptyList() }
    }

    private fun persistFavorites() {
        val array = JSONArray()
        favoriteVideos.take(MAX_FAVORITE).forEach { video ->
            array.put(videoToJson(video))
        }
        persistJsonArray(FAVORITES_KEY, array)
    }

    private fun restoreFavoriteVideos(): List<VideoCard> {
        return runCatching {
            val raw = localLibraryPrefs.getString(FAVORITES_KEY, null).orEmpty()
            if (raw.isBlank()) return@runCatching emptyList()
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val video = videoFromJson(array.optJSONObject(index)) ?: continue
                    add(video)
                }
            }.distinctBy(::videoIdentityKey).take(MAX_FAVORITE)
        }.getOrElse { emptyList() }
    }

    private fun persistWatchHistory() {
        val array = JSONArray()
        watchHistory.take(MAX_HISTORY).forEach { item ->
            array.put(historyToJson(item))
        }
        persistJsonArray(HISTORY_KEY, array)
    }

    private fun restoreWatchHistory(): List<HistoryItem> {
        return runCatching {
            val raw = localLibraryPrefs.getString(HISTORY_KEY, null).orEmpty()
            if (raw.isBlank()) return@runCatching emptyList()
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = historyFromJson(array.optJSONObject(index)) ?: continue
                    add(item)
                }
            }.distinctBy { videoIdentityKey(it.video) }.take(MAX_HISTORY)
        }.getOrElse { emptyList() }
    }

    private fun persistJsonArray(key: String, array: JSONArray) {
        val editor = localLibraryPrefs.edit()
        if (array.length() == 0) {
            editor.remove(key)
        } else {
            editor.putString(key, array.toString())
        }
        editor.apply()
    }

    private fun persistJsonObject(key: String, value: JSONObject) {
        localLibraryPrefs.edit().putString(key, value.toString()).apply()
    }

    private fun videoToJson(video: VideoCard): JSONObject {
        return JSONObject().apply {
            put("code", video.code)
            put("title", video.title)
            put("href", video.href)
            put("thumbnail", video.thumbnail)
            put("sourceSite", video.sourceSite)
        }
    }

    private fun videoFromJson(raw: JSONObject?): VideoCard? {
        if (raw == null) return null
        val href = raw.optString("href").trim()
        if (href.isBlank()) return null
        return sanitizeVideo(
            VideoCard(
                code = raw.optString("code").trim(),
                title = raw.optString("title").trim(),
                href = href,
                thumbnail = raw.optString("thumbnail").trim().ifBlank { null },
                sourceSite = raw.optString("sourceSite").trim()
            )
        )
    }

    private fun historyToJson(item: HistoryItem): JSONObject {
        return JSONObject().apply {
            put("video", videoToJson(item.video))
            put("playedAt", item.playedAt)
            put("progressPercent", item.progressPercent)
        }
    }

    private fun historyFromJson(raw: JSONObject?): HistoryItem? {
        if (raw == null) return null
        val video = videoFromJson(raw.optJSONObject("video")) ?: return null
        return HistoryItem(
            video = video,
            playedAt = raw.optLong("playedAt"),
            progressPercent = raw.optInt("progressPercent")
        )
    }

    private fun videoIdentityKey(video: VideoCard): String {
        val source = video.sourceSite.ifBlank { inferSource(video.href) }
        return "${video.code.ifBlank { video.href }}|$source|${video.href}"
    }

    override fun onCleared() {
        super.onCleared()
        downloadCollectJob.cancel()
        downloadCoordinator.close()
    }
}
