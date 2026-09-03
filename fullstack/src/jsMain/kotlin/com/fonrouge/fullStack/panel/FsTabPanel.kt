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
 * alguien referencie el panel se conserva vivo un grafo de componentes ya destruidos. Se usa
 * [removeTab] —que además corrige `activeIndex`— **sobre una copia** de la lista.
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
     * Destruye cada pestaña y luego delega. Iterar una copia es obligatorio: [removeTab] muta
     * `tabs`. Llamarlo dos veces es inofensivo — la segunda vuelta no encuentra pestañas.
     */
    override fun dispose() {
        tabs.toList().forEach { tab ->
            removeTab(tab)
            tab.dispose()
        }
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
