// app/src/main/java/com/futura/data/extraction/ExtractionFileWatcher.kt
package com.futura.data.extraction

import android.os.FileObserver
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Watches directories and emits file-system change events as a flow.
 *
 * Feature 4. Wraps [FileObserver] (which is per-directory on every
 * Android API level we support) in a manager that owns one observer per
 * watched directory, throttles duplicate events (a single save triggers
 * both MODIFY and CLOSE_WRITE), and emits debounced events on a
 * `SharedFlow` that the extraction ViewModel collects to trigger
 * incremental re-scans.
 *
 * Event semantics:
 *   • [FileChangeEvent.Created]      — a new audio file appeared
 *   • [FileChangeEvent.Modified]     — an existing audio file changed
 *   • [FileChangeEvent.Deleted]      — an existing audio file was removed
 *   • [FileChangeEvent.MovedFrom]    — file removed from this directory
 *   • [FileChangeEvent.MovedTo]      — file appeared in this directory
 *
 * Non-audio extensions are filtered at the observer level — the flow
 * never emits for `.txt`, `.jpg`, etc.
 *
 * Every subfolder that exists when [start] runs gets its own observer.
 * Folders created later are not watched until the next [start]; a full
 * re-scan picks them up.
 *
 * Rule 6: every observer start/stop wrapped.
 * Rule 7: [mutex] guards [observers] and [start/stop] so concurrent
 * calls don't leak observers.
 */
@Singleton
class ExtractionFileWatcher @Inject constructor() {

    private val tag = "ExtractionFileWatcher"
    private val mutex = Mutex()
    private val observers = ConcurrentHashMap<String, FileObserver>()

    // Debounce: key = "path|event" → last emitted ms. Suppresses the
    // MODIFY + CLOSE_WRITE + MOVE_SELF trio that fires on every save.
    private val recentEvents = ConcurrentHashMap<String, Long>()

    sealed interface FileChangeEvent {
        val path: String
        data class Created(override val path: String) : FileChangeEvent
        data class Modified(override val path: String) : FileChangeEvent
        data class Deleted(override val path: String) : FileChangeEvent
        data class MovedFrom(override val path: String) : FileChangeEvent
        data class MovedTo(override val path: String) : FileChangeEvent
    }

    private val _events = MutableSharedFlow<FileChangeEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<FileChangeEvent> = _events.asSharedFlow()

    /**
     * Start watching [directories]. Idempotent — directories already
     * watched are not re-watched.
     */
    suspend fun start(directories: List<String>, supportedExtensions: Set<String>) =
        mutex.withLock {
            for (dir in directories) {
                try {
                    if (observers.containsKey(dir)) continue
                    val dirFile = File(dir)
                    if (!dirFile.exists() || !dirFile.isDirectory) {
                        Log.w(tag, "Cannot watch non-directory: $dir")
                        continue
                    }
                    // The root and every visible subfolder under it.
                    val tree = dirFile.walkTopDown()
                        .onEnter { it == dirFile || !it.name.startsWith(".") }
                        .filter { it.isDirectory }
                        .map { it.absolutePath }
                        .toList()
                    for (sub in tree) {
                        if (observers.containsKey(sub)) continue
                        observers[sub] = watch(sub, supportedExtensions)
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Failed to watch $dir", e)
                }
            }
        }

    /** Creates and starts one observer for [dir]. Events are filtered to [supportedExtensions]. */
    private fun watch(dir: String, supportedExtensions: Set<String>): FileObserver {
        val observer = object : FileObserver(
            dir,
            CREATE or MODIFY or DELETE or MOVED_FROM or MOVED_TO
        ) {
            override fun onEvent(event: Int, path: String?) {
                try {
                    path ?: return
                    val ext = path.substringAfterLast('.', "")
                    if (ext.lowercase() !in supportedExtensions) return
                    val fullPath = File(dir, path).absolutePath
                    dispatch(event, fullPath)
                } catch (e: Exception) {
                    Log.w(tag, "onEvent failed for $path", e)
                }
            }
        }
        observer.startWatching()
        Log.i(tag, "Watching $dir")
        return observer
    }

    /** Stop watching [directory], if currently watched. */
    suspend fun stop(directory: String) = mutex.withLock {
        try {
            observers.remove(directory)?.stopWatching()
            Log.i(tag, "Stopped watching $directory")
        } catch (e: Exception) {
            Log.w(tag, "stop($directory) failed", e)
        }
    }

    /** Stop all observers. */
    suspend fun stopAll() = mutex.withLock {
        for ((dir, observer) in observers) {
            try { observer.stopWatching() } catch (e: Exception) {
                Log.w(tag, "stopWatching failed for $dir", e)
            }
        }
        observers.clear()
        recentEvents.clear()
    }

    private fun dispatch(eventMask: Int, fullPath: String) {
        // The FileObserver constants are only in scope inside the observer subclass.
        val event = when {
            eventMask and FileObserver.CREATE != 0 -> FileChangeEvent.Created(fullPath)
            eventMask and FileObserver.MOVED_TO != 0 -> FileChangeEvent.MovedTo(fullPath)
            eventMask and FileObserver.MOVED_FROM != 0 -> FileChangeEvent.MovedFrom(fullPath)
            eventMask and FileObserver.DELETE != 0 -> FileChangeEvent.Deleted(fullPath)
            eventMask and FileObserver.MODIFY != 0 -> FileChangeEvent.Modified(fullPath)
            else -> return
        }
        val key = "${event.javaClass.simpleName}|$fullPath"
        val now = System.currentTimeMillis()
        val last = recentEvents[key] ?: 0L
        if (now - last < DEBOUNCE_MS) return
        recentEvents[key] = now
        if (recentEvents.size > MAX_RECENT_EVENTS) {
            recentEvents.entries.removeIf { now - it.value >= DEBOUNCE_MS }
        }
        _events.tryEmit(event)
    }

    private companion object {
        const val DEBOUNCE_MS = 500L
        const val MAX_RECENT_EVENTS = 512
    }
}