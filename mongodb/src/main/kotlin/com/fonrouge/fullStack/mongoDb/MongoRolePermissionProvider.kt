package com.fonrouge.fullStack.mongoDb

import com.fonrouge.base.api.CrudTask
import com.fonrouge.base.common.ICommonContainer
import com.fonrouge.base.model.IAppRole
import com.fonrouge.base.state.ItemState
import com.fonrouge.base.state.SimpleState
import com.fonrouge.fullStack.repository.IRolePermissionProvider
import io.ktor.server.application.*
import org.litote.kmongo.and
import org.litote.kmongo.eq

/**
 * MongoDB-backed implementation of [IRolePermissionProvider].
 *
 * Delegates CRUD permission checks to the [IRoleInUserColl] system, which manages
 * role-based access control through MongoDB collections.
 *
 * @param roleInUserColl The role-in-user collection providing permission state resolution.
 */
internal class MongoRolePermissionProvider(
    private val roleInUserColl: IRoleInUserColl<*, *, *, *, *, *>,
) : IRolePermissionProvider {

    override suspend fun getCrudPermission(
        commonContainer: ICommonContainer<*, *, *>,
        call: ApplicationCall,
        crudTask: CrudTask,
    ): SimpleState {
        val matchDoc = and(
            IAppRole<*>::roleType eq IAppRole.RoleType.CrudTask,
            IAppRole<*>::classOwner eq commonContainer.name
        )
        return roleInUserColl.permissionState(
            call = call,
            roleType = IAppRole.RoleType.CrudTask,
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
}
