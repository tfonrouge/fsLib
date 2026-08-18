package com.fonrouge.fullStack.view

import com.fonrouge.base.api.ApiItem
import com.fonrouge.base.api.CrudTask
import com.fonrouge.base.api.IApiFilter
import com.fonrouge.base.api.setMasterItemId
import com.fonrouge.base.common.ICommonContainer
import com.fonrouge.base.lib.iconCrud
import com.fonrouge.fullStack.lib.rejectionToast
import com.fonrouge.fullStack.lib.toast
import com.fonrouge.base.model.BaseDoc
import com.fonrouge.base.state.ItemState
import com.fonrouge.base.state.SimpleState
import com.fonrouge.base.state.State
import com.fonrouge.fullStack.config.ConfigViewItem
import com.fonrouge.fullStack.config.ConfigViewItem.Companion.defaultViewItemMode
import com.fonrouge.fullStack.config.ConfigViewList
import com.fonrouge.fullStack.tabulator.TabulatorMenuItem
import com.fonrouge.fullStack.tabulator.TabulatorViewList
import com.fonrouge.fullStack.tabulator.menuItem
import com.fonrouge.fullStack.view.KVWebManager.configViewItemMap
import io.kvision.core.Container
import io.kvision.i18n.I18n.gettext
import io.kvision.navbar.Navbar
import io.kvision.state.ObservableValue
import io.kvision.tabulator.*
import io.kvision.tabulator.js.Tabulator
import io.kvision.toast.Toast
import io.kvision.utils.obj
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import org.w3c.dom.events.Event

/**
 * Abstract class representing a list view with various properties and methods to manage and display collections of data items.
 *
 * @param T Type of the data item.
 * @param ID Type representing the identifier of the data item.
 * @param FILT Type of the API filter used.
 * @param MID Type representing the master item identifier.
 */
@Suppress("unused")
abstract class ViewList<T : BaseDoc<ID>, ID : Any, FILT : IApiFilter<MID>, MID : Any>(
    final override val configView: ConfigViewList<T, ID, out ViewList<T, ID, FILT, MID>, FILT, MID, *>,
    configViewItem: ConfigViewItem<T, ID, *, FILT, *>? = null,
    periodicUpdateDataView: Boolean? = null,
    var editable: (() -> Boolean) = { true },
) : ViewDataContainer<T, ID, FILT>(
    configViewContainer = configView,
) {
    companion object {
        /**
         * A dynamic variable representing the configuration for persistence of tabulator-info.
         * This variable can be used to enable or disable the persistence mechanism or to define custom
         * configurations based on its value.
         *
         * See `https://tabulator.info/docs/6.3/persist`
         */
        var tabulatorInfoPersistenceConfig: dynamic = true
    }

    /**
     * A nullable lambda function that gets triggered when a user selects an item.
     * The selected item of type `T` is passed as a parameter to the function.
     */
    open val onUserChooseItem: ((T) -> Unit)? = null

    /**
     * Represents a configuration holder for a view item in a ViewList.
     *
     * This variable allows lazy initialization and retrieval of a `ConfigViewItem` instance based on the
     * associated configuration view class. If the variable is not already initialized, it attempts to
     * resolve the corresponding view item configuration using naming conventions and a predefined
     * `configViewItemMap`. For example, if the class name of the view starts with "ViewList", it replaces
     * it with "ViewItem". Otherwise, it appends "ViewItem" with the name of the `itemKClass` in the
     * associated common container.
     *
     * This variable integrates with the shared state within the containing `ViewList` and is essential for
     * configuring CRUD-related operations, backend API interactions, and other functionalities tied to
     * the view item.
     */
    private var _configViewItem: ConfigViewItem<T, ID, *, FILT, *>? = configViewItem
    fun configViewItem(): ConfigViewItem<T, ID, *, FILT, *>? {
        if (_configViewItem != null) return _configViewItem
        val viewClassName = configView.viewKClass.simpleName!!
        val name = if (viewClassName.startsWith("ViewList")) {
            viewClassName.replaceFirst("ViewList", "ViewItem")
        } else {
            "ViewItem${configView.commonContainer.itemKClass.js.name}"
        }
        _configViewItem = configViewItemMap[name]?.unsafeCast<ConfigViewItem<T, ID, *, FILT, *>>()
        return _configViewItem
    }

    /**
     * Resets the persisted column layout settings for a Tabulator table and reloads the page.
     *
     * This method removes the saved column configuration from the browser's local storage for the
     * current Tabulator instance. It is useful when restoring the table to its default column layout
     * after a user has made customizations. After clearing the persisted configuration, the page
     * is reloaded to apply the default settings.
     *
     * Automatically uses the Tabulator's `persistenceID` for identifying and removing the corresponding
     * local storage entry. If no `persistenceID` is available, a default identifier is used.
     */
    fun resetColumns() {
        val persistenceID =
            tabulator?.jsTabulator?.options?.persistenceID?.let { "tabulator-$it" } ?: "tabulator"
        localStorage.removeItem("$persistenceID-columns")
        window.location.reload()
    }

    /**
     * Builds a context menu for the column header in a Tabulator table.
     * The menu options include hiding a column, showing hidden columns,
     * and resetting the column layout to default. This context menu
     * enhances the interactivity of the table for the end user.
     *
     * @param event The event object associated with opening the context menu.
     * @param columnComponent The column component for which the context menu is being built.
     * @return An array representing the menu items available in the context menu.
     */
    private fun buildColumnHeaderContextMenu(event: Event, columnComponent: Tabulator.ColumnComponent): Array<Any> {
        fun hiddenColumns(): Array<Any>? {
            val x: List<Any>? = tabulator?.jsTabulator?.getColumns(false)?.filter {
                !it.isVisible()
            }?.flatMap { columnComponent ->
                val responsiveHiddenColumns =
                    (this@ViewList.tabulator?.jsTabulator?.modules?.asDynamic()?.responsiveLayout?.hiddenColumns as Array<Tabulator.ColumnComponent>).map {
                        it.getField()
                    }
                listOf(
                    obj {
                        this.label = columnComponent.getDefinition().title
                        this.action = { e: Event, c: Tabulator.ColumnComponent ->
                            e.stopPropagation()
                            if (columnComponent.isVisible()) {
                                columnComponent.hide()
                            } else if (responsiveHiddenColumns.contains(c.getField())) {
                                columnComponent.show()
                                columnComponent.hide()
                            } else {
                                columnComponent.show()
                            }
                            this@ViewList.tabulator?.jsTabulator?.redraw(true)
                        }
                    },
                    obj {
                        separator = true
                    }
                )
            }
            return x?.toTypedArray()
        }

        val menuHiddenColumns = hiddenColumns()
        val icon = document.createElement("i").apply {
            classList.add("far", "fa-eye-slash")
        }
        val label = document.createElement("span")
        val title = document.createElement("span")
        title.innerHTML = " ${gettext("Hide column")} ⇾ '<b>${columnComponent.getDefinition().title}</b>'"
        label.appendChild(icon)
        label.appendChild(title)
        val menu = mutableListOf<Any>()
        menu.add(
            obj {
                this.label = label
                action = fun(event: Event, column: Tabulator.ColumnComponent) {
                    column.hide()
                    tabulator?.jsTabulator?.redraw(true)
                }
            },
        )
        menu.add(
            obj {
                separator = true
            }
        )
        if (menuHiddenColumns.isNullOrEmpty().not()) {
            menu.add(
                obj {
                    val label = document.createElement("span")
                    val title = document.createElement("span")
                    val icon = document.createElement("i").apply {
                        classList.add("fas", "fa-table-columns")
                    }
                    title.textContent = " ${gettext("Hidden columns")} (${menuHiddenColumns.size / 2}) ⇾ "
                    label.appendChild(icon)
                    label.appendChild(title)
                    this.label = label
                    this.menu = menuHiddenColumns
                }
            )
        }
        menu.add(
            obj {
                val icon = document.createElement("i")
                icon.classList.add("fas")
                icon.classList.add("fa-rotate")
                val label = document.createElement("span")
                val title = document.createElement("span")
                title.textContent = " ${gettext("Reset columns")}"
                label.appendChild(icon)
                label.appendChild(title)
                this.label = label
                this.action = { e: Event ->
                    e.stopPropagation()
                    resetColumns()
                }
            }

        )
        return menu.toTypedArray()
    }

    /**
     * Provides the default column definition for a Tabulator table in the `ViewList` class.
     *
     * This function defines a base configuration for a table column, including behaviors such as tooltip,
     * whether the header tooltip is displayed, sorting enabled on the header, and the context menu for
     * the column header. The context menu allows users to modify column visibility and reset column layouts.
     *
     * The default values for the column configuration are as follows:
     * - Title: An empty string, indicating no default title.
     * - Header tooltip: Enabled by default.
     * - Header sort: Disabled by default.
     * - Tooltip for cells: Enabled by default.
     * - Header context menu: Builds a menu for column operations using the `buildColumnHeaderContextMenu` method.
     *
     * This property serves as a template for consistent column behaviors across the table, with customization
     * available for specific columns via additional configuration.
     */
    open fun columnDefaults(): ColumnDefinition<T>? = ColumnDefinition(
        title = "",
        headerHozAlign = Align.CENTER,
        headerTooltip = true,
        headerSort = false,
        tooltip = true,
        headerContextMenu = fun(event: Event, columnComponent: Tabulator.ColumnComponent): Array<Any> =
            buildColumnHeaderContextMenu(event, columnComponent)
    )

    val errorStateObs = ObservableValue(false)
    var errorMessage: String? = null

    /**
     * Observable property representing the currently selected item in the view list.
     *
     * This variable holds a reactive observable value of type `T?`, which updates
     * dynamically as the selected item changes. The selected item can be used to
     * determine the user’s current selection in the view list or tabular data
     * component.
     */
    var selectedItemObs: ObservableValue<T?> = ObservableValue(null)
    var jsTabulatorBuilt: Boolean = false
    var menuOpenedState: Boolean? = null
    var navbarTabulator: Navbar? = null
    var onDataLoadedTabulator: ((List<T>) -> Unit)? = null

    /* dynamic content only used to get _id */
    var overItem: Any? = null

    /**
     * Determines whether the default context menu for a row should be displayed.
     *
     * This property provides a lambda function returning a Boolean value
     * that specifies whether the default context row menu is enabled or not.
     * By default, it always returns `true`, indicating that the menu is shown.
     * It can be customized to modify the condition dynamically based on specific logic.
     */
    var showDefaultContextRowMenu: () -> Boolean = { true }

    private fun buildColumnDefinitionDeleteItem(
        visible: Boolean? = null,
        cellClick: ((e: Any?, cell: Tabulator.CellComponent) -> Unit),
    ): ColumnDefinition<T> = ColumnDefinition(
        title = "",
        field = "__deleteItem",
        vertAlign = VAlign.MIDDLE,
        hozAlign = Align.CENTER,
        formatterFunction = { _, _, _ ->
            "<i class=\"fa-solid fa-trash\"></i>"
        },
        visible = visible,
        cellClick = cellClick
    )

    /**
     * Defines a column for the deletion of items in a tabular view.
     *
     * This method configures a column that provides a delete functionality for items.
     * It integrates a confirmation dialog for user confirmation before performing a delete action.
     * If a configuration view item is not available, an error is logged and displayed.
     *
     * @param visible An optional parameter to control the visibility of the delete column.
     *                If `null`, the visibility is determined by the default configuration.
     * @return A `ColumnDefinition<T>` instance that represents the delete column.
     */
    fun columnDefinitionDeleteItem(visible: Boolean? = null): ColumnDefinition<T> =
        buildColumnDefinitionDeleteItem(visible = visible) { _, cell ->
            cell.item?.let { item ->
                configViewItem()?.let { configViewItem ->
                    confirmDeleteView(
                        apiItemFun = configViewItem.apiItemFun,
                        item = item
                    )
                } ?: run {
                    "No configViewItem found".also {
                        console.error(it)
                        SimpleState(
                            state = State.Error,
                            msgError = it
                        ).toast()
                    }
                }
            }
        }

    /**
     * Creates and configures a column definition that provides a delete functionality for items in a tabular view.
     *
     * This column allows users to delete items from the tabular view. It integrates a confirmation dialog
     * to ensure user approval before executing the delete operation. The delete operation uses the specified
     * item state function and refreshes the data view upon success.
     *
     * @param visible An optional parameter to determine the visibility of the delete column. Defaults to `null`,
     *                allowing the default visibility settings to apply.
     * @param apiItemFun A function that operates on the item's state and manages the delete operation.
     * @return A `ColumnDefinition<T>` instance representing the delete column with appropriate functionality.
     */
    fun columnDefinitionDeleteItem(
        visible: Boolean? = null,
        apiItemFun: Function<ItemState<T>>,
    ): ColumnDefinition<T> = buildColumnDefinitionDeleteItem(visible = visible) { _, cell ->
        cell.item?.let { item ->
            confirmDeleteView(
                apiItemFun = apiItemFun,
                item = item,
                onSuccess = { dataUpdate() }
            )
        }
    }

    /**
     * Provides a list of column definitions for the tabular view.
     *
     * This method is used to define the structure and attributes of columns in the view.
     *
     * @return A list of `ColumnDefinition<T>` instances representing the columns in the tabular view.
     *         If no columns are defined, an empty list is returned.
     */
    open fun columnDefinitionList(): List<ColumnDefinition<T>> = emptyList()

    var masterViewItem: ViewItem<out BaseDoc<MID>, MID, *>? = null
        set(value) {
            editable = { value?.actionUpsert == true }
            crudTask = value?.crudTask
            apiFilter = apiFilter.setMasterItemId(value?.item?._id)
            field = value
        }

    /**
     * Set to true if periodic update of table data is allowed
     */
    final override var periodicUpdateDataView: Boolean? = periodicUpdateDataView
        get() = field ?: KVWebManager.periodicUpdateDataViewList

    /**
     * Set to true to reload column definitions on next [dataUpdate]
     */
    @Suppress("MemberVisibilityCanBePrivate")
    var markReloadColumnDefinitions = false

    /**
     * Contains id's from selected row(s)
     */
    var selectedIdList: List<Any?>? = null

    /**
     * the tabulator list
     */
    var tabulator: TabulatorViewList<T, ID, FILT, MID>? = null

    /**
     * On calling crud actions [[Create, Update]] on this list, checks if it has a masterViewItem
     * which is currently on Update action, if so, then performs an update call to back end before
     * calling the list crud action required
     */
    open fun goActionUrl(
        crudTask: CrudTask,
        item: T? = selectedItemObs.value,
        configViewItem: ConfigViewItem<T, ID, *, FILT, *>? = configViewItem(),
    ) {
        configViewItem ?: return
        val apiItem = when (crudTask) {
            CrudTask.Create -> ApiItem.Query.Create<T, ID, FILT>(id = item?._id, apiFilter = apiFilter)
            CrudTask.Read -> item?._id?.let { ApiItem.Query.Read<T, ID, FILT>(id = item._id, apiFilter = apiFilter) }
            CrudTask.Update -> item?._id?.let {
                ApiItem.Query.Update<T, ID, FILT>(
                    id = item._id,
                    apiFilter = apiFilter
                )
            }

            CrudTask.Delete -> item?._id?.let {
                ApiItem.Query.Delete<T, ID, FILT>(
                    id = item._id,
                    apiFilter = apiFilter
                )
            }
        } ?: return
        val callBlock = {
            if (crudTask == CrudTask.Delete) {
                item?.let {
                    confirmDeleteView(
                        apiItemFun = configViewItem.apiItemFun,
                        item = item,
                        apiFilter = apiFilter
                    ) {
                        dataUpdate()
                    }
                }
            } else {
                configViewItem.openViewItem(apiItem = apiItem, vmode = defaultViewItemMode)
            }
        }
        if (masterViewItem?.crudTask == CrudTask.Update) {
            masterViewItem?.acceptUpsertAction { itemResponse ->
                // `state != State.Error` let a State.Warn refusal through as though the master had
                // saved, navigating on and leaving the refusal unshown. `isWriteComplete` is the
                // predicate that separates "saved, or nothing needed saving" from "turned down".
                if (itemResponse.isWriteComplete) {
                    callBlock()
                } else {
                    itemResponse.rejectionToast()
                }
            }
        } else {
            callBlock()
        }
    }

    /**
     * Generates a context menu for a specific row item.
     *
     * @param item The item for which the context menu is being generated. It can be null if no specific item is selected.
     * @return A list of TabulatorMenuItem representing the context menu options, or null if no menu is available.
     */
    open fun contextRowMenu(item: T): List<TabulatorMenuItem>? = null

    /**
     * Generates a context menu for a specific row in a Tabulator table, based on the provided configuration
     * and the current state.
     *
     * The menu includes options such as viewing details, creating, updating, and deleting records,
     * as well as custom menu items defined in the configuration or by specific logic.
     *
     * @return An array of [TabulatorMenuItem] objects representing the context menu for the row.
     */
    private fun contextRowMenuGenerator(): Array<TabulatorMenuItem> {
        val item: T? = overItem?.let {
            try {
                tabulator?.toKotlinObj(it)
            } catch (e: Exception) {
                Toast.danger(e.message ?: "")
                e.printStackTrace()
                null
            }
        }
        val configViewItem = configViewItem()
        val menu = mutableListOf<TabulatorMenuItem>()
        val labelId = configViewItem?.commonContainer?.labelId?.invoke(item) ?: ""
        menu += menuItem(
            label = " <font size=\"+1\">${configViewItem?.label ?: ""}</font>: <b>$labelId</b>",
            disabled = false,
            header = true
        )
        menu += menuItem(separator = true)
        configViewItem?.let {
            if (showDefaultContextRowMenu()) {
                menu += menuItem(
                    label = gettext("Detail of"),
                    icon = iconCrud(CrudTask.Read),
                    action = { _, _ ->
                        goActionUrl(CrudTask.Read, item)
                    }
                )
                if (editable()) {
                    menu += menuItem(
                        label = gettext("Create"),
                        icon = iconCrud(CrudTask.Create),
                        action = { _, _ ->
                            goActionUrl(CrudTask.Create, item)
                        }
                    )
                    menu += menuItem(
                        label = gettext("Update"),
                        icon = iconCrud(CrudTask.Update),
                        action = { _, _ ->
                            goActionUrl(CrudTask.Update, item)
                        }
                    )
                    menu += menuItem(
                        label = gettext("Delete"),
                        icon = iconCrud(CrudTask.Delete),
                        action = { _, _ ->
                            goActionUrl(CrudTask.Delete, item)
                        }
                    )
                }
            }
            configViewItem.item = item
            ConfigViewItem.contextMenuDefault?.invoke(configViewItem)?.let {
                if (it.isNotEmpty()) {
                    if (menu.isNotEmpty()) menu += menuItem(separator = true)
                    menu += it
                }
            }
        }
        configViewItem?.contextMenuItems?.let { function: (T) -> List<TabulatorMenuItem> ->
            item?.let {
                menu += function(item)
            }
        }
        item?.let {
            contextRowMenu(item)?.let {
                menu += it
            }
        }
        return menu.toTypedArray()
    }

    /**
     * forces an update for the tabulator data
     */
    final override fun dataUpdate() {
        if (jsTabulatorBuilt) {
            if (markReloadColumnDefinitions) {
                markReloadColumnDefinitions = false
                loadColumnDefinitions()
            }
            if (menuOpenedState != true) tabulator?.let { tabulator ->
                selectedIdList = tabulator.getSelectedData().map { it._id }
                tabulator.apiCall()
            }
        }
    }

    /**
     * Creates default Tabulator options for initializing a Tabulator table configuration.
     *
     * @param columnDefaults Default column settings to be applied. Defaults to `columnDefaults()`.
     * @param columns List of column definitions. Defaults to `columnDefinitionList()`.
     * @param dataLoader Enables or disables the data loader. Defaults to `true`.
     * @param dataTree Enables or disables data tree view. Defaults to `null`.
     * @param dataTreeChildIndent Indentation for child rows in the data tree. Defaults to `null`.
     * @param dataTreeCollapseElement Custom element used when a tree node is collapsed. Defaults to `null`.
     * @param dataTreeExpandElement Custom element used when a tree node is expanded. Defaults to `null`.
     * @param dataTreeStartExpanded Function to determine default expanded state for tree nodes. Defaults to `null`.
     * @param filterMode Mode for applying filters, either `REMOTE` or `LOCAL`. Defaults to `FilterMode.REMOTE`.
     * @param height Height of the Tabulator table. Defaults to `"calc(100vh - 35vh)"`.
     * @param index Field name for the row index. Defaults to `"_id"`.
     * @param layout Layout mode for table columns. Defaults to `Layout.FITDATAFILL`.
     * @param layoutColumnsOnNewData Re-layout columns when new data is loaded. Defaults to `true`.
     * @param movableColumns Enables or disables column reordering. Defaults to `true`.
     * @param pagination Enables or disables pagination. Defaults to `true`.
     * @param paginationCounter Defines the pagination counter behavior, e.g., "rows". Defaults to `"rows"`.
     * @param paginationMode Mode for pagination, either `REMOTE` or `LOCAL`. Defaults to `PaginationMode.REMOTE`.
     * @param paginationSize Number of rows per page. Defaults to `100`.
     * @param paginationSizeSelector Options for selecting page sizes. Defaults to `arrayOf(10, 20, 50, 100, 200, 500)`.
     * @param persistence Enables or disables state persistence for the table. Defaults to `true`.
     * @param persistenceID Unique identifier for state persistence. Defaults to the `simpleName` of the class.
     * @param rowContextMenu Context menu configuration for table rows. Defaults to the result of `contextRowMenuGenerator()`.
     * @param selectableRows Enables selection of rows. Defaults to `null`.
     * @param rowHeader Configures settings for the row header column, if enabled, such as selection controls. Defaults to `null` or specific configuration based on `selectableRows
     * `.
     * @param sortMode Mode for sorting, either `REMOTE` or `LOCAL`. Defaults to `SortMode.REMOTE`.
     * @return A configured instance of `TabulatorOptions` with the specified or default settings.
     */
    fun defaultTabulatorOptions(
        columnDefaults: ColumnDefinition<T>? = this.columnDefaults(),
        columns: List<ColumnDefinition<T>>? = columnDefinitionList(),
        placeholder: String? = """
            ${gettext("There Are No")} <i>${configView.commonContainer.labelList}</i><br>
            ${gettext("Click + to add new record")}
            """,
        dataLoader: Boolean? = false,
        dataLoaderError: String? = null,
        dataLoaderLoading: String? = null,
        dataTree: Boolean? = null,
        dataTreeChildIndent: Number? = null,
        dataTreeCollapseElement: dynamic = null,
        dataTreeExpandElement: dynamic = null,
        dataTreeStartExpanded: ((row: Tabulator.RowComponent, level: Number) -> Boolean)? = null,
        filterMode: FilterMode? = FilterMode.REMOTE,
        height: String? = "calc(100vh - 35vh)",
        index: String? = "_id",
        layout: Layout? = Layout.FITDATAFILL,
        layoutColumnsOnNewData: Boolean? = true,
        movableColumns: Boolean? = true,
        pagination: Boolean? = true,
        paginationCounter: dynamic = "rows",
        paginationMode: PaginationMode? = PaginationMode.REMOTE,
        paginationSize: Int? = 100,
        paginationSizeSelector: dynamic = arrayOf(10, 20, 50, 100, 200, 500),
        persistence: dynamic = tabulatorInfoPersistenceConfig,
        persistenceID: String? = this::class.simpleName,
        rowContextMenu: dynamic = { contextRowMenuGenerator() },
        selectableRows: dynamic = 1,
        rowHeader: dynamic = if (selectableRows is Number && selectableRows > 1) {
            obj {
                headerSort = false
                resizable = false
                frozen = true
                headerHozAlign = "center"
                hozAlign = "center"
                vertAlign = "middle"
                formatter = "rowSelection"
                titleFormatter = "rowSelection"
                cellClick = fun(e: dynamic, cell: dynamic) {
                    cell.getRow().toggleSelect()
                }
            }
        } else null,
        sortMode: SortMode? = SortMode.REMOTE,
    ): TabulatorOptions<T> {
        return TabulatorOptions(
            columnDefaults = columnDefaults,
            columns = columns,
            placeholder = placeholder,
            dataLoader = dataLoader,
            dataLoaderError = dataLoaderError,
            dataLoaderLoading = dataLoaderLoading,
            dataTree = dataTree,
            dataTreeChildIndent = dataTreeChildIndent,
            dataTreeCollapseElement = dataTreeCollapseElement,
            dataTreeExpandElement = dataTreeExpandElement,
            dataTreeStartExpanded = dataTreeStartExpanded,
            filterMode = filterMode,
            height = height,
            index = index,
            layout = layout,
            layoutColumnsOnNewData = layoutColumnsOnNewData,
            movableColumns = movableColumns,
            pagination = pagination,
            paginationCounter = paginationCounter,
            paginationMode = paginationMode,
            paginationSize = paginationSize,
            paginationSizeSelector = paginationSizeSelector,
            persistence = persistence,
            persistenceID = persistenceID,
            selectableRows = selectableRows,
            rowContextMenu = rowContextMenu,
            rowHeader = rowHeader,
            sortMode = sortMode,
        )
    }

    /**
     * load the column definitions from [columnDefinitionList] to the [tabulator]
     */
    @Suppress("MemberVisibilityCanBePrivate")
    fun loadColumnDefinitions() {
        val columnList = columnDefinitionList()
        tabulator?.let { tabulator ->
            tabulator.jsTabulator?.setColumns(columnList.map {
                it.toJs(tabulator, tabulator::translate, configView.commonContainer.itemKClass)
            }.toTypedArray())
        }
    }

    /**
     * the main display for the viewList, displays the [pageBanner] and the [buildOffCanvasFilterView] if any defined
     */
    final override fun Container.displayPage() {
        if (!noPageBanner) {
            pageBanner()
        }
        pageListBody()
    }

    /**
     * Retrieves the item associated with the current cell in the Tabulator.
     *
     * This property gets the data of the current cell and converts it to
     * a Kotlin object. If the `tabulator` is null, an exception is thrown.
     *
     * @throws Throwable If the `tabulator` is null, the method will throw an exception.
     * @return The data item represented by the current cell, converted to a Kotlin object.
     *
     * TODO: try/catch needed because tabulator column 'editable' property
     * can send wrong T structure and it throws error on deserialization
     * combined with using the bottomCalcFun ColumnDefinition property
     */
    val Tabulator.CellComponent.item: T?
        get() = try {
            tabulator?.toKotlinObj(this.getData())
        } catch (_: Exception) {
//            console.error(e)
            null
        }

    override val label: String get() = configView.commonContainer.labelList

    open fun Navbar.navBarOptions() {}

    /**
     * Set to false to suppress the "Create" button from the toolbar (e.g. when the view
     * provides its own creation entry point such as a wizard).
     * Update and Delete buttons are unaffected.
     */
    open val showCreateInToolbar: Boolean = true

    /**
     * allows processing JavaScript array arrived from the backend
     */
    open fun onReceivingData(data: dynamic) {}

    /**
     * export to file download
     */
    open fun outToFile() {
        tabulator?.downloadCSV(
            fileName = "${label}.csv",
            dataSet = RowRangeLookup.ALL,
            includeBOM = true
        )
    }

    /**
     * print viewList
     */
    open fun outPrint() {
        tabulator?.print(rowRangeLookup = RowRangeLookup.ALL, isStyled = true)
    }

    /**
     * the main display for the viewList tabulator area
     */
    abstract fun Container.pageListBody()
}
