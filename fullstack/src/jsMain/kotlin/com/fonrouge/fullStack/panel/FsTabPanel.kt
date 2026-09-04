package com.fonrouge.fullStack.panel

import io.kvision.core.Container
import io.kvision.panel.SideTabSize
import io.kvision.panel.TabPanel
import io.kvision.panel.TabPosition

/**
 * Un [TabPanel] que **sí destruye sus pestañas** al destruirse.
 *
 * ## El agujero que tapa
 *
 * `TabPanel` guarda sus pestañas en una lista `tabs` propia: `addTab` hace `tab.parent = nav` y
 * las mete ahí, **no** a `children` ni a `privateChildren`. Y sobrescribe `disposeAll()` pero
 * **no `dispose()`**. Como `SimplePanel.dispose()` sólo recorre `children` y `privateChildren`,
 * al destruir un `TabPanel` **las pestañas y todo su contenido quedan fuera del recorrido**: nunca
 * reciben `dispose()`, y por lo tanto ningún `addBeforeDisposeHook` colgado dentro de una pestaña
 * llega a dispararse.
 *
 * Es un defecto de ciclo de vida de KVision, general y no específico de fsLib: afecta a cualquier
 * suscripción, temporizador o limpieza registrada dentro de una pestaña.
 *
 * ## Cómo se detectó
 *
 * Midiendo llamadas RPC en la app consumidora. Con una ficha abierta en un modal corrían cuatro
 * refrescos periódicos a cadencia exacta de 5 s; al cerrar el modal, el del propio item y el del
 * grafo se daban de baja, pero **la lista embebida dentro de una pestaña seguía pidiendo datos**, y
 * cada nueva activación de esa pestaña sumaba otra llamada por ciclo.
 *
 * El defecto era **latente**: el planificador anterior, al destruirse cualquier vista, vaciaba el
 * registro completo de callbacks, así que aniquilaba el huérfano junto con el refresco de todas las
 * demás vistas. Al pasar a propiedad por token —donde cada quien da de baja sólo lo suyo— el
 * huérfano quedó a la vista. Los dos defectos se tapaban mutuamente.
 *
 * ## Por qué no basta con `tabs.forEach { it.dispose() }`
 *
 * Eso dispara los hooks pero deja la lista `tabs` poblada y los `parent` colgando, así que mientras
 * alguien referencie el panel se conserva vivo un grafo de componentes ya destruidos. Hace falta
 * soltar el `parent` y vaciar la lista — sin pasar por `removeTab`, por lo que explica [dispose].
 *
 * Y no se reutiliza `disposeAll()`: su implementación termina en `removeAll()`, que hace
 * `tabs.forEach { removeTab(it) }`, o sea que itera la misma lista que `removeTab` está mutando.
 *
 * ## Cuándo retirar esta clase
 *
 * En cuanto KVision sobrescriba `dispose()` en `TabPanel`. Si eso ocurre y esta clase sigue en pie,
 * las pestañas se destruirían dos veces; [dispose] es idempotente de este lado, pero la condición a
 * vigilar es la de KVision. Verificado contra **KVision 9.5.0 y 9.6.0**.
 */
open class FsTabPanel(
    tabPosition: TabPosition = TabPosition.TOP,
    sideTabSize: SideTabSize = SideTabSize.SIZE_3,
    scrollableTabs: Boolean = false,
    draggableTabs: Boolean = false,
    className: String? = null,
    init: (TabPanel.() -> Unit)? = null,
) : TabPanel(tabPosition, sideTabSize, scrollableTabs, draggableTabs, className, init) {

    /**
     * Cuántas pestañas sigue reteniendo el panel. Existe para que las pruebas puedan afirmar que
     * la limpieza vació la lista `tabs` —que es `protected` y no aparece en `getChildren()`, así
     * que sin esto la afirmación sería vacua—.
     */
    internal val pestanasVivas: Int get() = tabs.size

    /**
     * Destruye cada pestaña, suelta su `parent`, vacía la lista y luego delega.
     *
     * ## Por qué NO se usa `removeTab`
     *
     * La versión anterior llamaba `removeTab(tab)` en el bucle, que además de sacar la pestaña
     * limpia `activeIndex` — parecía lo correcto y es lo que uno escribe primero. Pero `removeTab`
     * asigna `activeIndex` en sus **tres** ramas (incluida la auto-asignación `activeIndex =
     * activeIndex`, que no es no-op porque el setter tiene efectos), y ese setter hace
     * `tabs.forEach { it.link.removeCssClass("active") }`: una pasada sobre todas las pestañas
     * restantes. Y `Widget.removeCssClass` (`Widget.kt:570`) termina en `refresh()`, que sube a
     * `Root.reRender()` (`Root.kt:223`), o sea **un patch síncrono del árbol completo del root**.
     *
     * Con el bucle antes de `super.dispose()` —que es el orden obligado, ver abajo— el panel sigue
     * colgado del root, así que cada uno de esos `refresh()` llega de verdad al patch. Resultado:
     * del orden de N² re-renders del árbol entero al cerrar una ficha, más N eventos `changeTab`
     * terminando en `-1` que cualquier oyente que persista la pestaña activa grabaría como basura.
     * *Con 6 pestañas —el máximo del consumidor— son ~36 patches del root por cierre.*
     *
     * `dispose()` + `clearParent()` deja el mismo estado observable sin tocar el setter. Es además
     * el idiom que el propio KVision ya usa para colecciones propias de componentes: ver
     * `MdWidget.dispose()` (`MdWidget.kt:64`), que hace `super.dispose()` y luego
     * `onEach { dispose(); clearParent() }.clear()`.
     *
     * ## Por qué el bucle va ANTES de `super.dispose()`
     *
     * Al revés sería más barato todavía —con el panel ya desenganchado los `refresh()` no alcanzan
     * el root— pero rompería el día que KVision corrija `TabPanel`: su `dispose()` destruiría las
     * pestañas y este bucle las destruiría **otra vez**. Y `Widget.dispose()` (`Widget.kt:795-803`)
     * limpia `afterDestroyHooks` tras ejecutarlos pero **no** `beforeDisposeHooks`, así que los
     * hooks correrían dos veces. Vaciando `tabs` antes de delegar, el `super.dispose()` de un
     * `TabPanel` ya corregido no encuentra nada — la convivencia es segura en las dos direcciones.
     *
     * Llamarlo dos veces es inofensivo: la segunda vuelta no encuentra pestañas.
     */
    override fun dispose() {
        tabs.toList().forEach { tab ->
            tab.dispose()
            tab.clearParent()
        }
        tabs.clear()
        super.dispose()
    }
}

/**
 * Constructor DSL de [FsTabPanel]. Mismos parámetros que `io.kvision.panel.tabPanel`, del que es
 * reemplazo directo: cambiar la llamada y el import es todo lo que hace falta.
 */
fun Container.fsTabPanel(
    tabPosition: TabPosition = TabPosition.TOP,
    sideTabSize: SideTabSize = SideTabSize.SIZE_3,
    scrollableTabs: Boolean = false,
    draggableTabs: Boolean = false,
    className: String? = null,
    init: (TabPanel.() -> Unit)? = null,
): FsTabPanel {
    val panel = FsTabPanel(tabPosition, sideTabSize, scrollableTabs, draggableTabs, className, init)
    this.add(panel)
    return panel
}
