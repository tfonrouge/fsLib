package com.fonrouge.fullStack.repository

import com.fonrouge.base.api.CrudTask
import com.fonrouge.base.common.ICommonContainer
import com.fonrouge.base.state.SimpleState
import io.ktor.server.application.*

/**
 * Backend-agnostic interface for role-based CRUD permission checking.
 *
 * This abstraction decouples the permission system from any specific database engine.
 * The MongoDB module registers its implementation (backed by `IRoleInUserColl`) at boot via the explicit
 * `MongoRbac.register` registrar; other engines (e.g., SQL) consume it through [PermissionRegistry] without
 * importing MongoDB-specific types.
 */
interface IRolePermissionProvider {

    /**
     * Checks whether the current user has permission for the specified CRUD task
     * on the given entity container.
     *
     * @param commonContainer The container providing entity metadata.
     * @param call The Ktor request context containing session/user information.
     * @param crudTask The CRUD operation to check permission for.
     * @return [SimpleState] indicating whether the operation is permitted.
     */
    suspend fun getCrudPermission(
        commonContainer: ICommonContainer<*, *, *>,
        call: ApplicationCall,
        crudTask: CrudTask,
    ): SimpleState
}

/**
 * Global registry for the role-based permission provider.
 *
 * The MongoDB module registers its [IRolePermissionProvider] implementation here via an **explicit boot
 * call** (the MongoDB module's `MongoRbac.register`) — no longer a side effect of constructing a collection
 * (RBAC blueprint D10 / P3.2a). Other repository implementations (e.g., [SqlRepository]) query this registry
 * to check CRUD permissions without depending on MongoDB types.
 *
 * When no provider is registered, the outcome is **not** unconditionally "allowed": each repository's
 * `permissionEnforcement` (RBAC blueprint D6, since P2.4) decides. An enforcing repository
 * (`PermissionEnforcement.Enforce`, the default) **fails closed** on a remote write when no provider is
 * wired; a non-enforcing one (`PermissionEnforcement.Off`) treats the missing provider as allowed.
 */
object PermissionRegistry {

    /**
     * The currently registered permission provider, or null if none is registered.
     *
     * When null, the effective verdict is governed by the calling repository's `permissionEnforcement`
     * (Enforce ⇒ fail-closed for remote writes; Off ⇒ allowed) — it is not implicitly allowed.
     */
    var rolePermissionProvider: IRolePermissionProvider? = null
}
