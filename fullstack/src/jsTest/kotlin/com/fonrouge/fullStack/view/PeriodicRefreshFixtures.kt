package com.fonrouge.fullStack.view

import com.fonrouge.base.api.ApiFilter
import com.fonrouge.base.common.ICommonContainer
import com.fonrouge.base.model.BaseDoc
import com.fonrouge.fullStack.config.ConfigViewContainer
import io.kvision.core.Container
import kotlinx.serialization.Serializable

/** Documento mínimo: sólo necesita `_id` para que `ICommonContainer` derive su serializador. */
@Serializable
internal data class DocSintetico(override val _id: String = "x") : BaseDoc<String>

internal object CommonSintetico : ICommonContainer<DocSintetico, String, ApiFilter>(
    itemKClass = DocSintetico::class,
    filterKClass = ApiFilter::class,
)

internal object ConfigSintetico :
    ConfigViewContainer<DocSintetico, String, ContenedorSintetico, ApiFilter>(
        commonContainer = CommonSintetico,
        viewKClass = ContenedorSintetico::class,
    )

/**
 * Contenedor de datos real —no un doble— para ejercitar `installUpdate` / `uninstallUpdate` y el
 * enganche de destrucción. Cuenta sus refrescos en [refrescos].
 */
internal class ContenedorSintetico(
    override var periodicUpdateDataView: Boolean? = true,
    sinBloqueDeRefresco: Boolean = false,
) : ViewDataContainer<DocSintetico, String, ApiFilter>(ConfigSintetico) {
    var refrescos = 0

    override val onPeriodicDataUpdate: (() -> Unit)? =
        if (sinBloqueDeRefresco) null else ({ dataUpdate() })

    override fun dataUpdate() {
        refrescos++
    }

    override fun Container.displayPage() = Unit
}
