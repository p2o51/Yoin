package com.gpo.yoin.player

import android.content.Context
import android.os.Build
import com.gpo.yoin.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The playback diagnostics log (Settings › Help and about › Export log), iOS's PlayTrace: the last
 * [CAPACITY] lines of playback commands, starts per provider, Spotify start failures, player errors
 * and the local decoder's formats, kept in memory and in cacheDir/playtrace.log so a log survives
 * a relaunch. Nothing leaves the phone unless the user shares the export. Callers log paths,
 * statuses and ids only; [redact] cuts anything shaped like a token or a Subsonic credential,
 * [describe] is how a stream URL may be logged and [maskHosts] is how an error message may be.
 */
object PlayTrace {
    const val CAPACITY = 2000
    private const val FILE_NAME = "playtrace.log"
    private const val EXPORT_DIR = "shared"
    private const val EXPORT_PREFIX = "yoin-log-"

    @Volatile private var ring: PlayTraceRing? = null
    private var executor: ExecutorService? = null

    /** Once, from Application.onCreate: lines logged before this are dropped (and in unit tests). */
    fun install(context: Context) {
        if (ring != null) return
        executor = Executors.newSingleThreadExecutor { r -> Thread(r, "yoin-playtrace").apply { isDaemon = true } }
        ring = PlayTraceRing(CAPACITY, File(context.cacheDir, FILE_NAME))
    }

    fun log(message: String) {
        val ring = ring ?: return
        val now = System.currentTimeMillis()
        executor?.execute { ring.append("${stamp.format(Date(now))} ${redact(message)}") }
    }

    /**
     * The log as cacheDir/shared/yoin-log-<time>.txt (the FileProvider's `shared` path), with the
     * app version and the device on top; earlier exports are deleted. Blocks: call off the main thread.
     */
    fun writeExport(context: Context): File {
        val lines = executor?.submit<List<String>> { ring?.snapshot().orEmpty() }?.get().orEmpty()
        val header = listOf(
            "Yoin ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) ${Build.MANUFACTURER} ${Build.MODEL}",
            "",
        )
        val dir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
        dir.listFiles { f -> f.name.startsWith(EXPORT_PREFIX) }?.forEach(File::delete)
        val name = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return File(dir, "$EXPORT_PREFIX$name.txt").apply {
            writeText((header + lines).joinToString(separator = "\n", postfix = "\n"))
        }
    }

    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private val tokenPattern = Regex("""(access_token=|refresh_token=|code=|Bearer )[^\s&#"]+""")
    private val credentialPattern = Regex("""([?&](?:u|t|s|p|apiKey|token|salt)=)[^\s&#"]+""")
    private val userInfoPattern = Regex("""(://)[^/\s@]+@""")

    /** Anything shaped like a bearer or OAuth token, a Subsonic credential parameter or a URL's user:password is cut. */
    fun redact(s: String): String = s
        .replace(tokenPattern, "$1…")
        .replace(credentialPattern, "$1…")
        .replace(userInfoPattern, "$1…@")

    private val authorityPattern = Regex("""(://)[^/\s?#"]+""")
    private val hostPattern = Regex(
        """\[[0-9a-fA-F:.]+]|\b(?:\d{1,3}\.){3}\d{1,3}\b|\b(?:[a-zA-Z0-9-]+\.)+[a-zA-Z]{2,}\b""",
    )

    /**
     * An error message as it may be logged: a URL's authority, a host name and an IPv4 / IPv6 literal
     * become "…", so a network failure ("Unable to resolve host …", "failed to connect to …") never
     * names the user's server. Dotted names that are not hosts are cut as well.
     */
    fun maskHosts(s: String?): String = s.orEmpty()
        .replace(authorityPattern, "$1…")
        .replace(hostPattern, "…")

    private val safeQuery = setOf("id", "maxBitRate", "format", "timeOffset", "estimateContentLength")

    /**
     * A stream URL as it may be logged (iOS AVAudioOutput.describe): scheme, the endpoint and the
     * song / delivery parameters, never the server's address, the credentials (`u`, `t`, `s`, `p`,
     * `apiKey`) or anything unknown.
     */
    fun describe(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return "<url>"
        val items = uri.rawQuery.orEmpty().split('&')
            .filter { it.substringBefore('=') in safeQuery }
            .joinToString("&")
        val endpoint = uri.path.orEmpty().split('/').lastOrNull { it.isNotEmpty() }.orEmpty()
        return "${uri.scheme.orEmpty()}://…/$endpoint" + if (items.isEmpty()) "" else "?$items"
    }
}

/**
 * [PlayTrace]'s lines: the last [capacity] in memory, appended to [file] as they come, the file cut
 * back to [capacity] lines each time [capacity] more have been written. On first use the previous
 * run's tail is read back so it comes first. Not thread-safe: [PlayTrace] calls it from one thread.
 */
internal class PlayTraceRing(private val capacity: Int, private val file: File?) {
    private val lines = ArrayDeque<String>()
    private var out: FileOutputStream? = null
    private var written = 0
    private var opened = false

    fun append(line: String) {
        open()
        lines.addLast(line)
        while (lines.size > capacity) lines.removeFirst()
        runCatching { out?.write((line + "\n").toByteArray()) }
        written += 1
        if (written >= capacity) compact()
    }

    fun snapshot(): List<String> {
        open()
        return lines.toList()
    }

    private fun open() {
        val file = file
        if (opened || file == null) return
        opened = true
        runCatching {
            if (file.exists()) {
                file.readLines().filter { it.isNotEmpty() }.takeLast(capacity).forEach(lines::addLast)
            }
            out = FileOutputStream(file, true)
        }
    }

    private fun compact() {
        written = 0
        val file = file ?: return
        runCatching {
            out?.close()
            file.writeText(lines.joinToString(separator = "\n", postfix = "\n"))
            out = FileOutputStream(file, true)
        }
    }
}
