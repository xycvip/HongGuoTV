// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class AppUpdate(val versionCode: Long, val versionName: String, val minSdk: Int,
                     val tag: String, val fileName: String, val size: Long, val sha256: String, val notes: String,
                     val downloadUrl: String) {
    fun newerThan(installed: Long, sdk: Int) = versionCode > installed && minSdk <= sdk
    companion object {
        const val OFFICIAL_PACKAGE = "com.x.hongguotv"
        const val OFFICIAL_REPOSITORY = "xycvip/AppDownload"
        const val OFFICIAL_TAG_PREFIX = "hongguotv-v"
        const val OFFICIAL_LATEST_MANIFEST = "https://raw.githubusercontent.com/xycvip/AppDownload/main/hongguoTV/latest.json"
        const val OFFICIAL_GITCODE_API = "https://api.gitcode.com/api/v5/repos/xycvip/hongguotv/releases/latest"
        const val BETA_PACKAGE = "com.x.hongguotv.bate"
        const val BETA_REPOSITORY = "xycvip/HongGuoTV"
        const val BETA_TAG_PREFIX = "hongguotv-bate-v"
        const val BETA_LATEST_MANIFEST = "https://raw.githubusercontent.com/xycvip/HongGuoTV/main/test-update/latest.json"
        const val MAX_APK_BYTES = 80L * 1024 * 1024
        /**
         * Public GitHub is often slow or unreachable from mainland China home networks.
         * These mirror CDNs act as transparent proxies and are generally accessible there,
         * so we fall through them in order until one responds. Each entry is a function
         * that takes the original GitHub URL and returns the proxied form; the raw URL is
         * always tried first.
         */
        val MIRRORS: List<(String) -> String> = listOf(
            { it },                                        // raw GitHub first
            { "https://gh-proxy.com/$it" },                // fastgit style proxy
            { "https://ghfast.top/$it" },                  // fastgit alternate
            { "https://v6.gh-proxy.org/$it" },                // another mirror
        )
        /** Display names aligned with MIRRORS for the manual download selector. */
        val MIRROR_NAMES = listOf("GitHub 原始线路", "gh-proxy.com", "ghfast.top", "v6.gh-proxy.org")
        /** All hosts we ever send HTTPS traffic to — the interceptor whitelist. */
        val ALLOWED_HOSTS = setOf(
            "github.com",
            "api.github.com",
            "raw.githubusercontent.com",
            "release-assets.githubusercontent.com",
            "objects.githubusercontent.com",
            "gh-proxy.com", "ghfast.top", "v6.gh-proxy.org",
            "api.gitcode.com", "gitcode.com", "raw.gitcode.com", "file-cdn.gitcode.com",
        )
        fun parse(
            json: String,
            expectedPackageName: String = OFFICIAL_PACKAGE,
            releaseRepository: String = OFFICIAL_REPOSITORY,
            releaseTagPrefix: String = OFFICIAL_TAG_PREFIX,
            apkFilePrefix: String = "HongguoTV-"
        ): AppUpdate {
            val o = JSONObject(json)
            require(o.getInt("schema") == 1 && o.getString("packageName") == expectedPackageName) { "更新信息与当前安装通道不匹配" }
            val apk = o.getJSONObject("apk")
            val manifestVersionName = o.getString("versionName")
            val manifestApkName = apk.getString("name")
            val manifestTag = o.getString("tag")
            val fallbackDownloadUrl = "https://github.com/$releaseRepository/releases/download/$manifestTag/$manifestApkName"
            val update = AppUpdate(o.getLong("versionCode"), manifestVersionName, o.getInt("minSdk"),
                manifestTag, manifestApkName, apk.getLong("size"), apk.getString("sha256").lowercase(), o.optString("notes").take(1500),
                apk.optString("url").takeIf { it.isNotBlank() } ?: fallbackDownloadUrl)
            require(update.versionCode in 1..Int.MAX_VALUE.toLong() && update.minSdk in 26..100)
            require(update.versionName.matches(Regex("[0-9][0-9A-Za-z.+-]{0,63}")))
            require(update.tag.matches(Regex(Regex.escape(releaseTagPrefix) + "[0-9][0-9A-Za-z.+-]{0,80}")))
            val expectedGitHubUrl = "https://github.com/$releaseRepository/releases/download/${update.tag}/${update.fileName}"
            val expectedGitCodeUrls = setOf(
                "https://gitcode.com/xycvip/hongguotv/releases/download/${update.tag}/${update.fileName}",
                // 兼容历史清单曾使用的旧附件路径。
                "https://gitcode.com/xycvip/hongguotv/-/releases/${update.tag}/downloads/${update.fileName}"
            )
            require(update.downloadUrl == expectedGitHubUrl ||
                (expectedPackageName == OFFICIAL_PACKAGE && update.downloadUrl in expectedGitCodeUrls))
            require(update.fileName.matches(Regex(Regex.escape(apkFilePrefix) + "[0-9][0-9A-Za-z.+-]{0,80}\\.apk")))
            require(update.size in 1..MAX_APK_BYTES && update.sha256.matches(Regex("[0-9a-f]{64}")))
            return update
        }
    }
}

/** Streaming validation also protects a cached APK from partial downloads and stale metadata. */
object UpdateFiles {
    fun verify(file: File, update: AppUpdate): Boolean {
        if (!file.isFile || file.length() != update.size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buf = ByteArray(32768); while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) } == update.sha256
    }
    fun receive(
        input: InputStream,
        destination: File,
        update: AppUpdate,
        progress: (Int) -> Unit = {},
        speed: (Long) -> Unit = {}
    ) {
        val partial = File(destination.parentFile, destination.name + ".part")
        destination.parentFile?.mkdirs()
        try {
            partial.outputStream().use { output ->
                val buffer = ByteArray(32768); var total = 0L; var last = -1
                var speedBytes = 0L; var speedStarted = System.nanoTime()
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw IOException("下载已取消")
                    val n = input.read(buffer); if (n < 0) break
                    total += n
                    if (total > update.size) throw IOException("安装包长度异常")
                    output.write(buffer, 0, n)
                    speedBytes += n
                    val now = System.nanoTime()
                    val elapsed = now - speedStarted
                    if (elapsed >= 500_000_000L) {
                        speed(speedBytes * 1_000_000_000L / elapsed)
                        speedBytes = 0L; speedStarted = now
                    }
                    val percent = (total * 100 / update.size).toInt()
                    if (percent != last) { last = percent; progress(percent) }
                }
                val elapsed = System.nanoTime() - speedStarted
                if (speedBytes > 0 && elapsed > 0) speed(speedBytes * 1_000_000_000L / elapsed)
                speed(0L)
                output.fd.sync()
            }
            if (!verify(partial, update)) throw IOException("安装包校验未通过，请重新下载")
            if (!partial.renameTo(destination)) {
                // File.renameTo refuses to replace an existing target on Windows.
                try { java.nio.file.Files.move(partial.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                catch(e: Exception) { throw IOException("无法保存安装包", e) }
            }
        } finally { partial.delete() }
    }
}

/**
 * 从 GitHub /releases/latest 响应中读取当前仓库的更新清单附件。
 * Latest 由 GitHub 在单个仓库内选定，不依赖 Releases 列表的返回顺序。
 */
internal fun latestReleaseManifestUrl(
    release: org.json.JSONObject,
    releaseTagPrefix: String
): String? {
    if (release.optBoolean("draft", false) || release.optBoolean("prerelease", false)) return null
    if (!release.optString("tag_name").startsWith(releaseTagPrefix)) return null

    val assets = release.optJSONArray("assets") ?: return null
    for (index in 0 until assets.length()) {
        val asset = assets.optJSONObject(index) ?: continue
        if (asset.optString("name") == "update.json") {
            return asset.optString("browser_download_url").takeIf { it.isNotBlank() }
        }
    }
    return null
}

/** GitCode 的 Latest Release API 使用 release_status 标记最新正式发行版。 */
internal fun gitCodeLatestReleaseManifestUrl(
    release: JSONObject,
    releaseTagPrefix: String
): String? {
    if (release.optBoolean("prerelease", false)) return null
    if (release.optString("release_status") != "latest") return null
    val tag = release.optString("tag_name")
    if (!tag.startsWith(releaseTagPrefix)) return null
    val assets = release.optJSONArray("assets") ?: return null
    for (index in 0 until assets.length()) {
        val asset = assets.optJSONObject(index) ?: continue
        if (asset.optString("type") != "attach" || asset.optString("name") != "update.json") continue
        val assetUrl = asset.optString("browser_download_url")
        val expectedUrls = setOf(
            "https://gitcode.com/xycvip/hongguotv/releases/download/$tag/update.json",
            // 兼容旧版 GitCode Release 附件路径。
            "https://gitcode.com/xycvip/hongguotv/-/releases/$tag/downloads/update.json"
        )
        return assetUrl.takeIf { it in expectedUrls }
    }
    return null
}

class AppUpdateRepository(
    private val expectedPackageName: String = AppUpdate.OFFICIAL_PACKAGE,
    private val releaseRepository: String = AppUpdate.OFFICIAL_REPOSITORY,
    private val releaseTagPrefix: String = AppUpdate.OFFICIAL_TAG_PREFIX,
    private val latestManifestUrl: String = AppUpdate.OFFICIAL_LATEST_MANIFEST,
    private val apkFilePrefix: String = "HongguoTV-"
) : java.io.Closeable {
    private val apiLatestReleaseUrl = "https://api.github.com/repos/$releaseRepository/releases/latest"
    private val isOfficialChannel = expectedPackageName == AppUpdate.OFFICIAL_PACKAGE
    private fun parseManifest(json: String) =
        AppUpdate.parse(json, expectedPackageName, releaseRepository, releaseTagPrefix, apkFilePrefix)

    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.MINUTES).addNetworkInterceptor { chain ->
            val url = chain.request().url
            // Follow GitHub CDN redirects + CN-friendly mirror proxies; never cleartext or arbitrary hosts.
            val host = url.host
            val isGitCodeHost = host == "gitcode.com" || host.endsWith(".gitcode.com")
            if (url.scheme != "https" || (!isGitCodeHost && host !in AppUpdate.ALLOWED_HOSTS))
                throw IOException("更新下载地址不受支持")
            chain.proceed(chain.request())
        }.build()
    private fun request(url: String) = Request.Builder().url(url).header("User-Agent", "HongguoTV-Updater")
        .header("Cache-Control", "no-cache, no-store, max-age=0").header("Pragma", "no-cache").build()

    /** Try each mirror in order; return the result from the first that works. */
    private fun <T> tryMirrors(base: String, maxPerMirrorSec: Long, block: (okHttpResponse: okhttp3.Response, url: String) -> T): T {
        val failures = ArrayList<String>()
        for (wrap in AppUpdate.MIRRORS) {
            val url = wrap(base)
            val call = http.newCall(request(url))
            call.timeout().timeout(maxPerMirrorSec, TimeUnit.SECONDS)
            var nextMirror = false
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        failures += "HTTP${response.code}@${hostOf(url)}"
                        nextMirror = true
                        return@use
                    }
                    return block(response, url)
                }
            } catch (e: IOException) {
                failures += "${e.message?.take(50) ?: "?"}@${hostOf(url)}"
            }
            if (nextMirror) continue
        }
        throw IOException("更新检查失败：${failures.joinToString("; ")}")
    }

    private fun hostOf(url: String) = url.removePrefix("https://").substringBefore("/")

    /**
     * 直接读取当前分发仓库的 Latest Release。
     * 测试版与正式版使用不同仓库，因此各自只会读取自己的 Latest。
     * API 或附件不可用时，latest() 仍会回退到对应渠道的 Raw latest.json。
     */
    /**
     * 正式版第一优先源：GitCode Latest Release API。
     * 只读取 /releases/latest，不扫描发行版列表；通过 update.json 获取版本与校验信息。
     */
    private fun latestFromGitCodeApi(): AppUpdate {
        val apiUrl = "${AppUpdate.OFFICIAL_GITCODE_API}?type=latest&cachebust=${System.currentTimeMillis()}"
        val releaseJson = http.newCall(request(apiUrl).newBuilder()
            .header("Accept", "application/json")
            .build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitCode Latest API HTTP${response.code}")
            val body = response.body ?: throw IOException("GitCode Latest API 响应为空")
            if (body.contentLength() > 2L * 1024 * 1024) throw IOException("GitCode Latest API 响应过大")
            body.string().also { if (it.length > 2 * 1024 * 1024) throw IOException("GitCode Latest API 响应过大") }
        }
        val release = JSONObject(releaseJson)
        val releaseTag = release.optString("tag_name")
        val manifestUrl = gitCodeLatestReleaseManifestUrl(release, releaseTagPrefix)
            ?: throw IOException("GitCode Latest Release 不属于正式版通道、状态不是 latest，或缺少 update.json 附件")
        // GitCode Release 附件地址必须保持原样；额外追加 query 参数可能导致 404。
        val manifestJson = http.newCall(request(manifestUrl)).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitCode 更新清单 HTTP${response.code}")
            val body = response.body ?: throw IOException("GitCode 更新清单为空")
            if (body.contentLength() > 65536) throw IOException("GitCode 更新清单过大")
            body.string().also { if (it.length > 65536) throw IOException("GitCode 更新清单过大") }
        }
        return try {
            parseManifest(manifestJson).also { update ->
                require(update.tag == releaseTag) { "GitCode 更新清单标签与 Latest Release 不一致" }
                val expectedGitCodeUrls = setOf(
                    "https://gitcode.com/xycvip/hongguotv/releases/download/$releaseTag/${update.fileName}",
                    "https://gitcode.com/xycvip/hongguotv/-/releases/$releaseTag/downloads/${update.fileName}"
                )
                require(update.downloadUrl in expectedGitCodeUrls) {
                    "GitCode 更新清单中的 APK 地址不是当前 Release 的正式版附件"
                }
            }
        } catch (_: Exception) {
            throw IOException("GitCode Release 更新信息格式不正确或标签、下载地址不匹配")
        }
    }

    private fun latestFromApi(): AppUpdate {
        val apiUrl = "${apiLatestReleaseUrl}?cachebust=${System.currentTimeMillis()}"
        val releaseJson = http.newCall(request(apiUrl).newBuilder()
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub Latest API HTTP${response.code}")
            val body = response.body ?: throw IOException("GitHub Latest API 响应为空")
            if (body.contentLength() > 2L * 1024 * 1024) throw IOException("GitHub Latest API 响应过大")
            body.string().also { if (it.length > 2 * 1024 * 1024) throw IOException("GitHub Latest API 响应过大") }
        }

        val release = org.json.JSONObject(releaseJson)
        val releaseTag = release.optString("tag_name")
        val assetUrl = latestReleaseManifestUrl(release, releaseTagPrefix)
            ?: throw IOException("Latest Release 不属于当前更新通道，或缺少 update.json 附件")
        val manifestUrl = "${assetUrl}?cachebust=${System.currentTimeMillis()}"
        return tryMirrors(manifestUrl, 12) { response, _ ->
            val bytes = response.body?.byteStream()?.use { input ->
                val out = ByteArrayOutputStream(); val buffer = ByteArray(4096)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    if (out.size() + n > 65536) throw IOException("更新信息过大")
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            } ?: throw IOException("更新信息为空")
            try {
                parseManifest(bytes.toString(Charsets.UTF_8)).also { update ->
                    require(update.tag == releaseTag) { "更新清单标签与 Latest Release 不一致" }
                }
            } catch (_: Exception) {
                throw IOException("Release 更新信息格式不正确或标签不匹配")
            }
        }
    }

    /** 正式版：GitCode Latest API 优先，AppDownload GitHub Release API / Raw 备用。测试版保持原有 GitHub 策略。 */
    fun latest(): AppUpdate {
        var primaryError: Exception? = null
        if (isOfficialChannel) {
            try {
                return latestFromGitCodeApi()
            } catch (e: Exception) {
                primaryError = e
            }
        }
        try {
            return latestFromApi()
        } catch (githubApiError: Exception) {
            val base = "${latestManifestUrl}?cachebust=${System.currentTimeMillis()}"
            try {
                return tryMirrors(base, 12) { response, _ ->
                    val bytes = response.body?.byteStream()?.use { input ->
                        val out = ByteArrayOutputStream(); val buffer = ByteArray(4096)
                        while (true) {
                            val n = input.read(buffer); if (n < 0) break
                            if (out.size() + n > 65536) throw IOException("更新信息过大")
                            out.write(buffer, 0, n)
                        }
                        out.toByteArray()
                    } ?: throw IOException("更新信息为空")
                    try { parseManifest(bytes.toString(Charsets.UTF_8)) }
                    catch (_: Exception) { throw IOException("Raw 更新信息格式不正确") }
                }
            } catch (rawError: Exception) {
                val primary = primaryError?.message?.let { "GitCode：$it；" } ?: ""
                throw IOException("更新检查失败（主源及 GitHub 备用源均不可用）：${primary}${githubApiError.message ?: "GitHub API异常"}；${rawError.message ?: "Raw异常"}", rawError)
            }
        }
    }

    fun download(
        update: AppUpdate,
        file: File,
        progress: (Int) -> Unit,
        mirrorIndex: Int? = null,
        sourceIndex: Int? = null,
        speed: (Long) -> Unit = {}
    ) {
        if (UpdateFiles.verify(file, update)) { speed(0L); return }
        // 手动地址选择：1=GitCode，2=GitHub 原始线路，3=GitHub 代理轮换。
        // 测试版不暴露这些选项，sourceIndex 为空时保持原有自动策略。
        val selectedUpdate = when (sourceIndex) {
            1 -> {
                // 当前更新信息可能来自 GitHub/Raw 回退源，不能直接把它的 URL 当成 GitCode 地址。
                // 用户明确选择 GitCode 时，重新读取 GitCode 最新正式版清单，并核对是否为同一构建。
                val gitCodeUpdate = try {
                    latestFromGitCodeApi()
                } catch (e: Exception) {
                    throw IOException("GitCode 下载地址获取失败：${e.message ?: "接口不可用"}", e)
                }
                if (gitCodeUpdate.versionCode != update.versionCode ||
                    gitCodeUpdate.versionName != update.versionName ||
                    gitCodeUpdate.fileName != update.fileName ||
                    gitCodeUpdate.sha256 != update.sha256 ||
                    gitCodeUpdate.size != update.size) {
                    throw IOException("GitCode 与当前待更新版本信息不一致，请重新检查更新")
                }
                gitCodeUpdate
            }
            2, 3 -> {
                // GitHub API 不可用时，继续使用 AppDownload Raw latest.json 获取 GitHub 附件地址。
                // 这与正式版更新检查的 API -> Raw 备用策略保持一致。
                val github = try {
                    latestFromApi()
                } catch (apiError: Exception) {
                    try {
                        val rawUrl = "${latestManifestUrl}?cachebust=${System.currentTimeMillis()}"
                        tryMirrors(rawUrl, 12) { response, _ ->
                            val text = response.body?.string() ?: throw IOException("AppDownload Raw 更新清单为空")
                            if (text.length > 65536) throw IOException("AppDownload Raw 更新清单过大")
                            try { parseManifest(text) }
                            catch (_: Exception) { throw IOException("AppDownload Raw 更新清单格式不正确") }
                        }
                    } catch (rawError: Exception) {
                        throw IOException(
                            "GitHub 下载地址获取失败：API：${apiError.message ?: "不可用"}；Raw：${rawError.message ?: "不可用"}",
                            rawError
                        )
                    }
                }
                if (github.versionCode != update.versionCode ||
                    github.versionName != update.versionName ||
                    github.fileName != update.fileName ||
                    github.sha256 != update.sha256 ||
                    github.size != update.size ||
                    github.minSdk != update.minSdk ||
                    hostOf(github.downloadUrl) != "github.com") {
                    throw IOException("GitHub 与当前待更新版本信息不一致，或清单未提供 GitHub 原始附件地址，拒绝下载")
                }
                github
            }
            else -> update
        }
        // GitCode Release 附件不追加随机参数；GitHub 线路保留原有 cachebust 策略。
        val selectedHost = hostOf(selectedUpdate.downloadUrl)
        val isGitCodeDownload = selectedHost == "gitcode.com" || selectedHost.endsWith(".gitcode.com")
        val base = if (isGitCodeDownload) selectedUpdate.downloadUrl
                   else "${selectedUpdate.downloadUrl}?cachebust=${System.currentTimeMillis()}"
        val errors = ArrayList<String>()
        val mirrors = when (sourceIndex) {
            1 -> listOf<(String) -> String>({ it })
            2 -> listOf<(String) -> String>({ it })
            3 -> {
                val proxies = AppUpdate.MIRRORS.drop(1)
                val index = (mirrorIndex ?: 0).mod(proxies.size)
                listOf(proxies[index])
            }
            else -> when {
                isGitCodeDownload -> listOf<(String) -> String>({ it })
                mirrorIndex == null -> AppUpdate.MIRRORS
                else -> listOf(AppUpdate.MIRRORS.getOrNull(mirrorIndex) ?: throw IOException("代理线路编号无效"))
            }
        }
        for (wrap in mirrors) {
            val url = wrap(base)
            var response: okhttp3.Response? = null
            try {
                response = http.newCall(request(url)).execute()
                if (!response.isSuccessful) { errors += "HTTP${response.code}@${hostOf(url)}"; continue }
                val body = response.body
                if (body == null) { errors += "空响应@${hostOf(url)}"; continue }
                if (body.contentLength() >= 0 && body.contentLength() != update.size) { errors += "长度不匹配@${hostOf(url)}"; continue }
                body.byteStream().use { UpdateFiles.receive(it, file, selectedUpdate, progress, speed) }
                return
            } catch (e: IOException) {
                errors += "${e.message?.take(50) ?: "?"}@${hostOf(url)}"
                file.delete()
            } finally {
                response?.close()
            }
        }
        // GitCode 附件下载失败时，使用 AppDownload Raw 清单作为最后备用；
        // 只有版本号、文件名和 SHA256 完全一致时才允许切换，避免把不同构建的 APK 混装。
        if (sourceIndex == null && hostOf(base) == "gitcode.com") {
            try {
                val fallback = try {
                    latestFromApi()
                } catch (githubApiError: Exception) {
                    val fallbackManifest = "${latestManifestUrl}?cachebust=${System.currentTimeMillis()}"
                    tryMirrors(fallbackManifest, 12) { response, _ ->
                        val text = response.body?.string() ?: throw IOException("AppDownload备用清单为空")
                        if (text.length > 65536) throw IOException("AppDownload备用清单过大")
                        parseManifest(text)
                    }
                }
                if (fallback.versionCode != selectedUpdate.versionCode ||
                    fallback.fileName != selectedUpdate.fileName ||
                    fallback.sha256 != selectedUpdate.sha256 ||
                    fallback.size != selectedUpdate.size) {
                    throw IOException("GitCode与AppDownload备用版本信息不一致，拒绝切换不同安装包")
                }
                val fallbackBase = "${fallback.downloadUrl}?cachebust=${System.currentTimeMillis()}"
                val fallbackErrors = ArrayList<String>()
                val fallbackMirrors = if (mirrorIndex == null) AppUpdate.MIRRORS else listOf(
                    AppUpdate.MIRRORS.getOrNull(mirrorIndex) ?: throw IOException("备用代理线路编号无效")
                )
                for (wrap in fallbackMirrors) {
                    val url = wrap(fallbackBase)
                    var response: okhttp3.Response? = null
                    try {
                        response = http.newCall(request(url)).execute()
                        if (!response.isSuccessful) { fallbackErrors += "HTTP${response.code}@${hostOf(url)}"; continue }
                        val body = response.body
                        if (body == null) { fallbackErrors += "空响应@${hostOf(url)}"; continue }
                        if (body.contentLength() >= 0 && body.contentLength() != update.size) {
                            fallbackErrors += "长度不匹配@${hostOf(url)}"; continue
                        }
                        body.byteStream().use { UpdateFiles.receive(it, file, selectedUpdate, progress, speed) }
                        return
                    } catch (e: IOException) {
                        fallbackErrors += "${e.message?.take(50) ?: "?"}@${hostOf(url)}"
                        file.delete()
                    } finally {
                        response?.close()
                    }
                }
                throw IOException("GitCode与AppDownload备用下载均失败：${fallbackErrors.joinToString("; ")}")
            } catch (fallbackError: Exception) {
                errors += "AppDownload备用：${fallbackError.message ?: "不可用"}"
            }
        }
        throw IOException("安装包下载失败：${errors.joinToString("; ")}")
    }

    fun cancel() = http.dispatcher.cancelAll()
    override fun close() { cancel(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
}
