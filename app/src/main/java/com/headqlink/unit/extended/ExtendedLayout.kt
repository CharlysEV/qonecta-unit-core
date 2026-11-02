package com.headqlink.unit.extended

import kotlin.math.max
import kotlin.math.min

/**
 * Márgenes por lado que se le anuncian a Android Auto (UpdateUiConfigRequest), en px del vídeo
 * negociado: AA dibuja su interfaz en el frame menos estos márgenes.
 */
data class AaMargins(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    override fun toString(): String = "L$left T$top R$right B$bottom"
}

/**
 * Geometría del modo Extendido (pura, sin Android: la prueban los tests del PC).
 *
 * AA se sigue negociando a pantalla completa (en el C10, 1920x1080 con margin_height 198) y ni la
 * vista de proyección ni la capa táctil cambian. En Extendido se le manda a AA, en caliente, un
 * margen derecho mayor (R + extraR): su interfaz acaba donde empieza nuestro panel, una vista opaca
 * encima de la franja derecha. Es lo que hace Qonecta+ en el coche (AaPassthroughSource: márgenes
 * por lado con el hueco a la derecha).
 *
 * ```
 * uiW = negW − (L + R) ;  uiH = negH − (T + B)
 * minAa   = max(ceil(800·dpi/160), ceil(1,15·uiH))
 * panelPx = min(round(viewW·pct/100), viewW − ceil(minAa·viewW/uiW))
 * disponible = panelPx ≥ max(200, 0,15·viewW)
 * aaVideoW = floor((viewW − panelPx)·uiW/viewW) ;  extraR = uiW − aaVideoW
 * Extendido: (L, T, R + extraR, B)     Completa: (L, T, R, B), los simétricos
 * ```
 *
 * Unidades: viewW y panelPx son px de la vista (el contenedor de la actividad, sin padding); el
 * resto, px del vídeo de AA. Con L = 0 la x de la pantalla y la del contenido de AA solo se
 * diferencian en la escala viewW/uiW (1 en el C10). Todo sale de las medidas del contenedor y de
 * HeadUnitScreenConfig, nunca de 1920x882 fijo.
 */
object ExtendedLayout {
    /** % de la pantalla para el panel (v1: fijo, sin interfaz). */
    const val DEFAULT_PERCENT = 30
    const val MIN_PERCENT = 20
    const val MAX_PERCENT = 40

    /**
     * Ancho mínimo de AA: 800 dp y 1,15 veces el alto (Qonecta+, CarUi.SPLIT_MIN_AA_DP y
     * SPLIT_MIN_AA_ASPECT). Más estrecho, AA pasa a su diseño de tarjetas y Google Maps se queda en
     * modo reducido. Medido en el C10 a 200 dpi: falla a 912 px y va bien a 950.
     */
    const val MIN_AA_DP = 800
    const val MIN_AA_ASPECT_PCT = 115

    /** Un panel más estrecho que esto (px de la vista) no sirve: sin Extendido. */
    const val MIN_PANEL_PX = 200

    /** ...ni uno de menos del 15 % de la pantalla. */
    const val MIN_PANEL_PCT = 15

    /** Densidad si no se sabe la de AA. */
    private const val FALLBACK_DPI = 160

    data class Input(
        /** Ancho del contenedor sin padding (px de la vista). */
        val viewW: Int,
        /** Resolución negociada con AA (HeadUnitScreenConfig.getNegotiatedWidth/Height). */
        val negW: Int,
        val negH: Int,
        /** Márgenes simétricos de HeadUnitScreenConfig (getLeft/Top/Right/BottomMargin). */
        val full: AaMargins,
        /** Densidad que se le anuncia a AA (HeadUnitScreenConfig.getDensityDpi). */
        val dpi: Int,
        /** % de la pantalla para el panel; se acota entre MIN_PERCENT y MAX_PERCENT. */
        val percent: Int = DEFAULT_PERCENT,
    )

    data class Result(
        /** Hay sitio para el panel sin dejar a AA por debajo de su mínimo. */
        val available: Boolean,
        /** Ancho del panel, px de la vista (0 sin Extendido). */
        val panelPx: Int,
        /** Ancho de la interfaz de AA en Extendido, px del vídeo (uiW sin Extendido). */
        val aaVideoW: Int,
        /** Lo que se suma al margen derecho en Extendido (0 sin Extendido). */
        val extraR: Int,
        /** Interfaz de AA a pantalla completa: vídeo negociado menos márgenes. */
        val uiW: Int,
        val uiH: Int,
        /** Ancho mínimo de AA, px del vídeo. */
        val minAaW: Int,
        /** El panel que daría el %, antes de mirar si cabe (para el log). */
        val wantedPanelPx: Int,
        /** Márgenes en Pantalla completa: los simétricos de HeadUnitScreenConfig. */
        val full: AaMargins,
        /** Márgenes en Extendido: (L, T, R + extraR, B); sin Extendido, los de full. */
        val extended: AaMargins,
    ) {
        /** Márgenes del modo: los del Extendido solo si está disponible. */
        fun margins(extendedMode: Boolean): AaMargins =
            if (extendedMode && available) extended else full
    }

    fun clampPercent(percent: Int): Int = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)

    /** Puro: el panel y los márgenes del Extendido para estas medidas. */
    fun compute(input: Input): Result {
        val full = input.full
        val viewW = input.viewW
        val uiW = input.negW - full.left - full.right
        val uiH = input.negH - full.top - full.bottom
        val dpi = if (input.dpi > 0) input.dpi else FALLBACK_DPI
        // ceil(800·dpi/160) y ceil(1,15·uiH), en enteros para que no baile un píxel
        val minAaW = max(
            ceilDiv(MIN_AA_DP.toLong() * dpi, 160L),
            ceilDiv(MIN_AA_ASPECT_PCT.toLong() * max(uiH, 0), 100L),
        ).toInt()
        val pct = clampPercent(input.percent)
        val wanted = if (viewW > 0) ((viewW.toLong() * pct + 50L) / 100L).toInt() else 0

        fun without() = Result(
            available = false, panelPx = 0, aaVideoW = max(uiW, 0), extraR = 0,
            uiW = uiW, uiH = uiH, minAaW = minAaW, wantedPanelPx = wanted,
            full = full, extended = full,
        )

        if (viewW <= 0 || uiW <= 0 || uiH <= 0) return without()
        // Lo que tiene que quedarle a AA, en px de la vista
        val minAaView = ceilDiv(minAaW.toLong() * viewW, uiW.toLong()).toInt()
        val panelPx = min(wanted, viewW - minAaView)
        val fits = panelPx >= MIN_PANEL_PX && panelPx.toLong() * 100L >= MIN_PANEL_PCT.toLong() * viewW
        if (!fits) return without()
        val aaVideoW = ((viewW - panelPx).toLong() * uiW / viewW).toInt()
        val extraR = uiW - aaVideoW
        return Result(
            available = true, panelPx = panelPx, aaVideoW = aaVideoW, extraR = extraR,
            uiW = uiW, uiH = uiH, minAaW = minAaW, wantedPanelPx = wanted,
            full = full, extended = AaMargins(full.left, full.top, full.right + extraR, full.bottom),
        )
    }

    /** ceil(a/b) para a ≥ 0 y b > 0. */
    private fun ceilDiv(a: Long, b: Long): Long = (a + b - 1L) / b
}
