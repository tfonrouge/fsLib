package com.fonrouge.base.state

import com.fonrouge.base.offsetDateTimeNow
import com.fonrouge.base.serializers.FSOffsetDateTimeSerializer
import io.kvision.types.OffsetDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Represents the state of an item, including its status, associated messages, and additional data.
 *
 * This class is a generic data container that implements the `ISimpleState` interface and is used
 * to encapsulate a specific item's state or condition effectively. It provides various constructors
 * to create instances based on different usage scenarios, such as initializing from another state,
 * creating a warning state, or explicitly specifying success or error states.
 *
 * @param item The item associated with this state. Defaults to `null` if not provided.
 * @param itemAlreadyOn A flag indicating whether the item is already in an "on" state. Defaults to `false`.
 * @param noDataModified A nullable flag indicating whether no data has been modified. Can be `null`.
 * @param serializedValueMap A map of per-property seed values encoded as [JsonElement] (use [kotlinx.serialization.json.JsonNull]
 *                           for an explicit null value; omit the key to signal "no value"). Primarily used by `onQueryCreateItem`
 *                           to seed a Create form's defaults without constructing a full [T]. Can be `null`.
 * @param state The state representing the current status of the item, which defaults to `State.Ok` if the
 *              item or `serializedValueMap` is present. Otherwise, defaults to `State.Error`.
 * @param msgOk An optional message associated with a successful state. Defaults to `MSG_OK`.
 * @param msgError An optional message associated with an error state. Automatically defaults to `MSG_ERROR`
 *                 if the state is not `State.Ok`.
 */
@Serializable
data class ItemState<T>(
    val item: T? = null,
    val itemAlreadyOn: Boolean = false,
    val noDataModified: Boolean? = null,
    val serializedValueMap: Map<String, JsonElement>? = null,
    override val state: State = if (item != null || serializedValueMap.isNullOrEmpty().not()) State.Ok else State.Error,
    override val msgOk: String? = MSG_OK,
    override val msgError: String? = if (state != State.Ok) MSG_ERROR else null,
) : ISimpleState {
    @Serializable(with = FSOffsetDateTimeSerializer::class)
    override val dateTime: OffsetDateTime = offsetDateTimeNow()
    override val hasError: Boolean get() = state == State.Error

    /**
     * Secondary constructor for the `ItemState` class that initializes its properties
     * based on a `SimpleState` instance.
     *
     * This constructor extracts the `state`, `msgOk`, and `msgError` properties from the provided
     * `SimpleState` instance and uses them to initialize an `ItemState` instance.
     *
     * @param simpleResponse An instance of `SimpleState` that provides the values for initializing the `ItemState` instance.
     */
    constructor(simpleResponse: SimpleState) : this(
        state = simpleResponse.state,
        msgOk = simpleResponse.msgOk,
        msgError = simpleResponse.msgError,
    )

    /**
     * Secondary constructor for initializing the `ItemState` with a warning state and corresponding message.
     *
     * This constructor sets the `state` property to `State.Warn` and uses the provided `msgWarn` as the `msgError` value.
     *
     * @param msgWarn The warning message to be associated with this state.
     */
    constructor(msgWarn: String) : this(
        state = State.Warn,
        msgError = msgWarn,
    )

    /**
     * Constructs an `ItemState` instance with a state determined by the provided `isOk` value.
     *
     * @param isOk A boolean indicating whether the state should be `State.Ok` or `State.Error`.
     * @param msgOk An optional message for a successful state. Defaults to `MSG_OK`.
     * @param msgError An optional message for an error state. Defaults to `MSG_ERROR`.
     */
    constructor(
        isOk: Boolean,
        msgOk: String? = MSG_OK,
        msgError: String? = MSG_ERROR,
        cargo: String? = null,
    ) : this(
        state = if (isOk) {
            State.Ok
        } else {
            State.Error
        },
        msgOk = msgOk,
        msgError = msgError,
    )

    /**
     * A property that provides a simplified representation of the current item state.
     *
     * This property returns an instance of [SimpleState], encapsulating the state,
     * optional success and error messages, and other relevant details from the parent [ItemState].
     */
    val asSimpleState get() = SimpleState(this)
}
