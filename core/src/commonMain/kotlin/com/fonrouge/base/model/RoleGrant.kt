package com.fonrouge.base.model

import com.fonrouge.base.api.CrudTask
import com.fonrouge.base.model.IAppRole.BaseRolePermission
import com.fonrouge.base.model.IAppRole.RoleType
import com.fonrouge.base.types.OId
import kotlinx.serialization.Serializable

/**
 * Backend-agnostic projection of a single permission grant (a direct user-role row or a group-role row)
 * as consumed by the RBAC resolution algebra.
 *
 * This is the typed value that a grant-fetch port (`IRbacGrantPort`) returns instead of an
 * engine-specific document type. It carries only the two fields the resolver needs to reach a verdict:
 * the raw [permission] vote and, for CRUD-task roles, the [crudTaskSet] the grant covers.
 *
 * @property permission The raw permission vote on the grant (`Allow`, `Deny`, or `Default`).
 * @property crudTaskSet The set of CRUD tasks this grant covers, or `null` when the grant is not
 *   task-scoped (e.g. a SingleAction grant). For CRUD-task resolution the resolver/port filters group
 *   grants to those whose set contains the requested task.
 */
@Serializable
data class RoleGrant(
    val permission: PermissionType,
    val crudTaskSet: Set<CrudTask>?,
)

/**
 * Backend-agnostic snapshot of the app-role policy fields the RBAC resolution algebra needs.
 *
 * This decouples the resolver from the [IAppRole] document type (and from any database engine): the
 * caller resolves the concrete app role at the entry point and projects it into an [AppRolePolicy],
 * which the [com.fonrouge.fullStack.repository] resolver consumes. The role's identifier is carried
 * **explicitly** in [id] so the resolver can drive grant fetches through the port without holding the
 * full document.
 *
 * @property id The identifier of the app role being evaluated, used by the resolver to fetch grants.
 * @property roleType Whether this is a `SingleAction` or `CrudTask` role.
 * @property defaultPermission The role's default verdict, applied when resolution falls through to the
 *   role default.
 * @property defaultCrudTaskSet For `CrudTask` roles, the allow-list of tasks the default covers (D3,
 *   no inversion); `null`/absent means no task is covered by the default.
 * @property upVoteInGroup The per-role group tie-break bias: `Allow` opts into allow-override,
 *   `Deny` keeps the safe deny-override default.
 */
@Serializable
data class AppRolePolicy(
    val id: OId<out IAppRole<*>>,
    val roleType: RoleType,
    val defaultPermission: BaseRolePermission,
    val defaultCrudTaskSet: Set<CrudTask>?,
    val upVoteInGroup: BaseRolePermission,
) {
    companion object {
        /**
         * Projects a resolved [IAppRole] into the backend-agnostic [AppRolePolicy] the resolver consumes.
         *
         * App-role documents are identified by an [OId] (the RBAC model convention, matching
         * `IRoleInUser.appRoleId` / `IRoleInGroup.appRoleId`), so the star-projected `_id` is narrowed
         * to `OId<out IAppRole<*>>` here — the same value the Mongo `appRoleId eq` filters already use.
         *
         * @param appRole The concrete app role resolved at the entry point.
         * @return An [AppRolePolicy] capturing exactly the fields the resolution algebra reads.
         */
        @Suppress("UNCHECKED_CAST")
        fun of(appRole: IAppRole<*>): AppRolePolicy = AppRolePolicy(
            id = appRole._id as OId<out IAppRole<*>>,
            roleType = appRole.roleType,
            defaultPermission = appRole.defaultPermission,
            defaultCrudTaskSet = appRole.defaultCrudTaskSet,
            upVoteInGroup = appRole.upVoteInGroup,
        )
    }
}
