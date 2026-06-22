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
import com.fonrouge.fullStack.repository.RbacResolver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cross-engine pin for the RBAC resolution algebra over the **real** in-memory grant port
 * ([InMemoryRbacGrantPort]). Where [RbacResolverTest] drives [RbacResolver.resolve] over a flat
 * `FakeGrantPort` (fixed direct/group lists), this suite seeds a real port per scenario and exercises
 * its actual fetch/join logic — the user → groups → group-grants walk — so the verdicts prove the
 * engine port agrees with the pure algebra (not just the resolver in isolation).
 *
 * The verdicts mirror [RbacResolverTest] one-for-one (D1 direct precedence, the uniform D2 group
 * tie-break, D3 allow-list defaults, the root short-circuit, empty-grant fall-through), and add a
 * dedicated group-join test that proves membership actually gates the group fetch. Runs locally with
 * no database.
 */
class InMemoryRbacGrantPortTest {

    // ---- root short-circuit ----

    /** A root user resolves to Allow ahead of any direct/group/default resolution. */
    @Test
    fun rootUserResolvesToAllow() = runTest {
        val policy = crudPolicy(default = BaseRolePermission.Deny, defaultTasks = setOf(CrudTask.Read))
        val port = InMemoryRbacGrantPort<OId<Any>>().apply { addRootUser(USER) }

        assertEquals(
            BaseRolePermission.Allow,
            RbacResolver.resolve(USER, policy, CrudTask.Delete, port),
            "a root user is granted unconditionally",
        )
    }

    // ---- (1) direct grant shadows group (D1), for each direct permission ----

    /** A direct Allow shadows a group Deny → Allow (SingleAction). */
    @Test
    fun directAllowShadowsGroup() = runTest {
        val policy = singleActionPolicy(default = BaseRolePermission.Deny)
        val port = InMemoryRbacGrantPort<OId<Any>>().apply {
            putDirectGrant(USER, policy.id, RoleGrant(PermissionType.Allow, crudTaskSet = null))
            addMembership(USER, GROUP_A)
            addGroupGrant(GROUP_A, policy.id, RoleGrant(PermissionType.Deny, crudTaskSet = null))
        }
        assertEquals(BaseRolePermission.Allow, RbacResolver.resolve(USER, policy, null, port))
    }

    /** A direct Deny shadows a group Allow → Deny (SingleAction). */
    @Test
    fun directDenyShadowsGroup() = runTest {
        val policy = singleActionPolicy(default = BaseRolePermission.Allow)
        val port = InMemoryRbacGrantPort<OId<Any>>().apply {
            putDirectGrant(USER, policy.id, RoleGrant(PermissionType.Deny, crudTaskSet = null))
            addMembership(USER, GROUP_A)
            addGroupGrant(GROUP_A, policy.id, RoleGrant(PermissionType.Allow, crudTaskSet = null))
        }
        assertEquals(BaseRolePermission.Deny, RbacResolver.resolve(USER, policy, null, port))
    }

    /** A direct Default row resolves to the role's defaultPermission and shadows a group Allow. */
    @Test
    fun directDefaultShadowsGroupAndUsesRoleDefault() = runTest {
        val policy = singleActionPolicy(default = BaseRolePermission.Deny)
        val port = InMemoryRbacGrantPort<OId<Any>>().apply {
            putDirectGrant(USER, policy.id, RoleGrant(PermissionType.Default, crudTaskSet = null))
            addMembership(USER, GROUP_A)
            addGroupGrant(GROUP_A, policy.id, RoleGrant(PermissionType.Allow, crudTaskSet = null))
        }
        assertEquals(
            BaseRolePermission.Deny,
            RbacResolver.resolve(USER, policy, null, port),
            "a direct Default row resolves to defaultPermission=Deny, not the group Allow",
        )
    }

    // ---- (2) direct CrudTask grant covering vs not-covering the task ----

    /** A direct CrudTask grant whose crudTaskSet covers the task uses its own verdict (Allow), shadowing groups. */
    @Test
    fun directCrudGrantCoveringTaskShadowsGroup() = runTest {
        val policy = crudPolicy(default = BaseRolePermission.Deny, defaultTasks = setOf(CrudTask.Read))
        val port = InMemoryRbacGrantPort<OId<Any>>().apply {
            putDirectGrant(USER, policy.id, RoleGrant(PermissionType.Allow, crudTaskSet = setOf(CrudTask.Read)))
            addMembership(USER, GROUP_A)
            addGroupGrant(GROUP_A, policy.id, RoleGrant(PermissionType.Deny, crudTaskSet = setOf(CrudTask.Read)))
        }
        assertEquals(BaseRolePermission.Allow, RbacResolver.resolve(USER, policy, CrudTask.Read, port))
    }

    /** A direct CrudTask grant whose crudTaskSet does NOT cover the task denies (not-in-set ⇒ Deny). */
    @Test
    fun directCrudGrantNotCoveringTaskDenies() = runTest {
        val policy = crudPolicy(default = BaseRolePermission.Allow, defaultTasks = setOf(CrudTask.Update))
        val port = InMemoryRbacGrantPort<OId<Any>>().apply {
            putDirectGrant(USER, policy.id, RoleGrant(PermissionType.Allow, crudTaskSet = setOf(CrudTask.Read)))
            addMembership(USER, GROUP_A)
            addGroupGrant(GROUP_A, policy.id, RoleGrant(PermissionType.Allow, crudTaskSet = setOf(CrudTask.Update)))
        }
        assertEquals(
            BaseRolePermission.Deny,
            RbacResolver.resolve(USER, policy, CrudTask.Update, port),
            "the direct row is present but its crudTaskSet misses the task → Deny (groups NOT consulted)",
        )
    }

    // ---- (3) single-group Deny → Deny ----

    /** A single group Deny resolves to Deny under the uniform allow-override branch. */
    @Test
    fun singleGroupDenyResolvesToDeny() = runTest {
        val policy = crudPolicy(
            default = BaseRolePermission.Allow,
            defaultTasks = setOf(CrudTask.Read),
            upVote = BaseRolePermission.Allow,
        )
        val port = InMemoryRbacGrantPort<OId<Any>>().apply {
            addMembership(USER, GROUP_A)
            addGroupGrant(GROUP_A, policy.id, RoleGrant(PermissionType.Deny, crudTaskSet = setOf(CrudTask.Read)))
        }
        assertEquals(BaseRolePermission.Deny, RbacResolver.resolve(USER, policy, CrudTask.Read, port))
    }

    // ---- (4) two-group Deny under upVote=Allow → Deny (D2 fix, R4) ----

    /** Two distinct group Denies under an Allow-biased role resolve to Deny — explicit grants never discarded. */
    @Test
    fun twoGroupDeniesUnderAllowOverrideResolveToDeny() = runTest {
        val policy = crudPolicy(
            default = BaseRolePermission.Allow,
            defaultTasks = setOf(CrudTask.Read),
            upVote = BaseRolePermission.Allow,
        )
        val port = InMemoryRbacGrantPort<OId<Any>>().apply {
            addMembership(USER, GROUP_A)
            addMembership(USER, GROUP_B)
            addGroupGrant(GROUP_A, policy.id, RoleGrant(PermissionType.Deny, crudTaskSet = setOf(CrudTask.Read)))
            addGroupGrant(GROUP_B, policy.id, RoleGrant(PermissionType.Deny, crudTaskSet = setOf(CrudTask.Read)))
        }
        assertEquals(
            BaseRolePermission.Deny,
            RbacResolver.resolve(USER, policy, CrudTask.Read, port),
            "D2: two group Denies → Deny, never the role default (R4)",
        )
    }

    // ---- (5) mixed Allow+Deny across two groups ----

    /** A mixed Allow+Deny group set (across two groups) under upVote=Allow resolves to Allow (allow-override opt-in). */
    @Test
    fun mixedGroupGrantsAllowWinUnderAllowOverride() = runTest {
        val policy = crudPolicy(
            default = BaseRolePermission.Deny,
            defaultTasks = setOf(CrudTask.Read),
            upVote = BaseRolePermission.Allow,
        )
        val port = InMemoryRbacGrantPort<OId<Any>>().apply {
            addMembership(USER, GROUP_A)
            addMembership(USER, GROUP_B)
            addGroupGrant(GROUP_A, policy.id, RoleGrant(PermissionType.Allow, crudTaskSet = setOf(CrudTask.Read)))
            addGroupGrant(GROUP_B, policy.id, RoleGrant(PermissionType.Deny, crudTaskSet = setOf(CrudTask.Read)))
        }
        assertEquals(BaseRolePermission.Allow, RbacResolver.resolve(USER, policy, CrudTask.Read, port))
    }

    /** A mixed Allow+Deny group set (across two groups) under upVote=Deny resolves to Deny (deny-override default). */
    @Test
    fun mixedGroupGrantsDenyWinUnderDenyOverride() = runTest {
        val policy = crudPolicy(
            default = BaseRolePermission.Allow,
            defaultTasks = setOf(CrudTask.Read),
            upVote = BaseRolePermission.Deny,
        )
        val port = InMemoryRbacGrantPort<OId<Any>>().apply {
            addMembership(USER, GROUP_A)
            addMembership(USER, GROUP_B)
            addGroupGrant(GROUP_A, policy.id, RoleGrant(PermissionType.Allow, crudTaskSet = setOf(CrudTask.Read)))
            addGroupGrant(GROUP_B, policy.id, RoleGrant(PermissionType.Deny, crudTaskSet = setOf(CrudTask.Read)))
        }
        assertEquals(BaseRolePermission.Deny, RbacResolver.resolve(USER, policy, CrudTask.Read, port))
    }

    // ---- (6) crudTaskSet miss under Deny-default → Deny (D3 no inversion) ----

    /** With no grants, a Deny-default role: a task in the default set denies; a task NOT in the set also denies (no inversion). */
    @Test
    fun crudTaskSetMissUnderDenyDefaultDenies() = runTest {
        val policy = crudPolicy(default = BaseRolePermission.Deny, defaultTasks = setOf(CrudTask.Read))
        val port = InMemoryRbacGrantPort<OId<Any>>() // no direct, no group

        assertEquals(
            BaseRolePermission.Deny,
            RbacResolver.resolve(USER, policy, CrudTask.Read, port),
            "task in defaultCrudTaskSet under a Deny-default → Deny",
        )
        assertEquals(
            BaseRolePermission.Deny,
            RbacResolver.resolve(USER, policy, CrudTask.Update, port),
            "D3: a crudTaskSet miss is uncovered → Deny, no inversion (R3)",
        )
    }

    // ---- (7) root → Allow (covered above) ; (8) no grants → role default ----

    /** With no direct row and no group grants, a SingleAction role falls through to its defaultPermission. */
    @Test
    fun noGrantsFallsThroughToSingleActionDefault() = runTest {
        assertEquals(
            BaseRolePermission.Allow,
            RbacResolver.resolve(USER, singleActionPolicy(default = BaseRolePermission.Allow), null, InMemoryRbacGrantPort()),
        )
        assertEquals(
            BaseRolePermission.Deny,
            RbacResolver.resolve(USER, singleActionPolicy(default = BaseRolePermission.Deny), null, InMemoryRbacGrantPort()),
        )
    }

    // ---- dedicated group-join test: membership actually gates the group fetch ----

    /**
     * Proves the membership join (not a flat list): group G holds a grant for role R; member user U
     * sees it via [InMemoryRbacGrantPort.fetchGroupGrants] and the resolver applies it, while a
     * non-member user gets an empty group fetch and falls through to the role default. Same store,
     * same role — only membership differs, so the verdict difference can only come from the join.
     */
    @Test
    fun membershipGatesGroupGrantFetch() = runTest {
        // Deny-default policy so the group grant (Allow) and the default (Deny) are distinguishable.
        val policy = crudPolicy(
            default = BaseRolePermission.Deny,
            defaultTasks = setOf(CrudTask.Read),
            upVote = BaseRolePermission.Allow,
        )
        val port = InMemoryRbacGrantPort<OId<Any>>().apply {
            // Group G has an Allow grant for role R. MEMBER joins G; OUTSIDER does not.
            addGroupGrant(GROUP_A, policy.id, RoleGrant(PermissionType.Allow, crudTaskSet = setOf(CrudTask.Read)))
            addMembership(MEMBER, GROUP_A)
        }

        // The join surfaces G's grant for the member, and the resolver grants Allow.
        val memberGrants = port.fetchGroupGrants(MEMBER, policy.id)
        assertEquals(1, memberGrants.size, "the member's group join returns G's single matching grant")
        assertEquals(PermissionType.Allow, memberGrants.single().permission)
        assertEquals(
            BaseRolePermission.Allow,
            RbacResolver.resolve(MEMBER, policy, CrudTask.Read, port),
            "a member of G inherits G's Allow grant",
        )

        // The non-member's join is empty — membership gates the fetch — so resolution falls to the default.
        assertTrue(port.fetchGroupGrants(OUTSIDER, policy.id).isEmpty(), "a non-member's group join is empty")
        assertEquals(
            BaseRolePermission.Deny,
            RbacResolver.resolve(OUTSIDER, policy, CrudTask.Read, port),
            "a non-member sees no group grant and falls through to the Deny default",
        )
    }

    // ---- helpers ----

    private companion object {
        /** A stable user id for the resolver calls. */
        val USER: OId<Any> = OId()

        /** Distinct users for the membership-join test (member vs outsider). */
        val MEMBER: OId<Any> = OId()
        val OUTSIDER: OId<Any> = OId()

        /** Opaque group ids used as the in-heap membership/grant keys. */
        const val GROUP_A: String = "group-A"
        const val GROUP_B: String = "group-B"

        /** A SingleAction policy with an explicit default permission. */
        fun singleActionPolicy(default: BaseRolePermission) = AppRolePolicy(
            id = OId<IAppRole<*>>(),
            roleType = RoleType.SingleAction,
            defaultPermission = default,
            defaultCrudTaskSet = null,
            upVoteInGroup = BaseRolePermission.Allow,
        )

        /** A CrudTask policy with explicit default permission / covered task set / group-vote bias. */
        fun crudPolicy(
            default: BaseRolePermission,
            defaultTasks: Set<CrudTask>?,
            upVote: BaseRolePermission = BaseRolePermission.Allow,
        ) = AppRolePolicy(
            id = OId<IAppRole<*>>(),
            roleType = RoleType.CrudTask,
            defaultPermission = default,
            defaultCrudTaskSet = defaultTasks,
            upVoteInGroup = upVote,
        )
    }
}
