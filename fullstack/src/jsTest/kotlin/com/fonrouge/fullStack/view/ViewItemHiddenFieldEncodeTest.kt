package com.fonrouge.fullStack.view

import com.fonrouge.base.serializers.OIdSerializer
import com.fonrouge.base.types.IntId
import com.fonrouge.base.types.LongId
import com.fonrouge.base.types.OId
import com.fonrouge.base.types.StringId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Stand-in for a polymorphic header interface (mirrors mppArel's `IRutaProceso<*>`). */
private interface Header<X>

/** Generic `@Serializable` payload used to exercise the residual reified path with a star projection. */
@Serializable
private data class Wrap<X>(val s: String)

/**
 * Pins the encoding contract behind [ViewItem.addSerializedValue] (`ViewItemHiddenFieldEncode.kt`).
 *
 * Regression guard for the star-projection trap: a polymorphic FK property (real case: mppArel
 * `RutaProcesoPaso::rutaProcesoId: OId<out IRutaProceso<*>>`, 2026-07-07) made reified
 * `serializer<V>()` throw `IllegalArgumentException: Star projections in type arguments are not
 * allowed` at RUNTIME inside `onBeforeDisplayForm`, leaving a blank form with no compile-time
 * signal. The `IBaseId` fast path must keep the canonical parent-ID idiom working for those types
 * — delegating to the REAL id serializers (no duplicated encoding logic) — and the residual
 * reified path must fail with an actionable message pointing to the `JsonElement` overload.
 */
class ViewItemHiddenFieldEncodeTest {

    // region encodeBaseIdOrNull — fast path delegates to the real serializers

    @Test
    fun oidFastPathMatchesRealSerializer() {
        val oid = OId<Any>("6a4e9017a0e1840d5d92fb0f")
        // Equivalence with the real serializer — if OIdSerializer's encoding ever changes shape,
        // this fails loudly instead of the fast path silently diverging.
        assertEquals(Json.encodeToJsonElement(OIdSerializer, oid), encodeBaseIdOrNull(oid))
        assertEquals(JsonPrimitive("6a4e9017a0e1840d5d92fb0f"), encodeBaseIdOrNull(oid))
    }

    @Test
    fun wholeIdFamilyEncodesTheRawIdPrimitive() {
        assertEquals(JsonPrimitive("abc"), encodeBaseIdOrNull(StringId<Any>("abc")))
        assertEquals(JsonPrimitive(7), encodeBaseIdOrNull(IntId<Any>(7)))
        assertEquals(JsonPrimitive(7L), encodeBaseIdOrNull(LongId<Any>(7L)))
    }

    @Test
    fun nonIdValuesFallThroughToReifiedResolution() {
        assertNull(encodeBaseIdOrNull("plain"))
        assertNull(encodeBaseIdOrNull(42))
        assertNull(encodeBaseIdOrNull(null))
    }

    // endregion

    // region encodeHiddenFieldValue — full pipeline

    @Test
    fun starProjectedIdEncodesViaFastPath() {
        // THE regression case: reified resolution for OId<out Header<*>> throws at runtime;
        // the fast path must sidestep it and produce the raw id primitive.
        val oid: OId<out Header<*>> = OId("6a4e9017a0e1840d5d92fb0f")
        val encoded = encodeHiddenFieldValue("rutaProcesoId", oid)
        assertEquals(JsonPrimitive("6a4e9017a0e1840d5d92fb0f"), encoded)
    }

    @Test
    fun starProjectedNonIdThrowsActionableError() {
        val wrap: Wrap<out List<*>> = Wrap("x")
        val e = assertFailsWith<IllegalArgumentException> {
            encodeHiddenFieldValue("someProp", wrap)
        }
        val msg = e.message ?: ""
        assertTrue("someProp" in msg, "message should name the property, got: $msg")
        assertTrue("JsonElement" in msg, "message should point to the non-reified overload, got: $msg")
    }

    @Test
    fun plainReifiedPathStillWorks() {
        assertEquals(JsonPrimitive("hola"), encodeHiddenFieldValue("p", "hola"))
        assertEquals(JsonPrimitive(3), encodeHiddenFieldValue("p", 3))
    }

    // endregion
}
