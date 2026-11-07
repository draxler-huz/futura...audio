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

    /** Directory handed to the native crash handler; it writes FILE here. */
    private const val NATIVE_DIR = "native-crash"

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
        nativeReportFile(context).delete()
    }

    /** Directory the native crash handler writes into. */
    fun nativeCrashDir(context: Context): File = File(context.filesDir, NATIVE_DIR)

    /** Report written by the native crash handler, or null if none. */
    fun nativeReport(context: Context): String? =
        nativeReportFile(context).takeIf { it.exists() }?.readText()

    private fun nativeReportFile(context: Context): File = File(nativeCrashDir(context), FILE)
}
