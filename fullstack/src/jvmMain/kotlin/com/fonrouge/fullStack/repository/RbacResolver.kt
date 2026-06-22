package com.fonrouge.fullStack.repository

import com.fonrouge.base.api.CrudTask
import com.fonrouge.base.model.AppRolePolicy
import com.fonrouge.base.model.IAppRole.BaseRolePermission
import com.fonrouge.base.model.IAppRole.RoleType
import com.fonrouge.base.model.PermissionType

/**
 * Pure, backend-agnostic and `ApplicationCall`-free RBAC permission-resolution algebra.
 *
 * This object holds the resolution semantics that previously lived inline in the MongoDB
 * `IRoleInUserColl`. It reaches a [BaseRolePermission] verdict for a `(user, app role, crud task)`
 * triple by driving all data access through an [IRbacGrantPort], so it depends on no database engine
 * and no Ktor request context. Callers wrap the returned verdict (e.g. into a `SimpleState`) at the
 * entry point.
 *
 * The algebra is preserved exactly from the prior Mongo implementation:
 * 1. **Root short-circuit** — a root user resolves to [BaseRolePermission.Allow] before any lookup.
 * 2. **Direct grant precedence (D1)** — a direct user grant, if present, decides the verdict and groups
 *    are *not* consulted.
 * 3. **Group tie-break (D2)** — otherwise the user's group grants are combined under a uniform total
 *    rule biased by `upVoteInGroup`.
 * 4. **Role default (D3)** — when no applicable grant exists, the role default applies as an allow-list
 *    (no inversion).
 */
object RbacResolver {

    /**
     * Resolves the effective [BaseRolePermission] for the given user, app-role policy and optional CRUD
     * task, fetching grants through [port].
     *
     * @param UID The user-identifier type.
     * @param userId The user whose permission is being resolved.
     * @param policy The backend-agnostic snapshot of the app role under evaluation (carries its own id).
     * @param crudTask The CRUD task being checked for [RoleType.CrudTask] policies; `null` for
     *   [RoleType.SingleAction].
     * @param port The grant-fetch port used for all data access (root check, direct grant, group grants).
     * @return The resolved verdict — [BaseRolePermission.Allow] or [BaseRolePermission.Deny].
     */
    suspend fun <UID : Any> resolve(
        userId: UID,
        policy: AppRolePolicy,
        crudTask: CrudTask?,
        port: IRbacGrantPort<UID>,
    ): BaseRolePermission {
        // (1) Root short-circuit.
        if (port.isRootUser(userId)) return BaseRolePermission.Allow

        // (3) Direct grant precedence (D1): if a direct row exists, it decides and groups are NOT consulted.
        val directGrant = port.fetchDirectGrant(userId = userId, appRoleId = policy.id)
        if (directGrant != null) {
            return when (policy.roleType) {
                RoleType.SingleAction -> when (directGrant.permission) {
                    PermissionType.Allow -> BaseRolePermission.Allow
                    PermissionType.Deny -> BaseRolePermission.Deny
                    PermissionType.Default -> policy.defaultPermission
                }

                RoleType.CrudTask -> if (directGrant.crudTaskSet?.contains(crudTask) == true) {
                    when (directGrant.permission) {
                        PermissionType.Allow -> BaseRolePermission.Allow
                        PermissionType.Deny -> BaseRolePermission.Deny
                        PermissionType.Default -> when (policy.defaultPermission) {
                            BaseRolePermission.Allow -> BaseRolePermission.Allow
                            BaseRolePermission.Deny -> BaseRolePermission.Deny
                        }
                    }
                } else BaseRolePermission.Deny
            }
        }

        // (4) No direct row → group resolution.
        return resolveGroup(userId = userId, policy = policy, crudTask = crudTask, port = port)
    }

    /**
     * Resolves the group-level verdict for a user with no direct grant, applying the uniform D2 tie-break.
     *
     * Mirrors the former Mongo `getGroupPermission`: it filters group grants to those applicable to the
     * requested task (for CRUD-task roles), and when none apply falls through to the role default.
     *
     * @param UID The user-identifier type.
     * @param userId The user whose group grants are combined.
     * @param policy The app-role policy under evaluation.
     * @param crudTask The CRUD task for [RoleType.CrudTask] policies; `null` for SingleAction.
     * @param port The grant-fetch port supplying the group grants.
     * @return The group-resolved verdict.
     */
    private suspend fun <UID : Any> resolveGroup(
        userId: UID,
        policy: AppRolePolicy,
        crudTask: CrudTask?,
        port: IRbacGrantPort<UID>,
    ): BaseRolePermission {
        val groupGrants = port.fetchGroupGrants(userId = userId, appRoleId = policy.id)
        val applicableGrants = when (policy.roleType) {
            RoleType.SingleAction -> groupGrants
            RoleType.CrudTask -> groupGrants.filter { grant ->
                crudTask?.let { grant.crudTaskSet?.contains(it) == true } != false
            }
        }
        if (applicableGrants.isEmpty()) return buildDefaultAppRolePermission(policy, crudTask)
        // D2 (total conflict rule, applied uniformly to single- and multi-group sets): the default bias is
        // deny-override (safe); `upVoteInGroup == Allow` is the explicit per-role allow-override opt-in. An
        // explicit Allow/Deny group grant is NEVER discarded into the role default — the role default
        // applies only when every applicable grant is Default.
        val hasAllow = applicableGrants.any { it.permission == PermissionType.Allow }
        val hasDeny = applicableGrants.any { it.permission == PermissionType.Deny }
        return when (policy.upVoteInGroup) {
            BaseRolePermission.Allow -> when {           // allow-override (per-role opt-in)
                hasAllow -> BaseRolePermission.Allow
                hasDeny -> BaseRolePermission.Deny
                else -> buildDefaultAppRolePermission(policy, crudTask)
            }

            BaseRolePermission.Deny -> when {            // deny-override (the safe default)
                hasDeny -> BaseRolePermission.Deny
                hasAllow -> BaseRolePermission.Allow
                else -> buildDefaultAppRolePermission(policy, crudTask)
            }
        }
    }

    /**
     * Builds the role-default verdict (D3) for the policy and optional CRUD task.
     *
     * Allow-list semantics, no inversion: a SingleAction policy yields its `defaultPermission`; a CrudTask
     * policy yields `defaultPermission` only for a task in `defaultCrudTaskSet`, else the safe baseline
     * [BaseRolePermission.Deny].
     *
     * @param policy The app-role policy under evaluation.
     * @param crudTask The CRUD task for CrudTask policies; `null` for SingleAction.
     * @return The role-default verdict.
     */
    private fun buildDefaultAppRolePermission(
        policy: AppRolePolicy,
        crudTask: CrudTask?,
    ): BaseRolePermission = when (policy.roleType) {
        RoleType.SingleAction -> policy.defaultPermission
        RoleType.CrudTask -> if (policy.defaultCrudTaskSet?.contains(crudTask) == true) {
            policy.defaultPermission
        } else {
            BaseRolePermission.Deny
        }
    }
}
