package eu.kanade.tachiyomi.animeextension.en.reanime

import android.util.Log
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
 * The embed page carries the font list in `extracted_fonts[]` as full URLs on
 * the file's vault host, e.g.
 * `https://vault-95.rundowncdn.top/fonts/<fileId>/<name>.ttf` (the vault host
 * varies per file, so the URLs must be used as-is, never rebuilt on
 * flixcloud.cc).
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

    /**
     * Full font URLs on the file's vault host.
     * Group 1 = whole URL, group 2 = fileId, group 3 = raw (encoded) name.
     */
    private val FONTS_URL_REGEX =
        Regex("""(https://[^"'\s)]+/fonts/([0-9a-fA-F-]{36})/([^"'\s)]+))""")

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

    /** Vault host observed in the same payload (fonts or subtitles). */
    private val VAULT_HOST_REGEX = Regex("""(https://[^"'\s)]+?)/(?:fonts|subtitles)/""")

    /** fileId observed in the same payload (fonts or subtitles). */
    private val FILE_ID_REGEX = Regex("""/(?:fonts|subtitles)/([0-9a-fA-F-]{36})/""")

    private data class FontCandidate(val url: String, val fileId: String, val name: String)

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
            candidates.map { candidate ->
                async {
                    try {
                        ensureOneFont(client, fontHeaders, cacheRoot, mpvFontsDir, candidate)
                    } catch (e: Exception) {
                        Log.w(TAG, "Font failed: ${candidate.name}: $e")
                    }
                }
            }.forEach { it.await() }
        }
    }

    private fun collectCandidates(
        html: String,
        embedJson: String,
        subtitleUrls: List<String>,
    ): List<FontCandidate> {
        val sources = listOf(html, embedJson) + subtitleUrls
        val found = linkedSetOf<FontCandidate>()

        // Full vault-host URLs: use as-is, never rebuild the host.
        sources.forEach { source ->
            FONTS_URL_REGEX.findAll(source).forEach { match ->
                sanitizeFontName(decodeName(match.groupValues[3]))?.let { name ->
                    found.add(FontCandidate(match.groupValues[1], match.groupValues[2], name))
                }
            }
        }

        // Bare file names in extracted_fonts[]: resolve against the vault host
        // and fileId observed in the same payload.
        val vaultHost = sources.asSequence()
            .flatMap { VAULT_HOST_REGEX.findAll(it) }
            .firstOrNull()?.groupValues?.get(1)
        val fallbackFileId = found.firstOrNull()?.fileId
            ?: sources.asSequence()
                .flatMap { FILE_ID_REGEX.findAll(it) }
                .firstOrNull()?.groupValues?.get(1)
        if (vaultHost != null && fallbackFileId != null) {
            listOf(html, embedJson).forEach { source ->
                EXTRACTED_FONTS_BLOCK_REGEX.findAll(source).forEach { block ->
                    FONT_NAME_REGEX.findAll(block.groupValues[1]).forEach { match ->
                        sanitizeFontName(match.groupValues[1].trim())?.let { name ->
                            val url = "$vaultHost/fonts/$fallbackFileId/" +
                                java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
                            found.add(FontCandidate(url, fallbackFileId, name))
                        }
                    }
                }
            }
        }

        return found.toList()
    }

    private fun decodeName(raw: String): String = runCatching {
        URLDecoder.decode(raw.trim(), "UTF-8").trim()
    }.getOrNull().orEmpty()

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
        candidate: FontCandidate,
    ) {
        val cached = File(File(cacheRoot, candidate.fileId), candidate.name)
        if (cached.length() <= 0) {
            downloadFont(client, fontHeaders, candidate.url, candidate.name, cached)
        }
        if (cached.length() <= 0) return

        // The internal dir is wiped on every MainActivity.onResume(), so copy
        // back whenever it is missing or stale. Current episode wins on name
        // clash: its fonts are what mpv is about to need.
        val internal = File(mpvFontsDir, candidate.name)
        if (!internal.isFile || internal.length() != cached.length()) {
            cached.copyTo(internal, overwrite = true)
            Log.i(TAG, "Installed font: ${candidate.name}")
        }
    }

    private fun downloadFont(
        client: OkHttpClient,
        fontHeaders: Headers,
        url: String,
        name: String,
        target: File,
    ) {
        if (!url.startsWith("https://")) {
            Log.w(TAG, "Refusing non-https font URL for $name")
            return
        }
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
