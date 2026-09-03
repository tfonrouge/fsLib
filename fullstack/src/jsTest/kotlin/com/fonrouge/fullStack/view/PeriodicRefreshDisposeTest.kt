package com.fonrouge.fullStack.view

import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import io.kvision.state.ObservableValue
import io.kvision.state.bind
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * El enganche entre **el componente montado** y su registro de refresco, contra el ciclo de vida
 * real de KVision.
 *
 * ## Por qué este archivo
 *
 * Las demás pruebas llaman `onBeforeDispose()` a mano, o sea que asumen el camino que deberían
 * demostrar. La pieza de integración del rediseño es [ownPeriodicUpdateOf] —lo que `fsTabulator`
 * cuelga del `vPanel`— y sin esta prueba su única evidencia era leer el código.
 *
 * Estas pruebas montan widgets KVision de verdad y los destruyen por el camino real
 * (`disposeAll()`, y la reconstrucción que hace `bind` al republicar). Ejercitan **la misma
 * función** que usa `fsTabulator`, no una reproducción del patrón.
 *
 * ## Lo que sigue sin cubrir, dicho de frente
 *
 * Montar el `fsTabulator` completo acá está bloqueado: `TabulatorViewList.kt:293` exige un
 * `ConfigViewList.serviceManager` de Kilua RPC con registro generado por KSP. Entonces lo que
 * ninguna prueba de esta librería demuestra es **el sitio de llamada** —que `fsTabulator` invoque
 * `ownPeriodicUpdateOf` sobre el panel montado— y eso queda cubierto por lectura del código y por
 * la medición en el consumidor.
 */
class PeriodicRefreshDisposeTest {

    private val contenedores = mutableListOf<ContenedorSintetico>()

    private fun contenedor() = ContenedorSintetico().also { contenedores.add(it) }

    @BeforeTest
    fun estadoLimpio() {
        PeriodicRefreshScheduler.resetForTest()
    }

    @AfterTest
    fun limpiar() {
        contenedores.forEach { it.uninstallUpdate() }
        contenedores.clear()
        PeriodicRefreshScheduler.resetForTest()
    }

    /**
     * Destruir el panel de una tabla da de baja **su** registro y deja intacto el de la otra. Es el
     * defecto original visto desde el ciclo de vida real: antes, destruir cualquier vista vaciaba el
     * registro completo.
     */
    @Test
    fun destruirUnPanelMontadoSueltaSoloSuRegistro() {
        val raiz = SimplePanel()
        val tablaA = contenedor()
        val tablaB = contenedor()

        val panelA = raiz.vPanel { ownPeriodicUpdateOf(tablaA) }
        raiz.vPanel { ownPeriodicUpdateOf(tablaB) }
        tablaA.installUpdate()
        tablaB.installUpdate()
        assertEquals(2, PeriodicRefreshScheduler.registrationCount)

        raiz.remove(panelA)
        panelA.dispose()

        assertEquals(1, PeriodicRefreshScheduler.registrationCount, "sólo debe irse el registro de A")

        PeriodicRefreshScheduler.runDueBlocks(PeriodicRefreshScheduler.nowSecs() + 3600)
        assertEquals(0, tablaA.refrescos, "la tabla destruida no debe volver a refrescarse")
        assertEquals(1, tablaB.refrescos, "destruir A no puede apagar a B")
    }

    /**
     * `disposeAll()` sobre el contenedor padre —el camino que usa `bind` al reconstruir— también
     * dispara el enganche.
     */
    @Test
    fun disposeAllDelPadreSueltaElRegistro() {
        val raiz = SimplePanel()
        val tabla = contenedor()

        raiz.vPanel { ownPeriodicUpdateOf(tabla) }
        tabla.installUpdate()
        assertEquals(1, PeriodicRefreshScheduler.registrationCount)

        raiz.disposeAll()

        assertEquals(0, PeriodicRefreshScheduler.registrationCount)
    }

    /**
     * **El antipatrón, medido.** Una tabla construida dentro de `bind { … }` nace de nuevo en cada
     * republicación: instancia nueva, alta nueva. Sin el enganche, cada republicación dejaría un
     * registro huérfano vivo para siempre y las llamadas se multiplicarían.
     *
     * Tras diez republicaciones el registro debe valer exactamente uno: el de la tabla vigente.
     */
    @Test
    fun reconstruirPorBindNoAcumulaRegistros(): Promise<Unit> {
        val raiz = SimplePanel()
        val obs = ObservableValue(0)

        raiz.bind(obs) {
            vPanel {
                val tabla = contenedor()
                ownPeriodicUpdateOf(tabla)
                tabla.installUpdate()
            }
        }
        assertEquals(1, PeriodicRefreshScheduler.registrationCount, "línea base tras el primer render")

        repeat(10) { obs.value = it + 1 }

        // `bind` reconstruye con `singleRenderAsync`, así que la aserción espera al siguiente turno.
        return Promise { resolve, reject ->
            kotlinx.browser.window.setTimeout({
                // El try/catch importa: una aserción que lanza dentro de un `setTimeout` no rechaza
                // la promesa, y el test quedaría colgado hasta el timeout en vez de reportar el
                // fallo. Con esto, un fallo real llega como fallo.
                try {
                    assertEquals(
                        expected = 1,
                        actual = PeriodicRefreshScheduler.registrationCount,
                        message = "diez republicaciones no pueden dejar diez registros vivos",
                    )
                    resolve(Unit)
                } catch (e: Throwable) {
                    reject(e)
                }
            }, 50)
        }
    }
}
