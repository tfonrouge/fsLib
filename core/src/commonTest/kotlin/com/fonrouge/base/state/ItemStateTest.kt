package com.fonrouge.base.state

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

/**
 * Tests for ItemState and its conversions.
 */
class ItemStateTest {

    @Test
    fun itemStateWithItem() {
        val state = ItemState(item = "hello")
        assertEquals(State.Ok, state.state)
        assertEquals("hello", state.item)
        assertFalse(state.hasError)
    }

    @Test
    fun itemStateWithNullItemAndMap() {
        val map = mapOf("field1" to JsonPrimitive("value1"))
        val state = ItemState<String>(serializedValueMap = map)
        assertEquals(State.Ok, state.state)
        assertNull(state.item)
        assertEquals(map, state.serializedValueMap)
    }

    @Test
    fun itemStateWithNoItemOrMap() {
        val state = ItemState<String>()
        assertEquals(State.Error, state.state)
        assertNull(state.item)
        assertNull(state.serializedValueMap)
    }

    @Test
    fun itemStateErrorWithMessage() {
        val state = ItemState<String>(isOk = false, msgError = "not found")
        assertEquals(State.Error, state.state)
        assertTrue(state.hasError)
        assertEquals("not found", state.msgError)
    }

    @Test
    fun itemStateOkBoolean() {
        val state = ItemState<String>(isOk = true, msgOk = "great")
        assertEquals(State.Ok, state.state)
        assertEquals("great", state.msgOk)
        assertFalse(state.hasError)
    }

    @Test
    fun itemStateWarning() {
        val state = ItemState<String>(msgWarn = "be careful")
        assertEquals(State.Warn, state.state)
        assertEquals("be careful", state.msgError)
        assertFalse(state.hasError)
    }

    @Test
    fun itemStateAsSimpleState() {
        val itemState = ItemState(item = "data", msgOk = "ok")
        val simple = itemState.asSimpleState
        assertEquals(itemState.state, simple.state)
        assertEquals(itemState.msgOk, simple.msgOk)
        assertEquals(itemState.msgError, simple.msgError)
    }

    @Test
    fun simpleStateToItemState() {
        val simple = SimpleState(isOk = true, msgOk = "done")
        val itemState = ItemState<String>(simple)
        assertEquals(simple.state, itemState.state)
        assertEquals(simple.msgOk, itemState.msgOk)
        assertNull(itemState.item)
    }
}
