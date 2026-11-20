package com.shilapi.xcertplay

import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded, private diagnostics. Disk writes & rotation run asynchronously off the UI thread. */
internal class SessionLogFile(val file: File) : Closeable {
    private val lock = Any()
    private val closed = AtomicBoolean(false)
    private val ioExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "leapcarplay-log-io").apply { isDaemon = true }
    }

    fun reset(header: String) {
        if (closed.get()) return
        runCatching {
            ioExecutor.execute {
                synchronized(lock) {
                    if (closed.get()) return@execute
                    runCatching {
                        file.parentFile?.mkdirs()
                        rotate()
                        file.writeText("")
                        appendLocked(header)
                    }
                }
            }
        }
    }

    fun append(line: String) {
        if (closed.get()) return
        val safe = DiagnosticRedactor.redact(line) ?: return
        runCatching {
            ioExecutor.execute {
                synchronized(lock) {
                    if (closed.get()) return@execute
                    appendLocked(safe)
                }
            }
        }
    }

    private fun appendLocked(safeLine: String) {
        runCatching {
            if (file.length() > MAX_BYTES) {
                rotate()
                file.writeText("")
            }
            file.appendText(safeLine + "\n")
        }
    }

    private fun rotate() {
        if (!file.exists() || file.length() == 0L) return
        for (index in ARCHIVE_NAMES.lastIndex downTo 1) {
            val source = File(file.parentFile, ARCHIVE_NAMES[index - 1])
            val destination = File(file.parentFile, ARCHIVE_NAMES[index])
            if (source.exists()) source.copyTo(destination, overwrite = true)
        }
        file.copyTo(File(file.parentFile, ARCHIVE_NAMES.first()), overwrite = true)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        ioExecutor.shutdown()
        runCatching { ioExecutor.awaitTermination(250L, TimeUnit.MILLISECONDS) }
    }
    companion object {
        const val MAX_BYTES = 512 * 1024L
        private val ARCHIVE_NAMES = listOf("previous.log") + (2..7).map { "previous-$it.log" }
        val REPORT_NAMES = ARCHIVE_NAMES.reversed() + "leapcarplay.log"
    }
}
