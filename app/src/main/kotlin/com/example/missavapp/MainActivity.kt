package com.example.missavapp

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.StringRes
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.example.missavapp.data.model.HistoryItem
import com.example.missavapp.data.model.LoadState
import com.example.missavapp.data.model.MissAvSection
import com.example.missavapp.data.model.VideoCard
import com.example.missavapp.data.model.VideoDetail
import com.example.missavapp.data.site.SiteRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.Locale

private const val CF_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 13; Pixel 7 Build/TQ3A.230901.001) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

private const val MISSAV_RUNTIME_SOURCE_SCRIPT = """
    (function() {
        const direct = window.source1280 || window.source842 || window.source || '';
        if (direct) return direct;
        const html = document.documentElement ? document.documentElement.innerHTML : '';
        const match = html.match(/https?:\/\/[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?/i);
        return match ? match[0] : '';
    })();
"""

private val BgDefault = Color(0xFFF7F8FA)
private val TxtPrimary = Color(0xFF18191C)
private val TxtSecondary = Color(0xFF61666D)
private val BrandBlue = Color(0xFF00AEEC)
private val DividerColor = Color(0xFFE3E5E7)
private val BrandPink = Color(0xFFDA5A9F)
private val BrandBlueDark = Color(0xFF0A8ED1)
private val DarkBg = Color(0xFF101318)
private val DarkSurface = Color(0xFF171B20)
private val DarkCard = Color(0xFF1F242A)
private val DarkTxtPrimary = Color(0xFFF1F3F5)
private val DarkTxtSecondary = Color(0xFFB1B9C3)

private val LightColors = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    secondary = BrandPink,
    background = BgDefault,
    onBackground = TxtPrimary,
    surface = Color.White,
    onSurface = TxtPrimary
)

private val DarkColors = darkColorScheme(
    primary = BrandBlueDark,
    onPrimary = Color.White,
    secondary = BrandPink,
    background = DarkBg,
    onBackground = DarkTxtPrimary,
    surface = DarkSurface,
    onSurface = DarkTxtPrimary
)

private enum class MainTab(@StringRes val titleRes: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Home(R.string.tab_home, Icons.Filled.Home),
    Search(R.string.tab_search, Icons.Filled.Search),
    Channels(R.string.tab_channels, Icons.Filled.FilterList),
    Collections(R.string.tab_collections, Icons.Filled.Bookmark),
    Me(R.string.tab_me, Icons.Filled.Person),
}

private val PrimaryTabs = listOf(
    MainTab.Home,
    MainTab.Search,
    MainTab.Collections,
    MainTab.Me
)

private enum class CollectionMode(@StringRes val titleRes: Int) {
    Favorite(R.string.collection_favorite),
    History(R.string.collection_history)
}

private enum class HomeFeedTab(val section: MissAvSection, @StringRes val titleRes: Int) {
    Recommend(MissAvSection.New, R.string.feed_recommend),
    Latest(MissAvSection.Release, R.string.feed_latest),
    Hot(MissAvSection.Uncensored, R.string.feed_hot)
}

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val colors = if (vm.darkMode) DarkColors else LightColors
            MaterialTheme(colorScheme = colors) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppScreen(vm = vm)
                }
            }
        }
    }
}

private fun normalizeSearchResults(videos: List<VideoCard>, query: String = ""): List<VideoCard> {
    val normalizedQuery = query.trim().lowercase(Locale.ROOT)
    val dedup = mutableMapOf<String, VideoCard>()
    videos.forEach { item ->
        val code = item.code.lowercase(Locale.ROOT).ifBlank { item.href.substringAfterLast("/").substringBefore('?').lowercase(Locale.ROOT) }
        val source = item.sourceSite.ifBlank { "MissAV" }.lowercase(Locale.ROOT)
        val key = "$source|$code"
        val current = dedup[key]
        if (current == null || isPreferredDisplayCard(item, current, normalizedQuery)) {
            dedup[key] = item
        }
    }
    return dedup.values.sortedWith(
        compareBy<VideoCard> { searchDisplayRank(it, normalizedQuery) }
            .thenByDescending { if (it.thumbnail.isNullOrBlank()) 0 else 1 }
            .thenBy { it.title.lowercase(Locale.ROOT) }
        .thenBy { it.sourceSite.lowercase(Locale.ROOT) }
    )
}

private fun detailMatchesTarget(detail: VideoDetail, target: String?): Boolean {
    val normalizedTarget = target?.trim().orEmpty()
    if (normalizedTarget.isBlank()) return false
    val sourceUrl = detail.sourceUrl.trim()
    if (sourceUrl.isBlank()) return false
    return sourceUrl.equals(normalizedTarget, ignoreCase = true) ||
        sourceUrl.contains(normalizedTarget, ignoreCase = true) ||
        normalizedTarget.contains(sourceUrl, ignoreCase = true)
}

@Composable
private fun SourcePill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    compact: Boolean = false,
    selectedBackground: Color = BrandBlue
) {
    val backgroundColor = if (selected) selectedBackground.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface
    val contentColor = if (selected) selectedBackground else TxtSecondary
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(if (compact) 30.dp else 34.dp),
        shape = RoundedCornerShape(999.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = backgroundColor,
            contentColor = contentColor,
            disabledContainerColor = backgroundColor.copy(alpha = 0.6f),
            disabledContentColor = contentColor.copy(alpha = 0.6f)
        ),
        contentPadding = PaddingValues(horizontal = if (compact) 10.dp else 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = contentColor,
            maxLines = 1
        )
    }
}

@Composable
private fun AppScreen(vm: MainViewModel) {
    var currentTab by rememberSaveable { mutableStateOf(MainTab.Home) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showCloudflareDialog by rememberSaveable { mutableStateOf(false) }
    var cloudflareHost by remember { mutableStateOf(vm.hostState) }
    var collectionMode by rememberSaveable { mutableStateOf(CollectionMode.Favorite) }
    var playingDetailUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingPlaybackUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDownloadDetail by remember { mutableStateOf<VideoDetail?>(null) }
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val playingDetail = when (val detailState = vm.detailState) {
        is LoadState.Success -> {
            val target = playingDetailUrl
            detailState.data.takeIf { detailMatchesTarget(it, target) }
        }
        else -> null
    }
    val downloadPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val pending = pendingDownloadDetail
        pendingDownloadDetail = null
        if (granted && pending != null) {
            vm.startDownloadFromDetail(pending)
        } else if (!granted) {
            vm.showMessage(context.getString(R.string.message_download_permission_required))
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val pending = pendingDownloadDetail
        pendingDownloadDetail = null
        if (pending != null) {
            vm.startDownloadFromDetail(pending)
        }
        if (!granted) {
            vm.showMessage(context.getString(R.string.message_notification_permission_optional))
        }
    }

    val hostCandidates = vm.activeSite.hosts.ifEmpty { listOf(vm.hostState) }
    val isDark = vm.darkMode

    LaunchedEffect(vm.detailState, pendingPlaybackUrl) {
        val target = pendingPlaybackUrl
        if (target.isNullOrBlank()) return@LaunchedEffect
        val detailState = vm.detailState
        if (detailState is LoadState.Success) {
            val detail = detailState.data
            if (detailMatchesTarget(detail, target)) {
                pendingPlaybackUrl = null
                playingDetailUrl = detail.sourceUrl
            }
        }
    }

    if (showCloudflareDialog) {
        CloudflareWarmupDialog(host = cloudflareHost) { solved, resolvedUrl ->
            showCloudflareDialog = false
            vm.onCloudflareWarmupReturned(solved, resolvedUrl ?: cloudflareHost)
        }
    }

    if (showSettings) {
        SettingsSheet(
            vm = vm,
            hostCandidates = hostCandidates,
            onDismiss = { showSettings = false },
            onManualWarmup = {
                showSettings = false
                cloudflareHost = vm.hostState
                showCloudflareDialog = true
            },
            onHostSelect = vm::setHost
        )
    }

    Scaffold(
            topBar = {
                VideoAppTopBar(
                    currentTab = currentTab,
                    onOpenSettings = { showSettings = true },
                    onOpenSearch = { currentTab = MainTab.Search },
                    onToggleTheme = vm::setDarkMode,
                    darkMode = isDark
                )
            },
        bottomBar = {
            VideoBottomNavigationBar(selected = currentTab, onSelect = { currentTab = it })
        }
    ) { padding ->
        val base = Modifier
            .fillMaxSize()
            .padding(padding)
            .background(
                brush = Brush.verticalGradient(
                    colors = if (isDark) {
                        listOf(DarkBg, DarkSurface, DarkBg)
                    } else {
                        listOf(BgDefault, Color(0xFFEFF4F9), BgDefault)
                    }
                )
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .imePadding()

        when (currentTab) {
            MainTab.Home,
            MainTab.Channels -> HomePage(
                vm = vm,
                modifier = base,
                onOpenVideo = { item -> vm.loadVideoDetail(item) },
                onRetry = { vm.loadSection(vm.currentSection) },
                onShowSettings = { showSettings = true },
                onOpenSearch = { currentTab = MainTab.Search }
            )
            MainTab.Search -> SearchPage(
                vm = vm,
                modifier = base,
                onSearch = vm::search,
                onOpenVideo = { item -> vm.loadVideoDetail(item) },
                onTriggerSearch = vm::triggerSearch,
                onOpenSettings = { showSettings = true },
                onRetry = {
                    if (vm.searchKeyword.isBlank()) vm.loadSection(vm.currentSection) else vm.search()
                }
            )
            MainTab.Collections -> {
                CollectionsPage(
                    vm = vm,
                    mode = collectionMode,
                    modifier = base,
                    onModeChange = { collectionMode = it },
                    onOpenVideo = { item -> vm.loadVideoDetail(item) },
                    onClearFavorites = vm::clearFavorites,
                    onClearHistory = vm::clearWatchHistory
                )
            }
            MainTab.Me -> {
                MePage(
                    vm = vm,
                    modifier = base,
                    onOpenSettings = { showSettings = true },
                    onThemeChange = vm::setDarkMode,
                    onPrivacyChange = vm::setPrivacyMode
                )
            }
        }

        DetailDrawer(
            vm = vm,
            onPlay = { detail ->
                pendingPlaybackUrl = null
                if (detail.sourceUrl.isBlank()) {
                    playingDetailUrl = null
                } else {
                    playingDetailUrl = detail.sourceUrl
                }
            },
            onDownload = { detail ->
                if (requiresLegacyDownloadPermission(context)) {
                    pendingDownloadDetail = detail
                    downloadPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else if (requiresNotificationPermission(context)) {
                    pendingDownloadDetail = detail
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    vm.startDownloadFromDetail(detail)
                }
            },
            onToggleFavorite = { detail ->
                vm.toggleFavorite(
                    VideoCard(
                        code = detail.code,
                        title = detail.title,
                        href = detail.sourceUrl,
                        thumbnail = detail.thumbnails.firstOrNull(),
                        sourceSite = detail.sourceSite
                    )
                )
            },
            onCopy = { value ->
                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(value))
            },
            onSwitchSource = { url ->
                pendingPlaybackUrl = url
                playingDetailUrl = null
                vm.loadVideoDetail(url)
            }
        )

        if (playingDetail != null) {
            val currentPlaying = playingDetail
            VideoPlaybackSheet(
                detail = currentPlaying,
                onClose = { progressPercent ->
                    vm.updateWatchHistoryProgress(
                        VideoCard(
                            code = currentPlaying.code,
                            title = currentPlaying.title,
                            href = currentPlaying.sourceUrl,
                            thumbnail = currentPlaying.thumbnails.firstOrNull(),
                            sourceSite = currentPlaying.sourceSite
                        ),
                        progressPercent
                    )
                    pendingPlaybackUrl = null
                    playingDetailUrl = null
                },
                onRetry = {
                    vm.loadVideoDetail(currentPlaying.sourceUrl)
                },
                onSwitchSource = { url, progressPercent ->
                    vm.updateWatchHistoryProgress(
                        VideoCard(
                            code = currentPlaying.code,
                            title = currentPlaying.title,
                            href = currentPlaying.sourceUrl,
                            thumbnail = currentPlaying.thumbnails.firstOrNull(),
                            sourceSite = currentPlaying.sourceSite
                        ),
                        progressPercent
                    )
                    pendingPlaybackUrl = url
                    playingDetailUrl = null
                    vm.loadVideoDetail(url)
                }
            )
        }
    }
}

@Composable
private fun VideoAppTopBar(
    currentTab: MainTab,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onToggleTheme: (Boolean) -> Unit,
    darkMode: Boolean
) {
    val textColor = if (darkMode) DarkTxtPrimary else TxtPrimary
    val searchContainer = if (darkMode) DarkCard else Color(0xFFF1F4F8)
    val searchTextColor = if (darkMode) DarkTxtSecondary else TxtSecondary
    Surface(
        shadowElevation = 0.5.dp,
        color = if (darkMode) DarkSurface else Color(0xFFF9FBFF)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = BrandBlue),
                    shape = CircleShape,
                    modifier = Modifier.size(30.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Text(
                    text = "Fluxia",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = textColor
                )
            }

            if (currentTab != MainTab.Search && currentTab != MainTab.Home && currentTab != MainTab.Channels) {
                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(999.dp),
                    colors = CardDefaults.cardColors(containerColor = searchContainer),
                    onClick = onOpenSearch
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = null, tint = searchTextColor)
                        Text(
                            text = stringResource(R.string.search_all_channels),
                            color = searchTextColor,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onToggleTheme(!darkMode) }) {
                    if (darkMode) {
                        Icon(Icons.Filled.LightMode, contentDescription = stringResource(R.string.cd_switch_light))
                    } else {
                        Icon(Icons.Filled.DarkMode, contentDescription = stringResource(R.string.cd_switch_dark))
                    }
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.cd_open_settings))
                }
            }
        }
    }
}

@Composable
private fun VideoBottomNavigationBar(selected: MainTab, onSelect: (MainTab) -> Unit) {
    Surface(
        shadowElevation = 10.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        NavigationBar(
            containerColor = Color.Transparent
        ) {
            PrimaryTabs.forEach { item ->
                NavigationBarItem(
                    selected = selected == item,
                    onClick = { onSelect(item) },
                    icon = { Icon(item.icon, contentDescription = stringResource(item.titleRes)) },
                    label = { Text(stringResource(item.titleRes), fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = BrandBlue,
                        selectedTextColor = BrandBlue,
                        indicatorColor = BrandBlue.copy(alpha = 0.12f),
                        unselectedIconColor = TxtSecondary,
                        unselectedTextColor = TxtSecondary
                    )
                )
            }
        }
    }
}

@Composable
private fun HomePage(
    vm: MainViewModel,
    modifier: Modifier,
    onOpenVideo: (VideoCard) -> Unit,
    onRetry: () -> Unit,
    onShowSettings: () -> Unit,
    onOpenSearch: () -> Unit
) {
    var historyExpanded by rememberSaveable { mutableStateOf(false) }
    val hasGridContent = when (vm.videos) {
        is LoadState.Idle,
        is LoadState.Success -> vm.filteredVideos.isNotEmpty()
        else -> false
    }

    if (hasGridContent) {
        VideoGrid(
            videos = vm.filteredVideos,
            onOpenVideo = onOpenVideo,
            canLoadMore = vm.canLoadMoreSection,
            isLoadingMore = vm.isLoadingMoreSection,
            onLoadMore = vm::loadNextSectionPage,
            modifier = modifier,
            headerContent = {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    HomeHeaderSection(
                        vm = vm,
                        historyExpanded = historyExpanded,
                        onHistoryExpandedChange = { historyExpanded = it },
                        onOpenVideo = onOpenVideo,
                        onOpenSearch = onOpenSearch
                    )
                }
            }
        )
    } else {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HomeHeaderSection(
                vm = vm,
                historyExpanded = historyExpanded,
                onHistoryExpandedChange = { historyExpanded = it },
                onOpenVideo = onOpenVideo,
                onOpenSearch = onOpenSearch
            )

            FeedState(
                state = vm.videos,
                videos = vm.filteredVideos,
                onOpenVideo = onOpenVideo,
                onRetry = onRetry,
                onSync = { vm.syncCloudflareCookies() },
                onOpenSettings = onShowSettings,
                canLoadMore = vm.canLoadMoreSection,
                isLoadingMore = vm.isLoadingMoreSection,
                onLoadMore = vm::loadNextSectionPage
            )
        }
    }
}

@Composable
private fun HomeHeaderSection(
    vm: MainViewModel,
    historyExpanded: Boolean,
    onHistoryExpandedChange: (Boolean) -> Unit,
    onOpenVideo: (VideoCard) -> Unit,
    onOpenSearch: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (vm.uiMessage != null) {
            NoticeBar(text = vm.uiMessage.orEmpty(), onDismiss = vm::clearMessage)
        }

        HomeHeroCard(
            onOpenSearch = onOpenSearch
        )

        if (vm.watchHistory.isNotEmpty()) {
            val showHistoryRail = historyExpanded || vm.watchHistory.size <= 2
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.section_continue_watching_title),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = TxtSecondary
                )
                TextButton(
                    onClick = { onHistoryExpandedChange(!showHistoryRail) },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = stringResource(if (showHistoryRail) R.string.action_collapse else R.string.action_expand),
                        fontSize = 12.sp
                    )
                }
            }
            if (showHistoryRail) {
                ContinueWatchingRail(
                    items = vm.watchHistory.take(4),
                    onOpenVideo = onOpenVideo
                )
            } else {
                ContinueWatchingSummaryCard(
                    item = vm.watchHistory.first(),
                    totalCount = vm.watchHistory.size,
                    onOpenVideo = onOpenVideo,
                    onExpand = { onHistoryExpandedChange(true) }
                )
            }
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(HomeFeedTab.values()) { item ->
                SourcePill(
                    text = stringResource(item.titleRes),
                    selected = vm.currentSection == item.section,
                    onClick = { vm.setSection(item.section) },
                    selectedBackground = BrandBlue
                )
            }
        }
    }
}

@Suppress("UNUSED_PARAMETER")
@Composable
private fun SearchPage(
    vm: MainViewModel,
    modifier: Modifier,
    onSearch: () -> Unit,
    onTriggerSearch: (String) -> Unit,
    onOpenVideo: (VideoCard) -> Unit,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit
) {
    val filteredVideos = remember(vm.filteredVideos, vm.searchKeyword) {
        normalizeSearchResults(vm.filteredVideos, vm.searchKeyword)
    }
    val fallbackQuery = vm.searchKeyword.trim()
    val missavFallbackUrl = remember(vm.searchKeyword, vm.hostState) {
        vm.buildMissavSearchFallbackUrl()
    }
    var missavFallbackTried by rememberSaveable(vm.searchKeyword, vm.videos is LoadState.Loading) { mutableStateOf(false) }
    val shouldRunMissavFallback = remember(vm.videos, fallbackQuery, missavFallbackUrl, missavFallbackTried) {
        if (fallbackQuery.isBlank() || missavFallbackUrl.isNullOrBlank() || missavFallbackTried) {
            false
        } else {
            when (val state = vm.videos) {
                is LoadState.Loading,
                is LoadState.Idle -> false
                is LoadState.Success -> state.data.none { it.sourceSite.contains("MissAV", ignoreCase = true) }
                is LoadState.Error,
                is LoadState.CloudflareChallenge -> true
            }
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SearchInput(
            query = vm.searchKeyword,
            onQueryChange = vm::setSearchKeyword,
            onSearch = onSearch,
            onClear = { vm.setSearchKeyword("") },
            placeholder = stringResource(R.string.search_hint)
        )

        if (vm.searchKeyword.isNotBlank()) {
            SearchStatusRow(
                resultCount = filteredVideos.size
            )
        }

        if (shouldRunMissavFallback && missavFallbackUrl != null) {
            val fallbackUrl = missavFallbackUrl
            AndroidView(
                factory = { viewContext ->
                    WebView(viewContext).apply {
                        settings.userAgentString = CF_USER_AGENT
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadsImagesAutomatically = false
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                view?.postDelayed({
                                    view.evaluateJavascript("(function(){return document.documentElement.outerHTML;})()") { raw ->
                                        missavFallbackTried = true
                                        decodeJavascriptString(raw)
                                            ?.takeIf { it.isNotBlank() }
                                            ?.let { html ->
                                                vm.applyMissavSearchFallbackHtml(
                                                    query = fallbackQuery,
                                                    html = html,
                                                    pageUrl = url.orEmpty().ifBlank { fallbackUrl }
                                                )
                                            }
                                    }
                                }, 1200L)
                            }
                        }
                        loadUrl(fallbackUrl)
                    }
                },
                modifier = Modifier.size(1.dp)
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            FeedState(
                state = vm.videos,
                videos = filteredVideos,
                onOpenVideo = onOpenVideo,
                onRetry = onRetry,
                onSync = { vm.syncCloudflareCookies() },
                onOpenSettings = onOpenSettings
            )
        }
    }
}

@Composable
private fun SectionHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        if (subtitle.isNotBlank()) {
            Text(subtitle, color = TxtSecondary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun HomeHeroCard(
    onOpenSearch: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            BrandBlue.copy(alpha = 0.12f),
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.surface
                        )
                    )
                )
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.home_hero_title),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Card(
                    shape = CircleShape,
                    colors = CardDefaults.cardColors(containerColor = BrandBlue.copy(alpha = 0.10f)),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = BrandBlue,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Card(
                onClick = onOpenSearch,
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (MaterialTheme.colorScheme.background == DarkBg) {
                        DarkCard
                    } else {
                        Color(0xFFF3F6FA)
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = BrandBlue)
                    Text(
                        text = stringResource(R.string.search_all_channels),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }
            }

        }
    }
}

@Composable
private fun ContinueWatchingRail(
    items: List<HistoryItem>,
    onOpenVideo: (VideoCard) -> Unit
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(items) { item ->
            ContinueWatchingCard(
                item = item,
                onOpenVideo = onOpenVideo
            )
        }
    }
}

@Composable
private fun ContinueWatchingSummaryCard(
    item: HistoryItem,
    totalCount: Int,
    onOpenVideo: (VideoCard) -> Unit,
    onExpand: () -> Unit
) {
    val context = LocalContext.current
    val title = item.video.title.ifBlank { stringResource(R.string.video_untitled) }
    val sourceColor = if (item.video.sourceSite.contains("Jable", ignoreCase = true)) BrandPink else BrandBlue
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        onClick = { onOpenVideo(item.video) }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(width = 104.dp, height = 62.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFECF1F7))
            ) {
                if (!item.video.thumbnail.isNullOrBlank()) {
                    AsyncImage(
                        model = item.video.thumbnail,
                        contentDescription = title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                SourceTag(
                    source = item.video.sourceSite.ifBlank { "Missav" },
                    textColor = sourceColor
                )
                Text(
                    text = title,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        append(stringResource(R.string.collections_recent_history_format, totalCount))
                        append(" · ")
                        append(formatRelativeTime(context, item.playedAt))
                    },
                    color = TxtSecondary,
                    fontSize = 11.sp
                )
                LinearProgressIndicator(
                    progress = { (item.progressPercent.coerceIn(0, 100)) / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(999.dp)),
                    color = BrandBlue,
                    trackColor = BrandBlue.copy(alpha = 0.12f)
                )
            }
            FilledTonalButton(
                onClick = onExpand,
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = sourceColor.copy(alpha = 0.12f),
                    contentColor = sourceColor
                )
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.action_expand))
            }
        }
    }
}

@Composable
private fun ContinueWatchingCard(
    item: HistoryItem,
    onOpenVideo: (VideoCard) -> Unit
) {
    val sourceColor = if (item.video.sourceSite.contains("Jable", ignoreCase = true)) BrandPink else BrandBlue
    val untitledLabel = stringResource(R.string.video_untitled)
    val continueLabel = stringResource(R.string.continue_watching_label)
    Card(
        modifier = Modifier.width(220.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        onClick = { onOpenVideo(item.video) }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color(0xFFECF1F7))
            ) {
                if (!item.video.thumbnail.isNullOrBlank()) {
                    AsyncImage(
                        model = item.video.thumbnail,
                        contentDescription = item.video.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(sourceColor.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = item.video.code.ifBlank { item.video.sourceSite },
                            color = sourceColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                SourceTag(
                    source = item.video.sourceSite.ifBlank { "MissAV" },
                    textColor = sourceColor,
                    modifier = Modifier
                        .padding(8.dp)
                        .align(Alignment.TopStart)
                )
            }

            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = item.video.title.ifBlank { untitledLabel },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(item.video.code.ifBlank { continueLabel }, fontSize = 11.sp, color = TxtSecondary)
                    Text(formatRelativeTime(LocalContext.current, item.playedAt), fontSize = 11.sp, color = TxtSecondary)
                }
                if (item.progressPercent > 0) {
                    LinearProgressIndicator(
                        progress = { item.progressPercent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelPage(vm: MainViewModel, modifier: Modifier, onOpenVideo: (VideoCard) -> Unit) {
    val channelSections = listOf(
        MissAvSection.New to R.string.channel_tag_recommend,
        MissAvSection.Release to R.string.channel_tag_latest,
        MissAvSection.Subtitle to R.string.channel_tag_subtitle,
        MissAvSection.Uncensored to R.string.channel_tag_uncensored
    )

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.channel_title), fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.channel_core_entry), color = TxtSecondary, fontSize = 11.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(channelSections) { item ->
                        val section = item.first
                        val tagRes = item.second
                        SourcePill(
                            text = stringResource(tagRes),
                            selected = vm.currentSection == section,
                            onClick = {
                                if (vm.currentSection != section) {
                                    vm.setSection(section)
                                }
                            },
                            selectedBackground = BrandBlue
                        )
                    }
                }
            }
        }
        FeedState(
            state = vm.videos,
            videos = vm.filteredVideos,
            onOpenVideo = onOpenVideo,
            onRetry = { vm.loadSection(vm.currentSection) },
            onSync = { vm.syncCloudflareCookies() },
            onOpenSettings = {},
            canLoadMore = vm.canLoadMoreSection,
            isLoadingMore = vm.isLoadingMoreSection,
            onLoadMore = vm::loadNextSectionPage
        )
    }
}

@Composable
private fun CollectionsPage(
    vm: MainViewModel,
    mode: CollectionMode,
    modifier: Modifier,
    onModeChange: (CollectionMode) -> Unit,
    onOpenVideo: (VideoCard) -> Unit,
    onClearFavorites: () -> Unit,
    onClearHistory: () -> Unit
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CollectionMode.values().forEach { type ->
                SourcePill(
                    text = stringResource(type.titleRes),
                    selected = type == mode,
                    onClick = { onModeChange(type) },
                    selectedBackground = BrandBlue
                )
            }
            TextButton(
                onClick = {
                    if (mode == CollectionMode.Favorite) onClearFavorites() else onClearHistory()
                }
            ) {
                Text(
                    stringResource(
                        R.string.collections_clear_format,
                        stringResource(if (mode == CollectionMode.Favorite) R.string.collection_favorite else R.string.collection_history)
                    )
                )
            }
        }

        when (mode) {
            CollectionMode.Favorite -> {
                if (vm.favoriteVideos.isNotEmpty()) {
                    Text(
                        stringResource(R.string.collections_recent_favorites_format, vm.favoriteVideos.size),
                        fontWeight = FontWeight.Medium,
                        color = TxtSecondary
                    )
                }
                if (vm.favoriteVideos.isEmpty()) {
                    EmptyState(
                        stringResource(R.string.collections_empty_favorites_title),
                        stringResource(R.string.collections_empty_favorites_subtitle)
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(vm.favoriteVideos) { item ->
                            VideoListItem(item = item, onClick = onOpenVideo, trailing = {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = { vm.removeFavorite(item) }) {
                                        Text(stringResource(R.string.collections_action_remove))
                                    }
                                    TextButton(onClick = { onOpenVideo(item) }) {
                                        Text(stringResource(R.string.collections_action_open))
                                    }
                                }
                            })
                        }
                    }
                }
            }
            CollectionMode.History -> {
                if (vm.watchHistory.isNotEmpty()) {
                    Text(
                        stringResource(R.string.collections_recent_history_format, vm.watchHistory.size),
                        fontWeight = FontWeight.Medium,
                        color = TxtSecondary
                    )
                }
                if (vm.watchHistory.isEmpty()) {
                    EmptyState(
                        stringResource(R.string.collections_empty_history_title),
                        stringResource(R.string.collections_empty_history_subtitle)
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(vm.watchHistory) { item ->
                            VideoListItem(
                                item = item.video,
                                onClick = onOpenVideo,
                                trailing = {
                                     Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                         Text(formatRelativeTime(LocalContext.current, item.playedAt), fontSize = 11.sp, color = TxtSecondary)
                                         if (item.progressPercent > 0) {
                                             LinearProgressIndicator(
                                                 progress = { item.progressPercent / 100f },
                                                 modifier = Modifier.width(64.dp)
                                             )
                                         }
                                     }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MePage(
    vm: MainViewModel,
    modifier: Modifier,
    onOpenSettings: () -> Unit,
    onThemeChange: (Boolean) -> Unit,
    onPrivacyChange: (Boolean) -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val completedDownloads = vm.downloads.count {
        it.status == com.example.missavapp.data.model.DownloadStatus.Completed
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.me_local_space_title), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.me_local_space_subtitle), color = TxtSecondary, fontSize = 11.sp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LibraryStatCard(stringResource(R.string.stat_favorites), vm.favoriteVideos.size.toString(), BrandBlue, Modifier.weight(1f))
                    LibraryStatCard(stringResource(R.string.stat_history), vm.watchHistory.size.toString(), BrandPink, Modifier.weight(1f))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LibraryStatCard(stringResource(R.string.stat_downloads), completedDownloads.toString(), BrandBlueDark, Modifier.weight(1f))
                    LibraryStatCard(stringResource(R.string.stat_search), vm.recentSearches.size.toString(), TxtSecondary, Modifier.weight(1f))
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.me_sources_title), fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.me_sources_subtitle), color = TxtSecondary, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetaInfoChip(text = "MissAV", color = BrandBlue)
                    MetaInfoChip(text = "Jable.tv", color = BrandPink)
                }
            }
        }

        DownloadsPanel(
            downloads = vm.downloads,
            onCancel = vm::cancelDownload,
            onClearFinished = vm::clearFinishedDownloads,
            onCopyPath = { path ->
                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(path))
            }
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.prefs_title), fontWeight = FontWeight.Bold)
                SettingsSwitch(title = stringResource(R.string.pref_dark_mode), checked = vm.darkMode, onChecked = onThemeChange)
                SettingsSwitch(title = stringResource(R.string.pref_privacy_mode), checked = vm.privacyMode, onChecked = onPrivacyChange)
                SettingsSwitch(title = stringResource(R.string.pref_auto_restore_access), checked = vm.autoCloudflareEnabled, onChecked = vm::updateAutoCloudflareEnabled)
                TextButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.prefs_more_settings))
                }
            }
        }
    }
}

@Composable
private fun DownloadsPanel(
    downloads: List<com.example.missavapp.data.model.DownloadTask>,
    onCancel: (String) -> Unit,
    onClearFinished: () -> Unit,
    onCopyPath: (String) -> Unit
) {
    val activeCount = downloads.count { it.status == com.example.missavapp.data.model.DownloadStatus.Running || it.status == com.example.missavapp.data.model.DownloadStatus.Queued }
    val finishedCount = downloads.count { it.status == com.example.missavapp.data.model.DownloadStatus.Completed || it.status == com.example.missavapp.data.model.DownloadStatus.Cancelled || it.status == com.example.missavapp.data.model.DownloadStatus.Failed }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.downloads_title), fontWeight = FontWeight.Bold)
                    Text(
                        if (downloads.isEmpty()) {
                            stringResource(R.string.downloads_saved_path_hint)
                        } else {
                            stringResource(R.string.downloads_tasks_format, downloads.size, activeCount)
                        },
                        color = TxtSecondary,
                        fontSize = 11.sp
                    )
                }
                if (finishedCount > 0) {
                    TextButton(onClick = onClearFinished) {
                        Text(stringResource(R.string.downloads_clear_finished))
                    }
                }
            }

            if (downloads.isEmpty()) {
                Text(stringResource(R.string.downloads_empty_desc), color = TxtSecondary, fontSize = 11.sp)
            } else {
                downloads.take(4).forEachIndexed { index, task ->
                    DownloadTaskRow(
                        task = task,
                        onCancel = onCancel,
                        onCopyPath = onCopyPath
                    )
                    if (index != downloads.take(4).lastIndex) {
                        HorizontalDivider(color = DividerColor)
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadTaskRow(
    task: com.example.missavapp.data.model.DownloadTask,
    onCancel: (String) -> Unit,
    onCopyPath: (String) -> Unit
) {
    val downloadFallbackTitle = stringResource(R.string.download_fallback_title)
    val middleDot = stringResource(R.string.separator_middle_dot)
    val statusLabel = when (task.status) {
        com.example.missavapp.data.model.DownloadStatus.Queued -> stringResource(R.string.download_status_queued)
        com.example.missavapp.data.model.DownloadStatus.Running -> stringResource(R.string.download_status_running)
        com.example.missavapp.data.model.DownloadStatus.Paused -> stringResource(R.string.download_status_paused)
        com.example.missavapp.data.model.DownloadStatus.Completed -> stringResource(R.string.download_status_completed)
        com.example.missavapp.data.model.DownloadStatus.Failed -> stringResource(R.string.download_status_failed)
        com.example.missavapp.data.model.DownloadStatus.Cancelled -> stringResource(R.string.download_status_cancelled)
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    task.title.ifBlank { task.code ?: downloadFallbackTitle },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    task.code?.uppercase(Locale.ROOT) ?: task.filePath.substringAfterLast('\\').substringAfterLast('/'),
                    color = TxtSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            MetaInfoChip(
                text = statusLabel,
                color = when (task.status) {
                    com.example.missavapp.data.model.DownloadStatus.Completed -> BrandBlue
                    com.example.missavapp.data.model.DownloadStatus.Failed -> BrandPink
                    com.example.missavapp.data.model.DownloadStatus.Cancelled -> TxtSecondary
                    else -> TxtPrimary
                }
            )
        }

        if (task.status == com.example.missavapp.data.model.DownloadStatus.Running || task.status == com.example.missavapp.data.model.DownloadStatus.Queued) {
            LinearProgressIndicator(
                progress = { task.progress.coerceIn(0, 100) / 100f },
                modifier = Modifier.fillMaxWidth()
            )
        }

        Text(
            buildString {
                append(task.message ?: statusLabel)
                val sizeInfo = formatStorageSize(task.downloadedBytes)
                if (sizeInfo.isNotBlank()) {
                    append(middleDot)
                    append(sizeInfo)
                    if (task.totalBytes > 0) {
                        append(" / ")
                        append(formatStorageSize(task.totalBytes))
                    }
                }
            },
            color = TxtSecondary,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (task.status) {
                com.example.missavapp.data.model.DownloadStatus.Running,
                com.example.missavapp.data.model.DownloadStatus.Queued -> {
                    TextButton(onClick = { onCancel(task.id) }) {
                        Text(stringResource(R.string.download_action_cancel))
                    }
                }

                com.example.missavapp.data.model.DownloadStatus.Completed,
                com.example.missavapp.data.model.DownloadStatus.Failed,
                com.example.missavapp.data.model.DownloadStatus.Cancelled -> {
                    TextButton(onClick = { onCopyPath(task.filePath) }) {
                        Text(stringResource(R.string.download_action_copy_path))
                    }
                }

                com.example.missavapp.data.model.DownloadStatus.Paused -> {
                    TextButton(onClick = { onCopyPath(task.filePath) }) {
                        Text(stringResource(R.string.download_action_copy_path))
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryStatCard(title: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.10f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(value, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = accent)
            Text(title, fontSize = 11.sp, color = TxtSecondary)
        }
    }
}

@Composable
private fun NoticeBar(text: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = BrandBlue.copy(alpha = 0.12f)),
                shape = CircleShape,
                modifier = Modifier.size(32.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, tint = BrandBlue, modifier = Modifier.size(16.dp))
                }
            }
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                color = TxtPrimary,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.notice_ack), color = BrandBlue)
            }
        }
    }
}

@Composable
private fun SearchInput(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    placeholder: String
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(placeholder) },
        leadingIcon = {
            Icon(Icons.Filled.Search, contentDescription = null)
        },
        trailingIcon = {
            if (query.isNotBlank()) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.cd_clear_search))
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(18.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = BrandBlue.copy(alpha = 0.55f),
            unfocusedBorderColor = DividerColor,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            cursorColor = BrandBlue
        )
    )
}

@Composable
private fun SearchStatusRow(
    resultCount: Int
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            MetaInfoChip(
                text = stringResource(R.string.search_result_count_format, resultCount),
                color = BrandPink
            )
        }
    }
}

@Composable
private fun FeedState(
    state: LoadState<List<VideoCard>>,
    videos: List<VideoCard>,
    onOpenVideo: (VideoCard) -> Unit,
    onRetry: () -> Unit,
    onSync: () -> Unit,
    onOpenSettings: () -> Unit,
    canLoadMore: Boolean = false,
    isLoadingMore: Boolean = false,
    onLoadMore: (() -> Unit)? = null,
) {
    when (state) {
        is LoadState.Idle -> {
            if (videos.isNotEmpty()) {
                VideoGrid(
                    videos = videos,
                    onOpenVideo = onOpenVideo,
                    canLoadMore = canLoadMore,
                    isLoadingMore = isLoadingMore,
                    onLoadMore = onLoadMore
                )
            } else {
                EmptyState(
                    title = stringResource(R.string.feed_empty_title),
                    subtitle = stringResource(R.string.feed_empty_subtitle)
                )
            }
        }
        is LoadState.Loading -> {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = BrandBlue, strokeWidth = 3.dp)
                    Text(stringResource(R.string.feed_loading), color = TxtPrimary, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.search_all_channels),
                        color = TxtSecondary,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        is LoadState.Error -> {
            ErrorBox(
                message = state.message,
                onRetry = onRetry,
                onSync = onSync,
                onOpenSettings = onOpenSettings
            )
        }
        is LoadState.CloudflareChallenge -> {
            CloudflareBox(
                onRetry = onRetry,
                onOpenSettings = onOpenSettings,
                onSync = onSync
            )
        }
        is LoadState.Success -> {
            if (videos.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.feed_no_results_title),
                    subtitle = stringResource(R.string.feed_no_results_subtitle)
                )
            } else {
                VideoGrid(
                    videos = videos,
                    onOpenVideo = onOpenVideo,
                    canLoadMore = canLoadMore,
                    isLoadingMore = isLoadingMore,
                    onLoadMore = onLoadMore
                )
            }
        }
    }
}

@Composable
private fun ErrorBox(message: String, onRetry: () -> Unit, onSync: () -> Unit, onOpenSettings: () -> Unit) {
    val displayMessage = sanitizeUiErrorMessage(LocalContext.current, message)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Card(
                colors = CardDefaults.cardColors(containerColor = BrandPink.copy(alpha = 0.12f)),
                shape = CircleShape,
                modifier = Modifier.size(38.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, tint = BrandPink, modifier = Modifier.size(18.dp))
                }
            }
            Text(stringResource(R.string.error_loading_title), fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Text(displayMessage, color = TxtSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRetry) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_reload))
                }
                TextButton(onClick = { onSync() }) {
                    Text(stringResource(R.string.action_refresh_session))
                }
                TextButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.action_settings))
                }
            }
        }
    }
}

@Composable
private fun CloudflareBox(onRetry: () -> Unit, onOpenSettings: () -> Unit, onSync: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Card(
                colors = CardDefaults.cardColors(containerColor = BrandBlue.copy(alpha = 0.12f)),
                shape = CircleShape,
                modifier = Modifier.size(38.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = BrandBlue, modifier = Modifier.size(18.dp))
                }
            }
            Text(stringResource(R.string.cloudflare_box_title), fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Text(stringResource(R.string.cloudflare_box_subtitle), color = TxtSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRetry) {
                    Text(stringResource(R.string.action_reload))
                }
                TextButton(onClick = { onSync() }) {
                    Text(stringResource(R.string.action_refresh_state))
                }
                TextButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.action_open_settings))
                }
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Card(
                shape = CircleShape,
                colors = CardDefaults.cardColors(containerColor = BrandBlue.copy(alpha = 0.10f)),
                modifier = Modifier.size(52.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = BrandBlue)
                }
            }
            Text(title, fontWeight = FontWeight.SemiBold, color = TxtPrimary)
            Text(subtitle, color = TxtSecondary, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun VideoGrid(
    videos: List<VideoCard>,
    onOpenVideo: (VideoCard) -> Unit,
    canLoadMore: Boolean = false,
    isLoadingMore: Boolean = false,
    onLoadMore: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    headerContent: LazyGridScope.() -> Unit = {}
) {
    val gridState = rememberLazyGridState()

    LaunchedEffect(gridState, videos.size, canLoadMore, isLoadingMore, onLoadMore) {
        if (onLoadMore == null) return@LaunchedEffect
        snapshotFlow {
            val layoutInfo = gridState.layoutInfo
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            canLoadMore && !isLoadingMore && layoutInfo.totalItemsCount > 0 && lastVisibleIndex >= layoutInfo.totalItemsCount - 3
        }.distinctUntilChanged().collect { shouldLoad ->
            if (shouldLoad) {
                onLoadMore()
            }
        }
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(bottom = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.fillMaxSize()
    ) {
        headerContent()
        items(videos, key = { it.href }) { item ->
            VideoCardItem(video = item, onOpen = { onOpenVideo(item) })
        }
        if (isLoadingMore) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(color = BrandBlue, modifier = Modifier.size(20.dp), strokeWidth = 2.5.dp)
                        Text(stringResource(R.string.feed_loading), color = TxtSecondary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoCardItem(video: VideoCard, onOpen: () -> Unit) {
    val source = video.sourceSite.ifBlank { "Missav" }
    val sourceColor = if (source.contains("Jable", ignoreCase = true)) BrandPink else BrandBlue
    val cover = video.thumbnail
    val untitledLabel = stringResource(R.string.video_untitled)
    val unnamedLabel = stringResource(R.string.video_unnamed)

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.fillMaxWidth()) {
                if (cover.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 10.2f)
                            .background(
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        sourceColor.copy(alpha = 0.22f),
                                        MaterialTheme.colorScheme.surface
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = video.code.ifBlank { source },
                            color = sourceColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 10.2f)
                            .background(Color(0xFFECF1F7))
                    ) {
                        AsyncImage(
                            model = cover,
                            contentDescription = video.title,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomStart)
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))
                                    )
                                )
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = video.code.ifBlank { source },
                                color = Color.White,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                SourceTag(
                    source = source,
                    textColor = sourceColor,
                    modifier = Modifier
                        .padding(8.dp)
                        .align(Alignment.TopStart)
                )
            }

            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    text = video.title.ifBlank { untitledLabel },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        video.code.ifBlank { unnamedLabel },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        source,
                        fontSize = 11.sp,
                        color = sourceColor
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceTag(source: String, textColor: Color, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = textColor.copy(alpha = 0.16f)),
        shape = RoundedCornerShape(999.dp),
        modifier = modifier
    ) {
        Text(
            text = source,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            fontSize = 10.sp,
            color = textColor,
            maxLines = 1
        )
    }
}

@Composable
private fun VideoListItem(item: VideoCard, onClick: (VideoCard) -> Unit, trailing: (@Composable () -> Unit)? = null) {
    val untitledLabel = stringResource(R.string.video_untitled)
    val sourceColor = if (item.sourceSite.contains("Jable", ignoreCase = true)) BrandPink else BrandBlue
    Card(
        modifier = Modifier
            .fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        onClick = { onClick(item) }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(width = 112.dp, height = 66.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFFECEEF2))
            ) {
                AsyncImage(
                    model = item.thumbnail,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(item.title.ifBlank { untitledLabel }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(item.code, color = TxtSecondary, fontSize = 11.sp)
                SourceTag(
                    source = item.sourceSite.ifBlank { "Missav" },
                    textColor = sourceColor
                )
            }

            trailing?.invoke()
        }
    }
}

@Composable
private fun DetailDrawer(
    vm: MainViewModel,
    onPlay: (VideoDetail) -> Unit,
    onDownload: (VideoDetail) -> Unit,
    onToggleFavorite: (VideoDetail) -> Unit,
    onCopy: (String) -> Unit,
    onSwitchSource: (String) -> Unit
) {
    when (val state = vm.detailState) {
        is LoadState.Idle -> return
        is LoadState.Loading -> {
            DetailPanelShell(onDismiss = vm::clearDetail) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.detail_loading), color = TxtSecondary)
            }
        }
        is LoadState.Error -> {
            DetailPanelShell(onDismiss = vm::clearDetail) {
                Text(stringResource(R.string.detail_unavailable), fontWeight = FontWeight.SemiBold)
                Text(sanitizeUiErrorMessage(LocalContext.current, state.message), color = TxtSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = vm::clearDetail) {
                        Text(stringResource(R.string.common_close))
                    }
                    TextButton(onClick = {
                        vm.clearDetail()
                        vm.loadSection(vm.currentSection)
                    }) {
                        Text(stringResource(R.string.detail_return_feed))
                    }
                }
            }
        }
        is LoadState.CloudflareChallenge -> {
            DetailPanelShell(onDismiss = vm::clearDetail) {
                Text(stringResource(R.string.detail_unavailable), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.detail_challenge_subtitle), color = TxtSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = vm::clearDetail) {
                        Text(stringResource(R.string.common_close))
                    }
                    TextButton(onClick = {
                        vm.clearDetail()
                        vm.loadSection(vm.currentSection)
                    }) {
                        Text(stringResource(R.string.detail_return_feed))
                    }
                }
            }
        }
        is LoadState.Success -> {
            DetailSheetContent(
                detail = state.data,
                onPlay = onPlay,
                onDownload = onDownload,
                onCopy = onCopy,
                onToggleFavorite = onToggleFavorite,
                onSwitchSource = onSwitchSource,
                onResolveRuntimeStream = vm::updateDetailResolvedStream,
                onClose = vm::clearDetail
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailPanelShell(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailSheetContent(
    detail: VideoDetail,
    onPlay: (VideoDetail) -> Unit,
    onDownload: (VideoDetail) -> Unit,
    onCopy: (String) -> Unit,
    onToggleFavorite: (VideoDetail) -> Unit,
    onSwitchSource: (String) -> Unit,
    onResolveRuntimeStream: (String) -> Unit,
    onClose: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sourceColor = if (detail.sourceSite.contains("Jable", ignoreCase = true)) BrandPink else BrandBlue
    val playableUrl = resolvePlayableMediaUrl(detail)
    var runtimeResolverTried by rememberSaveable(detail.sourceUrl) { mutableStateOf(false) }
    val isResolvingPlayableUrl =
        playableUrl.isNullOrBlank() &&
            detail.sourceSite.contains("MissAV", ignoreCase = true) &&
            detail.sourceUrl.startsWith("http", ignoreCase = true) &&
            !runtimeResolverTried
    val canPlay = playableUrl != null
    val canDownload = playableUrl != null
    val cover = detail.thumbnails.firstOrNull()
    val untitledLabel = stringResource(R.string.video_untitled)
    val availableSourceCount = detail.availableSources.distinctBy { it.url }.size

    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (isResolvingPlayableUrl) {
                AndroidView(
                    factory = { viewContext ->
                        WebView(viewContext).apply {
                            settings.userAgentString = CF_USER_AGENT
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    view?.postDelayed({
                                        view.evaluateJavascript(MISSAV_RUNTIME_SOURCE_SCRIPT) { raw ->
                                            decodeJavascriptString(raw)
                                                ?.takeIf { it.isNotBlank() }
                                                ?.let(onResolveRuntimeStream)
                                            runtimeResolverTried = true
                                        }
                                    }, 1200L)
                                }
                            }
                            loadUrl(detail.sourceUrl)
                        }
                    },
                    modifier = Modifier.size(1.dp)
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color(0xFFECF1F7), RoundedCornerShape(18.dp))
            ) {
                if (!cover.isNullOrBlank()) {
                    AsyncImage(
                        model = cover,
                        contentDescription = detail.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFFECF1F7), RoundedCornerShape(18.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                brush = Brush.verticalGradient(
                                    colors = listOf(sourceColor.copy(alpha = 0.22f), MaterialTheme.colorScheme.surface)
                                ),
                                shape = RoundedCornerShape(18.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                Text(
                    text = detail.code.ifBlank { detail.sourceSite },
                            color = sourceColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    }
                }

                SourceTag(
                    source = detail.sourceSite.ifBlank { "Missav" },
                    textColor = sourceColor,
                    modifier = Modifier
                        .padding(10.dp)
                        .align(Alignment.TopStart)
                )

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.58f)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .padding(10.dp)
                        .align(Alignment.BottomStart)
                ) {
                    Text(
                        text = detail.code.ifBlank { "VIDEO" },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = Color.White,
                        fontSize = 11.sp
                    )
                }
            }

            Text(
                detail.title.ifBlank { untitledLabel },
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                lineHeight = 30.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    SourceTag(
                        source = detail.sourceSite.ifBlank { "Missav" },
                        textColor = sourceColor
                    )
                }
                if (detail.code.isNotBlank()) {
                    item {
                        MetaInfoChip(
                            text = detail.code.uppercase(Locale.ROOT),
                            color = TxtSecondary
                        )
                    }
                }
                if (availableSourceCount > 1) {
                    item {
                        MetaInfoChip(
                            text = stringResource(R.string.detail_available_sources_count, availableSourceCount),
                            color = BrandPink
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { onPlay(detail) },
                    enabled = canPlay,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = sourceColor,
                        contentColor = Color.White,
                        disabledContainerColor = sourceColor.copy(alpha = 0.45f),
                        disabledContentColor = Color.White.copy(alpha = 0.92f)
                    )
                ) {
                    if (isResolvingPlayableUrl) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.White
                        )
                    } else {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (isResolvingPlayableUrl) {
                            stringResource(R.string.detail_play_resolving)
                        } else {
                            stringResource(R.string.detail_play_now)
                        }
                    )
                }
                FilledTonalButton(
                    onClick = { onDownload(detail) },
                    enabled = canDownload,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = sourceColor.copy(alpha = 0.14f),
                        contentColor = sourceColor,
                        disabledContainerColor = sourceColor.copy(alpha = 0.08f),
                        disabledContentColor = sourceColor.copy(alpha = 0.5f)
                    )
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.detail_save_local))
                }
            }

            if (isResolvingPlayableUrl) {
                Text(
                    text = stringResource(R.string.detail_play_resolving_hint),
                    color = TxtSecondary,
                    fontSize = 12.sp
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MiniIconAction(
                    icon = Icons.Filled.Favorite,
                    tint = BrandPink,
                    onClick = { onToggleFavorite(detail) },
                    modifier = Modifier.weight(1f)
                )
                MiniIconAction(
                    icon = Icons.Filled.ContentCopy,
                    tint = BrandBlue,
                    onClick = { onCopy(playableUrl ?: detail.sourceUrl) },
                    modifier = Modifier.weight(1f)
                )
            }

            if (detail.availableSources.size > 1) {
                HorizontalDivider(color = DividerColor.copy(alpha = 0.7f))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(detail.availableSources.distinctBy { it.url }) { source ->
                        SourcePill(
                            text = source.sourceSite,
                            selected = source.url == detail.sourceUrl,
                            onClick = { onSwitchSource(source.url) },
                            compact = true,
                            selectedBackground = if (source.sourceSite.contains("Jable", ignoreCase = true)) BrandPink else BrandBlue
                        )
                    }
                }
            }

            if (detail.recommendations.isNotEmpty()) {
                HorizontalDivider(color = DividerColor.copy(alpha = 0.7f))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(detailRecommendationTitleRes(detail.sourceSite)), fontWeight = FontWeight.Medium)
                    detail.recommendations.distinctBy { it.href }.forEach { recommendation ->
                        VideoListItem(
                            item = recommendation,
                            onClick = { card -> onSwitchSource(card.href) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniIconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = tint.copy(alpha = 0.10f)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun MetaInfoChip(text: String, color: Color) {
    Card(
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.10f)),
        shape = RoundedCornerShape(10.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            fontSize = 11.sp,
            color = color,
            maxLines = 1
        )
    }
}

@Composable
private fun PlaybackInfoPill(text: String, tint: Color) {
    Card(
        colors = CardDefaults.cardColors(containerColor = tint.copy(alpha = 0.14f)),
        shape = RoundedCornerShape(999.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            color = Color.White,
            fontSize = 11.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun VideoPlaybackSheet(
    detail: VideoDetail,
    onClose: (Int) -> Unit,
    onRetry: () -> Unit,
    onSwitchSource: (String, Int) -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var runtimeStreamUrl by remember(detail.sourceUrl, detail.hlsUrl) { mutableStateOf(resolvePlayableMediaUrl(detail)) }
    var runtimeResolverTried by remember(detail.sourceUrl) { mutableStateOf(false) }
    var forcedLandscape by rememberSaveable(detail.sourceUrl) { mutableStateOf(false) }
    var showFullscreenChrome by rememberSaveable(detail.sourceUrl) { mutableStateOf(true) }
    val streamUrl = runtimeStreamUrl
    val sourceColor = if (detail.sourceSite.contains("Jable", ignoreCase = true)) BrandPink else BrandBlue
    val cover = detail.thumbnails.firstOrNull()
    val sourceFallback = stringResource(R.string.detail_label_source)
    val untitledLabel = stringResource(R.string.video_untitled)
    val player = remember(streamUrl, detail.sourceUrl) {
        streamUrl?.let { url ->
            val referer = detail.sourceUrl.ifBlank { detail.href }
            val httpFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(CF_USER_AGENT)
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(buildPlaybackHeaders(referer, url))
            val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
            ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .build().apply {
                val mediaItem = MediaItem.Builder()
                    .setUri(url)
                    .apply {
                        if (isHlsPlaybackSource(url)) {
                            setMimeType(MimeTypes.APPLICATION_M3U8)
                        }
                    }
                    .build()
                setMediaItem(mediaItem)
                prepare()
                playWhenReady = true
            }
        }
    }

    DisposableEffect(player) {
        onDispose {
            player?.stop()
            player?.release()
        }
    }

    DisposableEffect(activity) {
        val window = activity?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        val originalOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = originalOrientation
        }
    }

    LaunchedEffect(activity, forcedLandscape) {
        activity?.requestedOrientation = if (forcedLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        showFullscreenChrome = true
    }

    LaunchedEffect(forcedLandscape, showFullscreenChrome) {
        if (forcedLandscape && showFullscreenChrome) {
            delay(1800L)
            showFullscreenChrome = false
        }
    }

    BackHandler {
        if (forcedLandscape) {
            forcedLandscape = false
            showFullscreenChrome = true
        } else {
            onClose(capturePlaybackProgress(player))
        }
    }

    Dialog(
        onDismissRequest = {
            if (forcedLandscape) {
                forcedLandscape = false
                showFullscreenChrome = true
            } else {
                onClose(capturePlaybackProgress(player))
            }
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            val shouldResolveMissavRuntime = streamUrl.isNullOrBlank() &&
                detail.sourceSite.contains("MissAV", ignoreCase = true) &&
                detail.sourceUrl.startsWith("http", ignoreCase = true) &&
                !runtimeResolverTried

            if (streamUrl.isNullOrBlank() || player == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                ) {
                    if (!cover.isNullOrBlank()) {
                        AsyncImage(
                            model = cover,
                            contentDescription = detail.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.62f))
                    )
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            if (shouldResolveMissavRuntime) stringResource(R.string.playback_resolving_title) else stringResource(R.string.playback_unavailable_title),
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Text(
                            if (shouldResolveMissavRuntime) stringResource(R.string.playback_resolving_subtitle) else stringResource(R.string.playback_unavailable_subtitle),
                            color = DarkTxtSecondary,
                            textAlign = TextAlign.Center
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (!shouldResolveMissavRuntime) {
                                TextButton(onClick = onRetry) {
                                    Text(stringResource(R.string.action_reparse), color = Color.White)
                                }
                            }
                            TextButton(onClick = { onClose(0) }) {
                                Text(stringResource(R.string.common_close), color = Color.White)
                            }
                        }
                    }

                    if (shouldResolveMissavRuntime) {
                        AndroidView(
                            factory = { viewContext ->
                                WebView(viewContext).apply {
                                    settings.userAgentString = CF_USER_AGENT
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.mediaPlaybackRequiresUserGesture = false
                                    webViewClient = object : WebViewClient() {
                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            super.onPageFinished(view, url)
                                            view?.postDelayed({
                                                view.evaluateJavascript(MISSAV_RUNTIME_SOURCE_SCRIPT) { raw ->
                                                    decodeJavascriptString(raw)
                                                        ?.takeIf { it.isNotBlank() }
                                                        ?.let { resolved ->
                                                            runtimeStreamUrl = resolved
                                                        }
                                                    runtimeResolverTried = true
                                                }
                                            }, 1200L)
                                        }
                                    }
                                    loadUrl(detail.sourceUrl)
                                }
                            },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(1.dp)
                        )
                    }
                }
                return@Surface
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                AndroidView(
                    factory = { viewContext ->
                        PlayerView(viewContext).apply {
                            useController = true
                            controllerAutoShow = true
                            controllerHideOnTouch = true
                            keepScreenOn = true
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            this.player = player
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                )

                if (forcedLandscape && !showFullscreenChrome) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable { showFullscreenChrome = true }
                    )
                }

                if (!forcedLandscape || showFullscreenChrome) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Black.copy(alpha = 0.74f), Color.Transparent)
                                )
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (forcedLandscape) {
                                IconButton(onClick = {
                                    forcedLandscape = false
                                    showFullscreenChrome = true
                                }) {
                                    Icon(
                                        imageVector = Icons.Filled.Clear,
                                        contentDescription = stringResource(R.string.playback_exit_fullscreen),
                                        tint = Color.White
                                    )
                                }
                                TextButton(onClick = {
                                    forcedLandscape = false
                                    showFullscreenChrome = true
                                }) {
                                    Text(stringResource(R.string.playback_exit_fullscreen), color = Color.White)
                                }
                            } else {
                                TextButton(onClick = { onClose(capturePlaybackProgress(player)) }) {
                                    Text(stringResource(R.string.common_return), color = Color.White)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = onRetry) {
                                        Text(stringResource(R.string.common_reload), color = Color.White)
                                    }
                                    TextButton(onClick = {
                                        forcedLandscape = true
                                        showFullscreenChrome = true
                                    }) {
                                        Text(stringResource(R.string.playback_fullscreen), color = Color.White)
                                    }
                                    SourceTag(
                                        source = detail.sourceSite.ifBlank { sourceFallback },
                                        textColor = sourceColor
                                    )
                                }
                            }
                        }
                    }
                }

                if (!forcedLandscape) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))
                                )
                            )
                            .padding(horizontal = 16.dp, vertical = 18.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                detail.title.ifBlank { untitledLabel },
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                color = Color.White
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (detail.code.isNotBlank()) {
                                    PlaybackInfoPill(detail.code.uppercase(Locale.ROOT), sourceColor)
                                }
                                PlaybackInfoPill(detail.sourceSite.ifBlank { sourceFallback }, Color.White)
                            }

                            if (detail.tags.isNotEmpty()) {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(detail.tags.take(5)) { tag ->
                                        PlaybackInfoPill(tag, sourceColor)
                                    }
                                }
                            }

                            if (detail.availableSources.size > 1) {
                                Text(stringResource(R.string.playback_switch_source), color = DarkTxtSecondary, fontSize = 11.sp)
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(detail.availableSources.distinctBy { it.url }) { source ->
                                        SourcePill(
                                            text = source.sourceSite.ifBlank { sourceFallback },
                                            selected = source.url == detail.sourceUrl,
                                            onClick = { onSwitchSource(source.url, capturePlaybackProgress(player)) },
                                            compact = true,
                                            selectedBackground = if (source.sourceSite.contains("Jable", ignoreCase = true)) BrandPink else BrandBlue
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun resolvePlayableMediaUrl(detail: VideoDetail): String? {
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

private fun isHlsPlaybackSource(url: String): Boolean {
    return url.contains(".m3u8", ignoreCase = true) ||
        url.startsWith("data:application/x-mpegurl", ignoreCase = true) ||
        url.startsWith("data:application/vnd.apple.mpegurl", ignoreCase = true) ||
        url.startsWith("data:audio/mpegurl", ignoreCase = true)
}

private fun buildPlaybackHeaders(refererUrl: String, mediaUrl: String): Map<String, String> {
    val normalizedReferer = refererUrl.trim().ifBlank { mediaUrl }
    val headers = linkedMapOf(
        "User-Agent" to CF_USER_AGENT,
        "Accept" to "*/*"
    )
    if (normalizedReferer.startsWith("http://") || normalizedReferer.startsWith("https://")) {
        headers["Referer"] = normalizedReferer
        extractUrlOrigin(normalizedReferer)?.let { headers["Origin"] = it }
    }
    return headers
}

private fun extractUrlOrigin(url: String): String? {
    return runCatching {
        val uri = java.net.URI(url)
        if (uri.scheme.isNullOrBlank() || uri.host.isNullOrBlank()) {
            null
        } else {
            "${uri.scheme}://${uri.host}"
        }
    }.getOrNull()
}

private fun decodeJavascriptString(raw: String?): String? {
    val value = raw?.trim().orEmpty()
    if (value.isBlank() || value == "null" || value == "\"\"") return null
    return value
        .removePrefix("\"")
        .removeSuffix("\"")
        .replace("\\u003A", ":")
        .replace("\\u002F", "/")
        .replace("\\u0026", "&")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
        .replace("\\/", "/")
        .trim()
        .takeIf { it.isNotBlank() }
}

private fun requiresLegacyDownloadPermission(context: Context): Boolean {
    return Build.VERSION.SDK_INT in Build.VERSION_CODES.M until Build.VERSION_CODES.Q &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
}

private fun requiresNotificationPermission(context: Context): Boolean {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    vm: MainViewModel,
    hostCandidates: List<String>,
    onDismiss: () -> Unit,
    onManualWarmup: () -> Unit,
    onHostSelect: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.settings_title), fontWeight = FontWeight.SemiBold)

            Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_primary_host), fontWeight = FontWeight.Medium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(hostCandidates) { host ->
                            SourcePill(
                                text = host,
                                selected = vm.hostState == host,
                                onClick = { onHostSelect(host) },
                                compact = true,
                                selectedBackground = BrandBlue
                            )
                        }
                    }
                    Text(stringResource(R.string.settings_current_host, vm.hostState), color = TxtSecondary, fontSize = 11.sp)
                }
            }

            Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.settings_search_sources), fontWeight = FontWeight.Medium)
                    vm.availableSites.forEach { site ->
                        SettingsSwitch(
                            title = site.name,
                            checked = vm.isSearchSiteEnabled(site.id),
                            onChecked = { vm.updateSearchSiteEnabled(site.id, it) }
                        )
                    }
                    Text(
                        stringResource(R.string.settings_search_sources_hint),
                        color = TxtSecondary,
                        fontSize = 11.sp
                    )
                }
            }

            if (vm.isSearchSiteEnabled(SiteRegistry.missav.id)) {
                Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.settings_search_missav_domains), fontWeight = FontWeight.Medium)
                        SiteRegistry.missav.hosts.forEach { host ->
                            SettingsSwitch(
                                title = host.removePrefix("https://"),
                                checked = vm.isMissavSearchHostEnabled(host),
                                onChecked = { vm.updateMissavSearchHostEnabled(host, it) }
                            )
                        }
                        Text(
                            stringResource(R.string.settings_search_missav_domains_hint),
                            color = TxtSecondary,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsSwitch(title = stringResource(R.string.settings_auto_restore), checked = vm.autoCloudflareEnabled, onChecked = vm::updateAutoCloudflareEnabled)
                    TextButton(onClick = onManualWarmup) {
                        Text(stringResource(R.string.settings_manual_refresh))
                    }
                }
            }

            Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.github_update_title), fontWeight = FontWeight.Medium)
                    Text(
                        stringResource(R.string.github_update_hint, vm.currentVersionName),
                        color = TxtSecondary,
                        fontSize = 11.sp
                    )
                    OutlinedTextField(
                        value = vm.githubReleaseRepo,
                        onValueChange = vm::updateGithubReleaseRepo,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.github_update_repo_label)) },
                        placeholder = { Text(stringResource(R.string.github_update_repo_placeholder)) }
                    )
                    Button(
                        onClick = vm::checkGithubReleaseAndInstall,
                        enabled = !vm.githubUpdateInProgress,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (vm.githubUpdateInProgress) {
                                stringResource(R.string.github_update_checking)
                            } else {
                                stringResource(R.string.github_update_action)
                            }
                        )
                    }
                }
            }

            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_done))
            }
        }
    }
}

@Composable
private fun SettingsSwitch(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CloudflareWarmupDialog(host: String, onResult: (Boolean, String?) -> Unit) {
    val context = LocalContext.current
    var solved by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf(context.getString(R.string.cf_dialog_initial)) }
    var currentUrl by remember(host) { mutableStateOf(normalizeHostUrl(host)) }

    AlertDialog(
        onDismissRequest = { onResult(false, currentUrl) },
        title = { Text(stringResource(R.string.cf_dialog_title), fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message, color = TxtSecondary, fontSize = 11.sp)
                AndroidView(factory = { context ->
                    WebView(context).apply {
                        settings.userAgentString = CF_USER_AGENT
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                currentUrl = url.orEmpty().ifBlank { currentUrl }
                                val solvedNow = isCloudflareSolved(currentUrl, view?.title.orEmpty())
                                solved = solvedNow
                                message = if (solvedNow) {
                                    context.getString(R.string.cf_dialog_ready)
                                } else {
                                    context.getString(R.string.cf_dialog_connecting)
                                }
                            }
                        }
                        loadUrl(normalizeHostUrl(host))
                    }
                }, modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .background(MaterialTheme.colorScheme.surface)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onResult(solved, currentUrl) }, enabled = solved) {
                Text(stringResource(R.string.cf_dialog_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = { onResult(false, currentUrl) }) {
                Text(stringResource(R.string.cf_dialog_later))
            }
        }
    )
}

private fun isCloudflareSolved(url: String, title: String): Boolean {
    val lowerUrl = url.lowercase(Locale.ROOT)
    val lowerTitle = title.lowercase(Locale.ROOT)
    return !(lowerUrl.contains("/cdn-cgi/challenge-platform") || lowerUrl.contains("just a moment") || lowerTitle.contains("just a moment"))
}

private fun formatRelativeTime(context: Context, millis: Long): String {
    val now = System.currentTimeMillis()
    val delta = maxOf(0L, now - millis)
    val minute = 60_000L
    val hour = 60 * minute
    val day = 24 * hour

    return when {
        delta < minute -> context.getString(R.string.relative_just_now)
        delta < hour -> context.getString(R.string.relative_minutes_ago, delta / minute)
        delta < day -> context.getString(R.string.relative_hours_ago, delta / hour)
        else -> context.getString(R.string.relative_days_ago, delta / day)
    }
}

private fun formatStorageSize(bytes: Long): String {
    if (bytes <= 0L) return ""
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        bytes >= gb -> String.format(Locale.ROOT, "%.1f GB", bytes / gb)
        bytes >= mb -> String.format(Locale.ROOT, "%.1f MB", bytes / mb)
        bytes >= kb -> String.format(Locale.ROOT, "%.0f KB", bytes / kb)
        else -> "$bytes B"
    }
}

private fun normalizeHostUrl(host: String): String {
    val trimmed = host.trim().trimEnd('/')
    if (trimmed.isBlank()) return "https://missav.ai"
    return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
}

private fun sanitizeUiErrorMessage(context: Context, message: String): String {
    val normalized = message.lowercase(Locale.ROOT)
    return if (
        normalized.contains("cloudflare") ||
        normalized.contains("cf") ||
        normalized.contains("403") ||
        normalized.contains("forbidden")
    ) {
        context.getString(R.string.message_network_unstable)
    } else {
        message
    }
}

private fun capturePlaybackProgress(player: ExoPlayer?): Int {
    if (player == null) return 0
    val duration = player.duration
    val position = player.currentPosition
    if (duration <= 0L || position <= 0L) return 0
    return ((position * 100) / duration).toInt().coerceIn(0, 100)
}

@StringRes
private fun detailRecommendationTitleRes(source: String): Int {
    return if (source.isJableSource()) R.string.detail_recommend_guess else R.string.detail_recommend_related
}

private fun isPreferredDisplayCard(candidate: VideoCard, current: VideoCard, normalizedQuery: String): Boolean {
    val candidateRank = searchDisplayRank(candidate, normalizedQuery)
    val currentRank = searchDisplayRank(current, normalizedQuery)
    if (candidateRank != currentRank) return candidateRank < currentRank

    val candidateThumb = !candidate.thumbnail.isNullOrBlank()
    val currentThumb = !current.thumbnail.isNullOrBlank()
    if (candidateThumb != currentThumb) return candidateThumb

    return candidate.title.trim().length > current.title.trim().length
}

private fun searchDisplayRank(card: VideoCard, normalizedQuery: String): Int {
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

private fun String.isMissavSource(): Boolean {
    return contains("missav", ignoreCase = true) || isBlank()
}

private fun String.isJableSource(): Boolean {
    return contains("jable", ignoreCase = true)
}


