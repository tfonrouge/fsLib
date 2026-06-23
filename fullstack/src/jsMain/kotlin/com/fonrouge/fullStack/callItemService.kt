package com.fonrouge.fullStack

import com.fonrouge.base.api.CallType
import com.fonrouge.base.api.CrudTask
import com.fonrouge.base.api.IApiFilter
import com.fonrouge.base.api.IApiItem
import com.fonrouge.base.common.ICommonContainer
import com.fonrouge.base.common.toIApiItem
import com.fonrouge.base.model.BaseDoc
import com.fonrouge.base.state.ItemState
import com.fonrouge.fullStack.config.ConfigViewItem
import io.kvision.core.KVScope
import dev.kilua.rpc.CallAgent
import io.kvision.toast.Toast
import io.kvision.toast.ToastOptions
import io.kvision.toast.ToastPosition
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/**
 * Executes a service call for an API item within the container context.
 *
 * @param apiItemFun A reference to the function representing the API operation.
 * @param crudTask The type of CRUD operation to perform (Create, Read, Update, Delete).
 * @param callType The type of API call (Query or Action).
 * @param id The unique identifier of the item, or null if not applicable.
 * @param item The item to be used for the operation, or null if not applicable.
 * @param apiFilter The API filter used to customize queries, defaults to an instance created from the container's apiFilterInstance method.
 * @param block A lambda to process the result of the operation, receiving the item state as input and returning the modified item state.
 */
@OptIn(ExperimentalSerializationApi::class)
fun <T : BaseDoc<ID>, ID : Any, FILT : IApiFilter<*>> ICommonContainer<T, ID, FILT>.callItemService(
    apiItemFun: Function<*>,
    crudTask: CrudTask,
    callType: CallType,
    id: ID? = null,
    item: T? = null,
    apiFilter: FILT = apiFilterInstance(),
    block: (ItemState<T>) -> ItemState<T>,
) {
    val (url, method) = ConfigViewItem.serviceManager.requireCall(apiItemFun)
    val kvCallAgent = CallAgent()
    val iApiItem = when (callType) {
        CallType.Query -> when (crudTask) {
            CrudTask.Create -> apiItemQueryCreate(id = id, apiFilter = apiFilter)
            CrudTask.Read -> id?.let { apiItemQueryRead(id = id, apiFilter = apiFilter) }
            CrudTask.Update -> id?.let { apiItemQueryUpdate(id = id, apiFilter = apiFilter) }
            CrudTask.Delete -> id?.let { apiItemQueryDelete(id = id, apiFilter = apiFilter) }
        }

        CallType.Action -> when (crudTask) {
            CrudTask.Create -> item?.let {
                apiItemActionCreate(
                    item,
                    apiFilter
                )
            }

            CrudTask.Read -> null
            CrudTask.Update -> item?.let {
                apiItemActionUpdate(
                    item,
                    apiFilter,
                )
            }

            CrudTask.Delete -> item?.let {
                apiItemActionDelete(
                    item,
                    apiFilter
                )
            }
        }
    } ?: return
    val paramList = listOf(
        Json.encodeToString(
            serializer = IApiItem.serializer(
                itemSerializer,
                idSerializer,
                apiFilterSerializer
            ),
            value = toIApiItem(iApiItem)
        ),
    )
    KVScope.launch {
        try {
            val jsonString = kvCallAgent.jsonRpcCall(url = url, data = paramList, method = method)
            try {
                val itemResponse: ItemState<T> =
                    Json.decodeFromString(
                        ItemState.serializer(itemSerializer),
                        jsonString
                    )
                block(itemResponse)
            } catch (e: Exception) {
                console.error(
                    "Error decoding KClass",
                    itemSerializer,
                    "with serialized value",
                    jsonString,
                    "exception:",
                    e
                )
                e.printStackTrace()
            }
        } catch (e: Exception) {
            console.error("Server error:", e.message)
            Toast.danger(
                message = "Server error ${e.message}",
                options = ToastOptions(
                    position = ToastPosition.BOTTOMRIGHT,
                    escapeHtml = true,
                    duration = 10000,
                    stopOnFocus = true,
                    newWindow = true
                )
            )
        }
    }
}
