package com.fonrouge.fullStack.mongoDb

import com.fonrouge.base.api.*
import com.fonrouge.base.common.ICommonContainer
import com.fonrouge.base.model.BaseDoc
import com.fonrouge.base.model.UserSession
import com.fonrouge.base.state.ItemState
import com.fonrouge.base.state.ListState
import com.fonrouge.base.state.SimpleState
import com.fonrouge.base.state.State
import com.fonrouge.fullStack.FieldPath
import com.fonrouge.fullStack.repository.ConstructorCopier
import com.fonrouge.fullStack.repository.IRepository
import com.fonrouge.fullStack.repository.IRepository.Dependency
import com.fonrouge.fullStack.repository.PermissionRegistry
import com.mongodb.MongoCommandException
import com.mongodb.MongoSocketException
import com.mongodb.MongoTimeoutException
import com.mongodb.MongoWriteException
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.WriteModel
import com.mongodb.client.result.InsertOneResult
import com.mongodb.client.result.UpdateResult
import com.mongodb.reactivestreams.client.AggregatePublisher
import com.mongodb.reactivestreams.client.MongoCollection
import com.mongodb.reactivestreams.client.MongoDatabase
import io.ktor.server.application.*
import kotlinx.coroutines.*
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bson.Document
import org.bson.conversions.Bson
import org.litote.kmongo.*
import org.litote.kmongo.coroutine.CoroutineCollection
import org.litote.kmongo.coroutine.coroutine
import org.litote.kmongo.coroutine.toList
import org.litote.kmongo.property.KPropertyPath
import java.util.*
import kotlin.jvm.internal.PropertyReference1Impl
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.superclasses

/**
 * Represents a utility or service class for handling collections in MongoDB with support for
 * CRUD operations, filtering, lookup, and aggregation pipelines. It also supports advanced
 * processing configurations for handling API-driven data manipulations.
 *
 * @param commonContainer Centralized container for shared resources, such as filters or configurations.
 * @param debug Boolean flag enabling or disabling debug information and logging.
 * @param changeLogCollFun Function or logic for handling change logs in the collection.
 * @param dependencies Dependencies or services required by the class to operate.
 * @param coroutine Coroutine scope or context used for asynchronous operations.
 * @param lookupFun Functionality related to lookup management in queries or pipelines.
 * @param mongoDatabase Instance of the MongoDB database used by this collection.
 * @param mongoColl The MongoDB collection being managed by this class.
 * @param objName Name of the collection or object handled by this class.
 * @param readOnly Boolean flag indicating if the collection is in read-only mode.
 * @param resultFieldStack Stack or configuration for handling field results during operations.
 * @param readOnlyErrorMsg Error message used when trying to perform write operations in a read-only collection.
 * @param userCollFun Functionality specific to user-collection interactions.
 */
abstract class Coll<T : BaseDoc<ID>, ID : Any, FILT : IApiFilter<*>, UID : Any>(
    override val commonContainer: ICommonContainer<T, ID, FILT>,
    mongoDbBuilder: MongoDbBuilder? = null,
    private var debug: Boolean = false,
) : IRepository<T, ID, FILT, UID> {
    companion object {
        internal var roleInUserColl: IRoleInUserColl<*, *, *, *, *, *>? = null
        var MAX_RECURSIVE_RESULT_FIELD = 1

        internal fun friendlyExceptionMessage(e: Exception): String? {
            return when {
                e is MongoWriteException && e.code == 11000 -> {
                    val msg = e.error.message
                    val indexName = msg.substringAfter("index: ", "").substringBefore(" dup key:")
                    val dupKey = msg.substringAfter("dup key: ", "")
                    "Duplicate key error on index: $indexName, key: $dupKey"
                }

                e is MongoWriteException && e.code == 121 -> "Document validation failed"
                e is MongoWriteException && e.code == 11600 -> "Operation interrupted due to replica set state change"
                e is MongoWriteException && e.code == 50 -> "Operation exceeded time limit"
                e is MongoWriteException && e.code == 13 -> "Unauthorized operation"
                e is MongoCommandException && e.errorCode == 13 -> "Unauthorized operation"
                e is MongoTimeoutException -> "Database connection timed out"
                e is MongoSocketException -> "Database connection error"
                else -> e.message
            }
        }
    }


    /**
     * A property representing a function that returns an optional implementation
     * of `IChangeLogColl<*, *, *>`. By default, it is a lambda returning `null`.
     *
     * This function is intended to be overridden to provide a specific implementation
     * of `IChangeLogColl` if required. It can be used in scenarios where a
     * change log collection is needed for specific operations or tracking changes
     * in a collection-like structure.
     *
     * The return type, `IChangeLogColl<*, *, *>?`, allows for flexibility with
     * various types of change log collections while supporting optional behavior.
     */
    override val changeLogCollFun: (() -> IChangeLogColl<*, *, *>?) = { null }

    /**
     * Provides a list of dependencies that reference this collection.
     * When a document is deleted, this list is used to check for any existing references
     * to prevent orphaning data in dependent collections.
     *
     * @return A function that returns a list of [Dependency] objects describing the relationships
     * between this collection and other collections that depend on its documents, or null if there
     * are no dependencies
     */
    override val dependencies: (() -> List<Dependency<*, ID>>)? = null

    /**
     * A coroutine-based collection instance derived from a MongoDB collection.
     * This variable provides coroutine support for asynchronous operations
     * on the MongoDB collection, enabling more efficient and non-blocking database
     * interactions.
     *
     * @param T The type of documents stored within the MongoDB collection.
     */
    val coroutine: CoroutineCollection<T> get() = mongoColl.coroutine

    private val openMutex = Mutex()

    @Volatile
    private var opened = false

    /**
     * A function that takes a filter of type `FILT` and returns a list of `LookupPipelineBuilder` instances.
     *
     * This function is designed to perform lookups based on the provided filter and build a pipeline of
     * lookup operations. By default, it returns an empty list, indicating no lookups.
     *
     * @param FILT the type of the filter used for lookups.
     * @return a list of `LookupPipelineBuilder` instances based on the provided filter.
     */
    open val lookupFun: (FILT) -> List<LookupPipelineBuilder<T, *, *>> = { listOf() }

    val mongoDatabase: MongoDatabase = mongoDbBuilder?.getMongoDb() ?: com.fonrouge.fullStack.mongoDb.mongoDatabase

    val mongoColl: MongoCollection<T> =
        mongoDatabase.getCollection(
            commonContainer.itemKClass.collectionName,
            commonContainer.itemKClass.java
        )

    val objName: String
        get() = this::class.simpleName ?: run {
            this::class.superclasses[0].simpleName ?: "unknown"
        }

    /**
     * Indicates if the current instance should be read-only.
     *
     * This variable determines whether the instance can be modified or not.
     * If set to `true`, the instance is immutable and cannot be changed.
     * If set to `false`, the instance is mutable and can be altered.
     */
    override val readOnly = false

    /**
     * A mutable map that associates a `ResultField` with an integer value.
     * This map is used to track or store specific relationships or mappings
     * involving `ResultField` objects and their corresponding integer identifiers or counters.
     */
    internal val resultFieldStack = mutableMapOf<ResultField, Int>()

    override val readOnlyErrorMsg get() = "${commonContainer.labelItem} is read-only"

    /**
     * A function that represents a collection of users, returning an optional collection instance.
     * The returned collection implements the `IUserColl` interface with generic type parameters.
     *
     * This property allows for lazy or dynamic initialization of the user collection,
     * providing flexibility in determining the collection's behavior or content.
     *
     * Abstract and must be implemented by subclasses with a specific implementation
     * of the `IUserColl` interface.
     */
    abstract override val userCollFun: () -> IUserColl<*, UID, *>?

    /**
     * Handles the creation action for a given API item and inserts it into the data store.
     *
     * @param apiItem The API item containing the data and parameters required for the creation action.
     * @return The state of the created item after the insertion, encapsulated in an ItemState object.
     */
    protected suspend fun actionCreate(
        apiItem: ApiItem.Action.Create<T, ID, FILT>,
    ): ItemState<T> = insertOne(
        apiItem.copy(item = apiItem.item.copyItemWithPrimaryConstructorParameters())
    )

    /**
     * Executes an update action on the given API item and returns the updated item state.
     *
     * @param apiItem The update action containing the necessary data and filters to perform the update operation.
     * @return The state of the updated item after the operation is completed.
     */
    protected suspend fun actionUpdate(
        apiItem: ApiItem.Action.Update<T, ID, FILT>,
    ): ItemState<T> = updateOne(
        apiItem.copy(
            item = apiItem.item.copyItemWithPrimaryConstructorParameters(),
        )
    )

    /**
     * Executes the delete action for a specific item and returns the resulting state of the item.
     *
     * @param apiItem The configuration object containing the delete action,
     *                including the item to be deleted and related information.
     * @return The state of the item after the delete action has been performed.
     */
    protected suspend fun actionDelete(
        apiItem: ApiItem.Action.Delete<T, ID, FILT>,
    ): ItemState<T> = deleteOne(apiItem)

    /**
     * Executes actions or transformations after a lookup match stage.
     *
     * @param call The optional current application call context, which could be null when not applicable.
     * @param apiFilter The filter parameter of type `FILT` used for API filtering operations.
     * @param resultUnit The result unit of type `ResultUnit` used to process the outcome.
     * @return A BSON object resulting from the operation, or null if nothing is produced.
     */
    open fun afterLookupMatchStage(
        call: ApplicationCall? = null,
        apiFilter: FILT,
        resultUnit: ResultUnit,
    ): Bson? = null

    /**
     * Executes the sorting stage after a lookup operation, potentially applying additional filtering logic.
     *
     * @param call An optional `ApplicationCall` object that may contain necessary request context or parameters.
     * @param apiFilter The filter criteria of type `FILT` to apply to the lookup results.
     * @return A `Bson` object representing the sorting and filtering operations to be applied, or `null` if no operations are needed.
     */
    open fun afterLookupSortStage(call: ApplicationCall? = null, apiFilter: FILT): Bson? = null

    /**
     * Aggregates a MongoDB query pipeline and returns an `AggregatePublisher` for querying a collection.
     *
     * @param call Optionally specifies the `ApplicationCall` instance for the current request context.
     * @param pipeline A mutable list of `Bson` stages representing the aggregation pipeline; defaults to an empty list.
     * @param lookupWrappers A list of `LookupWrapper` objects used for constructing lookup stages in the pipeline.
     * @param apiFilter An instance of the filter applicable for the aggregation; default is provided by `commonContainer.apiFilterInstance()`.
     * @param apiRequestParams Optional parameters for customizing the API request, such as pagination.
     * @param countType Specifies the counting strategy used in the pipeline, such as pre-lookup or post-lookup.
     * @param resultUnit Specifies the unit of the resulting data, often used to define the data structure of the results.
     * @param debug A flag indicating whether debug information, such as the pipeline, should be printed; defaults to the class-level debug setting.
     * @param pageStateInfoFun An optional function that is invoked with `PageCountInfo` to provide state about paginated results.
     * @return An `AggregatePublisher` instance for querying the MongoDB collection with the constructed aggregation pipeline.
     */
    @Suppress("MemberVisibilityCanBePrivate")
    fun aggregateLookupPublisher(
        call: ApplicationCall? = null,
        pipeline: MutableList<Bson> = mutableListOf(),
        lookupWrappers: List<LookupWrapper<*, *>> = emptyList(),
        apiFilter: FILT = commonContainer.apiFilterInstance(),
        apiRequestParams: ApiRequestParams? = null,
        countType: CountType = CountType.PreLookup,
        resultUnit: ResultUnit,
        debug: Boolean = this.debug,
        pageStateInfoFun: ((PageCountInfo) -> Unit)? = null,
    ): AggregatePublisher<T> = buildAggregatePublisher(
        call = call,
        pipeline = pipeline,
        lookupWrappers = lookupWrappers,
        apiFilter = apiFilter,
        apiRequestParams = apiRequestParams,
        countType = countType,
        resultUnit = resultUnit,
        debug = debug,
        pageStateInfoFun = pageStateInfoFun,
    )

    /**
     * Performs an aggregation operation on a MongoDB collection using the specified pipeline,
     * lookup wrappers, and API filter, and returns an `AggregatePublisher` for further processing.
     *
     * @param pipeline Optional list of aggregation stages represented as a list of `Bson`.
     *                 If null, a default pipeline is generated based on the `apiFilter`, `lookupWrappers`, and `ResultUnit.Single`.
     * @param lookupWrappers A list of `LookupWrapper` instances to specify lookup operations during aggregation. Defaults to an empty list.
     * @param apiFilter An instance of `FILT` used to apply specific filters as part of the aggregation. Defaults to the common container's API filter instance.
     * @return An `AggregatePublisher` representing the result of the aggregation operation.
     */
    @Suppress("unused")
    fun aggregateSingleLookup(
        pipeline: List<Bson>? = null,
        lookupWrappers: List<LookupWrapper<*, *>> = emptyList(),
        apiFilter: FILT = commonContainer.apiFilterInstance(),
    ): AggregatePublisher<T> {
        val pipeline1 = pipeline ?: pipeline(
            apiFilter = apiFilter,
            lookupWrappers = lookupWrappers,
            resultUnit = ResultUnit.Single
        )
        if (debug) {
            printOutPipeline(pipeline1)
        }
        return mongoColl.aggregate(pipeline1, commonContainer.itemKClass.java)
    }

    /**
     * Processes an API item and performs appropriate CRUD or action tasks based on the API item's type.
     *
     * @param call The current application call, or null if none is provided.
     * @param iApiItem The API item to process, encapsulating the item and task information.
     * @param lookupWrappers A list of lookup wrappers to assist with data lookups during processing. Defaults to an empty list.
     * @return The resulting state of the processed item, including any success or error information.
     */
    @Suppress("unused")
    suspend fun apiItemProcess(
        call: ApplicationCall?,
        iApiItem: IApiItem<T, ID, FILT>,
        lookupWrappers: List<LookupWrapper<*, *>> = emptyList(),
    ): ItemState<T> {
        ensureOpen().also { if (it.hasError) return ItemState(it) }

        val apiItem: ApiItem<T, ID, FILT> = asApiItem(
            apiItem = iApiItem.asApiItem(commonContainer, call),
        ).let {
            val item = it.item
            if (it.hasError || item == null) {
                return ItemState(isOk = false, msgError = it.msgError)
            } else {
                item
            }
        }
        if (apiItem !is ApiItem.Query.Read && readOnly) return ItemState(isOk = false, msgError = readOnlyErrorMsg)
        return when (apiItem) {
            is ApiItem.Query -> {
                call?.let {
                    getCrudPermission(
                        call = call,
                        crudTask = apiItem.crudTask,
                    ).also {
                        if (it.state == State.Error) return ItemState(it)
                    }
                }
                when (apiItem) {
                    is ApiItem.Query.Create<T, ID, FILT> -> {
                        onQueryUpsert(apiItem = apiItem, orig = null).also { if (it.hasError) return it.asItemState() }
                        onQueryCreate(apiItem = apiItem).also { if (it.hasError) return it.asItemState() }
                        onQueryCreateItem(apiItem = apiItem)
                    }

                    is ApiItem.Query.Read<T, ID, FILT> -> {
                        onQueryRead(apiItem = apiItem).also { if (it.hasError) return it.asItemState() }
                        findItemStateById(
                            id = apiItem.id,
                            apiFilter = apiItem.apiFilter,
                            lookupWrappers = lookupWrappers
                        )
                    }

                    is ApiItem.Query.Update<T, ID, FILT> -> {
                        val itemState = findItemState(apiItem = apiItem, lookupWrappers = lookupWrappers)
                        val orig = itemState.item?.copyItemWithPrimaryConstructorParameters()
                        if (itemState.hasError || orig == null) return itemState
                        onQueryUpsert(apiItem = apiItem, orig = orig).also { if (it.hasError) return it.asItemState() }
                        onQueryUpdate(apiItem = apiItem, orig = orig).also { if (it.hasError) return it.asItemState() }
                        itemState
                    }

                    is ApiItem.Query.Delete<T, ID, FILT> -> {
                        val itemState = findItemStateById(
                            id = apiItem.id,
                            apiFilter = apiItem.apiFilter,
                            lookupWrappers = lookupWrappers
                        )
                        val item = itemState.item
                        if (itemState.hasError || item == null) return itemState
                        onQueryDelete(apiItem = apiItem, item = item).also { if (it.hasError) return it.asItemState() }
                        itemState
                    }
                }
            }

            is ApiItem.Action -> {
                // CONTRACT.md I5: gate the generic/remote write tier only; the low-level service-tier
                // methods bypass this. Reads (Query) are never gated.
                allowApiCrud(apiItem).also { if (it.hasError) return it.asItemState() }
                when (apiItem) {
                    is ApiItem.Action.Create<T, ID, FILT> -> actionCreate(apiItem = apiItem)
                    is ApiItem.Action.Update<T, ID, FILT> -> actionUpdate(apiItem = apiItem)
                    is ApiItem.Action.Delete<T, ID, FILT> -> actionDelete(apiItem = apiItem)
                }
            }
        }
    }

    /**
     * Processes a list for an API call, applying various stages, lookups, filters, and post-processing steps.
     *
     * @param call Optional ApplicationCall that might be used to fetch user session information.
     * @param apiRequestParams The initial stage of the list processing pipeline.
     * @param lookupWrappers A list of LookupWrapper instances for performing lookup operations in the pipeline.
     * @param apiFilter An instance of a filter to be applied to the API list.
     * @param countType Specifies the type of count operation to be performed.
     * @param debug Optional debug flag to control debug output.
     * @param postProcessList Optional function to further process the list after retrieval.
     * @return ListState containing the processed list data, pagination information, and state status.
     */
    @Suppress("MemberVisibilityCanBePrivate")
    suspend fun apiListProcess(
        call: ApplicationCall? = null,
        apiRequestParams: ApiRequestParams,
        lookupWrappers: List<LookupWrapper<*, *>> = emptyList(),
        apiFilter: FILT = commonContainer.apiFilterInstance(),
        countType: CountType = CountType.PreLookup,
        debug: Boolean = this.debug,
        postProcessList: ((List<T>) -> List<T>)? = null,
    ): ListState<T> {
        ensureOpen().also {
            if (it.hasError) return ListState(state = it.state, msgOk = it.msgOk, msgError = it.msgError)
        }

        call?.let {
            getCrudPermission(
                call = call,
                crudTask = CrudTask.Read,
            ).also {
                if (it.hasError) return ListState(state = State.Error, msgError = "User not authorized")
            }
        }
        var pageCountInfo: PageCountInfo? = null
        val publisher = aggregateLookupPublisher(
            call = call,
            lookupWrappers = lookupWrappers,
            apiFilter = apiFilter,
            apiRequestParams = apiRequestParams,
            countType = countType,
            debug = debug,
            pageStateInfoFun = {
                pageCountInfo = it
            },
            resultUnit = ResultUnit.List,
        )
        val curTime = Date().time
        var t1: Long? = null
        var t2: Long? = null
        var list: List<T> = emptyList()
        coroutineScope {
            val j1 = launch {
                list = publisher.toList()
                t1 = Date().time - curTime
            }
            val j2 = launch {
                apiRequestParams.pageSize?.let {
                    pageCountInfo?.count(this@Coll, apiRequestParams.pageSize)
                }
                t2 = Date().time - curTime
            }
            joinAll(j1, j2)
        }
        if (debug) {
            println("Class: ${commonContainer.itemKClass.simpleName} ('${commonContainer.itemKClass.collectionName}'), Aggregate time: ${t1}ms, Count time: ${t2}ms")
        }
        return ListState(
            data = postProcessList?.invoke(list) ?: list,
            last_page = pageCountInfo?.lastPage,
            last_row = pageCountInfo?.lastRow,
            state = State.Ok,
        )
    }

    /**
     * Processes a list for API responses with configurable match, sort, filter, and post-process stages.
     *
     * @param call Optional `ApplicationCall` context for the current API request, used for request details.
     * @param apiList The `ApiList` object containing configuration for pagination, filtering, sorting, and API-specific filters.
     * @param countType Specifies the type of count operation to be performed, either pre-lookup or post-lookup.
     * @param debug Optional flag to enable or disable debug mode for logging or tracing the operation.
     * @param lookupWrappers A list of `LookupWrapper` objects to be applied in the lookup pipeline.
     * @param postProcessList Optional lambda function to post-process the resulting list after fetching data.
     * @return A `ListState` object containing the processed list along with related metadata such as pagination information.
     */
    @Suppress("unused")
    suspend fun apiListProcess(
        call: ApplicationCall? = null,
        apiList: ApiList<FILT>,
        countType: CountType = CountType.PreLookup,
        debug: Boolean = this.debug,
        lookupWrappers: List<LookupWrapper<*, *>> = emptyList(),
        postProcessList: ((List<T>) -> List<T>)? = null,
    ): ListState<T> = apiListProcess(
        call = call,
        apiRequestParams = ApiRequestParams(
            page = apiList.tabPage,
            pageSize = apiList.tabSize,
            remoteFilters = apiList.tabFilter,
            remoteSorters = apiList.tabSorter,
        ),
        lookupWrappers = lookupWrappers,
        apiFilter = apiList.apiFilter,
        countType = countType,
        debug = debug,
        postProcessList = postProcessList
    )

    /**
     * Converts the provided `ApiItem` into an `ItemState` containing the given `ApiItem` instance.
     *
     * @param apiItem The API item to be wrapped in an ItemState.
     * @return An ItemState object that contains the given ApiItem.
     */
    override suspend fun asApiItem(
        apiItem: ApiItem<T, ID, FILT>,
    ): ItemState<ApiItem<T, ID, FILT>> = ItemState(item = apiItem)

    /**
     * Builds a lookup pipeline list based on the provided lookup wrappers and API filter.
     *
     * @param lookupWrappers A list of `LookupWrapper` instances that define the lookup configurations. Defaults to an empty list if not provided.
     * @param apiFilter The API filter instance used to determine the lookup functions and pipelines.
     * @return A mutable list of `Bson` representing the pipeline to be applied for the lookup operation.
     */

    /**
     * Performs a bulk write operation asynchronously on the provided list of write models.
     *
     * **Raw escape hatch (CONTRACT.md I7):** this bypasses **all** lifecycle hooks, the permission and
     * [allowApiCrud] gates, change-logging, and constructor-only-persistence stripping — it writes the
     * given [WriteModel]s straight to the driver on a detached background coroutine. It is **not** part
     * of the write-control story, and an `allowApiCrud` lockdown provides no protection against it. Use
     * only for trusted, server-side batch-performance work.
     *
     * @param writeModels A mutable list of write models to be written in bulk.
     * @param debug A boolean flag indicating whether to print debug information. Default is false.
     */
    @Suppress("unused")
    fun bulkWrite(writeModels: MutableList<WriteModel<T>>, debug: Boolean = false) {
        CoroutineScope(context = Dispatchers.IO).launch {
            if (writeModels.isNotEmpty()) {
                if (debug) {
                    println("BulkWrite ${writeModels.hashCode()} start with ${writeModels.size} items.")
                }
                val r = coroutine.bulkWrite(writeModels)
                if (debug) {
                    println("BulkWrite ${writeModels.hashCode()} result = ${r.insertedCount}")
                }
                writeModels.clear()
            }
        }
    }

    /**
     * Creates a copy of the current item by invoking its primary constructor with the specified field assignments
     * or the current values of the item's member properties.
     *
     * @param fieldAssignments A list of field assignments providing specific values to set for fields during copying.
     *                          If not provided, defaults to an empty list, and the current values of the item's properties are used.
     * @return A new instance of the item, created using its primary constructor with the specified or current values.
     */
    /**
     * Creates a copy of the current item by invoking its primary constructor with the specified field assignments
     * or the current values of the item's member properties. Delegates to [ConstructorCopier] for cached,
     * shared reflection logic.
     *
     * @param fieldAssignments A list of field assignments providing specific values to set for fields during copying.
     *                          If not provided, defaults to an empty array, and the current values of the item's properties are used.
     * @return A new instance of the item, created using its primary constructor with the specified or current values.
     */
    @Suppress("MemberVisibilityCanBePrivate")
    fun T.copyItemWithPrimaryConstructorParameters(
        vararg fieldAssignments: AssignTo<T, *> = emptyArray(),
    ): T {
        val overrides = fieldAssignments.associate { it.kField.name to it.value }
        return ConstructorCopier.copyWithConstructorParams(commonContainer.itemKClass, this, overrides)
    }

    /**
     * Deletes a single item from the collection based on the specified ID and an optional filter.
     *
     * @param id The unique identifier for the item to be deleted.
     * @param filter An optional BSON filter to further specify which item to delete.
     * @return The state of the item after the delete operation, encapsulated in an ItemState object.
     */
    @Suppress("unused")
    suspend fun deleteOne(
        id: ID,
        filter: Bson? = null,
    ): ItemState<T> {
        return deleteOne(
            apiItem = ApiItem.Action.Delete(
                item = coroutine.findOneById(id) ?: return ItemState(),
                apiFilter = commonContainer.apiFilterInstance(),
            ),
            filter = filter
        )
    }

    /**
     * Deletes a single item from the database based on the provided filter.
     *
     * @param apiItem The API item representing the action to perform the delete operation.
     * @param filter An optional filter to apply to the delete operation.
     *
     * @return The state of the delete operation. It contains information about whether the operation was successful or not,
     * as well as any error message in case of failure.
     */
    @Suppress("MemberVisibilityCanBePrivate")
    suspend fun deleteOne(
        apiItem: ApiItem.Action.Delete<T, ID, FILT>,
        filter: Bson? = null,
    ): ItemState<T> {
        if (readOnly) return ItemState(isOk = false, msgError = readOnlyErrorMsg)
        getCrudPermission(apiItem).also { if (it.state == State.Error) return ItemState(it) }
        onQueryDelete(
            apiItem = apiItem.asQuery as ApiItem.Query.Delete,
            item = apiItem.item
        ).also { if (it.hasError) return it.asItemState() }
        onBeforeDeleteAction(apiItem = apiItem).also { if (it.hasError) return it }
        findChildrenNot(apiItem.item).also { if (it.hasError) return it }
        var result: Boolean? = null
        return try {
            result = coroutine.deleteOne(
                and(
                    BaseDoc<*>::_id eq apiItem.item._id,
                    filter ?: EMPTY_BSON
                )
            ).deletedCount == 1L
            ItemState(
                isOk = result,
                msgOk = "Delete operation ok"
            )
        } catch (e: Exception) {
            ItemState(isOk = false, msgError = e.message)
        } finally {
            onAfterDeleteAction(apiItem = apiItem, result = result == true)
            if (result == true) {
                changeLogCollFun()?.buildChangeLog(cc = commonContainer, apiItem = apiItem, orig = null)
            }
        }
    }

    /**
     * Finds an entity by its ID with optional filters, API filter, and lookup wrappers.
     *
     * @param id The ID of the entity to find.
     * @param filter Optional Bson filter to apply additional query conditions.
     * @param apiFilter The API filter instance to apply to the query.
     * @param lookupWrappers List of LookupWrapper instances to specify lookup conditions for related collections.
     * @return The entity matching the provided ID and filters, or null if not found.
     */
    @Suppress("MemberVisibilityCanBePrivate")
    suspend fun findById(
        id: ID?,
        filter: Bson? = null,
        apiFilter: FILT = commonContainer.apiFilterInstance(),
        lookupWrappers: List<LookupWrapper<*, *>> = emptyList(),
    ): T? = findOne(and(BaseDoc<*>::_id eq id, filter), apiFilter, lookupWrappers)

    /**
     * Finds and checks child dependencies of the given item and determines if they are not valid.
     *
     * @param item The item of type T whose children dependencies are to be checked.
     * @return An [ItemState] representing the state of the item. It may include errors if any
     *         invalid child dependencies are detected.
     */
    /**
     * Checks whether any document exists where the given property matches the specified value.
     *
     * @param property The property to match against.
     * @param value The value to search for.
     * @return True if at least one matching document exists.
     */
    override suspend fun existsByField(property: KProperty1<out BaseDoc<*>, *>, value: Any?): Boolean {
        if (value == null) return false
        val (fieldName, collectionName) = when (property) {
            is FieldPath -> property.path to property.owner.collectionName
            is PropertyReference1Impl -> {
                @Suppress("UNCHECKED_CAST")
                property.name to (property.owner as KClass<out BaseDoc<*>>).collectionName
            }

            else -> property.name to commonContainer.itemKClass.collectionName
        }
        return mongoDatabase.getCollection(collectionName)
            .coroutine.find(Document(fieldName, value)).first() != null
    }

    @Suppress("MemberVisibilityCanBePrivate")
    override suspend fun findChildrenNot(
        item: T,
    ): ItemState<T> {
        val itemState = findItemStateById(item._id)
        if (itemState.hasError.not()) {
            dependencies?.invoke()?.forEach { dependency ->
                // If a cross-engine repository is provided, delegate to it
                val repoFun = dependency.repositoryFun
                if (repoFun != null) {
                    if (repoFun.invoke().existsByField(dependency.property, item._id)) {
                        return ItemState(
                            state = State.Error,
                            msgError = "'${commonContainer.labelItemId(item)}' has dependencies in '${dependency.common.labelList}'"
                        )
                    }
                    return@forEach
                }
                // Engine-specific MongoDB fallback
                val kProperty1: KProperty1<out BaseDoc<*>, ID?> = dependency.property
                when (kProperty1) {
                    is FieldPath -> kProperty1.path to kProperty1.owner.collectionName
                    is PropertyReference1Impl -> {
                        @Suppress("UNCHECKED_CAST")
                        kProperty1.name to (kProperty1.owner as KClass<out BaseDoc<*>>).collectionName
                    }

                    is KPropertyPath<*, ID?> -> return ItemState(
                        state = State.Error,
                        msgError = "Child field path '${commonContainer.itemKClass.simpleName}::${kProperty1.path()}' not valid (if you used '/' or '%' operators to describe Dependency::property you must use '+' operator)"
                    )

                    else -> null
                }?.let { (fieldName: String, collectionName) ->
                    mongoDatabase.getCollection(collectionName).also { mongoCollection ->
                        mongoCollection.coroutine.find(Document(fieldName, item._id)).first()?.let {
                            return ItemState(
                                state = State.Error,
                                msgError = "'${commonContainer.labelItemId(item)}' has dependencies in '${dependency.common.labelList}'"
                            )
                        }
                    }
                }
            }
        }
        return itemState
    }

    /**
     * Filters items based on the criteria specified in the given filter.
     *
     * @param apiFilter the filter criteria to apply when searching for items.
     * @return a BSON object representing the filter to be applied, or null if no filter is specified.
     */
    open fun findItemFilter(apiFilter: FILT): Bson? = null

    /**
     * Finds the state of an item based on the provided API query and lookup wrappers.
     *
     * @param apiItem An instance of ApiItem.Query containing the item's ID and filter.
     * @param lookupWrappers An optional list of LookupWrapper instances to perform additional lookups.
     * @return The state of the item as an ItemState instance.
     */
    @Suppress("MemberVisibilityCanBePrivate")
    suspend fun findItemState(
        apiItem: ApiItem<T, ID, FILT>,
        lookupWrappers: List<LookupWrapper<*, *>> = emptyList(),
    ): ItemState<T> {
        val id: ID = when (apiItem) {
            is ApiItem.Query.Create -> null
            is ApiItem.Query.Read -> apiItem.id
            is ApiItem.Query.Update -> apiItem.id
            is ApiItem.Query.Delete -> apiItem.id
            is ApiItem.Action.Create -> apiItem.item._id
            is ApiItem.Action.Update -> apiItem.item._id
            is ApiItem.Action.Delete -> apiItem.item._id
        } ?: return ItemState(isOk = false)
        return findItemStateById(
            id = id,
            apiFilter = apiItem.apiFilter,
            lookupWrappers = lookupWrappers,
        )
    }

    /**
     * Finds the state of an item by its identifier.
     *
     * @param id The identifier of the item.
     * @param apiFilter The API filter to be applied; defaults to commonContainer's API filter instance.
     * @param filter The BSON filter for querying the item; defaults to the result of the `findItemFilter` function.
     * @param lookupWrappers List of lookup wrappers to be used in the query; defaults to an empty list.
     * @return An [ItemState] containing the item or an error message if the item could not be found.
     */
    @Suppress("MemberVisibilityCanBePrivate")
    suspend fun findItemStateById(
        id: ID?,
        apiFilter: FILT = commonContainer.apiFilterInstance(),
        filter: Bson? = findItemFilter(apiFilter),
        lookupWrappers: List<LookupWrapper<*, *>> = emptyList(),
    ): ItemState<T> = try {
        ItemState(
            item = findById(id = id, filter = filter, apiFilter = apiFilter, lookupWrappers = lookupWrappers),
            msgError = "_id '$id' (${commonContainer.itemKClass.simpleName}) not found..."
        )
    } catch (e: Exception) {
        ItemState(isOk = false, msgError = e.message)
    }

    /**
     * Suspends the current coroutine and performs a query to find a list of documents from the database
     * based on the provided filter, lookup wrappers, and api filter.
     *
     * @param filter Optional Bson filter to apply to the query. Defaults to null.
     * @param lookupWrappers List of LookupWrapper instances to be used for lookups in the query. Defaults to an empty list.
     * @param apiFilter Instance of FILT used for additional API-level filtering. Defaults to an instance from the commonContainer.
     * @param debug Boolean flag to enable or disable debugging for the query. Defaults to false.
     * @return A list of documents of type T matching the query criteria.
     */
    @Suppress("unused")
    suspend fun findList(
        filter: Bson? = null,
        lookupWrappers: List<LookupWrapper<*, *>> = emptyList(),
        apiFilter: FILT = commonContainer.apiFilterInstance(),
        debug: Boolean = this.debug,
    ): List<T> = findPublisher(
        filter = filter,
        lookupWrappers = lookupWrappers,
        apiFilter = apiFilter,
        resultUnit = ResultUnit.List,
        debug = debug,
    ).toList()

    /**
     * Finds a single document based on the provided filter criteria and lookup wrappers.
     *
     * @param filter BSON filter to narrow down the search.
     * @param apiFilter An instance of FILT used to apply additional API-level filters.
     * @param lookupWrappers List of LookupWrapper instances for join operations.
     * @param debug Boolean flag to enable or disable debug mode.
     * @return The first document matching the filter criteria or null if no match is found.
     */
    @Suppress("MemberVisibilityCanBePrivate")
    suspend fun findOne(
        filter: Bson? = null,
        apiFilter: FILT = commonContainer.apiFilterInstance(),
        lookupWrappers: List<LookupWrapper<*, *>> = emptyList(),
        debug: Boolean = false,
    ): T? = findPublisher(
        filter = filter,
        lookupWrappers = lookupWrappers,
        apiFilter = apiFilter,
        resultUnit = ResultUnit.Single,
        debug = debug,
    ).awaitFirstOrNull()

    /**
     * Finds and returns an aggregate publisher for a single matching document.
     *
     * @param filter A BSON filter to apply, or null if no filter is provided.
     * @param apiFilter The API filter used for filtering data, defaulting to `commonContainer.apiFilterInstance()`.
     * @param lookupWrappers A list of lookup wrappers to be applied in the aggregation pipeline.
     * @param debug A flag indicating whether debug mode is enabled, default is false.
     * @return An `AggregatePublisher` that emits a single matching document.
     */
    @Suppress("unused")
    fun findOnePublisher(
        filter: Bson? = null,
        apiFilter: FILT = commonContainer.apiFilterInstance(),
        lookupWrappers: List<LookupWrapper<*, *>> = emptyList(),
        debug: Boolean = false,
    ): AggregatePublisher<T> = findPublisher(
        filter = filter,
        lookupWrappers = lookupWrappers,
        apiFilter = apiFilter,
        resultUnit = ResultUnit.Single,
        debug = debug,
    )

    /**
     * Finds a publisher with the specified criteria.
     *
     * @param filter Filter condition in BSON format. Defaults to null.
     * @param lookupWrappers List of lookup wrappers to be applied. Defaults to an empty list.
     * @param apiFilter An instance of the API filter. Defaults to `commonContainer.apiFilterInstance()`.
     * @param debug Flag to enable or disable debug mode. Defaults to false.
     * @return An instance of [AggregatePublisher] that runs the aggregate query with the given criteria.
     */
    @Suppress("MemberVisibilityCanBePrivate")
    fun findPublisher(
        filter: Bson? = null,
        lookupWrappers: List<LookupWrapper<*, *>> = emptyList(),
        apiFilter: FILT = commonContainer.apiFilterInstance(),
        resultUnit: ResultUnit = ResultUnit.List,
        debug: Boolean = false,
    ): AggregatePublisher<T> = aggregateLookupPublisher(
        pipeline = filter?.let { mutableListOf(match(filter)) } ?: mutableListOf(),
        lookupWrappers = lookupWrappers,
        apiFilter = apiFilter,
        resultUnit = resultUnit,
        debug = debug,
    )

    /**
     * Generates a fixed lookup list based on the provided API filter.
     *
     * @param apiFilter An optional filter parameter used to control or refine the lookup process.
     * By default, it retrieves the instance of the API filter from the common container.
     * @return A list of KProperty1 instances or null, representing the properties of the type `T`
     * that match the criteria defined by the provided API filter.
     */
    open fun fixedLookupList(
        apiFilter: FILT = commonContainer.apiFilterInstance(),
    ): List<KProperty1<in T, *>>? = null

    /**
     * Determines the CRUD (Create, Read, Update, Delete) permissions for a given API item.
     *
     * @param apiItem The API item containing details and context for evaluating permissions, including
     *                the associated call and CRUD task.
     * @return A [SimpleState] indicating whether the CRUD operations are permitted. Returns a successful
     *         state by default if no call is provided.
     */
    @Suppress("unused")
    suspend fun getCrudPermission(apiItem: ApiItem<T, ID, FILT>): SimpleState =
        checkCrudPermission(apiItem)

    /**
     * Determines the CRUD (Create, Read, Update, Delete) permission for a given user.
     *
     * @param call The ApplicationCall associated with the request, which may contain session information.
     * @param crudTask The specific CRUD task for which permission is being checked.
     * @return A SimpleState indicating whether the permission check was successful, including an error message if not.
     */
    override suspend fun getCrudPermission(
        call: ApplicationCall,
        crudTask: CrudTask,
    ): SimpleState = checkCrudPermission(call, crudTask)

    /**
     * Retrieves the user session associated with the current API call.
     *
     * @return An instance of [UserSession] of type [UID] if a user session exists, or null if no session is found.
     */
    @Suppress("unused")
    fun ApiItem<T, ID, FILT>.getUserSession(): UserSession<UID>? = userCollFun()?.userSessionFromCall(this.call)

    /**
     * Retrieves the indexes of the documents within the collection.
     *
     * This function is a coroutine and should be called within a coroutine scope.
     *
     * @receiver CoroutineCollection<T> The collection from which indexes are obtained.
     */
    open suspend fun CoroutineCollection<T>.indexes() {}

    /**
     * Inserts a single item into the data store with the provided filters and optional application call context.
     *
     * @param item The item to be inserted.
     * @param apiFilter The filter configuration to apply during the insertion operation.
     * @param call An optional application call context, which can be used to provide additional request information.
     * @return An instance of ItemState representing the state of the item after the insertion.
     */
    @Suppress("unused")
    override suspend fun insertOne(
        item: T,
        apiFilter: FILT,
        call: ApplicationCall?,
    ): ItemState<T> = insertOne(
        apiItem = ApiItem.Action.Create(
            item = item,
            apiFilter = apiFilter,
            call = call,
        ),
    )

    /**
     * Inserts a single item into the data store.
     *
     * @param apiItem The API item containing the query and filter information for the insert operation.
     * @param item The item to be inserted.
     * @return The state of the item after the insert operation, with a flag indicating if the item was already present.
     */
    @Suppress("unused")
    suspend fun insertOne(
        apiItem: ApiItem.Query.Create<T, ID, FILT>,
        item: T,
    ): ItemState<T> = insertOne(
        apiItem = ApiItem.Action.Create(item = item, apiFilter = apiItem.apiFilter),
    ).copy(itemAlreadyOn = true)

    /**
     * Inserts a single item into the collection.
     *
     * @param apiItem The item to be inserted, wrapped in an Upsert.Create.Action object.
     * @return An ItemState indicating the result of the insertion.
     */
    @Suppress("MemberVisibilityCanBePrivate")
    suspend fun insertOne(
        apiItem: ApiItem.Action.Create<T, ID, FILT>,
    ): ItemState<T> {
        if (readOnly) return ItemState(isOk = false, msgError = readOnlyErrorMsg)
        getCrudPermission(apiItem).also { if (it.state == State.Error) return ItemState(it) }
        onQueryUpsert(
            apiItem = apiItem.asQuery as ApiItem.Query.Create,
            orig = null
        ).also { if (it.hasError) return it.asItemState() }
        onQueryCreate(apiItem.asQuery as ApiItem.Query.Create).also { if (it.hasError) return it.asItemState() }
        var apiItem1 = apiItem.copy(item = apiItem.item)
        onBeforeUpsertAction(apiItem = apiItem1, orig = null).also { it ->
            if (it.hasError) return it
            it.item?.let { apiItem1 = apiItem1.copy(item = it) }
        }
        onBeforeCreateAction(apiItem1).also { it ->
            if (it.hasError) return it
            it.item?.let { apiItem1 = apiItem1.copy(item = it) }
        }
        // Strip body properties and validate BEFORE the write region. A validation rejection
        // must short-circuit here so it cannot fire the after-hooks or write a phantom
        // change-log entry in the `finally` block (mirrors SqlRepository.insertOne ordering).
        apiItem1 = apiItem1.copy(item = apiItem1.item.copyItemWithPrimaryConstructorParameters())
        onValidate(apiItem1, apiItem1.item).also { if (it.hasError) return it.asItemState() }
        var result: Boolean? = null
        return try {
            val insertOneResult: InsertOneResult = mongoColl.insertOne(
                apiItem1.item,
            ).awaitSingle()
            result = insertOneResult.insertedId != null
            ItemState(item = apiItem1.item, state = if (result) State.Ok else State.Error)
        } catch (e: Exception) {
            ItemState(isOk = false, msgError = friendlyExceptionMessage(e))
        } finally {
            onAfterCreateAction(apiItem = apiItem1, result = result == true)
            onAfterUpsertAction(apiItem = apiItem1, orig = null, result = result == true)
            // Only log a change when the insert actually succeeded; a failed/exception-thrown
            // insert (or an upstream validation return) must not produce a change-log entry.
            if (result == true) {
                changeLogCollFun()?.buildChangeLog(cc = commonContainer, apiItem = apiItem1, orig = null)
            }
        }
    }

    /**
     * Generates a list of BSON objects based on the provided property and parameters.
     *
     * @param property The property of type [KProperty1] to use for the lookup.
     * @param apiFilter An optional filter of type [FILT] used to dictate API handling logic, with a default instance provided by `commonContainer.apiFilterInstance()`.
     * @param lookupWrappers A list of [LookupWrapper] objects for additional lookup processing, with an empty list as the default.
     * @return A list of BSON objects if a match is found, or null if no match exists.
     */
    @Suppress("unused")
    fun lookupByProperty(
        property: KProperty1<T, *>,
        apiFilter: FILT = commonContainer.apiFilterInstance(),
        lookupWrappers: List<LookupWrapper<*, *>> = emptyList(),
    ): List<Bson>? = lookupFun(apiFilter).firstOrNull { it.resultProperty.name == property.name }?.toPipeline(
        lookupWrappers = lookupWrappers
    )

    /**
     * Builds a BSON representation of the given filter to be used in a MongoDB aggregation match stage.
     *
     * @param call The optional ApplicationCall instance that may be used during matching. Defaults to null.
     * @param apiFilter The filter criteria of type FILT used for the matching process.
     * @param resultUnit The result unit of type ResultUnit utilized in the matching operation.
     * @return A Bson object representing the matched result or null if no match is found.
     */
    open fun matchStage(
        call: ApplicationCall? = null,
        apiFilter: FILT,
        resultUnit: ResultUnit,
    ): Bson? = null

    /**
     * Transforms data in the pipeline before lookups are created, allowing aggregation and restructuring
     * of records. For example, can convert individual sales records into summarized sales reports
     * or reshape document structure. An typical application is using a mongodb group stage or a projection of
     * resulting fields, or adding new calculated fields.
     *
     * @param call The optional ApplicationCall instance that may be used during matching. Defaults to null.
     * @param pipeline The mutable list of BSON objects representing the pipeline that needs to be processed.
     * @param apiFilter The filter instance to be applied during the morphing stage. Defaults to a common container's API filter instance.
     * @param apiRequestParams The parameters of the API request used for modifying or adapting the pipeline.
     * @param resultUnit The resulting unit specifying the output or transformation target of the morphing process.
     */
    open fun morphingStage(
        call: ApplicationCall? = null,
        pipeline: MutableList<Bson>,
        apiFilter: FILT = commonContainer.apiFilterInstance(),
        apiRequestParams: ApiRequestParams?,
        resultUnit: ResultUnit,
    ) {
    }

    /**
     * This method is executed after a delete action is performed.
     *
     * @param apiItem Represents the API item on which the delete action was performed, containing details about the action.
     * @param result The result of the delete action, where true indicates success and false indicates failure.
     */
    override suspend fun onAfterDeleteAction(
        apiItem: ApiItem.Action.Delete<T, ID, FILT>,
        result: Boolean,
    ) = Unit

    /**
     * Function to be called immediately after an entity is opened.
     * This is a suspend function, allowing for asynchronous operations.
     *
     * Can be overridden to provide specific behavior upon opening.
     */
    // Idempotent, retryable init. ensureOpen() calls this from the generic API; direct service-tier
    // callers must call open() at boot for readiness before low-level use (CONTRACT I4).
    override suspend fun open(): SimpleState = openMutex.withLock {
        if (opened) return SimpleState(isOk = true)
        try {
            with(coroutine) {
                onAfterOpen()
                indexes()
            }
            opened = true
            SimpleState(isOk = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SimpleState(isOk = false, msgError = "Repository init failed: ${e.message}")
        }
    }

    override suspend fun onAfterOpen() = Unit

    /**
     * This method is invoked after the "upsert create" action takes place.
     *
     * @param apiItem the API item that represents the "upsert create" action.
     * @param result a boolean indicating the success or failure of the action.
     */
    override suspend fun onAfterCreateAction(
        apiItem: ApiItem.Action.Create<T, ID, FILT>,
        result: Boolean,
    ) = Unit

    /**
     * Executes custom logic after an update action has been performed.
     *
     * @param apiItem The API update action details, including its parameters and context.
     * @param orig The original item before the update operation.
     * @param result The result of the update operation, indicating success or failure.
     */
    override suspend fun onAfterUpdateAction(
        apiItem: ApiItem.Action.Update<T, ID, FILT>,
        orig: T,
        result: Boolean,
    ) = Unit

    /**
     * Executes after an upsert action is performed.
     *
     * @param apiItem Represents the API action metadata, including type and filters.
     * @param orig The original item before the upsert action. Can be null if no prior item existed.
     * @param result Indicates the success status of the upsert action.
     */
    override suspend fun onAfterUpsertAction(
        apiItem: ApiItem.Action<T, ID, FILT>,
        orig: T?,
        result: Boolean,
    ) = Unit

    /**
     * Called before the delete action is performed. This method can be overridden
     * to execute any necessary logic or checks prior to the deletion of an item.
     *
     * @param apiItem An instance of ApiItem.Action.Delete containing the details
     *                of the delete action to be performed, including the target
     *                entity type, ID, and filters.
     * @return An ItemState object representing the state or outcome of the operation,
     *         with the default implementation returning a successful state.
     */
    override suspend fun onBeforeDeleteAction(apiItem: ApiItem.Action.Delete<T, ID, FILT>): ItemState<T> =
        ItemState(isOk = true)

    /**
     * This method is called before the upsert create action.
     * It allows for custom pre-processing of the upsert create action.
     *
     * @param apiItem The ApiItem containing the upsert create action details, including the item and its associated metadata.
     * @return The resulting state of the item after any necessary transformations or validations.
     */
    override suspend fun onBeforeCreateAction(apiItem: ApiItem.Action.Create<T, ID, FILT>): ItemState<T> =
        ItemState(isOk = true)

    /**
     * This method is called before performing an upsert update action. It allows
     * for any necessary preprocessing or validation of the update action.
     *
     * @param apiItem The update action item containing the item to be updated and related metadata.
     * @return The state of the item after the preprocessing or validation step.
     */
    override suspend fun onBeforeUpdateAction(
        apiItem: ApiItem.Action.Update<T, ID, FILT>,
        orig: T,
    ): ItemState<T> = ItemState(isOk = true)

    /**
     * This method is executed before the upsert operation for a given API item.
     * It allows performing any custom processing or validation on the item
     * prior to the upsert action.
     *
     * @param apiItem The API item representing the action, including its metadata and context.
     * @param orig The original instance of the item being upserted, if it exists.
     * @return An ItemState object representing the state after processing, including whether the operation is allowed to proceed.
     */
    override suspend fun onBeforeUpsertAction(
        apiItem: ApiItem.Action<T, ID, FILT>,
        orig: T?
    ): ItemState<T> = ItemState(isOk = true)

    /**
     * Handles the deletion query operation for a given item.
     *
     * @param apiItem The API query object representing the delete operation, containing metadata such as type, ID, and filters.
     * @param item The item of type T that is being queried for deletion.
     * @return whether the delete query gate permits the operation
     */
    override suspend fun onQueryDelete(
        apiItem: ApiItem.Query.Delete<T, ID, FILT>,
        item: T
    ): SimpleState = SimpleState(state = State.Ok)

    /**
     * Handles the read permission check for the given API item.
     *
     * @param apiItem The API item for which the read permission is being checked.
     * It contains the item details and the ID.
     * @return The current state of the item after checking the read permission.
     */
    override suspend fun onQueryRead(apiItem: ApiItem.Query.Read<T, ID, FILT>): SimpleState =
        SimpleState(isOk = true)

    /**
     * Handles the creation or update of a permission in the system.
     *
     * @param apiItem The entity that contains the data required for creating or updating the permission.
     * @return The state of the item after the upsert operation.
     */
    override suspend fun onQueryCreate(apiItem: ApiItem.Query.Create<T, ID, FILT>): SimpleState =
        SimpleState(isOk = true)

    /**
     * Handles the query creation and returns an item for the requester
     *
     * @param apiItem The query object containing the information required to create an item.
     *                It includes data related to the type, ID, and filter criteria.
     * @return an [ItemState] containing the item model to be created
     */
    override suspend fun onQueryCreateItem(apiItem: ApiItem.Query.Create<T, ID, FILT>): ItemState<T> =
        ItemState(isOk = true)

    /**
     * Handles the update operation for a given item with permissions.
     *
     * @param apiItem The API item update operation containing necessary information.
     * @param orig The item to be updated.
     * @return The state of the item after the update operation.
     */
    override suspend fun onQueryUpdate(
        apiItem: ApiItem.Query.Update<T, ID, FILT>,
        orig: T
    ): SimpleState = SimpleState(isOk = true)

    /**
     * Invoked before the upsert operation is performed on the given query item.
     *
     * @param apiItem The Query API item containing the entity and associated query parameters.
     * @param orig The original entity object, if it exists. Can be null if no prior entity exists.
     * @return A SimpleState object indicating whether the pre-upsert process was successful or not.
     */
    override suspend fun onQueryUpsert(
        apiItem: ApiItem.Query<T, ID, FILT>,
        orig: T?
    ): SimpleState = SimpleState(isOk = true)

    /**
     * Validates the provided item based on the given API item context.
     *
     * @param apiItem The API item context containing configuration and rules for validation.
     * @param item The item of type T that needs to be validated.
     * @return A SimpleState object indicating the validation result, with isOk set to true if valid.
     */
    override suspend fun onValidate(apiItem: ApiItem.Action<T, ID, FILT>, item: T): SimpleState =
        SimpleState(isOk = true)

    /**
     * Constructs and modifies a MongoDB aggregation pipeline based on class-defined match and sort stages.
     *
     * @param call The optional ApplicationCall instance that may be used during matching. Defaults to null.
     * @param apiFilter The filter object used to determine match and sort stages. Defaults to the common container's API filter instance.
     * @param apiRequestParams Optional request parameters that may include post-lookup match conditions.
     * @param lookupWrappers A list of lookup wrapper objects used to build lookup stages. Defaults to an empty list.
     * @param resultUnit Specifies the result-related configurations to refine or transform the pipeline.
     * @return A mutable list of Bson objects representing the constructed and refined aggregation pipeline.
     */
    fun pipeline(
        call: ApplicationCall? = null,
        apiFilter: FILT = commonContainer.apiFilterInstance(),
        apiRequestParams: ApiRequestParams? = null,
        lookupWrappers: List<LookupWrapper<*, *>> = emptyList(),
        resultUnit: ResultUnit,
    ): MutableList<Bson> = buildPipeline(
        call = call,
        apiFilter = apiFilter,
        apiRequestParams = apiRequestParams,
        lookupWrappers = lookupWrappers,
        resultUnit = resultUnit,
    )

    /**
     * Constructs a MongoDB aggregation pipeline based on the provided parameters.
     *
     * @param apiFilter an instance of the filter to apply to the pipeline, defaulting to a common filter.
     * @param matchStage an optional `Bson` match stage to add to the pipeline, or null if no match is required.
     * @param sortStage an optional `Bson` sort stage to apply to the pipeline, or null if no sorting is required.
     * @param lookupWrappers a list of `LookupWrapper` objects defining lookup stages to include in the pipeline, defaults to an empty list.
     * @param refactor an optional lambda function that takes the constructed pipeline as input and allows modification or replacement of it. Returns the final pipeline list.
     * @return a list of `Bson` objects representing the constructed MongoDB aggregation pipeline.
     */
    @Suppress("unused")
    fun pipelineByHand(
        apiFilter: FILT = commonContainer.apiFilterInstance(),
        matchStage: Bson? = null,
        sortStage: Bson? = null,
        lookupWrappers: List<LookupWrapper<*, *>> = emptyList(),
        refactor: ((MutableList<Bson>) -> List<Bson>)? = null,
    ): List<Bson> {
        val pipeline: MutableList<Bson> = mutableListOf()
        matchStage?.let { pipeline += match(it) }
        sortStage?.let { pipeline += sort(it) }
        pipeline += buildLookupList(lookupWrappers = lookupWrappers, apiFilter = apiFilter)
        return refactor?.invoke(pipeline) ?: pipeline
    }

    /**
     * Prints out the details of the aggregate pipeline for a specific collection.
     *
     * @param pipeline The list of Bson stages representing the aggregation pipeline that will be printed.
     */
    /**
     * Refactors the given pipeline right after the [buildLookupList] fun by applying a result unit and an API filter.
     *
     * @param call The optional ApplicationCall instance that may be used during matching. Defaults to null.
     * @param pipeline a mutable list of Bson elements representing the data pipeline
     * @param resultUnit an instance of the ResultUnit to apply to the pipeline
     * @param apiFilter an instance of FILT filter to apply to the pipeline, with a default of commonContainer.apiFilterInstance()
     * @return the refactored pipeline as a mutable list of Bson elements
     */
    open fun refactorPipeline(
        call: ApplicationCall? = null,
        pipeline: MutableList<Bson> = mutableListOf(),
        apiFilter: FILT = commonContainer.apiFilterInstance(),
        apiRequestParams: ApiRequestParams? = null,
        resultUnit: ResultUnit,
    ) {
    }

    /**
     * Generates a MongoDB BSON sort stage based on the provided filter.
     *
     * @param call The optional ApplicationCall instance that may be used during matching. Defaults to null.
     * @param apiFilter the filter object specifying the sorting configuration.
     * @return a BSON object representing the sort stage, or null if no sort stage is defined.
     */
    open fun sortStage(call: ApplicationCall? = null, apiFilter: FILT): Bson? = null

    /**
     * Updates the fields of an item identified by its ID with the specified field assignments.
     *
     * **Mongo-only / engine-coupled (CONTRACT.md I7, N6):** this method exists only on [Coll] — there
     * is no `SqlRepository`/`InMemoryRepository` equivalent — so domain-service code built on it is
     * coupled to MongoDB. As a low-level service-tier method it runs the full hook / validation /
     * change-log lifecycle but **bypasses** the generic-CRUD gate ([allowApiCrud]); a non-null [call]
     * engages the per-action permission check.
     *
     * @param call An optional [ApplicationCall] used for permission checks or additional context during the update operation.
     * @param id The identifier of the item to be updated.
     * @param fieldAssignments A variable number of field assignments specifying the fields to update and their new values.
     * @param apiFilter The API filter instance to apply during the update operation, defaults to `commonContainer.apiFilterInstance()`.
     * @param filter An optional BSON filter to further restrict the item selection for updating.
     * @param newItem An optional new item to insert if no item matching the ID is found.
     * @return An [ItemState] representing the result of the update operation, including success or error status, and any accompanying error message.
     */
    suspend fun updateFieldsById(
        call: ApplicationCall? = null,
        id: ID,
        vararg fieldAssignments: AssignTo<T, *>,
        apiFilter: FILT = commonContainer.apiFilterInstance(),
        filter: Bson? = null,
        newItem: T? = null
    ): ItemState<T> {
        if (readOnly) return ItemState(isOk = false, msgError = readOnlyErrorMsg)
        fieldAssignments.forEach { it ->
            if (it.kField.returnType.isMarkedNullable.not() && it.value == null) {
                return ItemState(
                    isOk = false,
                    msgError = "Field '${it.kField.name}' is marked as non-nullable, but null value provided."
                )
            }
        }
        call?.let {
            val s: SimpleState = getCrudPermission(call = it, crudTask = CrudTask.Update)
            if (s.hasError) return ItemState(isOk = false, msgError = s.msgError)
        }
        val orig = findById(id = id, filter = filter)?.copyItemWithPrimaryConstructorParameters() ?: run {
            if (newItem == null) return ItemState(
                isOk = false,
                msgError = "Orig item not found"
            )
            insertOne(item = newItem, apiFilter = apiFilter, call = call).also {
                if (it.item == null) return ItemState(isOk = false, msgError = it.msgError)
            }
            findById(id = id, filter = filter) ?: return ItemState(isOk = false, msgError = "New item not found")
        }
        var apiItem = ApiItem.Action.Update(
            item = orig.copyItemWithPrimaryConstructorParameters(
                *fieldAssignments
            ),
            apiFilter = apiFilter,
            call = call,
        )
        // CONTRACT.md I1: shared Upsert hook outermost — upsert→specific on both query gates and
        // before-hooks, symmetric with create (P2.2).
        onQueryUpsert(
            apiItem = apiItem.asQuery as ApiItem.Query.Update,
            orig = orig
        ).also { if (it.hasError) return it.asItemState() }
        onQueryUpdate(
            apiItem = apiItem.asQuery as ApiItem.Query.Update,
            orig = orig
        ).also { if (it.hasError) return it.asItemState() }
        onBeforeUpsertAction(apiItem = apiItem, orig = orig).also { it ->
            if (it.hasError) return it
            it.item?.let {
                apiItem = apiItem.copy(item = it)
            }
        }
        onBeforeUpdateAction(apiItem = apiItem, orig = orig).also { it ->
            if (it.hasError) return it
            it.item?.let {
                apiItem = apiItem.copy(item = it)
            }
        }
        // CONTRACT.md I2: strip body properties, short-circuit on no-op, and validate BEFORE the
        // write `try`. A no-change skip or a validation rejection must return without reaching the
        // catch's after-hooks (mirrors insertOne / SqlRepository ordering).
        apiItem = apiItem.copy(item = apiItem.item.copyItemWithPrimaryConstructorParameters())
        if (apiItem.item.json == orig.json) return ItemState(
            state = State.Warn,
            msgError = "Update skipped - no changes detected in item"
        )
        onValidate(
            apiItem,
            apiItem.item
        ).also { if (it.hasError) return it.asItemState() }
        /* Can't use here UpdateOptions because an orig item is required to be passed to upsertOne() */
        val result: UpdateResult = try {
            coroutine.updateOne(
                filter = and(BaseDoc<*>::_id eq id, filter ?: EMPTY_BSON),
                target = apiItem.item,
            )
        } catch (e: Exception) {
            onAfterUpdateAction(apiItem = apiItem, orig = orig, result = false)
            onAfterUpsertAction(apiItem = apiItem, orig = orig, result = false)
            return ItemState(isOk = false, msgError = friendlyExceptionMessage(e))
        }
        val itemState = when (result.modifiedCount) {
            1L -> findItemStateById(id = id)
            0L -> ItemState(state = State.Warn, msgError = "Field not modified")
            else -> ItemState(isOk = false)
        }
        onAfterUpdateAction(apiItem = apiItem, orig = orig, result = itemState.hasError.not())
        onAfterUpsertAction(apiItem = apiItem, orig = orig, result = itemState.hasError.not())
        if (itemState.hasError.not()) {
            changeLogCollFun()?.buildChangeLog(cc = commonContainer, apiItem = apiItem, orig = orig)
        }
        return itemState
    }

    /**
     * Updates multiple documents in the collection that match the specified filter using the provided update pipeline.
     *
     * Note: this method is not intended for general use but is rather a helper method to support specific use cases.
     *
     * **Raw escape hatch (CONTRACT.md I7):** this bypasses **all** lifecycle hooks, the permission and
     * [allowApiCrud] gates, change-logging, and body-property stripping, writing directly to the driver.
     * It is **not** part of the write-control story.
     *
     * https://www.mongodb.com/docs/manual/tutorial/update-documents-with-aggregation-pipeline/
     *
     * @param filter the criteria used to filter documents that need to be updated.
     * @param pipeline a list of aggregation pipeline stages describing the updates to apply to the matching documents.
     * @param updateOptions optional settings to control the update operation behavior, such as upsert.
     * @return an UpdateResult containing information about the update operation, including the number of documents matched and modified.
     */
    @Suppress("unused")
    suspend fun updateMany(
        filter: Bson,
        pipeline: List<Bson>,
        updateOptions: UpdateOptions = UpdateOptions(),
    ): UpdateResult = coroutine.collection.updateMany(
        filter,
        pipeline,
        updateOptions
    ).awaitSingle()

    /**
     * Updates a single item in the database.
     *
     * @param item The item to be updated.
     * @param filter The filter to identify the item to be updated. Defaults to null.
     * @param apiFilter The API filter instance for the update operation. Defaults to a common API filter instance.
     * @param updateOptions Options to apply during the update operation. Defaults to an instance of UpdateOptions.
     * @param call The optional ApplicationCall instance that may be used during matching. Defaults to null.
     * @return The state of the item after the update operation.
     */
    @Suppress("unused")
    suspend fun updateOne(
        item: T,
        filter: Bson? = null,
        apiFilter: FILT = commonContainer.apiFilterInstance(),
        updateOptions: UpdateOptions = UpdateOptions(),
        call: ApplicationCall? = null,
    ): ItemState<T> = updateOne(
        apiItem = ApiItem.Action.Update(
            item = item.copyItemWithPrimaryConstructorParameters(),
            apiFilter = apiFilter,
            call = call,
        ),
        filter = filter,
        updateOptions = updateOptions
    )

    /**
     * Updates a single item in the database based on the provided filter.
     *
     * @param apiItem The API item containing the item id [ApiItem] to update and other relevant information.
     * @param filter The filter to apply when updating the item. If null, no filter will be applied.
     * @param updateOptions The options to use when updating the item.
     *
     * @return The state of the item after the update operation.
     */
    @Suppress("MemberVisibilityCanBePrivate")
    suspend fun updateOne(
        apiItem: ApiItem.Action.Update<T, ID, FILT>,
        filter: Bson? = null,
        updateOptions: UpdateOptions = UpdateOptions(),
    ): ItemState<T> {
        if (readOnly) return ItemState(isOk = false, msgError = readOnlyErrorMsg)
        getCrudPermission(apiItem).also { if (it.state == State.Error) return ItemState(it) }
        val orig: T? = findById(
            id = apiItem.item._id,
            filter = filter,
            apiFilter = apiItem.apiFilter
        )?.copyItemWithPrimaryConstructorParameters() //?: return ItemState(isOk = false, msgError = "Item not found")
        if (updateOptions.isUpsert.not() && orig == null) return ItemState(
            isOk = false,
            msgError = "Orig item not found"
        )
        orig?.let {
            onQueryUpsert(
                apiItem = apiItem.asQuery as ApiItem.Query.Update,
                orig = orig
            ).also { if (it.hasError) return it.asItemState() }
            onQueryUpdate(
                apiItem = apiItem.asQuery as ApiItem.Query.Update,
                orig = orig
            ).also { if (it.hasError) return it.asItemState() }
        }
        var apiItem1 = apiItem.copy(item = apiItem.item)
        // CONTRACT.md I1: shared Upsert hook outermost — onBeforeUpsertAction first (it also runs on
        // the upsert-insert path), then onBeforeUpdateAction only when an original exists (P2.2).
        onBeforeUpsertAction(apiItem = apiItem1, orig = orig).also { it ->
            if (it.hasError) return it
            it.item?.let {
                apiItem1 = apiItem1.copy(item = it)
            }
        }
        orig?.let {
            onBeforeUpdateAction(apiItem = apiItem1, orig = orig).also { it ->
                if (it.hasError) return it
                it.item?.let {
                    apiItem1 = apiItem1.copy(item = it)
                }
            }
        }
        val filter1 = and(BaseDoc<ID>::_id eq apiItem1.item._id, filter ?: EMPTY_BSON)
        // CONTRACT.md I2: strip body properties, short-circuit on no-op, and validate BEFORE the
        // write `try`, so a no-change skip or a validation rejection returns without reaching the
        // catch's after-hooks (mirrors insertOne / SqlRepository ordering).
        apiItem1 = apiItem1.copy(item = apiItem1.item.copyItemWithPrimaryConstructorParameters())
        if (orig != null && apiItem1.item.json == orig.json) return ItemState(
            state = State.Warn,
            msgError = "Update skipped - no changes detected in item"
        )
        onValidate(
            apiItem1,
            apiItem1.item
        ).also { if (it.hasError) return it.asItemState() }
        val updateResult = try {
            mongoColl.coroutine.updateOne(
                filter = filter1,
                target = apiItem1.item,
                options = updateOptions
            )
        } catch (e: Exception) {
            orig?.let { onAfterUpdateAction(apiItem = apiItem1, orig = orig, result = false) }
            onAfterUpsertAction(apiItem = apiItem1, orig = orig, result = false)
            return ItemState(isOk = false, msgError = friendlyExceptionMessage(e))
        }
        val state: State
        val noDataModified: Boolean
        if (updateResult.matchedCount > 0) {
            if (updateResult.modifiedCount == 1L) {
                state = State.Ok
                noDataModified = false
            } else {
                state = State.Warn
                noDataModified = true
            }
        } else {
            if (updateOptions.isUpsert && updateResult.upsertedId != null) {
                state = State.Ok
                noDataModified = false
            } else {
                state = State.Error
                noDataModified = true
            }
        }
        orig?.let { onAfterUpdateAction(apiItem = apiItem1, orig = orig, result = state != State.Error) }
        onAfterUpsertAction(apiItem = apiItem1, orig = orig, result = state != State.Error)
        if (state != State.Error) {
            changeLogCollFun()?.buildChangeLog(cc = commonContainer, apiItem = apiItem1, orig = orig)
        }
        return if (state != State.Error) {
            ItemState(
                item = apiItem1.item,
                state = state,
                noDataModified = noDataModified,
                msgError = "No data was modified ..."
            )
        } else {
            ItemState(
                isOk = false,
                msgError = "${commonContainer.labelItemId(apiItem1.item)} not found with [ ${filter1.json} ]"
            )
        }
    }

    // ── IRepository bridge methods ──────────────────────────────
    // These override the backend-agnostic interface signatures,
    // delegating to Coll's fuller MongoDB-specific overloads.

    /**
     * Finds an item by its identifier (IRepository bridge).
     * Delegates to the MongoDB-specific overload with no BSON filter and no lookup wrappers.
     */
    override suspend fun findById(
        id: ID?,
        apiFilter: FILT,
    ): T? = findById(id = id, filter = null, apiFilter = apiFilter, lookupWrappers = emptyList())

    /**
     * Finds an item by its identifier and returns an [ItemState] (IRepository bridge).
     * Delegates to the MongoDB-specific overload with default BSON filter and no lookup wrappers.
     */
    override suspend fun findItemStateById(
        id: ID?,
        apiFilter: FILT,
    ): ItemState<T> = findItemStateById(
        id = id,
        apiFilter = apiFilter,
        filter = findItemFilter(apiFilter),
        lookupWrappers = emptyList()
    )

    /**
     * Updates an existing item (IRepository bridge).
     * Delegates to the MongoDB-specific overload with no BSON filter.
     */
    override suspend fun updateOne(
        item: T,
        apiFilter: FILT,
        call: ApplicationCall?,
    ): ItemState<T> = updateOne(
        item = item,
        filter = null,
        apiFilter = apiFilter,
        call = call,
    )

    /**
     * Deletes an item by its identifier (IRepository bridge).
     * Delegates to the MongoDB-specific overload with no BSON filter.
     */
    override suspend fun deleteOne(
        id: ID,
        apiFilter: FILT,
    ): ItemState<T> = deleteOne(id = id, filter = null)

    /**
     * Processes an API item request (IRepository bridge).
     * Delegates to the MongoDB-specific overload with no lookup wrappers.
     */
    override suspend fun apiItemProcess(
        call: ApplicationCall?,
        iApiItem: IApiItem<T, ID, FILT>,
    ): ItemState<T> = apiItemProcess(call = call, iApiItem = iApiItem, lookupWrappers = emptyList())

    /**
     * Processes a paginated list request (IRepository bridge).
     * Delegates to the MongoDB-specific overload with default count type and no lookup wrappers.
     */
    override suspend fun apiListProcess(
        call: ApplicationCall?,
        apiList: ApiList<FILT>,
    ): ListState<T> = apiListProcess(
        call = call,
        apiList = apiList,
        countType = CountType.PreLookup,
        lookupWrappers = emptyList(),
    )

    /**
     * Finds a list of items matching the filter (IRepository bridge).
     * Delegates to the MongoDB-specific overload with no BSON filter and no lookup wrappers.
     */
    override suspend fun findList(
        apiFilter: FILT,
    ): List<T> = findList(filter = null, lookupWrappers = emptyList(), apiFilter = apiFilter)

    /**
     * Finds a single item matching the filter (IRepository bridge).
     * Delegates to the MongoDB-specific overload with no BSON filter and no lookup wrappers.
     */
    override suspend fun findOne(
        apiFilter: FILT,
    ): T? = findOne(filter = null, apiFilter = apiFilter, lookupWrappers = emptyList())

    init {
        if (this is IRoleInUserColl<*, *, *, *, *, *>) {
            roleInUserColl = this
            PermissionRegistry.rolePermissionProvider = MongoRolePermissionProvider(this)
        }
    }
}
