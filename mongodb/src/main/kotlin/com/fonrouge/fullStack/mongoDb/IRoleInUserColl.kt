package com.fonrouge.fullStack.mongoDb

import com.fonrouge.base.api.CrudTask
import com.fonrouge.base.api.IApiFilter
import com.fonrouge.base.common.ICommonContainer
import com.fonrouge.base.model.*
import com.fonrouge.base.model.IAppRole.BaseRolePermission
import com.fonrouge.base.model.IAppRole.RoleType
import com.fonrouge.base.state.ItemState
import com.fonrouge.base.state.SimpleState
import com.fonrouge.base.types.OId
import com.fonrouge.fullStack.repository.IRbacGrantPort
import com.fonrouge.fullStack.repository.RbacMembership
import com.fonrouge.fullStack.repository.RbacResolver
import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Projections
import com.mongodb.client.model.UnwindOptions
import io.ktor.server.application.*
import io.ktor.server.sessions.*
import org.bson.conversions.Bson
import org.litote.kmongo.*
import org.litote.kmongo.coroutine.CoroutineCollection
import kotlin.jvm.internal.FunctionReferenceImpl
import kotlin.reflect.KCallable
import kotlin.reflect.KClass

/**
 * Abstract class `IRoleInUserColl` that represents a collection of user roles.
 *
 * @param RIU The type of the user role.
 * @param U The type of the user.
 * @param UID The type of the user ID.
 * @param GR The type of the group role.
 * @param GOU The type of the group of users.
 * @param FILT The type of the API filter.
 * @param commonContainer A common container object.
 * @param mongoDbBuilder Optional Mongo connection builder forwarded to [Coll]; when `null` (the
 *   default) the collection resolves its database from the process-global Mongo configuration,
 *   preserving prior behavior. Supplying a builder targets a specific server/database (e.g. a
 *   per-test Testcontainers instance). The sibling RBAC collections ([appRoleColl],
 *   [roleInGroupColl], [userGroupColl]) must share the same database for [getSingleActionPermission]'s
 *   group `$lookup` to resolve.
 */
@Suppress("unused")
abstract class IRoleInUserColl<RIU : IRoleInUser<U, UID>, U : IUser<UID>, UID : Any, GR : IRoleInGroup<*, GOU>, GOU : IGroupOfUser<*>, FILT : IApiFilter<*>>(
    commonContainer: ICommonContainer<RIU, OId<IRoleInUser<U, UID>>, FILT>,
    mongoDbBuilder: MongoDbBuilder? = null,
) : Coll<RIU, OId<IRoleInUser<U, UID>>, FILT, UID>(
    commonContainer = commonContainer,
    mongoDbBuilder = mongoDbBuilder,
) {
    override suspend fun CoroutineCollection<RIU>.indexes() {
        coroutine.ensureUniqueIndex(
            IRoleInUser<U, UID>::userId, IRoleInUser<U, UID>::appRoleId
        )
    }

    //    abstract val appRoleColl: Coll<out ICommonContainer<out IAppRole, OId<IAppRole>, out IApiFilter<*>>, out IAppRole, OId<IAppRole>, out IApiFilter<*>>
    abstract val appRoleColl: IAppRoleColl<*, *, *, UID>
    abstract val roleInGroupColl: IRoleInGroupColl<GR, *, GOU, *, UID>
    abstract val userGroupColl: IUserGroupColl<out IUserGroup<U, UID, *, *>, U, UID, *, *, out IApiFilter<*>>

    /**
     * Determines whether the given user has root privileges.
     *
     * @param userId The user to check for root privileges.
     * @return A Boolean indicating whether the user has root privileges, or null if the check could not be performed.
     */
    open suspend fun rootUser(userId: UID): Boolean? = null

    /**
     * Retrieves the single action permission for a user based on the provided `ApplicationCall` and an optional `KCallable` or `StackTraceElement`.
     *
     * @param call The application call containing user session details.
     * @param kCallable An optional callable reference for the function whose permission is being checked. Defaults to null.
     * @param stackTraceElement The stack trace element from which the calling method's information is derived. Defaults to the caller's context.
     * @return A pair consisting of the user object (if valid) and a `SimpleState` object representing the permission state.
     */
    suspend fun getSingleActionPermission(
        call: ApplicationCall,
        kCallable: KCallable<*>? = null,
        stackTraceElement: StackTraceElement = Thread.currentThread().stackTrace[2],
    ): Pair<UserSession<UID>?, SimpleState> {
        val userSession: UserSession<UID> = call.sessions.get() ?: return null to SimpleState(
            isOk = false,
            msgError = "ApplicationCall not provided or invalid user session"
        )
        return userSession to getSingleActionPermission(
            userSession = userSession,
            kCallable = kCallable,
            stackTraceElement = stackTraceElement
        )
    }

    /**
     * Suspends and retrieves the single action permission for a user based on the provided `user` object and an optional `KCallable` or `StackTraceElement`.
     *
     * @param userSession The user for whom the permission is being checked.
     * @param kCallable An optional callable reference for the method whose permission is being checked. Defaults to null.
     * @param stackTraceElement The stack trace element from which the calling method's information is derived. Defaults to the caller's context.
     * @return A SimpleState object representing the permission state for the user.
     */
    suspend fun getSingleActionPermission(
        userSession: UserSession<UID>,
        kCallable: KCallable<*>? = null,
        stackTraceElement: StackTraceElement = Thread.currentThread().stackTrace[2],
    ): SimpleState {
        val classOwner: String
        val funcName: String
        if (kCallable != null) {
            classOwner = ((kCallable as FunctionReferenceImpl).owner as KClass<*>).simpleName ?: ""
            funcName = kCallable.name
        } else {
            classOwner = stackTraceElement.className.substringAfterLast('.')
            funcName = stackTraceElement.methodName
        }
        return getSingleActionPermission(
            userSession = userSession,
            classOwner = classOwner,
            funcName = funcName
        )
    }

    /**
     * Retrieves the single action permission for a user based on the provided class owner and function name.
     *
     * @param userSession The user for whom the permission is being checked.
     * @param classOwner The name of the class that owns the function for which permission is being checked.
     * @param funcName The name of the function for which permission is being checked.
     * @return A SimpleState object representing the permission state for the user.
     */
    @Suppress("MemberVisibilityCanBePrivate")
    suspend fun getSingleActionPermission(
        userSession: UserSession<UID>,
        classOwner: String,
        funcName: String,
    ): SimpleState {
        val (matchLabel, matchAppRole) = "${classOwner}::${funcName}" to and(
            IAppRole<*>::roleType eq RoleType.SingleAction,
            IAppRole<*>::classOwner eq classOwner,
            IAppRole<*>::funcName eq funcName
        )
        return permissionState(
            roleType = RoleType.SingleAction,
            userSession = userSession,
            crudTask = null
        ) {
            // D4 (side-effect-free): resolution does NOT provision. A missing role denies; roles are
            // provisioned explicitly at boot via IAppRoleColl.ensureRoles(...). (Was: `?: insertSingleActionRole(...)`.)
            appRoleColl.coroutine.findOne(matchAppRole)?.let {
                ItemState(item = it)
            } ?: ItemState(
                isOk = false,
                msgError = "App role doesn't exist '$matchLabel'"
            )
        }
    }

    /**
     * Determines the permission state based on the provided parameters and user session.
     *
     * @param call The application call, which may contain session information about the current user.
     * @param roleType The type of role being checked, such as single action or CRUD task.
     * @param crudTask An optional CRUD operation (Create, Read, Update, or Delete) to check the permissions for. Defaults to null.
     * @param appRoleBlock A suspending block that provides the state of an application role, which is used to determine permissions.
     */
    suspend fun permissionState(
        call: ApplicationCall,
        roleType: RoleType,
        crudTask: CrudTask? = null,
        appRoleBlock: suspend () -> ItemState<out IAppRole<*>>,
    ): SimpleState {
        val userSession: UserSession<UID>? = call.sessions.get()
        return userSession?.let {
            permissionState(
                roleType = roleType,
                userSession = userSession,
                crudTask = crudTask,
                appRoleBlock = appRoleBlock
            )
        } ?: SimpleState(isOk = false, msgError = "UserSession not valid")
    }

    /**
     * Retrieves the permission state for a specified user based on their role and a potential CRUD task.
     *
     * @param roleType The type of role being checked.
     * @param userSession The user for whom the permission is being checked.
     * @param crudTask An optional CRUD task that may further specify the permission being checked. Defaults to null.
     * @param appRoleBlock A suspending block that provides the state of an application role.
     * @return A SimpleState object representing the permission state for the user.
     */
    suspend fun permissionState(
        roleType: RoleType,
        userSession: UserSession<UID>,
        crudTask: CrudTask? = null,
        appRoleBlock: suspend () -> ItemState<out IAppRole<*>>,
    ): SimpleState {
        // Root short-circuit is kept at the entry point so its bespoke "as rootUser" SimpleState text is
        // preserved (the pure resolver returns only a BaseRolePermission). RbacResolver.resolve also
        // re-checks root via the port — harmless and keeps the resolver self-contained.
        if (rootUser(userId = userSession.userId) == true) return SimpleState(isOk = true, msgOk = "as rootUser")
        // AppRole resolution stays at the entry point (the appRoleBlock pattern is unchanged).
        val appRole: IAppRole<*> = appRoleBlock().let { itemState ->
            itemState.item ?: return SimpleState(
                isOk = false,
                msgError = itemState.msgError ?: "App role doesn't exist"
            )
        }
        // Delegate the (preserved) resolution algebra to the backend-agnostic resolver over the Mongo port,
        // then wrap the BaseRolePermission verdict with buildSimpleState (output identical to before).
        val baseRolePermission = RbacResolver.resolve(
            userId = userSession.userId,
            policy = AppRolePolicy.of(appRole),
            crudTask = crudTask,
            port = mongoRbacGrantPort,
        )
        return buildSimpleState(
            baseRolePermission = baseRolePermission,
            appRole = appRole,
            crudTask = crudTask,
        )
    }

    /**
     * Builds a SimpleState object based on role permissions and an optional CRUD task.
     *
     * @param baseRolePermission The base role permission indicating whether the action is allowed or denied.
     * @param appRole The application role associated with the user.
     * @param crudTask An optional CRUD task specifying the type of CRUD operation. Default is null.
     * @return A SimpleState object representing whether the permission is granted or denied, along with an appropriate message.
     */
    private fun buildSimpleState(
        baseRolePermission: BaseRolePermission,
        appRole: IAppRole<*>,
        crudTask: CrudTask?,
    ): SimpleState {
        val granted = baseRolePermission == BaseRolePermission.Allow
        val preLabel = "${appRole.roleType} ${crudTask?.let { "[" + it.name + "]" } ?: ""} ${appRole.description}"
        return SimpleState(
            isOk = granted,
            msgOk = if (granted) "$preLabel: Permission granted" else null,
            msgError = if (granted.not()) "$preLabel: Permission denied" else null
        )
    }

    /**
     * Group-aware membership probe: whether a SingleAction grant **edge** exists for `(userId, appRoleId)`
     * — a direct row OR any group grant — **ignoring** the permission vote, role default and group up-vote.
     *
     * This is the non-materializing, group-aware replacement for a raw
     * `countDocuments(RoleInUser by userId+appRoleId)`: that count is group-blind (it only sees the direct
     * row and returns `false` for a user who holds the role solely through a group), whereas this sees both
     * the direct and group edges via [mongoRbacGrantPort]. It answers **existence only** (D8: existence ≠
     * authz) — an explicit `Deny` edge still reports `true`; use [isAllowedSingleAction] for the effective
     * authorization decision.
     *
     * @param userId The user whose membership is checked.
     * @param appRoleId The app role being probed.
     * @return `true` iff a direct OR group grant edge exists for the pair.
     */
    suspend fun hasSingleActionGrant(userId: UID, appRoleId: OId<out IAppRole<*>>): Boolean =
        RbacMembership.hasSingleActionGrant(port = mongoRbacGrantPort, userId = userId, appRoleId = appRoleId)

    /**
     * Group-aware effective authorization: whether the user is allowed for the SingleAction role
     * `(userId, appRoleId)` under the full D1/T5 precedence resolution (direct-grant precedence, the
     * uniform group tie-break, then the role default), restricted to the SingleAction shape.
     *
     * This **is** the resolver, **not** a deny-override union over the edges (D8) — a direct `Allow` wins
     * over a group `Deny` (D1 precedence). The app role is fetched **non-provisioningly** by id through
     * [mongoRbacGrantPort]; a **missing** role denies (D4: unknown role denies, no provisioning). Like
     * [hasSingleActionGrant] it is group-aware, replacing the group-blind raw `RoleInUser` count.
     *
     * @param userId The user whose authorization is resolved.
     * @param appRoleId The app role being evaluated.
     * @return `true` iff the role exists and the precedence resolution yields an allow.
     */
    suspend fun isAllowedSingleAction(userId: UID, appRoleId: OId<out IAppRole<*>>): Boolean =
        RbacMembership.isAllowedSingleAction(port = mongoRbacGrantPort, userId = userId, appRoleId = appRoleId)

    /**
     * The MongoDB-backed [IRbacGrantPort] this collection delegates resolution to.
     *
     * It adapts the RBAC collections to the backend-agnostic grant-fetch port consumed by [RbacResolver]:
     * `fetchDirectGrant` is the former direct-row `find`, projected to a [RoleGrant]; `fetchGroupGrants`
     * is the former `getGroupPermission` `$lookup` aggregation, projected to [RoleGrant] (this REPLACES
     * the prior file-private `RoleInGroup`/`GroupOfUser` decode). The `exists*` probes are the
     * Phase-4-ready cheap existence checks (count / `limit(1)` aggregation).
     */
    private val mongoRbacGrantPort: IRbacGrantPort<UID> = object : IRbacGrantPort<UID> {

        /** A root user short-circuits to allow; mirrors [rootUser] (`null`/`false` ⇒ not root). */
        override suspend fun isRootUser(userId: UID): Boolean = rootUser(userId = userId) == true

        /**
         * The [AppRolePolicy] for [appRoleId], or `null` when no such app role exists.
         *
         * Fetches the app-role document by id from [appRoleColl] (a `_id` filter, consistent with the
         * existing `appRoleId eq` filters) and projects it via [AppRolePolicy.of]. **Non-provisioning**
         * (D4): a missing role yields `null` and performs no write — the membership API then denies.
         */
        override suspend fun fetchAppRolePolicy(appRoleId: OId<out IAppRole<*>>): AppRolePolicy? {
            val appRole = appRoleColl.coroutine.findOne(IAppRole<*>::_id eq appRoleId) ?: return null
            return AppRolePolicy.of(appRole)
        }

        /**
         * The user's direct role grant for [appRoleId], or `null` when no direct row exists — projected
         * **server-side** to exactly `{permission, crudTaskSet}` ([RoleGrant]'s shape) via a
         * `match → $limit(1) → $project` pipeline, so the full `RoleInUser` document is **never decoded**
         * (D9/R13): an undecodable `_id` (or any other field) can no longer throw at the check. This is
         * **behavior-preserving** — the resolver consumes only `permission`/`crudTaskSet` — and uses the
         * same projection shape as [buildGroupGrantPipeline], proven to decode into [RoleGrant] in CI.
         */
        override suspend fun fetchDirectGrant(userId: UID, appRoleId: OId<out IAppRole<*>>): RoleGrant? {
            val pipeline = mutableListOf<Bson>(
                match(
                    and(
                        IRoleInUser<U, UID>::userId eq userId,
                        IRoleInUser<U, UID>::appRoleId eq appRoleId
                    )
                ),
                limit(1),
                Aggregates.project(
                    Projections.fields(
                        Projections.include("permission", "crudTaskSet"),
                        Projections.excludeId(),
                    )
                ),
            )
            return coroutine.aggregate<RoleGrant>(pipeline = pipeline).first()
        }

        /**
         * The grants contributed by the user's groups for [appRoleId], projected to [RoleGrant].
         * The pipeline is the former `getGroupPermission` aggregation (match → `$lookup` on the matching
         * app role → unwind → replaceRoot) **plus a `$project` to `{permission, crudTaskSet}`** so the
         * `aggregate<RoleGrant>` decode is robust; the decode target is the typed [RoleGrant], which closes
         * R6 by dropping the file-private decode classes.
         */
        override suspend fun fetchGroupGrants(userId: UID, appRoleId: OId<out IAppRole<*>>): List<RoleGrant> {
            val pipeline = buildGroupGrantPipeline(userId = userId, appRoleId = appRoleId)
            return userGroupColl.coroutine.aggregate<RoleGrant>(pipeline = pipeline).toList()
        }

        /** Cheap presence probe for a direct grant (Phase-4-ready; not consulted by the resolver). */
        override suspend fun existsDirectGrant(userId: UID, appRoleId: OId<out IAppRole<*>>): Boolean =
            coroutine.countDocuments(
                and(
                    IRoleInUser<U, UID>::userId eq userId,
                    IRoleInUser<U, UID>::appRoleId eq appRoleId
                )
            ) > 0

        /** Cheap presence probe for any group grant (Phase-4-ready; not consulted by the resolver). */
        override suspend fun existsGroupGrant(userId: UID, appRoleId: OId<out IAppRole<*>>): Boolean {
            val pipeline = buildGroupGrantPipeline(userId = userId, appRoleId = appRoleId)
            pipeline += limit(1)
            return userGroupColl.coroutine.aggregate<RoleGrant>(pipeline = pipeline).first() != null
        }
    }

    /**
     * Builds the group-grant aggregation pipeline shared by the port's `fetchGroupGrants` and
     * `existsGroupGrant`: it matches the user's group memberships, `$lookup`s the role-in-group rows for
     * [appRoleId], unwinds, replaces the root with the role-in-group document, and `$project`s it to
     * `{permission, crudTaskSet}` so it decodes into [RoleGrant]. This is the pipeline previously inlined
     * in `getGroupPermission`, with the trailing `$project` added in P3.1a for a robust typed decode.
     *
     * @param userId The user whose group memberships are consulted.
     * @param appRoleId The app role being evaluated.
     * @return A mutable pipeline (mutable so callers may append a `$limit`).
     */
    private fun buildGroupGrantPipeline(userId: UID, appRoleId: OId<out IAppRole<*>>): MutableList<Bson> {
        val roleInGroupColl = this@IRoleInUserColl.roleInGroupColl
        val pipeline = mutableListOf<Bson>()
        pipeline.add(0, match(IUserGroup<U, UID, *, *>::userId eq userId))
        pipeline += lookup5(
            from = roleInGroupColl.commonContainer.itemKClass.collectionName,
            localField = IUserGroup<U, UID, *, *>::groupOfUserId,
            foreignField = IRoleInGroup<*, GOU>::groupOfUserId,
            resultField = IUserGroup<U, UID, *, *>::roleInGroups,
            pipeline = listOf(
                match(IRoleInGroup<*, GOU>::appRoleId eq appRoleId)
            )
        )
        pipeline += IUserGroup<U, UID, *, *>::roleInGroups.unwind(
            UnwindOptions().preserveNullAndEmptyArrays(
                false
            )
        )
        pipeline += replaceRoot(IUserGroup<U, UID, *, *>::roleInGroups)
        // Project to exactly RoleGrant's shape so the aggregate<RoleGrant> decode never depends on the
        // decoder ignoring the role-in-group doc's other fields (_id/groupOfUserId/appRoleId).
        pipeline += Aggregates.project(
            Projections.fields(
                Projections.include("permission", "crudTaskSet"),
                Projections.excludeId(),
            )
        )
        return pipeline
    }

    fun userSessionFromCall(call: ApplicationCall?): UserSession<UID>? = call?.sessions?.get<UserSession<UID>>()
}
