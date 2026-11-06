package com.headqlink.unit.companion

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import androidx.annotation.RequiresApi

/**
 * El sistema llama aquí cuando el coche asociado aparece o desaparece por Bluetooth, aunque el Companion esté
 * cerrado. Así la conexión con Qonecta Unit se abre sola al subir al coche.
 */
@RequiresApi(31)
class CarPresenceService : CompanionDeviceService() {

    @Deprecated("API 31-32")
    override fun onDeviceAppeared(address: String) = carAppeared()

    @Deprecated("API 31-32")
    override fun onDeviceDisappeared(address: String) = carGone()

    @RequiresApi(33)
    override fun onDeviceAppeared(associationInfo: AssociationInfo) = carAppeared()

    @RequiresApi(33)
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) = carGone()

    private fun carAppeared() {
        val prefs = CompanionPrefs(this)
        if (prefs.enabled && prefs.isPaired) BridgeService.start(this)
    }

    /** Al bajar del coche se deja de buscar (si no hay conexión viva por Wi-Fi). */
    private fun carGone() {
        if (BridgeState.current.phase != BridgeState.Phase.CONNECTED) BridgeService.stop(this)
    }
}
