package com.fonrouge.conformance

import com.fonrouge.base.api.ApiFilter
import com.fonrouge.base.api.CrudTask
import com.fonrouge.base.api.IApiFilter
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
 * **Characterization** of MongoDB RBAC permission resolution (blueprint
 * `rbac-permission-resolution`, PLAN P1.1). These tests freeze the *current* behavior of
 * [IRoleInUserColl.permissionState] / `getGroupPermission` — **including the known foot-guns** — so
 * that the Phase-2 semantic changes (LEDGER D2/D3) are visible, intended diffs against a green
 * baseline. A test here asserts *what is*, not *what should be*; the ones marked `// CHARACTERIZATION`
 * encode behavior the blueprint intends to change and will be inverted at the cited step.
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

    // ---- C3 — multi-group tie-break and the discarded-denies foot-gun (R4 → D2) ----

    /**
     * C3: with exactly one matching group row, `upVoteInGroup` is ignored and the single group's `Deny`
     * applies directly — even though the role is `Allow`-biased.
     */
    @Test
    fun singleGroupDenyApplies_upVoteIgnored() = runTest {
        val f = RbacFixture()
        val appRole = crudRole(
            defaultPermission = BaseRolePermission.Allow,
            defaultTasks = setOf(CrudTask.Read),
            upVote = BaseRolePermission.Allow,
        )
        f.grantGroup(appRole, PermissionType.Deny, setOf(CrudTask.Read))

        assertTrue(
            f.resolveCrud(appRole, CrudTask.Read).hasError,
            "a single group Deny applies directly; upVoteInGroup is not consulted for one row",
        )
    }

    /**
     * C3/R4 — **the discarded-denies foot-gun.** Two groups each grant `Deny`, but under an
     * `Allow`-biased role the tie-break finds no `Allow`, so it falls through to the role default
     * (`Allow`) — silently discarding both explicit denies. CHARACTERIZATION: P2.1 (D2) makes this
     * resolve to `Deny`.
     */
    @Test
    fun twoGroupDeniesResolveToRoleDefault_theFootgun() = runTest {
        val f = RbacFixture()
        val appRole = crudRole(
            defaultPermission = BaseRolePermission.Allow,
            defaultTasks = setOf(CrudTask.Read),
            upVote = BaseRolePermission.Allow,
        )
        f.grantGroup(appRole, PermissionType.Deny, setOf(CrudTask.Read)) // group 1
        f.grantGroup(appRole, PermissionType.Deny, setOf(CrudTask.Read)) // group 2

        // CHARACTERIZATION: two explicit Denies currently resolve to the Allow role-default (granted).
        assertFalse(
            f.resolveCrud(appRole, CrudTask.Read).hasError,
            "current behavior: 2 group Denies under an Allow-biased role fall through to default=Allow (R4)",
        )
    }

    // ---- C4 — crudTaskSet-miss default inversion (R3 → D3) ----

    /**
     * C4/R3 — **the default-inversion foot-gun.** A `Deny`-default role with no grant for the user:
     * a task *in* `defaultCrudTaskSet` denies (expected), but a task *not* in the set **inverts** to
     * `Allow`. CHARACTERIZATION: P2.2 (D3) removes the inversion.
     */
    @Test
    fun denyDefaultInvertsToAllowOnCrudTaskMiss_theFootgun() = runTest {
        val f = RbacFixture()
        val appRole = crudRole(defaultPermission = BaseRolePermission.Deny, defaultTasks = setOf(CrudTask.Read))
        // No direct row, no group grant → getGroupPermission is empty → buildDefaultAppRolePermission.

        assertTrue(
            f.resolveCrud(appRole, CrudTask.Read).hasError,
            "task in defaultCrudTaskSet under a Deny-default denies (expected)",
        )
        // CHARACTERIZATION: Update is NOT in defaultCrudTaskSet, so the Deny-default inverts to Allow.
        assertFalse(
            f.resolveCrud(appRole, CrudTask.Update).hasError,
            "current behavior: a crudTaskSet miss under a Deny-default inverts to Allow (R3)",
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

    // ---- fixture ----

    /** Builds a fresh, wired RBAC collection set sharing one Testcontainers database. */
    private class RbacFixture {
        private val builder = MongoTestSupport.freshBuilder()
        val userId = OId<TUser>()
        private val appRoleColl = TAppRoleColl(builder)
        private val roleInGroupColl = TRoleInGroupColl(builder)
        private val userGroupColl = TUserGroupColl(builder)
        val groupOfUserColl = TGroupOfUserColl(builder)

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
                userSession = UserSession(
                    userId = userId,
                    inactivityUiSecsToNoRefresh = null,
                    inactivityUiSecsToLogout = null,
                    sessionMaxSecs = null,
                ),
                crudTask = task,
            ) { ItemState(item = appRole) }
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

/** App-role collection for characterization; auto-create insert hooks stay defaulted (unused here). */
private class TAppRoleColl(builder: MongoDbBuilder) :
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
