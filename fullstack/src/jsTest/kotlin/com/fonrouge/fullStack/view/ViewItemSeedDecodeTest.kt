package com.fonrouge.fullStack.view

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the pure seed-decode helpers in `ViewItemSeedDecode.kt`.
 *
 * Why these tests matter: the helpers were refactored multiple times to avoid the Kotlin/JS `Long`-boxing
 * trap that made server-seeded integers submit as `{"low_1":…,"high_1":…}` object literals and crash the
 * submit-time kotlinx decode. [intAboveMaxBecomesDouble] is the regression guard — if any future refactor
 * brings back `longOrNull` in the chain, this test fails loudly.
 *
 * The suite also covers the KDoc-documented contract of [decodePrimitiveOrParse] (Int / Double / String /
 * Boolean primitive unwrapping, non-primitive JSON.parse fallback) and [jsonElementToSubmitValue]'s
 * [JsonNull] → `null` layer.
 */
class ViewItemSeedDecodeTest {

    // region decodePrimitiveOrParse — primitive numeric branch

    /**
     * Integer literals that fit in [Int] unwrap as Kotlin/JS `Int`, which compiles to a bare JS `number`
     * and round-trips through `JSON.stringify` with no boxing.
     */
    @Test
    fun intBelowMaxStaysInt() {
        val result = decodePrimitiveOrParse(JsonPrimitive(42))
        assertEquals(42, result)
        assertTrue(result is Int, "expected Int, got ${result?.let { it::class.simpleName }}")
    }

    /**
     * Integer literals above [Int.MAX_VALUE] **must** fall through to [Double] — not [Long]. Kotlin/JS
     * represents `Long` as a `{low_, high_}` boxed object whose `JSON.stringify` output crashes the server
     * decode. This test locks in the current design and would catch any regression that reintroduces
     * `longOrNull` into the decode chain.
     */
    @Test
    fun intAboveMaxBecomesDouble() {
        val result = decodePrimitiveOrParse(JsonPrimitive(Int.MAX_VALUE + 1L))
        assertEquals(2147483648.0, result)
        assertTrue(result is Double, "expected Double, got ${result?.let { it::class.simpleName }}")
    }

    /**
     * Fractional literals unwrap as [Double] (integer branch returns null, double branch wins).
     */
    @Test
    fun fractionalStaysDouble() {
        val result = decodePrimitiveOrParse(JsonPrimitive(1.5))
        assertEquals(1.5, result)
        assertTrue(result is Double)
    }

    /**
     * Values past `2^53` are documented to lose precision (same limit as the browser's native `JSON.parse`).
     * This test asserts the current behavior so the contract is visible in the suite; if precision matters,
     * callers are instructed in the KDoc to transport the value as a [String].
     */
    @Test
    fun longBeyondSafeIntegerLosesPrecisionViaDouble() {
        // 2^53 + 1 rounds to 2^53 when stored in a Double — this is expected and documented.
        val bigLong = (1L shl 53) + 1L
        val result = decodePrimitiveOrParse(JsonPrimitive(bigLong))
        assertTrue(result is Double)
        assertEquals((1L shl 53).toDouble(), result)
    }

    // endregion

    // region decodePrimitiveOrParse — string / boolean branches

    /**
     * String primitives are returned as Kotlin [String] via the `element.isString` short-circuit, *before*
     * numeric unwrapping is attempted. A string that happens to look like an integer (`"42"`) stays a
     * string — type preservation across the wire.
     */
    @Test
    fun stringKeptAsString() {
        assertEquals("42", decodePrimitiveOrParse(JsonPrimitive("42")))
        assertEquals("hello", decodePrimitiveOrParse(JsonPrimitive("hello")))
        assertEquals("", decodePrimitiveOrParse(JsonPrimitive("")))
    }

    /**
     * Boolean primitives unwrap via [JsonPrimitive.booleanOrNull] after int and double branches return
     * null — `"true"`/`"false"` content doesn't parse as either.
     */
    @Test
    fun booleanKeptAsBoolean() {
        assertEquals(true, decodePrimitiveOrParse(JsonPrimitive(true)))
        assertEquals(false, decodePrimitiveOrParse(JsonPrimitive(false)))
    }

    // endregion

    // region decodePrimitiveOrParse — non-primitive fallback (JSON.parse)

    /**
     * Non-primitive [kotlinx.serialization.json.JsonElement]s (objects, arrays) flatten through
     * `JSON.parse(element.toString())` into JS-native `dynamic` values that form controls can consume
     * opaquely.
     */
    @Test
    fun jsonObjectParsesIntoDynamic() {
        val obj = buildJsonObject {
            put("x", 7)
            put("s", "hi")
            put("f", 1.5)
        }
        val result: dynamic = decodePrimitiveOrParse(obj)
        assertEquals(7, result.x as Int)
        assertEquals("hi", result.s as String)
        assertEquals(1.5, result.f as Double)
    }

    @Test
    fun jsonArrayParsesIntoDynamic() {
        val arr = JsonArray(
            listOf(JsonPrimitive(1), JsonPrimitive("two"), JsonPrimitive(3.0))
        )
        val result: dynamic = decodePrimitiveOrParse(arr)
        assertEquals(3, result.length as Int)
        assertEquals(1, result[0] as Int)
        assertEquals("two", result[1] as String)
        assertEquals(3.0, result[2] as Double)
    }

    // endregion

    // region jsonElementToSubmitValue — JsonNull layer

    /**
     * [JsonNull] maps to Kotlin `null` via the typed layer — this is the single source of truth both
     * `applyServerSeeds` and the submission overlay route through, so the same seed produces the same
     * runtime value regardless of which consumer path handles it.
     */
    @Test
    fun jsonNullThroughSubmitValueIsNull() {
        assertNull(jsonElementToSubmitValue(JsonNull))
    }

    /**
     * Every non-null value routed through `jsonElementToSubmitValue` must match what
     * [decodePrimitiveOrParse] would return directly — the layer only adds the `JsonNull` short-circuit.
     */
    @Test
    fun submitValueMatchesDecodeForNonNull() {
        assertEquals(decodePrimitiveOrParse(JsonPrimitive(42)), jsonElementToSubmitValue(JsonPrimitive(42)))
        assertEquals(decodePrimitiveOrParse(JsonPrimitive("x")), jsonElementToSubmitValue(JsonPrimitive("x")))
        assertEquals(decodePrimitiveOrParse(JsonPrimitive(true)), jsonElementToSubmitValue(JsonPrimitive(true)))
    }

    /**
     * `JsonNull` is a [JsonPrimitive] subtype in kotlinx — a frequent source of confusion — so passing it
     * directly to [decodePrimitiveOrParse] bypasses the non-primitive branch, fails every unwrap
     * ([JsonPrimitive.isString]/`intOrNull`/`doubleOrNull`/`booleanOrNull` all reject content `"null"`),
     * and hits the trip-wire `error(...)`. This test locks in that contract: callers **must** route
     * through [jsonElementToSubmitValue] to handle `JsonNull` safely. If a future refactor adds an
     * explicit guard (e.g., `if (element is JsonNull) return null`), update this test alongside the
     * KDoc.
     */
    @Test
    fun jsonNullDirectlyIntoDecodeThrowsTripWire() {
        assertFailsWith<IllegalStateException> {
            decodePrimitiveOrParse(JsonNull)
        }
    }

    // endregion
}
