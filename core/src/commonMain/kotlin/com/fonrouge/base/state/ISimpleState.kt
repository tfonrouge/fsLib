package com.fonrouge.base.state

import io.kvision.types.OffsetDateTime

const val MSG_OK = "Operation successful"
const val MSG_ERROR = "Operation Failed"

/**
 * Represents a simple state interface that encapsulates the current state, associated messages,
 * metadata, and details about the state occurrence.
 *
 * The interface provides a standardized way to express and measure the conditions or status
 * of an object, process, or item through the following properties:
 *
 * @property state The current state of the object or process, represented using the `State` enum.
 * @property msgOk An optional message indicating successful status, if applicable.
 * @property msgError An optional message describing an error or problematic condition, if applicable.
 * @property dateTime The timestamp when the state was defined or last modified.
 * @property hasError A boolean value that indicates whether the state represents an error condition.
 */
interface ISimpleState {
    val state: State
    val msgOk: String?
    val msgError: String?
    val dateTime: OffsetDateTime
    val hasError: Boolean

    /**
     * Whether the operation did **not** succeed, for any reason.
     *
     * [hasError] answers a narrower question — it is `true` only for [State.Error], i.e. something
     * broke. A repository can also refuse a write with [State.Warn] and no exception at all: see
     * the no-op short-circuits in `Coll.updateOne` and `SqlRepository.updateItem`. Those refusals
     * are not errors, but they are not successes either, and code that branches on [hasError]
     * silently reports them as successful.
     *
     * Any caller deciding whether to announce success to the user, close a form, or discard
     * captured input must branch on this property rather than on [hasError].
     */
    val isRejected: Boolean get() = state != State.Ok
}
