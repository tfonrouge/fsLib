package com.fonrouge.fullStack.view

import com.fonrouge.base.serializers.IntIdSerializer
import com.fonrouge.base.serializers.LongIdSerializer
import com.fonrouge.base.serializers.OIdSerializer
import com.fonrouge.base.serializers.StringIdSerializer
import com.fonrouge.base.types.IntId
import com.fonrouge.base.types.LongId
import com.fonrouge.base.types.OId
import com.fonrouge.base.types.StringId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

/*
 * Pure encoding helpers behind [ViewItem.addSerializedValue]. Extracted to top-level functions
 * (same pattern as `ViewItemSeedDecode.kt`) so tests can pin the contract without constructing a
 * [ViewItem], and so their independence from [ViewItem] state is explicit.
 */

/**
 * Fast-path JSON encoding for the `IBaseId` family ([OId] / [StringId] / [IntId] / [LongId]),
 * consulted by [ViewItem.addSerializedValue] **before** reified serializer resolution.
 *
 * Why it exists: the id types are phantom-typed — their serializers ignore the type argument
 * entirely and encode the raw `id` primitive. But reified resolution (`serializer<V>()`) walks the
 * *declared* property type, and a star-projected generic (a polymorphic FK such as
 * `OId<out IHeader<*>>`) makes it throw `IllegalArgumentException: Star projections in type
 * arguments are not allowed` **at runtime**, aborting the display cycle with a blank form even
 * though the value itself is trivially encodable. Dispatching on the concrete id class and
 * delegating to the **real** serializer sidesteps reified resolution while keeping a single source
 * of encoding truth (no duplicated encoding logic to drift).
 *
 * @return The encoded primitive for id-family values; `null` for anything else (caller falls back
 *   to reified resolution).
 */
@PublishedApi
internal fun encodeBaseIdOrNull(value: Any?): JsonElement? {
    @Suppress("UNCHECKED_CAST")
    return when (value) {
        is OId<*> -> Json.encodeToJsonElement(OIdSerializer as KSerializer<OId<*>>, value)
        is StringId<*> -> Json.encodeToJsonElement(StringIdSerializer as KSerializer<StringId<*>>, value)
        is IntId<*> -> Json.encodeToJsonElement(IntIdSerializer as KSerializer<IntId<*>>, value)
        is LongId<*> -> Json.encodeToJsonElement(LongIdSerializer as KSerializer<LongId<*>>, value)
        else -> null
    }
}

/**
 * Encoding pipeline of [ViewItem.addSerializedValue]: [encodeBaseIdOrNull] fast path first, then
 * reified `serializer<V>()` resolution.
 *
 * The residual reified path wraps kotlinx's `IllegalArgumentException` ("Star projections in type
 * arguments are not allowed") with an **actionable** message naming the property and pointing to
 * the non-reified `addSerializedValue(property, JsonElement)` overload — the raw failure mode is a
 * blank form with a generic exception, which cost a real debugging session downstream
 * (mppArel `RutaProcesoPaso`, 2026-07-07).
 */
@PublishedApi
internal inline fun <reified V> encodeHiddenFieldValue(propertyName: String, value: V): JsonElement =
    encodeBaseIdOrNull(value) ?: try {
        Json.encodeToJsonElement(value)
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException(
            "addSerializedValue('$propertyName'): reified serializer resolution failed for the declared " +
                "property type — typically a star-projected generic (e.g. a polymorphic FK like " +
                "`OId<out IHeader<*>>`). Use the non-reified overload " +
                "`addSerializedValue(property, element: JsonElement)` and encode the value yourself " +
                "(for an id: `JsonPrimitive(value.id)`). Root cause: ${e.message}",
            e,
        )
    }
