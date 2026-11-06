package com.headqlink.unit.bridge

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Canal de líneas UTF-8 sobre cualquier par de flujos (socket TCP o Bluetooth RFCOMM).
 * Escritura sincronizada: se puede escribir desde varios hilos.
 */
class LineChannel(
    private val input: InputStream,
    private val output: OutputStream,
    private val onClose: () -> Unit = {},
) : Closeable {

    private val writeLock = Any()

    @Volatile
    var isClosed: Boolean = false
        private set

    /** Devuelve la siguiente línea (sin el `\n`), o `null` si el otro lado cerró. */
    @Throws(IOException::class)
    fun readLine(): String? {
        val buf = ByteArrayOutputStream(256)
        while (true) {
            val b = input.read()
            if (b < 0) {
                return if (buf.size() == 0) null else buf.toString("UTF-8")
            }
            if (b == '\n'.code) {
                return buf.toString("UTF-8").trimEnd('\r')
            }
            buf.write(b)
            if (buf.size() > BridgeConstants.MAX_LINE_BYTES) {
                throw IOException("Línea demasiado larga en el puente")
            }
        }
    }

    @Throws(IOException::class)
    fun writeLine(line: String) {
        require(!line.contains('\n')) { "Una línea no puede contener saltos de línea" }
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        synchronized(writeLock) {
            output.write(bytes)
            output.flush()
        }
    }

    fun send(msg: BridgeMessage) = writeLine(msg.encode())

    override fun close() {
        if (isClosed) return
        isClosed = true
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { onClose() }
    }
}
