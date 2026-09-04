package com.fonrouge.fullStack.panel

import com.fonrouge.fullStack.view.ContenedorSintetico
import com.fonrouge.fullStack.view.PeriodicRefreshScheduler
import com.fonrouge.fullStack.view.ownPeriodicUpdateOf
import io.kvision.panel.ContainerType
import io.kvision.panel.Root
import io.kvision.panel.SimplePanel
import io.kvision.panel.TabPanel
import io.kvision.panel.tab
import io.kvision.panel.tabPanel
import io.kvision.panel.vPanel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * El ciclo de vida de las pestañas: que destruir un [FsTabPanel] destruya de verdad su contenido.
 *
 * ## Por qué estas pruebas y no las del planificador
 *
 * `PeriodicRefreshDisposeTest` destruye paneles **directamente**, así que nunca tocó un `TabPanel`
 * y no podía ver este defecto. Lo encontró una medición de llamadas RPC en la app consumidora: al
 * cerrar una ficha, la lista embebida **dentro de una pestaña** seguía pidiendo datos cada 5 s, y
 * cada activación de la pestaña sumaba otro huérfano.
 *
 * La causa está en KVision, no en fsLib: `TabPanel` guarda sus pestañas fuera de `children` y
 * sobrescribe `disposeAll()` pero no `dispose()`, así que el recorrido de destrucción nunca las
 * alcanza. [tabPanelCrudoNoDestruyeSusPestanas] fija ese comportamiento como caracterización —
 * cuando KVision lo corrija, **esa** prueba fallará, y ésa es la señal para retirar [FsTabPanel].
 */
class FsTabPanelDisposeTest {

    private val contenedores = mutableListOf<ContenedorSintetico>()
    private var hooksDisparados = 0

    private fun contenedor() = ContenedorSintetico().also { contenedores.add(it) }

    @BeforeTest
    fun estadoLimpio() {
        PeriodicRefreshScheduler.resetForTest()
        hooksDisparados = 0
    }

    @AfterTest
    fun limpiar() {
        contenedores.forEach { it.uninstallUpdate() }
        contenedores.clear()
        PeriodicRefreshScheduler.resetForTest()
    }

    /** Arma un panel con dos pestañas; la segunda lleva una tabla registrada. */
    private fun panelConTabla(
        raiz: SimplePanel,
        activarSegunda: Boolean,
        crudo: Boolean = false,
    ): Pair<TabPanel, ContenedorSintetico> {
        val tabla = contenedor()
        val armar: TabPanel.() -> Unit = {
            tab(label = "Main") { vPanel { } }
            tab(label = "Tabla") {
                vPanel {
                    ownPeriodicUpdateOf(tabla)
                    addBeforeDisposeHook { hooksDisparados++ }
                }
            }
        }
        val panel = if (crudo) raiz.tabPanel(init = armar) else raiz.fsTabPanel(init = armar)
        if (activarSegunda) panel.activeIndex = 1
        tabla.installUpdate()
        return panel to tabla
    }

    // region el defecto y su corrección

    /**
     * **La corrección, en una prueba.** Destruir el panel da de baja la tabla que vive dentro de la
     * pestaña, y el hook de destrucción corre **exactamente una vez**.
     */
    @Test
    fun destruirElPanelDestruyeElContenidoDeLaPestana() {
        val raiz = SimplePanel()
        val (panel, tabla) = panelConTabla(raiz, activarSegunda = true)
        assertEquals(1, PeriodicRefreshScheduler.registrationCount, "la tabla debe estar registrada")

        panel.dispose()

        assertEquals(1, hooksDisparados, "el hook de la pestaña debe correr exactamente una vez")
        assertEquals(0, PeriodicRefreshScheduler.registrationCount, "vuelta a la línea base")

        PeriodicRefreshScheduler.runDueBlocks(PeriodicRefreshScheduler.nowSecs() + 3600)
        assertEquals(0, tabla.refrescos, "una tabla destruida no vuelve a refrescarse")
    }

    /**
     * **Caracterización del defecto de KVision.** El `tabPanel` crudo NO destruye sus pestañas, así
     * que el registro sobrevive. Documenta por qué existe [FsTabPanel].
     *
     * Verificado contra KVision 9.5.0 y 9.6.0. Cuando KVision sobrescriba `dispose()` en
     * `TabPanel`, esta prueba fallará: es la señal de que el envoltorio ya no hace falta y debe
     * retirarse antes de que las pestañas se destruyan dos veces.
     */
    @Test
    fun tabPanelCrudoNoDestruyeSusPestanas() {
        val raiz = SimplePanel()
        val (panel, _) = panelConTabla(raiz, activarSegunda = true, crudo = true)
        assertEquals(1, PeriodicRefreshScheduler.registrationCount)

        panel.dispose()

        assertEquals(
            expected = 0,
            actual = hooksDisparados,
            message = "si esto falla, KVision ya corrigió TabPanel.dispose() — retirar FsTabPanel",
        )
        assertEquals(1, PeriodicRefreshScheduler.registrationCount, "el registro sobrevive: la fuga")
    }

    /** También se destruye una pestaña **inactiva**: no depende de que se haya activado. */
    @Test
    fun laPestanaInactivaTambienSeDestruye() {
        val raiz = SimplePanel()
        val (panel, _) = panelConTabla(raiz, activarSegunda = false)
        assertEquals(1, PeriodicRefreshScheduler.registrationCount)

        panel.dispose()

        assertEquals(1, hooksDisparados)
        assertEquals(0, PeriodicRefreshScheduler.registrationCount)
    }

    /**
     * **Destruir NO debe emitir eventos `changeTab`.** Es el oráculo que fija la forma de [dispose]:
     * sólo hay eventos si se pasa por el setter de `activeIndex`, y pasar por él es exactamente lo
     * que dispara la cascada de `removeCssClass` → `refresh()` → `Root.reRender()` (un patch del
     * árbol completo por pestaña restante, del orden de N² por cierre).
     *
     * La prueba monta el panel en un `Root` **de verdad**: `Widget.dispatchEvent` despacha sobre
     * `getElement()`, así que sin render no habría elemento, no habría evento, y la prueba pasaría
     * por vacío diga lo que diga el código. El `assertEquals(2, …)` previo a destruir es parte del
     * oráculo — comprueba que el listener SÍ capta eventos reales de este panel antes de exigir que
     * durante la destrucción no llegue ninguno.
     *
     * Equivale a `disposeWithoutChangeTabEvents` del PR que fsLib abrió contra KVision; tenerla de
     * este lado hace que el workaround converja con lo que aterrice upstream en vez de divergir.
     */
    @Test
    fun destruirNoEmiteEventosDeCambioDePestana() {
        val id = "fs-tab-panel-test-root"
        val div = kotlinx.browser.document.createElement("div")
        div.setAttribute("id", id)
        kotlinx.browser.document.body?.appendChild(div)
        try {
            val raiz = Root(id, containerType = ContainerType.NONE)
            val (panel, _) = panelConTabla(raiz, activarSegunda = false)

            var eventos = 0
            panel.getElement()?.addEventListener("changeTab", { eventos++ })

            // Control positivo: el listener capta eventos reales de ESTE panel. Sin esto, un
            // `assertEquals(0, …)` no distingue "no hubo eventos" de "el listener no estaba puesto".
            panel.activeIndex = 1
            panel.activeIndex = 0
            assertEquals(2, eventos, "el listener debe captar los cambios de pestaña reales")

            eventos = 0
            panel.dispose()
            assertEquals(0, eventos, "destruir no puede emitir cambios de pestaña")
        } finally {
            kotlinx.browser.document.getElementById(id)?.remove()
        }
    }

    /** Destruir no toca `activeIndex`: es la señal de que no se pasó por su setter. */
    @Test
    fun destruirNoToquetealaPestanaActiva() {
        val raiz = SimplePanel()
        val (panel, _) = panelConTabla(raiz, activarSegunda = true)
        assertEquals(1, panel.activeIndex)

        panel.dispose()

        assertEquals(1, panel.activeIndex, "el setter de activeIndex no debe correr al destruir")
    }

    // endregion

    // region no crece, no destruye de más

    /** Tres ciclos de armar y destruir dejan el registro en cero: no acumula. */
    @Test
    fun ciclosRepetidosNoAcumulan() {
        repeat(3) {
            val raiz = SimplePanel()
            val (panel, _) = panelConTabla(raiz, activarSegunda = true)
            assertEquals(1, PeriodicRefreshScheduler.registrationCount, "una tabla viva por ciclo")
            panel.dispose()
            assertEquals(0, PeriodicRefreshScheduler.registrationCount)
        }
        assertEquals(3, hooksDisparados, "un hook por ciclo, ni más ni menos")
    }

    /**
     * Cambiar de pestaña **no** destruye su contenido. `TabPanel` sólo renderiza la pestaña activa;
     * si el cambio destruyera la anterior, volver a ella daría una pestaña muerta.
     */
    @Test
    fun cambiarDePestanaNoDestruyeElContenido() {
        val raiz = SimplePanel()
        val (panel, tabla) = panelConTabla(raiz, activarSegunda = true)

        panel.activeIndex = 0
        panel.activeIndex = 1
        panel.activeIndex = 0

        assertEquals(0, hooksDisparados, "cambiar de pestaña no es destruirla")
        assertEquals(1, PeriodicRefreshScheduler.registrationCount)

        PeriodicRefreshScheduler.runDueBlocks(PeriodicRefreshScheduler.nowSecs() + 3600)
        assertTrue(tabla.refrescos > 0, "la tabla sigue viva y refrescando")
    }

    /** `dispose()` dos veces es inofensivo: la segunda vuelta no encuentra pestañas. */
    @Test
    fun disposeEsIdempotente() {
        val raiz = SimplePanel()
        val (panel, _) = panelConTabla(raiz, activarSegunda = true)

        panel.dispose()
        panel.dispose()

        assertEquals(1, hooksDisparados, "el hook no puede correr dos veces")
        assertEquals(0, PeriodicRefreshScheduler.registrationCount)
    }

    /** Destruir un panel no toca a una tabla residente montada fuera de él. */
    @Test
    fun laTablaResidenteSobrevive() {
        val raiz = SimplePanel()
        val residente = contenedor()
        raiz.vPanel { ownPeriodicUpdateOf(residente) }
        residente.installUpdate()

        val (panel, _) = panelConTabla(raiz, activarSegunda = true)
        assertEquals(2, PeriodicRefreshScheduler.registrationCount)

        panel.dispose()

        assertEquals(1, PeriodicRefreshScheduler.registrationCount, "la residente sigue registrada")
        PeriodicRefreshScheduler.runDueBlocks(PeriodicRefreshScheduler.nowSecs() + 3600)
        assertEquals(1, residente.refrescos, "y sigue refrescando a su cadencia")
    }

    /**
     * Destruir el **contenedor padre** —el caso real: un modal que se cierra— también alcanza la
     * pestaña. Es la cadena completa `raíz → panel → pestaña → tabla`.
     */
    @Test
    fun destruirLaRaizAlcanzaLaPestana() {
        val raiz = SimplePanel()
        panelConTabla(raiz, activarSegunda = true)
        assertEquals(1, PeriodicRefreshScheduler.registrationCount)

        raiz.dispose()

        assertEquals(1, hooksDisparados)
        assertEquals(0, PeriodicRefreshScheduler.registrationCount)
    }

    /**
     * Tras destruir, la lista `tabs` queda **vacía**: no se conserva un grafo de componentes ya
     * destruidos colgando del panel.
     *
     * Se mira `pestanasVivas` y no `getChildren()`: las pestañas no están en `children`, así que
     * afirmar sobre `getChildren()` daría verde sin probar nada. La afirmación previa a destruir
     * es parte de la prueba — sin ella, un panel que nunca tuvo pestañas la pasaría igual.
     */
    @Test
    fun noQuedaGrafoColgando() {
        val raiz = SimplePanel()
        val (panel, _) = panelConTabla(raiz, activarSegunda = true)
        assertEquals(2, (panel as FsTabPanel).pestanasVivas, "dos pestañas antes de destruir")

        panel.dispose()

        assertEquals(0, panel.pestanasVivas, "la lista de pestañas debe quedar vacía")
    }

    // endregion
}
