package com.headqlink.unit.companion

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.headqlink.unit.bridge.BridgeConstants
import com.headqlink.unit.bridge.BridgeMessage
import com.headqlink.unit.bridge.MapsRoute
import com.headqlink.unit.bridge.NavPoint

/**
 * Abre Google Maps navegando, igual que Qonecta+ (`NavApps.start`): `ACTION_VIEW` con el paquete de Maps.
 * Android Auto, que está proyectando, recoge la navegación y la muestra en el coche.
 *
 * Abrir una pantalla desde segundo plano solo está permitido si el usuario dio "Mostrar sobre otras apps" o si el
 * coche está asociado como dispositivo compañero. Si no se puede, deja una notificación para tocarla.
 */
object MapsLauncher {

    private const val CHANNEL_ROUTE = "ruta_coche"
    private const val NOTIF_ROUTE = 2

    fun canOpenFromBackground(ctx: Context): Boolean =
        Settings.canDrawOverlays(ctx) || hasCompanionAssociation(ctx)

    @Suppress("DEPRECATION")
    fun hasCompanionAssociation(ctx: Context): Boolean = runCatching {
        val cdm = ctx.getSystemService(CompanionDeviceManager::class.java) ?: return false
        if (Build.VERSION.SDK_INT >= 33) cdm.myAssociations.isNotEmpty() else cdm.associations.isNotEmpty()
    }.getOrDefault(false)

    fun isMapsInstalled(ctx: Context): Boolean = runCatching {
        ctx.packageManager.getPackageInfo(BridgeConstants.MAPS_PACKAGE, 0)
        true
    }.getOrDefault(false)

    fun buildIntent(ctx: Context, built: MapsRoute.Built): Intent {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(built.uri))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        if (isMapsInstalled(ctx)) i.setPackage(BridgeConstants.MAPS_PACKAGE)
        return i
    }

    /** Atiende una orden del coche y devuelve la respuesta para él. */
    fun handle(ctx: Context, nav: BridgeMessage.Navigate): BridgeMessage.NavResult {
        if (!nav.dest.isValid) {
            return BridgeMessage.NavResult(nav.id, ok = false, error = BridgeMessage.NavResult.ERR_BAD_REQUEST)
        }
        val built = MapsRoute.build(nav.dest, nav.via)
        val intent = buildIntent(ctx, built)
        if (intent.resolveActivity(ctx.packageManager) == null) {
            return BridgeMessage.NavResult(nav.id, ok = false, error = BridgeMessage.NavResult.ERR_NO_MAPS)
        }
        val summary = describe(nav.dest, built)
        CompanionPrefs(ctx).lastRouteSummary = summary
        BridgeState.update { it.copy(lastRoute = summary) }

        if (canOpenFromBackground(ctx)) {
            val started = runCatching { ctx.applicationContext.startActivity(intent) }.isSuccess
            if (started) {
                return BridgeMessage.NavResult(nav.id, ok = true, viaSent = built.waypoints.size)
            }
        }
        // Sin permiso para abrir en segundo plano: notificación que abre Maps al tocarla.
        postTapNotification(ctx, intent, summary)
        return BridgeMessage.NavResult(
            nav.id,
            ok = false,
            error = BridgeMessage.NavResult.ERR_NEEDS_TAP,
            viaSent = built.waypoints.size,
        )
    }

    fun describe(dest: NavPoint, built: MapsRoute.Built): String {
        val name = dest.name.ifBlank { String.format(java.util.Locale.US, "%.4f, %.4f", dest.lat, dest.lon) }
        return when {
            built.waypoints.isEmpty() -> name
            built.reachesDestination -> CompanionI18n.tr(
                "$name · ${built.waypoints.size} parada(s) de carga",
                "$name · ${built.waypoints.size} charging stop(s)",
                "$name · ${built.waypoints.size} Ladestopp(s)",
                "$name · ${built.waypoints.size} paragem(ns) de carregamento",
            )
            else -> {
                val stopLabel = built.target.name.ifBlank {
                    CompanionI18n.tr("Parada", "Stop", "Stopp", "Paragem")
                }
                CompanionI18n.tr(
                    "$stopLabel (siguiente tramo hacia $name)",
                    "$stopLabel (next leg towards $name)",
                    "$stopLabel (nächster Abschnitt Richtung $name)",
                    "$stopLabel (próximo troço rumo a $name)",
                )
            }
        }
    }

    private fun postTapNotification(ctx: Context, intent: Intent, summary: String) {
        CompanionI18n.init(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ROUTE,
                CompanionI18n.tr("Rutas del coche", "Car routes", "Fahrzeugrouten", "Rotas do carro"),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = CompanionI18n.tr(
                    "Rutas que manda el planificador de Qonecta Unit",
                    "Routes sent by the Qonecta Unit planner",
                    "Routen vom Qonecta Unit Routenplaner",
                    "Rotas enviadas pelo planeador do Qonecta Unit",
                )
            }
        )
        val pi = PendingIntent.getActivity(
            ctx, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = android.app.Notification.Builder(ctx, CHANNEL_ROUTE)
            .setSmallIcon(R.drawable.ic_stat_route)
            .setContentTitle(
                CompanionI18n.tr(
                    "Ruta del coche: toca para abrir Google Maps",
                    "Car route: tap to open Google Maps",
                    "Fahrzeugroute: Tippen zum Öffnen von Google Maps",
                    "Rota do carro: toca para abrir o Google Maps",
                )
            )
            .setContentText(summary)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setCategory(android.app.Notification.CATEGORY_NAVIGATION)
            .build()
        runCatching { nm.notify(NOTIF_ROUTE, n) }
    }
}
