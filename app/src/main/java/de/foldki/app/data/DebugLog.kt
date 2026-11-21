package de.foldki.app.data

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Error report: what the AI got and answered, which model answered, timings and errors (also crashes).
 * Stays on the phone in a small file; only leaves the phone when the user taps "Fehlerbericht teilen".
 * The Claude API key is never written.
 */
object DebugLog {
    private const val MAX_BYTES = 300_000L
    private var file: File? = null
    private val time = SimpleDateFormat("dd.MM. HH:mm:ss", Locale.GERMANY)

    fun init(context: Context) {
        if (file != null) return // only once (activity can be recreated)
        file = File(context.filesDir, "debug.log")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { write("ABSTURZ in ${t.name}: ${e.stackTraceToString().take(4000)}") }
            previous?.uncaughtException(t, e)
        }
        runCatching { logExitReasons(context) }
    }

    /**
     * Why the app was closed last time (Android 11+): native crash (e.g. LiteRT-LM aborted), out of memory,
     * killed by the system, ANR … Native crashes cannot be caught while they happen, so they are read at the next start.
     */
    private fun logExitReasons(context: Context) {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val prefs = context.getSharedPreferences("debug_log", Context.MODE_PRIVATE)
        val seen = prefs.getLong("last_exit", 0L)
        val infos = am.getHistoricalProcessExitReasons(context.packageName, 0, 5).filter { it.timestamp > seen }
        for (i in infos.sortedBy { it.timestamp }) {
            val why = when (i.reason) {
                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVER ABSTURZ (z. B. KI-Modell/LiteRT-LM)"
                android.app.ApplicationExitInfo.REASON_CRASH -> "Absturz (Java/Kotlin)"
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "vom System beendet: zu wenig Arbeitsspeicher"
                android.app.ApplicationExitInfo.REASON_ANR -> "App reagierte nicht (ANR)"
                android.app.ApplicationExitInfo.REASON_SIGNALED -> "vom System beendet (Signal ${i.status})"
                android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "vom System beendet: zu viel Rechenlast"
                android.app.ApplicationExitInfo.REASON_USER_REQUESTED, android.app.ApplicationExitInfo.REASON_USER_STOPPED -> null
                android.app.ApplicationExitInfo.REASON_EXIT_SELF -> null
                else -> "beendet (Grund ${i.reason})"
            } ?: continue
            write(
                "LETZTES ENDE (${time.format(Date(i.timestamp))}): $why" +
                    (i.description?.let { " · $it" } ?: "") +
                    " · Speicher: ${i.pss / 1024} MB (PSS), ${i.rss / 1024} MB (RSS)" +
                    " · App war " + if (i.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) "im Vordergrund" else "im Hintergrund (${i.importance})",
            )
        }
        infos.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong("last_exit", it).apply() }
    }

    @Synchronized
    fun write(text: String) {
        val f = file ?: return
        runCatching {
            if (f.length() > MAX_BYTES) {
                // keep the newer half
                val keep = f.readText().takeLast((MAX_BYTES / 2).toInt())
                f.writeText(keep)
            }
            f.appendText("[${time.format(Date())}] $text\n")
        }
    }

    /** One chat request: model, instruction, question, answer, timings. */
    fun request(
        engine: String, system: String, historyCount: Int, question: String, answer: String,
        firstTokenMs: Long?, totalMs: Long, error: String? = null,
    ) = write(buildString {
        append("ANFRAGE · Modell: ").append(engine)
        append(" · Verlauf: ").append(historyCount).append(" Nachrichten")
        append(" · erstes Wort: ").append(firstTokenMs?.let { "${it} ms" } ?: "-")
        append(" · gesamt: ").append(totalMs).append(" ms\n")
        append("  Anweisung (").append(system.length).append(" Zeichen): ").append(system.take(1500).replace("\n", " ⏎ ")).append("\n")
        append("  Frage: ").append(question.take(1000).replace("\n", " ⏎ ")).append("\n")
        append("  Antwort: ").append(answer.take(1500).replace("\n", " ⏎ "))
        if (error != null) append("\n  FEHLER: ").append(error)
    })

    fun clear() { runCatching { file?.writeText("") } }

    /** The whole report as text (header with version and device + all entries). */
    fun text(appVersion: String): String = buildString {
        append("Fold KI ").append(appVersion).append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
        append(" · Android ").append(Build.VERSION.RELEASE).append("\n\n")
        append(runCatching { file?.readText() }.getOrNull().orEmpty().ifBlank { "(noch keine Einträge)" })
    }

    /** Copies the report to the clipboard, so it can simply be pasted into a chat. */
    fun copy(context: Context, appVersion: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Fold KI Fehlerbericht", text(appVersion)))
    }

    /** Opens Android's share sheet with the report as a text file. */
    fun share(context: Context, appVersion: String) {
        val dir = File(File(context.cacheDir, "pdf"), "report").apply { mkdirs() }
        val out = File(dir, "FoldKI-Fehlerbericht.txt")
        out.writeText(text(appVersion))
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", out)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Fold KI Fehlerbericht")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Fehlerbericht teilen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
