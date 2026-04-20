package com.example.missavapp.data.model

data class VideoItem(
    val code: String,
    val title: String,
    val href: String,
    val thumbnail: String? = null,
    val sourceSite: String = "MissAV"
)
typealias VideoCard = VideoItem

data class VideoSourceOption(
    val sourceSite: String,
    val url: String,
    val title: String = ""
)

data class HistoryItem(
    val video: VideoCard,
    val playedAt: Long = System.currentTimeMillis(),
    val progressPercent: Int = 0
)

data class SiteProfile(
    val id: String,
    val name: String,
    val hosts: List<String>,
    val locale: String = "cn",
    val enabled: Boolean = true,
    val note: String? = null
)

data class Actress(
    val name: String,
    val url: String
)

data class VideoDetail(
    val code: String,
    val title: String,
    val href: String,
    val hlsUrl: String? = null,
    val thumbnails: List<String> = emptyList(),
    val actresses: List<Actress> = emptyList(),
    val tags: List<String> = emptyList(),
    val recommendations: List<VideoCard> = emptyList(),
    val sourceSite: String = "MissAV",
    val sourceUrl: String = href,
    val availableSources: List<VideoSourceOption> = emptyList(),
    val saved: Boolean? = null,
    val saveApiUrl: String? = null
)

enum class MissAvSection(val slug: String, val title: String) {
    New("new", "最新更新"),
    Release("release", "新作"),
    Uncensored("uncensored-leak", "无码流出"),
    Subtitle("chinese-subtitle", "中文字幕"),
    Chinese("cn", "中文列表")
}

data class LoginResult(
    val ok: Boolean,
    val message: String?
)

data class UserInfo(
    val id: String? = null,
    val email: String? = null,
)

enum class DownloadStatus {
    Queued,
    Running,
    Paused,
    Completed,
    Failed,
    Cancelled
}

data class DownloadTask(
    val id: String,
    val title: String,
    val code: String?,
    val sourceUrl: String,
    val filePath: String,
    val status: DownloadStatus,
    val progress: Int = 0,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val message: String? = null
)

sealed class LoadState<out T> {
    object Idle : LoadState<Nothing>()
    object Loading : LoadState<Nothing>()
    data class Success<T>(val data: T) : LoadState<T>()
    data class Error(val message: String) : LoadState<Nothing>()
    object CloudflareChallenge : LoadState<Nothing>()
}
