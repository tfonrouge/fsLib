package com.example.rbac

import com.fonrouge.base.api.CrudTask
import com.fonrouge.base.model.AppRolePolicy
import com.fonrouge.base.model.IAppRole
import com.fonrouge.base.model.IAppRole.BaseRolePermission
import com.fonrouge.base.model.IAppRole.RoleType
import com.fonrouge.base.model.PermissionType
import com.fonrouge.base.model.RoleGrant
import com.fonrouge.base.types.OId
import com.fonrouge.fullStack.memoryDb.InMemoryRbacGrantPort
import com.fonrouge.fullStack.repository.RbacMembership
import com.fonrouge.fullStack.repository.RbacResolver
import kotlinx.coroutines.runBlocking

/**
 * Runnable RBAC walkthrough (blueprint `rbac-permission-resolution`, P3.3) — closes R8 (no sample wired RBAC).
 *
 * It seeds the **shippable** in-memory grant port ([InMemoryRbacGrantPort]) and drives the **real**,
 * backend-agnostic resolver ([RbacResolver]) and membership API ([RbacMembership]) — the exact code the
 * MongoDB engine runs behind `IRoleInUserColl` — to demonstrate, with **no database**:
 *
 *  1. the group-only membership **bypass fix** (R12) — a user who holds a role only through a group is seen;
 *  2. **direct-grant precedence** (D1) — a direct grant outweighs the group, and is **not** a deny/allow union;
 *  3. **existence ≠ authorization** (D8) — a grant edge can exist while the user is still denied;
 *  4. the intra-group **tie-break** (D2) — `upVoteInGroup` opting into allow-override.
 *
 * In production the same algebra runs over the MongoDB engine — see `README.md` for the wiring
 * (`MongoRbac.register(roleInUserColl)` + `IAppRoleColl.ensureRoles(...)` at boot).
 *
 * Run: `./gradlew :samples:rbac:run`.
 */
fun main() = runBlocking {
    println("== fsLib RBAC walkthrough (in-memory grant port, no database) ==\n")

    // A SingleAction app role "delete-records": its default verdict is Deny, so a user with no grant is denied.
    val deleteRecords: OId<out IAppRole<*>> = OId<IAppRole<*>>()
    val port = InMemoryRbacGrantPort<String>()
    port.putAppRolePolicy(
        AppRolePolicy(
            id = deleteRecords,
            roleType = RoleType.SingleAction,
            defaultPermission = BaseRolePermission.Deny,
            defaultCrudTaskSet = null,
            upVoteInGroup = BaseRolePermission.Allow,
        )
    )

    // 1) Group-only membership — the bypass fix (R12). alice holds delete-records ONLY through the
    //    "admins" group; she has no direct row. A raw `RoleInUser` count would be group-blind (false).
    port.addMembership("alice", "admins")
    port.addGroupGrant("admins", deleteRecords, RoleGrant(PermissionType.Allow, crudTaskSet = null))
    singleActionScenario(
        port, "alice", deleteRecords,
        title = "1. Group-only membership (the bypass fix, R12)",
        note = "alice holds the role only via 'admins' (no direct row) — a raw RoleInUser count would miss it",
    )

    // 2) Direct grant beats group (D1). bob is in 'admins' (Allow) but has a direct Deny row. The direct
    //    grant decides — a naive deny/allow union would wrongly allow.
    port.addMembership("bob", "admins")
    port.putDirectGrant("bob", deleteRecords, RoleGrant(PermissionType.Deny, crudTaskSet = null))
    singleActionScenario(
        port, "bob", deleteRecords,
        title = "2. Direct grant beats group (D1 precedence, not a union)",
        note = "bob is in 'admins' (Allow) but has a direct Deny — the direct grant wins (a union would allow)",
    )

    // 3) Existence != authorization (D8). carol's 'auditors' group carries a Deny grant: a grant edge
    //    exists, but she is not allowed.
    port.addMembership("carol", "auditors")
    port.addGroupGrant("auditors", deleteRecords, RoleGrant(PermissionType.Deny, crudTaskSet = null))
    singleActionScenario(
        port, "carol", deleteRecords,
        title = "3. Existence != authorization (D8)",
        note = "carol's group carries a Deny — a grant edge EXISTS (has=true) yet she is not allowed (allowed=false)",
    )

    // 4) CRUD-task role + intra-group tie-break (D2/D3), shown via the resolver directly. dave is in two
    //    groups granting Update — one Allow, one Deny. upVoteInGroup = Allow opts into allow-override.
    val editProjects: OId<out IAppRole<*>> = OId<IAppRole<*>>()
    val editPolicy = AppRolePolicy(
        id = editProjects,
        roleType = RoleType.CrudTask,
        defaultPermission = BaseRolePermission.Deny,
        defaultCrudTaskSet = null,
        upVoteInGroup = BaseRolePermission.Allow,
    )
    port.putAppRolePolicy(editPolicy)
    port.addMembership("dave", "writers")
    port.addMembership("dave", "reviewers")
    port.addGroupGrant("writers", editProjects, RoleGrant(PermissionType.Allow, crudTaskSet = setOf(CrudTask.Update)))
    port.addGroupGrant("reviewers", editProjects, RoleGrant(PermissionType.Deny, crudTaskSet = setOf(CrudTask.Update)))
    val updateVerdict = RbacResolver.resolve(userId = "dave", policy = editPolicy, crudTask = CrudTask.Update, port = port)
    println("4. CRUD-task intra-group tie-break (D2)")
    println("   dave is in 'writers' (Allow Update) and 'reviewers' (Deny Update); upVoteInGroup = Allow")
    println("   opts into allow-override, so Update resolves to: $updateVerdict")
    println("   (with upVoteInGroup = Deny it would be the safe deny-override default: Deny)\n")

    println("== done — README.md shows the production MongoDB wiring (MongoRbac.register + ensureRoles) ==")
}

/**
 * Runs both membership operations for `(userId, appRoleId)` and prints the contrast — existence
 * ([RbacMembership.hasSingleActionGrant]) vs effective authorization ([RbacMembership.isAllowedSingleAction]).
 */
private suspend fun singleActionScenario(
    port: InMemoryRbacGrantPort<String>,
    userId: String,
    appRoleId: OId<out IAppRole<*>>,
    title: String,
    note: String,
) {
    val has = RbacMembership.hasSingleActionGrant(port, userId, appRoleId)
    val allowed = RbacMembership.isAllowedSingleAction(port, userId, appRoleId)
    println(title)
    println("   $note")
    println("   hasSingleActionGrant($userId) = $has   isAllowedSingleAction($userId) = $allowed\n")
}
