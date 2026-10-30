package com.clearview.player

import android.app.Application
import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Records any crash (on any thread) to a file so MainActivity can show it on the next launch. */
class ClearViewApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(this, "uncaught on '${thread.name}'", error) }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        lateinit var instance: ClearViewApp
            private set

        fun crashFile(ctx: Context) = File(ctx.filesDir, "crash.txt")

        /** Appends a stack trace (keeps the last ~20 KB). Safe to call from anywhere. */
        fun record(ctx: Context, where: String, error: Throwable) {
            val sw = StringWriter()
            error.printStackTrace(PrintWriter(sw))
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val f = crashFile(ctx)
            val old = if (f.exists()) f.readText() else ""
            f.writeText((old + "[$stamp] $where\n$sw\n").takeLast(20_000))
        }
    }
}
