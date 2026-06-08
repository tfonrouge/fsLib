package com.fonrouge.fullStack.repository

import com.fonrouge.base.api.*
import com.fonrouge.base.common.ICommonContainer
import com.fonrouge.base.model.BaseDoc
import com.fonrouge.base.state.ItemState
import com.fonrouge.base.state.ListState
import com.fonrouge.base.state.SimpleState
import io.ktor.server.application.*
import kotlin.reflect.KProperty1

/**
 * Backend-agnostic repository interface for CRUD operations, list queries,
 * lifecycle hooks, and cross-cutting concerns (permissions, change logging, dependencies).
 *
 * This interface defines the portable contract that both MongoDB ([com.fonrouge.fullStack.mongoDb.Coll])
 * and SQL implementations can fulfill. Methods use only platform-neutral types; backend-specific
 * parameters (BSON filters, lookup wrappers, aggregation pipelines) are left to concrete implementations
 * as additional overloads.
 *
 * ## Write & Lifecycle Contract
 *
 * The authoritative spec lives in `blueprints/repository-write-lifecycle/CONTRACT.md`. Invariants
 * tagged **(target)** are the agreed end-state still rolling out across engines (see
 * `blueprints/repository-write-lifecycle/PLAN.md`); the cross-engine conformance suite (PLAN P1.8)
 * is being built to pin them. Untagged invariants are enforced today.
 *
 * - **Two write tiers (one-directional).** [apiItemProcess] is the generic/remote entry point and
 *   dispatches down to the low-level [insertOne]/[updateOne]/[deleteOne] methods, which form the
 *   trusted service tier and never call back into [apiItemProcess]. Domain services call the
 *   low-level methods directly.
 * - **Generic-CRUD gate.** `allowApiCrud` is invoked once in the [apiItemProcess] write (Action)
 *   branch — after [asApiItem] and the [readOnly] gate, and before action dispatch, any applicable
 *   per-action CRUD permission check, and the write lifecycle hooks. It gates the generic/remote tier
 *   **only**; the service tier bypasses it. Distinct from [readOnly] (which blocks *all* tiers).
 * - **Validation & after-hooks.** [onValidate] runs after the before-hooks and before the write. A
 *   validation failure — or a no-op/no-change skip — returns *before the write is attempted*: it
 *   fires **no** after-hooks and writes **no** change-log entry. Once a write is *attempted*, its
 *   after-hooks fire **exactly once** with a success flag (they run even when the write fails, with
 *   `result = false`); the change-log is written **only on success**.
 * - **Canonical hook order (target).** Before-hooks shared→specific ([onQueryUpsert] →
 *   `onQuery{Create|Update}`, then [onBeforeUpsertAction] → `onBefore{Create|Update}Action`);
 *   after-hooks specific→shared (`onAfter{Create|Update}Action` → [onAfterUpsertAction]); the shared
 *   *Upsert* hook is outermost. Rollout: Mongo/SQL `updateOne` before-hooks and Mongo
 *   `updateFieldsById` query gates still differ — see PLAN P2.2.
 * - **Dependency safety.** [findChildrenNot] blocks deleting a parent that still has children, in
 *   every engine. **(target:** run *exactly once*, owned solely by the concrete [deleteOne] —
 *   Mongo/SQL currently also check via [onQueryDelete]; see PLAN P2.1**)**
 * - **Permissions.** On the generic/remote path a non-null `call` engages the per-action CRUD
 *   permission check (after `allowApiCrud`); when `call` is null (the trusted service tier) the check
 *   is a documented no-op. The in-memory engine is intentionally permission-free (samples/tests).
 * - **Initialization (target).** [onAfterOpen] should run exactly once before first use and surface
 *   failures; today only the Mongo engine auto-invokes it (fire-and-forget) — see PLAN P2.3.
 *
 * @param T The entity type, must extend [BaseDoc].
 * @param ID The identifier type.
 * @param FILT The filter type, must extend [IApiFilter].
 * @param UID The user identifier type (for role-based access control).
 */
interface IRepository<T : BaseDoc<ID>, ID : Any, FILT : IApiFilter<*>, UID : Any> {

    // ── Metadata ──────────────────────────────────────────────

    /** The container providing entity metadata (KClass, serializers, labels). */
    val commonContainer: ICommonContainer<T, ID, FILT>

    /** Whether this repository is read-only (blocks all write operations). */
    val readOnly: Boolean

    /** Error message returned when a write operation is attempted on a read-only repository. */
    val readOnlyErrorMsg: String

    // ── Cross-cutting Concerns ────────────────────────────────

    /** Optional factory for the change log repository used to track mutations. */
    val changeLogCollFun: () -> IChangeLogRepository?

    /** Optional list of dependent repositories that reference this entity (checked before deletion). */
    val dependencies: (() -> List<Dependency<*, ID>>)?

    /** Factory for the user repository, used for role-based access control. */
    val userCollFun: () -> IUserRepository<*, UID>?

    // ── CRUD: Single-item Operations ──────────────────────────

    /**
     * Inserts a new item into the data store.
     *
     * @param item The item to insert.
     * @param apiFilter Filter context for the operation.
     * @param call Optional Ktor request context for permission checks.
     * @return [ItemState] with the inserted item or error information.
     */
    suspend fun insertOne(
        item: T,
        apiFilter: FILT = commonContainer.apiFilterInstance(),
        call: ApplicationCall? = null,
    ): ItemState<T>

    /**
     * Finds an item by its identifier.
     *
     * @param id The identifier to search for.
     * @param apiFilter Filter context for the query.
     * @return The matching item, or null if not found.
     */
    suspend fun findById(
        id: ID?,
        apiFilter: FILT = commonContainer.apiFilterInstance(),
    ): T?

    /**
     * Finds an item by its identifier, returning an [ItemState] with error information if not found.
     *
     * @param id The identifier to search for.
     * @param apiFilter Filter context for the query.
     * @return [ItemState] with the item or error message.
     */
    suspend fun findItemStateById(
        id: ID?,
        apiFilter: FILT = commonContainer.apiFilterInstance(),
    ): ItemState<T>

    /**
     * Updates an existing item by replacing it with a new version.
     *
     * @param item The item with updated values (identified by its _id).
     * @param apiFilter Filter context for the operation.
     * @param call Optional Ktor request context for permission checks.
     * @return [ItemState] with the updated item or error information.
     */
    suspend fun updateOne(
        item: T,
        apiFilter: FILT = commonContainer.apiFilterInstance(),
        call: ApplicationCall? = null,
    ): ItemState<T>

    /**
     * Deletes an item by its identifier.
     *
     * @param id The identifier of the item to delete.
     * @param apiFilter Filter context for the operation.
     * @return [ItemState] indicating success or error.
     */
    suspend fun deleteOne(
        id: ID,
        apiFilter: FILT = commonContainer.apiFilterInstance(),
    ): ItemState<T>

    // ── API Item Processing ───────────────────────────────────

    /**
     * Processes an API item request, dispatching to the appropriate CRUD operation
     * based on the [ApiItem] type. Handles the full Query -> Permission -> Action -> ChangeLog flow.
     *
     * @param call Optional Ktor request context.
     * @param iApiItem The serialized API item to process.
     * @return [ItemState] with the result of the operation.
     */
    suspend fun apiItemProcess(
        call: ApplicationCall?,
        iApiItem: IApiItem<T, ID, FILT>,
    ): ItemState<T>

    // ── List Operations ───────────────────────────────────────

    /**
     * Processes a paginated list request with filtering, sorting, and pagination.
     *
     * This is the main entry point for grid/table views. The [ApiList] parameter carries
     * page number, page size, remote filters (from Tabulator header filters), remote sorters,
     * and the typed API filter — all in a backend-neutral format.
     *
     * @param call Optional Ktor request context for permission checks.
     * @param apiList The list request parameters (pagination, filters, sorters, apiFilter).
     * @return [ListState] with the result data, pagination info, and status.
     */
    suspend fun apiListProcess(
        call: ApplicationCall? = null,
        apiList: ApiList<FILT>,
    ): ListState<T>

    /**
     * Finds a list of items matching the filter criteria.
     *
     * @param apiFilter Filter context for the query.
     * @return List of matching items.
     */
    suspend fun findList(
        apiFilter: FILT = commonContainer.apiFilterInstance(),
    ): List<T>

    /**
     * Finds a single item matching the filter criteria.
     *
     * @param apiFilter Filter context for the query.
     * @return The first matching item, or null if none found.
     */
    suspend fun findOne(
        apiFilter: FILT = commonContainer.apiFilterInstance(),
    ): T?

    // ── Permissions ───────────────────────────────────────────

    /**
     * Checks whether the current user has permission for the specified CRUD task.
     *
     * @param call The Ktor request context containing session/user information.
     * @param crudTask The CRUD operation to check permission for.
     * @return [SimpleState] indicating whether the operation is permitted.
     */
    suspend fun getCrudPermission(
        call: ApplicationCall,
        crudTask: CrudTask,
    ): SimpleState

    // ── Existence Check ────────────────────────────────────────

    /**
     * Checks whether any document/row exists where the given property matches the specified value.
     *
     * This is the engine-agnostic primitive used by [findChildrenNot] to verify
     * cross-engine dependency references. Each backend implements this against its
     * own data store.
     *
     * @param property The property to match against.
     * @param value The value to search for.
     * @return True if at least one matching record exists.
     */
    suspend fun existsByField(property: KProperty1<out BaseDoc<*>, *>, value: Any?): Boolean

    // ── Dependency Checking ───────────────────────────────────

    /**
     * Checks that no child records in dependent repositories reference this item,
     * ensuring safe deletion without orphaning data.
     *
     * @param item The item to check for existing dependencies.
     * @return [ItemState] with an error if dependencies exist, or the item if safe to delete.
     */
    suspend fun findChildrenNot(item: T): ItemState<T>

    // ── Lifecycle Hooks ───────────────────────────────────────
    // These hooks are called at specific points during CRUD processing.
    // Override them to add custom validation, transformation, or side effects.

    // -- Query phase hooks (validation before the actual action) --

    /**
     * Called during Query.Create processing. Override to validate or reject creation requests.
     *
     * @param apiItem The create query being processed.
     * @return [SimpleState] indicating whether to proceed.
     */
    suspend fun onQueryCreate(apiItem: ApiItem.Query.Create<T, ID, FILT>): SimpleState

    /**
     * Called during Query.Create to seed the Create form. Two idiomatic return shapes:
     *
     * - **Full item** — `return ItemState(item = T(...))` when a valid fully-constructed `T` makes sense
     *   as a default (e.g. simple entity with sensible constructor defaults).
     * - **Sparse seeds** — `return ItemState(serializedValueMap = serializedValueMapEntry(T::field, value) + ...)`
     *   when constructing a full `T` is impractical (required non-nullable fields, generated `_id`, etc.).
     *   Each entry maps a property name to a [kotlinx.serialization.json.JsonElement] via
     *   [com.fonrouge.base.state.serializedValueMapEntry]; on the client, matching form controls are
     *   pre-populated and unmatched entries ride the submission overlay as hidden fields.
     *
     * Returning `ItemState(isOk = true)` (no item, no map) yields an empty form — valid if the user is
     * expected to fill everything from scratch.
     *
     * @param apiItem The create query being processed.
     * @return [ItemState] carrying either an `item` template or a `serializedValueMap` of field seeds.
     */
    suspend fun onQueryCreateItem(apiItem: ApiItem.Query.Create<T, ID, FILT>): ItemState<T>

    /**
     * Called during Query.Read processing. Override to add custom read validation.
     *
     * @param apiItem The read query being processed.
     * @return [SimpleState] indicating whether to proceed.
     */
    suspend fun onQueryRead(apiItem: ApiItem.Query.Read<T, ID, FILT>): SimpleState

    /**
     * Called during Query.Update processing. Override to validate update requests.
     *
     * @param apiItem The update query being processed.
     * @param orig The original item before modification.
     * @return [SimpleState] indicating whether to proceed.
     */
    suspend fun onQueryUpdate(apiItem: ApiItem.Query.Update<T, ID, FILT>, orig: T): SimpleState

    /**
     * Called during Query.Delete processing. Override to add custom deletion validation.
     *
     * @param apiItem The delete query being processed.
     * @param item The item being deleted.
     * @return [SimpleState] indicating whether to proceed.
     */
    suspend fun onQueryDelete(apiItem: ApiItem.Query.Delete<T, ID, FILT>, item: T): SimpleState

    /**
     * Called during both create and update query processing. Override for shared upsert validation.
     *
     * @param apiItem The query being processed.
     * @param orig The original item (null for creation).
     * @return [SimpleState] indicating whether to proceed.
     */
    suspend fun onQueryUpsert(apiItem: ApiItem.Query<T, ID, FILT>, orig: T?): SimpleState

    // -- Action phase hooks (before/after the actual database mutation) --

    /**
     * Called before executing an insert action. Override to transform the item or reject the operation.
     *
     * @param apiItem The create action about to be executed.
     * @return [ItemState] — return an error to abort, or an item to replace the one being inserted.
     */
    suspend fun onBeforeCreateAction(apiItem: ApiItem.Action.Create<T, ID, FILT>): ItemState<T>

    /**
     * Called before executing an update action. Override to transform the item or reject the operation.
     *
     * @param apiItem The update action about to be executed.
     * @param orig The original item before modification.
     * @return [ItemState] — return an error to abort, or an item to replace the one being saved.
     */
    suspend fun onBeforeUpdateAction(apiItem: ApiItem.Action.Update<T, ID, FILT>, orig: T): ItemState<T>

    /**
     * Called before executing a delete action. Override to reject the operation.
     *
     * @param apiItem The delete action about to be executed.
     * @return [ItemState] — return an error to abort.
     */
    suspend fun onBeforeDeleteAction(apiItem: ApiItem.Action.Delete<T, ID, FILT>): ItemState<T>

    /**
     * Called before executing any create or update action. Override for shared upsert logic.
     *
     * @param apiItem The action about to be executed.
     * @param orig The original item (null for creation).
     * @return [ItemState] — return an error to abort, or an item to replace the one being saved.
     */
    suspend fun onBeforeUpsertAction(apiItem: ApiItem.Action<T, ID, FILT>, orig: T?): ItemState<T>

    /**
     * Called after a create action completes.
     *
     * @param apiItem The create action that was executed.
     * @param result Whether the operation succeeded.
     */
    suspend fun onAfterCreateAction(apiItem: ApiItem.Action.Create<T, ID, FILT>, result: Boolean)

    /**
     * Called after an update action completes.
     *
     * @param apiItem The update action that was executed.
     * @param orig The original item before modification.
     * @param result Whether the operation succeeded.
     */
    suspend fun onAfterUpdateAction(apiItem: ApiItem.Action.Update<T, ID, FILT>, orig: T, result: Boolean)

    /**
     * Called after a delete action completes.
     *
     * @param apiItem The delete action that was executed.
     * @param result Whether the operation succeeded.
     */
    suspend fun onAfterDeleteAction(apiItem: ApiItem.Action.Delete<T, ID, FILT>, result: Boolean)

    /**
     * Called after any create or update action completes.
     *
     * @param apiItem The action that was executed.
     * @param orig The original item (null for creation).
     * @param result Whether the operation succeeded.
     */
    suspend fun onAfterUpsertAction(apiItem: ApiItem.Action<T, ID, FILT>, orig: T?, result: Boolean)

    /**
     * Called after the repository is opened/initialized.
     */
    suspend fun onAfterOpen()

    // -- Validation --

    /**
     * Called before persisting an item to validate its contents.
     *
     * @param apiItem The action being validated.
     * @param item The item to validate.
     * @return [SimpleState] — return an error to reject the item.
     */
    suspend fun onValidate(apiItem: ApiItem.Action<T, ID, FILT>, item: T): SimpleState

    // -- API item transformation --

    /**
     * Transforms or validates an [ApiItem] before it is processed. Override to modify
     * the API item or reject it early.
     *
     * @param apiItem The API item to transform.
     * @return [ItemState] wrapping the (possibly modified) API item, or an error to reject.
     */
    suspend fun asApiItem(apiItem: ApiItem<T, ID, FILT>): ItemState<ApiItem<T, ID, FILT>>

    // ── Generic-CRUD gate ─────────────────────────────────────

    /**
     * Gate for the **generic / remote** write tier. Invoked exactly once in the write (Action) branch
     * of [apiItemProcess] — after [asApiItem] transformation and the [readOnly] gate, and before
     * action dispatch, any applicable per-action CRUD permission check, and the write lifecycle hooks.
     *
     * Gates the generic/remote entry point **only**: the low-level service-tier methods
     * ([insertOne], [updateOne], [deleteOne], and engine helpers such as `updateFieldsById`)
     * intentionally bypass it, so domain services call them directly. Override this — rather than
     * overriding the whole [apiItemProcess] dispatcher — to make an entity "writable only via domain
     * services": return an error [SimpleState] to reject generic writes while leaving the trusted
     * service tier open. Reads (`Query`) are never gated.
     *
     * Distinct from [readOnly], which blocks *all* tiers; use a distinct error message
     * (e.g. "not writable via the generic API"), never the read-only message.
     *
     * @param apiItem The create/update/delete action arriving through the generic entry point.
     * @return [SimpleState] — an error rejects the generic write; the default permits all writes.
     */
    suspend fun allowApiCrud(apiItem: ApiItem.Action<T, ID, FILT>): SimpleState = SimpleState(isOk = true)

    // ── Nested Types ──────────────────────────────────────────

    /**
     * Represents a dependency relationship between repositories/collections.
     * Used to prevent deletion of items that are referenced by other entities.
     *
     * @param T The type of document in the dependent repository that references this one.
     * @param ID The type of the identifier used to reference items in this repository.
     * @param common The container managing the dependent entity's metadata.
     * @param property The property within the dependent document that holds the foreign key reference.
     * @param repositoryFun Optional factory returning the [IRepository] that owns the dependent entity.
     *   When provided, [findChildrenNot] uses [existsByField] on that repository, enabling
     *   cross-engine dependency checks (e.g., SQL entity referencing a MongoDB document).
     *   When null, the current repository's engine-specific logic is used as a fallback.
     */
    data class Dependency<T : BaseDoc<*>, ID : Any>(
        val common: ICommonContainer<T, *, *>,
        val property: KProperty1<out T, ID?>,
        val repositoryFun: (() -> IRepository<*, *, *, *>)? = null,
    )
}
