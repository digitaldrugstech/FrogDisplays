package com.dreamdisplays.managers

import com.dreamdisplays.Config
import com.dreamdisplays.utils.YouTubeUtils
import me.inotsleep.utils.logging.LoggingManager
import org.jspecify.annotations.NullMarked
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@NullMarked
class StreamResolver(private val config: Config.YoutubeSection) {

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    @Volatile
    private var resolvedBinary: String? = null

    fun resolve(videoUrl: String): String? {
        val videoId = YouTubeUtils.extractVideoIdFromUri(videoUrl) ?: return null

        val cached = cache[videoId]
        if (cached != null && System.currentTimeMillis() < cached.expiryMs) {
            return cached.json
        }

        return runCatching {
            val binary = resolveBinary()
            fetchFormats(binary, videoUrl)
        }.onFailure { error ->
            LoggingManager.warn("yt-dlp: failed to resolve $videoId: ${error.message}")
        }.getOrNull()?.also { json ->
            cache[videoId] = CacheEntry(json, System.currentTimeMillis() + config.cacheTtlMs)
        }
    }

    fun invalidate(videoUrl: String) {
        val videoId = YouTubeUtils.extractVideoIdFromUri(videoUrl) ?: return
        cache.remove(videoId)
    }

    fun shutdown() {
        cache.clear()
    }

    private fun fetchFormats(binary: String, videoUrl: String): String {
        val command = mutableListOf(binary, "-J", "--no-playlist", "--no-warnings")

        if (config.cookiesFile.isNotEmpty()) {
            command.addAll(listOf("--cookies", config.cookiesFile))
        }

        command.add(videoUrl)

        val process = ProcessBuilder(command)
            .redirectErrorStream(false)
            .start()

        val stdout = StringBuilder()
        BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8)).use { reader ->
            val buf = CharArray(8192)
            var n: Int
            while (reader.read(buf).also { n = it } != -1) stdout.append(buf, 0, n)
        }

        val stderr = StringBuilder()
        BufferedReader(InputStreamReader(process.errorStream, StandardCharsets.UTF_8)).use { reader ->
            val buf = CharArray(8192)
            var n: Int
            while (reader.read(buf).also { n = it } != -1) stderr.append(buf, 0, n)
        }

        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("yt-dlp timed out for $videoUrl")
        }

        if (process.exitValue() != 0) {
            error("yt-dlp exited ${process.exitValue()}: ${stderr.toString().trim()}")
        }

        // Return the raw yt-dlp JSON — client parses the "formats" array
        return stdout.toString()
    }

    @Synchronized
    private fun resolveBinary(): String {
        resolvedBinary?.let { return it }

        val candidates = mutableListOf<String>()

        if (config.ytdlpPath.isNotEmpty()) {
            candidates.add(config.ytdlpPath)
        }

        val bundled = Path.of("plugins", "DreamDisplays", "yt-dlp", bundledBinaryName())
        candidates.add(bundled.toString())
        candidates.addAll(SYSTEM_PATHS)

        for (candidate in candidates) {
            if (canExecute(candidate)) {
                LoggingManager.log("yt-dlp: using $candidate")
                resolvedBinary = candidate
                return candidate
            }
        }

        LoggingManager.log("yt-dlp: not found, downloading...")
        val downloaded = downloadBundled(bundled)
        resolvedBinary = downloaded
        return downloaded
    }

    private fun downloadBundled(target: Path): String {
        Files.createDirectories(target.parent)
        val tmp = target.resolveSibling("${target.fileName}.part")
        val assetName = downloadAssetName()
        val url = "$DOWNLOAD_BASE$assetName"
        LoggingManager.log("yt-dlp: downloading from $url")

        val conn = URI.create(url).toURL().openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 120_000
        conn.setRequestProperty("User-Agent", "DreamDisplays-yt-dlp-bootstrap")
        try {
            conn.inputStream.use { input -> Files.copy(input, tmp, StandardCopyOption.REPLACE_EXISTING) }
        } finally {
            conn.disconnect()
        }

        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)

        val os = System.getProperty("os.name", "").lowercase(Locale.ENGLISH)
        if (!os.contains("win")) {
            runCatching {
                Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-xr-x"))
            }.onFailure {
                target.toFile().setExecutable(true, false)
            }
        }

        val path = target.toString()
        if (!canExecute(path)) {
            error("Downloaded yt-dlp at $path is not executable")
        }
        LoggingManager.log("yt-dlp: ready at $path")
        return path
    }

    companion object {
        private const val DOWNLOAD_BASE = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/"
        private val SYSTEM_PATHS = listOf(
            "yt-dlp",
            "/usr/local/bin/yt-dlp",
            "/usr/bin/yt-dlp",
        )

        private fun bundledBinaryName(): String {
            val os = System.getProperty("os.name", "").lowercase(Locale.ENGLISH)
            return if (os.contains("win")) "yt-dlp.exe" else "yt-dlp"
        }

        private fun downloadAssetName(): String {
            val os = System.getProperty("os.name", "").lowercase(Locale.ENGLISH)
            val arch = System.getProperty("os.arch", "").lowercase(Locale.ENGLISH)
            return when {
                os.contains("win") -> "yt-dlp.exe"
                os.contains("mac") -> "yt-dlp_macos"
                arch.contains("aarch64") || arch.contains("arm64") -> "yt-dlp_linux_aarch64"
                arch.contains("arm") -> "yt-dlp_linux_armv7l"
                else -> "yt-dlp_linux"
            }
        }

        private fun canExecute(path: String): Boolean {
            return runCatching {
                val f = File(path)
                if (f.isAbsolute || path.contains(File.separator)) {
                    if (!f.isFile || !f.canExecute()) return false
                }
                val p = ProcessBuilder(path, "--version")
                    .redirectErrorStream(true)
                    .start()
                p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0
            }.getOrDefault(false)
        }
    }

    private data class CacheEntry(val json: String, val expiryMs: Long)
}
