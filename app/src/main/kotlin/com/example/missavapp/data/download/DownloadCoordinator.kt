package com.example.missavapp.data.download

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.missavapp.R
import com.example.missavapp.data.model.DownloadStatus
import com.example.missavapp.data.model.DownloadTask
import com.example.missavapp.data.network.MissAvHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class DownloadCoordinator(
    context: Context,
    private val httpClient: MissAvHttpClient
) {
    companion object {
        private const val DOWNLOAD_CHANNEL_ID = "fluxia.downloads"
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val lastNotificationProgress = ConcurrentHashMap<String, Int>()

    fun startDownload(title: String, code: String?, sourceUrl: String, refererUrl: String? = null): String {
        val id = UUID.randomUUID().toString()
        val safeTitle = sanitizeFileName(title.ifBlank { "missav_video" })
        val safeCode = sanitizeFileName(code.orEmpty())
        val isHls = sourceUrl.contains(".m3u8", ignoreCase = true)
        val extension = if (isHls) "mp4" else inferFileExtension(sourceUrl)
        val fileName = buildDownloadFileName(safeTitle, safeCode, extension)
        val displayPath = text(R.string.downloads_path_format, fileName)

        val tempFile = File(appContext.cacheDir, "downloads/$fileName")
        tempFile.parentFile?.mkdirs()

        _tasks.update { list ->
            list + DownloadTask(
                id = id,
                title = title.ifBlank { safeCode.ifBlank { text(R.string.content_unnamed) } },
                code = code,
                sourceUrl = sourceUrl,
                filePath = displayPath,
                status = DownloadStatus.Queued,
                message = text(R.string.download_message_preparing)
            )
        }
        showTaskNotification(
            DownloadTask(
                id = id,
                title = title.ifBlank { safeCode.ifBlank { text(R.string.content_unnamed) } },
                code = code,
                sourceUrl = sourceUrl,
                filePath = displayPath,
                status = DownloadStatus.Queued,
                message = text(R.string.download_message_preparing)
            )
        )

        val job = scope.launch {
            updateTask(id) {
                it.copy(
                    status = DownloadStatus.Running,
                    message = if (isHls) text(R.string.download_message_merging_segments) else text(R.string.download_message_downloading_file)
                )
            }
            val result = if (isHls) {
                downloadHlsStream(id, sourceUrl, tempFile, refererUrl)
            } else {
                httpClient.downloadToFile(sourceUrl, tempFile, refererUrl) { downloaded, total ->
                    val progress = if (total > 0) {
                        ((downloaded.toDouble() / total.toDouble()) * 100).toInt().coerceIn(0, 100)
                    } else {
                        0
                    }
                    updateTask(id) {
                        it.copy(
                            status = DownloadStatus.Running,
                            progress = progress,
                            downloadedBytes = downloaded,
                            totalBytes = total,
                            message = if (total > 0) text(R.string.download_message_downloading_file) else text(R.string.download_message_fetching_file)
                        )
                    }
                }
            }

            result.fold(
                onSuccess = {
                    val published = publishToDownloads(tempFile, fileName)
                    updateTask(id) {
                        it.copy(
                            status = DownloadStatus.Completed,
                            progress = 100,
                            filePath = published.path,
                            message = text(R.string.download_message_saved)
                        )
                    }
                    showCompletedNotification(id, fileName, title.ifBlank { safeCode.ifBlank { text(R.string.content_unnamed) } }, published)
                },
                onFailure = { err ->
                    updateTask(id) {
                        it.copy(
                            status = if (err is CancellationException) DownloadStatus.Cancelled else DownloadStatus.Failed,
                            message = err.message ?: text(R.string.download_error_failed)
                        )
                    }
                    tempFile.delete()
                }
            )
            tempFile.delete()
            jobs.remove(id)
            lastNotificationProgress.remove(id)
        }

        jobs[id] = job
        return id
    }

    fun cancel(id: String) {
        jobs[id]?.let {
            it.cancel()
            jobs.remove(id)
            updateTask(id) { task -> task.copy(status = DownloadStatus.Cancelled, message = text(R.string.download_message_cancelled)) }
            NotificationManagerCompat.from(appContext).cancel(notificationId(id))
        }
    }

    fun clearFinished() {
        _tasks.update { list ->
            list.filter { task ->
                task.status !in setOf(DownloadStatus.Completed, DownloadStatus.Cancelled, DownloadStatus.Failed)
            }
        }
    }

    fun close() {
        scope.cancel()
    }

    private suspend fun downloadHlsStream(id: String, playlistUrl: String, outFile: File, refererUrl: String?): Result<Unit> {
        var tempDir: File? = null
        return runCatching {
            val mediaPlaylistUrl = resolveMediaPlaylistUrl(playlistUrl, refererUrl)
            val playlistBody = fetchText(mediaPlaylistUrl, refererUrl)
            if (playlistBody.contains("#EXT-X-KEY", ignoreCase = true) && !playlistBody.contains("METHOD=NONE", ignoreCase = true)) {
                throw IOException(text(R.string.download_error_direct_cache_unsupported))
            }

            val baseUri = URI(mediaPlaylistUrl)
            val initSegment = parseInitSegmentUri(playlistBody)?.let { resolveUri(baseUri, it) }
            val segments = parseMediaSegments(playlistBody, baseUri)
            if (segments.isEmpty()) {
                throw IOException(text(R.string.download_error_segments_missing))
            }

            outFile.parentFile?.mkdirs()
            if (outFile.exists()) outFile.delete()
            tempDir = File(outFile.parentFile, ".$id.parts").apply {
                if (exists()) deleteRecursively()
                mkdirs()
            }

            var completedSegments = 0
            var completedBytes = 0L

            outFile.outputStream().buffered().use { output ->
                initSegment?.let { initUrl ->
                    val initFile = File(tempDir, "init.part")
                    httpClient.downloadToFile(initUrl, initFile, mediaPlaylistUrl) { _, _ -> }.getOrThrow()
                    initFile.inputStream().use { input -> input.copyTo(output) }
                    completedBytes += initFile.length()
                    initFile.delete()
                }

                segments.forEachIndexed { index, segmentUrl ->
                    val partFile = File(tempDir, "segment_${index.toString().padStart(5, '0')}.part")
                    httpClient.downloadToFile(segmentUrl, partFile, mediaPlaylistUrl) { downloaded, total ->
                        val segmentFraction = if (total > 0) downloaded.toDouble() / total.toDouble() else 0.0
                        val overall = (((completedSegments + segmentFraction) / segments.size.toDouble()) * 100.0).toInt().coerceIn(0, 99)
                        updateTask(id) {
                            it.copy(
                                status = DownloadStatus.Running,
                                progress = overall,
                                downloadedBytes = completedBytes + downloaded,
                                totalBytes = if (total > 0) completedBytes + total else 0L,
                                message = text(R.string.download_message_merging_progress, index + 1, segments.size)
                            )
                        }
                    }.getOrThrow()

                    partFile.inputStream().use { input -> input.copyTo(output) }
                    completedBytes += partFile.length()
                    completedSegments += 1
                    partFile.delete()

                    updateTask(id) {
                        it.copy(
                            status = DownloadStatus.Running,
                            progress = ((completedSegments * 100.0) / segments.size.toDouble()).toInt().coerceIn(0, 100),
                            downloadedBytes = completedBytes,
                            totalBytes = completedBytes,
                            message = text(R.string.download_message_merging_progress, completedSegments, segments.size)
                        )
                    }
                }
            }
        }.onFailure {
            tempDir?.deleteRecursively()
            outFile.delete()
        }.onSuccess {
            tempDir?.deleteRecursively()
        }
    }

    private fun updateTask(id: String, mutate: (DownloadTask) -> DownloadTask) {
        var updatedTask: DownloadTask? = null
        _tasks.update { list ->
            list.map { task ->
                if (task.id == id) {
                    mutate(task).also { updatedTask = it }
                } else {
                    task
                }
            }
        }
        updatedTask?.let(::showTaskNotification)
    }

    private suspend fun resolveMediaPlaylistUrl(playlistUrl: String, refererUrl: String?): String {
        var current = playlistUrl
        repeat(3) {
            val body = fetchText(current, refererUrl)
            val variant = parseVariantPlaylistUrl(body, current) ?: return current
            current = variant
        }
        return current
    }

    private suspend fun fetchText(url: String, refererUrl: String?): String {
        val response = httpClient.performGet(url, refererUrl).getOrElse { throw it }
        if (response.code !in 200..299 || response.body.isBlank()) {
            throw IOException(text(R.string.download_error_resource_http, response.code))
        }
        return response.body
    }

    private fun parseVariantPlaylistUrl(body: String, currentUrl: String): String? {
        val lines = body.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        if (lines.none { it.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) }) return null

        val baseUri = URI(currentUrl)
        var bestBandwidth = -1L
        var bestUrl: String? = null

        lines.forEachIndexed { index, line ->
            if (!line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) return@forEachIndexed
            val bandwidth = Regex("BANDWIDTH=(\\d+)", RegexOption.IGNORE_CASE)
                .find(line)
                ?.groupValues
                ?.getOrNull(1)
                ?.toLongOrNull()
                ?: 0L

            val candidate = lines.drop(index + 1).firstOrNull { !it.startsWith("#") } ?: return@forEachIndexed
            if (bandwidth >= bestBandwidth) {
                bestBandwidth = bandwidth
                bestUrl = resolveUri(baseUri, candidate)
            }
        }

        return bestUrl
    }

    private fun parseInitSegmentUri(body: String): String? {
        return Regex("""#EXT-X-MAP:.*URI="([^"]+)"""", RegexOption.IGNORE_CASE)
            .find(body)
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun parseMediaSegments(body: String, baseUri: URI): List<String> {
        return body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { resolveUri(baseUri, it) }
            .toList()
    }

    private fun resolveUri(baseUri: URI, value: String): String {
        return baseUri.resolve(value.trim()).toString()
    }

    private fun inferFileExtension(sourceUrl: String): String {
        val path = sourceUrl.substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext.takeIf { it.matches(Regex("[a-z0-9]{2,4}")) } ?: "mp4"
    }

    private fun sanitizeFileName(input: String): String {
        return input
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\p{Cntrl}+"), "")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '.', '_')
            .ifBlank { "video" }
    }

    private fun buildDownloadFileName(title: String, code: String, extension: String): String {
        val baseName = buildList {
            code.takeIf { it.isNotBlank() }?.let(::add)
            title.takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) }?.let(::add)
        }.joinToString(" - ")
            .take(96)
            .ifBlank { "video" }
        return "$baseName.$extension"
    }

    private fun publishToDownloads(sourceFile: File, fileName: String): PublishedDownload {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = appContext.contentResolver
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, inferMimeType(fileName))
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Fluxia")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val uri = resolver.insert(collection, values)
                ?: throw IOException(text(R.string.download_error_create_file))

            try {
                resolver.openOutputStream(uri)?.use { output ->
                    sourceFile.inputStream().use { input -> input.copyTo(output) }
                } ?: throw IOException(text(R.string.download_error_write_system_dir))

                val completed = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(uri, completed, null, null)
                return PublishedDownload(
                    path = text(R.string.downloads_path_format, fileName),
                    uri = uri,
                    mimeType = inferMimeType(fileName)
                )
            } catch (error: Throwable) {
                resolver.delete(uri, null, null)
                throw error
            }
        }

        val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Fluxia")
        if (!publicDir.exists()) {
            publicDir.mkdirs()
        }
        val target = uniquePublicTarget(publicDir, fileName)
        sourceFile.copyTo(target, overwrite = true)
        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            target
        )
        return PublishedDownload(
            path = target.absolutePath,
            uri = uri,
            mimeType = inferMimeType(fileName)
        )
    }

    private fun uniquePublicTarget(directory: File, fileName: String): File {
        val dotIndex = fileName.lastIndexOf('.')
        val name = if (dotIndex > 0) fileName.substring(0, dotIndex) else fileName
        val ext = if (dotIndex > 0) fileName.substring(dotIndex) else ""
        var candidate = File(directory, fileName)
        var suffix = 1
        while (candidate.exists()) {
            candidate = File(directory, "$name ($suffix)$ext")
            suffix += 1
        }
        return candidate
    }

    private fun showTaskNotification(task: DownloadTask) {
        if (!canPostNotifications()) return
        ensureNotificationChannel()

        when (task.status) {
            DownloadStatus.Queued -> {
                notifyProgress(task, indeterminate = true)
            }
            DownloadStatus.Running -> {
                val current = task.progress.coerceIn(0, 100)
                val previous = lastNotificationProgress[task.id]
                if (previous == null || current == 0 || current == 100 || kotlin.math.abs(current - previous) >= 5) {
                    lastNotificationProgress[task.id] = current
                    notifyProgress(task, indeterminate = task.totalBytes <= 0L && current <= 0)
                }
            }
            DownloadStatus.Failed -> {
                lastNotificationProgress.remove(task.id)
                NotificationManagerCompat.from(appContext).notify(
                    notificationId(task.id),
                    NotificationCompat.Builder(appContext, DOWNLOAD_CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.stat_sys_warning)
                        .setContentTitle(task.title.ifBlank { text(R.string.download_fallback_title) })
                        .setContentText(task.message ?: text(R.string.download_error_failed))
                        .setAutoCancel(true)
                        .build()
                )
            }
            DownloadStatus.Cancelled -> {
                lastNotificationProgress.remove(task.id)
                NotificationManagerCompat.from(appContext).cancel(notificationId(task.id))
            }
            DownloadStatus.Completed,
            DownloadStatus.Paused -> Unit
        }
    }

    private fun notifyProgress(task: DownloadTask, indeterminate: Boolean) {
        val notification = NotificationCompat.Builder(appContext, DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(task.title.ifBlank { text(R.string.download_fallback_title) })
            .setContentText(task.message ?: text(R.string.download_message_downloading_file))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, task.progress.coerceIn(0, 100), indeterminate)
            .build()
        NotificationManagerCompat.from(appContext).notify(notificationId(task.id), notification)
    }

    private fun showCompletedNotification(id: String, fileName: String, title: String, published: PublishedDownload) {
        if (!canPostNotifications()) return
        ensureNotificationChannel()

        val openIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(published.uri, published.mimeType)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openPendingIntent = PendingIntent.getActivity(
            appContext,
            notificationId(id),
            openIntent,
            pendingFlags
        )

        val notification = NotificationCompat.Builder(appContext, DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title.ifBlank { text(R.string.download_fallback_title) })
            .setContentText(text(R.string.download_message_saved))
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .addAction(
                0,
                text(R.string.download_notification_open),
                openPendingIntent
            )
            .setStyle(NotificationCompat.BigTextStyle().bigText("$fileName\n${published.path}"))
            .build()

        NotificationManagerCompat.from(appContext).notify(notificationId(id), notification)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(DOWNLOAD_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            DOWNLOAD_CHANNEL_ID,
            text(R.string.download_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = text(R.string.download_notification_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun notificationId(id: String): Int = id.hashCode()

    private fun inferMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (ext) {
            "ts" -> "video/mp2t"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            else -> "application/octet-stream"
        }
    }

    private fun text(@StringRes resId: Int, vararg args: Any): String = appContext.getString(resId, *args)
}

private data class PublishedDownload(
    val path: String,
    val uri: Uri,
    val mimeType: String
)
