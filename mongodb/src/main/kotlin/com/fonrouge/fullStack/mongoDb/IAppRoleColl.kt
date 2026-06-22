package com.fonrouge.fullStack.mongoDb

import com.fonrouge.base.api.CrudTask
import com.fonrouge.base.api.IApiFilter
import com.fonrouge.base.common.ICommonContainer
import com.fonrouge.base.model.IAppRole
import com.fonrouge.base.model.IAppRole.RoleType
import com.fonrouge.base.state.ItemState
import com.fonrouge.base.state.SimpleState
import com.mongodb.client.model.IndexOptions
import org.litote.kmongo.eq

/**
 * This abstract class `IAppRoleColl` manages application roles within a collection and provides methods
 * to insert roles into the collection. It extends the `Coll` class by handling roles of type `IAppRole`.
 *
 * @param CC The common container type, extending `ICommonContainer` with generics `T`, `ID`, and `FILT`.
 * @param T The type of roles being managed, which must implement `IAppRole`.
 * @param ID The type of the identifier for the roles, which must be non-null.
 * @param FILT The type of API filter used for querying, which must extend `IApiFilter`.
 * @constructor Initializes the `IAppRoleColl` with the provided common container.
 *
 * @param commonContainer The common container instance used by this collection.
 * @param mongoDbBuilder Optional Mongo connection builder forwarded to [Coll]; when `null` (the
 *   default) the collection resolves its database from the process-global Mongo configuration,
 *   preserving prior behavior. Supplying a builder targets a specific server/database (e.g. a
 *   per-test Testcontainers instance).
 */
abstract class IAppRoleColl<T : IAppRole<ID>, ID : Any, FILT : IApiFilter<*>, UID : Any>(
    commonContainer: ICommonContainer<T, ID, FILT>,
    mongoDbBuilder: MongoDbBuilder? = null,
) : Coll<T, ID, FILT, UID>(
    commonContainer = commonContainer,
    mongoDbBuilder = mongoDbBuilder,
) {
    /**
     * Provisioning primitive for a SingleAction role. A no-op by default; downstream subclasses override
     * it to perform the actual write. **No longer invoked from the permission-check path** (D4 — side-effect-free
     * resolution); it is now called only from [ensureRoles] (or directly by the app at boot/migration).
     */
    open suspend fun insertSingleActionRole(
        classOwner: String,
        funcName: String,
    ): ItemState<T> = ItemState(isOk = false)

    /**
     * Provisioning primitive for a CRUD role (one per [container], keyed by `classOwner == container.name`).
     * A no-op by default; downstream subclasses override it to perform the actual write. **No longer invoked
     * from the permission-check path** (D4); it is now called only from [ensureRoles].
     */
    open suspend fun insertCrudRole(
        container: ICommonContainer<*, *, *>,
        crudTask: CrudTask,
    ): ItemState<T> = ItemState(isOk = false)

    /**
     * Provisions the application roles for the given [crudContainers] (one CRUD role each, keyed by
     * `classOwner == container.name`) and [singleActions] (`(classOwner, funcName)` pairs), by delegating
     * to [insertCrudRole] / [insertSingleActionRole].
     *
     * Call once at boot — e.g. after [open] — to replace the former *lazy* "provision on first permission
     * check" behavior, removed in 4.x (D4, side-effect-free resolution): **an unprovisioned role now
     * denies** rather than self-provisioning. The in-tree primitive defaults are no-ops that return
     * `isOk=false`, so this is effective only once a subclass implements them — calling it on an
     * unimplemented base reports failure for every requested role.
     *
     * **Re-run safety is the override's responsibility:** re-running calls the primitives again, so a
     * downstream [insertCrudRole]/[insertSingleActionRole] should be **idempotent** — find-or-insert, or
     * tolerate the unique `classOwner` / `classOwner`+`funcName` duplicate-key error from the indexes built
     * in [onAfterOpen]. Override this method for finer control (e.g. a per-container [CrudTask] seed other
     * than [CrudTask.Read]).
     *
     * @param crudContainers entity containers that need a CRUD-type role.
     * @param singleActions `(classOwner, funcName)` pairs that need a SingleAction role.
     * @return `isOk` only if **every** primitive succeeded; otherwise an error [SimpleState] aggregating the
     *   roles that were not provisioned.
     */
    open suspend fun ensureRoles(
        crudContainers: List<ICommonContainer<*, *, *>> = emptyList(),
        singleActions: List<Pair<String, String>> = emptyList(),
    ): SimpleState {
        val failures = mutableListOf<String>()
        crudContainers.forEach { container ->
            val result = insertCrudRole(container = container, crudTask = CrudTask.Read)
            if (result.hasError) failures += "CRUD role '${container.name}': ${result.msgError ?: "not provisioned"}"
        }
        singleActions.forEach { (classOwner, funcName) ->
            val result = insertSingleActionRole(classOwner = classOwner, funcName = funcName)
            if (result.hasError) failures += "SingleAction role '$classOwner::$funcName': ${result.msgError ?: "not provisioned"}"
        }
        return if (failures.isEmpty()) {
            SimpleState(isOk = true)
        } else {
            SimpleState(isOk = false, msgError = "ensureRoles: ${failures.size} role(s) not provisioned — ${failures.joinToString("; ")}")
        }
    }

    override suspend fun onAfterOpen() {
        coroutine.ensureIndex(IAppRole<*>::description)
        coroutine.ensureUniqueIndex(
            IAppRole<*>::roleType,
            IAppRole<*>::description,
            indexOptions = IndexOptions().collation(collation())
        )
        coroutine.ensureUniqueIndex(
            IAppRole<*>::classOwner,
            indexOptions = IndexOptions().partialFilterExpression(IAppRole<*>::roleType eq RoleType.CrudTask)
        )
        coroutine.ensureUniqueIndex(
            IAppRole<*>::classOwner,
            IAppRole<*>::funcName,
            indexOptions = IndexOptions().partialFilterExpression(IAppRole<*>::roleType eq RoleType.SingleAction)
        )
    }
}
