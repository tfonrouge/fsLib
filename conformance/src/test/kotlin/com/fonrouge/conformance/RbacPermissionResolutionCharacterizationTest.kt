package com.fonrouge.conformance

import com.fonrouge.base.api.ApiFilter
import com.fonrouge.base.api.CrudTask
import com.fonrouge.base.api.IApiFilter
import com.fonrouge.base.common.ICommonContainer
import com.fonrouge.base.common.simpleContainer
import com.fonrouge.base.model.IAppRole
import com.fonrouge.base.model.IAppRole.BaseRolePermission
import com.fonrouge.base.model.IAppRole.RoleType
import com.fonrouge.base.model.IGroupOfUser
import com.fonrouge.base.model.IRoleInGroup
import com.fonrouge.base.model.IRoleInUser
import com.fonrouge.base.model.IUser
import com.fonrouge.base.model.IUserGroup
import com.fonrouge.base.model.PermissionType
import com.fonrouge.base.model.UserSession
import com.fonrouge.base.state.ItemState
import com.fonrouge.base.types.OId
import com.fonrouge.fullStack.mongoDb.IAppRoleColl
import com.fonrouge.fullStack.mongoDb.IGroupOfUserColl
import com.fonrouge.fullStack.mongoDb.IRoleInGroupColl
import com.fonrouge.fullStack.mongoDb.IRoleInUserColl
import com.fonrouge.fullStack.mongoDb.IUserColl
import com.fonrouge.fullStack.mongoDb.IUserGroupColl
import com.fonrouge.fullStack.mongoDb.MongoDbBuilder
import com.fonrouge.fullStack.repository.PermissionRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for MongoDB RBAC permission resolution (blueprint `rbac-permission-resolution`).
 * **Mixed.** *Characterization* (frozen current behavior, PLAN P1.1): `rootUserShortCircuitsToAllow`
 * (C1), `directDenyRowShadowsGroupAllow` / `directDefaultRowShadowsGroupAllow` (C2). *Correctness*
 * assertions for the shipped D2/D3 fixes: `multiGroupDeniesAreHonoredNotDiscarded` (P2.1/R4) and
 * `crudTaskSetMissUnderDenyDefaultDenies` (P2.2/R3) are the former foot-gun characterizations flipped
 * red→green; `singleGroupDenyResolvesToDeny` and `mixedGroupGrantsAllowWinUnderAllowOverride` pin the
 * uniform D2 tie-break (deny-override default + `upVote=Allow` allow-override opt-in).
 *
 * Runs against a real mongod via the shared Testcontainers fixture ([MongoTestSupport]); skips
 * locally when Docker is absent (green-or-skip) and runs for real in CI (D11 discipline). Per-instance
 * isolation uses the `mongoDbBuilder` parameter added to the RBAC colls in P1.0 — every fixture shares
 * one fresh database so `getGroupPermission`'s cross-collection `$lookup` resolves.
 */
class RbacPermissionResolutionCharacterizationTest {

    @BeforeTest
    fun requireDocker() {
        MongoTestSupport.requireDocker()
    }

    /** Resets the process-global permission provider that constructing an [IRoleInUserColl] registers. */
    @AfterTest
    fun resetRegistry() {
        PermissionRegistry.rolePermissionProvider = null
    }

    // ---- C2 / D1 — a direct user grant outweighs group grants (ratified target) ----

    /**
     * C2/D1: with no direct row the group `Allow` applies, but adding a direct `Deny` row flips the
     * verdict to denied — the direct grant shadows the group entirely.
     */
    @Test
    fun directDenyRowShadowsGroupAllow() = runTest {
        val f = RbacFixture()
        val appRole = crudRole(defaultPermission = BaseRolePermission.Deny, defaultTasks = setOf(CrudTask.Read))
        f.grantGroup(appRole, PermissionType.Allow, setOf(CrudTask.Read))

        // Baseline: no direct row → the group Allow grants.
        assertFalse(
            f.resolveCrud(appRole, CrudTask.Read).hasError,
            "group Allow should grant Read when the user has no direct row",
        )

        // Add a direct Deny row for the same (user, role) → it shadows the group Allow.
        f.grantDirect(appRole, PermissionType.Deny, setOf(CrudTask.Read))
        assertTrue(
            f.resolveCrud(appRole, CrudTask.Read).hasError,
            "a direct Deny row must outweigh the group Allow (D1 user precedence)",
        )
    }

    /**
     * C2/D1: a direct row whose permission is `Default` still shadows a group `Allow` — it resolves to
     * the role's `defaultPermission` (here `Deny`) rather than falling through to groups. This is the
     * subtle case re-confirmed on master 2026-06-21.
     */
    @Test
    fun directDefaultRowShadowsGroupAllow() = runTest {
        val f = RbacFixture()
        val appRole = crudRole(defaultPermission = BaseRolePermission.Deny, defaultTasks = setOf(CrudTask.Read))
        f.grantGroup(appRole, PermissionType.Allow, setOf(CrudTask.Read))
        f.grantDirect(appRole, PermissionType.Default, setOf(CrudTask.Read))

        assertTrue(
            f.resolveCrud(appRole, CrudTask.Read).hasError,
            "a direct Default row resolves to defaultPermission=Deny and shadows the group Allow",
        )
    }

    // ---- C3 / D2 — group tie-break (uniform total rule) ----

    /**
     * D2: a single group `Deny` resolves to `Deny` under the **uniform** total rule. (Under the
     * `Allow`-override branch the rule looks for an `Allow` first, finds none, then honors the `Deny`.)
     * NB: this is the uniform rule, not the old `size==1` special case that skipped `upVote`.
     */
    @Test
    fun singleGroupDenyResolvesToDeny() = runTest {
        val f = RbacFixture()
        val appRole = crudRole(
            defaultPermission = BaseRolePermission.Allow,
            defaultTasks = setOf(CrudTask.Read),
            upVote = BaseRolePermission.Allow,
        )
        f.grantGroup(appRole, PermissionType.Deny, setOf(CrudTask.Read))

        assertTrue(
            f.resolveCrud(appRole, CrudTask.Read).hasError,
            "D2: single group Deny → Deny (allow-override finds no Allow, so the explicit Deny applies)",
        )
    }

    /**
     * D2 allow-override branch (option d): with `upVoteInGroup == Allow`, a mixed Allow+Deny group set
     * resolves to **`Allow`** — the per-role allow-override opt-in lets any `Allow` win over a `Deny`.
     * Pins the approved allow-override path (the deny-override default is pinned by
     * `multiGroupDeniesAreHonoredNotDiscarded`).
     */
    @Test
    fun mixedGroupGrantsAllowWinUnderAllowOverride() = runTest {
        val f = RbacFixture()
        val appRole = crudRole(
            defaultPermission = BaseRolePermission.Deny,   // restrictive default, to prove the Allow grant wins
            defaultTasks = setOf(CrudTask.Read),
            upVote = BaseRolePermission.Allow,             // allow-override opt-in
        )
        f.grantGroup(appRole, PermissionType.Allow, setOf(CrudTask.Read)) // group 1
        f.grantGroup(appRole, PermissionType.Deny, setOf(CrudTask.Read))  // group 2

        assertFalse(
            f.resolveCrud(appRole, CrudTask.Read).hasError,
            "D2 allow-override: an Allow grant wins over a Deny when upVoteInGroup == Allow",
        )
    }

    /**
     * C3/R4 → **fixed (P2.1, D2).** Two groups each grant `Deny` under an `Allow`-biased role. The
     * old tie-break found no `Allow` and discarded both denies into the role default (`Allow`); the
     * D2 total rule honors the explicit denies → `Deny`. This is now a **correctness** assertion (the
     * red→green flip of the former foot-gun characterization).
     */
    @Test
    fun multiGroupDeniesAreHonoredNotDiscarded() = runTest {
        val f = RbacFixture()
        val appRole = crudRole(
            defaultPermission = BaseRolePermission.Allow,
            defaultTasks = setOf(CrudTask.Read),
            upVote = BaseRolePermission.Allow,
        )
        f.grantGroup(appRole, PermissionType.Deny, setOf(CrudTask.Read)) // group 1
        f.grantGroup(appRole, PermissionType.Deny, setOf(CrudTask.Read)) // group 2

        assertTrue(
            f.resolveCrud(appRole, CrudTask.Read).hasError,
            "D2: 2 group Denies resolve to Deny — explicit grants are never discarded into the role default (R4 fixed)",
        )
    }

    // ---- C4 — crudTaskSet-miss semantics (R3 → D3) ----

    /**
     * C4/R3 → **fixed (P2.2, D3).** A `Deny`-default role with no grant for the user: a task *in*
     * `defaultCrudTaskSet` denies (expected), and a task *not* in the set is now **uncovered → `Deny`**
     * (allow-list semantics), no longer the old inversion to `Allow`. Correctness assertion.
     */
    @Test
    fun crudTaskSetMissUnderDenyDefaultDenies() = runTest {
        val f = RbacFixture()
        val appRole = crudRole(defaultPermission = BaseRolePermission.Deny, defaultTasks = setOf(CrudTask.Read))
        // No direct row, no group grant → getGroupPermission is empty → buildDefaultAppRolePermission.

        assertTrue(
            f.resolveCrud(appRole, CrudTask.Read).hasError,
            "task in defaultCrudTaskSet under a Deny-default denies (expected)",
        )
        assertTrue(
            f.resolveCrud(appRole, CrudTask.Update).hasError,
            "D3: a crudTaskSet miss is uncovered → Deny, no inversion (R3 fixed)",
        )
    }

    // ---- C1 — root short-circuit ----

    /** C1: a root user is granted before any role/group resolution, regardless of the role default. */
    @Test
    fun rootUserShortCircuitsToAllow() = runTest {
        val f = RbacFixture()
        val appRole = crudRole(defaultPermission = BaseRolePermission.Deny, defaultTasks = setOf(CrudTask.Read))

        assertFalse(
            f.resolveCrud(appRole, CrudTask.Delete, asRoot = true).hasError,
            "a root user is granted unconditionally, ahead of direct/group/default resolution",
        )
    }

    // ---- C5 / D4 — lazy provisioning hook on the check path (R5) ----

    /**
     * C5/R5 → **fixed (P2.3, D4).** After removing the lazy `?: insertSingleActionRole` from the check
     * path, a permission check on a **missing** SingleAction role **denies** and **does not invoke** the
     * provisioning hook. This is the red→green flip of `missingSingleActionRoleInvokesProvisioningHook`
     * (P1.2, committed `c5a09a6c`). Roles are now provisioned explicitly via `IAppRoleColl.ensureRoles(...)`.
     */
    @Test
    fun missingSingleActionRoleDeniesWithoutProvisioning() = runTest {
        val f = RbacFixture()
        // No AppRole seeded for ("Demo","act"); the lazy provisioning site has been removed.
        val state = f.resolveSingleAction(classOwner = "Demo", funcName = "act")

        assertTrue(
            state.hasError,
            "P2.3: a missing SingleAction role denies",
        )
        assertFalse(
            f.appRoleColl.singleActionInsertInvoked,
            "P2.3: the provisioning hook is NOT invoked on the check path",
        )
    }

    // ---- fixture ----

    /** Builds a fresh, wired RBAC collection set sharing one Testcontainers database. */
    private class RbacFixture {
        private val builder = MongoTestSupport.freshBuilder()
        val userId = OId<TUser>()
        val appRoleColl = TAppRoleColl(builder)
        private val roleInGroupColl = TRoleInGroupColl(builder)
        private val userGroupColl = TUserGroupColl(builder)
        val groupOfUserColl = TGroupOfUserColl(builder)

        private val session = UserSession(
            userId = userId,
            inactivityUiSecsToNoRefresh = null,
            inactivityUiSecsToLogout = null,
            sessionMaxSecs = null,
        )

        private fun riuColl(asRoot: Boolean) = TRoleInUserColl(
            builder = builder,
            appRoleColl = appRoleColl,
            roleInGroupColl = roleInGroupColl,
            userGroupColl = userGroupColl,
            rootUserIds = if (asRoot) setOf(userId) else emptySet(),
        )

        /** Seeds a direct [IRoleInUser] grant for [userId] on [appRole] (raw insert — no hooks/gate). */
        suspend fun grantDirect(appRole: TAppRole, permission: PermissionType, tasks: Set<CrudTask>?) {
            riuColl(asRoot = false).coroutine.insertOne(
                TRoleInUser(userId = userId, appRoleId = appRole._id, permission = permission, crudTaskSet = tasks),
            )
        }

        /** Seeds a new group granting [appRole], and makes [userId] a member of it. */
        suspend fun grantGroup(appRole: TAppRole, permission: PermissionType, tasks: Set<CrudTask>?) {
            val group = TGroupOfUser(description = "g_${OId<TGroupOfUser>().id}")
            groupOfUserColl.coroutine.insertOne(group)
            roleInGroupColl.coroutine.insertOne(
                TRoleInGroup(
                    groupOfUserId = group._id,
                    appRoleId = appRole._id,
                    permission = permission,
                    crudTaskSet = tasks,
                ),
            )
            userGroupColl.coroutine.insertOne(TUserGroup(userId = userId, groupOfUserId = group._id))
        }

        /** Resolves the CRUD permission for [appRole]/[task] for [userId] via the real resolution engine. */
        suspend fun resolveCrud(appRole: TAppRole, task: CrudTask, asRoot: Boolean = false) =
            riuColl(asRoot).permissionState(
                roleType = RoleType.CrudTask,
                userSession = session,
                crudTask = task,
            ) { ItemState(item = appRole) }

        /**
         * Resolves a SingleAction permission via the public `getSingleActionPermission` entry — the
         * SingleAction check path (site A). No `AppRole` is seeded, so the role is missing: after P2.3 it
         * denies without invoking any provisioning hook (pre-P2.3 it reached the lazy `insertSingleActionRole`).
         */
        suspend fun resolveSingleAction(classOwner: String, funcName: String) =
            riuColl(asRoot = false).getSingleActionPermission(
                userSession = session,
                classOwner = classOwner,
                funcName = funcName,
            )
    }

    private companion object {
        /** A CRUD-type app role with explicit default permission / task set / group-vote bias. */
        fun crudRole(
            defaultPermission: BaseRolePermission,
            defaultTasks: Set<CrudTask>?,
            upVote: BaseRolePermission = BaseRolePermission.Allow,
        ) = TAppRole(
            classOwner = "Demo_${OId<TAppRole>().id}",
            funcName = null,
            roleType = RoleType.CrudTask,
            description = "demo_${OId<TAppRole>().id}",
            defaultPermission = defaultPermission,
            defaultCrudTaskSet = defaultTasks,
            upVoteInGroup = upVote,
        )
    }
}

/**
 * Local (no-Docker) test for the explicit provisioning surface added in P2.3 (D4). `ensureRoles` only
 * delegates to the `insert*` primitives (probes here) and performs no DB I/O, so it runs without a
 * mongod. Proves the surface **delegates** to both primitives and **aggregates** their success/failure
 * (no false success) — it does not exercise real persistence.
 */
class RbacEnsureRolesTest {

    /** ensureRoles delegates to both primitives and aggregates to OK when they all succeed. */
    @Test
    fun ensureRolesInvokesProvisioningPrimitivesAndSucceeds() = runTest {
        val appRoleColl = TAppRoleColl(MongoDbBuilder()) // no connection — ensureRoles does no DB I/O

        val state = appRoleColl.ensureRoles(
            crudContainers = listOf(CommonCItem),
            singleActions = listOf("Demo" to "act"),
        )

        assertFalse(state.hasError, "ensureRoles aggregates to OK when every primitive succeeds")
        assertTrue(appRoleColl.crudInsertInvoked, "ensureRoles delegates to insertCrudRole")
        assertTrue(appRoleColl.singleActionInsertInvoked, "ensureRoles delegates to insertSingleActionRole")
    }

    /** ensureRoles must SURFACE failure, not lie: a failing primitive yields an error state. */
    @Test
    fun ensureRolesSurfacesPrimitiveFailure() = runTest {
        val appRoleColl = TFailingAppRoleColl(MongoDbBuilder()) // primitives keep the inert isOk=false default

        val state = appRoleColl.ensureRoles(
            crudContainers = listOf(CommonCItem),
            singleActions = listOf("Demo" to "act"),
        )

        assertTrue(
            state.hasError,
            "ensureRoles must report failure when a provisioning primitive fails (no false success)",
        )
    }
}

// ---- Minimal concrete RBAC model types (constructor-only, @Serializable) ----

/** Characterization user. */
@Serializable
private data class TUser(
    override val _id: OId<TUser> = OId(),
    override val inactivityUiSecsToNoRefresh: Int? = null,
    override val inactivityUiSecsToLogout: Int? = null,
    override val sessionMaxSecs: Int? = null,
) : IUser<OId<TUser>>

/** Characterization application role. */
@Serializable
private data class TAppRole(
    override val _id: OId<TAppRole> = OId(),
    override val classOwner: String,
    override val funcName: String? = null,
    override val roleType: RoleType,
    override val description: String,
    override val detail: String? = null,
    override val defaultPermission: BaseRolePermission,
    override val defaultCrudTaskSet: Set<CrudTask>? = null,
    override val upVoteInGroup: BaseRolePermission = BaseRolePermission.Allow,
) : IAppRole<OId<TAppRole>>

/** Characterization group. */
@Serializable
private data class TGroupOfUser(
    override val _id: OId<TGroupOfUser> = OId(),
    override val description: String,
) : IGroupOfUser<TGroupOfUser>

/** Characterization direct user-role grant. */
@Serializable
private data class TRoleInUser(
    override val _id: OId<IRoleInUser<TUser, OId<TUser>>> = OId(),
    override val userId: OId<TUser>,
    override val appRoleId: OId<out IAppRole<*>>,
    override val permission: PermissionType,
    override val crudTaskSet: Set<CrudTask>? = null,
) : IRoleInUser<TUser, OId<TUser>>

/** Characterization group-role grant. */
@Serializable
private data class TRoleInGroup(
    override val _id: OId<TRoleInGroup> = OId(),
    override val groupOfUserId: OId<TGroupOfUser>,
    override val appRoleId: OId<out IAppRole<*>>,
    override val permission: PermissionType,
    override val crudTaskSet: Set<CrudTask>? = null,
) : IRoleInGroup<TRoleInGroup, TGroupOfUser>

/** Characterization user-group membership. */
@Serializable
private data class TUserGroup(
    override val _id: OId<IUserGroup<TUser, OId<TUser>, TGroupOfUser, TRoleInGroup>> = OId(),
    override val userId: OId<TUser>,
    override val groupOfUserId: OId<TGroupOfUser>,
    override val roleInGroups: List<TRoleInGroup> = emptyList(),
) : IUserGroup<TUser, OId<TUser>, TGroupOfUser, TRoleInGroup>

// ---- Minimal concrete RBAC collections (per-instance builder, shared DB) ----

/**
 * App-role collection for characterization, instrumented with **provisioning-hook invocation probes**.
 * `insertSingleActionRole`/`insertCrudRole` record whether the hook was called, and (unlike the inert
 * in-tree defaults that return `isOk=false`) return a **successful** `ItemState` — modeling a real
 * downstream impl that provisions, so `ensureRoles` aggregates to success. Used to prove P1.2 (a check on
 * a missing role invokes the hook), that P2.3 stops invoking it, and that `ensureRoles` reaches both hooks.
 */
private class TAppRoleColl(builder: MongoDbBuilder) :
    IAppRoleColl<TAppRole, OId<TAppRole>, ApiFilter, OId<TUser>>(simpleContainer<TAppRole, OId<TAppRole>>(), builder) {
    override val userCollFun: () -> IUserColl<*, OId<TUser>, *>? = { null }

    var singleActionInsertInvoked = false
        private set
    var crudInsertInvoked = false
        private set

    override suspend fun insertSingleActionRole(classOwner: String, funcName: String): ItemState<TAppRole> {
        singleActionInsertInvoked = true
        return ItemState(isOk = true) // model a downstream impl that successfully provisions
    }

    override suspend fun insertCrudRole(container: ICommonContainer<*, *, *>, crudTask: CrudTask): ItemState<TAppRole> {
        crudInsertInvoked = true
        return ItemState(isOk = true)
    }
}

/**
 * App-role collection whose provisioning primitives keep the inert in-tree default (`isOk=false`), i.e.
 * a subclass that never actually provisions. Used to prove `ensureRoles` **surfaces** that failure.
 */
private class TFailingAppRoleColl(builder: MongoDbBuilder) :
    IAppRoleColl<TAppRole, OId<TAppRole>, ApiFilter, OId<TUser>>(simpleContainer<TAppRole, OId<TAppRole>>(), builder) {
    override val userCollFun: () -> IUserColl<*, OId<TUser>, *>? = { null }
}

/** Group collection for characterization. */
private class TGroupOfUserColl(builder: MongoDbBuilder) :
    IGroupOfUserColl<TGroupOfUser, TGroupOfUser, ApiFilter, OId<TUser>>(
        simpleContainer<TGroupOfUser, OId<TGroupOfUser>>(), builder,
    ) {
    override val userCollFun: () -> IUserColl<*, OId<TUser>, *>? = { null }
}

/** Group-role collection for characterization. */
private class TRoleInGroupColl(builder: MongoDbBuilder) :
    IRoleInGroupColl<TRoleInGroup, TRoleInGroup, TGroupOfUser, ApiFilter, OId<TUser>>(
        simpleContainer<TRoleInGroup, OId<TRoleInGroup>>(), builder,
    ) {
    override val userCollFun: () -> IUserColl<*, OId<TUser>, *>? = { null }
}

/** User-group collection for characterization. */
private class TUserGroupColl(builder: MongoDbBuilder) :
    IUserGroupColl<TUserGroup, TUser, OId<TUser>, TGroupOfUser, TRoleInGroup, ApiFilter>(
        simpleContainer<TUserGroup, OId<IUserGroup<TUser, OId<TUser>, TGroupOfUser, TRoleInGroup>>>(), builder,
    ) {
    override val userCollFun: () -> IUserColl<*, OId<TUser>, *>? = { null }
}

/** Role-in-user collection — the resolution engine under characterization; siblings injected to share one DB. */
private class TRoleInUserColl(
    builder: MongoDbBuilder,
    override val appRoleColl: IAppRoleColl<*, *, *, OId<TUser>>,
    override val roleInGroupColl: IRoleInGroupColl<TRoleInGroup, *, TGroupOfUser, *, OId<TUser>>,
    override val userGroupColl: IUserGroupColl<out IUserGroup<TUser, OId<TUser>, *, *>, TUser, OId<TUser>, *, *, out IApiFilter<*>>,
    private val rootUserIds: Set<OId<TUser>> = emptySet(),
) : IRoleInUserColl<TRoleInUser, TUser, OId<TUser>, TRoleInGroup, TGroupOfUser, ApiFilter>(
    simpleContainer<TRoleInUser, OId<IRoleInUser<TUser, OId<TUser>>>>(), builder,
) {
    override val userCollFun: () -> IUserColl<*, OId<TUser>, *>? = { null }
    override suspend fun rootUser(userId: OId<TUser>): Boolean? = if (userId in rootUserIds) true else null
}
