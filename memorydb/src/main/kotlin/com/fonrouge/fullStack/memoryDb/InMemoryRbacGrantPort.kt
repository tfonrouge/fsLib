package com.fonrouge.fullStack.memoryDb

import com.fonrouge.base.model.AppRolePolicy
import com.fonrouge.base.model.IAppRole
import com.fonrouge.base.model.RoleGrant
import com.fonrouge.base.types.OId
import com.fonrouge.fullStack.repository.IRbacGrantPort
import com.fonrouge.fullStack.repository.RbacResolver
import java.util.concurrent.ConcurrentHashMap

/**
 * In-heap, seedable [IRbacGrantPort] for the in-memory engine — the database-free analogue of the
 * MongoDB grant port.
 *
 * Unlike a trivial fake, this port performs the **real** user → groups → group-grants join: it stores
 * group memberships separately from the per-group grant rows and, on [fetchGroupGrants], walks the
 * user's memberships and flattens the matching group grants (the in-heap equivalent of Mongo's
 * `$lookup` from the membership edge into the role-in-group collection). Direct grants take precedence
 * and are looked up by the composite `(userId, appRoleId)` key.
 *
 * The store is mutated only through the public seed methods; it is intended to be populated once per
 * test or per deployment scope and then read by [RbacResolver]. Backing maps are [ConcurrentHashMap]s
 * to match [InMemoryRepository]'s storage style, so concurrent reads after seeding are safe.
 *
 * @param UID The user-identifier type (e.g. `OId<TUser>`), matching the resolver's user id type.
 */
class InMemoryRbacGrantPort<UID : Any> : IRbacGrantPort<UID> {

    /** Internal group identifier type — opaque to the port; any value the caller seeds with. */
    private val rootUsers: MutableSet<UID> = ConcurrentHashMap.newKeySet()

    /** Direct user grants keyed by the composite `(userId, appRoleId)`; at most one per key. */
    private val directGrants: ConcurrentHashMap<GrantKey<UID>, RoleGrant> = ConcurrentHashMap()

    /** User → set of group ids the user is a member of (the membership edge of the join). */
    private val memberships: ConcurrentHashMap<UID, MutableSet<Any>> = ConcurrentHashMap()

    /**
     * Group grants keyed by the composite `(groupId, appRoleId)`; a group may carry **multiple**
     * matching rows for the same role, so the value is a list (all are returned by the join).
     */
    private val groupGrants: ConcurrentHashMap<GroupGrantKey, MutableList<RoleGrant>> = ConcurrentHashMap()

    /**
     * App-role policy snapshots keyed by app-role id; consulted by [fetchAppRolePolicy] so the membership
     * API can resolve a `(userId, appRoleId)` key without an engine-specific app-role document. A role
     * absent from this map is "unknown" and [fetchAppRolePolicy] returns `null` (no provisioning).
     */
    private val appRolePolicies: ConcurrentHashMap<OId<out IAppRole<*>>, AppRolePolicy> = ConcurrentHashMap()

    /**
     * Composite key for a direct user grant.
     *
     * @property userId The user the grant belongs to.
     * @property appRoleId The app role the grant is for.
     */
    private data class GrantKey<UID : Any>(val userId: UID, val appRoleId: OId<out IAppRole<*>>)

    /**
     * Composite key for a group grant.
     *
     * @property groupId The group the grant belongs to.
     * @property appRoleId The app role the grant is for.
     */
    private data class GroupGrantKey(val groupId: Any, val appRoleId: OId<out IAppRole<*>>)

    // ---- seed API ----

    /**
     * Marks [userId] as a root user, so [isRootUser] reports `true` and resolution short-circuits to
     * an unconditional allow for that user.
     *
     * @param userId The user to flag as root.
     */
    fun addRootUser(userId: UID) {
        rootUsers.add(userId)
    }

    /**
     * Stores (or replaces) the user's direct grant for an app role. A present direct grant takes
     * precedence over all group grants (D1).
     *
     * @param userId The user the grant belongs to.
     * @param appRoleId The app role the grant is for.
     * @param grant The direct [RoleGrant] to store.
     */
    fun putDirectGrant(userId: UID, appRoleId: OId<out IAppRole<*>>, grant: RoleGrant) {
        directGrants[GrantKey(userId, appRoleId)] = grant
    }

    /**
     * Records that [userId] is a member of [groupId] — the membership edge the group join walks.
     *
     * @param userId The member user.
     * @param groupId The group the user belongs to (any opaque id value).
     */
    fun addMembership(userId: UID, groupId: Any) {
        memberships.computeIfAbsent(userId) { ConcurrentHashMap.newKeySet() }.add(groupId)
    }

    /**
     * Appends a grant row to a group for an app role. A group may have multiple matching rows for the
     * same role; all are returned by [fetchGroupGrants] for any member.
     *
     * @param groupId The group the grant belongs to.
     * @param appRoleId The app role the grant is for.
     * @param grant The group [RoleGrant] to append.
     */
    fun addGroupGrant(groupId: Any, appRoleId: OId<out IAppRole<*>>, grant: RoleGrant) {
        groupGrants
            .computeIfAbsent(GroupGrantKey(groupId, appRoleId)) { mutableListOf() }
            .add(grant)
    }

    /**
     * Stores (or replaces) the [AppRolePolicy] snapshot for an app role, keyed by [AppRolePolicy.id], so
     * [fetchAppRolePolicy] (and thus the membership API) can resolve the role by id. Roles never seeded
     * are "unknown" and resolve to `null` (no provisioning).
     *
     * @param policy The app-role policy to register; its [AppRolePolicy.id] is the lookup key.
     */
    fun putAppRolePolicy(policy: AppRolePolicy) {
        appRolePolicies[policy.id] = policy
    }

    // ---- IRbacGrantPort ----

    /**
     * Returns the seeded [AppRolePolicy] for the app role, or `null` when none was seeded (unknown role,
     * no provisioning).
     *
     * @param appRoleId The app role to resolve into a policy snapshot.
     * @return The seeded [AppRolePolicy], or `null`.
     */
    override suspend fun fetchAppRolePolicy(appRoleId: OId<out IAppRole<*>>): AppRolePolicy? =
        appRolePolicies[appRoleId]

    /**
     * Whether [userId] was seeded as a root user.
     *
     * @param userId The user to check.
     * @return `true` if the user is root.
     */
    override suspend fun isRootUser(userId: UID): Boolean = userId in rootUsers

    /**
     * Returns the user's stored direct grant for the app role, or `null` when none was seeded.
     *
     * @param userId The user whose direct grant is sought.
     * @param appRoleId The app role being evaluated.
     * @return The direct [RoleGrant], or `null`.
     */
    override suspend fun fetchDirectGrant(userId: UID, appRoleId: OId<out IAppRole<*>>): RoleGrant? =
        directGrants[GrantKey(userId, appRoleId)]

    /**
     * Performs the user → groups → group-grants join: for each group the user is a member of, collects
     * that group's grants matching the app role, flattened across all groups.
     *
     * This is the in-heap analogue of Mongo's `$lookup` from the membership edge into the role-in-group
     * collection: a user in no matching group yields an empty list (the resolver then falls through to
     * the role default), and a user in multiple groups accumulates every group's matching rows.
     *
     * @param userId The user whose group memberships are consulted.
     * @param appRoleId The app role being evaluated.
     * @return The flattened list of group [RoleGrant]s (possibly empty).
     */
    override suspend fun fetchGroupGrants(userId: UID, appRoleId: OId<out IAppRole<*>>): List<RoleGrant> {
        val userGroups = memberships[userId] ?: return emptyList()
        return userGroups.flatMap { groupId ->
            groupGrants[GroupGrantKey(groupId, appRoleId)].orEmpty()
        }
    }

    /**
     * Whether a direct grant exists for `(userId, appRoleId)` without decoding it.
     *
     * @param userId The user whose direct grant presence is checked.
     * @param appRoleId The app role being evaluated.
     * @return `true` if a direct row exists.
     */
    override suspend fun existsDirectGrant(userId: UID, appRoleId: OId<out IAppRole<*>>): Boolean =
        directGrants.containsKey(GrantKey(userId, appRoleId))

    /**
     * Whether any group grant exists for `(userId, appRoleId)` via the membership join, without
     * decoding the grants.
     *
     * @param userId The user whose group-grant presence is checked.
     * @param appRoleId The app role being evaluated.
     * @return `true` if at least one matching group grant exists.
     */
    override suspend fun existsGroupGrant(userId: UID, appRoleId: OId<out IAppRole<*>>): Boolean =
        fetchGroupGrants(userId, appRoleId).isNotEmpty()
}
