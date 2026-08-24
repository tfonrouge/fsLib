package com.fonrouge.utils.view.dataMedia

import com.fonrouge.base.api.CrudTask
import com.fonrouge.base.fieldName
import com.fonrouge.base.state.ItemState
import com.fonrouge.fullStack.lib.format
import com.fonrouge.fullStack.lib.toDateTimeString
import com.fonrouge.fullStack.lib.toast
import com.fonrouge.base.model.IUser
import com.fonrouge.base.types.StringId
import com.fonrouge.fullStack.cellParams.cellNumberEditorParams
import com.fonrouge.fullStack.config.ConfigViewList
import com.fonrouge.fullStack.layout.addPageListBody
import com.fonrouge.fullStack.tabulator.fsTabulator
import com.fonrouge.fullStack.tabulator.getDataDate
import com.fonrouge.fullStack.tabulator.getDataValue
import com.fonrouge.fullStack.view.AppScope
import com.fonrouge.fullStack.view.ViewItem
import com.fonrouge.fullStack.view.ViewList
import com.fonrouge.utils.common.ICommonDataMedia
import com.fonrouge.utils.model.DataMediaFilter
import com.fonrouge.utils.model.IDataMedia
import com.fonrouge.utils.services.IApiDataMediaService
import com.fonrouge.utils.version.documentDownloadMediaUrl
import com.fonrouge.utils.version.documentUploadMediaUrl
import io.kvision.core.Container
import io.kvision.core.hideAnim
import io.kvision.core.showAnim
import io.kvision.form.upload.bootstrapUpload
import io.kvision.html.*
import io.kvision.navbar.Navbar
import io.kvision.panel.TabPanel
import io.kvision.panel.hPanel
import io.kvision.panel.tab
import io.kvision.panel.vPanel
import io.kvision.state.ObservableValue
import io.kvision.state.bind
import io.kvision.tabulator.Align
import io.kvision.tabulator.ColumnDefinition
import io.kvision.tabulator.Editor
import io.kvision.tabulator.VAlign
import io.kvision.utils.rem
import io.kvision.utils.vh
import js.uri.encodeURIComponent
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.reflect.KClass

/**
 * Abstract class representing a view for listing and managing media data associated with documents.
 *
 * @param CmnDM The type of common media data that extends from ICommonDataMedia.
 * @param DM The type of individual media data that extends from IDataMedia.
 * @param ID The type of the document's identifier.
 * @param U The type of the user associated with the media, extending from IUser.
 * @param UID The type of the user's unique identifier.
 * @param configViewList Configuration for initializing the view list for the media data.
 * @param viewItem Object containing the data and configuration for the associated ICommonContainer.
 * @param classifierClass Optional classifier for identifying the media type, defaulted based on viewItem.
 */
abstract class IViewListDataMedia<DM : IDataMedia<U, UID>, ID : Any, U : IUser<UID>, UID : Any>(
    configViewList: ConfigViewList<DM, StringId<IDataMedia<U, UID>>, *, DataMediaFilter, Unit, *>,
    val viewItem: ViewItem<*, ID, *>,
    classifierClass: KClass<*>? = null,
) : ViewList<DM, StringId<IDataMedia<U, UID>>, DataMediaFilter, Unit>(
    configView = configViewList
) {
    companion object {
        private var dataMediaService: IApiDataMediaService? = null
        private var deleteApiItemFun: Function<*>? = null
        private var buildViewListDataMedia: ((viewItem: ViewItem<*, *, *>, classifierClass: KClass<*>?) -> IViewListDataMedia<*, *, *, *>)? =
            null

        /**
         * Initializes the view list data media using the provided data media service and a builder function.
         *
         * @param dataMediaService An instance of `IApiDataMediaService` used for interacting with the media API.
         * @param deleteApiItemFun The consuming app's generic per-item CRUD function for its `DataMedia`
         *                         type (e.g. `IDataItemService::dataMedia`), which makes the row-delete
         *                         column actually delete. `columnDefinitionDeleteItem()` has two overloads:
         *                         the zero-arg one resolves its `apiItemFun` from `configViewItem()`, which
         *                         finds a config by *name convention* (`ViewListX` -> `ViewItemX`). An app
         *                         with no `ViewItem` registered for its `DataMedia` type resolves `null`
         *                         there, and the delete button reports `"No configViewItem found"` to the
         *                         user instead of deleting. Passing this parameter routes the column
         *                         through the explicit overload and sidesteps the lookup entirely.
         *
         *                         Declared *before* [buildViewListDataMedia] deliberately: that parameter
         *                         is a function type and call sites pass it as a trailing lambda, so a new
         *                         parameter after it would capture the lambda and break every existing
         *                         caller at compile time.
         *
         *                         Left `null`, the column falls back to the zero-arg overload — exactly
         *                         the previous behaviour, including for an app whose name-convention
         *                         lookup does resolve.
         * @param buildViewListDataMedia A lambda function that takes a `ViewItem` and an optional classifier class,
         *                               and returns an instance of `IViewListDataMedia`.
         */
        @Suppress("unused")
        fun initializeViewListDataMedia(
            dataMediaService: IApiDataMediaService,
            deleteApiItemFun: Function<*>? = null,
            buildViewListDataMedia: (viewItem: ViewItem<*, *, *>, classifierClass: KClass<*>?) -> IViewListDataMedia<*, *, *, *>,
        ) {
            this.dataMediaService = dataMediaService
            this.deleteApiItemFun = deleteApiItemFun
            this.buildViewListDataMedia = buildViewListDataMedia
        }

        /**
         * Adds a "Media" tab to a `TabPanel` that displays a list of media items.
         *
         * The method uses a function, if available, to build a list of media items and displays this
         * list within a "Media" tab, with an appropriate icon. If the function to build the media list
         * is not initialized, an error is logged to the console.
         *
         * @param viewItem The `ViewItem` that serves as the main context for building the media items to be displayed.
         * @param classifierClass An optional classifier class used to filter or configure the media item list based on its type.
         */
        @Suppress("unused")
        fun TabPanel.tabDataMedia(
            viewItem: ViewItem<*, *, *>,
            classifierClass: KClass<*>? = null,
        ) {
            if (viewItem.crudTask == CrudTask.Create) return
            buildViewListDataMedia?.let {
                tab("Media", icon = "fas fa-photo-film") {
                    addPageListBody(
                        viewList = it(viewItem, classifierClass)
                    )
                }
            } ?: run {
                console.error("IViewListDataMedia.initializeViewListDataMedia() must be called before calling tabDataMedia")
            }
        }
    }

    private val showUploadMediaObs = ObservableValue(false)
    private val classifierClass: KClass<*> = classifierClass ?: viewItem.configView.commonContainer.itemKClass

    init {
        this.crudTask = viewItem.crudTask
        apiFilter = apiFilter.copy(
            classifierDoc = this.classifierClass.simpleName ?: "",
            serializedIdDoc = viewItem.item?.let {
                Json.encodeToString(serializer = viewItem.configView.commonContainer.idSerializer, it._id)
            }
        )
        showDefaultContextRowMenu = { false }
    }

    fun bytesForHuman(bytes: Long, decimals: Int = 1): String {
        val units = listOf("B", "KB", "MB", "GB", "TB", "PB")
        var result = bytes.toDouble()
        var i = 0
        while (result > 1000) {
            ++i
            result /= 1000.0
        }
        return result.format(decimals) + " ${units[i]}"
    }

    override fun columnDefinitionList(): List<ColumnDefinition<DM>> = listOf(
        // Without an explicit function, fall back to the zero-arg overload rather than hiding the
        // column: `configViewItem()` resolves by name convention, so an app that registers a
        // `ViewItem` for its `DataMedia` type has a working delete button today, and hiding it here
        // would take that away.
        deleteApiItemFun?.let {
            @Suppress("UNCHECKED_CAST")
            columnDefinitionDeleteItem(
                visible = crudTask == CrudTask.Update,
                apiItemFun = it as Function<ItemState<DM>>,
            )
        } ?: columnDefinitionDeleteItem(visible = crudTask == CrudTask.Update),
        ColumnDefinition(
            title = "#",
            headerHozAlign = Align.CENTER,
            field = fieldName(IDataMedia<*, *>::order),
            vertAlign = VAlign.MIDDLE,
            hozAlign = Align.RIGHT,
            editor = Editor.INPUT,
            editable = { crudTask == CrudTask.Update },
            editorParams = cellNumberEditorParams {

            },
            cellEdited = { cell ->
                dataMediaService?.let { dataMediaService ->
                    cell.getDataValue<String?>(IDataMedia<U, UID>::_id)?.let {
                        AppScope.launch {
                            dataMediaService.updateOrder(
                                id = it,
                                order = cell.getDataValue(IDataMedia<*, *>::order)
                            ).toast()
                            dataUpdate()
                        }
                    }
                }
            }
        ),
        ColumnDefinition(
            title = "File",
            field = fieldName(IDataMedia<*, *>::fileName),
            vertAlign = VAlign.MIDDLE,
//            width = "20.rem",
            formatterFunction = { cell, _, _ ->
                cell.item?.fileName?.replaceFirst("^(.{25}).*".toRegex(), "$1...")
            },
            tooltip = true
        ),
        ColumnDefinition(
            title = "Type",
            field = fieldName(IDataMedia<*, *>::contentType),
            vertAlign = VAlign.MIDDLE,
            hozAlign = Align.CENTER,
            formatterComponentFunction = { _, _, data ->
                when (data.contentType) {
                    "application" -> when (data.contentSubtype) {
                        "pdf" -> Icon("fa-solid fa-file-pdf")
                        else -> Span("?")
                    }

                    "video" -> Icon("fa-solid fa-film")
                    "image" -> Image(
                        src = "${documentDownloadMediaUrl(classifierClass)}/${data._id}-thumbnail.jpeg",
                        alt = null,
                        responsive = true,
                        shape = ImageShape.ROUNDED,
                        centered = true
                    ).also {
                        it.height = 2.rem
                    }

                    else -> Span("?")
                }
            }
        ),
        ColumnDefinition(
            title = "Date",
            field = fieldName(IDataMedia<*, *>::fechaCreacion),
            vertAlign = VAlign.MIDDLE,
            formatterFunction = { cell, _, _ ->
                cell.getDataDate(IDataMedia<*, *>::fechaCreacion).toDateTimeString
            }
        ),
        ColumnDefinition(
            title = "Size",
            field = fieldName(IDataMedia<*, *>::fileSize),
            vertAlign = VAlign.MIDDLE,
            hozAlign = Align.RIGHT,
            formatterFunction = { cell, _, _ ->
                cell.item?.fileSize?.let { bytesForHuman(it) }
            }
        ),
        ColumnDefinition(
            title = "User",
//            field = fieldName(IDataMedia<*, *>::user, userProperty),
            vertAlign = VAlign.MIDDLE,
        )
    )

    override fun Navbar.navBarOptions() {
        if (crudTask == CrudTask.Update) {
            nav {
                button("+ Media") {
                    size = ButtonSize.XSMALL
                    onClick {
                        showUploadMediaObs.value = !showUploadMediaObs.value
                    }
                }
            }
        }
    }

    override fun Container.pageListBody() {
        vPanel {
            viewItem.item?.let { item ->
                val serializedIdDoc = encodeURIComponent(
                    Json.encodeToString(serializer = viewItem.configView.commonContainer.idSerializer, item._id)
                )
                div {
                    bootstrapUpload(
                        label = "Upload media",
                        multiple = true,
                        uploadUrl = "${documentUploadMediaUrl(classifierClass)}?serializedIdDoc=${serializedIdDoc}",
                    ) {
                        allowedFileTypes = setOf("image", "video", "pdf")
                        showUploadMediaObs.subscribe {
                            if (it) showAnim() else hideAnim()
                        }
                    }
                }
                hPanel(spacing = 10) {
                    div(className = "col-md-6") {
                        fsTabulator<DM, StringId<IDataMedia<U, UID>>, DataMediaFilter, Unit>(
                            viewList = this@IViewListDataMedia,
                            tabulatorOptions = defaultTabulatorOptions(selectableRows = 1)
                        )
                    }
                    div(className = "col-md-6").bind(selectedItemObs) { it ->
                        val src = "${documentDownloadMediaUrl(classifierClass)}/${it?._id}.${it?.contentSubtype}"
                        if (it?.contentType == "image") {
                            image(src = src, alt = null, responsive = true, centered = true).also {
                                it.height = 69.vh
                            }
                            link(
                                label = "View",
                                url = "${documentDownloadMediaUrl(classifierClass)}/${it._id}.${it.contentSubtype}",
                                target = "_blank",
                                icon = "fas fa-eye",
                                className = "btn-sm"
                            )
                        } else iframe(src = src, className = "iframe1") {
                            setStyle("width", "100%")
                            setStyle("height", "69vh")
                        }
                    }
                }
            }
        }
    }
}