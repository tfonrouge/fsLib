package com.fonrouge.fullStack.memoryDb

import com.fonrouge.base.api.*
import com.fonrouge.base.common.ICommonContainer
import com.fonrouge.base.model.BaseDoc
import com.fonrouge.base.state.ItemState
import com.fonrouge.base.state.ListState
import com.fonrouge.base.state.SimpleState
import com.fonrouge.base.state.State
import com.fonrouge.fullStack.repository.ConstructorCopier
import com.fonrouge.fullStack.repository.IChangeLogRepository
import com.fonrouge.fullStack.repository.IRepository
import com.fonrouge.fullStack.repository.IUserRepository
import com.fonrouge.fullStack.repository.PermissionRegistry
import io.ktor.server.application.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties

/**
 * In-memory implementation of [IRepository] backed by a [ConcurrentHashMap].
 *
 * Designed for use in samples, tests, and prototyping — no database engine required.
 * Supports basic CRUD, pagination, column-level filtering (from Tabulator header filters),
 * column-level sorting, and the full [apiItemProcess] lifecycle with hooks.
 *
 * All lifecycle hooks are no-ops by default and can be overridden in subclasses.
 * Permissions always return OK; change logging and user lookups are disabled.
 *
 * @param T The entity type, must extend [BaseDoc].
 * @param ID The identifier type.
 * @param FILT The filter type, must extend [IApiFilter].
 * @param UID The user identifier type.
 * @param commonContainer The container providing entity metadata.
 * @param readOnly Whether this repository blocks all write operations.
 * @param readOnlyErrorMsg Error message for write attempts on a read-only repository.
 */
open class InMemoryRepository<T : BaseDoc<ID>, ID : Any, FILT : IApiFilter<*>, UID : Any>(
    override val commonContainer: ICommonContainer<T, ID, FILT>,
    override val readOnly: Boolean = false,
    override val readOnlyErrorMsg: String = "Repository is read-only",
) : IRepository<T, ID, FILT, UID> {

    /** In-memory data store keyed by entity ID. */
    protected val store: ConcurrentHashMap<ID, T> = ConcurrentHashMap()

    private val openMutex = Mutex()

    @Volatile
    private var opened = false

    /**
     * Creates a copy of the item using only its primary constructor parameters,
     * stripping any body properties. Delegates to [ConstructorCopier].
     */
    private fun T.copyCtorOnly(): T =
        ConstructorCopier.copyWithConstructorParams(commonContainer.itemKClass, this)

    // ── Cross-cutting Concerns (disabled) ────────────────────

    override val changeLogCollFun: () -> IChangeLogRepository? = { null }
    override val dependencies: (() -> List<IRepository.Dependency<*, ID>>)? = null
    override val userCollFun: () -> IUserRepository<*, UID>? = { null }

    // ── CRUD: Single-item Operations ─────────────────────────

    override suspend fun insertOne(
        item: T,
        apiFilter: FILT,
        call: ApplicationCall?,
    ): ItemState<T> = insertOne(
        apiItem = ApiItem.Action.Create(item = item, apiFilter = apiFilter, call = call),
    )

    override suspend fun findById(
        id: ID?,
        apiFilter: FILT,
    ): T? = id?.let { store[it] }

    override suspend fun findItemStateById(
        id: ID?,
        apiFilter: FILT,
    ): ItemState<T> {
        val item = findById(id, apiFilter)
        return if (item != null) {
            ItemState(item = item, state = State.Ok)
        } else {
            ItemState(state = State.Error, msgError = "Item not found: $id")
        }
    }

    override suspend fun updateOne(
        item: T,
        apiFilter: FILT,
        call: ApplicationCall?,
    ): ItemState<T> = updateOne(
        apiItem = ApiItem.Action.Update(item = item, apiFilter = apiFilter, call = call),
    )

    override suspend fun deleteOne(
        id: ID,
        apiFilter: FILT,
    ): ItemState<T> {
        val item = store[id]
            ?: return ItemState(state = State.Error, msgError = "Item not found: $id")
        return deleteOne(
            apiItem = ApiItem.Action.Delete(
                item = item,
                apiFilter = apiFilter,
            ),
        )
    }

    // ── Internal Action Methods ──────────────────────────────

    /**
     * Executes a create action with the full hook lifecycle.
     *
     * @param apiItem The create action containing the item to insert.
     * @return [ItemState] with the inserted item or error information.
     */
    protected open suspend fun insertOne(
        apiItem: ApiItem.Action.Create<T, ID, FILT>,
    ): ItemState<T> {
        if (readOnly) return ItemState(state = State.Error, msgError = readOnlyErrorMsg)

        onQueryUpsert(apiItem.asQuery as ApiItem.Query.Create, orig = null)
            .also { if (it.hasError) return it.asItemState() }
        onQueryCreate(apiItem.asQuery as ApiItem.Query.Create)
            .also { if (it.hasError) return it.asItemState() }

        var currentItem = apiItem.item
        onBeforeUpsertAction(apiItem, orig = null).also {
            if (it.hasError) return it
            it.item?.let { transformed -> currentItem = transformed }
        }

        val currentApiItem = ApiItem.Action.Create(
            item = currentItem,
            apiFilter = apiItem.apiFilter,
            call = apiItem.call,
        )
        onBeforeCreateAction(currentApiItem).also {
            if (it.hasError) return it
            it.item?.let { transformed -> currentItem = transformed }
        }

        currentItem = currentItem.copyCtorOnly()

        val finalApiItem = ApiItem.Action.Create(
            item = currentItem,
            apiFilter = apiItem.apiFilter,
            call = apiItem.call,
        )
        onValidate(finalApiItem, currentItem).also { if (it.hasError) return it.asItemState() }

        var result = false
        return try {
            store[currentItem._id] = currentItem
            result = true
            ItemState(item = currentItem, state = State.Ok)
        } catch (e: Exception) {
            ItemState(state = State.Error, msgError = e.message ?: "Insert failed")
        } finally {
            onAfterCreateAction(finalApiItem, result)
            onAfterUpsertAction(finalApiItem, orig = null, result)
        }
    }

    /**
     * Executes an update action with the full hook lifecycle.
     *
     * @param apiItem The update action containing the item to update.
     * @return [ItemState] with the updated item or error information.
     */
    protected open suspend fun updateOne(
        apiItem: ApiItem.Action.Update<T, ID, FILT>,
    ): ItemState<T> {
        if (readOnly) return ItemState(state = State.Error, msgError = readOnlyErrorMsg)

        val orig = store[apiItem.item._id]
            ?: return ItemState(state = State.Error, msgError = "Item not found: ${apiItem.item._id}")

        onQueryUpsert(apiItem.asQuery as ApiItem.Query.Update, orig)
            .also { if (it.hasError) return it.asItemState() }
        onQueryUpdate(apiItem.asQuery as ApiItem.Query.Update, orig)
            .also { if (it.hasError) return it.asItemState() }

        var currentItem = apiItem.item
        onBeforeUpsertAction(apiItem, orig).also {
            if (it.hasError) return it
            it.item?.let { transformed -> currentItem = transformed }
        }

        val currentApiItem = ApiItem.Action.Update(
            item = currentItem,
            apiFilter = apiItem.apiFilter,
            call = apiItem.call,
        )
        onBeforeUpdateAction(currentApiItem, orig).also {
            if (it.hasError) return it
            it.item?.let { transformed -> currentItem = transformed }
        }

        currentItem = currentItem.copyCtorOnly()

        val finalApiItem = ApiItem.Action.Update(
            item = currentItem,
            apiFilter = apiItem.apiFilter,
            call = apiItem.call,
        )
        onValidate(finalApiItem, currentItem).also { if (it.hasError) return it.asItemState() }

        var result = false
        return try {
            store[currentItem._id] = currentItem
            result = true
            ItemState(item = currentItem, state = State.Ok)
        } catch (e: Exception) {
            ItemState(state = State.Error, msgError = e.message ?: "Update failed")
        } finally {
            onAfterUpdateAction(finalApiItem, orig, result)
            onAfterUpsertAction(finalApiItem, orig, result)
        }
    }

    /**
     * Executes a delete action with the full hook lifecycle.
     *
     * @param apiItem The delete action containing the item to delete.
     * @return [ItemState] indicating success or error.
     */
    protected open suspend fun deleteOne(
        apiItem: ApiItem.Action.Delete<T, ID, FILT>,
    ): ItemState<T> {
        if (readOnly) return ItemState(state = State.Error, msgError = readOnlyErrorMsg)

        val item = apiItem.item
        onQueryDelete(
            apiItem.asQuery as ApiItem.Query.Delete,
            item,
        ).also { if (it.hasError) return it.asItemState() }

        onBeforeDeleteAction(apiItem).also { if (it.hasError) return it }

        // CONTRACT.md I3: the concrete deleteOne is the single, unbypassable owner of dependency
        // safety — a parent with existing children cannot be deleted. (Mongo/SQL already enforce
        // this; previously the in-memory engine did not, allowing silent referential-integrity loss.)
        findChildrenNot(item).also { if (it.hasError) return it }

        var result = false
        return try {
            result = store.remove(item._id) != null
            if (result) {
                ItemState(item = item, state = State.Ok)
            } else {
                ItemState(state = State.Error, msgError = "Item not found: ${item._id}")
            }
        } catch (e: Exception) {
            ItemState(state = State.Error, msgError = e.message ?: "Delete failed")
        } finally {
            onAfterDeleteAction(apiItem, result)
        }
    }

    // ── API Item Processing ──────────────────────────────────

    override suspend fun apiItemProcess(
        call: ApplicationCall?,
        iApiItem: IApiItem<T, ID, FILT>,
    ): ItemState<T> {
        ensureOpen().also { if (it.hasError) return ItemState(it) }

        val apiItem: ApiItem<T, ID, FILT> = asApiItem(
            apiItem = iApiItem.asApiItem(commonContainer, call),
        ).let {
            val item = it.item
            if (it.hasError || item == null) {
                return ItemState(state = State.Error, msgError = it.msgError)
            } else {
                item
            }
        }

        if (apiItem !is ApiItem.Query.Read && readOnly) {
            return ItemState(state = State.Error, msgError = readOnlyErrorMsg)
        }

        return when (apiItem) {
            is ApiItem.Query -> {
                call?.let {
                    getCrudPermission(call, apiItem.crudTask)
                        .also { if (it.state == State.Error) return ItemState(it) }
                }
                when (apiItem) {
                    is ApiItem.Query.Create -> {
                        onQueryUpsert(apiItem, orig = null)
                            .also { if (it.hasError) return it.asItemState() }
                        onQueryCreate(apiItem)
                            .also { if (it.hasError) return it.asItemState() }
                        onQueryCreateItem(apiItem)
                    }

                    is ApiItem.Query.Read -> {
                        onQueryRead(apiItem)
                            .also { if (it.hasError) return it.asItemState() }
                        findItemStateById(apiItem.id, apiItem.apiFilter)
                    }

                    is ApiItem.Query.Update -> {
                        val itemState = findItemStateById(apiItem.id, apiItem.apiFilter)
                        val orig = itemState.item ?: return itemState
                        if (itemState.hasError) return itemState
                        onQueryUpsert(apiItem, orig)
                            .also { if (it.hasError) return it.asItemState() }
                        onQueryUpdate(apiItem, orig)
                            .also { if (it.hasError) return it.asItemState() }
                        itemState
                    }

                    is ApiItem.Query.Delete -> {
                        val itemState = findItemStateById(apiItem.id, apiItem.apiFilter)
                        val item = itemState.item ?: return itemState
                        if (itemState.hasError) return itemState
                        onQueryDelete(apiItem, item)
                            .also { if (it.hasError) return it.asItemState() }
                        itemState
                    }
                }
            }

            is ApiItem.Action -> {
                // CONTRACT.md I5: gate the generic/remote write tier only; the low-level service-tier
                // methods bypass this. Reads (Query) are never gated.
                allowApiCrud(apiItem).also { if (it.hasError) return it.asItemState() }
                // D6: per-action permission on the remote path (call != null); call == null is the trusted
                // service tier (no-op). With the engine's default Off this returns OK, but the path now
                // exists so the declaration is real (parity with SQL/Mongo).
                apiItem.call?.let {
                    getCrudPermission(it, apiItem.crudTask).also { state -> if (state.hasError) return state.asItemState() }
                }
                when (apiItem) {
                    is ApiItem.Action.Create -> insertOne(apiItem)
                    is ApiItem.Action.Update -> updateOne(apiItem)
                    is ApiItem.Action.Delete -> deleteOne(apiItem)
                }
            }
        }
    }

    // ── List Operations ──────────────────────────────────────

    override suspend fun apiListProcess(
        call: ApplicationCall?,
        apiList: ApiList<FILT>,
    ): ListState<T> {
        ensureOpen().also {
            if (it.hasError) return ListState(state = it.state, msgOk = it.msgOk, msgError = it.msgError)
        }

        call?.let {
            getCrudPermission(call, CrudTask.Read)
                .also { if (it.hasError) return ListState(state = State.Error, msgError = "User not authorized") }
        }

        var items = store.values.toList()

        // Apply column filters (from Tabulator header filters)
        apiList.tabFilter?.forEach { filter ->
            items = applyFilter(items, filter)
        }

        // Apply sorting
        apiList.tabSorter?.let { sorters ->
            items = applySorters(items, sorters)
        }

        // Apply pagination
        val totalRows = items.size
        val page = apiList.tabPage ?: 1
        val pageSize = apiList.tabSize

        return if (pageSize != null && pageSize > 0) {
            val totalPages = maxOf(1, (totalRows + pageSize - 1) / pageSize)
            val startIndex = (page - 1) * pageSize
            val pagedItems = items.drop(startIndex).take(pageSize)
            ListState(
                data = pagedItems,
                last_page = totalPages,
                last_row = totalRows,
                state = State.Ok,
            )
        } else {
            ListState(
                data = items,
                last_page = 1,
                last_row = totalRows,
                state = State.Ok,
            )
        }
    }

    override suspend fun findList(
        apiFilter: FILT,
    ): List<T> = store.values.toList()

    override suspend fun findOne(
        apiFilter: FILT,
    ): T? = store.values.firstOrNull()

    // ── Permissions ──────────────────────────────────────────

    /**
     * The in-memory engine is a **deliberately non-enforcing** engine (samples/tests) — D6. It declares
     * [PermissionEnforcement.Off] so its CRUD checks always allow; a concrete subclass may override back
     * to [PermissionEnforcement.Enforce] if it wires a permission provider.
     */
    override val permissionEnforcement: PermissionEnforcement get() = PermissionEnforcement.Off

    override suspend fun getCrudPermission(
        call: ApplicationCall,
        crudTask: CrudTask,
    ): SimpleState {
        // D6: an explicitly non-enforcing repository always allows (the default for this engine).
        if (permissionEnforcement == PermissionEnforcement.Off) return SimpleState(state = State.Ok)
        // Enforcing, but no resolver wired → fail CLOSED.
        val provider = PermissionRegistry.rolePermissionProvider
            ?: return SimpleState(isOk = false, msgError = "Permission enforcement is on but no permission provider is registered")
        return provider.getCrudPermission(commonContainer, call, crudTask)
    }

    // ── Existence Check ──────────────────────────────────────

    override suspend fun existsByField(
        property: KProperty1<out BaseDoc<*>, *>,
        value: Any?,
    ): Boolean = store.values.any { item ->
        getPropertyValue(item, property.name) == value
    }

    // ── Dependency Checking ──────────────────────────────────

    override suspend fun findChildrenNot(item: T): ItemState<T> {
        val itemState = findItemStateById(item._id)
        if (itemState.hasError.not()) {
            dependencies?.invoke()?.forEach { dependency ->
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
                // In-memory fallback: scan own store
                if (existsByField(dependency.property, item._id)) {
                    return ItemState(
                        state = State.Error,
                        msgError = "'${commonContainer.labelItemId(item)}' has dependencies in '${dependency.common.labelList}'"
                    )
                }
            }
        }
        return itemState
    }

    // ── Lifecycle Hooks (no-ops) ─────────────────────────────

    override suspend fun onQueryCreate(apiItem: ApiItem.Query.Create<T, ID, FILT>): SimpleState =
        SimpleState(state = State.Ok)

    override suspend fun onQueryCreateItem(apiItem: ApiItem.Query.Create<T, ID, FILT>): ItemState<T> =
        ItemState(state = State.Ok)

    override suspend fun onQueryRead(apiItem: ApiItem.Query.Read<T, ID, FILT>): SimpleState =
        SimpleState(state = State.Ok)

    override suspend fun onQueryUpdate(apiItem: ApiItem.Query.Update<T, ID, FILT>, orig: T): SimpleState =
        SimpleState(state = State.Ok)

    override suspend fun onQueryDelete(apiItem: ApiItem.Query.Delete<T, ID, FILT>, item: T): SimpleState =
        SimpleState(state = State.Ok)

    override suspend fun onQueryUpsert(apiItem: ApiItem.Query<T, ID, FILT>, orig: T?): SimpleState =
        SimpleState(state = State.Ok)

    override suspend fun onBeforeCreateAction(apiItem: ApiItem.Action.Create<T, ID, FILT>): ItemState<T> =
        ItemState(item = apiItem.item, state = State.Ok)

    override suspend fun onBeforeUpdateAction(apiItem: ApiItem.Action.Update<T, ID, FILT>, orig: T): ItemState<T> =
        ItemState(item = apiItem.item, state = State.Ok)

    override suspend fun onBeforeDeleteAction(apiItem: ApiItem.Action.Delete<T, ID, FILT>): ItemState<T> =
        ItemState(item = apiItem.item, state = State.Ok)

    override suspend fun onBeforeUpsertAction(apiItem: ApiItem.Action<T, ID, FILT>, orig: T?): ItemState<T> =
        ItemState(item = apiItem.item, state = State.Ok)

    override suspend fun onAfterCreateAction(apiItem: ApiItem.Action.Create<T, ID, FILT>, result: Boolean) = Unit

    override suspend fun onAfterUpdateAction(apiItem: ApiItem.Action.Update<T, ID, FILT>, orig: T, result: Boolean) =
        Unit

    override suspend fun onAfterDeleteAction(apiItem: ApiItem.Action.Delete<T, ID, FILT>, result: Boolean) = Unit

    override suspend fun onAfterUpsertAction(apiItem: ApiItem.Action<T, ID, FILT>, orig: T?, result: Boolean) = Unit

    // Idempotent, retryable init. ensureOpen() calls this from the generic API; direct service-tier
    // callers must call open() at boot for readiness before low-level use (CONTRACT I4).
    override suspend fun open(): SimpleState = openMutex.withLock {
        if (opened) return SimpleState(isOk = true)
        try {
            onAfterOpen()
            opened = true
            SimpleState(isOk = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SimpleState(isOk = false, msgError = "Repository init failed: ${e.message}")
        }
    }

    override suspend fun onAfterOpen() = Unit

    override suspend fun onValidate(apiItem: ApiItem.Action<T, ID, FILT>, item: T): SimpleState =
        SimpleState(state = State.Ok)

    override suspend fun asApiItem(apiItem: ApiItem<T, ID, FILT>): ItemState<ApiItem<T, ID, FILT>> =
        ItemState(item = apiItem, state = State.Ok)

    // ── Utility: Seeding ─────────────────────────────────────

    /**
     * Seeds the store with initial data. Useful for sample apps and tests.
     *
     * Note: unlike [insertOne]/[updateOne], seed does **not** strip body properties
     * via constructor copying. This is intentional — seed is for test setup where
     * forcing constructor copies could break test expectations.
     *
     * @param items The items to add to the store.
     * @return This repository instance for chaining.
     */
    fun seed(vararg items: T): InMemoryRepository<T, ID, FILT, UID> {
        items.forEach { store[it._id] = it }
        return this
    }

    /**
     * Seeds the store with a list of items.
     *
     * Note: unlike [insertOne]/[updateOne], seed does **not** strip body properties
     * via constructor copying. See [seed] vararg overload for rationale.
     *
     * @param items The list of items to add.
     * @return This repository instance for chaining.
     */
    fun seed(items: List<T>): InMemoryRepository<T, ID, FILT, UID> {
        items.forEach { store[it._id] = it }
        return this
    }

    /**
     * Clears all data from the store.
     */
    fun clear() {
        store.clear()
    }

    /**
     * Returns the current number of items in the store.
     */
    val size: Int get() = store.size

    // ── Internal Helpers ─────────────────────────────────────

    /**
     * Applies a [RemoteFilter] to a list of items using reflection.
     * Supports filter types: `like`, `=`, `!=`, `<`, `<=`, `>`, `>=`, `starts`, `ends`.
     */
    @Suppress("UNCHECKED_CAST")
    private fun applyFilter(items: List<T>, filter: dev.kilua.rpc.RemoteFilter): List<T> {
        val fieldName = filter.field
        val filterValue = filter.value ?: return items

        return items.filter { item ->
            val propValue = getPropertyValue(item, fieldName)?.toString() ?: ""
            when (filter.type) {
                "like" -> propValue.contains(filterValue, ignoreCase = true)
                "=" -> propValue == filterValue
                "!=" -> propValue != filterValue
                "<" -> propValue < filterValue
                "<=" -> propValue <= filterValue
                ">" -> propValue > filterValue
                ">=" -> propValue >= filterValue
                "starts" -> propValue.startsWith(filterValue, ignoreCase = true)
                "ends" -> propValue.endsWith(filterValue, ignoreCase = true)
                else -> true
            }
        }
    }

    /**
     * Applies a list of [RemoteSorter] directives to a list of items using reflection.
     */
    private fun applySorters(items: List<T>, sorters: List<dev.kilua.rpc.RemoteSorter>): List<T> {
        if (sorters.isEmpty()) return items

        val comparator = sorters.fold<dev.kilua.rpc.RemoteSorter, Comparator<T>?>(null) { acc, sorter ->
            val fieldComparator = Comparator<T> { a, b ->
                val valA = getPropertyValue(a, sorter.field)?.toString() ?: ""
                val valB = getPropertyValue(b, sorter.field)?.toString() ?: ""
                val cmp = valA.compareTo(valB)
                if (sorter.dir.equals("desc", ignoreCase = true)) -cmp else cmp
            }
            acc?.thenComparing(fieldComparator) ?: fieldComparator
        }

        return comparator?.let { items.sortedWith(it) } ?: items
    }

    /**
     * Gets a property value from an item using reflection.
     * Supports dot-notation for nested properties (e.g., "address.city").
     */
    @Suppress("UNCHECKED_CAST")
    private fun getPropertyValue(item: Any, fieldName: String): Any? {
        val parts = fieldName.split(".")
        var current: Any? = item
        for (part in parts) {
            if (current == null) return null
            val prop = current::class.memberProperties.firstOrNull { it.name == part }
            current = (prop as? KProperty1<Any, *>)?.get(current)
        }
        return current
    }
}
