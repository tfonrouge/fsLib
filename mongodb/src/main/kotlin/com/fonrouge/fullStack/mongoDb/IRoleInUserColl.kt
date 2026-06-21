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
import com.mongodb.client.model.UnwindOptions
import io.ktor.server.application.*
import io.ktor.server.sessions.*
import kotlinx.serialization.Serializable
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
            appRoleColl.coroutine.findOne(matchAppRole)?.let {
                ItemState(item = it)
            } ?: appRoleColl.insertSingleActionRole(
                classOwner = classOwner,
                funcName = funcName
            ).item?.let {
                ItemState(item = it)
            } ?: ItemState(
                isOk = false,
                msgError = "App role doesn't exist '$matchLabel' ... "
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
        if (rootUser(userId = userSession.userId) == true) return SimpleState(isOk = true, msgOk = "as rootUser")
        val appRole: IAppRole<*> = appRoleBlock().let { itemState ->
            itemState.item ?: return SimpleState(
                isOk = false,
                msgError = itemState.msgError ?: "App role doesn't exist"
            )
        }
        val roleInUser: RIU? = coroutine.find(
            filter = and(
                IRoleInUser<U, UID>::userId eq userSession.userId,
                IRoleInUser<U, UID>::appRoleId eq appRole._id
            )
        ).first()
        roleInUser?.let { it: RIU ->
            return when (roleType) {
                RoleType.SingleAction -> when (it.permission) {
                    PermissionType.Allow -> buildSimpleState(BaseRolePermission.Allow, appRole, null)
                    PermissionType.Deny -> buildSimpleState(BaseRolePermission.Deny, appRole, null)
                    PermissionType.Default -> buildSimpleState(appRole.defaultPermission, appRole, null)
                }

                RoleType.CrudTask -> buildSimpleState(
                    baseRolePermission = if (it.crudTaskSet?.contains(crudTask) == true) {
                        when (it.permission) {
                            PermissionType.Allow -> BaseRolePermission.Allow
                            PermissionType.Deny -> BaseRolePermission.Deny
                            PermissionType.Default -> when (appRole.defaultPermission) {
                                BaseRolePermission.Allow -> BaseRolePermission.Allow
                                BaseRolePermission.Deny -> BaseRolePermission.Deny
                            }
                        }
                    } else BaseRolePermission.Deny,
                    appRole = appRole,
                    crudTask = crudTask
                )
            }
        }
        return when (roleType) {
            RoleType.SingleAction -> buildSimpleState(
                baseRolePermission = getGroupPermission(
                    userSession = userSession,
                    appRole = appRole,
                    crudTask = crudTask
                ),
                appRole = appRole,
                crudTask = crudTask
            )

            RoleType.CrudTask -> buildSimpleState(
                baseRolePermission = getGroupPermission(
                    userSession = userSession,
                    appRole = appRole,
                    crudTask = crudTask
                ),
                appRole = appRole,
                crudTask = crudTask
            )
        }
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
     * Builds the default application role permission based on the role type and optional CRUD task.
     *
     * @param appRole The application role for which the permission is being built.
     * @param crudTask An optional CRUD task specifying the type of CRUD operation. Default is null.
     * @return The default base role permission, either Allow or Deny, depending on the role type and CRUD task.
     */
    private fun buildDefaultAppRolePermission(
        appRole: IAppRole<*>,
        crudTask: CrudTask? = null,
    ): BaseRolePermission {
        return when (appRole.roleType) {
            RoleType.SingleAction -> appRole.defaultPermission
            // D3 (allow-list, no inversion): `defaultCrudTaskSet` lists the tasks the default covers.
            // A task in the set takes `defaultPermission`; a task NOT in the set is simply uncovered
            // and falls to the safe baseline `Deny` — never the former `Deny`-default ⇒ `Allow`
            // inversion (R3). This now agrees with the direct-row path's not-in-set ⇒ `Deny`.
            RoleType.CrudTask -> if (appRole.defaultCrudTaskSet?.contains(crudTask) == true) {
                appRole.defaultPermission
            } else {
                BaseRolePermission.Deny
            }
        }
    }

    /**
     * Retrieves the group-level permission for a specified user and application role.
     *
     * @param userSession The user whose group permissions are being checked.
     * @param appRole The application role for which the group's permission is being determined.
     * @param crudTask An optional CRUD task that further specifies the permission being checked. Defaults to null.
     * @return The base role permission (Allow, Deny, or Default) for the user's group with respect to the specified application role and CRUD task.
     */
    private suspend fun getGroupPermission(
        userSession: UserSession<*>,
        appRole: IAppRole<out Any>,
        crudTask: CrudTask? = null,
    ): BaseRolePermission {
        val userGroupColl = userGroupColl
        val roleInGroupColl = this@IRoleInUserColl.roleInGroupColl
        val pipeline = mutableListOf<Bson>()
        pipeline.add(0, match(IUserGroup<U, UID, *, *>::userId eq userSession.userId))
        pipeline += lookup5(
            from = roleInGroupColl.commonContainer.itemKClass.collectionName,
            localField = IUserGroup<U, UID, *, *>::groupOfUserId,
            foreignField = IRoleInGroup<*, GOU>::groupOfUserId,
            resultField = IUserGroup<U, UID, *, *>::roleInGroups,
            pipeline = listOf(
                match(IRoleInGroup<*, GOU>::appRoleId eq appRole._id)
            )
        )
        pipeline += IUserGroup<U, UID, *, *>::roleInGroups.unwind(
            UnwindOptions().preserveNullAndEmptyArrays(
                false
            )
        )
        pipeline += replaceRoot(IUserGroup<U, UID, *, *>::roleInGroups)
        val groupRoleList = userGroupColl.coroutine.aggregate<RoleInGroup>(
            pipeline = pipeline
        ).toList()
        val permissionTypes = when (appRole.roleType) {
            RoleType.SingleAction -> groupRoleList
            RoleType.CrudTask -> groupRoleList.filter { roleInGroup ->
                crudTask?.let { roleInGroup.crudTaskSet?.contains(it) == true } != false
            }
        }
        if (permissionTypes.isEmpty()) return buildDefaultAppRolePermission(appRole, crudTask)
        // D2 (total conflict rule, applied uniformly to single- and multi-group sets): the default
        // bias is deny-override (safe); `upVoteInGroup == Allow` is the explicit per-role allow-override
        // opt-in. An explicit `Allow`/`Deny` group grant is NEVER discarded into the role default —
        // the role default applies only when every applicable grant is `Default` (closes R4, satisfies
        // T1). Previously a 2+-group set whose `upVote` bias was unmet fell through to the role default,
        // so two `Deny` groups under an `Allow`-biased role could resolve to `Allow`.
        val hasAllow = permissionTypes.any { it.permission == PermissionType.Allow }
        val hasDeny = permissionTypes.any { it.permission == PermissionType.Deny }
        return when (appRole.upVoteInGroup) {
            BaseRolePermission.Allow -> when {           // allow-override (per-role opt-in)
                hasAllow -> BaseRolePermission.Allow
                hasDeny -> BaseRolePermission.Deny
                else -> buildDefaultAppRolePermission(appRole, crudTask)
            }

            BaseRolePermission.Deny -> when {            // deny-override (the safe default)
                hasDeny -> BaseRolePermission.Deny
                hasAllow -> BaseRolePermission.Allow
                else -> buildDefaultAppRolePermission(appRole, crudTask)
            }
        }
    }

    fun userSessionFromCall(call: ApplicationCall?): UserSession<UID>? = call?.sessions?.get<UserSession<UID>>()
}

@Serializable
private data class GroupOfUser(
    override val _id: OId<GroupOfUser>,
    override val description: String,
) : IGroupOfUser<GroupOfUser>

@Serializable
private data class RoleInGroup(
    override val _id: OId<RoleInGroup>,
    override val groupOfUserId: OId<GroupOfUser>,
    override val appRoleId: OId<out IAppRole<*>>,
    override val permission: PermissionType,
    override val crudTaskSet: Set<CrudTask>?,
) : IRoleInGroup<RoleInGroup, GroupOfUser>
