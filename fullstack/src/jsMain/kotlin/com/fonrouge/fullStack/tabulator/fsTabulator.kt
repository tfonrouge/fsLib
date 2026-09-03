package com.fonrouge.fullStack.tabulator

import com.fonrouge.base.api.ApiList
import com.fonrouge.base.api.IApiFilter
import com.fonrouge.base.common.ICommonContainer
import com.fonrouge.base.model.BaseDoc
import com.fonrouge.fullStack.layout.centeredMessage
import com.fonrouge.fullStack.layout.toolBarList
import com.fonrouge.fullStack.view.ViewDataContainer
import com.fonrouge.fullStack.view.ownPeriodicUpdateOf
import com.fonrouge.fullStack.view.ViewItem
import com.fonrouge.fullStack.view.ViewList
import io.kvision.core.*
import io.kvision.panel.vPanel
import io.kvision.state.bind
import io.kvision.tabulator.TableType
import io.kvision.tabulator.TabulatorOptions
import io.kvision.tabulator.js.Tabulator.RowComponent
import io.kvision.utils.px
import io.kvision.utils.vh
import kotlinx.browser.window
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.json.Json
import org.w3c.dom.events.Event

/**
 * Configures and applies the Tabulator rendering for a given `ViewList` object.
 *
 * @param viewList The `ViewList` object to which the Tabulator functionality will be applied. This represents the dataset and configurations for the table grid.
 * @param masterViewItem An optional parent `ViewItem`, which acts as a master-detail container. Used for sharing CRUD tasks and relationships between items.
 * @param tabulatorOptions Options for configuring the Tabulator component, such as pagination, sorting, or formatting. By default, it uses `viewList.defaultTabulatorOptions`.
 * @param types A set of table styles like striped, bordered, hoverable, etc. Defaults to a set containing `TableType.STRIPED`, `TableType.BORDERED`, `TableType.HOVER`, and `Table
 * Type.SMALL`.
 * @param minToolbarSize A flag indicating whether to minimize the size of the toolbar. Defaults to `true`.
 * @param editable An optional lambda expression that dynamically determines whether the table rows can be edited. Defaults to `null`.
 * @param debug A flag to enable debugging features in the Tabulator initialization. Defaults to `false`.
 * @param init An optional lambda to further configure the `TabulatorViewList` after it has been initialized.
 * @return The updated `ViewList` object with Tabulator configurations applied.
 */
@Suppress("unused")
@OptIn(InternalSerializationApi::class)
fun <T : BaseDoc<ID>, ID : Any, FILT : IApiFilter<MID>, MID : Any> Container.fsTabulator(
    viewList: ViewList<T, ID, FILT, MID>,
    masterViewItem: ViewItem<out BaseDoc<MID>, MID, *>? = null,
    tabulatorOptions: TabulatorOptions<T> = viewList.defaultTabulatorOptions(),
    types: Set<TableType> = setOf(
        TableType.STRIPED,
        TableType.BORDERED,
        TableType.HOVER,
        TableType.SMALL
    ),
    minToolbarSize: Boolean = false,
    editable: (() -> Boolean)? = null,
    debug: Boolean = false,
    init: (TabulatorViewList<T, ID, FILT, MID>.() -> Unit)? = null,
): ViewList<T, ID, FILT, MID> {
    masterViewItem?.let {
        viewList.masterViewItem = it
        viewList.crudTask = it.crudTask
    }
    editable?.let { viewList.editable = it }
    val apiListBlock: () -> ApiList<FILT> = {
        ApiList(apiFilter = viewList.apiFilter)
    }

    val apiListSerialize: (ApiList<FILT>) -> String = { apiList: ApiList<FILT> ->
        Json.encodeToString(
            serializer = ApiList.serializer(viewList.configView.commonContainer.apiFilterSerializer),
            value = apiList
        )
    }

    vPanel {
        // El token del refresco periódico lo posee el panel realmente montado, no la instancia de
        // `ViewList`. Ver `ownPeriodicUpdateOf`, que es donde vive el porqué y lo que ejercita
        // `PeriodicRefreshDisposeTest` contra el ciclo de vida real de KVision.
        ownPeriodicUpdateOf(viewList)
        bind(viewList.errorStateObs) { errorState ->
            viewList.navbarTabulator = toolBarList(viewList = viewList, minToolbarSize)
            if (!errorState) {
                viewList.tabulator = tabulatorViewList(
                    viewList = viewList,
                    apiListBlock = apiListBlock,
                    apiListSerialize = apiListSerialize,
                    serializer = viewList.configView.commonContainer.itemSerializer,
                    tabulatorOptions = tabulatorOptions,
                    types = types,
                    debug = debug,
                ) {
                    id = viewList::class.simpleName
                    init?.invoke(this)
                    onEvent {
                        rowSelectionChangedTabulator = {
                            val tList = self.getSelectedData()
                            viewList.selectedItemObs.value = tList.let {
                                if (it.isEmpty()) null else it[0]
                            }
                            viewList.tabulator?.onRowSelected?.invoke(viewList.selectedItemObs.value)
                        }
                        viewList.onDataLoadedTabulator?.let { func ->
                            dataLoadedTabulator = { func(it.detail.unsafeCast<List<T>>()) }
                        }
                    }
                    addAfterInsertHook {
                        jsTabulator?.on("rowMouseOver") { event: Event, row: RowComponent ->
                            if (!event.defaultPrevented) {
                                viewList.overItem = row.getData()
                                ViewDataContainer.clearStartTime()
                            }
                        }
                        jsTabulator?.on("menuOpened") {
                            viewList.menuOpenedState = true
                        }
                        jsTabulator?.on("menuClosed") {
                            viewList.menuOpenedState = false
                        }
                        jsTabulator?.on("pageLoaded") { page: Int ->
                            onPageLoaded?.invoke(page)
                        }
                        jsTabulator?.on("dataProcessed") {
                            if (oldMaxPage != getPageMax() && oldPage > getPageMax()) {
//                                console.warn("DATA PROCESSED: adjusting tabulator.page:", getPage(), "to last")
                                jsTabulator?.setPage("last")
                            }
                        }
                        jsTabulator?.on("tableBuilt") {
                            viewList.jsTabulatorBuilt = true
//                            viewList.loadColumnDefinitions()
                        }
                        jsTabulator?.on("dataProcessing") {
                            window.setTimeout(
                                {
                                    val list = viewList.selectedIdList
                                    if (list?.isNotEmpty() == true) {
                                        jsTabulator?.getRows("")?.firstOrNull {
                                            it.getData().asDynamic()["_id"] == list[0]
                                        }?.select?.invoke()
                                    }
                                },
                                0
                            )
                        }
                    }
                }
            } else {
                centeredMessage(viewList.errorMessage ?: "unknown error", 50.vh) {
                    color = Color("Red")
                    addBsBgColor(BsBgColor.DARKSUBTLE)
                    border = Border(width = 5.px, color = Color("Red"))
                }
            }
        }
    }
    if (viewList.allowInstallPeriodicUpdate) {
        viewList.installUpdate()
    }
    return viewList
}
