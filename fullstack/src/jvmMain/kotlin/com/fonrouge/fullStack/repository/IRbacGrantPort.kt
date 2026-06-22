package com.fonrouge.fullStack.repository

import com.fonrouge.base.model.AppRolePolicy
import com.fonrouge.base.model.IAppRole
import com.fonrouge.base.model.RoleGrant
import com.fonrouge.base.types.OId

/**
 * Backend-agnostic, `ApplicationCall`-free port for fetching the RBAC grants a permission decision
 * depends on.
 *
 * The resolution algebra ([RbacResolver]) is pure: it never touches a database engine or a Ktor request.
 * Instead it drives every data access through this port, which a concrete engine (MongoDB, and later SQL
 * / in-memory) implements over its own collections. The port returns the engine-neutral [RoleGrant]
 * value type rather than any document type.
 *
 * Beyond the grant fetches the resolver consumes, the port also exposes [fetchAppRolePolicy] so the
 * higher-level, group-aware membership API ([RbacMembership]) can be driven purely from a
 * `(userId, appRoleId)` key — the engine resolves the app role to its backend-agnostic [AppRolePolicy]
 * snapshot, keeping the membership entry points fully engine- and document-agnostic.
 *
 * @param UID The user-identifier type (e.g. `OId<TUser>`).
 */
interface IRbacGrantPort<UID : Any> {

    /**
     * Resolves the [AppRolePolicy] snapshot for the given app role id, or `null` when no such role exists.
     *
     * This is a **non-provisioning** lookup (D4): a missing role yields `null` and the engine performs no
     * write. It lets the membership API ([RbacMembership.isAllowedSingleAction]) project the concrete app
     * role into the backend-agnostic policy the resolver consumes, keyed solely by [appRoleId] — so the
     * membership entry points never hold an engine-specific app-role document.
     *
     * @param appRoleId The app role to resolve into a policy snapshot.
     * @return The [AppRolePolicy] for the role, or `null` when the role does not exist.
     */
    suspend fun fetchAppRolePolicy(appRoleId: OId<out IAppRole<*>>): AppRolePolicy?

    /**
     * Whether the given user is a root user, which short-circuits resolution to an unconditional allow.
     *
     * @param userId The user to check.
     * @return `true` if the user is root.
     */
    suspend fun isRootUser(userId: UID): Boolean

    /**
     * Fetches the user's **direct** role grant for the given app role, if one exists.
     *
     * A present direct grant takes precedence over all group grants (D1).
     *
     * @param userId The user whose direct grant is sought.
     * @param appRoleId The app role being evaluated.
     * @return The direct [RoleGrant], or `null` when the user has no direct row for this role.
     */
    suspend fun fetchDirectGrant(userId: UID, appRoleId: OId<out IAppRole<*>>): RoleGrant?

    /**
     * Fetches the grants contributed by the **groups** the user belongs to for the given app role.
     *
     * Each member group's matching role-in-group row is projected to a [RoleGrant]. The resolver applies
     * the group tie-break (D2) over the returned list, and for CRUD-task roles **the resolver** first
     * filters the list to grants whose `crudTaskSet` covers the requested task (the port returns all
     * matching group grants for the role).
     *
     * @param userId The user whose group memberships are consulted.
     * @param appRoleId The app role being evaluated.
     * @return The list of group [RoleGrant]s (possibly empty).
     */
    suspend fun fetchGroupGrants(userId: UID, appRoleId: OId<out IAppRole<*>>): List<RoleGrant>

    /**
     * Whether a direct grant exists for `(userId, appRoleId)` without decoding it.
     *
     * Declared as part of the Phase-4-ready port shape (a cheap existence probe for callers that only
     * need presence). Not consulted by [RbacResolver] in this phase.
     *
     * @param userId The user whose direct grant presence is checked.
     * @param appRoleId The app role being evaluated.
     * @return `true` if a direct row exists.
     */
    suspend fun existsDirectGrant(userId: UID, appRoleId: OId<out IAppRole<*>>): Boolean

    /**
     * Whether any group grant exists for `(userId, appRoleId)` without decoding the grants.
     *
     * Declared as part of the Phase-4-ready port shape. Not consulted by [RbacResolver] in this phase.
     *
     * @param userId The user whose group-grant presence is checked.
     * @param appRoleId The app role being evaluated.
     * @return `true` if at least one matching group grant exists.
     */
    suspend fun existsGroupGrant(userId: UID, appRoleId: OId<out IAppRole<*>>): Boolean
}
