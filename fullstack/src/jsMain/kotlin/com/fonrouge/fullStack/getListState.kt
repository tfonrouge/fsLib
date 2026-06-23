package com.fonrouge.fullStack

import com.fonrouge.base.api.ApiList
import com.fonrouge.base.api.IApiFilter
import com.fonrouge.base.common.ICommonContainer
import com.fonrouge.base.model.BaseDoc
import com.fonrouge.base.state.ListState
import com.fonrouge.base.state.State
import com.fonrouge.fullStack.config.ConfigViewList
import io.kvision.core.KVScope
import dev.kilua.rpc.CallAgent
import kotlinx.coroutines.asPromise
import kotlinx.coroutines.async
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlin.js.Promise

/**
 * Retrieves a transformed representation of the state of a list retrieved from an API function.
 *
 * @param apiListFun A suspend function specifying the API call that retrieves the list state.
 * @param apiList An instance of ApiList containing the filter, sorter, and pagination details for the API call. Defaults to a new ApiList with the default filter.
 * @param transform A transformation function to convert the retrieved ListState into the desired return format.
 * @return A Promise that resolves to the result of the transformation function applied to the ListState.
 */
@Suppress("unused")
@OptIn(ExperimentalSerializationApi::class)
fun <T : BaseDoc<ID>, ID : Any, FILT : IApiFilter<*>, AIS : Any, R : Any> ICommonContainer<T, ID, FILT>.getListState(
    apiListFun: suspend AIS.(ApiList<FILT>) -> ListState<T>,
    apiFilter: FILT = apiFilterInstance(),
    transform: (ListState<T>) -> R,
): Promise<R> {
    val (url, method) = ConfigViewList.serviceManager.requireCall(apiListFun)
    val kvCallAgent = CallAgent()
    val apiListSerialized = Json.encodeToString(ApiList.serializer(apiFilterSerializer), ApiList(apiFilter = apiFilter))
    val paramList = listOf(apiListSerialized)
    return KVScope.async {
        try {
            transform(
                Json.decodeFromString(
                    deserializer = ListState.serializer(itemSerializer),
                    string = kvCallAgent.jsonRpcCall(url = url, data = paramList, method = method)
                )
            )
        } catch (e: Exception) {
            transform(
                ListState(
                    data = listOf(),
                    last_page = null,
                    last_row = null,
                    state = State.Error,
                    msgError = e.message ?: "Unknown error"
                )
            )
        }
    }.asPromise()
}
