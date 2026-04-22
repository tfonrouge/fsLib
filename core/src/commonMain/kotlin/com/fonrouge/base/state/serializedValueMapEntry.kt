package com.fonrouge.base.state

import com.fonrouge.base.model.BaseDoc
import com.fonrouge.base.serializers.FSOffsetDateTimeSerializer
import io.kvision.types.OffsetDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.reflect.KProperty1

/**
 * Builds a single-entry map pairing [property]'s name with [value] encoded as a [JsonElement], suitable for
 * populating [ItemState.serializedValueMap] when seeding a Create form's defaults.
 *
 * The returned [JsonElement] can be any of [kotlinx.serialization.json.JsonPrimitive],
 * [kotlinx.serialization.json.JsonObject], [kotlinx.serialization.json.JsonArray], or
 * [kotlinx.serialization.json.JsonNull] — complex `@Serializable` classes (including nested `BaseDoc` ids) round-trip
 * natively without the double-JSON-escaping that a [String]-encoded map value would impose.
 *
 * [OffsetDateTime] is handled explicitly via [FSOffsetDateTimeSerializer] because it is not itself `@Serializable`;
 * every other type relies on the compiler-generated serializer via the reified [V].
 *
 * **Constraint:** the property's Kotlin name must match its serialized name — i.e. the property must not carry a
 * `@SerialName` annotation that renames it. The map key here is taken verbatim from [KProperty1.name], but the
 * client-side consumer resolves seed keys against the item's serializer descriptor, which indexes by
 * `@SerialName` value (not by Kotlin property name). If the two disagree, the seed silently fails to round-trip:
 * on the wire under one name, on the client look-up under the other. Callers that need renamed fields should
 * either drop `@SerialName` on that field or bypass [serializedValueMapEntry] and build the map key manually
 * using the same name the serializer emits.
 *
 * @param property The property whose name becomes the map key.
 * @param value The value to encode. `null` produces [kotlinx.serialization.json.JsonNull].
 * @return A single-entry [Map] keyed by `property.name` with the encoded [JsonElement].
 */
@Suppress("unused")
inline fun <T : BaseDoc<*>, reified V> serializedValueMapEntry(
    property: KProperty1<in T, V?>,
    value: V?,
): Map<String, JsonElement> = mapOf(
    property.name to when (value) {
        is OffsetDateTime -> Json.encodeToJsonElement(FSOffsetDateTimeSerializer, value)
        else -> Json.encodeToJsonElement<V?>(value)
    }
)
