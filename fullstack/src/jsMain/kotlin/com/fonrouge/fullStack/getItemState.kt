package com.fonrouge.fullStack

import com.fonrouge.base.api.ApiItem
import com.fonrouge.base.api.IApiFilter
import com.fonrouge.base.api.IApiItem
import com.fonrouge.base.common.ICommonContainer
import com.fonrouge.base.common.toIApiItem
import com.fonrouge.base.model.BaseDoc
import com.fonrouge.base.state.ItemState
import com.fonrouge.fullStack.config.ConfigViewItem
import io.kvision.core.KVScope
import dev.kilua.rpc.CallAgent
import kotlinx.coroutines.asPromise
import kotlinx.coroutines.async
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlin.js.Promise

/**
 * Retrieves the state of a specific item and applies a transformation function to the result.
 *
 * @param T The type of the item, which must extend [BaseDoc].
 * @param ID The type of the item ID, which must be a non-nullable type.
 * @param FILT The type of the API filter used for querying, must extend [IApiFilter].
 * @param R The return type of the transformation function.
 *
 * @param apiItemFun The function representing the API call to retrieve the item state.
 * @param apiItem The item for which the state is queried.
 * @param transform A transformation function applied to the retrieved [ItemState] to produce the result.
 * @return A [Promise] containing the transformed result of type [R].
 */
@OptIn(ExperimentalSerializationApi::class)
fun <T : BaseDoc<ID>, ID : Any, FILT : IApiFilter<*>, R : Any?> ICommonContainer<T, ID, FILT>.getItemState(
    apiItemFun: Function<*>,
    apiItem: ApiItem<T, ID, FILT>,
    transform: (ItemState<T>) -> R,
): Promise<R> {
    val (url, method) = ConfigViewItem.serviceManager.requireCall(apiItemFun)
    val kvCallAgent = CallAgent()
    val iApiItem = toIApiItem(apiItem)
    val apiItemSerialized =
        Json.encodeToString(IApiItem.serializer(itemSerializer, idSerializer, apiFilterSerializer), iApiItem)
    val paramList = listOf(apiItemSerialized)
    return KVScope.async {
        transform(
            Json.decodeFromString<ItemState<T>>(
                ItemState.serializer(this@getItemState.itemSerializer),
                kvCallAgent.jsonRpcCall(url, paramList, method)
            )
        )
    }.asPromise()
}
