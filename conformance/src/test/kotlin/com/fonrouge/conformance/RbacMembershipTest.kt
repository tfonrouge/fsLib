package com.fonrouge.conformance

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
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure (no-DB) unit tests for the group-aware membership API ([RbacMembership]) over the **real**
 * in-memory grant port ([InMemoryRbacGrantPort]). These pin the headline P4 fix: the group-only bypass,
 * where a user who holds a role solely through a group is now seen by both membership operations — the
 * raw `RoleInUser` count would have been group-blind and returned `false`.
 *
 * They also pin the deliberate separation of the two operations (D8: existence ≠ authz):
 * [RbacMembership.hasSingleActionGrant] is pure edge existence (ignores votes/defaults), while
 * [RbacMembership.isAllowedSingleAction] is the D1 precedence resolution (NOT a deny-override union, so a
 * direct Allow wins over a group Deny). Runs locally with no database.
 */
class RbacMembershipTest {

    // ---- (1) group-only user → both true (THE bypass fix) ----

    /**
     * A user with **no direct row** but a member of a group that grants the role: the group-blind raw
     * `RoleInUser` count would return `false`, but the membership API sees the group edge — existence is
     * `true` and the effective authz (group Allow) is `true`.
     */
    @Test
    fun groupOnlyUserIsSeenByBothOperations() = runTest {
        val policy = singleActionPolicy(default = BaseRolePermission.Deny)
        val port = InMemoryRbacGrantPort<OId<Any>>().apply {
            putAppRolePolicy(policy)
            addMembership(USER, GROUP_A)
            addGroupGrant(GROUP_A, policy.id, RoleGrant(PermissionType.Allow, crudTaskSet = null))
            // Deliberately NO putDirectGrant — the user holds the role ONLY through the group.
        }

        assertTrue(
            RbacMembership.hasSingleActionGrant(port, USER, policy.id),
            "group-only membership: a group grant edge exists (the raw count would miss it)",
        )
        assertTrue(
            RbacMembership.isAllowedSingleAction(port, USER, policy.id),
            "group-only membership: the group Allow grants effective authz",
        )
    }

    // ---- (2) direct-only Allow → both true ----

    /** A user with a direct Allow row and no group: existence is true and authz is true. */
    @Test
    fun directOnlyAllowIsSeenByBothOperations() = runTest {
        val policy = singleActionPolicy(default = BaseRolePermission.Deny)
        val port = InMemoryRbacGrantPort<OId<Any>>().apply {
            putAppRolePolicy(policy)
            putDirectGrant(USER, policy.id, RoleGrant(PermissionType.Allow, crudTaskSet = null))
        }

        assertTrue(RbacMembership.hasSingleActionGrant(port, USER, policy.id), "a direct grant edge exists")
        assertTrue(RbacMembership.isAllowedSingleAction(port, USER, policy.id), "a direct Allow grants authz")
    }

    // ---- (3) no grants at all → both false ----

    /** A user with neither a direct row nor any group grant: existence is false and authz is false. */
    @Test
    fun noGrantsAtAllIsSeenByNeitherOperation() = runTest {
        val policy = singleActionPolicy(default = BaseRolePermission.Deny)
        val port = InMemoryRbacGrantPort<OId<Any>>().apply {
            putAppRolePolicy(policy)
            // No direct grant, no membership, no group grant.
        }

        assertFalse(RbacMembership.hasSingleActionGrant(port, USER, policy.id), "no edge exists")
        assertFalse(
            RbacMembership.isAllowedSingleAction(port, USER, policy.id),
            "no grant → resolution falls to the Deny default → not allowed",
        )
    }

    // ---- (4) unknown role (no policy) → isAllowedSingleAction false ----

    /**
     * An app role with no seeded policy is "unknown": [RbacMembership.isAllowedSingleAction] denies
     * (returns `false`) without provisioning (D4). The role id has no grants either, so existence is
     * `false` as well — existence never needs the policy.
     */
    @Test
    fun unknownRoleDeniesEffectiveAuthz() = runTest {
        val unknownRoleId = OId<IAppRole<*>>()
        val port = InMemoryRbacGrantPort<OId<Any>>() // nothing seeded

        assertFalse(
            RbacMembership.isAllowedSingleAction(port, USER, unknownRoleId),
            "unknown role (no policy) denies effective authz (D4: unknown role denies)",
        )
        assertFalse(
            RbacMembership.hasSingleActionGrant(port, USER, unknownRoleId),
            "no grant edge exists for the unknown role",
        )
    }

    // ---- (5) Deny edge: existence true, authz false (the two ops differ) ----

    /**
     * A group Deny edge exists, so [RbacMembership.hasSingleActionGrant] is `true` (an edge is present),
     * yet effective authz is `false` (the explicit Deny resolves to a deny). This proves the two
     * operations answer different questions (D8: existence ≠ authz).
     */
    @Test
    fun denyEdgeHasGrantButIsNotAllowed() = runTest {
        val policy = singleActionPolicy(default = BaseRolePermission.Allow) // Allow default, to prove the Deny edge — not the default — drives authz
        val port = InMemoryRbacGrantPort<OId<Any>>().apply {
            putAppRolePolicy(policy)
            addMembership(USER, GROUP_A)
            addGroupGrant(GROUP_A, policy.id, RoleGrant(PermissionType.Deny, crudTaskSet = null))
        }

        assertTrue(
            RbacMembership.hasSingleActionGrant(port, USER, policy.id),
            "the Deny group edge EXISTS, so existence is true",
        )
        assertFalse(
            RbacMembership.isAllowedSingleAction(port, USER, policy.id),
            "the explicit group Deny resolves to a deny, so effective authz is false — existence ≠ authz",
        )
    }

    // ---- (6) direct Allow + group Deny → allowed (D1 precedence, NOT a deny-override union) ----

    /**
     * A direct Allow alongside a group Deny: under D1 precedence the direct Allow decides and groups are
     * not consulted, so [RbacMembership.isAllowedSingleAction] is `true`. A naive deny-override union over
     * the edges would have mis-resolved this to `false` — this proves isAllowedSingleAction IS the
     * precedence resolver, not a union.
     */
    @Test
    fun directAllowWithGroupDenyIsAllowedByPrecedenceNotUnion() = runTest {
        val policy = singleActionPolicy(default = BaseRolePermission.Deny)
        val port = InMemoryRbacGrantPort<OId<Any>>().apply {
            putAppRolePolicy(policy)
            putDirectGrant(USER, policy.id, RoleGrant(PermissionType.Allow, crudTaskSet = null))
            addMembership(USER, GROUP_A)
            addGroupGrant(GROUP_A, policy.id, RoleGrant(PermissionType.Deny, crudTaskSet = null))
        }

        assertTrue(
            RbacMembership.hasSingleActionGrant(port, USER, policy.id),
            "both a direct and a group edge exist",
        )
        assertTrue(
            RbacMembership.isAllowedSingleAction(port, USER, policy.id),
            "D1 precedence: the direct Allow wins over the group Deny — NOT a deny-override union",
        )
    }

    // ---- helpers ----

    private companion object {
        /** A stable user id for the membership calls. */
        val USER: OId<Any> = OId()

        /** An opaque group id used as the in-heap membership/grant key. */
        const val GROUP_A: String = "group-A"

        /** A SingleAction policy with an explicit default permission. */
        fun singleActionPolicy(default: BaseRolePermission) = AppRolePolicy(
            id = OId<IAppRole<*>>(),
            roleType = RoleType.SingleAction,
            defaultPermission = default,
            defaultCrudTaskSet = null,
            upVoteInGroup = BaseRolePermission.Allow,
        )
    }
}
