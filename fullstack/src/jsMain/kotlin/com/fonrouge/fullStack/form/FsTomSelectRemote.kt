/*
 * FsTomSelectRemote — fsLib workaround for KVision's TomSelectRemoteInput firing duplicate
 * remote loads (kvision-tom-select-remote 9.6.0 and 9.7.0 — re-checked on the 9.7.0 bump,
 * unchanged — TomSelectRemoteInput.refreshState lines 117-127: the guard
 * `options[value] == null` stays true for the whole in-flight window, so
 * every refreshState() during form population fires another identical RPC and duplicates
 * options — measured at 7-9 RPCs per selector on form open in a real consumer).
 *
 * Contract: blueprints/view-consumer-gaps/CONTRACT.md (I-1 in-flight guard, I-2 failure clears
 * the guard, I-3 behavioral mirror + retirement condition). RETIREMENT: when a KVision release
 * fsLib adopts guards refreshState against in-flight loads, delete this file and point
 * consumers back to tomSelectRemote (LEDGER L-003; FsTabPanel precedent) — re-check
 * TomSelectRemoteInput.refreshState sources at every KVision version bump.
 *
 * The mirror portions derive from KVision (Copyright (c) 2017-present Robert Jaros, MIT
 * license), because TomSelectRemote hard-wires its input as `final override val` and cannot be
 * given a subclassed input.
 */
package com.fonrouge.fullStack.form

import dev.kilua.rpc.CallAgent
import dev.kilua.rpc.HttpMethod
import dev.kilua.rpc.RemoteOption
import dev.kilua.rpc.RpcServiceMgr
import io.kvision.core.ClassSetBuilder
import io.kvision.core.Component
import io.kvision.core.Container
import io.kvision.core.KVScope
import io.kvision.core.Widget
import io.kvision.form.FieldLabel
import io.kvision.form.InvalidFeedback
import io.kvision.form.StringFormControl
import io.kvision.form.select.TomSelectCallbacks
import io.kvision.form.select.TomSelectOptions
import io.kvision.form.select.TomSelectRemoteInput
import io.kvision.form.select.TomSelectRenders
import io.kvision.panel.SimplePanel
import io.kvision.state.MutableState
import io.kvision.utils.Serialization
import io.kvision.utils.SnOn
import io.kvision.utils.obj
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import web.http.Request

/**
 * Tracks the set of values whose remote resolution loads are currently in flight, so duplicate
 * [FsTomSelectRemoteInput.refreshState] calls can be dropped instead of re-firing a load
 * (CONTRACT I-1) while a failed load still clears the way for a retry (CONTRACT I-2).
 *
 * Set-based on purpose (ACS-01 2026-09-09): with a single slot, a rapid A→B→A value change
 * would forget that A's load is still outstanding and admit a duplicate A load. Tracking each
 * value until its own load settles keeps I-1's per-value guarantee unconditional, and settling
 * one value never unblocks another.
 *
 * Pure state machine, extracted so the guard is unit-testable without the RPC stack. Internal:
 * an implementation detail of [FsTomSelectRemoteInput], not public API (CONTRACT §New public API).
 */
internal class InFlightValueGuard {

    private val pending = mutableSetOf<String>()

    /** Marks a load for [value] as in flight. */
    fun start(value: String) {
        pending.add(value)
    }

    /** Returns `true` while a load for [value] is in flight. */
    fun isInFlight(value: String): Boolean = value in pending

    /**
     * Clears [value]'s marker — called on both completion and failure of that value's load;
     * other values' markers are untouched.
     */
    fun settle(value: String) {
        pending.remove(value)
    }
}

/**
 * A [TomSelectRemoteInput] that never fires more than one remote value-resolution load per
 * value at a time: while a load for the current value is in flight, further [refreshState]
 * calls are dropped and the in-flight load's completion performs the base refresh instead.
 * A failed load clears the guard so a later [refreshState] can retry (CONTRACT I-1/I-2).
 *
 * Constructor parameters mirror [TomSelectRemoteInput] exactly (CONTRACT I-3).
 */
open class FsTomSelectRemoteInput<out T : Any>(
    serviceManager: RpcServiceMgr<T>,
    function: suspend T.(String?, String?, String?) -> List<RemoteOption>,
    stateFunction: (() -> String)? = null,
    value: String? = null, emptyOption: Boolean = false, multiple: Boolean = false, maxOptions: Int? = null,
    tsOptions: TomSelectOptions? = null, tsCallbacks: TomSelectCallbacks? = null, tsRenders: TomSelectRenders? = null,
    preload: Boolean = false, openOnFocus: Boolean = false,
    requestFilter: (suspend Request.() -> Unit)? = null,
    className: String? = null,
    init: (FsTomSelectRemoteInput<T>.() -> Unit)? = null
) : TomSelectRemoteInput<T>(
    serviceManager, function, stateFunction, value, emptyOption, multiple, maxOptions,
    tsOptions, tsCallbacks, tsRenders, preload, openOnFocus, requestFilter, className
) {

    /**
     * Backing field for the guard, created on first use via [guard]. `lateinit` on purpose
     * (ACS-02 2026-09-09): the parent constructor's preset-value load dispatches into this
     * class's [loadResults] override BEFORE this class's property initializers run. A plain
     * `val ... = InFlightValueGuard()` would be uninitialized at that moment, and a nullable
     * field's `= null` initializer would later WIPE a guard created during that dispatch —
     * un-guarding the constructor-phase load. A `lateinit` field has no initializer to run
     * after the parent constructor, so the guard created inside the dispatch survives and the
     * preset-value load is guarded like every other load (CONTRACT I-1, no exceptions).
     */
    private lateinit var inFlightValueGuardField: InFlightValueGuard

    /** Returns the guard, creating it on first access (which may be during the parent constructor). */
    private fun guard(): InFlightValueGuard {
        if (!::inFlightValueGuardField.isInitialized) {
            inFlightValueGuardField = InFlightValueGuard()
        }
        return inFlightValueGuardField
    }

    init {
        @Suppress("LeakingThis")
        init?.invoke(this)
    }

    /**
     * Drops the call when the base class would re-fire a load for a value that is already being
     * resolved — the in-flight load's callback runs the base refresh once the options arrive,
     * which is exactly what the dropped calls wanted. Everything else delegates to the base
     * implementation unchanged.
     */
    override fun refreshState() {
        val v = value
        if (v != null && tomSelectJs != null &&
            tomSelectJs!!.asDynamic().options[v] == null &&
            guard().isInFlight(v)
        ) {
            return
        }
        super.refreshState()
    }

    /**
     * Reimplements the base load (same request, same option mapping — CONTRACT I-3) to mark
     * value-resolution loads in the guard and settle them on completion *and* on failure: the
     * base implementation's coroutine has no failure path that could clear a guard, and a guard
     * stuck on a failed load would freeze the selector forever (CONTRACT I-2). Typeahead
     * searches (`query != null`) and the preload load (`initial == null`) are not guarded.
     *
     * Every request parameter is prepared BEFORE the guard is marked (ACS-03 2026-09-09): a
     * synchronously throwing [stateFunction] after `start` would leave the marker set forever,
     * since only the coroutine body has a failure path that settles it.
     */
    override fun loadResults(
        callAgent: CallAgent,
        url: String,
        method: HttpMethod,
        query: String?,
        initial: String?,
        requestFilter: (suspend Request.() -> Unit)?,
        callback: (Array<dynamic>) -> Unit
    ) {
        val queryParam = query?.let { JSON.stringify(it) }
        val initialParam = initial?.let { JSON.stringify(it) }
        val state = stateFunction?.invoke()?.let { JSON.stringify(it) }
        val guardedValue = if (query == null && initial != null) initial else null
        if (guardedValue != null) guard().start(guardedValue)
        KVScope.launch {
            try {
                val result = callAgent.jsonRpcCall(
                    url,
                    listOf(queryParam, initialParam, state),
                    method,
                    requestFilter = requestFilter
                )
                val options = Serialization.plain.decodeFromString(
                    ListSerializer(RemoteOption.serializer()),
                    result
                ).mapIndexed { index, option ->
                    obj {
                        if (option.divider) {
                            this.value = "divider${index}"
                            this.divider = true
                            this.disabled = true
                        } else {
                            this.value = option.value
                            if (option.text != null) this.text = option.text
                            if (option.className != null) this.className = option.className
                            if (option.disabled) this.disabled = true
                            if (option.subtext != null) this.subtext = option.subtext
                            if (option.icon != null) this.icon = option.icon
                            if (option.content != null) this.content = option.content
                        }
                    }
                }.toTypedArray()
                if (guardedValue != null) guard().settle(guardedValue)
                callback(options)
            } catch (e: Throwable) {
                if (guardedValue != null) guard().settle(guardedValue)
                throw e
            }
        }
    }
}

/**
 * The form field component for [FsTomSelectRemoteInput] — a mirror of KVision's
 * `TomSelectRemote` (which hard-wires its own input and cannot host a subclassed one), so a
 * consumer swap is builder-name only (CONTRACT I-3). See the file header for the retirement
 * condition.
 */
open class FsTomSelectRemote<out T : Any>(
    serviceManager: RpcServiceMgr<T>,
    function: suspend T.(String?, String?, String?) -> List<RemoteOption>,
    stateFunction: (() -> String)? = null,
    value: String? = null, emptyOption: Boolean = false, multiple: Boolean = false, maxOptions: Int? = null,
    tsOptions: TomSelectOptions? = null, tsCallbacks: TomSelectCallbacks? = null, tsRenders: TomSelectRenders? = null,
    preload: Boolean = false, openOnFocus: Boolean = false,
    requestFilter: (suspend Request.() -> Unit)? = null,
    name: String? = null, label: String? = null, rich: Boolean = false,
    init: (FsTomSelectRemote<T>.() -> Unit)? = null
) : SimplePanel("form-group kv-mb-3"), StringFormControl, MutableState<String?> {

    /** A value of the selected option. */
    override var value
        get() = input.value
        set(value) {
            input.value = value
        }

    /** Determines if an empty option is automatically generated. */
    var emptyOption
        get() = input.emptyOption
        set(value) {
            input.emptyOption = value
        }

    /** Determines if multiple value selection is allowed. */
    var multiple
        get() = input.multiple
        set(value) {
            input.multiple = value
        }

    /** The number of visible options. */
    var maxOptions
        get() = input.maxOptions
        set(value) {
            input.maxOptions = value
        }

    /** Disable searching in options. */
    var disableSearch
        get() = input.disableSearch
        set(value) {
            input.disableSearch = value
        }

    /** Tom Select options. */
    var tsOptions
        get() = input.tsOptions
        set(value) {
            input.tsOptions = value
        }

    /** Tom Select callbacks. */
    var tsCallbacks
        get() = input.tsCallbacks
        set(value) {
            input.tsCallbacks = value
        }

    /** Tom Select render functions. */
    var tsRenders
        get() = input.tsRenders
        set(value) {
            input.tsRenders = value
        }

    /** The placeholder for the select control. */
    var placeholder
        get() = input.placeholder
        set(value) {
            input.placeholder = value
        }

    /** Determines if the select is automatically focused. */
    var autofocus
        get() = input.autofocus
        set(value) {
            input.autofocus = value
        }

    /** The label text bound to the select element. */
    var label
        get() = flabel.content
        set(value) {
            flabel.content = value
        }

    /** Determines if [label] can contain HTML code. */
    var rich
        get() = flabel.rich
        set(value) {
            flabel.rich = value
        }

    /** The label of the currently selected option. */
    val selectedLabel
        get() = input.selectedLabel

    private val idc = "kv_form_FsTomSelectRemote_$counter"

    final override val input: FsTomSelectRemoteInput<T> = FsTomSelectRemoteInput(
        serviceManager, function, stateFunction, value, emptyOption, multiple, maxOptions,
        tsOptions, tsCallbacks, tsRenders, preload, openOnFocus, requestFilter, "form-control"
    ).apply {
        this.id = this@FsTomSelectRemote.idc
        this.name = name
        if (label != null) setAttribute("aria-label", label)
    }
    final override val flabel: FieldLabel = FieldLabel(idc, label, rich, "form-label")
    final override val invalidFeedback: InvalidFeedback = InvalidFeedback().apply { visible = false }

    init {
        useSnabbdomDistinctKey()
        @Suppress("LeakingThis")
        input.eventTarget = this
        this.addPrivate(flabel)
        this.addPrivate(input)
        this.addPrivate(invalidFeedback)
        counter++
        @Suppress("LeakingThis")
        init?.invoke(this)
    }

    override fun buildClassSet(classSetBuilder: ClassSetBuilder) {
        super.buildClassSet(classSetBuilder)
        if (validatorError != null) {
            classSetBuilder.add("kv-text-danger")
        }
    }

    override fun <T : Widget> setEventListener(block: SnOn<T>.() -> Unit): Int {
        return input.setEventListener(block)
    }

    override fun removeEventListener(id: Int) {
        input.removeEventListener(id)
    }

    override fun removeEventListeners() {
        input.removeEventListeners()
    }

    override fun add(child: Component) {
        input.add(child)
    }

    override fun add(position: Int, child: Component) {
        input.add(position, child)
    }

    override fun addAll(children: List<Component>) {
        input.addAll(children)
    }

    override fun remove(child: Component) {
        input.remove(child)
    }

    override fun removeAt(position: Int) {
        input.removeAt(position)
    }

    override fun removeAll() {
        input.removeAll()
    }

    override fun disposeAll() {
        input.disposeAll()
    }

    override fun getChildren(): List<Component> {
        return input.getChildren()
    }

    override fun focus() {
        input.focus()
    }

    override fun blur() {
        input.blur()
    }

    /** Removes all unselected options from the control. */
    open fun clearOptions() {
        input.clearOptions()
    }

    override fun getState(): String? = input.getState()

    override fun subscribe(observer: (String?) -> Unit): () -> Unit {
        return input.subscribe(observer)
    }

    override fun setState(state: String?) {
        input.setState(state)
    }

    companion object {
        internal var counter = 0
    }
}

/**
 * DSL builder extension function — drop-in replacement for KVision's `tomSelectRemote`,
 * returning the in-flight-guarded [FsTomSelectRemote] (CONTRACT I-1/I-2/I-3).
 *
 * It takes the same parameters as the constructor of the built component.
 */
fun <T : Any> Container.fsTomSelectRemote(
    serviceManager: RpcServiceMgr<T>,
    function: suspend T.(String?, String?, String?) -> List<RemoteOption>, stateFunction: (() -> String)? = null,
    value: String? = null, emptyOption: Boolean = false, multiple: Boolean = false, maxOptions: Int? = null,
    tsOptions: TomSelectOptions? = null, tsCallbacks: TomSelectCallbacks? = null, tsRenders: TomSelectRenders? = null,
    preload: Boolean = false, openOnFocus: Boolean = false, requestFilter: (suspend Request.() -> Unit)? = null,
    name: String? = null, label: String? = null, rich: Boolean = false, init: (FsTomSelectRemote<T>.() -> Unit)? = null
): FsTomSelectRemote<T> {
    val fsTomSelectRemote =
        FsTomSelectRemote(
            serviceManager, function, stateFunction,
            value, emptyOption, multiple, maxOptions, tsOptions, tsCallbacks, tsRenders, preload, openOnFocus,
            requestFilter, name, label, rich, init
        )
    this.add(fsTomSelectRemote)
    return fsTomSelectRemote
}

/**
 * DSL builder extension function — drop-in replacement for KVision's `tomSelectRemoteInput`,
 * returning the in-flight-guarded [FsTomSelectRemoteInput] (CONTRACT I-1/I-2/I-3).
 *
 * It takes the same parameters as the constructor of the built component.
 */
fun <T : Any> Container.fsTomSelectRemoteInput(
    serviceManager: RpcServiceMgr<T>,
    function: suspend T.(String?, String?, String?) -> List<RemoteOption>,
    stateFunction: (() -> String)? = null,
    value: String? = null, emptyOption: Boolean = false, multiple: Boolean = false, maxOptions: Int? = null,
    tsOptions: TomSelectOptions? = null, tsCallbacks: TomSelectCallbacks? = null, tsRenders: TomSelectRenders? = null,
    preload: Boolean = false, openOnFocus: Boolean = false,
    requestFilter: (suspend Request.() -> Unit)? = null,
    className: String? = null,
    init: (FsTomSelectRemoteInput<T>.() -> Unit)? = null
): FsTomSelectRemoteInput<T> {
    val fsTomSelectRemoteInput =
        FsTomSelectRemoteInput(
            serviceManager, function, stateFunction, value, emptyOption, multiple, maxOptions,
            tsOptions, tsCallbacks, tsRenders, preload, openOnFocus, requestFilter, className, init
        )
    this.add(fsTomSelectRemoteInput)
    return fsTomSelectRemoteInput
}
