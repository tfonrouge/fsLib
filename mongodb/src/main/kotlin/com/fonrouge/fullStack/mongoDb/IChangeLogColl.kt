package com.fonrouge.fullStack.mongoDb

import com.fonrouge.base.api.ApiItem
import com.fonrouge.base.api.PermissionEnforcement
import com.fonrouge.base.common.ICommonChangeLog
import com.fonrouge.base.common.ICommonContainer
import com.fonrouge.base.model.BaseDoc
import com.fonrouge.base.model.ChangeLogFilter
import com.fonrouge.base.model.IChangeLog
import com.fonrouge.base.model.IUser
import com.fonrouge.base.offsetDateTimeNow
import com.fonrouge.base.serializers.FSOffsetDateTimeSerializer
import com.fonrouge.base.state.SimpleState
import com.fonrouge.base.types.OId
import com.fonrouge.fullStack.repository.IChangeLogRepository
import io.ktor.server.application.*
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.json.*
import kotlinx.serialization.serializer
import org.bson.conversions.Bson
import org.litote.kmongo.and
import org.litote.kmongo.coroutine.CoroutineCollection
import org.litote.kmongo.descending
import org.litote.kmongo.eq

/**
 * MongoDB-backed change log collection that records CRUD mutations for audit purposes.
 *
 * Implements [IChangeLogRepository] for backend-agnostic change log recording.
 *
 * @param CC The change log container type.
 * @param ChangeLog The change log entity type.
 * @param U The user entity type.
 * @param UID The user identifier type.
 */
abstract class IChangeLogColl<ChangeLog : IChangeLog<U, UID>, U : IUser<UID>, UID : Any>(
    commonContainer: ICommonChangeLog<ChangeLog, U, UID>,
    override val userCollFun: () -> IUserColl<U, UID, *>,
) : Coll<ChangeLog, OId<IChangeLog<U, UID>>, ChangeLogFilter, UID>(
    commonContainer = commonContainer
), IChangeLogRepository {
    abstract val commonContainerUser: ICommonContainer<U, UID, *>
    abstract val userInfo: ((U?) -> String)

    /**
     * Change-log collections are **never user-gated**: entries are system-written audit records, not user
     * CRUD. They therefore declare [PermissionEnforcement.Off] (RBAC D6) — the explicit, declarative RBAC
     * exemption (P3.2b). This replaces the former special-case `isSubclassOf(IChangeLogColl)` check in the
     * Mongo permission path: `getCrudPermission`'s `Off` guard now covers the exemption uniformly across
     * engines, with no dispatch-time type check.
     */
    override val permissionEnforcement: PermissionEnforcement
        get() = PermissionEnforcement.Off

    /**
     * Builds a change log entry for the given action and API item, storing details about changes
     * made to the item, including the user and other contextual information.
     *
     * @param cc The common container managing the items of type T.
     * @param apiItem The API action and item involved in the change.
     * @param orig The original item before the change, if applicable.
     * @return A SimpleState object indicating the success or failure of the operation.
     */
    @OptIn(InternalSerializationApi::class)
    override suspend fun <CC : ICommonContainer<T, ID, *>, T : BaseDoc<ID>, ID : Any> buildChangeLog(
        cc: CC,
        apiItem: ApiItem.Action<T, ID, *>,
        orig: T?
    ): SimpleState {
        if (apiItem.item is IChangeLog<*, *>) return SimpleState(
            isOk = false,
            msgError = "Item is already a change log."
        )
        val user: U? = userCollFun().userFromCall(call = apiItem.call)
        val (items, action) = when (apiItem) {
            is ApiItem.Action.Create<T, ID, *> -> Pair(
                apiItem.item to null,
                IChangeLog.Action.Create,
            )

            is ApiItem.Action.Update<T, ID, *> -> Pair(
                apiItem.item to orig,
                IChangeLog.Action.Update,
            )

            is ApiItem.Action.Delete<T, ID, *> -> Pair(
                apiItem.item to null,
                IChangeLog.Action.Delete,
            )
        }
        val json1 = Json.encodeToJsonElement(cc.itemSerializer, items.first) as JsonObject
        val orig = items.second?.let { Json.encodeToJsonElement(cc.itemSerializer, it) as JsonObject }
        fun getValue(jsonElement: JsonElement?): String? {
            return when (jsonElement) {
                is JsonPrimitive -> if (jsonElement == JsonNull) null else jsonElement.content
                is JsonArray -> jsonElement.toString()
                is JsonObject -> jsonElement.toString()
                else -> null
            }
        }

        val data: Map<String, Pair<String?, String?>> = orig?.let {
            val map = mutableMapOf<String, Pair<String?, String?>>()
            json1.map { entry ->
                val v1 = getValue(entry.value)
                val v2 = getValue(orig[entry.key])
                if (v1 != v2) {
                    map[entry.key] = Pair(v1, v2)
                }
            }
            orig.map { entry ->
                val v1 = getValue(json1[entry.key])
                val v2 = getValue(entry.value)
                if (v1 != v2) {
                    map[entry.key] = Pair(v1, v2)
                }
            }
            map
        } ?: json1.mapValues {
            Pair(
                first = when (it.value) {
                    is JsonArray -> it.value.jsonArray.toString()
                    is JsonObject -> it.value.toString()
                    else -> it.value.jsonPrimitive.content
                },
                second = null
            )
        }
        return if (data.isNotEmpty()) {
            val changeLog = Json.decodeFromJsonElement(
                deserializer = commonContainer.itemKClass.serializer(),
                element = buildJsonObject {
                    put(
                        "serializedId",
                        Json.encodeToString(serializer = cc.idSerializer, value = items.first._id)
                    )
                    put("className", cc.itemKClass.java.simpleName)
                    put("dateTime", Json.encodeToJsonElement(FSOffsetDateTimeSerializer, offsetDateTimeNow()))
                    put("action", Json.encodeToJsonElement(action))
                    put(
                        "clientInfo",
                        apiItem.call?.request?.let { "${it.headers["Origin"]}; ${it.headers["User-Agent"]}" })
                    put(
                        "userId",
                        user?._id?.let {
                            Json.encodeToJsonElement(
                                serializer = commonContainerUser.idSerializer,
                                value = it
                            )
                        } ?: JsonNull)
                    put("userInfo", userInfo(user))
                    put("data", Json.encodeToJsonElement(data))
                }
            )
            insertOne(changeLog).asSimpleState.also {
                if (it.hasError) System.err.println("Error adding change log entry: ${it.msgError}")
            }
        } else SimpleState(isOk = false, msgError = "No data changed.")
    }

    override suspend fun CoroutineCollection<ChangeLog>.indexes() {
        ensureIndex(IChangeLog<*, *>::className, IChangeLog<*, *>::serializedId, IChangeLog<*, *>::dateTime)
    }

    override fun matchStage(call: ApplicationCall?, apiFilter: ChangeLogFilter, resultUnit: ResultUnit): Bson? {
        val matchDoc = mutableListOf<Bson>()
        apiFilter.action?.let { matchDoc += IChangeLog<*, *>::action eq it }
        apiFilter.className?.let { matchDoc += IChangeLog<*, *>::className eq it }
        apiFilter.serializedId?.let { matchDoc += IChangeLog<*, *>::serializedId eq it }
        return and(matchDoc)
    }

    override fun sortStage(call: ApplicationCall?, apiFilter: ChangeLogFilter): Bson? {
        return descending(IChangeLog<*, *>::dateTime)
    }
}
