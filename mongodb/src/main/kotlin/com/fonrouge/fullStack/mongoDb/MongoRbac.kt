package com.fonrouge.fullStack.mongoDb

import com.fonrouge.fullStack.repository.PermissionRegistry

/**
 * Explicit, boot-time registrar for the MongoDB RBAC permission provider (RBAC blueprint D10 / R10).
 *
 * Replaces the former **construction side-effect** in `Coll.init`, where building any concrete
 * [IRoleInUserColl] silently wired the process RBAC state (P3.2a). Registration is now a deliberate,
 * app-owned boot step — the application calls [register] once, after constructing its `IRoleInUserColl`,
 * the same way RBAC roles are provisioned explicitly via `IAppRoleColl.ensureRoles(...)` (D4) rather than
 * as a side effect of a read.
 *
 * Since P3.2b the registration drives a **single** handle: [PermissionRegistry.rolePermissionProvider],
 * the backend-agnostic provider that **every** engine — Mongo (its own `getCrudPermission` now routes
 * through it too), SQL, and in-memory — consults. The former Mongo-only `Coll.roleInUserColl` companion is
 * gone; the split-brain it caused (Mongo enforcing through a handle the conformance harness couldn't drive)
 * is resolved.
 *
 * Fail-closed is preserved (D6): with no [register] call the provider stays `null`, and each repository's
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
        PermissionRegistry.rolePermissionProvider = MongoRolePermissionProvider(roleInUserColl)
    }

    /**
     * Clears the registered RBAC provider, so an enforcing repository fails closed again (D6). Primarily for
     * test isolation and controlled teardown; production apps register once at boot and never unregister.
     */
    fun unregister() {
        PermissionRegistry.rolePermissionProvider = null
    }

    /**
     * Whether an RBAC provider is currently registered. `true` only after [register] (and before
     * [unregister]); `false` when unregistered, where enforcing repositories fail closed (D6).
     *
     * A boot diagnostic — an app can assert RBAC is wired before serving traffic.
     */
    val isRegistered: Boolean
        get() = PermissionRegistry.rolePermissionProvider != null
}
