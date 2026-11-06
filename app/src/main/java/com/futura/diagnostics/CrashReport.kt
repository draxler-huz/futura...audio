package com.futura.diagnostics

import android.content.Context
import android.util.Log
import java.io.File
import java.util.Date

/**
 * Saves the stack trace of an uncaught crash to app storage, so the next
 * launch can show it. Nothing is sent off the device.
 */
object CrashReport {

    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val text = buildString {
                    append("Time: ").append(Date()).append('\n')
                    append("Thread: ").append(thread.name).append('\n')
                    append(Log.getStackTraceString(error))
                }
                File(appContext.filesDir, FILE).writeText(text)
            } catch (e: Exception) {
                Log.w("CrashReport", "Could not save the crash report", e)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun pending(context: Context): String? =
        File(context.filesDir, FILE).takeIf { it.exists() }?.readText()

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }
}
