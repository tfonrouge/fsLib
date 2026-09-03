package com.fonrouge.fullStack.view

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Contrato de propiedad del refresco periódico ([PeriodicRefreshScheduler] y el par
 * `installUpdate` / `uninstallUpdate` de [ViewDataContainer]).
 *
 * ## Qué falla si estas pruebas no existen
 *
 * El diseño anterior guardaba las funciones de refresco en un mapa del companion y las apagaba con
 * `handleInterval = null`, cuyo `set` hacía `clearInterval` **y `dataUpdateFuncs.clear()`**. Como
 * `onBeforeDispose()` hacía justamente eso, **cerrar una vista apagaba el refresco de todas**.
 *
 * Ningún test lo hubiera notado y ninguna pantalla lo grita: la tabla simplemente deja de
 * actualizarse. Se detectó midiendo llamadas RPC en ventanas silenciosas de 16 s sobre una lista
 * embebida: **6 → 1 → 1** al abrir, cerrar y reabrir. [cerrarUnRegistroNoApagaALosDemas] es esa
 * medición convertida en prueba determinista.
 *
 * Cada prueba se escribe para **poder fallar**: la de aislamiento falla si `unregister` toca algo
 * más que su token; la de idempotencia falla si `installUpdate` apila registros; la del temporizador
 * falla tanto si no arranca como si se queda corriendo de más.
 */
class PeriodicRefreshSchedulerTest {

    /** Tokens dados de alta por una prueba, para garantizar registro vacío al terminar. */
    private val tokens = mutableListOf<Int>()

    private fun alta(intervalSecs: Int = 0, block: () -> Unit): Int =
        PeriodicRefreshScheduler.register(intervalSecs = { intervalSecs }, block = block)
            .also { tokens.add(it) }

    @BeforeTest
    fun registroLimpioAlEmpezar() {
        // Falsable de entrada: si una prueba anterior dejó basura, ésta lo dice en vez de heredarla.
        assertEquals(
            expected = 0,
            actual = PeriodicRefreshScheduler.registrationCount,
            message = "el registro debe empezar vacío",
        )
    }

    @AfterTest
    fun limpiar() {
        tokens.forEach { PeriodicRefreshScheduler.unregister(it) }
        tokens.clear()
        contenedores.forEach { it.uninstallUpdate() }
        contenedores.clear()
    }

    private var saltos = 0

    /**
     * Reloj sintético **estrictamente creciente**, muy por delante del alta, para que venza
     * cualquier intervalo sin esperar segundos reales. Tiene que crecer en cada llamada: dos
     * barridos con el mismo `now` no vencen un intervalo mayor que cero, porque `runDueBlocks` deja
     * `lastRun = now` en el primero.
     */
    private fun futuro(): Long = PeriodicRefreshScheduler.nowSecs() + 3600L * (++saltos)

    // region ownership

    /**
     * **El defecto original, en una prueba.** Dos tablas registradas; se cierra una. La otra debe
     * seguir corriendo. Antes, la baja de la primera vaciaba el mapa completo y la segunda quedaba
     * muda para siempre.
     */
    @Test
    fun cerrarUnRegistroNoApagaALosDemas() {
        var corridasA = 0
        var corridasB = 0
        val tokenA = alta { corridasA++ }
        alta { corridasB++ }

        PeriodicRefreshScheduler.runDueBlocks(futuro())
        assertEquals(1, corridasA)
        assertEquals(1, corridasB)

        PeriodicRefreshScheduler.unregister(tokenA)
        PeriodicRefreshScheduler.runDueBlocks(futuro())

        assertEquals(1, corridasA, "el registro dado de baja no debe volver a correr")
        assertEquals(2, corridasB, "dar de baja a A no puede apagar a B")
        assertEquals(1, PeriodicRefreshScheduler.registrationCount)
    }

    /** Cada alta recibe un token propio: dos tablas de la misma clase no se pisan. */
    @Test
    fun cadaAltaRecibeTokenPropio() {
        val t1 = alta { }
        val t2 = alta { }
        assertNotEquals(t1, t2)
        assertEquals(2, PeriodicRefreshScheduler.registrationCount)
    }

    /** Dar de baja dos veces —o un token desconocido— no tira a nadie más. */
    @Test
    fun bajaRepetidaOAjenaEsNoOp() {
        var corridas = 0
        val token = alta { corridas++ }

        PeriodicRefreshScheduler.unregister(token)
        PeriodicRefreshScheduler.unregister(token)
        PeriodicRefreshScheduler.unregister(null)
        PeriodicRefreshScheduler.unregister(999_999)

        val vivo = alta { corridas++ }
        PeriodicRefreshScheduler.runDueBlocks(futuro())
        assertEquals(1, corridas, "sólo el registro vivo debió correr")
        assertEquals(1, PeriodicRefreshScheduler.registrationCount)
        PeriodicRefreshScheduler.unregister(vivo)
    }

    // endregion

    // region temporizador

    /** El temporizador arranca con el primer registro y se apaga **sólo** cuando se va el último. */
    @Test
    fun temporizadorViveExactamenteMientrasHayRegistros() {
        assertFalse(PeriodicRefreshScheduler.timerRunning, "sin registros no debe haber temporizador")

        val t1 = alta { }
        assertTrue(PeriodicRefreshScheduler.timerRunning, "el primer alta debe arrancarlo")

        val t2 = alta { }
        PeriodicRefreshScheduler.unregister(t1)
        assertTrue(
            PeriodicRefreshScheduler.timerRunning,
            "con un registro vivo el temporizador NO puede apagarse — ése era el defecto",
        )

        PeriodicRefreshScheduler.unregister(t2)
        assertFalse(PeriodicRefreshScheduler.timerRunning, "sin registros debe apagarse")
    }

    // endregion

    // region independencia de cadencia

    /**
     * Cada entrada lleva su propio intervalo y su propio `lastRun`. Con un `startTime` global —el
     * diseño anterior— la tabla que corría posponía a todas las demás.
     */
    @Test
    fun cadaRegistroCorreASuPropiaCadencia() {
        var rapidas = 0
        var lentas = 0
        alta(intervalSecs = 0) { rapidas++ }
        alta(intervalSecs = 600) { lentas++ }

        val ahora = PeriodicRefreshScheduler.nowSecs()
        PeriodicRefreshScheduler.runDueBlocks(ahora)
        PeriodicRefreshScheduler.runDueBlocks(ahora)

        assertEquals(2, rapidas, "la de intervalo 0 corre en cada barrido")
        assertEquals(0, lentas, "la de 600 s no debe correr todavía")

        PeriodicRefreshScheduler.runDueBlocks(ahora + 601)
        assertEquals(1, lentas, "vencido su intervalo, la lenta corre")
    }

    /** `postponeAll` —el viejo `clearStartTime`— empuja a todos un intervalo hacia adelante. */
    @Test
    fun postponerAplazaATodos() {
        var corridas = 0
        alta(intervalSecs = 10) { corridas++ }

        PeriodicRefreshScheduler.postponeAll()
        PeriodicRefreshScheduler.runDueBlocks(PeriodicRefreshScheduler.nowSecs() + 5)
        assertEquals(0, corridas, "5 s después de posponer, con intervalo 10, no debe correr")

        PeriodicRefreshScheduler.runDueBlocks(PeriodicRefreshScheduler.nowSecs() + 11)
        assertEquals(1, corridas)
    }

    // endregion

    // region robustez del barrido

    /**
     * Un bloque puede destruir vistas —y darlas de baja— mientras el barrido corre. La iteración va
     * sobre una copia, y el registro que se fue **no** corre en ese mismo barrido.
     */
    @Test
    fun bajaDuranteElBarridoNoCorrompeLaIteracion() {
        var corridasVictima = 0
        var corridasTestigo = 0
        var victima = -1
        alta {
            PeriodicRefreshScheduler.unregister(victima)
        }
        victima = alta { corridasVictima++ }
        alta { corridasTestigo++ }

        PeriodicRefreshScheduler.runDueBlocks(futuro())

        assertEquals(0, corridasVictima, "el registro dado de baja durante el barrido no debe correr")
        assertEquals(1, corridasTestigo, "el registro posterior sí debe correr")
    }

    /** Un bloque que lanza no puede llevarse a los demás — antes un `try` envolvía el `forEach`. */
    @Test
    fun bloqueQueLanzaNoDetieneALosDemas() {
        var corridas = 0
        alta { throw IllegalStateException("falla sintética de prueba") }
        alta { corridas++ }

        PeriodicRefreshScheduler.runDueBlocks(futuro())
        assertEquals(1, corridas, "el bloque siguiente al que lanzó debe correr igual")

        // Y el que lanzó no queda reintentando: su `lastRun` se marcó antes de invocar.
        PeriodicRefreshScheduler.runDueBlocks(futuro())
        assertEquals(2, corridas)
    }

    // endregion

    // region ViewDataContainer

    private val contenedores = mutableListOf<ContenedorSintetico>()

    private fun contenedor(periodic: Boolean? = true, sinBloque: Boolean = false) =
        ContenedorSintetico(periodic, sinBloque).also { contenedores.add(it) }

    /**
     * `installUpdate()` es idempotente. Importa porque `fsTabulator` lo llama en **cada** render y
     * `ViewItem` en **cada** carga de item: sin la guarda, cada repintado apilaría un registro más y
     * la misma tabla acabaría pidiendo datos N veces por vuelta.
     */
    @Test
    fun installUpdateEsIdempotente() {
        val c = contenedor()
        c.installUpdate()
        c.installUpdate()
        c.installUpdate()

        assertEquals(1, PeriodicRefreshScheduler.registrationCount)
        PeriodicRefreshScheduler.runDueBlocks(futuro())
        assertEquals(1, c.refrescos, "tres altas no pueden producir tres refrescos")
    }

    /**
     * Abrir y cerrar deja el registro **exactamente** como estaba: ni tokens huérfanos acumulados
     * (que multiplicarían las llamadas) ni bajas de más (que las apagarían).
     */
    @Test
    fun abrirYCerrarVuelveAlPuntoDePartida() {
        val residente = contenedor()
        residente.installUpdate()
        val base = PeriodicRefreshScheduler.registrationCount

        repeat(3) {
            val efimero = contenedor()
            efimero.installUpdate()
            assertEquals(base + 1, PeriodicRefreshScheduler.registrationCount)
            efimero.onBeforeDispose()
            assertEquals(base, PeriodicRefreshScheduler.registrationCount)
        }

        residente.refrescos = 0
        PeriodicRefreshScheduler.runDueBlocks(futuro())
        assertEquals(
            expected = 1,
            actual = residente.refrescos,
            message = "tras tres aperturas y cierres, el contenedor residente debe seguir vivo y solo",
        )
    }

    /**
     * Un contenedor que se excluye no se registra. Antes entraba al mapa igual y terminaba
     * refrescándose si el temporizador de otra vista estaba corriendo: la bandera sólo decidía quién
     * creaba el temporizador. Éste es el único cambio de comportamiento visible del rediseño.
     */
    @Test
    fun contenedorExcluidoNoSeRegistra() {
        val excluido = contenedor(periodic = false)
        excluido.installUpdate()
        assertEquals(0, PeriodicRefreshScheduler.registrationCount)

        val activo = contenedor(periodic = true)
        activo.installUpdate()
        PeriodicRefreshScheduler.runDueBlocks(futuro())
        assertEquals(0, excluido.refrescos, "excluido no debe refrescarse ni con el temporizador vivo")
        assertEquals(1, activo.refrescos)
    }

    /**
     * Un contenedor sin bloque de refresco (`onPeriodicDataUpdate == null`) no ocupa lugar en el
     * registro. Sin esta guarda se registraría un token que nunca hace nada y que, por existir,
     * mantendría vivo el temporizador compartido aunque no quedara ninguna tabla real refrescándose.
     */
    @Test
    fun contenedorSinBloqueNoSeRegistra() {
        val sinBloque = contenedor(sinBloque = true)
        sinBloque.installUpdate()
        assertEquals(0, PeriodicRefreshScheduler.registrationCount)
        assertFalse(PeriodicRefreshScheduler.timerRunning, "no debe arrancar el temporizador")
    }

    /**
     * Apagar la bandera en caliente surte efecto sin volver a montar: el bloque la consulta en vivo.
     * La dirección contraria (`false` → `true`) no se prueba acá porque no ocurre sola — está
     * documentada en [ViewDataContainer.installUpdate] y requiere un `installUpdate()` nuevo.
     */
    @Test
    fun apagarLaBanderaEnCalienteDetieneElRefresco() {
        val c = contenedor(periodic = true)
        c.installUpdate()

        PeriodicRefreshScheduler.runDueBlocks(futuro())
        assertEquals(1, c.refrescos)

        c.periodicUpdateDataView = false
        PeriodicRefreshScheduler.runDueBlocks(futuro())
        assertEquals(1, c.refrescos, "con la bandera apagada el bloque no debe hacer nada")
    }

    /** `suspendPeriodicUpdate()` calla el refresco sin soltar el registro; `resume` lo devuelve. */
    @Test
    fun suspenderCallaSinSoltarElRegistro() {
        val c = contenedor()
        c.installUpdate()
        c.suspendPeriodicUpdate()

        PeriodicRefreshScheduler.runDueBlocks(futuro())
        assertEquals(0, c.refrescos)
        assertEquals(1, PeriodicRefreshScheduler.registrationCount, "suspender no da de baja")

        c.resumePeriodicUpdate()
        PeriodicRefreshScheduler.runDueBlocks(futuro())
        assertEquals(1, c.refrescos)
    }

    // endregion
}
