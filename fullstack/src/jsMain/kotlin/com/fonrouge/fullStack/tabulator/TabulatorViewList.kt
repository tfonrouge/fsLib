package com.fonrouge.fullStack.tabulator

import com.fonrouge.base.api.ApiList
import com.fonrouge.base.api.IApiFilter
import com.fonrouge.base.common.ICommonContainer
import com.fonrouge.base.model.BaseDoc
import com.fonrouge.base.state.State
import com.fonrouge.fullStack.config.ConfigViewList
import com.fonrouge.fullStack.view.ViewDataContainer.Companion.clearStartTime
import com.fonrouge.fullStack.view.ViewList
import dev.kilua.rpc.HttpMethod
import dev.kilua.rpc.RemoteFilter
import dev.kilua.rpc.RemoteSorter
import dev.kilua.rpc.RpcSerialization
import io.kvision.core.Container
import io.kvision.core.KVScope
import dev.kilua.rpc.CallAgent
import io.kvision.tabulator.PaginationMode
import io.kvision.tabulator.TableType
import io.kvision.tabulator.Tabulator
import io.kvision.tabulator.TabulatorOptions
import io.kvision.toast.Toast
import io.kvision.toast.ToastOptions
import io.kvision.utils.Serialization
import kotlinx.browser.window
import kotlinx.coroutines.asPromise
import kotlinx.coroutines.async
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromDynamic
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.overwriteWith
import kotlinx.serialization.serializer
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.reflect.KClass

/**
 * A specialized implementation of the Tabulator class designed for integration with a `ViewList`
 * and remote API calls. This class supports server-side pagination, filtering, and sorting,
 * while maintaining compatibility with Kotlin serialization and Tabulator options.
 *
 * @param T The type of the data model being displayed in the Tabulator. This type must extend `BaseDoc`.
 * @param ID The type of the unique identifier for the data model.
 * @param FILT The type of the filter used for API interactions, extending `IApiFilter`.
 * @param MID The type of the metadata identifier used for filtering.
 * @param viewList The `ViewList` instance responsible for managing the configuration of the container
 * and item serializers.
 * @param apiListBlock A function that instantiates a new `ApiList` object used for API interactions.
 * @param apiListSerialize A function that serializes the `ApiList` instance into a JSON string.
 * @param debug A flag to enable or disable debug logs. Defaults to `false`.
 * @param tabulatorOptions The options to configure the Tabulator instance. Some parameters are
 * overridden to enforce server-side pagination (`pagination = true` and `paginationMode = REMOTE`).
 * @param types A set of `TableType` values specifying the types of table views supported.
 * @param className The name of the class associated with the data model, used for logging or debugging.
 * @param kClass The Kotlin class reference of the data model, required for dynamic type operations.
 * @param serializer The Kotlin serializer used for encoding and decoding instances of the data model.
 * @param module An optional `SerializersModule` used for custom serialization scenarios.
 */
@OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
class TabulatorViewList<T : BaseDoc<ID>, ID : Any, FILT : IApiFilter<MID>, MID : Any>(
    val viewList: ViewList<T, ID, FILT, MID>,
    private val apiListBlock: (() -> ApiList<FILT>),
    private val apiListSerialize: (ApiList<FILT>) -> String?,
    val debug: Boolean = false,
    tabulatorOptions: TabulatorOptions<T>,
    types: Set<TableType>,
    className: String?,
    kClass: KClass<T>?,
    serializer: KSerializer<T>?,
    module: SerializersModule?,
) : Tabulator<T>(
    data = null,
    dataUpdateOnEdit = false,
    // TODO: Fix error when not using the following parameters when decoding api result in [TabulatorViewList.promise]
    // which returns an object ("{}") instead of a list ("[]")
    options = tabulatorOptions.copy(
        pagination = true,
        paginationMode = PaginationMode.REMOTE
    ),
    types = types,
    className = className,
    kClass = kClass,
    serializer = serializer,
    module = module
) {
    private var contentHashCode: Int? = null
    private var diffContentHashCode: Boolean = false
    private val kvCallAgent: CallAgent
    private var method: HttpMethod = HttpMethod.GET
    var oldPage: Int = -1
    var oldMaxPage: Int = -1
    private var url: String = ""

    override val jsonHelper = if (serializer != null) Json(
        from = (RpcSerialization.customConfiguration ?: Serialization.customConfiguration
        ?: Json {
            ignoreUnknownKeys = true
            isLenient = true
        })
    ) {
        serializersModule = SerializersModule {
            include(RpcSerialization.plain.serializersModule)
            module?.let { this.include(it) }
        }.overwriteWith(serializersModule)
    } else null

    private val kvUrlPrefix = window["kv_remote_url_prefix"]
    private val urlPrefix: String = if (kvUrlPrefix != undefined) "$kvUrlPrefix/" else ""

    /**
     * A callback that is triggered when a page is successfully loaded.
     *
     * The variable allows the assignment of a lambda function or a method reference,
     * which will be invoked upon the completion of a page load event.
     * The single `Int` parameter passed to the callback typically represents
     * the page number or an identifier related to the loaded page.
     *
     * The callback is nullable, so it can remain `null` if no operation
     * should be executed after a page is loaded.
     */
    var onPageLoaded: ((Int) -> Unit)? = null

    /**
     * A callback function that is triggered when a row in the list is selected.
     *
     * The lambda receives the selected item of type [T] or `null` if no row is selected.
     * This property can be used to handle specific actions or behaviors when a row is selected
     * in the user interface.
     *
     * Setting this property to `null` will disable the callback.
     */
    var onRowSelected: ((T?) -> Unit)? = null

    /**
     * Converts a dynamic data object into a Kotlin List of type [T].
     *
     * @param data The dynamic data object to be converted into a Kotlin List.
     * The data is expected to be in a JSON-compatible format.
     *
     * @return A Kotlin List of type [T] generated from the input data.
     * If an error occurs during the conversion, an empty list is returned.
     */
    @Suppress("MemberVisibilityCanBePrivate")
    fun toKotlinList(data: dynamic): List<T> =
        try {
            Json.decodeFromDynamic(
                ListSerializer(viewList.configView.commonContainer.itemSerializer),
                data
            )
        } catch (_: Exception) {
//            console.error(e)
            emptyList()
        }

    /**
     * Executes an API call to fetch and process paginated data with filters and sorters applied.
     *
     * The method retrieves the current page number, page size, header filters, and sorters
     * from the associated jsTabulator instance. These parameters are then used to invoke a
     * promise that interacts with the server to fetch data. Upon receiving the data,
     * the method determines whether the content has changed by comparing hash codes.
     *
     * If the data differs from the current content, it replaces the existing content by
     * transforming the retrieved data into a Kotlin List using the `toKotlinList` method.
     *
     * The method relies on the following components:
     * - `jsTabulator`: An external tabulator instance to fetch page, filters, and sorters data.
     * - `promise`: A private function that handles the server call with the specified parameters
     *   including pagination, filters, and sorters.
     * - `diffContentHashCode`: A flag indicating whether the content has changed based on hash codes.
     * - `replaceData()`: Called to update the data if changes are detected.
     *
     * This function is designed to handle dynamic data efficiently and integrate with the
     * server-side logic to ensure the data displayed reflects the latest state based on the
     * applied filters and sorting.
     */
    internal fun apiCall() {
        val page: Int = jsTabulator?.getPage() as? Int ?: 1
        val size: Int = jsTabulator?.getPageSize()?.toInt() ?: 50
        val filters: List<RemoteFilter>? = jsTabulator?.getHeaderFilters()?.map {
            RemoteFilter(field = it.field, type = it.type, value = "${it.value}")
        }
        val sorters: List<RemoteSorter>? = jsTabulator?.getSorters()?.map {
            RemoteSorter(field = it.field, dir = it.dir)
        }
        promise(
            page = page,
            size = size,
            filters = filters,
            sorters = sorters,
        ).then { result: dynamic ->
//            console.warn("RESULT ->", result, "CONTENT_HASHCODE ->", contentHashCode, "diffContentHashCode", diffContentHashCode)
            if (diffContentHashCode) {
                if (result.data != undefined) {
                    val selectedRows = jsTabulator?.getSelectedData()?.map { it.asDynamic()["_id"] }?.toTypedArray()
                    replaceData(toKotlinList(result.data).toTypedArray())
                    selectedRows?.let { jsTabulator?.selectRow(selectedRows) }
                }
            }
        }
    }

    private fun promise(
        page: Int,
        size: Int,
        filters: List<RemoteFilter>?,
        sorters: List<RemoteSorter>?,
    ): Promise<dynamic> {
        val apiList = apiListBlock.invoke().apply {
            tabPage = page
            tabSize = size
            tabFilter = filters
            tabSorter = sorters
            contentHashCode = this@TabulatorViewList.contentHashCode
        }
        return KVScope.async {
            try {
                val jsonObj = this@TabulatorViewList.kvCallAgent.jsonRpcCall(
                    this@TabulatorViewList.url,
                    listOf(this@TabulatorViewList.apiListSerialize.invoke(apiList)),
                    this@TabulatorViewList.method
                ).let {
                    if (debug) {
                        console.warn("--- start of ${viewList.configView.viewKClass.simpleName} jsonRpcCall ---")
                        console.warn(it)
                        console.warn("--- end of ${viewList.configView.viewKClass.simpleName} jsonRpcCall ---")
                    }
                    JSON.parse<dynamic>(it)
                }
//                console.warn("${viewList.configView.viewKClass.simpleName} jsonObj", jsonObj)
                if (jsonObj.data == undefined) {
                    jsonObj.data = js("[]")
                }
                jsonObj.contentHashCode = JSON.stringify(jsonObj.data).hashCode()
                if (jsonObj.contentHashCode != undefined) {
                    this@TabulatorViewList.diffContentHashCode =
                        (jsonObj.contentHashCode as? Int) != this@TabulatorViewList.contentHashCode
                    this@TabulatorViewList.contentHashCode = jsonObj.contentHashCode as? Int
                }
                ((jsonObj.state as? String) == State.Error.name).also { errorState ->
                    if (this@TabulatorViewList.viewList.errorStateObs.value != errorState) {
                        this@TabulatorViewList.viewList.errorStateObs.value = errorState
                    }
                    this@TabulatorViewList.viewList.errorMessage = if (errorState) jsonObj.msgError as? String else null
                    if (errorState) {
                        Toast.danger(
                            message = this@TabulatorViewList.viewList.errorMessage ?: "Unknown error",
                            options = ToastOptions(avatar = "")
                        )
                    }
                }
                this@TabulatorViewList.viewList.onReceivingData(jsonObj.data)
                oldPage = jsTabulator?.getPage() as? Int ?: -1
                oldMaxPage = jsTabulator?.getPageMax() as? Int ?: -1
                jsTabulator?.setMaxPage(jsonObj.last_page as? Int ?: -1)
                jsonObj
            } catch (e: Exception) {
                console.error("Server call response error:", e.message)
                Toast.danger(
                    message = "Server call response error: ${e.message}",
                    options = ToastOptions(avatar = "")
                )
                js("{}")
            }
        }.asPromise()
    }

    init {
        ConfigViewList.serviceManager.requireCall(viewList.configView.apiListFun).let {
            url = it.first
            method = it.second
        }
        kvCallAgent = CallAgent()
        options.ajaxURL = urlPrefix + url.drop(1)
        options.ajaxRequestFunc = { _, _, params ->
            val page: Int = params?.asDynamic()?.page as? Int ?: 1
            val size: Int = params?.asDynamic()?.size as? Int ?: 50
            val filters = if (params?.asDynamic()?.filter != null) {
                Json.decodeFromString(
                    ListSerializer(RemoteFilter::class.serializer()),
                    JSON.stringify(params.asDynamic()?.filter)
                )
            } else {
                null
            }
            val sorters = if (params?.asDynamic()?.sort != null) {
                val j = js("[]")
                JSON.stringify(params.asDynamic()?.sort)
                js(
                    """
                    params.sort.forEach(function(value) {
                        j.push({"field": value["field"], "dir": value["dir"]})
                    })
                """
                )
//                console.warn(">>>", j)
                Json.decodeFromDynamic(ListSerializer(RemoteSorter::class.serializer()), j)
            } else {
                null
            }
            promise(
                page = page,
                size = size,
                filters = filters,
                sorters = sorters,
            ).also {
                clearStartTime()
            }
        }
    }
}

/**
 * Adds a new instance of a TabulatorViewList to the container and returns it. This function initializes
 * the TabulatorViewList with the provided settings and applies additional initialization logic if passed.
 *
 * @param CC The type of the container, which extends ICommonContainer.
 * @param T The type of items in the container, which extends BaseDoc.
 * @param ID The type of the identifier for items, which must be a non-nullable type.
 * @param FILT The type of the API filter used for querying, which extends IApiFilter.
 * @param MID The type of the master item identifier used in the API filter.
 * @param viewList The ViewList instance containing the data and configuration for the tabulator view.
 * @param apiListBlock A block that returns an instance of ApiList with the filter configuration.
 * @param apiListSerialize A function to serialize the instance of ApiList with the specified filter.
 * @param tabulatorOptions Configuration options for the tabulator, defaults to the options defined in the viewList.
 * @param types A set of TableType(s) that determine the type of the table, defaults to an empty set.
 * @param className An optional CSS class name to apply to the TabulatorViewList, defaults to null.
 * @param serializer A Kotlinx serialization serializer for the items of type T, defaults to null.
 * @param module An optional Kotlinx SerializersModule for custom serialization, defaults to null.
 * @param debug A flag to enable or disable debug mode for the TabulatorViewList, defaults to false.
 * @param init An optional block to apply additional initialization logic to the TabulatorViewList.
 * @return The created and initialized TabulatorViewList instance.
 */
fun <T : BaseDoc<ID>, ID : Any, FILT : IApiFilter<MID>, MID : Any> Container.tabulatorViewList(
    viewList: ViewList<T, ID, FILT, MID>,
    apiListBlock: (() -> ApiList<FILT>),
    apiListSerialize: (ApiList<FILT>) -> String?,
    tabulatorOptions: TabulatorOptions<T> = viewList.defaultTabulatorOptions(),
    types: Set<TableType> = setOf(),
    className: String? = null,
    serializer: KSerializer<T>? = null,
    module: SerializersModule? = null,
    debug: Boolean = false,
    init: (TabulatorViewList<T, ID, FILT, MID>.() -> Unit)? = null,
): TabulatorViewList<T, ID, FILT, MID> {
    val tabulatorViewList: TabulatorViewList<T, ID, FILT, MID> =
        TabulatorViewList(
            viewList = viewList,
            apiListBlock = apiListBlock,
            apiListSerialize = apiListSerialize,
            debug = debug,
            tabulatorOptions = tabulatorOptions,
            types = types,
            className = className,
            kClass = viewList.configView.commonContainer.itemKClass,
            serializer = serializer,
            module = module,
        )
    init?.invoke(tabulatorViewList)
    this.add(tabulatorViewList)
    return tabulatorViewList
}
