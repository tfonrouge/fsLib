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
import kotlinx.browser.window
import kotlin.js.Date
import kotlin.time.Clock
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
        var startTime = 0L
        val dataUpdateFuncs = HashMap<Pair<Int, String>, () -> Unit>()
        var handleInterval: Int? = null
            set(value) {
                field?.let {
                    window.clearInterval(it)
                    dataUpdateFuncs.clear()
                }
                field = value
            }

        fun clearStartTime() {
            startTime = (Date().getTime() / 1000).toLong()
        }
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

    fun runPeriodicBlock() {
        try {
//            console.warn("dataUpdateFuncs", dataUpdateFuncs.map { it.key }.toObj())
            dataUpdateFuncs.forEach {
//                console.warn("callBlock", it.key, it.value.toString().substringBefore("("))
                it.value.invoke()
            }
        } catch (e: Exception) {
            console.error("Error on runPeriodicBlock(): ", e)
        }
    }

    fun installUpdate() {
//        console.warn("installUpdate", this.hashCode(), this::class.simpleName, periodicUpdateDataView)
        onPeriodicDataUpdate?.let {
            dataUpdateFuncs[this.hashCode() to (this::class.simpleName ?: "?")] = it
        }
        if (handleInterval == null && periodicUpdateDataView == true) {
            var lock = false
            handleInterval = window.setInterval(
                handler = {
                    if (periodicUpdate) {
                        val curTime = (Date().getTime() / 1000).toLong()
                        val inactivityUiSecs = userSessionParams?.inactivityUiSecsToNoRefresh?.let {
                            (Clock.System.now() - lastUiActivity).inWholeSeconds
                        }
                        if ((curTime - startTime) >= periodicUpdateViewInterval && (inactivityUiSecs == null || inactivityUiSecs < 60L)) {
                            if (!lock) {
                                startTime = curTime
                                lock = true
                                runPeriodicBlock()
                                lock = false
                            }
                        }
                    }
                },
                timeout = 250,
            )
        }
    }

    /**
     * Open function that allows to override the default action when the [apiFilterObservable] observable changes.
     * The default action will do an [updateBanner] and then an [dataUpdate]
     */
    override fun onApiFilterChange() {
        super.onApiFilterChange()
        dataUpdate()
    }

    override fun onBeforeDispose() {
        super.onBeforeDispose()
        handleInterval = null
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
