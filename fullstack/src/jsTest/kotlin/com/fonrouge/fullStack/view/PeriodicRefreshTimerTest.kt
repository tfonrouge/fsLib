package com.fonrouge.fullStack.view

import com.fonrouge.base.model.UserSessionParams
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/**
 * Las tres guardas que el primer censo declaró **sin seam observable**: que se cree un solo
 * temporizador, la compuerta de inactividad y la reentrancia del barrido.
 *
 * Ese diagnóstico estaba mal. No eran inobservables: faltaba inyectar el reloj y el temporizador.
 * Con [PeriodicRefreshScheduler.clockSecs], [PeriodicRefreshScheduler.startTimer] y
 * [PeriodicRefreshScheduler.tick] accesibles, las tres se prueban sin esperar un solo milisegundo
 * real. Este archivo existe como corrección de ese censo, no como añadido.
 */
@OptIn(ExperimentalTime::class)
class PeriodicRefreshTimerTest {

    private var reloj = 1_000L
    private var vueltasDelTimer = 0
    private var timerCancelado = false

    /** Instala el reloj y el temporizador falsos; devuelve el callback que el timer ejecutaría. */
    private fun conTimerFalso(): () -> Unit {
        var callback: (() -> Unit)? = null
        PeriodicRefreshScheduler.clockSecs = { reloj }
        PeriodicRefreshScheduler.startTimer = { cb ->
            callback = cb
            vueltasDelTimer++
            42
        }
        PeriodicRefreshScheduler.stopTimer = { timerCancelado = true }
        return { callback?.invoke() }
    }

    @BeforeTest
    fun estadoLimpio() {
        // El planificador es un `object`: sin este reseteo, `timersCreated` arrastraría las altas y
        // bajas de las demás clases de prueba que corren en la misma página del navegador.
        PeriodicRefreshScheduler.resetForTest()
        reloj = 1_000L
        vueltasDelTimer = 0
        timerCancelado = false
    }

    @AfterTest
    fun limpiar() {
        PeriodicRefreshScheduler.resetForTest()
        View.userSessionParams = null
    }

    /**
     * Diez registros, **un** temporizador. `timerRunning == true` sólo probaba que existe un handle;
     * si `ensureTimer` perdiera su guarda se crearía un `setInterval` por alta y los sobrantes
     * quedarían corriendo sin handle que los cancele — una fuga invisible desde la API pública.
     */
    @Test
    fun variosRegistrosCreanUnSoloTemporizador() {
        conTimerFalso()
        val tokens = (1..10).map { PeriodicRefreshScheduler.register({ 5 }) { } }

        assertEquals(1, PeriodicRefreshScheduler.timersCreated, "diez altas, un temporizador")

        tokens.forEach { PeriodicRefreshScheduler.unregister(it) }
        assertTrue(timerCancelado, "al irse el último registro el temporizador debe cancelarse")
        assertFalse(PeriodicRefreshScheduler.timerRunning)

        PeriodicRefreshScheduler.register({ 5 }) { }
        assertEquals(2, PeriodicRefreshScheduler.timersCreated, "vuelto a arrancar tras quedar vacío")
    }

    /**
     * Con umbral de inactividad declarado y el usuario quieto, el barrido no corre; en cuanto hay
     * señal de actividad, vuelve. Antes esto se medía contra `lastUiActivity` de *la instancia que
     * hubiera creado el temporizador*; ahora es un solo valor global.
     */
    @Test
    fun inactividadDetieneElBarridoYLaActividadLoReanuda() {
        val disparar = conTimerFalso()
        var corridas = 0
        PeriodicRefreshScheduler.register({ 0 }) { corridas++ }

        View.userSessionParams = UserSessionParams(
            inactivityUiSecsToNoRefresh = 30,
            inactivityUiSecsToLogout = null,
            sessionMaxSecs = null,
        )

        View.lastUiActivity = Clock.System.now() - 300.seconds
        reloj += 10
        disparar()
        assertEquals(0, corridas, "usuario quieto: no debe refrescarse")

        View.lastUiActivity = Clock.System.now()
        reloj += 10
        disparar()
        assertEquals(1, corridas, "con actividad reciente vuelve a refrescarse")
    }

    /** Sin `userSessionParams` no hay corte por inactividad, por más quieto que esté el usuario. */
    @Test
    fun sinParametrosDeSesionNoHayCorte() {
        val disparar = conTimerFalso()
        var corridas = 0
        PeriodicRefreshScheduler.register({ 0 }) { corridas++ }

        View.userSessionParams = null
        View.lastUiActivity = Clock.System.now() - 3600.seconds
        reloj += 10
        disparar()
        assertEquals(1, corridas)
    }

    /**
     * **El umbral configurado se respeta.** Con 5 s declarados y 30 s de inactividad, no refresca.
     *
     * El diseño anterior daba lo contrario: su `let` recibía `inactivityUiSecsToNoRefresh` y
     * comparaba contra un `60` literal, así que a los 30 s **seguía refrescando** aunque el ajuste
     * dijera 5. Pasó desapercibido veinte años porque el valor sembrado por omisión es 60 y ahí los
     * dos comportamientos coinciden — sólo una instalación que cambiara el número veía que su
     * número no hacía nada.
     */
    @Test
    fun umbralConfiguradoSeRespeta() {
        val disparar = conTimerFalso()
        var corridas = 0
        PeriodicRefreshScheduler.register({ 0 }) { corridas++ }

        View.userSessionParams = UserSessionParams(
            inactivityUiSecsToNoRefresh = 5,
            inactivityUiSecsToLogout = null,
            sessionMaxSecs = null,
        )

        View.lastUiActivity = Clock.System.now() - 30.seconds
        reloj += 10
        disparar()
        assertEquals(
            expected = 0,
            actual = corridas,
            message = "30 s de inactividad con umbral de 5 s: debe cortar, no esperar a los 60",
        )

        View.lastUiActivity = Clock.System.now() - 3.seconds
        reloj += 10
        disparar()
        assertEquals(1, corridas, "3 s de inactividad con umbral de 5 s: sigue refrescando")
    }

    /**
     * Un umbral mayor que el viejo literal también se respeta: a los 90 s con umbral de 120 s aún
     * refresca. Es la mitad complementaria de [umbralConfiguradoSeRespeta] — una prueba que sólo
     * mirara umbrales cortos pasaría igual con un `if (inactivo >= 60)` mal escrito.
     */
    @Test
    fun umbralLargoTampocoSeRecortaA60() {
        val disparar = conTimerFalso()
        var corridas = 0
        PeriodicRefreshScheduler.register({ 0 }) { corridas++ }

        View.userSessionParams = UserSessionParams(
            inactivityUiSecsToNoRefresh = 120,
            inactivityUiSecsToLogout = null,
            sessionMaxSecs = null,
        )

        View.lastUiActivity = Clock.System.now() - 90.seconds
        reloj += 10
        disparar()
        assertEquals(1, corridas, "90 s con umbral de 120 s: todavía dentro, debe refrescar")
    }

    /**
     * `0` desactiva el corte, con el mismo criterio que `sessionMaxSecs` usa en `IUserColl.kt:52`.
     * Sin esta guarda, `inactivo >= 0` sería siempre cierto y una configuración en cero apagaría el
     * refresco por completo — un modo de fallo que no existía antes de la corrección.
     */
    @Test
    fun umbralCeroDesactivaElCorte() {
        val disparar = conTimerFalso()
        var corridas = 0
        PeriodicRefreshScheduler.register({ 0 }) { corridas++ }

        View.userSessionParams = UserSessionParams(
            inactivityUiSecsToNoRefresh = 0,
            inactivityUiSecsToLogout = null,
            sessionMaxSecs = null,
        )

        View.lastUiActivity = Clock.System.now() - 3600.seconds
        reloj += 10
        disparar()
        assertEquals(1, corridas, "umbral 0 = desactivado: refresca aunque lleve una hora quieto")
    }

    /**
     * Una vuelta del temporizador no puede meterse dentro de otra. Un bloque que vuelve a entrar al
     * planificador —directa o indirectamente— encuentra la guarda `sweeping` y no recursa.
     *
     * Cubre el recorrido síncrono, que es lo único que esta guarda promete: no protege contra
     * peticiones RPC solapadas, porque un bloque puede lanzar una y retornar de inmediato.
     */
    @Test
    fun elBarridoNoSeReentra() {
        val disparar = conTimerFalso()
        var corridas = 0
        PeriodicRefreshScheduler.register({ 0 }) {
            corridas++
            PeriodicRefreshScheduler.tick()
        }

        reloj += 10
        disparar()
        assertEquals(1, corridas, "el tick anidado debe rebotar contra la guarda")

        // Y la guarda se libera al salir: si `sweeping` no volviera a `false` en el `finally`, el
        // planificador quedaría mudo para siempre después del primer barrido — un apagón peor que
        // el que este rediseño vino a corregir.
        reloj += 10
        disparar()
        assertEquals(2, corridas, "la siguiente vuelta debe correr: la guarda se libera al salir")
    }

    /** El temporizador respeta la cadencia: sólo dispara el bloque cuando venció su intervalo. */
    @Test
    fun elTemporizadorRespetaLaCadencia() {
        val disparar = conTimerFalso()
        var corridas = 0
        PeriodicRefreshScheduler.register({ 5 }) { corridas++ }

        reloj += 2
        disparar()
        assertEquals(0, corridas, "a los 2 s de un intervalo de 5 no corre")

        reloj += 4
        disparar()
        assertEquals(1, corridas, "a los 6 s sí")
    }
}
