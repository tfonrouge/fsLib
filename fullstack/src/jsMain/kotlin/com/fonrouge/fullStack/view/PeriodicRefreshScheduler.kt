package com.fonrouge.fullStack.view

import io.kvision.core.Widget
import kotlinx.browser.window
import kotlin.js.Date
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Planificador único de los refrescos periódicos de la UI: **un registro por función de refresco**,
 * identificado por un *token* que sólo su dueño puede dar de baja.
 *
 * ## Qué estaba mal antes
 *
 * La idea de indexar cada función de refresco ya existía —el mapa vivía en `ViewDataContainer` con
 * clave `hashCode() to simpleName`— pero **registro, temporizador y vida útil estaban trenzados**:
 *
 * - el `setInterval` lo creaba *el primer contenedor que llegara*, y su clausura capturaba los
 *   campos de **esa** instancia (`periodicUpdate`, `periodicUpdateViewInterval`, `lastUiActivity`);
 * - `handleInterval` tenía un `set` que, al recibir `null`, hacía `clearInterval` **y vaciaba el
 *   mapa entero**;
 * - `onBeforeDispose()` hacía exactamente eso.
 *
 * Consecuencia: **cerrar cualquier vista apagaba el refresco de todas las demás**. Y un único
 * `startTime` global significaba que el turno de una tabla posponía el de las otras, así que dos
 * tablas en la misma vista nunca fueron realmente independientes.
 *
 * *Medición en mppArel antes del cambio (lista embebida, ventanas silenciosas de 16 s): **6** llamadas
 * RPC en la primera apertura, luego **1** y **1** al cerrar y reabrir. Esa serie descendente es la
 * firma falsable del defecto — no acumulación, sino colapso.*
 *
 * ## La forma de ahora
 *
 * - [register] devuelve un **token**; [unregister] borra esa entrada y ninguna otra. Ningún
 *   participante puede tirar el registro de otro, ni siquiera al destruirse.
 * - El temporizador es del planificador, no de una vista: su handler no captura ninguna instancia.
 *   Arranca con el primer registro y se detiene **sólo cuando se va el último**.
 * - Cada entrada lleva su propio intervalo (leído en vivo) y su propio `lastRun`, así que N tablas
 *   en una misma vista corren **cada una a su cadencia**. La independencia llega hasta ahí: el
 *   aplazamiento por interacción ([postponeAll], que es lo que llama `clearStartTime`) **sigue
 *   siendo global** —tocar una tabla pospone a todas—. Se conserva así por compatibilidad con el
 *   comportamiento anterior; hacerlo por token pediría un `postpone(token)` y una decisión
 *   explícita de cuándo usarlo.
 * - [runDueBlocks] itera sobre una **copia**: un bloque que destruye una vista —y por tanto se da de
 *   baja— durante el barrido no corrompe la iteración.
 * - Un bloque que lanza excepción queda aislado: se registra en consola y los demás igual corren.
 *
 * Nadie fuera de este archivo puede parar el temporizador ni vaciar el registro; ésa es justamente
 * la propiedad que faltaba.
 */
@OptIn(ExperimentalTime::class)
object PeriodicRefreshScheduler {

    /**
     * Una función de refresco registrada.
     *
     * @param intervalSecs proveedor —no valor fijo— porque `View.periodicUpdateViewInterval` es un
     *   `var` que la vista puede cambiar después de registrarse; el diseño anterior también lo leía
     *   en cada vuelta y no hay razón para congelarlo aquí.
     */
    private class Registro(
        val block: () -> Unit,
        val intervalSecs: () -> Int,
        var lastRun: Long,
    )

    private val registros = LinkedHashMap<Int, Registro>()
    private var nextToken = 1
    private var handleInterval: Int? = null
    private var sweeping = false

    // --- Seams de prueba -----------------------------------------------------------------------
    // El reloj y el temporizador se inyectan porque son lo único que ata este objeto al navegador.
    // Sin ellos, tres guardas —que se cree UN solo temporizador, la pausa por inactividad y la
    // reentrancia del barrido— sólo se podrían ejercitar esperando segundos reales, que es otra
    // forma de decir que no se ejercitan.

    /** Reloj en segundos. Inyectable para avanzar el tiempo sin esperarlo. */
    internal var clockSecs: () -> Long = { (Date().getTime() / 1000).toLong() }

    /** Arranca el temporizador de 250 ms y devuelve su handle. */
    internal var startTimer: (() -> Unit) -> Int =
        { cb -> window.setInterval(handler = cb, timeout = 250) }

    /** Cancela el temporizador. */
    internal var stopTimer: (Int) -> Unit = { window.clearInterval(it) }

    /** Cuántos temporizadores se han creado. Debe subir a lo sumo uno por racha de registros. */
    internal var timersCreated = 0
        private set

    /** Devuelve los seams a su implementación de navegador y vacía el registro. */
    internal fun resetForTest() {
        handleInterval?.let { stopTimer(it) }
        handleInterval = null
        registros.clear()
        sweeping = false
        timersCreated = 0
        clockSecs = { (Date().getTime() / 1000).toLong() }
        startTimer = { cb -> window.setInterval(handler = cb, timeout = 250) }
        stopTimer = { window.clearInterval(it) }
    }

    /** Cuántas funciones de refresco hay registradas ahora mismo. */
    val registrationCount: Int get() = registros.size

    /** `true` mientras el temporizador compartido corre — debe correr exactamente si hay registros. */
    val timerRunning: Boolean get() = handleInterval != null

    /** Reloj en segundos; el mismo que usaba el diseño anterior. */
    fun nowSecs(): Long = clockSecs()

    /**
     * Registra [block] para correr cada `intervalSecs()` segundos y devuelve el token que lo posee.
     * Arranca el temporizador compartido si éste es el primer registro.
     */
    fun register(intervalSecs: () -> Int, block: () -> Unit): Int {
        val token = nextToken++
        registros[token] = Registro(block = block, intervalSecs = intervalSecs, lastRun = nowSecs())
        ensureTimer()
        return token
    }

    /**
     * Da de baja el registro que posee [token] —no-op si es `null` o si ya no está— y detiene el
     * temporizador cuando no queda nada que correr.
     */
    fun unregister(token: Int?) {
        if (token == null) return
        registros.remove(token) ?: return
        if (registros.isEmpty()) {
            handleInterval?.let { stopTimer(it) }
            handleInterval = null
        }
    }

    /**
     * Pospone **todos** los registros un intervalo completo. Se llama tras una interacción del
     * usuario (hover sobre una fila, por ejemplo) para que un refresco automático no le mueva la
     * tabla debajo del cursor. Es el equivalente exacto del viejo `clearStartTime()` global.
     */
    fun postponeAll() {
        val now = nowSecs()
        registros.values.forEach { it.lastRun = now }
    }

    /**
     * Corre las entradas cuyo intervalo ya venció, sobre una **copia** del registro.
     *
     * [now] es parámetro para que las pruebas puedan avanzar el reloj sin esperar segundos reales.
     */
    fun runDueBlocks(now: Long = nowSecs()) {
        // La copia es de **llaves**, no de entradas: en Kotlin/JS `entries.toList()` devuelve
        // referencias vivas a las entradas del mapa, y leerles la llave después de que el mapa
        // cambió lanza `ConcurrentModificationException`. Copiar los tokens y volver a buscar cada
        // uno resuelve las dos cosas de una vez: la iteración queda desacoplada del mapa y un
        // registro dado de baja por un bloque anterior de este mismo barrido simplemente ya no está.
        registros.keys.toList().forEach { token ->
            val reg = registros[token] ?: return@forEach
            if (now - reg.lastRun < reg.intervalSecs()) return@forEach
            // `lastRun` se marca ANTES de invocar: si el bloque lanza, no queda reintentando en
            // cada vuelta de 250 ms.
            reg.lastRun = now
            try {
                reg.block.invoke()
            } catch (e: Exception) {
                console.error("Error en refresco periódico (token $token): ", e)
            }
        }
    }

    private fun ensureTimer() {
        if (handleInterval != null) return
        timersCreated++
        handleInterval = startTimer { tick() }
    }

    /**
     * Una vuelta del temporizador compartido. `internal` para que las pruebas puedan ejercitar la
     * compuerta de inactividad y la reentrancia sin depender de que pasen 250 ms de verdad.
     *
     * `sweeping` protege **el recorrido síncrono**: impide que una vuelta se meta dentro de otra.
     * No dice nada sobre peticiones RPC en vuelo — un bloque puede lanzar una petición y retornar
     * de inmediato, y este planificador no las conoce ni las cancela.
     */
    internal fun tick() {
        if (sweeping) return
        // Compuerta de inactividad: si la sesión declara un umbral y el usuario lleva ese rato
        // quieto, se deja de jalar datos.
        //
        // El diseño anterior **descartaba el umbral configurado**: su `let` recibía
        // `inactivityUiSecsToNoRefresh` y comparaba contra un `60` literal, así que el ajuste
        // funcionaba sólo como interruptor y el corte era siempre a los 60 s. Pasó desapercibido
        // porque el valor que siembra `IUserSessionParamsColl` es justamente 60: para la
        // configuración por omisión, ambos comportamientos coinciden. Sólo una instalación que
        // hubiera cambiado el número notaba que su número no servía para nada.
        //
        // `0` significa desactivado, siguiendo el mismo criterio que `sessionMaxSecs` en
        // `IUserColl.kt:52` y en `userSessionInfoModal.kt:70`.
        val umbralSecs = View.userSessionParams?.inactivityUiSecsToNoRefresh
        if (umbralSecs != null && umbralSecs > 0) {
            val inactivoSecs = (Clock.System.now() - View.lastUiActivity).inWholeSeconds
            if (inactivoSecs >= umbralSecs) return
        }
        sweeping = true
        try {
            runDueBlocks()
        } finally {
            sweeping = false
        }
    }
}

/**
 * Ata el registro de refresco periódico de [container] al ciclo de vida de **este widget**, que es
 * el que de verdad está montado en el DOM.
 *
 * Hace falta porque una lista embebida nunca pasa por `View.startDisplayPage` y por lo tanto nunca
 * recibe `onBeforeDispose()`: sin este enganche, una tabla reconstruida dentro de un `bind { … }`
 * dejaría su registro anterior vivo para siempre y las llamadas se acumularían en cada
 * republicación. `bind` hace `disposeAll()` al reconstruir, y eso dispara el hook.
 *
 * Existe como función con nombre —en vez de un `addBeforeDisposeHook` suelto dentro de
 * `fsTabulator`— para que la prueba de montaje y destrucción ejercite **este mismo código** y no
 * una reproducción del patrón. Lo único que queda fuera de esa prueba es el sitio de llamada.
 */
internal fun Widget.ownPeriodicUpdateOf(container: ViewDataContainer<*, *, *>) {
    addBeforeDisposeHook { container.uninstallUpdate() }
}
