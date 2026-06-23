package com.fonrouge.conformance

import com.fonrouge.base.api.CrudTask
import com.fonrouge.base.api.PermissionEnforcement
import com.fonrouge.base.common.ICommonChangeLog
import com.fonrouge.base.common.ICommonContainer
import com.fonrouge.base.model.IChangeLog
import com.fonrouge.base.model.IUser
import com.fonrouge.base.offsetDateTimeNow
import com.fonrouge.base.state.SimpleState
import com.fonrouge.base.types.OId
import com.fonrouge.fullStack.mongoDb.IChangeLogColl
import com.fonrouge.fullStack.repository.IRolePermissionProvider
import com.fonrouge.fullStack.repository.PermissionRegistry
import io.ktor.server.application.ApplicationCall
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Local (no-Docker) pin for the change-log RBAC exemption (RBAC blueprint P3.2b).
 *
 * P3.2b collapsed the Mongo permission dispatch onto the single registered [PermissionRegistry] provider and
 * removed the special-case `isSubclassOf(IChangeLogColl)` check. The change-log exemption is now declarative:
 * `IChangeLogColl` overrides `permissionEnforcement = Off`, so `Coll.getCrudPermission`'s first guard exempts
 * it. This test pins that — it is the one semantic that lived outside the resolution provider and was easy to
 * lose when collapsing the old `CollPermission`. It needs **no database**: construction derives serializers
 * only, and the exempt `getCrudPermission` path returns before touching the collection.
 */
class MongoChangeLogExemptionTest {

    @AfterTest
    fun resetRegistry() {
        PermissionRegistry.rolePermissionProvider = null
    }

    /**
     * A change-log collection (1) declares [PermissionEnforcement.Off] and (2) is RBAC-exempt: its
     * `getCrudPermission` returns OK even while a **deny** provider is registered — proving changelog writes
     * stay permission-free while ordinary Mongo writes route through [PermissionRegistry].
     */
    @Test
    fun changeLogCollIsRbacExempt() = runTest {
        val changeLogColl = TChangeLogColl()

        assertEquals(
            PermissionEnforcement.Off,
            changeLogColl.permissionEnforcement,
            "IChangeLogColl must declare permissionEnforcement = Off (the RBAC exemption, P3.2b)",
        )

        PermissionRegistry.rolePermissionProvider = object : IRolePermissionProvider {
            override suspend fun getCrudPermission(
                commonContainer: ICommonContainer<*, *, *>,
                call: ApplicationCall,
                crudTask: CrudTask,
            ): SimpleState = SimpleState(isOk = false, msgError = "denied")
        }

        val result = changeLogColl.getCrudPermission(mockk<ApplicationCall>(relaxed = true), CrudTask.Create)
        assertFalse(
            result.hasError,
            "a change-log write must be RBAC-exempt (Off) even with a deny provider registered",
        )
    }

    // ---- minimal first-in-repo IChangeLogColl fixture (no DB) ----

    /** A throwaway user type — only a phantom for the change-log's `U`/`UID` type parameters. */
    private data class TClUser(
        override val _id: OId<TClUser> = OId(),
        override val inactivityUiSecsToNoRefresh: Int? = null,
        override val inactivityUiSecsToLogout: Int? = null,
        override val sessionMaxSecs: Int? = null,
    ) : IUser<OId<TClUser>>

    /**
     * A minimal change-log entity. Only `_id` is a serialized constructor property (the container derives its
     * `_id` serializer at construction); the remaining [IChangeLog] members are body vals — they implement the
     * interface without being serialized, so no `io.kvision` date serializer is required for the entity.
     */
    @Serializable
    private data class TClChangeLog(
        override val _id: OId<IChangeLog<TClUser, OId<TClUser>>> = OId(),
    ) : IChangeLog<TClUser, OId<TClUser>> {
        // Getter-only (no backing field) ⇒ not part of the serial form: only `_id` is serialized, so the
        // entity needs no `io.kvision` date serializer for `dateTime`.
        override val className get() = ""
        override val serializedId get() = ""
        override val dateTime get() = offsetDateTimeNow()
        override val action get() = IChangeLog.Action.Create
        override val clientInfo: String? get() = null
        override val userId: OId<TClUser>? get() = null
        override val userInfo: String? get() = null
        override val data: Map<String, Pair<String?, String?>> get() = emptyMap()
    }

    private object TClChangeLogContainer :
        ICommonChangeLog<TClChangeLog, TClUser, OId<TClUser>>(TClChangeLog::class)

    /**
     * Minimal change-log collection. `userCollFun` and `commonContainerUser` are only used by `buildChangeLog`
     * (never at construction or on the exempt `getCrudPermission` path), so they are intentionally unimplemented.
     */
    private class TChangeLogColl : IChangeLogColl<TClChangeLog, TClUser, OId<TClUser>>(
        commonContainer = TClChangeLogContainer,
        userCollFun = { error("user collection is not used by the exemption pin") },
    ) {
        override val commonContainerUser: ICommonContainer<TClUser, OId<TClUser>, *>
            get() = error("user container is not used by the exemption pin")
        override val userInfo: (TClUser?) -> String = { "" }
    }
}
