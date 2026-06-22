package com.fonrouge.fullStack.repository

import com.fonrouge.base.model.IAppRole
import com.fonrouge.base.model.IAppRole.BaseRolePermission
import com.fonrouge.base.types.OId

/**
 * Backend-agnostic, group-aware membership API keyed by `(userId, appRoleId)` — the consumer-facing
 * replacement for a raw `RoleInUser` count.
 *
 * The original problem this closes: a consumer asking "is this user a member of this app role?" had no
 * fsLib-blessed way to do so and fell back to a direct `countDocuments(RoleInUser by userId+appRoleId)`.
 * That count is **group-blind** — it only sees the user's *direct* grant row and silently returns `false`
 * for a user who holds the role solely through a group. Both operations here go through the
 * [IRbacGrantPort], so they see direct **and** group edges, fixing the bypass.
 *
 * The two operations answer **deliberately different questions** and must never be blended into a single
 * verdict:
 *
 * - [hasSingleActionGrant] — **pure existence.** Does a grant *edge* exist at all (direct OR group),
 *   ignoring permission votes, role defaults and the group up-vote bias? This is a membership probe, not
 *   an authorization decision (D8: existence ≠ authz). A user with an explicit `Deny` edge still
 *   "has a grant" by this measure.
 * - [isAllowedSingleAction] — **effective authorization.** Run the full D1/T5 precedence resolution
 *   ([RbacResolver]) restricted to the SingleAction shape and report whether the verdict is
 *   [BaseRolePermission.Allow]. This **is the resolver** — direct-grant precedence, the uniform group
 *   tie-break, role defaults — **not** a deny-override union over the edges. In particular a direct
 *   `Allow` wins over a group `Deny` (D1 precedence), which a union would get wrong.
 *
 * Both operations are **non-materializing of grant documents** (D9/R13): existence uses the port's cheap
 * `exists*` probes (`countDocuments` / `limit(1)`), and authz uses the resolver's **server-side-projected**
 * typed grant fetches (`{permission, crudTaskSet}`) — neither ever decodes a full `RoleInUser` /
 * `RoleInGroup` grant document just to answer a boolean, so an undecodable grant-row field cannot throw at
 * the check. Authz does read the app role's policy by id ([IRbacGrantPort.fetchAppRolePolicy]) for its
 * defaults/up-vote — that is not a *grant* document — but performs **no app-role provisioning** (D4): an
 * unknown role denies.
 */
object RbacMembership {

    /**
     * Whether a SingleAction grant **edge** exists for `(userId, appRoleId)` — a direct row OR any group
     * grant — **ignoring** the permission vote, role default and group up-vote.
     *
     * This is pure existence (D8: existence ≠ authz): it answers "does the user touch this role at all?",
     * not "is the user allowed?". An explicit `Deny` edge still counts as present. Use
     * [isAllowedSingleAction] for the effective authorization decision.
     *
     * Unlike a raw `RoleInUser` count, this is **group-aware**: a user with no direct row but in a group
     * that grants the role reports `true`. It needs no [com.fonrouge.base.model.AppRolePolicy] — only the
     * port's existence probes.
     *
     * @param UID The user-identifier type.
     * @param port The grant-fetch port supplying the existence probes.
     * @param userId The user whose membership is checked.
     * @param appRoleId The app role being probed.
     * @return `true` iff a direct OR group grant edge exists.
     */
    suspend fun <UID : Any> hasSingleActionGrant(
        port: IRbacGrantPort<UID>,
        userId: UID,
        appRoleId: OId<out IAppRole<*>>,
    ): Boolean =
        port.existsDirectGrant(userId = userId, appRoleId = appRoleId) ||
                port.existsGroupGrant(userId = userId, appRoleId = appRoleId)

    /**
     * Whether the user is **effectively authorized** for the SingleAction role `(userId, appRoleId)` — the
     * D1/T5 precedence resolution restricted to the SingleAction shape, reporting `true` only when the
     * resolved verdict is [BaseRolePermission.Allow].
     *
     * This **is** the resolver, not a deny-override union over the edges (D8): it applies direct-grant
     * precedence (a direct `Allow`/`Deny`/`Default` decides and groups are not consulted), then the
     * uniform group tie-break, then the role default — exactly as [RbacResolver.resolve] does with
     * `crudTask = null`. Consequently a direct `Allow` wins over a group `Deny`, which a naive
     * deny-override union would mis-resolve to `false`.
     *
     * The app role is resolved through the port ([IRbacGrantPort.fetchAppRolePolicy]); a **missing** role
     * denies (returns `false`) and triggers no provisioning (D4: unknown role denies).
     *
     * @param UID The user-identifier type.
     * @param port The grant-fetch port supplying the app-role policy and grants.
     * @param userId The user whose authorization is resolved.
     * @param appRoleId The app role being evaluated.
     * @return `true` iff the role exists and the precedence resolution yields [BaseRolePermission.Allow].
     */
    suspend fun <UID : Any> isAllowedSingleAction(
        port: IRbacGrantPort<UID>,
        userId: UID,
        appRoleId: OId<out IAppRole<*>>,
    ): Boolean {
        val policy = port.fetchAppRolePolicy(appRoleId = appRoleId) ?: return false
        return RbacResolver.resolve(
            userId = userId,
            policy = policy,
            crudTask = null,
            port = port,
        ) == BaseRolePermission.Allow
    }
}
