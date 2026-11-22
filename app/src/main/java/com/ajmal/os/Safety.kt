package com.ajmal.os

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

/** Runs [block]; on any failure logs it and returns [fallback]. */
inline fun <T> guard(tag: String, fallback: T, block: () -> T): T =
    try { block() } catch (t: Throwable) { Diag.e(tag, t); fallback }

/** Runs [block]; on any failure logs it and carries on. */
inline fun guardRun(tag: String, block: () -> Unit) {
    try { block() } catch (t: Throwable) { Diag.e(tag, t) }
}

/** Background task whose failure can never take the app down. */
fun Executor.safe(tag: String, block: () -> Unit) {
    execute { try { block() } catch (t: Throwable) { Diag.e(tag, t) } }
}

/**
 * Error log, crash recorder and last-resort protection for a launcher, which must never die:
 *  - every fatal crash is written to diagnostics.log (viewable and shareable in Settings -> Diagnostics);
 *  - small recoverable errors on the UI thread (coming from this app, never from start-up) are logged and
 *    skipped instead of killing the home screen, within a budget so a repeating fault still stops;
 *  - three crashes within two minutes switch on Safe mode (see [Safe]).
 */
object Diag {
    private const val TAG = "AjmalPad"
    private const val MAX_BYTES = 96 * 1024
    private const val KEEP_BYTES = 48 * 1024
    private var file: File? = null
    private var sp: SharedPreferences? = null
    private var versionLine = ""
    private var previous: Thread.UncaughtExceptionHandler? = null
    private val lastSeen = HashMap<String, Long>()
    private val softTimes = java.util.ArrayDeque<Long>()

    fun install(app: Application) {
        try {
            file = File(app.filesDir, "diagnostics.log")
            sp = app.getSharedPreferences("ajmal_diag", Context.MODE_PRIVATE)
            versionLine = try {
                val pi = app.packageManager.getPackageInfo(app.packageName, 0)
                "v${pi.versionName} sdk=${Build.VERSION.SDK_INT} ${Build.MANUFACTURER} ${Build.MODEL}"
            } catch (t: Throwable) { "sdk=${Build.VERSION.SDK_INT}" }
        } catch (t: Throwable) { Log.w(TAG, "diag init", t) }
        previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { th, e ->
            try { fatal(th, e) } catch (t: Throwable) { }
            previous?.uncaughtException(th, e)
        }
        Safe.init(sp)
        installLooperGuard()
    }

    /** A non-fatal problem: logged once in a while per kind, never thrown. */
    fun e(tag: String, t: Throwable) {
        try {
            Log.w(TAG, tag, t)
            val top = t.stackTrace.firstOrNull()
            val key = tag + t.javaClass.name + (top?.toString() ?: "")
            val now = SystemClock.elapsedRealtime()
            synchronized(lastSeen) {
                val last = lastSeen[key]
                if (last != null && now - last < 5000) return
                lastSeen[key] = now
                if (lastSeen.size > 200) lastSeen.clear()
            }
            append("ERROR", tag, Thread.currentThread().name, t)
        } catch (x: Throwable) { }
    }

    private fun fatal(thread: Thread, t: Throwable) {
        append("FATAL", "crash", thread.name, t)
        val prefs = sp ?: return
        val now = System.currentTimeMillis()
        val list = Safe.crashTimes(prefs, now).toMutableList()
        list.add(now)
        prefs.edit()
            .putString("crashes", list.takeLast(6).joinToString(","))
            .putString("last", t.javaClass.simpleName + ": " + (t.message ?: ""))
            .commit()
    }

    @Synchronized
    private fun append(level: String, tag: String, thread: String, t: Throwable) {
        val f = file ?: return
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        var body = Log.getStackTraceString(t)
        if (body.length > 6000) body = body.substring(0, 6000) + "\n\t... (truncated)"
        val entry = "=== $ts $level $tag thread=$thread $versionLine\n$body\n"
        if (f.length() > MAX_BYTES) {
            try { f.writeText(f.readText().takeLast(KEEP_BYTES)) } catch (x: Throwable) { f.delete() }
        }
        f.appendText(entry)
    }

    @Synchronized
    fun readLog(): String = try { file?.takeIf { it.exists() }?.readText() ?: "" } catch (t: Throwable) { "" }

    @Synchronized
    fun clearLog() { try { file?.delete() } catch (t: Throwable) { } }

    // ---------------------------------------------------------------- UI-thread guard

    private fun installLooperGuard() {
        Handler(Looper.getMainLooper()).post {
            while (true) {
                try {
                    Looper.loop()
                    break
                } catch (t: Throwable) {
                    if (!softOk(t)) throw t
                    e("ui", t)
                }
            }
        }
    }

    /** Only small faults from our own code, never during Activity start-up, and only a few per half minute. */
    private fun softOk(t: Throwable): Boolean {
        if (t is OutOfMemoryError || t is StackOverflowError) return false
        var root: Throwable = t
        while (root.cause != null && root.cause !== root) root = root.cause!!
        val frames = root.stackTrace.toList() + t.stackTrace.toList()
        val ours = frames.filter { it.className.startsWith("com.ajmal.os") }
        if (ours.isEmpty()) return false
        if (ours.any { it.methodName == "onCreate" || it.methodName == "attachBaseContext" }) return false
        val now = SystemClock.elapsedRealtime()
        synchronized(softTimes) {
            while (softTimes.isNotEmpty() && now - softTimes.first() > 30_000) softTimes.removeFirst()
            if (softTimes.size >= 8) return false
            softTimes.addLast(now)
        }
        return true
    }
}

/**
 * Safe mode: after repeated crashes the launcher starts with the risky extras off (wallpaper blur, glass
 * refraction, real widgets, genie animation) so the home screen always comes up. Settings are untouched.
 */
object Safe {
    @Volatile var mode = false
        private set
    /** One-shot message for the home screen. */
    @Volatile var notice: String? = null
    private var sp: SharedPreferences? = null

    fun init(prefs: SharedPreferences?) {
        sp = prefs
        mode = prefs?.getBoolean("safe", false) ?: false
        val recent = if (prefs != null) crashTimes(prefs, System.currentTimeMillis()).size else 0
        if (!mode && recent >= 3) enter("it crashed $recent times in a row")
    }

    fun crashTimes(prefs: SharedPreferences, now: Long): List<Long> =
        (prefs.getString("crashes", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }.filter { now - it < 120_000L }

    fun enter(reason: String) {
        mode = true
        notice = "Safe mode on: $reason. Open Settings > Diagnostics for details."
        sp?.edit()?.putBoolean("safe", true)?.putLong("safe_since", System.currentTimeMillis())?.apply()
    }

    fun exit() {
        mode = false
        notice = null
        sp?.edit()?.putBoolean("safe", false)?.putString("crashes", "")?.apply()
    }

    /** Leaves safe mode on its own after ten stable minutes. */
    fun autoExit() {
        val p = sp ?: return
        if (mode && System.currentTimeMillis() - p.getLong("safe_since", 0L) > 10 * 60_000L) exit()
    }

    fun lastCrash(): String = sp?.getString("last", "") ?: ""
}
