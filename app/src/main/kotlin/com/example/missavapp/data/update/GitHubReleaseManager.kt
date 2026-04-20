package com.example.missavapp.data.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.Locale

sealed class GitHubUpdateResult {
    data class UpToDate(val tagName: String) : GitHubUpdateResult()
    data class InstallStarted(val tagName: String, val assetName: String) : GitHubUpdateResult()
}

class GitHubReleaseManager(
    private val appContext: Context,
    private val httpClient: OkHttpClient = OkHttpClient()
) {
    suspend fun downloadLatestApkAndInstall(repoInput: String, currentVersionName: String): GitHubUpdateResult {
        val repo = normalizeRepo(repoInput)
            ?: throw IllegalArgumentException("请填写 GitHub 仓库，例如 owner/repo")
        val release = fetchLatestRelease(repo)
        val latestVersion = release.tagName.trim().removePrefix("v").removePrefix("V")
        val currentVersion = currentVersionName.trim().removePrefix("v").removePrefix("V")
        if (latestVersion.isNotBlank() && latestVersion == currentVersion) {
            return GitHubUpdateResult.UpToDate(release.tagName)
        }

        val asset = release.apkAssets
            .sortedWith(
                compareByDescending<GitHubReleaseAsset> {
                    val name = it.name.lowercase(Locale.ROOT)
                    name.contains("release") || name.contains("universal")
                }.thenByDescending { it.sizeBytes }
            )
            .firstOrNull()
            ?: throw IllegalStateException("最新 GitHub Release 中没有 APK 文件")

        val apkFile = downloadApk(asset)
        withContext(Dispatchers.Main) {
            openInstaller(apkFile)
        }
        return GitHubUpdateResult.InstallStarted(release.tagName, asset.name)
    }

    private suspend fun fetchLatestRelease(repo: String): GitHubRelease = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Fluxia-Android-Updater")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("GitHub Release 查询失败 HTTP=${response.code}")
            }
            val body = response.body?.string().orEmpty()
            val root = JSONObject(body)
            val tagName = root.optString("tag_name").ifBlank {
                root.optString("name").ifBlank { "latest" }
            }
            val assets = parseAssets(root.optJSONArray("assets"))
            GitHubRelease(tagName = tagName, apkAssets = assets)
        }
    }

    private suspend fun downloadApk(asset: GitHubReleaseAsset): File = withContext(Dispatchers.IO) {
        val updateDir = File(appContext.cacheDir, "github-updates")
        if (!updateDir.exists()) {
            updateDir.mkdirs()
        }
        val target = File(updateDir, sanitizeFileName(asset.name))
        if (target.exists()) {
            target.delete()
        }

        val request = Request.Builder()
            .url(asset.downloadUrl)
            .header("Accept", "application/octet-stream")
            .header("User-Agent", "Fluxia-Android-Updater")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("APK 下载失败 HTTP=${response.code}")
            }
            val body = response.body ?: throw IllegalStateException("APK 下载响应为空")
            target.outputStream().use { output ->
                body.byteStream().use { input -> input.copyTo(output) }
            }
        }

        target
    }

    private fun openInstaller(apkFile: File) {
        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        appContext.startActivity(intent)
    }

    private fun parseAssets(array: JSONArray?): List<GitHubReleaseAsset> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val name = item.optString("name").trim()
                val url = item.optString("browser_download_url").trim()
                if (name.isBlank() || url.isBlank()) continue
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                add(
                    GitHubReleaseAsset(
                        name = name,
                        downloadUrl = url,
                        sizeBytes = item.optLong("size", 0L)
                    )
                )
            }
        }
    }

    private fun normalizeRepo(input: String): String? {
        val trimmed = input.trim().trimEnd('/')
        if (trimmed.isBlank()) return null

        val path = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            runCatching { URI(trimmed).path.trim('/') }.getOrNull().orEmpty()
        } else {
            trimmed.removePrefix("github.com/").trim('/')
        }
        val parts = path.split('/').filter { it.isNotBlank() }
        if (parts.size < 2) return null
        val owner = parts[0]
        val repo = parts[1]
        val validPart = Regex("""^[A-Za-z0-9_.-]+$""")
        if (!validPart.matches(owner) || !validPart.matches(repo)) return null
        return "$owner/$repo"
    }

    private fun sanitizeFileName(input: String): String {
        return input.replace(Regex("""[\\/:*?"<>|]"""), "_")
            .ifBlank { "fluxia-latest.apk" }
    }

    private data class GitHubRelease(
        val tagName: String,
        val apkAssets: List<GitHubReleaseAsset>
    )

    private data class GitHubReleaseAsset(
        val name: String,
        val downloadUrl: String,
        val sizeBytes: Long
    )
}
