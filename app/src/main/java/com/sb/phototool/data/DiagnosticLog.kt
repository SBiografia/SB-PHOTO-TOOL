package com.sb.phototool.data

import android.app.Application
import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Keeps a small, app-private diagnostic trail so a crash can be shared after
 * the app starts again. It intentionally excludes source photo URIs and user
 * content from every entry.
 */
object DiagnosticLog {
    private const val PREFERENCES_NAME = "diagnostic_log"
    private const val ENTRIES_KEY = "entries"
    private const val ENTRY_SEPARATOR = "\n\n---\n\n"
    private const val MAX_ENTRIES = 80
    private const val MAX_ENTRY_CHARACTERS = 2_400
    private val lock = Any()

    fun install(application: Application) {
        record(application, "app", "started; androidSdk=${Build.VERSION.SDK_INT}")
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            recordException(application, "fatal/${thread.name}", throwable, commit = true)
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    fun record(context: Context, category: String, message: String) {
        append(context, category, sanitizeDiagnosticText(message), commit = false)
    }

    fun recordException(context: Context, category: String, throwable: Throwable, commit: Boolean = false) {
        append(context, category, exceptionSummary(throwable), commit)
    }

    fun snapshot(context: Context): String {
        val entries = preferences(context).getString(ENTRIES_KEY, "").orEmpty()
        return buildString {
            appendLine("SB PHOTO TOOL 진단 로그")
            appendLine("생성: ${timestamp()}")
            appendLine("기기: ${sanitizeDiagnosticText(Build.MANUFACTURER)} ${sanitizeDiagnosticText(Build.MODEL)}")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine()
            append(if (entries.isBlank()) "아직 기록된 오류가 없습니다." else entries)
        }
    }

    fun entryCount(context: Context): Int = preferences(context)
        .getString(ENTRIES_KEY, "")
        .orEmpty()
        .split(ENTRY_SEPARATOR)
        .count(String::isNotBlank)

    fun clear(context: Context) {
        preferences(context).edit().remove(ENTRIES_KEY).apply()
    }

    private fun append(context: Context, category: String, detail: String, commit: Boolean) {
        runCatching {
            synchronized(lock) {
                val preferences = preferences(context)
                val currentEntries = preferences.getString(ENTRIES_KEY, "")
                    .orEmpty()
                    .split(ENTRY_SEPARATOR)
                    .filter(String::isNotBlank)
                val entry = "[${timestamp()}] [$category]\n${detail.take(MAX_ENTRY_CHARACTERS)}"
                val stored = (currentEntries + entry)
                    .takeLast(MAX_ENTRIES)
                    .joinToString(ENTRY_SEPARATOR)
                val editor = preferences.edit().putString(ENTRIES_KEY, stored)
                if (commit) editor.commit() else editor.apply()
            }
        }
    }

    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun exceptionSummary(throwable: Throwable): String = buildString {
        var current: Throwable? = throwable
        var causeDepth = 0
        while (current != null && causeDepth < 4) {
            if (causeDepth > 0) appendLine("Caused by:")
            append(current::class.java.name)
            current.message?.takeIf(String::isNotBlank)?.let { append(": ").append(sanitizeDiagnosticText(it)) }
            appendLine()
            current.stackTrace.take(40).forEach { frame -> appendLine("  at $frame") }
            current = current.cause
            causeDepth++
        }
    }.take(MAX_ENTRY_CHARACTERS)

    private fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS z", Locale.KOREA).apply {
        timeZone = TimeZone.getTimeZone("Asia/Seoul")
    }.format(Date())
}

internal fun sanitizeDiagnosticText(value: String): String {
    val withoutUris = value.replace(Regex("(?i)(content|file)://\\S+"), "<uri>")
    return withoutUris.replace(Regex("(?i)(api[_-]?key|token|authorization)\\s*[:=]\\s*\\S+")) { match ->
        "${match.groupValues[1]}=<redacted>"
    }
}
