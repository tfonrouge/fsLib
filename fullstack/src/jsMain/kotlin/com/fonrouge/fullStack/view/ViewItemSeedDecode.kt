package com.fonrouge.fullStack.view

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Pure [JsonElement]-to-runtime-value converters used by [ViewItem]'s Create-seed dispatch and by its
 * submission overlay. Extracted to top-level `internal` functions so tests can exercise them without
 * constructing a [ViewItem] and so the functions' independence from [ViewItem] state is explicit.
 *
 * The contract:
 * - [decodePrimitiveOrParse] unwraps a [JsonElement] into the closest JS-native value a KVision form
 *   control can consume directly.
 * - [jsonElementToSubmitValue] layers [JsonNull] → `null` on top of [decodePrimitiveOrParse] so every
 *   submission path (form controls in [ViewItem.applyServerSeeds], overlay entries in
 *   [ViewItem.viewFormPanel]) produces identical values for identical seeds.
 */

/**
 * Unwraps a [JsonElement] into the JS-native value a KVision form control expects. The cast order is
 * deliberate:
 *
 * - Integer literals ≤ [Int.MAX_VALUE] → [Int] (Kotlin/JS compiles as JS `number`, round-trips cleanly
 *   through `JSON.stringify`).
 * - Any other number → [Double]. Kotlin/JS boxes [Long] as a `{low_, high_}` object that `JSON.stringify`
 *   emits as `{"low_1":…,"high_1":…}` — feeding such a boxed [Long] to a widget whose model field is
 *   numeric makes [io.kvision.form.FormPanel.getData] emit an object literal in place of a number, and
 *   the submit-time kotlinx decode blows up with *"Expected numeric literal"*. Routing through [Double]
 *   preserves integer precision up to `2^53` and stays as a JS `number`; values beyond `2^53` lose the
 *   last bits of precision — same limit the browser's `JSON.parse` has, so this is not a regression. If
 *   a field genuinely needs precision past `2^53`, transport it as a [String].
 * - Booleans → [Boolean].
 * - Non-primitive [JsonElement]s (objects / arrays) → `JSON.parse(element.toString())`, returning a
 *   JS-native `dynamic` the form control can store opaquely.
 *
 * [JsonNull] must **not** be passed in. In kotlinx's class hierarchy `JsonNull` is itself a [JsonPrimitive]
 * (a common source of confusion) whose `content` is `"null"` — which doesn't parse as string, int, double,
 * or boolean, so it hits the trip-wire and throws [IllegalStateException]. Always route seeds through
 * [jsonElementToSubmitValue], which applies the `JsonNull → null` guard before delegating here.
 */
internal fun decodePrimitiveOrParse(element: JsonElement): Any? {
    if (element !is JsonPrimitive) return JSON.parse<Any?>(element.toString())
    if (element.isString) return element.content
    element.intOrNull?.let { return it }
    element.doubleOrNull?.let { return it }
    element.booleanOrNull?.let { return it }
    // Unreachable under kotlinx.serialization's parser — a non-string [JsonPrimitive] must be one of
    // int / long / double / boolean. Kept as a loud trip-wire in case a future kotlinx release
    // introduces a new primitive subtype or a caller hand-constructs a malformed [JsonPrimitive].
    error("decodePrimitiveOrParse: JsonPrimitive content '${element.content}' is neither string, int, double, nor boolean")
}

/**
 * Converts a wire-format [JsonElement] into the runtime value both [ViewItem.applyServerSeeds] and
 * [ViewItem.viewFormPanel]'s overlay use for submission. Unifies [JsonNull] → `null` with the typed
 * primitive decode so the same seed produces the same Kotlin value regardless of which path consumes
 * it.
 */
internal fun jsonElementToSubmitValue(element: JsonElement): Any? =
    if (element is JsonNull) null else decodePrimitiveOrParse(element)
