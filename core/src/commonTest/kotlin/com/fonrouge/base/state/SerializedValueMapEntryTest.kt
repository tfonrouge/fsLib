package com.fonrouge.base.state

import com.fonrouge.base.model.BaseDoc
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.contentOrNull
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for [serializedValueMapEntry], covering primitive, null, enum, list, and nested class-object seeds.
 */
class SerializedValueMapEntryTest {

    @Serializable
    enum class Role { Admin, User }

    @Serializable
    data class NestedId(
        val tenant: String,
        val counter: Int,
        val tag: String? = null,
    )

    @Serializable
    data class Doc(
        override val _id: NestedId,
        val name: String,
        val age: Int,
        val active: Boolean,
        val role: Role,
        val tags: List<String>,
        val optional: String? = null,
    ) : BaseDoc<NestedId>

    @Test
    fun primitiveStringProducesJsonPrimitive() {
        val entry = serializedValueMapEntry(Doc::name, "hello")
        assertEquals(JsonPrimitive("hello"), entry["name"])
    }

    @Test
    fun primitiveIntAndBoolProduceTypedJsonPrimitives() {
        val intEntry = serializedValueMapEntry(Doc::age, 42)
        assertEquals(42, (intEntry["age"] as JsonPrimitive).int)

        val boolEntry = serializedValueMapEntry(Doc::active, true)
        assertEquals(true, (boolEntry["active"] as JsonPrimitive).boolean)
    }

    @Test
    fun nullValueProducesJsonNull() {
        val entry = serializedValueMapEntry(Doc::optional, null)
        assertEquals(JsonNull, entry["optional"])
    }

    @Test
    fun enumValueProducesJsonPrimitiveWithName() {
        val entry = serializedValueMapEntry(Doc::role, Role.Admin)
        assertEquals("Admin", (entry["role"] as JsonPrimitive).contentOrNull)
    }

    @Test
    fun listValueProducesJsonArray() {
        val entry = serializedValueMapEntry(Doc::tags, listOf("a", "b", "c"))
        val array = entry["tags"]
        assertIs<JsonArray>(array)
        assertEquals(3, array.size)
        assertEquals(JsonPrimitive("a"), array[0])
    }

    @Test
    fun nestedClassObjectProducesJsonObjectAndRoundTrips() {
        val id = NestedId(tenant = "acme", counter = 7, tag = "priority")
        val entry = serializedValueMapEntry(Doc::_id, id)

        val element = entry["_id"]
        assertIs<JsonObject>(element)
        assertEquals(JsonPrimitive("acme"), element["tenant"])
        assertEquals(JsonPrimitive(7), element["counter"])
        assertEquals(JsonPrimitive("priority"), element["tag"])

        val roundTripped = Json.decodeFromJsonElement(NestedId.serializer(), element)
        assertEquals(id, roundTripped)
    }

    @Test
    fun nestedClassObjectWithNullDefaultRoundTrips() {
        // Kotlinx default `encodeDefaults = false`: fields equal to their default (including `tag = null`)
        // are omitted from the JsonObject. The round-trip through decodeFromJsonElement still restores them.
        val id = NestedId(tenant = "acme", counter = 7, tag = null)
        val entry = serializedValueMapEntry(Doc::_id, id)

        val element = entry["_id"]
        assertIs<JsonObject>(element)
        assertTrue("tag" !in element)

        val roundTripped = Json.decodeFromJsonElement(NestedId.serializer(), element)
        assertEquals(id, roundTripped)
    }

    @Test
    fun entryIntegratesWithItemState() {
        val entry = serializedValueMapEntry(Doc::name, "seeded")
        val state = ItemState<Doc>(serializedValueMap = entry)
        assertEquals(State.Ok, state.state)
        assertTrue(state.serializedValueMap!!.containsKey("name"))
    }
}
