package com.fonrouge.base.state

import kotlinx.serialization.json.Json
import kotlin.test.*

/**
 * Tests for the State enum and SimpleState data class.
 */
class StateTest {

    // -- State enum --

    @Test
    fun stateEnumValues() {
        assertEquals(3, State.entries.size)
        assertEquals(State.Ok, State.valueOf("Ok"))
        assertEquals(State.Warn, State.valueOf("Warn"))
        assertEquals(State.Error, State.valueOf("Error"))
    }

    @Test
    fun stateJsonRoundTrip() {
        for (state in State.entries) {
            val json = Json.encodeToString(state)
            val decoded = Json.decodeFromString<State>(json)
            assertEquals(state, decoded)
        }
    }

    // -- SimpleState --

    @Test
    fun simpleStateOk() {
        val state = SimpleState(isOk = true, msgOk = "Done", msgError = null)
        assertEquals(State.Ok, state.state)
        assertEquals("Done", state.msgOk)
        assertNull(state.msgError)
        assertFalse(state.hasError)
    }

    @Test
    fun simpleStateError() {
        val state = SimpleState(isOk = false, msgOk = null, msgError = "Failed")
        assertEquals(State.Error, state.state)
        assertNull(state.msgOk)
        assertEquals("Failed", state.msgError)
        assertTrue(state.hasError)
    }

    @Test
    fun simpleStateWarn() {
        val state = simpleWarnState("Warning message")
        assertEquals(State.Warn, state.state)
        assertNull(state.msgOk)
        assertEquals("Warning message", state.msgError)
        assertFalse(state.hasError)
    }

    @Test
    fun simpleErrorStateHelper() {
        val state = simpleErrorState("Something failed")
        assertEquals(State.Error, state.state)
        assertEquals("Something failed", state.msgError)
        assertTrue(state.hasError)
    }

    @Test
    fun simpleStateDefaultMessages() {
        val ok = SimpleState(isOk = true)
        assertEquals(MSG_OK, ok.msgOk)

        val err = SimpleState(isOk = false)
        assertEquals(MSG_ERROR, err.msgError)
    }

    @Test
    fun simpleStateHasErrorOnlyForErrorState() {
        assertFalse(SimpleState(state = State.Ok).hasError)
        assertFalse(SimpleState(state = State.Warn).hasError)
        assertTrue(SimpleState(state = State.Error).hasError)
    }

    /**
     * `isRejected` must be broader than [ISimpleState.hasError]: it also covers [State.Warn].
     * This is the distinction UI code needs — `hasError` answers "did it break", `isRejected`
     * answers "did it not succeed" — and conflating the two reported refused writes as successes.
     */
    @Test
    fun isRejectedCoversWarnUnlikeHasError() {
        assertFalse(SimpleState(state = State.Ok).isRejected)
        assertTrue(SimpleState(state = State.Warn).isRejected)
        assertTrue(SimpleState(state = State.Error).isRejected)
    }

    /**
     * Regression guard for the no-op update path. `Coll.updateOne` and `SqlRepository` refuse an
     * update that would change nothing by returning [State.Warn] with `noDataModified = true`.
     * Since [ItemState.msgOk] defaults to [MSG_OK], anything branching on `hasError` picked that
     * default up and told the user "Operation successful" about a write the server had refused.
     */
    @Test
    fun noOpUpdateIsRejectedButFlaggedAsNoDataModified() {
        val noOpUpdate = ItemState<String>(
            state = State.Warn,
            noDataModified = true,
            msgError = "Update skipped - no changes detected in item",
        )

        // The trap: not an error, carries a success message, yet the write did not happen.
        assertFalse(noOpUpdate.hasError)
        assertEquals(MSG_OK, noOpUpdate.msgOk)

        assertTrue(noOpUpdate.isRejected)
        assertEquals(true, noOpUpdate.noDataModified)
    }

    /**
     * A refusal the user has to act on is distinguishable from the benign no-op above purely by
     * `noDataModified`, which is what lets the form stay open for one and close for the other.
     */
    @Test
    fun refusedWriteIsRejectedWithoutNoDataModifiedFlag() {
        val refused = ItemState<String>(
            state = State.Warn,
            msgError = "Business rule rejected the record",
        )

        assertTrue(refused.isRejected)
        assertNull(refused.noDataModified)
    }

    /**
     * `isWriteComplete` is the predicate a form uses to decide it is finished — may it close, and
     * may it reset its unsaved-changes baseline. It must be true for a success and for a benign
     * no-op, and false for every refusal the user still has to act on.
     */
    @Test
    fun isWriteCompleteAcceptsSuccessAndNoOpButNotRefusals() {
        assertTrue(ItemState("saved", state = State.Ok).isWriteComplete)

        assertTrue(
            ItemState<String>(state = State.Warn, noDataModified = true).isWriteComplete,
            "a no-op left the store exactly as the user asked",
        )

        assertFalse(
            ItemState<String>(state = State.Warn, msgError = "Refused").isWriteComplete,
            "a Warn refusal must not let the form close and discard input",
        )
        assertFalse(
            ItemState<String>(state = State.Error, msgError = "Boom").isWriteComplete,
            "an error must not let the form close and discard input",
        )

        // `noDataModified` means only "nothing was written", which is also true of an outright
        // failure — `Coll.upsertOne` pairs Error with it when a document is neither matched nor
        // upserted. It must not launder an error into a completed write.
        assertFalse(
            ItemState<String>(state = State.Error, noDataModified = true).isWriteComplete,
            "noDataModified must not make an Error count as complete",
        )
    }
}
