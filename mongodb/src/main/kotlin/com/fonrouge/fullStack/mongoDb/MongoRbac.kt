package com.fonrouge.fullStack.mongoDb

import com.fonrouge.fullStack.repository.IRolePermissionProvider
import com.fonrouge.fullStack.repository.PermissionRegistry

/**
 * Explicit, boot-time registrar for the MongoDB RBAC permission provider (RBAC blueprint D10 / R10, P3.2a).
 *
 * Replaces the former **construction side-effect** in `Coll.init`, where building any concrete
 * [IRoleInUserColl] silently wired two process globals. Registration is now a deliberate, app-owned boot
 * step — the application calls [register] once, after constructing its `IRoleInUserColl`, the same way RBAC
 * roles are provisioned explicitly via `IAppRoleColl.ensureRoles(...)` (D4) rather than as a side effect of
 * a read.
 *
 * It populates the two RBAC handles the two (still-separate in P3.2a) dispatch paths read:
 * - the `Coll.roleInUserColl` companion handle that Mongo's own permission path (`CollPermission`) resolves
 *   through; and
 * - [PermissionRegistry.rolePermissionProvider] — the backend-agnostic provider that **non-Mongo** engines
 *   (SQL, in-memory) consume without importing MongoDB types.
 *
 * Fail-closed is preserved (D6): with no [register] call both handles stay `null`, and each repository's
 * `permissionEnforcement` governs the verdict (`Enforce` ⇒ deny on a remote write; `Off` ⇒ allowed) — never
 * a silent allow-all.
 */
object MongoRbac {

    /**
     * Registers [roleInUserColl] as the process RBAC provider. Call **once at boot**, after constructing the
     * application's `IRoleInUserColl`.
     *
     * Last call wins: a subsequent [register] replaces the provider (the same effective semantics as the
     * former last-writer-wins side-effect, but now an explicit, ordered, app-owned step rather than an
     * invisible consequence of constructing a collection).
     *
     * @param roleInUserColl The application's role-in-user collection that drives RBAC resolution.
     */
    fun register(roleInUserColl: IRoleInUserColl<*, *, *, *, *, *>) {
        Coll.roleInUserColl = roleInUserColl
        PermissionRegistry.rolePermissionProvider = MongoRolePermissionProvider(roleInUserColl)
    }

    /**
     * Clears the registered RBAC provider — both handles return to `null`, so an enforcing repository fails
     * closed again (D6). Primarily for test isolation and controlled teardown; production apps register once
     * at boot and never unregister.
     */
    fun unregister() {
        Coll.roleInUserColl = null
        PermissionRegistry.rolePermissionProvider = null
    }

    /**
     * Whether **both** RBAC handles are currently wired: the `Coll.roleInUserColl` companion that Mongo's own
     * permission path reads **and** the agnostic [PermissionRegistry.rolePermissionProvider] that non-Mongo
     * engines read. `true` only after [register] (and before [unregister]); `false` when unregistered, where
     * enforcing repositories fail closed (D6).
     *
     * A boot diagnostic — an app can assert RBAC is wired before serving traffic — and the guarantee that
     * [register] sets **both** handles, not just one (a half-wire would leave Mongo's own path or the
     * cross-engine path silently unresolved).
     */
    val isRegistered: Boolean
        get() = Coll.roleInUserColl != null && PermissionRegistry.rolePermissionProvider != null
}
