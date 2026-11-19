package com.shilapi.xcertplay.hud

import android.content.Context
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/**
 * Nonblocking boundary between phone control messages and vendor services.
 *
 * Disabled in Qonecta Unit Core (Leapmotor C10): prevents spawning BYD HUD/cluster worker
 * threads or hammering ActivityManagerService with `com.ts.car.someip.SomeIpServerService`
 * `bindService` calls every 300ms on non-BYD head units.
 */
object BydNavigationOutputs {
    fun onAppOpened(@Suppress("UNUSED_PARAMETER") context: Context) = Unit
    fun setDiagnosticHold(@Suppress("UNUSED_PARAMETER") hold: Boolean) = Unit
    fun start(@Suppress("UNUSED_PARAMETER") context: Context) = Unit
    internal fun onFrame(@Suppress("UNUSED_PARAMETER") frame: Iap2Frame) = Unit
    fun endNow() = Unit
}
