package com.headqlink.unit.companion

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/** Estado en vivo del Companion, para la pantalla y la notificación (mismo proceso). */
object BridgeState {

    enum class Phase { STOPPED, SEARCHING, CONNECTING, CONNECTED, PAIRING }

    data class Snapshot(
        val phase: Phase = Phase.STOPPED,
        val transport: String = "",
        val carName: String = "",
        val detail: String = "",
        val lastRoute: String = "",
        val dataRequestsServed: Int = 0,
        val lastDataHost: String = "",
    )

    @Volatile
    var current = Snapshot()
        private set

    private val listeners = CopyOnWriteArraySet<(Snapshot) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun update(block: (Snapshot) -> Snapshot) {
        current = block(current)
        val s = current
        main.post { listeners.forEach { it(s) } }
    }

    fun addListener(l: (Snapshot) -> Unit) {
        listeners.add(l)
        l(current)
    }

    fun removeListener(l: (Snapshot) -> Unit) {
        listeners.remove(l)
    }
}
