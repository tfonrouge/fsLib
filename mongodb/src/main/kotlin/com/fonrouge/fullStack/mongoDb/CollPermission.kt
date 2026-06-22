package com.fonrouge.fullStack.mongoDb

import com.fonrouge.base.api.ApiItem
import com.fonrouge.base.api.CrudTask
import com.fonrouge.base.api.IApiFilter
import com.fonrouge.base.api.PermissionEnforcement
import com.fonrouge.base.model.BaseDoc
import com.fonrouge.base.model.IAppRole
import com.fonrouge.base.model.IAppRole.RoleType
import com.fonrouge.base.state.ItemState
import com.fonrouge.base.state.SimpleState
import io.ktor.server.application.*
import org.litote.kmongo.and
import org.litote.kmongo.eq
import kotlin.reflect.full.isSubclassOf

/**
 * Determines the CRUD permissions for a given API item by delegating to the call-based overload.
 *
 * @param apiItem The API item containing the call and CRUD task context.
 * @return A [SimpleState] indicating whether the operation is permitted.
 */
internal suspend fun <T : BaseDoc<ID>, ID : Any, FILT : IApiFilter<*>, UID : Any> Coll<T, ID, FILT, UID>.checkCrudPermission(
    apiItem: ApiItem<T, ID, FILT>,
): SimpleState =
    apiItem.call?.let { call -> checkCrudPermission(call, apiItem.crudTask) } ?: SimpleState(isOk = true)

/**
 * Determines the CRUD permission for a specific call and task by checking role-based access control.
 *
 * @param call The ApplicationCall associated with the request.
 * @param crudTask The specific CRUD task for which permission is being checked.
 * @return A [SimpleState] indicating whether the permission check was successful.
 */
internal suspend fun <T : BaseDoc<ID>, ID : Any, FILT : IApiFilter<*>, UID : Any> Coll<T, ID, FILT, UID>.checkCrudPermission(
    call: ApplicationCall,
    crudTask: CrudTask,
): SimpleState {
    // D6: an explicitly non-enforcing repository always allows.
    if (permissionEnforcement == PermissionEnforcement.Off) return SimpleState(isOk = true)
    // Change-log collections are exempt from RBAC.
    if (this::class.isSubclassOf(IChangeLogColl::class)) return SimpleState(isOk = true)
    // D6: enforcing, but RBAC not wired (no roleInUserColl) → fail CLOSED (was: `?: SimpleState(isOk = true)`, fail-open R7).
    val roleInUserColl = Coll.roleInUserColl
        ?: return SimpleState(isOk = false, msgError = "Permission enforcement is on but RBAC (roleInUserColl) is not wired")
    val matchDoc = and(
        IAppRole<*>::roleType eq RoleType.CrudTask,
        IAppRole<*>::classOwner eq commonContainer.name
    )
    return roleInUserColl.permissionState(
        call = call,
        roleType = RoleType.CrudTask,
        crudTask = crudTask,
    ) {
        // D4 (side-effect-free): resolution does NOT provision. A missing role denies; roles are
        // provisioned explicitly at boot via IAppRoleColl.ensureRoles(...). (Was: `?: insertCrudRole(...)`.)
        roleInUserColl.appRoleColl.findOne(matchDoc)?.let {
            ItemState(item = it)
        } ?: ItemState(
            isOk = false,
            msgError = "App role doesn't exist '${commonContainer.name}' for ${commonContainer.labelItem} item."
        )
    }
}
