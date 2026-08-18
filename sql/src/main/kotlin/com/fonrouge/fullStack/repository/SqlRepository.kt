package com.fonrouge.fullStack.repository

import com.fonrouge.base.annotations.SqlField
import com.fonrouge.base.annotations.SqlIgnoreField
import com.fonrouge.base.api.*
import com.fonrouge.base.common.ICommonContainer
import com.fonrouge.base.model.BaseDoc
import com.fonrouge.base.sqlDb.SqlDatabase
import com.fonrouge.base.state.ItemState
import com.fonrouge.base.state.ListState
import com.fonrouge.base.state.SimpleState
import com.fonrouge.base.state.State
import com.fonrouge.base.types.IBaseId
import com.fonrouge.base.types.IntId
import com.fonrouge.base.types.LongId
import com.fonrouge.base.types.StringId
import dev.kilua.rpc.RemoteFilter
import dev.kilua.rpc.RemoteSorter
import io.ktor.server.application.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.json.*
import kotlinx.serialization.serializer
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.javatime.JavaLocalDateTimeColumnType
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberProperties

/**
 * SQL-backed implementation of [IRepository] using Exposed for database access
 * and Kotlinx Serialization for object-to-row mapping.
 *
 * This class provides full CRUD operations, paginated list queries, lifecycle hooks,
 * permission checks, dependency validation, and change logging — all backed by a
 * relational database instead of MongoDB.
 *
 * @param T The entity type, must extend [BaseDoc].
 * @param ID The identifier type.
 * @param FILT The filter type, must extend [IApiFilter].
 * @param UID The user identifier type (for role-based access control).
 * @param commonContainer The container providing entity metadata (KClass, serializers, labels).
 * @param sqlDatabase The [SqlDatabase] instance providing the database connection.
 * @param tableName The SQL table name. Defaults to the entity class simple name in lowercase.
 */
abstract class SqlRepository<T : BaseDoc<ID>, ID : Any, FILT : IApiFilter<*>, UID : Any>(
    override val commonContainer: ICommonContainer<T, ID, FILT>,
    val sqlDatabase: SqlDatabase,
    tableName: String = commonContainer.itemKClass.simpleName
        ?.replaceFirstChar { if (it.isUpperCase()) it.lowercaseChar() else it }
        ?: error("Cannot determine table name for ${commonContainer.itemKClass}"),
) : IRepository<T, ID, FILT, UID> {

    /** The raw SQL table name. */
    val tableName: String = tableName

    /** The quoted SQL table name, safe for direct interpolation in SQL statements. */
    private val quotedTableName: String = quoteIdentifier(tableName)

    private val openMutex = Mutex()

    @Volatile
    private var opened = false

    // ── Metadata ──────────────────────────────────────────────

    override val readOnly: Boolean = false

    override val readOnlyErrorMsg: String
        get() = "${commonContainer.labelItem} is read-only"

    // ── Cross-cutting (defaults) ──────────────────────────────

    override val changeLogCollFun: () -> IChangeLogRepository? = { null }

    override val dependencies: (() -> List<IRepository.Dependency<*, ID>>)? = null

    abstract override val userCollFun: () -> IUserRepository<*, UID>?

    // ── Internal: Database reference ──────────────────────────

    /** The Exposed [Database] instance for transactions. */
    private val database: Database get() = sqlDatabase.database

    // ── CRUD Operations ───────────────────────────────────────

    @OptIn(InternalSerializationApi::class)
    override suspend fun insertOne(
        item: T,
        apiFilter: FILT,
        call: ApplicationCall?,
    ): ItemState<T> {
        if (readOnly) return ItemState(isOk = false, msgError = readOnlyErrorMsg)
        val apiItem = ApiItem.Action.Create(item = item, apiFilter = apiFilter, call = call)
        onQueryUpsert(apiItem.asQuery as ApiItem.Query.Create, orig = null)
            .also { if (it.hasError) return it.asItemState() }
        onQueryCreate(apiItem.asQuery as ApiItem.Query.Create)
            .also { if (it.hasError) return it.asItemState() }
        var currentItem = item
        var currentApiItem = apiItem
        onBeforeUpsertAction(currentApiItem, orig = null).also {
            if (it.hasError) return it
            it.item?.let { modified ->
                currentItem = modified; currentApiItem =
                currentApiItem.copy(item = currentItem)
            }
        }
        onBeforeCreateAction(currentApiItem).also {
            if (it.hasError) return it
            it.item?.let { modified ->
                currentItem = modified; currentApiItem =
                currentApiItem.copy(item = currentItem)
            }
        }
        currentItem = currentItem.copyCtorOnly()
        currentApiItem = currentApiItem.copy(item = currentItem)
        onValidate(currentApiItem, currentItem).also { if (it.hasError) return it.asItemState() }
        var result = false
        return try {
            val jsonObj = Json.encodeToJsonElement(commonContainer.itemSerializer, currentItem) as JsonObject
            newSuspendedTransaction(context = Dispatchers.IO, db = database) {
                val columns = mutableListOf<String>()
                val args = mutableListOf<Pair<IColumnType<*>, Any?>>()
                jsonObj.forEach { (key, value) ->
                    if (!isIgnoredField(key)) {
                        val sqlColumn = resolveColumnName(key)
                        columns += sqlColumn
                        args += jsonValueToColumnArg(key, value)
                    }
                }
                val columnList = columns.joinToString(", ")
                val placeholders = columns.joinToString(", ") { "?" }
                exec("INSERT INTO $quotedTableName ($columnList) VALUES ($placeholders)", args)
            }
            result = true
            ItemState(item = currentItem, state = State.Ok)
        } catch (e: Exception) {
            ItemState(isOk = false, msgError = friendlyExceptionMessage(e))
        } finally {
            onAfterCreateAction(currentApiItem, result = result)
            onAfterUpsertAction(currentApiItem, orig = null, result = result)
            if (result) changeLogCollFun()?.buildChangeLog(cc = commonContainer, apiItem = currentApiItem, orig = null)
        }
    }

    override suspend fun findById(
        id: ID?,
        apiFilter: FILT,
    ): T? {
        if (id == null) return null
        return newSuspendedTransaction(context = Dispatchers.IO, db = database) {
            val idColumn = resolveIdColumnName()
            val args = listOf(idToColumnArg(id))
            exec("SELECT * FROM $quotedTableName WHERE $idColumn = ?", args) { rs ->
                if (rs.next()) resultSetToEntity(rs) else null
            }
        }
    }

    override suspend fun findItemStateById(
        id: ID?,
        apiFilter: FILT,
    ): ItemState<T> = try {
        ItemState(
            item = findById(id, apiFilter),
            msgError = "_id '$id' (${commonContainer.itemKClass.simpleName}) not found..."
        )
    } catch (e: Exception) {
        ItemState(isOk = false, msgError = e.message)
    }

    @OptIn(InternalSerializationApi::class)
    override suspend fun updateOne(
        item: T,
        apiFilter: FILT,
        call: ApplicationCall?,
    ): ItemState<T> {
        if (readOnly) return ItemState(isOk = false, msgError = readOnlyErrorMsg)
        val orig = findById(item._id, apiFilter)?.copyCtorOnly()
            ?: return ItemState(isOk = false, msgError = "Original item not found")
        val apiItem =
            ApiItem.Action.Update(item = item, apiFilter = apiFilter, call = call)
        onQueryUpsert(apiItem.asQuery as ApiItem.Query.Update, orig)
            .also { if (it.hasError) return it.asItemState() }
        onQueryUpdate(apiItem.asQuery as ApiItem.Query.Update, orig)
            .also { if (it.hasError) return it.asItemState() }
        var currentItem = item
        var currentApiItem = apiItem
        // CONTRACT.md I1: shared Upsert hook outermost — upsert→specific, symmetric with create (P2.2).
        onBeforeUpsertAction(currentApiItem, orig).also {
            if (it.hasError) return it
            it.item?.let { modified ->
                currentItem = modified; currentApiItem =
                currentApiItem.copy(item = currentItem)
            }
        }
        onBeforeUpdateAction(currentApiItem, orig).also {
            if (it.hasError) return it
            it.item?.let { modified ->
                currentItem = modified; currentApiItem =
                currentApiItem.copy(item = currentItem)
            }
        }
        currentItem = currentItem.copyCtorOnly()
        currentApiItem = currentApiItem.copy(item = currentItem)
        onValidate(currentApiItem, currentItem).also { if (it.hasError) return it.asItemState() }
        val origJson = Json.encodeToJsonElement(commonContainer.itemSerializer, orig) as JsonObject
        val newJson = Json.encodeToJsonElement(commonContainer.itemSerializer, currentItem) as JsonObject
        // Mirrors `Coll.updateOne`: `noDataModified` marks this as the benign refusal — nothing
        // needed writing — so clients can distinguish it from a refusal the user must act on.
        if (origJson == newJson) return ItemState(
            state = State.Warn,
            noDataModified = true,
            msgError = "Update skipped - no changes detected in item"
        )
        var result = false
        return try {
            newSuspendedTransaction(context = Dispatchers.IO, db = database) {
                val setClauses = mutableListOf<String>()
                val args = mutableListOf<Pair<IColumnType<*>, Any?>>()
                newJson.forEach { (key, value) ->
                    if (key != "_id" && !isIgnoredField(key) && value != origJson[key]) {
                        val sqlColumn = resolveColumnName(key)
                        setClauses += "$sqlColumn = ?"
                        args += jsonValueToColumnArg(key, value)
                    }
                }
                if (setClauses.isEmpty()) return@newSuspendedTransaction
                val idColumn = resolveIdColumnName()
                args += idToColumnArg(currentItem._id)
                val setClause = setClauses.joinToString(", ")
                exec("UPDATE $quotedTableName SET $setClause WHERE $idColumn = ?", args)
            }
            result = true
            ItemState(item = currentItem, state = State.Ok)
        } catch (e: Exception) {
            ItemState(isOk = false, msgError = friendlyExceptionMessage(e))
        } finally {
            onAfterUpdateAction(currentApiItem, orig, result = result)
            onAfterUpsertAction(currentApiItem, orig, result = result)
            if (result) changeLogCollFun()?.buildChangeLog(cc = commonContainer, apiItem = currentApiItem, orig = orig)
        }
    }

    override suspend fun deleteOne(
        id: ID,
        apiFilter: FILT,
    ): ItemState<T> {
        if (readOnly) return ItemState(isOk = false, msgError = readOnlyErrorMsg)
        val item = findById(id, apiFilter)
            ?: return ItemState(isOk = false, msgError = "_id '$id' not found")
        val apiItem = ApiItem.Action.Delete(item = item, apiFilter = apiFilter)
        onQueryDelete(apiItem.asQuery as ApiItem.Query.Delete, item)
            .also { if (it.hasError) return it.asItemState() }
        onBeforeDeleteAction(apiItem).also { if (it.hasError) return it }
        findChildrenNot(item).also { if (it.hasError) return it }
        var result = false
        return try {
            newSuspendedTransaction(context = Dispatchers.IO, db = database) {
                val idColumn = resolveIdColumnName()
                val args = listOf(idToColumnArg(id))
                exec("DELETE FROM $quotedTableName WHERE $idColumn = ?", args)
            }
            result = true
            ItemState(isOk = true, msgOk = "Delete operation ok")
        } catch (e: Exception) {
            ItemState(isOk = false, msgError = friendlyExceptionMessage(e))
        } finally {
            onAfterDeleteAction(apiItem, result = result)
            if (result) changeLogCollFun()?.buildChangeLog(cc = commonContainer, apiItem = apiItem, orig = null)
        }
    }

    // ── API Item Processing ───────────────────────────────────

    override suspend fun apiItemProcess(
        call: ApplicationCall?,
        iApiItem: IApiItem<T, ID, FILT>,
    ): ItemState<T> {
        ensureOpen().also { if (it.hasError) return ItemState(it) }

        val apiItem: ApiItem<T, ID, FILT> = asApiItem(
            iApiItem.asApiItem(commonContainer, call)
        ).let {
            val item = it.item
            if (it.hasError || item == null) return ItemState(isOk = false, msgError = it.msgError)
            item
        }
        if (apiItem !is ApiItem.Query.Read && readOnly) return ItemState(isOk = false, msgError = readOnlyErrorMsg)
        return when (apiItem) {
            is ApiItem.Query -> {
                call?.let {
                    getCrudPermission(call, apiItem.crudTask)
                        .also { if (it.state == State.Error) return ItemState(it) }
                }
                when (apiItem) {
                    is ApiItem.Query.Create -> {
                        onQueryUpsert(apiItem, orig = null).also { if (it.hasError) return it.asItemState() }
                        onQueryCreate(apiItem).also { if (it.hasError) return it.asItemState() }
                        onQueryCreateItem(apiItem)
                    }

                    is ApiItem.Query.Read -> {
                        onQueryRead(apiItem).also { if (it.hasError) return it.asItemState() }
                        findItemStateById(id = apiItem.id, apiFilter = apiItem.apiFilter)
                    }

                    is ApiItem.Query.Update -> {
                        val itemState = findItemStateById(id = apiItem.id, apiFilter = apiItem.apiFilter)
                        val orig = itemState.item?.copyCtorOnly()
                        if (itemState.hasError || orig == null) return itemState
                        onQueryUpsert(apiItem, orig).also { if (it.hasError) return it.asItemState() }
                        onQueryUpdate(apiItem, orig).also { if (it.hasError) return it.asItemState() }
                        itemState
                    }

                    is ApiItem.Query.Delete -> {
                        val itemState = findItemStateById(id = apiItem.id, apiFilter = apiItem.apiFilter)
                        val item = itemState.item
                        if (itemState.hasError || item == null) return itemState
                        onQueryDelete(apiItem, item).also { if (it.hasError) return it.asItemState() }
                        itemState
                    }
                }
            }

            is ApiItem.Action -> {
                // CONTRACT.md I5: gate the generic/remote write tier only; the low-level service-tier
                // methods bypass this. Reads (Query) are never gated.
                allowApiCrud(apiItem).also { if (it.hasError) return it.asItemState() }
                // CONTRACT.md I6: enforce the per-action CRUD permission on the remote path
                // (call != null), matching the Query branch above; the trusted service tier
                // (call == null) is a no-op. An unconfigured PermissionRegistry is NOT a no-op
                // (D6, since 5.0.0): this repository inherits the default permissionEnforcement =
                // Enforce and therefore fails CLOSED until a provider is registered.
                call?.let {
                    getCrudPermission(call, apiItem.crudTask)
                        .also { if (it.state == State.Error) return ItemState(it) }
                }
                when (apiItem) {
                    is ApiItem.Action.Create -> insertOne(item = apiItem.item, apiFilter = apiItem.apiFilter, call = call)
                    is ApiItem.Action.Update -> updateOne(item = apiItem.item, apiFilter = apiItem.apiFilter, call = call)
                    is ApiItem.Action.Delete -> deleteOne(id = apiItem.item._id, apiFilter = apiItem.apiFilter)
                }
            }
        }
    }

    // ── List Operations ───────────────────────────────────────

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
        return newSuspendedTransaction(context = Dispatchers.IO, db = database) {
            val whereClauses = mutableListOf<String>()
            val whereArgs = mutableListOf<Pair<IColumnType<*>, Any?>>()
            buildWhereFromApiFilter(apiList.apiFilter, whereClauses, whereArgs)
            buildWhereFromFilters(apiList.tabFilter, whereClauses, whereArgs)
            val whereClause = if (whereClauses.isNotEmpty()) "WHERE ${whereClauses.joinToString(" AND ")}" else ""
            val orderClause = buildOrderFromSorters(apiList.tabSorter)

            // Count total rows
            val countSql = "SELECT COUNT(*) FROM $quotedTableName $whereClause"
            val totalRows = exec(countSql, whereArgs) { rs -> if (rs.next()) rs.getInt(1) else 0 } ?: 0

            val pageSize = apiList.tabSize ?: 0
            val page = apiList.tabPage ?: 1
            val lastPage = if (pageSize > 0) {
                (totalRows / pageSize + if (totalRows % pageSize > 0) 1 else 0)
            } else null

            // Build paginated query
            val limitClause = if (pageSize > 0) {
                val offset = pageSize * (page - 1)
                "LIMIT $pageSize OFFSET $offset"
            } else ""

            val sql = "SELECT * FROM $quotedTableName $whereClause $orderClause $limitClause"
            val list = buildList {
                exec(sql, whereArgs) { rs ->
                    while (rs.next()) {
                        add(resultSetToEntity(rs))
                    }
                }
            }

            ListState(
                data = list,
                last_page = lastPage,
                last_row = totalRows,
                state = State.Ok,
            )
        }
    }

    override suspend fun findList(
        apiFilter: FILT,
    ): List<T> = newSuspendedTransaction(context = Dispatchers.IO, db = database) {
        val whereClauses = mutableListOf<String>()
        val whereArgs = mutableListOf<Pair<IColumnType<*>, Any?>>()
        buildWhereFromApiFilter(apiFilter, whereClauses, whereArgs)
        val whereClause = if (whereClauses.isNotEmpty()) "WHERE ${whereClauses.joinToString(" AND ")}" else ""
        val sql = "SELECT * FROM $quotedTableName $whereClause"
        buildList {
            exec(sql, whereArgs) { rs ->
                while (rs.next()) {
                    add(resultSetToEntity(rs))
                }
            }
        }
    }

    override suspend fun findOne(
        apiFilter: FILT,
    ): T? = newSuspendedTransaction(context = Dispatchers.IO, db = database) {
        val whereClauses = mutableListOf<String>()
        val whereArgs = mutableListOf<Pair<IColumnType<*>, Any?>>()
        buildWhereFromApiFilter(apiFilter, whereClauses, whereArgs)
        val whereClause = if (whereClauses.isNotEmpty()) "WHERE ${whereClauses.joinToString(" AND ")}" else ""
        val sql = "SELECT * FROM $quotedTableName $whereClause LIMIT 1"
        exec(sql, whereArgs) { rs ->
            if (rs.next()) resultSetToEntity(rs) else null
        }
    }

    // ── Permissions ───────────────────────────────────────────

    override suspend fun getCrudPermission(
        call: ApplicationCall,
        crudTask: CrudTask,
    ): SimpleState {
        // D6: an explicitly non-enforcing repository always allows.
        if (permissionEnforcement == PermissionEnforcement.Off) return SimpleState(isOk = true)
        // D6: enforcing, but no resolver wired → fail CLOSED (was: `?: SimpleState(isOk = true)`, fail-open R7).
        val provider = PermissionRegistry.rolePermissionProvider
            ?: return SimpleState(isOk = false, msgError = "Permission enforcement is on but no permission provider is registered")
        return provider.getCrudPermission(commonContainer, call, crudTask)
    }

    // ── Existence Check ─────────────────────────────────────────

    override suspend fun existsByField(property: KProperty1<out BaseDoc<*>, *>, value: Any?): Boolean {
        if (value == null) return false
        val fieldName = quoteIdentifier(property.name)
        return newSuspendedTransaction(context = Dispatchers.IO, db = database) {
            val args = listOf(anyToColumnArg(value))
            exec("SELECT 1 FROM $quotedTableName WHERE $fieldName = ? LIMIT 1", args) { rs ->
                rs.next()
            } ?: false
        }
    }

    // ── Dependency Checking ───────────────────────────────────

    override suspend fun findChildrenNot(item: T): ItemState<T> {
        val itemState = findItemStateById(item._id)
        if (itemState.hasError.not()) {
            dependencies?.invoke()?.forEach { dependency ->
                val repoFun = dependency.repositoryFun
                val hasChild = if (repoFun != null) {
                    repoFun.invoke().existsByField(dependency.property, item._id)
                } else {
                    existsByFieldInTable(dependency, item._id)
                }
                if (hasChild) {
                    return ItemState(
                        state = State.Error,
                        msgError = "'${commonContainer.labelItemId(item)}' has dependencies in '${dependency.common.labelList}'"
                    )
                }
            }
        }
        return itemState
    }

    /**
     * Engine-specific fallback for dependency checking when no [IRepository.Dependency.repositoryFun]
     * is provided. Queries the dependent SQL table directly.
     */
    private suspend fun existsByFieldInTable(dependency: IRepository.Dependency<*, *>, value: Any?): Boolean {
        if (value == null) return false
        val fieldName = quoteIdentifier(dependency.property.name)
        val depTableName = dependency.common.itemKClass.simpleName
            ?.replaceFirstChar { if (it.isUpperCase()) it.lowercaseChar() else it }
            ?.let { quoteIdentifier(it) }
            ?: return false
        return newSuspendedTransaction(context = Dispatchers.IO, db = database) {
            val args = listOf(anyToColumnArg(value))
            exec("SELECT 1 FROM $depTableName WHERE $fieldName = ? LIMIT 1", args) { rs ->
                rs.next()
            } ?: false
        }
    }

    // ── Lifecycle Hooks (default no-ops) ──────────────────────

    override suspend fun onQueryCreate(apiItem: ApiItem.Query.Create<T, ID, FILT>): SimpleState =
        SimpleState(isOk = true)

    override suspend fun onQueryCreateItem(apiItem: ApiItem.Query.Create<T, ID, FILT>): ItemState<T> =
        ItemState(isOk = true)

    override suspend fun onQueryRead(apiItem: ApiItem.Query.Read<T, ID, FILT>): SimpleState = SimpleState(isOk = true)
    override suspend fun onQueryUpdate(apiItem: ApiItem.Query.Update<T, ID, FILT>, orig: T): SimpleState =
        SimpleState(isOk = true)

    override suspend fun onQueryDelete(apiItem: ApiItem.Query.Delete<T, ID, FILT>, item: T): SimpleState =
        SimpleState(state = State.Ok)

    override suspend fun onQueryUpsert(apiItem: ApiItem.Query<T, ID, FILT>, orig: T?): SimpleState =
        SimpleState(isOk = true)

    override suspend fun onBeforeCreateAction(apiItem: ApiItem.Action.Create<T, ID, FILT>): ItemState<T> =
        ItemState(isOk = true)

    override suspend fun onBeforeUpdateAction(apiItem: ApiItem.Action.Update<T, ID, FILT>, orig: T): ItemState<T> =
        ItemState(isOk = true)

    override suspend fun onBeforeDeleteAction(apiItem: ApiItem.Action.Delete<T, ID, FILT>): ItemState<T> =
        ItemState(isOk = true)

    override suspend fun onBeforeUpsertAction(apiItem: ApiItem.Action<T, ID, FILT>, orig: T?): ItemState<T> =
        ItemState(isOk = true)

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
        SimpleState(isOk = true)

    override suspend fun asApiItem(apiItem: ApiItem<T, ID, FILT>): ItemState<ApiItem<T, ID, FILT>> =
        ItemState(item = apiItem)

    // ── Overridable Filter Hook ──────────────────────────────

    /**
     * Converts a typed [IApiFilter] into SQL WHERE clauses and parameterized arguments.
     *
     * Subclasses should override this method to translate their domain-specific filter
     * properties into SQL conditions, mirroring the role of `matchStage` in MongoDB's [Coll].
     *
     * The default implementation is a no-op (no filtering applied).
     *
     * @param apiFilter The typed API filter to convert.
     * @param clauses Mutable list to append WHERE clause fragments (e.g., "status = ?").
     * @param args Mutable list to append corresponding column type/value pairs.
     */
    protected open fun buildWhereFromApiFilter(
        apiFilter: FILT,
        clauses: MutableList<String>,
        args: MutableList<Pair<IColumnType<*>, Any?>>,
    ) = Unit

    // ── Internal Helpers ──────────────────────────────────────

    /**
     * Checks whether a Kotlin property is annotated with [SqlIgnoreField],
     * indicating it should be excluded from SQL operations.
     *
     * @param propertyName The Kotlin property name to check.
     * @return True if the property should be ignored in SQL operations.
     */
    private fun isIgnoredField(propertyName: String): Boolean {
        val prop = commonContainer.itemKClass.memberProperties.find { it.name == propertyName }
        return prop?.findAnnotation<SqlIgnoreField>() != null
    }

    /**
     * Converts a SQL ResultSet row to an entity of type T using the entity KClass.
     * This avoids the reified type parameter limitation of [SqlDatabase.sqlEntityTo].
     */
    @OptIn(InternalSerializationApi::class)
    private fun resultSetToEntity(rs: java.sql.ResultSet): T {
        val jsonObj = sqlDatabase.buildJsonFromResultSet(commonContainer.itemKClass, rs)
        return Json.decodeFromJsonElement(commonContainer.itemKClass.serializer(), jsonObj)
    }

    /**
     * Creates a copy of the item using only its primary constructor parameters,
     * stripping any body properties. Delegates to [ConstructorCopier].
     */
    private fun T.copyCtorOnly(): T =
        ConstructorCopier.copyWithConstructorParams(commonContainer.itemKClass, this)

    /**
     * Resolves the SQL column name for the _id field.
     * Uses the @SqlField annotation if present, otherwise defaults to "_id".
     * The returned name is quoted to prevent SQL injection.
     */
    private fun resolveIdColumnName(): String {
        val idProp = commonContainer.itemKClass.memberProperties.find { it.name == "_id" }
        val raw = idProp?.findAnnotation<SqlField>()?.name?.ifEmpty { null } ?: "_id"
        return quoteIdentifier(raw)
    }

    /**
     * Resolves the SQL column name for a given Kotlin property name.
     * Checks @SqlField annotation for renamed columns.
     * The returned name is quoted to prevent SQL injection.
     */
    private fun resolveColumnName(propertyName: String): String {
        val prop = commonContainer.itemKClass.memberProperties.find { it.name == propertyName }
        val raw = prop?.findAnnotation<SqlField>()?.name?.ifEmpty { null } ?: propertyName
        return quoteIdentifier(raw)
    }

    /**
     * Resolves the Exposed [IColumnType] for a Kotlin property by inspecting
     * its return type. Used to generate type-correct parameterized query arguments
     * for filter values.
     *
     * @param propertyName The Kotlin property name.
     * @return The matching [IColumnType], defaulting to [VarCharColumnType] for unknown types.
     */
    private fun resolveColumnType(propertyName: String): IColumnType<*> {
        val prop = commonContainer.itemKClass.memberProperties.find { it.name == propertyName }
        val kClass = prop?.returnType?.classifier as? KClass<*> ?: return VarCharColumnType()
        return when {
            kClass == Int::class || kClass == Integer::class -> IntegerColumnType()
            kClass == Long::class || kClass == java.lang.Long::class -> LongColumnType()
            kClass == Double::class || kClass == java.lang.Double::class -> DoubleColumnType()
            kClass == Float::class || kClass == java.lang.Float::class -> FloatColumnType()
            kClass == Boolean::class || kClass == java.lang.Boolean::class -> BooleanColumnType()
            kClass == LocalDateTime::class || kClass == kotlinx.datetime.LocalDateTime::class -> JavaLocalDateTimeColumnType()
            kClass == LocalDate::class || kClass == kotlinx.datetime.LocalDate::class -> org.jetbrains.exposed.sql.javatime.JavaLocalDateColumnType()
            kClass.isSubclassOf(IBaseId::class) -> VarCharColumnType()
            else -> VarCharColumnType()
        }
    }

    /**
     * Quotes a SQL identifier (table or column name) using double quotes,
     * escaping any embedded double-quote characters to prevent SQL injection.
     *
     * @param identifier The raw identifier string.
     * @return The safely quoted identifier.
     */
    private fun quoteIdentifier(identifier: String): String {
        val escaped = identifier.replace("\"", "\"\"")
        return "\"$escaped\""
    }

    /**
     * Converts an ID value to an Exposed column type/value pair for parameterized queries.
     */
    private fun idToColumnArg(id: ID): Pair<IColumnType<*>, Any?> = anyToColumnArg(id)

    /**
     * Converts an arbitrary value to an Exposed column type/value pair by inspecting its runtime type.
     * Used by [existsByField] and [idToColumnArg] for parameterized queries.
     *
     * @param value The value to convert.
     * @return A pair of [IColumnType] and the unwrapped value.
     */
    private fun anyToColumnArg(value: Any?): Pair<IColumnType<*>, Any?> = when (value) {
        null -> VarCharColumnType() to null
        is IBaseId<*> -> when (value) {
            is IntId<*> -> IntegerColumnType() to value.id
            is LongId<*> -> LongColumnType() to value.id
            is StringId<*> -> VarCharColumnType() to value.id
            else -> VarCharColumnType() to value.toString()
        }

        is Int -> IntegerColumnType() to value
        is Long -> LongColumnType() to value
        is String -> VarCharColumnType() to value
        is Double -> DoubleColumnType() to value
        is Float -> FloatColumnType() to value
        is Boolean -> BooleanColumnType() to value
        is LocalDateTime -> JavaLocalDateTimeColumnType() to value
        is LocalDate -> org.jetbrains.exposed.sql.javatime.JavaLocalDateColumnType() to value
        else -> VarCharColumnType() to value.toString()
    }

    /**
     * Converts a JSON property value to an Exposed column type/value pair.
     */
    private fun jsonValueToColumnArg(key: String, value: JsonElement): Pair<IColumnType<*>, Any?> {
        if (value is JsonNull || value == JsonNull) return VarCharColumnType() to null
        return when (value) {
            is JsonPrimitive -> when {
                value.isString -> VarCharColumnType() to value.content
                value.content.toBooleanStrictOrNull() != null -> BooleanColumnType() to value.boolean
                value.content.toIntOrNull() != null -> IntegerColumnType() to value.int
                value.content.toLongOrNull() != null -> LongColumnType() to value.long
                value.content.toDoubleOrNull() != null -> DoubleColumnType() to value.double
                else -> VarCharColumnType() to value.content
            }

            is JsonObject -> VarCharColumnType() to value.toString()
            is JsonArray -> VarCharColumnType() to value.toString()
        }
    }

    /**
     * Builds SQL WHERE clauses from Kilua RPC [RemoteFilter] list.
     * Resolves the column type from the entity's property metadata so that
     * filter values are bound with the correct JDBC type (not always VARCHAR).
     */
    private fun buildWhereFromFilters(
        filters: List<RemoteFilter>?,
        clauses: MutableList<String>,
        args: MutableList<Pair<IColumnType<*>, Any?>>,
    ) {
        filters?.forEach { filter ->
            val column = resolveColumnName(filter.field)
            val colType = resolveColumnType(filter.field)
            val typedValue = coerceFilterValue(filter.value, colType)
            val operator = when (filter.type) {
                "like" -> "LIKE"
                "=" -> "="
                "!=" -> "!="
                ">" -> ">"
                "<" -> "<"
                ">=" -> ">="
                "<=" -> "<="
                else -> "="
            }
            if (filter.type == "like") {
                clauses += "$column LIKE ?"
                args += VarCharColumnType() to "%${filter.value}%"
            } else {
                clauses += "$column $operator ?"
                args += colType to typedValue
            }
        }
    }

    /**
     * Coerces a string filter value into the appropriate JVM type
     * matching the given Exposed [IColumnType].
     *
     * @param value The raw string value from the remote filter.
     * @param colType The resolved column type for the target property.
     * @return The coerced value, or the original string if conversion fails.
     */
    private fun coerceFilterValue(value: String?, colType: IColumnType<*>): Any? {
        if (value == null) return null
        return when (colType) {
            is IntegerColumnType -> value.toIntOrNull() ?: value
            is LongColumnType -> value.toLongOrNull() ?: value
            is DoubleColumnType -> value.toDoubleOrNull() ?: value
            is FloatColumnType -> value.toFloatOrNull() ?: value
            is BooleanColumnType -> value.toBooleanStrictOrNull() ?: value
            is JavaLocalDateTimeColumnType -> runCatching { LocalDateTime.parse(value) }.getOrDefault(value)
            is org.jetbrains.exposed.sql.javatime.JavaLocalDateColumnType -> runCatching { LocalDate.parse(value) }.getOrDefault(
                value
            )

            else -> value
        }
    }

    /**
     * Builds SQL ORDER BY clause from Kilua RPC [RemoteSorter] list.
     */
    private fun buildOrderFromSorters(sorters: List<RemoteSorter>?): String {
        if (sorters.isNullOrEmpty()) return ""
        val parts = sorters.map { sorter ->
            val column = resolveColumnName(sorter.field)
            val dir = if (sorter.dir == "desc") "DESC" else "ASC"
            "$column $dir"
        }
        return "ORDER BY ${parts.joinToString(", ")}"
    }

    companion object {
        /**
         * Produces a user-friendly message from SQL exceptions.
         */
        internal fun friendlyExceptionMessage(e: Exception): String? = e.message
    }
}
