package com.shilapi.xcertplay.media

import android.view.MotionEvent
import com.shilapi.xcertplay.airplay.AirPlayContact

/**
 * Converts Android MotionEvents into normalized CarPlay touch contacts while preserving stable
 * 2-slot HID contact IDs (`0` and `1`) across multi-touch pinch/zoom gestures so lifting finger 0
 * does not teleport finger 1 onto contact ID 0.
 */
object CarPlayTouchMapper {
    private const val MAX_CONTACTS = 2
    private val slotPointerIds = IntArray(MAX_CONTACTS) { -1 }

    @Synchronized
    fun reset() {
        slotPointerIds.fill(-1)
    }

    @Synchronized
    fun contacts(event: MotionEvent, viewWidth: Int, viewHeight: Int): List<AirPlayContact> {
        val width = viewWidth.coerceAtLeast(1)
        val height = viewHeight.coerceAtLeast(1)
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            slotPointerIds.fill(-1)
        }
        val liftedIndex = if (action == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1
        val allUp = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL

        // Map active pointers to stable HID slots (0 or 1).
        val activePointerIndices = HashMap<Int, Int>(event.pointerCount)
        for (index in 0 until event.pointerCount) {
            val pid = event.getPointerId(index)
            activePointerIndices[pid] = index
            var slot = slotPointerIds.indexOf(pid)
            if (slot < 0) {
                val free = slotPointerIds.indexOf(-1)
                if (free >= 0) {
                    slotPointerIds[free] = pid
                }
            }
        }

        // Release any stale slot whose pointer is no longer present in the event.
        for (slot in 0 until MAX_CONTACTS) {
            val pid = slotPointerIds[slot]
            if (pid != -1 && pid !in activePointerIndices) {
                slotPointerIds[slot] = -1
            }
        }

        val contacts = ArrayList<AirPlayContact>(MAX_CONTACTS)
        for (slot in 0 until MAX_CONTACTS) {
            val pid = slotPointerIds[slot]
            if (pid == -1) continue
            val index = activePointerIndices[pid] ?: continue
            val down = !allUp && index != liftedIndex
            contacts.add(
                AirPlayContact(
                    id = slot,
                    x = (event.getX(index).toDouble() / width).coerceIn(0.0, 1.0),
                    y = (event.getY(index).toDouble() / height).coerceIn(0.0, 1.0),
                    down = down,
                ),
            )
            if (!down) {
                slotPointerIds[slot] = -1
            }
        }
        if (allUp) {
            slotPointerIds.fill(-1)
        }
        return contacts
    }
}
