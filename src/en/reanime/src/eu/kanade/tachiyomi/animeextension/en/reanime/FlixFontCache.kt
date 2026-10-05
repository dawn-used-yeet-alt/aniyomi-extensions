package eu.kanade.tachiyomi.animeextension.en.reanime

import android.util.Log
import eu.kanade.tachiyomi.animeextension.en.reanime.FlixProxyServer.Companion.flixCloudUrl
import keiyoushi.utils.applicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLDecoder

/**
 * Pre-seeds the ASS font attachments for HLS playback.
 *
 * The MKV downloads embed their fonts as attachments, so libass resolves the
 * \pos()-anchored karaoke correctly. The HLS path serves the same bare .ass
 * text with no fonts, so libass falls back to system fonts and the highlight
 * sweeps land off-glyph.
 *
 * The embed page carries the font list in `extracted_fonts[]`, served from
 * `/fonts/<fileId>/<name>` (requires `Referer: <flixCloudUrl>/`).
 *
 * This runs inside [ReAnime.extractFromServer], i.e. before the user picks a
 * video and therefore before mpv starts. Fonts are written to Animiru's
 * internal `filesDir/mpv/fonts` directory, which mpv uses as its default
 * `--sub-fonts-dir` (`~~/fonts`), so no per-video mpv options are needed.
 * A canonical copy is kept in `cacheDir/reanime-fonts/<fileId>/` because
 * Animiru wipes and re-copies the internal fonts dir on every
 * `MainActivity.onResume()` — on the next episode open the files are simply
 * copied back instead of re-downloaded.
 *
 * Everything is best-effort: any failure is logged and playback continues
 * with system-font fallback, exactly as before this change.
 */
object FlixFontCache {

    private const val TAG = "ReAnimeFonts"

    private const val MAX_FONT_BYTES = 25L * 1024 * 1024

    /** /fonts/<fileId>/<name> — fileId is a UUID, name is URL-encoded. */
    private val FONTS_URL_REGEX = Regex("""/fonts/([0-9a-fA-F-]{36})/([^"'\s)]+)""")

    /** "extracted_fonts": [ ... ] block in the embed HTML/JSON. */
    private val EXTRACTED_FONTS_BLOCK_REGEX = Regex(
        """"extracted_fonts"\s*:\s*\[(.*?)]""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    /** Bare font file names inside the extracted_fonts block. */
    private val FONT_NAME_REGEX = Regex(
        """"([^"']+?\.(?:ttf|otf|ttc))"""",
        RegexOption.IGNORE_CASE,
    )

    /** Fallback fileId source: the subtitle URLs carry the same fileId. */
    private val SUBTITLE_FILE_ID_REGEX = Regex("""/subtitles/([0-9a-fA-F-]{36})/""")

    suspend fun ensureFonts(
        client: OkHttpClient,
        fontHeaders: Headers,
        html: String,
        embedJson: String,
        subtitleUrls: List<String>,
    ) {
        val candidates = collectCandidates(html, embedJson, subtitleUrls)
        if (candidates.isEmpty()) {
            Log.i(TAG, "No extracted fonts found, skipping")
            return
        }

        val mpvFontsDir: File
        val cacheRoot: File
        try {
            val appContext = applicationContext
            mpvFontsDir = File(appContext.filesDir, "mpv/fonts")
            cacheRoot = File(appContext.cacheDir, "reanime-fonts")
            mpvFontsDir.mkdirs()
            cacheRoot.mkdirs()
        } catch (e: Exception) {
            Log.w(TAG, "Cannot resolve app dirs: $e")
            return
        }

        supervisorScope {
            candidates.map { (fileId, name) ->
                async {
                    try {
                        ensureOneFont(client, fontHeaders, cacheRoot, mpvFontsDir, fileId, name)
                    } catch (e: Exception) {
                        Log.w(TAG, "Font failed: $name: $e")
                    }
                }
            }.forEach { it.await() }
        }
    }

    private fun collectCandidates(
        html: String,
        embedJson: String,
        subtitleUrls: List<String>,
    ): List<Pair<String, String>> {
        val found = linkedSetOf<Pair<String, String>>()

        // Full /fonts/<fileId>/<name> URLs carry both pieces.
        (sequenceOf(html, embedJson)).forEach { source ->
            FONTS_URL_REGEX.findAll(source).forEach { match ->
                val fileId = match.groupValues[1]
                val name = runCatching {
                    URLDecoder.decode(match.groupValues[2].trim(), "UTF-8")
                }.getOrNull()?.trim().orEmpty()
                sanitizeFontName(name)?.let { found.add(fileId to it) }
            }
        }

        // Bare file names in extracted_fonts[] share the subtitle fileId.
        val fallbackFileId = found.firstOrNull()?.first
            ?: sequenceOf(html, embedJson)
                .flatMap { SUBTITLE_FILE_ID_REGEX.findAll(it) }
                .plus(subtitleUrls.asSequence().flatMap { SUBTITLE_FILE_ID_REGEX.findAll(it) })
                .firstOrNull()?.groupValues?.get(1)
        if (fallbackFileId != null) {
            sequenceOf(html, embedJson).forEach { source ->
                EXTRACTED_FONTS_BLOCK_REGEX.findAll(source).forEach { block ->
                    FONT_NAME_REGEX.findAll(block.groupValues[1]).forEach { match ->
                        sanitizeFontName(match.groupValues[1].trim())?.let {
                            found.add(fallbackFileId to it)
                        }
                    }
                }
            }
        }

        return found.toList()
    }

    private fun sanitizeFontName(raw: String): String? {
        // Never allow path traversal: keep only the final segment.
        val name = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        if (name.isEmpty() || name.length > 128) return null
        if (name == "." || name == ".." || name.contains("..")) return null
        if (!name.endsWith(".ttf", ignoreCase = true) &&
            !name.endsWith(".otf", ignoreCase = true) &&
            !name.endsWith(".ttc", ignoreCase = true)
        ) {
            return null
        }
        return name
    }

    private fun ensureOneFont(
        client: OkHttpClient,
        fontHeaders: Headers,
        cacheRoot: File,
        mpvFontsDir: File,
        fileId: String,
        name: String,
    ) {
        val cached = File(File(cacheRoot, fileId), name)
        if (cached.length() <= 0) {
            downloadFont(client, fontHeaders, fileId, name, cached)
        }
        if (cached.length() <= 0) return

        // The internal dir is wiped on every MainActivity.onResume(), so copy
        // back whenever it is missing or stale. Current episode wins on name
        // clash: its fonts are what mpv is about to need.
        val internal = File(mpvFontsDir, name)
        if (!internal.isFile || internal.length() != cached.length()) {
            cached.copyTo(internal, overwrite = true)
            Log.i(TAG, "Installed font: $name")
        }
    }

    private fun downloadFont(
        client: OkHttpClient,
        fontHeaders: Headers,
        fileId: String,
        name: String,
        target: File,
    ) {
        val url = flixCloudUrl.trimEnd('/') + "/fonts/" + fileId + "/" +
            java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        val request = Request.Builder().url(url).headers(fontHeaders).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "Font HTTP ${response.code}: $name")
                return
            }
            val declared = response.body.contentLength()
            if (declared > MAX_FONT_BYTES) {
                Log.w(TAG, "Font too large ($declared): $name")
                return
            }
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.tmp")
            try {
                response.body.byteStream().use { input ->
                    tmp.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n == -1) break
                            total += n
                            if (total > MAX_FONT_BYTES) {
                                throw IllegalStateException("Font too large: $name")
                            }
                            output.write(buffer, 0, n)
                        }
                    }
                }
                if (tmp.length() <= 0) return
                tmp.renameTo(target)
                Log.i(TAG, "Downloaded font: $name (${target.length()} bytes)")
            } finally {
                tmp.delete()
            }
        }
    }
}
