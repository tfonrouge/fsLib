package com.fonrouge.fullStack.config

import com.fonrouge.base.api.ApiFilter
import com.fonrouge.base.common.ICommon
import com.fonrouge.fullStack.view.View
import io.kvision.core.Container
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **P1.1-fsLib — caracteriza la REGLA de derivación de `baseUrl`** (blueprint
 * `navigable-destinations`, C2 / D1). Verde contra el código de hoy: no exige ningún cambio.
 *
 * **Por qué hace falta acá y no basta con el consumidor.** mppArel tiene un test que ancla cada view
 * en `viewKClass.simpleName` — pero **ninguno de sus 15 `ConfigView` planos ejercita la rama
 * divergente**: en los 15, `"View" + commonContainer.name` coincide con el nombre de la clase por
 * convención de nomenclatura. O sea que la regla que este archivo fija está **sin ejercitar por el
 * único consumidor que la testea**: si alguien cambiara la derivación de `ConfigView` a
 * `viewKClass.simpleName`, los tests de mppArel **seguirían verdes** y sus URLs se moverían igual.
 *
 * El fixture existe justamente para forzar esa divergencia: [CommonZzzDivergente] da
 * `name = "ZzzDivergente"` mientras la clase del view es `ViewSintetico`, así que las dos reglas
 * posibles dan resultados distintos y el test puede decir cuál está vigente.
 *
 * **Por qué importa** (`ConfigView.kt:52`): `url` es `"#/" + baseUrl`. Cambiar la derivación **mueve
 * la URL** de todo view afectado — bookmarks y links citados en documentación apuntan en silencio a
 * otro lado. Un rename de clase rompe el compilado; un cambio de derivación no rompe nada. Este test
 * es lo que lo rompe.
 */
class BaseUrlDerivationTest {

    /** `ICommon.name` = `simpleName` sin el prefijo `Common` ⇒ "ZzzDivergente", distinto del view. */
    private data object CommonZzzDivergente : ICommon<ApiFilter>(
        label = "contenedor de prueba",
        filterKClass = ApiFilter::class,
    )

    private class ViewSintetico(configView: ConfigView<*, ApiFilter>) : View<ApiFilter>(configView) {
        override fun Container.displayPage() = Unit
    }

    /** El registro es global: sin esto, los fixtures contaminarían a quien corra después. */
    @AfterTest
    fun limpiar() = ViewRegistry.reset()

    @Test
    fun `un ConfigView plano deriva su baseUrl del CONTAINER, no del nombre de la clase del view`() {
        val cfg = object : ConfigView<ViewSintetico, ApiFilter>(
            commonContainer = CommonZzzDivergente,
            viewKClass = ViewSintetico::class,
        ) {}

        assertEquals(
            "ViewZzzDivergente", cfg.baseUrl,
            "un ConfigView plano deriva `\"View\" + commonContainer.name`",
        )
        assertEquals(
            "#/ViewZzzDivergente", cfg.url,
            "y la URL publicada es `\"#/\" + baseUrl`",
        )
    }

    @Test
    fun `de ahi que renombrar un Common mueva la URL sin tocar la clase del view`() {
        // La consecuencia operativa de la regla de arriba, escrita como aserción para que no haya que
        // deducirla: el nombre de la clase del view NO participa en la URL de un ConfigView plano.
        val cfg = object : ConfigView<ViewSintetico, ApiFilter>(
            commonContainer = CommonZzzDivergente,
            viewKClass = ViewSintetico::class,
        ) {}

        assertEquals(
            "ViewSintetico", ViewSintetico::class.simpleName,
            "sanity: el fixture sí diverge (si esto cambia, el test dejó de probar lo que dice)",
        )
        assertEquals(
            false, cfg.baseUrl == ViewSintetico::class.simpleName,
            "la clase del view NO determina la URL de un ConfigView plano — ésta es la asimetría " +
                    "contra `ConfigViewList`/`ConfigViewItem`, que sí usan `viewKClass.simpleName!!` " +
                    "(`ConfigViewList.kt:53`, `ConfigViewItem.kt`). Es exactamente lo que D1 decide.",
        )
    }

    @Test
    fun `un baseUrl explicito gana sobre la derivacion`() {
        // Es lo que usan las 2 excepciones deliberadas de mppArel (`ViewHome` -> "", la raíz;
        // `ViewBolsaTrabajo` -> "bolsaDeTrabajo"). `ViewHome` -> "" es lo que refuta la opción (a)
        // de D1: unificar en `simpleName` movería la raíz de la app a `#/ViewHome`.
        val cfg = object : ConfigView<ViewSintetico, ApiFilter>(
            commonContainer = CommonZzzDivergente,
            viewKClass = ViewSintetico::class,
            _baseUrl = "urlAmigable",
        ) {}

        assertEquals("urlAmigable", cfg.baseUrl)
        assertEquals("#/urlAmigable", cfg.url)
    }

    @Test
    fun `un baseUrl explicito vacio produce la raiz`() {
        // El caso `ViewHome`. Sin esto, nada en fsLib documenta que "" es legal y significa `#/`.
        val cfg = object : ConfigView<ViewSintetico, ApiFilter>(
            commonContainer = CommonZzzDivergente,
            viewKClass = ViewSintetico::class,
            _baseUrl = "",
        ) {}

        assertEquals("", cfg.baseUrl)
        assertEquals("#/", cfg.url, "`\"\"` es la raíz de la app, no un bug")
    }
}
