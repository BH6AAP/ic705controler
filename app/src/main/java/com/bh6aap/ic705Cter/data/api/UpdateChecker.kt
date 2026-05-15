package com.bh6aap.ic705Cter.data.api

import com.bh6aap.ic705Cter.BuildConfig
import com.bh6aap.ic705Cter.util.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * GitHub Releases 更新检查器。
 *
 * 通过 GitHub Releases API 获取原作者仓库的最新版本信息，
 * 与本地 BuildConfig.VERSION_NAME 对比，返回更新结果。
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val GITHUB_API_URL =
        "https://api.github.com/repos/BH6AAP/ic705controler/releases/latest"

    /** 加速代理基础地址列表 */
    private val PROXY_BASES = listOf(
        "https://gh-proxy.org/",
        "https://cdn.gh-proxy.org/",
        "https://edgeone.gh-proxy.org/"
    )

    /** 下载源信息 */
    data class DownloadSource(
        val label: String,
        val url: String
    )

    /** 更新检查结果 */
    sealed class Result {
        /** 已是最新版本 */
        data class NoUpdate(val currentVersion: String) : Result()

        /** 有新版本可用 */
        data class UpdateAvailable(
            val currentVersion: String,
            val latestVersion: String,
            val changelog: String,
            val downloadUrl: String?
        ) : Result()

        /** 检查失败 */
        data class Error(val message: String) : Result()
    }

    /**
     * 检查更新（挂起函数，应在协程中调用）。
     */
    suspend fun check(): Result = withContext(Dispatchers.IO) {
        val currentVersion = BuildConfig.VERSION_NAME

        try {
            // GitHub API 需要跟随可能的重定向，故不使用 SecureHttp（禁用重定向）
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .build()

            // GitHub API 不需要走用户自定义 URL 校验，走内置 Safe URL
            // 直接构建 Request（该 URL 硬编码，无需 validateOutboundUrl）
            val request = Request.Builder()
                .url(GITHUB_API_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "ic705controler/${BuildConfig.VERSION_NAME}")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    LogManager.w(TAG, "GitHub API 返回 ${response.code}")
                    return@withContext Result.Error(
                        if (response.code == 403) "API 频率限制，请稍后再试"
                        else "检查失败 (HTTP ${response.code})"
                    )
                }

                val body = SecureHttp.readLimitedBody(response, limitBytes = 512 * 1024)
                    ?: return@withContext Result.Error("响应数据为空")

                val json = JSONObject(body)
                val rawTag = json.optString("tag_name", "")
                if (rawTag.isEmpty()) return@withContext Result.Error("未获取到版本信息")

                // 去掉可能的前导 "v"
                val latestVersion = rawTag.trimStart('v')

                if (compareVersions(latestVersion, currentVersion) <= 0) {
                    LogManager.i(TAG, "已是最新版本: $currentVersion (远端: $latestVersion)")
                    return@withContext Result.NoUpdate(currentVersion)
                }

                val changelog = json.optString("body", "")
                val downloadUrl = json.optJSONArray("assets")
                    ?.optJSONObject(0)
                    ?.optString("browser_download_url", null)

                LogManager.i(TAG, "发现新版本: $latestVersion (当前: $currentVersion)")
                Result.UpdateAvailable(
                    currentVersion = currentVersion,
                    latestVersion = latestVersion,
                    changelog = changelog,
                    downloadUrl = downloadUrl
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            LogManager.e(TAG, "检查更新失败", e)
            val msg = e.message
            val detail = if (msg.isNullOrBlank()) "${e.javaClass.simpleName}: ${e.cause?.message ?: "未知错误"}" else msg
            Result.Error(detail)
        }
    }

    /**
     * 比较两个点分版本号。
     *
     * @return 正数表示 a > b，负数表示 a < b，0 表示相等。
     *         短版本号缺少的段按 0 处理（如 3.5 vs 3.5.0 视为相等）。
     */
    internal fun compareVersions(a: String, b: String): Int {
        val aParts = a.split(".").map { it.toIntOrNull() ?: 0 }
        val bParts = b.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(aParts.size, bParts.size)

        for (i in 0 until maxLen) {
            val aVal = aParts.getOrElse(i) { 0 }
            val bVal = bParts.getOrElse(i) { 0 }
            if (aVal != bVal) return aVal - bVal
        }
        return 0
    }

    /**
     * 根据原始下载链接生成所有下载源（加速链接 + 原始链接）。
     *
     * @param originalUrl 原始 GitHub 下载链接（可能为 null，fallback 到 Releases 页面）
     * @return 下载源列表，第一个元素为默认加速链接
     */
    fun generateDownloadSources(originalUrl: String?): List<DownloadSource> {
        val baseUrl = originalUrl
            ?: "https://github.com/BH6AAP/ic705controler/releases"

        val sources = mutableListOf<DownloadSource>()

        // 加速链接：{proxy_base}{original_url}
        for (proxyBase in PROXY_BASES) {
            sources.add(DownloadSource(
                label = proxyBase.removeSuffix("/"),
                url = proxyBase + baseUrl
            ))
        }

        // 原始链接
        sources.add(DownloadSource(
            label = "GitHub (原始)",
            url = baseUrl
        ))

        return sources
    }
}
