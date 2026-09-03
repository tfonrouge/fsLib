package com.fonrouge.fullStack.view

import com.fonrouge.base.api.CallType
import com.fonrouge.base.api.CrudTask
import com.fonrouge.base.api.IApiFilter
import com.fonrouge.base.common.ICommonContainer
import com.fonrouge.fullStack.lib.completionToast
import com.fonrouge.fullStack.lib.rejectionToast
import com.fonrouge.base.model.BaseDoc
import com.fonrouge.base.state.ItemState
import com.fonrouge.fullStack.callItemService
import com.fonrouge.fullStack.config.ConfigViewContainer
import io.kvision.i18n.I18n.gettext
import io.kvision.modal.Confirm
import io.kvision.modal.ModalSize
import io.kvision.toast.Toast
import kotlin.time.ExperimentalTime

/**
 * An abstract class `ViewDataContainer` which extends from the `View`. This class is designed
 * to manage the configuration and periodic update of a view container.
 *
 * @param T The type of the data item must extend from `BaseDoc`.
 * @param ID The type out of the ID of a data item, which must be a non-nullable type.
 * @param FILT The type of the API filter used for querying, must extend `IApiFilter`.
 * @property configViewContainer The configuration object for the view container.
 */
@OptIn(ExperimentalTime::class)
abstract class ViewDataContainer<T : BaseDoc<ID>, ID : Any, FILT : IApiFilter<*>>(
    val configViewContainer: ConfigViewContainer<T, ID, *, FILT>,
) : View<FILT>(
    configView = configViewContainer,
) {
    companion object {
        /**
         * Pospone un intervalo el refresco de **todas** las tablas registradas, tras una interacción
         * del usuario. Conserva el nombre histórico porque es lo que llaman `TabulatorViewList` y
         * `fsTabulator`; la mecánica vive ahora en [PeriodicRefreshScheduler.postponeAll].
         */
        fun clearStartTime() = PeriodicRefreshScheduler.postponeAll()
    }

    /**
     * Determines whether the installation of periodic updates is allowed.
     * This variable can be toggled to enable or disable periodic updates,
     * influencing the behavior of update-related operations within the system.
     */
    var allowInstallPeriodicUpdate: Boolean = true

    private var periodicUpdate = true
    abstract fun dataUpdate()

    open val onPeriodicDataUpdate: (() -> Unit)? = {
        dataUpdate()
    }

    /**
     * Displays a confirmation dialog for deleting an item and processes the delete operation
     * based on user interaction. If confirmed, it attempts to delete the specified item
     * using the provided API function and callbacks for handling success or failure scenarios.
     *
     * @param apiItemFun The API function to be used for the delete operation.
     * @param item The item to be deleted.
     * @param apiFilter An API filter instance used for filtering the delete operation. Defaults to a common filter instance.
     * @param onFail A callback invoked when the delete operation fails, providing the resulting [ItemState].
     * @param onSuccess A callback invoked when the delete operation succeeds.
     */
    fun confirmDeleteView(
        apiItemFun: Function<*>,
        item: T,
        apiFilter: FILT = configViewContainer.commonContainer.apiFilterInstance(),
        onFail: ((ItemState<T>) -> Unit)? = null,
        onSuccess: (() -> Unit)? = null,
    ) {
        configViewContainer.commonContainer.callItemService(
            apiItemFun = apiItemFun,
            crudTask = CrudTask.Delete,
            callType = CallType.Query,
            id = item._id,
            item = item,
            apiFilter = apiFilter,
        ) { itemState ->
            // The delete pre-check. `isRejected` rather than `isWriteComplete`: this is a Query, so
            // there is no write for `noDataModified` to describe — a Warn here means the check said
            // no (dependent records, for instance), and used to open the confirmation anyway.
            if (itemState.isRejected.not()) {
                val numSelectedRows = if (this is ViewList<*, *, *, *>)
                    tabulator?.getSelectedRows()?.size ?: 0 else null
                val deleteWord = gettext("Delete")
                val text = if (numSelectedRows != null && numSelectedRows > 0) {
                    "<b>$deleteWord</b> $numSelectedRows selected '<i>${configViewContainer.commonContainer.labelItem}</i>', id: <b>${
                        configViewContainer.commonContainer.labelId(item)
                    }</b> ?"
                } else "<b>$deleteWord</b> '<i>${configViewContainer.commonContainer.labelItem}</i>', id: <b>${
                    configViewContainer.commonContainer.labelId(item)
                }</b> ?"
                val modal = Confirm(
                    caption = "Please Confirm",
                    text = text,
                    rich = true,
                    size = ModalSize.XLARGE,
                    centered = true,
                    noTitle = "Cancel",
                    noCallback = {
                        Toast.warning("Delete canceled")
                    },
                    yesCallback = {
                        configViewContainer.commonContainer.callItemService(
                            apiItemFun = apiItemFun,
                            crudTask = CrudTask.Delete,
                            callType = CallType.Action,
                            id = item._id,
                            item = item,
                            apiFilter = apiFilter,
                        ) { itemState1 ->
                            // `hasError` covers only State.Error, so a State.Warn refusal used to
                            // run `onSuccess` and report the delete as done. Routed through the
                            // shared state-to-toast path: correct severity, translated fallbacks.
                            if (itemState1.isWriteComplete) {
                                itemState1.completionToast()
                                onSuccess?.invoke()
                            } else {
                                itemState1.rejectionToast()
                                onFail?.invoke(itemState1)
                            }
                            itemState1
                        }
                    }
                )
                modal.show()
            } else {
                // A refused pre-check is something to read and act on, not a toast that fades.
                itemState.rejectionToast()
                onFail?.invoke(itemState)
            }
            itemState
        }
    }

    /**
     * Token de este contenedor en [PeriodicRefreshScheduler]; `null` mientras no está registrado.
     * Es el único que puede darlo de baja — antes cualquier vista al destruirse borraba el mapa
     * completo, y con él el refresco de todas las demás.
     */
    private var periodicToken: Int? = null

    /**
     * Da de alta el refresco periódico de este contenedor. **Idempotente**: llamarlo otra vez sobre
     * la misma instancia —como hacen `fsTabulator` en cada render y `ViewItem` en cada carga—
     * conserva el registro que ya tiene en lugar de apilar duplicados.
     *
     * Un contenedor que se excluye (`periodicUpdateDataView != true`) simplemente no se registra.
     * Antes se metía al mapa igual y terminaba refrescándose cuando el temporizador de **otra**
     * vista estaba corriendo: la bandera sólo decidía quién creaba el temporizador, no quién se
     * refrescaba. Ahora la bandera significa lo que dice su nombre.
     *
     * ## Cuándo se lee la bandera
     *
     * `periodicUpdateDataView` es un `var` en `ViewList` y `ViewItem`, así que puede cambiar después
     * del montaje, y el contrato es asimétrico a propósito:
     *
     * - **`true` → `false` en caliente**: surte efecto de inmediato. El registro sigue ahí, pero el
     *   bloque consulta la bandera **en vivo** en cada vuelta y no hace nada.
     * - **`false` → `true` en caliente**: no surte efecto solo. Un contenedor excluido no ocupa un
     *   token —si lo ocupara mantendría vivo el temporizador compartido sin refrescar nada—, así
     *   que empieza a refrescarse en la siguiente llamada a [installUpdate], que es lo que hacen
     *   `fsTabulator` en cada render y `ViewItem` en cada carga de item.
     *
     * En la práctica las vistas la fijan antes de montarse, que es el caso para el que se diseñó.
     */
    fun installUpdate() {
        if (periodicToken != null) return
        if (periodicUpdateDataView != true) return
        val block = onPeriodicDataUpdate ?: return
        periodicToken = PeriodicRefreshScheduler.register(
            intervalSecs = { periodicUpdateViewInterval },
            block = { if (periodicUpdate && periodicUpdateDataView == true) block.invoke() },
        )
    }

    /**
     * Da de baja **sólo** el registro de este contenedor. Seguro de llamar dos veces; lo invocan
     * [onBeforeDispose] y el hook de dispose del panel que monta la tabla en `fsTabulator`.
     */
    fun uninstallUpdate() {
        PeriodicRefreshScheduler.unregister(periodicToken)
        periodicToken = null
    }

    /**
     * Open function that allows to override the default action when the [apiFilterObservable] observable changes.
     * The default action will do an [updateBanner] and then an [dataUpdate]
     */
    override fun onApiFilterChange() {
        super.onApiFilterChange()
        dataUpdate()
    }

    /**
     * Suelta **únicamente** el registro de este contenedor. Antes hacía `handleInterval = null`,
     * cuyo `set` mataba el temporizador compartido y vaciaba las funciones de refresco de todas las
     * demás vistas: ése era el defecto.
     */
    override fun onBeforeDispose() {
        super.onBeforeDispose()
        uninstallUpdate()
    }

    /**
     * Resumes periodic updates by setting the `periodicUpdate` flag to `true`.
     *
     * This method is used to re-enable the periodic update mechanism within the
     * update lifecycle management in `ViewDataContainer` after it has been suspended.
     */
    @Suppress("unused")
    fun resumePeriodicUpdate() {
        periodicUpdate = true
    }

    /**
     * Suspends periodic updates by setting the `periodicUpdate` flag to `false`.
     *
     * This method temporarily disables the periodic update mechanism within
     * the update lifecycle management of `ViewDataContainer`.
     */
    @Suppress("unused")
    fun suspendPeriodicUpdate() {
        periodicUpdate = false
    }
}
