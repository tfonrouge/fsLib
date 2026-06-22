package com.fonrouge.conformance

import com.fonrouge.base.api.CrudTask
import com.fonrouge.base.model.AppRolePolicy
import com.fonrouge.base.model.IAppRole
import com.fonrouge.base.model.IAppRole.BaseRolePermission
import com.fonrouge.base.model.IAppRole.RoleType
import com.fonrouge.base.model.PermissionType
import com.fonrouge.base.model.RoleGrant
import com.fonrouge.base.types.OId
import com.fonrouge.fullStack.repository.IRbacGrantPort
import com.fonrouge.fullStack.repository.RbacResolver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure (no-DB) unit tests for the backend-agnostic RBAC resolution algebra ([RbacResolver]), the
 * local faithfulness net for the P3.1a extraction. Each test drives [RbacResolver.resolve] over a
 * [FakeGrantPort] (an in-heap [IRbacGrantPort]) and asserts the returned [BaseRolePermission], pinning
 * the preserved semantics: D1 direct-grant precedence, the uniform D2 group tie-break, D3 allow-list
 * defaults (no inversion), the root short-circuit, and the empty-grant fall-through to the role default.
 */
class RbacResolverTest {

    // ---- (f) root short-circuit ----

    /** A root user resolves to Allow ahead of any direct/group/default resolution. */
    @Test
    fun rootUserResolvesToAllow() = runTest {
        val policy = crudPolicy(default = BaseRolePermission.Deny, defaultTasks = setOf(CrudTask.Read))
        val port = FakeGrantPort(root = true)

        assertEquals(
            BaseRolePermission.Allow,
            RbacResolver.resolve(USER, policy, CrudTask.Delete, port),
            "a root user is granted unconditionally",
        )
    }

    // ---- (a) direct grant shadows group (D1), for each direct permission ----

    /** A direct Allow shadows a group Deny → Allow (SingleAction). */
    @Test
    fun directAllowShadowsGroup() = runTest {
        val policy = singleActionPolicy(default = BaseRolePermission.Deny)
        val port = FakeGrantPort(
            directGrant = RoleGrant(PermissionType.Allow, crudTaskSet = null),
            groupGrants = listOf(RoleGrant(PermissionType.Deny, crudTaskSet = null)),
        )
        assertEquals(BaseRolePermission.Allow, RbacResolver.resolve(USER, policy, null, port))
    }

    /** A direct Deny shadows a group Allow → Deny (SingleAction). */
    @Test
    fun directDenyShadowsGroup() = runTest {
        val policy = singleActionPolicy(default = BaseRolePermission.Allow)
        val port = FakeGrantPort(
            directGrant = RoleGrant(PermissionType.Deny, crudTaskSet = null),
            groupGrants = listOf(RoleGrant(PermissionType.Allow, crudTaskSet = null)),
        )
        assertEquals(BaseRolePermission.Deny, RbacResolver.resolve(USER, policy, null, port))
    }

    /** A direct Default row resolves to the role's defaultPermission and shadows a group Allow. */
    @Test
    fun directDefaultShadowsGroupAndUsesRoleDefault() = runTest {
        val policy = singleActionPolicy(default = BaseRolePermission.Deny)
        val port = FakeGrantPort(
            directGrant = RoleGrant(PermissionType.Default, crudTaskSet = null),
            groupGrants = listOf(RoleGrant(PermissionType.Allow, crudTaskSet = null)),
        )
        assertEquals(
            BaseRolePermission.Deny,
            RbacResolver.resolve(USER, policy, null, port),
            "a direct Default row resolves to defaultPermission=Deny, not the group Allow",
        )
    }

    /** A direct CrudTask grant whose crudTaskSet covers the task uses its own verdict (Allow), shadowing groups. */
    @Test
    fun directCrudGrantCoveringTaskShadowsGroup() = runTest {
        val policy = crudPolicy(default = BaseRolePermission.Deny, defaultTasks = setOf(CrudTask.Read))
        val port = FakeGrantPort(
            directGrant = RoleGrant(PermissionType.Allow, crudTaskSet = setOf(CrudTask.Read)),
            groupGrants = listOf(RoleGrant(PermissionType.Deny, crudTaskSet = setOf(CrudTask.Read))),
        )
        assertEquals(BaseRolePermission.Allow, RbacResolver.resolve(USER, policy, CrudTask.Read, port))
    }

    /** A direct CrudTask grant whose crudTaskSet does NOT cover the task denies (not-in-set ⇒ Deny). */
    @Test
    fun directCrudGrantNotCoveringTaskDenies() = runTest {
        val policy = crudPolicy(default = BaseRolePermission.Allow, defaultTasks = setOf(CrudTask.Update))
        val port = FakeGrantPort(
            directGrant = RoleGrant(PermissionType.Allow, crudTaskSet = setOf(CrudTask.Read)),
            groupGrants = listOf(RoleGrant(PermissionType.Allow, crudTaskSet = setOf(CrudTask.Update))),
        )
        assertEquals(
            BaseRolePermission.Deny,
            RbacResolver.resolve(USER, policy, CrudTask.Update, port),
            "the direct row is present but its crudTaskSet misses the task → Deny (groups NOT consulted)",
        )
    }

    // ---- (b) single-group Deny → Deny ----

    /** A single group Deny resolves to Deny under the uniform allow-override branch. */
    @Test
    fun singleGroupDenyResolvesToDeny() = runTest {
        val policy = crudPolicy(
            default = BaseRolePermission.Allow,
            defaultTasks = setOf(CrudTask.Read),
            upVote = BaseRolePermission.Allow,
        )
        val port = FakeGrantPort(
            groupGrants = listOf(RoleGrant(PermissionType.Deny, crudTaskSet = setOf(CrudTask.Read))),
        )
        assertEquals(BaseRolePermission.Deny, RbacResolver.resolve(USER, policy, CrudTask.Read, port))
    }

    // ---- (c) two-group Deny under upVote=Allow → Deny (D2 fix, R4) ----

    /** Two group Denies under an Allow-biased role resolve to Deny — explicit grants are never discarded. */
    @Test
    fun twoGroupDeniesUnderAllowOverrideResolveToDeny() = runTest {
        val policy = crudPolicy(
            default = BaseRolePermission.Allow,
            defaultTasks = setOf(CrudTask.Read),
            upVote = BaseRolePermission.Allow,
        )
        val port = FakeGrantPort(
            groupGrants = listOf(
                RoleGrant(PermissionType.Deny, crudTaskSet = setOf(CrudTask.Read)),
                RoleGrant(PermissionType.Deny, crudTaskSet = setOf(CrudTask.Read)),
            ),
        )
        assertEquals(
            BaseRolePermission.Deny,
            RbacResolver.resolve(USER, policy, CrudTask.Read, port),
            "D2: two group Denies → Deny, never the role default (R4)",
        )
    }

    // ---- (d) mixed Allow+Deny under upVote=Allow → Allow ----

    /** A mixed Allow+Deny group set under upVote=Allow resolves to Allow (allow-override opt-in). */
    @Test
    fun mixedGroupGrantsAllowWinUnderAllowOverride() = runTest {
        val policy = crudPolicy(
            default = BaseRolePermission.Deny,
            defaultTasks = setOf(CrudTask.Read),
            upVote = BaseRolePermission.Allow,
        )
        val port = FakeGrantPort(
            groupGrants = listOf(
                RoleGrant(PermissionType.Allow, crudTaskSet = setOf(CrudTask.Read)),
                RoleGrant(PermissionType.Deny, crudTaskSet = setOf(CrudTask.Read)),
            ),
        )
        assertEquals(BaseRolePermission.Allow, RbacResolver.resolve(USER, policy, CrudTask.Read, port))
    }

    /** A mixed Allow+Deny group set under upVote=Deny resolves to Deny (deny-override default). */
    @Test
    fun mixedGroupGrantsDenyWinUnderDenyOverride() = runTest {
        val policy = crudPolicy(
            default = BaseRolePermission.Allow,
            defaultTasks = setOf(CrudTask.Read),
            upVote = BaseRolePermission.Deny,
        )
        val port = FakeGrantPort(
            groupGrants = listOf(
                RoleGrant(PermissionType.Allow, crudTaskSet = setOf(CrudTask.Read)),
                RoleGrant(PermissionType.Deny, crudTaskSet = setOf(CrudTask.Read)),
            ),
        )
        assertEquals(BaseRolePermission.Deny, RbacResolver.resolve(USER, policy, CrudTask.Read, port))
    }

    // ---- (e) crudTaskSet miss under Deny-default → Deny (D3 no inversion) ----

    /** With no grants, a Deny-default role: a task in the default set denies; a task NOT in the set also denies (no inversion). */
    @Test
    fun crudTaskSetMissUnderDenyDefaultDenies() = runTest {
        val policy = crudPolicy(default = BaseRolePermission.Deny, defaultTasks = setOf(CrudTask.Read))
        val port = FakeGrantPort() // no direct, no group

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

    /** With no grants, a CrudTask role grants Allow only for a task covered by an Allow default. */
    @Test
    fun crudTaskSetHitUnderAllowDefaultAllows() = runTest {
        val policy = crudPolicy(default = BaseRolePermission.Allow, defaultTasks = setOf(CrudTask.Read))
        val port = FakeGrantPort()

        assertEquals(BaseRolePermission.Allow, RbacResolver.resolve(USER, policy, CrudTask.Read, port))
        assertEquals(
            BaseRolePermission.Deny,
            RbacResolver.resolve(USER, policy, CrudTask.Delete, port),
            "uncovered task falls to Deny even under an Allow default (allow-list)",
        )
    }

    // ---- (g) no grants → role default (SingleAction) ----

    /** With no direct row and no group grants, a SingleAction role falls through to its defaultPermission. */
    @Test
    fun noGrantsFallsThroughToSingleActionDefault() = runTest {
        assertEquals(
            BaseRolePermission.Allow,
            RbacResolver.resolve(USER, singleActionPolicy(default = BaseRolePermission.Allow), null, FakeGrantPort()),
        )
        assertEquals(
            BaseRolePermission.Deny,
            RbacResolver.resolve(USER, singleActionPolicy(default = BaseRolePermission.Deny), null, FakeGrantPort()),
        )
    }

    // ---- helpers ----

    private companion object {
        /** A stable user id for the resolver calls (the fake port ignores its actual value). */
        val USER: OId<Any> = OId()

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

/**
 * In-heap [IRbacGrantPort] for the pure resolver tests: returns a fixed root flag, optional direct
 * grant, and a fixed list of group grants — no database. The `exists*` probes mirror the fetch state.
 *
 * @property root Whether [isRootUser] reports the user as root.
 * @property directGrant The direct grant returned by [fetchDirectGrant], or `null` for none.
 * @property groupGrants The group grants returned by [fetchGroupGrants].
 */
private class FakeGrantPort(
    private val root: Boolean = false,
    private val directGrant: RoleGrant? = null,
    private val groupGrants: List<RoleGrant> = emptyList(),
) : IRbacGrantPort<OId<Any>> {
    /** Unused by the pure resolver tests (they pass an explicit policy to [RbacResolver.resolve]). */
    override suspend fun fetchAppRolePolicy(appRoleId: OId<out IAppRole<*>>): AppRolePolicy? = null
    override suspend fun isRootUser(userId: OId<Any>): Boolean = root
    override suspend fun fetchDirectGrant(userId: OId<Any>, appRoleId: OId<out IAppRole<*>>): RoleGrant? = directGrant
    override suspend fun fetchGroupGrants(userId: OId<Any>, appRoleId: OId<out IAppRole<*>>): List<RoleGrant> = groupGrants
    override suspend fun existsDirectGrant(userId: OId<Any>, appRoleId: OId<out IAppRole<*>>): Boolean = directGrant != null
    override suspend fun existsGroupGrant(userId: OId<Any>, appRoleId: OId<out IAppRole<*>>): Boolean = groupGrants.isNotEmpty()
}
