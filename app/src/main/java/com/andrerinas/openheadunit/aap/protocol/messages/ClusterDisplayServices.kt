package com.andrerinas.openheadunit.aap.protocol.messages

import android.content.Context
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.aap.protocol.proto.Media
import com.andrerinas.openheadunit.utils.Settings

/**
 * Disabled in Qonecta Unit Core (Phase 0): single hardware video decoder on main display only.
 */
object ClusterDisplayServices {
    fun buildUiConfig720(settings: Settings): Media.UiConfig {
        val zero = Media.Insets.newBuilder().setTop(0).setBottom(0).setLeft(0).setRight(0).build()
        return Media.UiConfig.newBuilder()
            .setMargins(zero)
            .setContentInsets(zero)
            .setStableContentInsets(zero)
            .build()
    }

    fun build(context: Context, settings: Settings): List<Control.Service> = emptyList()
}
