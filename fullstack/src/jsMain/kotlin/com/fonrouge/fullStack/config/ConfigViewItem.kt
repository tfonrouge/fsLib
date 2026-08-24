package com.fonrouge.fullStack.config

import com.fonrouge.base.api.ApiItem
import com.fonrouge.base.api.CrudTask
import com.fonrouge.base.api.IApiFilter
import com.fonrouge.base.api.IApiItem
import com.fonrouge.base.common.ICommonContainer
import com.fonrouge.fullStack.lib.disposeOnHidden
import com.fonrouge.fullStack.lib.UrlParams
import com.fonrouge.fullStack.lib.toEncodedUrlString
import com.fonrouge.base.model.BaseDoc
import com.fonrouge.base.state.ItemState
import com.fonrouge.fullStack.apiItemQueryReadCall
import com.fonrouge.fullStack.tabulator.TabulatorMenuItem
import com.fonrouge.fullStack.view.KVWebManager.configViewItemMap
import com.fonrouge.fullStack.view.ViewItem
import dev.kilua.rpc.RpcServiceManager
import io.kvision.modal.Modal
import io.kvision.modal.ModalSize
import kotlinx.browser.window
import kotlinx.serialization.json.Json
import org.w3c.dom.Window
import web.cssom.HtmlAttributes.Companion.target
import kotlin.js.Promise
import kotlin.reflect.KClass

/**
 * Represents an abstract configuration view item that integrates with a common container, enabling interaction
 * with items and views in a structured manner. It provides utilities for managing URLs, navigating views, and
 * querying API-related operations.
 *
 * @param T The type of the item or document being processed.
 * @param ID The type of the identifier associated with the item or document.
 * @param V The type of the view item associated with the configuration.
 * @param FILT The type of API filter used for queries.
 * @param AIS The type of the asynchronous item service.
 * @param commonContainer The common container instance responsible for managing items and their corresponding views.
 * @param apiItemFun A suspending function for fetching item states using an API query.
 * @param viewKClass The class of the view item associated with this configuration.
 * @param contextMenuItems An optional function providing context menu items for a specific item.
 * @param baseUrl The base URL for the configuration; defaults to the name of the view class if not specified.
 */
abstract class ConfigViewItem<T : BaseDoc<ID>, ID : Any, V : ViewItem<T, ID, FILT>, FILT : IApiFilter<*>, AIS : Any>(
    commonContainer: ICommonContainer<T, ID, FILT>,
    val apiItemFun: suspend AIS.(IApiItem<T, ID, FILT>) -> ItemState<T>,
    viewKClass: KClass<out V>,
    val contextMenuItems: ((T) -> List<TabulatorMenuItem>)? = null,
    baseUrl: String? = null,
) : ConfigViewContainer<T, ID, V, FILT>(
    commonContainer = commonContainer,
    viewKClass = viewKClass,
    baseUrl = baseUrl,
) {
    companion object {
        /**
         * RPC service manager for item operations. Delegated to [ViewRegistry.itemServiceManager].
         *
         * @throws IllegalStateException if accessed before being initialized.
         */
        var serviceManager: RpcServiceManager<*>
            get() = ViewRegistry.itemServiceManager
            set(value) {
                ViewRegistry.itemServiceManager = value
            }

        /**
         * Defines a default context menu provider for a `ConfigViewItem`. This variable is a higher-order function
         * that generates a list of `TabulatorMenuItem` for a given context represented by a `BaseDoc`.
         *
         * The function takes in a `BaseDoc` instance and applies it to the `ConfigViewItem` context
         * to produce an optional list of tabular menu items. This list can define actions, separators,
         * or hierarchies displayed as part of the context menu.
         *
         * It is nullable, meaning the default context menu may not always be configured.
         */
        var contextMenuDefault: (ConfigViewItem<*, *, *, *, *>.() -> List<TabulatorMenuItem>?)? =
            null

        /**
         * Represents the default view mode for displaying items within the configuration.
         *
         * This variable determines how items are rendered or opened, using the {@link VMode} enum.
         * By default, the view mode is set to [VMode.modal], indicating that items will be displayed
         * in a modal window.
         *
         * The value of this variable is used as the default `vmode` parameter for various navigation
         * and rendering methods when no specific view mode is provided.
         */
        var defaultViewItemMode: VMode = VMode.modal
    }

    override val baseUrl: String
        get() {
            return _baseUrl ?: viewKClass.simpleName!!
        }

    var item: T? = null

    override val label: String get() = commonContainer.labelItem

    @Suppress("unused")
    val labelId get() = commonContainer.labelItemId(item)

    @Suppress("unused")
    val labelItemId get() = commonContainer.labelItemId(item)

    override val labelUrl: Pair<String, String> get() = label to url

    /**
     * Provides the serialized identifier of the current item using the provided ID serializer.
     * The serialization process is performed only if the `item` property is not null.
     * Returns the JSON-encoded string of the item's identifier.
     */
    @Suppress("unused")
    val serializedId get() = item?.let { Json.encodeToString(commonContainer.idSerializer, it._id) }

    @Suppress("unused")
    val urlCreate: String
        get() {
            val urlParams = UrlParams("action" to CrudTask.Create.name)
            return url + urlParams.toEncodedUrlString()
        }

    /**
     * Converts an [ApiItem] of type [T] to a list of key-value pairs representing API query parameters.
     *
     * @param apiItem The [ApiItem] containing the operation type, identifier, and filter criteria.
     *                The method processes `Create`, `Read`, `Update`, and `Delete` operations,
     *                constructing corresponding query parameters. If the [ApiItem] does not match
     *                a recognized type, it returns `null`.
     * @return A list of key-value pairs where each pair represents a query parameter key and its
     *         corresponding value. The list includes the action type, identifier, and serialized
     *         filter criteria. Returns `null` if the [ApiItem] cannot be processed.
     */
    fun apiItemToParamList(apiItem: ApiItem<T, ID, FILT>): List<Pair<String, String>>? = when (apiItem) {
        is ApiItem.Query.Create -> listOf(
            "action" to CrudTask.Create.name,
        ) + (apiItem.id?.let {
            listOf(
                "id" to Json.encodeToString(commonContainer.idSerializer, it)
            )
        } ?: emptyList())

        is ApiItem.Query.Read -> listOf(
            "action" to apiItem.crudTask.name,
            "id" to Json.encodeToString(commonContainer.idSerializer, apiItem.id)
        )

        is ApiItem.Query.Update -> listOf(
            "action" to apiItem.crudTask.name,
            "id" to Json.encodeToString(commonContainer.idSerializer, apiItem.id)
        )

        is ApiItem.Query.Delete -> listOf(
            "action" to apiItem.crudTask.name,
            "id" to Json.encodeToString(commonContainer.idSerializer, apiItem.id)
        )

        else -> null
    }?.let { pairs ->
        pairs + ("apiFilter" to Json.encodeToString(
            serializer = commonContainer.apiFilterSerializer,
            value = apiItem.apiFilter
        ))
    }

    /**
     * Constructs a URL string based on the provided [ApiItem] and an optional view mode.
     *
     * @param apiItem The [ApiItem] containing the operation type, identifier, and filter criteria.
     *                This is used to generate the query parameters for the URL.
     * @param vmode An optional [VMode] specifying the view mode to include as a query parameter.
     *              Defaults to `null` if not provided.
     * @return A URL string combining the base URL and encoded query parameters derived from
     *         the [ApiItem]. Returns `null` if the [ApiItem] cannot be converted into URL parameters.
     */
    fun apiItemToUrlString(apiItem: ApiItem<T, ID, FILT>, vmode: VMode? = null): String? {
        val url: String? = apiItemToParamList(apiItem)?.let { params: List<Pair<String, String>> ->
            val urlParams = UrlParams(*params.toTypedArray())
            vmode?.let { urlParams.pushParam("vmode" to it.name) }
            url + urlParams.toEncodedUrlString()
        }
        return url
    }

    /**
     * Executes a query to fetch a specific item using its identifier and processes the resulting state.
     *
     * @param R The return type of the transformation function applied to the resulting [ItemState].
     * @param id The identifier of the item to be queried.
     * @param apiFilter An optional filter of type `FILT` to refine the API query; defaults to a new instance of `FILT`.
     * @param transform A function that processes the resulting [ItemState] and transforms it into the desired type [R].
     * @return A `Promise` wrapping the result of the `transform` function applied to the queried item's [ItemState].
     */
    @Suppress("unused")
    fun <R> apiQueryReadCall(
        id: ID,
        apiFilter: FILT = commonContainer.apiFilterInstance(),
        transform: (ItemState<T>) -> R,
    ): Promise<R> {
        return commonContainer.apiItemQueryReadCall(
            apiItemFun = apiItemFun,
            id = id,
            apiFilter = apiFilter,
            transform = transform
        )
    }

    @Suppress("unused")
    fun labelUrlRead(id: ID) = commonContainer.labelItem to urlRead(id)

    @Suppress("unused")
    fun labelUrlUpdate(id: ID) = commonContainer.labelItem to urlUpdate(id)

    /**
     * Navigates to the URL associated with the given API item in a specified target window or tab.
     *
     * @param apiItem the API item from which the URL will be resolved and navigated to
     * @param target the target window or tab where the URL should be opened; default is "_blank"
     * @return the opened [Window] instance if the URL is successfully resolved and opened, or `null` if the URL cannot be resolved
     */
    @Suppress("unused")
    fun navigateToViewItem(apiItem: ApiItem<T, ID, FILT>, vmode: VMode = defaultViewItemMode) =
        openViewItem(apiItem, vmode)

    /**
     * Navigates to the creation query URL for a specific item using an optional identifier and a filter.
     *
     * @param id The optional identifier of the item to include in the creation query; defaults to `null`.
     * @param apiFilter An optional filter of type `FILT` to customize the creation query; defaults to a new instance of `FILT`.
     * @param target The target window or tab where the creation query URL should be opened; default is "_blank".
     * @return The opened [Window] instance if the URL is successfully resolved and opened, or `null` if the URL cannot be resolved.
     */
    @Suppress("unused")
    fun navigateToQueryCreate(
        id: ID? = null,
        apiFilter: FILT = commonContainer.apiFilterInstance(),
        vmode: VMode = defaultViewItemMode,
    ) = navigateToViewItem(
        apiItem = commonContainer.apiItemQueryCreate(
            id = id,
            apiFilter = apiFilter
        ),
        vmode = vmode
    )

    /**
     * Navigates to the URL associated with the given item identifier in a specified target window or tab.
     *
     * @param id The identifier of the item to retrieve and navigate to.
     * @param apiFilter An optional filter of type `FILT` to customize the API query; defaults to a new instance of `FILT`.
     * @param target The target window or tab where the URL should be opened; default is "_blank".
     * @return The opened [Window] instance if the URL is successfully resolved and opened, or `null` if the URL cannot be resolved.
     */
    @Suppress("unused")
    fun navigateToQueryRead(
        id: ID,
        apiFilter: FILT = commonContainer.apiFilterInstance(),
        vmode: VMode = defaultViewItemMode,
    ) = navigateToViewItem(
        apiItem = commonContainer.apiItemQueryRead(
            id = id,
            apiFilter = apiFilter
        ),
        vmode = vmode
    )

    /**
     * Navigates to the update query URL for a specific item using its identifier and an optional filter.
     *
     * @param id The identifier of the item for which the update query URL will be generated.
     * @param apiFilter An optional filter of type `FILT` to customize the update query; defaults to a new instance of `FILT`.
     * @param target The target window or tab where the update query URL should be opened; default is "_blank".
     * @return The opened [Window] instance if the URL is successfully resolved and opened, or `null` if the URL cannot be resolved.
     */
    @Suppress("unused")
    fun navigateToQueryUpdate(
        id: ID,
        apiFilter: FILT = commonContainer.apiFilterInstance(),
        vmode: VMode = defaultViewItemMode,
    ) = navigateToViewItem(
        apiItem = commonContainer.apiItemQueryUpdate(
            id = id,
            apiFilter = apiFilter
        ),
        vmode = vmode
    )

    /**
     * Opens a view for the specified API item in a specified view mode.
     *
     * @param apiItem The API item containing the data and context for generating the view. This includes
     *                associated attributes such as the identifier and optional filter criteria.
     * @param vmode The view mode specifying how the item should be displayed, defaulting to the configuration's
     *              default view mode. Supported modes include modal and various window targets (_self, _blank, etc.).
     */
    fun openViewItem(apiItem: ApiItem<T, ID, FILT>, vmode: VMode = defaultViewItemMode) {
        when (vmode) {
            VMode._blank,
            VMode._self,
            VMode._parent,
            VMode._top -> apiItemToUrlString(apiItem = apiItem, vmode)?.let { url ->
                window.open(url = url, target = "$vmode")
            }

            VMode.modal -> apiItemToParamList(apiItem)?.let { paramList ->
                val urlParams = (paramList + ("vmode" to "${VMode.modal}"))
                val modal = Modal(
                    caption = "",
                    size = ModalSize.XLARGE,
                    animation = false,
                    centered = false,
                    className = "viewItemModal"
                ) {
                    newViewInstance(UrlParams(*urlParams.toTypedArray())).apply {
                        viewModal = this@Modal
                        startDisplayPage()
                    }
                }
                modal.show()
                modal.disposeOnHidden()
            }
        }
    }

    fun urlRead(id: ID): String {
        val urlParams =
            UrlParams(
                "id" to Json.encodeToString(commonContainer.idSerializer, id),
                "action" to CrudTask.Read.name
            )
        return url + urlParams.toEncodedUrlString()
    }

    @Suppress("unused")
    fun urlDelete(id: ID): String {
        val urlParams =
            UrlParams(
                "id" to Json.encodeToString(commonContainer.idSerializer, id),
                "action" to CrudTask.Delete.name
            )
        return url + urlParams.toEncodedUrlString()
    }

    fun urlUpdate(id: ID): String {
        val urlParams = UrlParams(
            "id" to Json.encodeToString(commonContainer.idSerializer, id),
            "action" to CrudTask.Update.name
        )
        return url + urlParams.toEncodedUrlString()
    }

    init {
        configViewItemMap[this.baseUrl] = this
    }
}

/**
 * Configures a custom view item by combining the provided parameters into a configuration object.
 *
 * @param viewKClass The Kotlin class of the view item to be configured. This specifies the type of the view.
 * @param commonContainer The container managing the API items and facilitating interaction with the provided items.
 * @param apiItemFun A suspending function that processes API items and returns their corresponding item states.
 * @param contextMenuItems An optional lambda function to generate a list of context menu items for a given item of type T.
 * @param baseUrl An optional base URL to be used in the configuration of the view item.
 * @return A configuration object of the type ConfigViewItem for the provided generic parameters.
 */
@Suppress("unused")
fun <T : BaseDoc<ID>, ID : Any, V : ViewItem<T, ID, FILT>, FILT : IApiFilter<*>, AIS : Any> configViewItem(
    viewKClass: KClass<out V>,
    commonContainer: ICommonContainer<T, ID, FILT>,
    apiItemFun: suspend AIS.(IApiItem<T, ID, FILT>) -> ItemState<T>,
    contextMenuItems: ((T) -> List<TabulatorMenuItem>)? = null,
    baseUrl: String? = null,
): ConfigViewItem<T, ID, V, FILT, AIS> = object : ConfigViewItem<T, ID, V, FILT, AIS>(
    commonContainer = commonContainer,
    apiItemFun = apiItemFun,
    viewKClass = viewKClass,
    contextMenuItems = contextMenuItems,
    baseUrl = baseUrl
) {}
