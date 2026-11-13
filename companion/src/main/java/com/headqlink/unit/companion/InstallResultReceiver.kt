package com.headqlink.unit.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast

/**
 * Recibe el resultado de PackageInstaller al instalar una actualización desde GitHub Releases.
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirm) }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                CompanionI18n.init(context)
                Toast.makeText(
                    context,
                    CompanionI18n.tr(
                        "✓ Actualización instalada correctamente",
                        "✓ Update installed successfully",
                        "✓ Update erfolgreich installiert",
                        "✓ Atualização instalada com sucesso",
                    ),
                    Toast.LENGTH_LONG,
                ).show()
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                    CompanionI18n.init(context)
                    Toast.makeText(
                        context,
                        CompanionI18n.tr(
                            "No se completó la instalación ($msg)",
                            "Installation was not completed ($msg)",
                            "Installation wurde nicht abgeschlossen ($msg)",
                            "A instalação não foi concluída ($msg)",
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }
}
