package com.fonrouge.fullStack.view

import com.fonrouge.base.api.CallType
import com.fonrouge.base.api.CrudTask
import com.fonrouge.base.api.IApiFilter
import com.fonrouge.base.common.ICommonContainer
import com.fonrouge.fullStack.lib.UrlParams
import com.fonrouge.fullStack.lib.toEncodedUrlString
import com.fonrouge.fullStack.lib.completionToast
import com.fonrouge.fullStack.lib.dismissStickyToasts
import com.fonrouge.fullStack.lib.rejectionToast
import com.fonrouge.fullStack.lib.toast
import com.fonrouge.fullStack.lib.translatedIfFrameworkDefault
import com.fonrouge.base.model.BaseDoc
import com.fonrouge.base.state.ItemState
import com.fonrouge.base.state.SimpleState
import com.fonrouge.base.state.State
import com.fonrouge.fullStack.callItemService
import com.fonrouge.fullStack.config.ConfigViewContainer
import com.fonrouge.fullStack.config.ConfigViewItem
import com.fonrouge.fullStack.getItemState
import com.fonrouge.fullStack.layout.centeredMessage
import com.fonrouge.fullStack.tabulator.TabulatorMenuItem
import io.kvision.core.*
import io.kvision.form.DateFormControl
import io.kvision.form.FormPanel
import io.kvision.form.KFilesFormControl
import io.kvision.types.KFile
import io.kvision.html.Button
import io.kvision.html.ButtonSize
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.i18n.I18n.gettext
import io.kvision.i18n.tr
import io.kvision.navbar.Nav
import io.kvision.panel.flexPanel
import io.kvision.panel.vPanel
import io.kvision.state.ObservableList
import io.kvision.state.ObservableValue
import io.kvision.tabulator.Tabulator
import io.kvision.toast.Toast
import io.kvision.toast.ToastOptions
import io.kvision.toast.ToastPosition
import io.kvision.utils.em
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.serializer
import org.w3c.dom.events.MouseEvent
import web.prompts.confirm
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1

/**
 * Represents a configurable view item that connects and interacts with the backend API
 * and provides functionalities to handle data viewing, editing, and validation.
 *
 * This class is designed for creating and managing UI forms that interact with backend
 * services via API calls, allowing CRUD (Create, Read, Update, Delete) actions.
 *
 * @param T The type of the domain entity being managed.
 * @param ID The type of the identifier for the domain entity.
 * @param FILT The type of API filter used for querying or filtering data.
 * @param configView Configuration details for the view item, includes serializers and endpoint-related logic.
 * @param periodicUpdateDataView Determines if periodic updates of the view's data are allowed.
 * @param debug Enables or disables debugging for the instance.
 */
@Suppress("unused")
abstract class ViewItem<T : BaseDoc<ID>, ID : Any, FILT : IApiFilter<*>>(
    final override val configView: ConfigViewItem<T, ID, out ViewItem<T, ID, FILT>, FILT, *>,
    periodicUpdateDataView: Boolean? = null,
    private var debug: Boolean = false,
) : ViewDataContainer<T, ID, FILT>(
    configViewContainer = configView,
) {
    companion object {
        private const val TOAST_DURATION = 10000
    }

    /**
     * Per-property seed values for a Create form, populated from the wire via [ItemState.serializedValueMap].
     * Transient: entries are drained by [applyServerSeeds] during form display — matching keys are applied to
     * their form control and removed; unmatched keys remain as residue and flow through the
     * [FormPanel.getData] submission overlay via [viewFormPanel]'s
     * [dataOverlayProvider][io.kvision.form.Form.dataOverlayProvider].
     *
     * Drain is **incremental across [displayForm] invocations** until the map is empty: if a subclass
     * rebuilds the form (e.g. pushes a new [pageItemBody] that exposes a previously-hidden control),
     * the next invocation of [applyServerSeeds] picks up the newly-matchable residue without extra
     * wiring. Failures recorded by [applyServerSeeds] (per-seed `try/catch`) also stay in the bucket
     * across invocations and will be retried on the next display — useful if a later render changes
     * the control shape.
     *
     * For client-added hidden fields that should ride every submission (e.g. parent IDs), prefer
     * [addSerializedValue] / [hiddenFields] instead — those persist across re-renders.
     *
     * The setter is `private`: external callers can read the map and mutate its entries (`put`, `remove`,
     * `clear`) but cannot swap the reference with a different [MutableMap]. This protects pending residue
     * from being accidentally wiped by an unqualified `viewItem.serverSeeds = mutableMapOf()` assignment
     * from a subclass or test.
     */
    var serverSeeds: MutableMap<String, JsonElement> = mutableMapOf()
        private set

    /**
     * Hidden, client-side fields to be merged into every form submission without a visible control. Populated
     * exclusively by [addSerializedValue]; persistent across re-renders and CRUD mode switches. Read back by
     * [viewFormPanel]'s [dataOverlayProvider][io.kvision.form.Form.dataOverlayProvider] on `getData()`.
     *
     * Keys colliding with a form control are intentionally filtered out in the overlay — use a regular form
     * control for values meant to be visible or editable. Compared to [serverSeeds], this bucket is never
     * drained and is the right place for things like parent IDs, tenant IDs, or any contextual metadata the
     * server expects on every Create/Update payload.
     */
    @PublishedApi
    internal val hiddenFields: MutableMap<String, JsonElement> = mutableMapOf()
    var buttonBack: Button? = null
    var buttonCancel: Button? = null
    var buttonAccept: Button? = null
    var dataDisplayed: Boolean = false
    val dropDownElementsObs = ObservableValue<List<TabulatorMenuItem>?>(null)

    /**
     * Reference to the [FormPanel] instance that manages display and input handling for the view's item
     * type. Initialized with the container's item serializer inside [viewFormPanel] so that form data
     * can be serialized for submission without subclasses having to wire anything up.
     */
    var formPanel: FormPanel<T>? = null

    /**
     * Observable that holds the [ItemState] for the [ViewItem]
     */
    val itemObservable: ObservableValue<T?> = ObservableValue(null)

    /**
     * Helper to get the item property from the [ItemState]
     */
    var item: T?
        get() = itemObservable.value
        set(value) {
            itemObservable.value = value
            configView.item = value
        }

    init {
        itemObservable.subscribe { it ->
            it?.let { item ->
                if (debug) {
                    console.warn("itemObservable.subscribe:", item)
                }
                formPanel?.setData(item)
                if (mainView) updateTitle()
            }
            dropDownElementsObs.value = it?.let { item ->
                configView.item = item
                val x = ConfigViewItem.contextMenuDefault?.invoke(configView)
                configView.contextMenuItems?.invoke(item)?.let {
                    x?.plus(it) ?: it
                } ?: x
            }
        }
    }

    /**
     * Represents the label of the view item, composed dynamically using the `configView`'s label and
     * the label ID of the current item from the common container.
     *
     * This label is utilized to provide a concise and descriptive textual representation, aiding in
     * UI rendering or internal debugging processes.
     */
    override val label: String
        get() = configView.commonContainer.labelItemId(item)

    /**
     * Provides the identifier string for the label associated with the current item in the view.
     * This identifier is determined dynamically based on the `commonContainer` configuration
     * and the current `item` in the `configView`.
     *
     * The property is utilized for associating a label with a corresponding item within the user interface
     * or backend system, facilitating structure and accessibility.
     */
    val labelId get() = configView.commonContainer.labelId(item)

    /**
     * Indicates whether the back button should be hidden or disabled in the current view context.
     *
     * This variable determines the visibility or functionality of the navigational back button.
     * It is primarily used to control user navigation within the interface. When set to `true`,
     * the back button is effectively deactivated or not shown. The default value is `false`,
     * which means the back button is enabled and visible.
     */
    var noBackButton = false

    /**
     * A callback function that is invoked when the accept button is clicked.
     * The function can handle custom logic or UI updates when the event occurs.
     *
     * This callback receives a reference to the button (`Button`) that was clicked
     * and the associated `MouseEvent` representing the click action.
     *
     * It can be used to define specific behavior for the accept button,
     * such as processing form submissions or triggering additional actions.
     *
     * The callback is optional and can be set to `null` if no specific action
     * needs to be performed on the button click.
     */
    var onAcceptButtonClick: (Button.(MouseEvent) -> Unit)? = null
    private var origSerialized: String? = null

    /**
     * Indicates whether periodic updates for the data view are enabled. If not explicitly set,
     * the value defaults to the `periodicUpdateDataViewItem` from `KVWebManager`.
     *
     * This property can be used to control or interrogate the state of periodic updates
     * within the `ViewItem` context.
     */
    final override var periodicUpdateDataView: Boolean? = periodicUpdateDataView
        get() = field ?: KVWebManager.periodicUpdateDataViewItem

    val tabulators: MutableMap<String, TabulatorItem<*>> = mutableMapOf()

    data class TabulatorItem<T : Any>(
        val tabulator: Tabulator<T>,
        val kClass: KClass<T>,
    ) {
        @OptIn(InternalSerializationApi::class)
        fun toPlainObj(): dynamic {
            val x: List<T>? = tabulator.getData()
            val s = ListSerializer(kClass.serializer())
            return JSON.parse(Json.encodeToString(s, x as List<T>))
        }
    }

    /**
     * Applies the outcome of an upsert to the view's own state: the save buttons and the clean
     * baseline used to detect unsaved changes.
     *
     * Called by [acceptUpsertAction] on every upsert, before and independently of its `block`.
     * These are not presentation concerns — getting them wrong discards the user's work — so they
     * must not sit in an overridable argument. A caller that supplies its own `block` changes how
     * the outcome is announced; it cannot change whether captured input survives a refusal.
     *
     * Private for the same reason: an overridable safety check is not a safety check. Subclasses
     * that need to react to an upsert result have [acceptUpsertAction]'s `block`, which runs after
     * this and can reuse [defaultUpsertToast].
     *
     * Nothing happens unless [ItemState.isWriteComplete]: while a refusal is on screen the form
     * stays editable, the save buttons stay available for a retry, and [origSerialized] keeps
     * pointing at the last state the server actually accepted — so a later Cancel still recognises
     * the rejected edits as unsaved and asks before discarding them.
     *
     * Clearing an earlier refusal also happens here rather than in the toast functions, because a
     * `block` is free to present an outcome without showing a toast at all — `ViewList` navigates
     * away on success — and the stale refusal would otherwise stay pinned to the screen.
     *
     * @param itemState the result returned by the item service.
     * @param crudTask the action that produced [itemState].
     * @param data the transformed form data that was submitted.
     */
    internal fun applyUpsertOutcome(itemState: ItemState<T>, crudTask: CrudTask, data: T) {
        // A new outcome for this form supersedes whatever refusal is still on screen, whether or
        // not the `block` about to run chooses to say anything.
        dismissStickyToasts()

        if (itemState.isWriteComplete.not()) return

        // The accepted item is view state: everything derived from `item` — the title, the context
        // menu, a bound notice — otherwise keeps rendering the pre-write world, and during a Create
        // that world has `item == null`. Republishing it here re-runs the bindings.
        //
        // Null-guarded: a write that completed without returning an item must not blank a form that
        // is currently showing one.
        itemState.item?.let { itemObservable.value = it }

        if (crudTask == CrudTask.Update) {
            // Derived from what the form now holds, not from `data`. Publishing above re-populates
            // the form through the `itemObservable` subscription, so a server that normalised a
            // value leaves the form showing something the submitted `data` no longer matches — and
            // this baseline is what a later Cancel compares against to decide whether to warn about
            // unsaved edits. Computing it the same way `backCloseAction` does keeps the two in step
            // by construction.
            origSerialized = Json.encodeToString(
                serializer = configView.commonContainer.itemSerializer,
                value = formPanel?.let { transformData(it.getData()) } ?: itemState.item ?: data
            )
        }
        navButtonCancel?.hide()
        navButtonAccept?.hide()
        navButtonBack?.show()
        buttonCancel?.hide()
        buttonAccept?.hide()
        buttonBack?.show()
    }

    /**
     * Handles a **completed** write from the Accept buttons — never a refusal; see
     * [dispatchUpsertResult]. The default announces it with [defaultUpsertToast].
     *
     * This is the seam for a view whose *structure* depends on the saved item. Values are already
     * covered, by [applyUpsertOutcome] republishing the item and by bindings on [itemObservable];
     * structure is not. A section whose existence is decided by a plain `if (item?…)` inside
     * `pageItemBody()` was settled while the form was being built — when a Create still had
     * `item == null` — and no observable re-runs it. A view in that position overrides this and
     * navigates to the saved record, rebuilding the page through the normal path with the item in
     * hand.
     *
     * @param itemState the result of the write; [ItemState.isWriteComplete] is guaranteed `true`.
     */
    protected open fun onUpsertResult(itemState: ItemState<T>) = defaultUpsertToast(itemState)

    /**
     * Routes an upsert result: a completed write goes to the overridable [onUpsertResult], anything
     * else is presented by the framework as a refusal.
     *
     * This is [acceptUpsertAction]'s default `block`, so *both* framework-built Accept buttons —
     * the one in the form and the one in the navbar — reach [onUpsertResult] without either having
     * to opt in. Routing from a single call site is deliberate: wiring the buttons individually is
     * how one of them silently stops honouring an override.
     *
     * Splitting refusals off here is what lets [onUpsertResult] promise a completed write. An
     * override that only knows how to handle success cannot suppress the sticky refusal toast, and
     * never sees an [ItemState] whose `item` is missing because the write did not happen.
     */
    internal fun dispatchUpsertResult(itemState: ItemState<T>) {
        if (itemState.isWriteComplete) onUpsertResult(itemState) else itemState.rejectionToast()
    }

    /**
     * The default notification shown by [acceptUpsertAction], exposed so that a caller supplying
     * its own `block` can still reuse it: `acceptUpsertAction { defaultUpsertToast(it); extra(it) }`.
     *
     * A completed write ([ItemState.isWriteComplete]) is announced and closes the view as the toast
     * fades. Anything else is a refusal the user has to act on, so its toast is sticky — it stays
     * until dismissed, and it does not close the view, because doing so would take the form and
     * everything captured in it away along with the explanation.
     *
     * Only the framework's own default messages are translated; text written by the server passes
     * through untouched (see `translatedIfFrameworkDefault`).
     */
    fun defaultUpsertToast(itemState: ItemState<T>) {
        if (itemState.isWriteComplete) {
            itemState.completionToast(
                message = if (itemState.noDataModified == true) "${gettext("No data was modified")} ..." else null,
                options = ToastOptions(
                    callback = { backCloseAction() },
                    close = true,
                    stopOnFocus = true,
                ),
            )
        } else {
            itemState.rejectionToast()
        }
    }

    /**
     * Executes an "upsert" action (either update or insert) for the current item, using form validation,
     * data transformation, and API service calls. Optionally displays toast notifications and updates UI components.
     *
     * @param block An optional lambda function to handle the result of the upsert API call. The function receives
     *              an [ItemState] parameter, which contains information about the success, error, or status of the operation.
     */
    fun acceptUpsertAction(
        block: ((ItemState<T>) -> Unit)? = { dispatchUpsertResult(it) },
    ) {
        val crudAction = crudTask
        if (crudAction != null && crudAction in arrayOf(CrudTask.Create, CrudTask.Update)) {
            formPanel?.let { formPanel ->
                if (formPanel.validate()) {
                    val data = transformData(formPanel.getData())
                    val simpleState = formPanelValidate(data)
                    if (simpleState.state == State.Ok) {
                        configView.commonContainer.callItemService(
                            apiItemFun = configView.apiItemFun,
                            crudTask = crudAction,
                            callType = CallType.Action,
                            id = item?._id,
                            item = data,
                            apiFilter = apiFilter,
                        ) { itemResponse ->
                            // Runs before — and independently of — `block`. Overriding `block`
                            // replaces how the outcome is announced, never whether the user's work
                            // is protected from it.
                            applyUpsertOutcome(
                                itemState = itemResponse,
                                crudTask = crudAction,
                                data = data,
                            )
                            block?.invoke(itemResponse)
                            itemResponse
                        }
                    } else {
                        simpleState.toast()
                    }
                } else {
                    Toast.warning(
                        message = gettext("Form has incomplete data"),
                        options = ToastOptions(
                            position = ToastPosition.BOTTOMRIGHT,
                            stopOnFocus = true
                        )
                    )
                }
            }
        }
    }

    /**
     * Adds a viewList to the container and optionally initializes it with custom logic.
     *
     * @param viewList The ViewList object to be added. It should extend from ICommonContainer and support an API filter implementation.
     * @param init An optional lambda function to initialize the ViewList with custom configurations or behavior.
     */
    @Suppress("unused")
    fun Container.addViewList(
        viewList: ViewList<*, *, out IApiFilter<ID>, ID>,
        init: ((ViewList<*, *, *, *>).() -> Unit)? = null,
    ) {
        viewList.apply { startDisplayPage() }
        viewList.masterViewItem = this@ViewItem
        init?.invoke(viewList)
    }

    /**
     * Handles the back or close action for the current view. If confirmation is required
     * and unsaved changes are detected, prompts the user for confirmation before proceeding.
     * Depending on the browser history, navigates back or closes the window.
     *
     * @param confirmCancel Indicates if the user should be prompted to confirm canceling any unsaved changes.
     *                      If true, the method detects changes in the form panel data and compares them
     *                      with the original item state. If changes are detected, a confirmation dialog
     *                      is displayed to the user.
     */
    fun backCloseAction(confirmCancel: Boolean = false) {
        var proceedClose = true
        if (confirmCancel) {
            try {
                formPanel?.let { formPanel ->
                    val s1 = Json.encodeToString(
                        configView.commonContainer.itemSerializer,
                        transformData(formPanel.getData())
                    )
                    if (s1 != origSerialized) {
                        proceedClose = confirm("Cancel and forget current changes ?")
                    }
                }
            } catch (e: Exception) {
                console.warn("exception = ", e)
            }
        }
        if (proceedClose) {
            // Toasts live on document.body, so a sticky one would outlive this view and follow the
            // user to the next screen.
            dismissStickyToasts()
            if (viewModal != null) {
                viewModal?.hide()
            } else if (window.history.length > 1) {
                window.history.back()
            } else {
                window.close()
            }
        }
    }

    /**
     * Binds the given property to the provided observable list and associates it with the tabulator.
     *
     * @param property The property of type `KProperty1` that refers to a collection of items to bind.
     * @param data The observable list that will be populated with the values from the provided property.
     *      This data can be assigned to the Tabulator data property
     * @return The current instance of the `Tabulator` to allow for method chaining.
     */
    @OptIn(ExperimentalSerializationApi::class)
    inline fun <reified V : Any> Tabulator<V>.bind(
        property: KProperty1<in T, Collection<V>>,
        data: ObservableList<V>,
    ): Tabulator<V> {
        item?.let { property.get(it) }?.let { x ->
            data.addAll(x)
        }
        tabulators[property.name] = TabulatorItem(this, V::class)
        return this
    }

    /**
     * Updates the data in the view based on the current CRUD task.
     *
     * Specifically, if the current task is a "Read" operation, this method retrieves the item
     * based on its ID, fetches the corresponding state from the API through the configured
     * query function, and updates the observable item with the retrieved data.
     *
     * Behavior:
     * - Checks if the task is set to `Read` in the current CRUD operation.
     * - If the `item` has an associated ID, the method calls the API function `apiItemQueryRead`
     *   with the item ID and filter settings.
     * - Updates the state of the observable item (`itemObservable`) with the fetched data
     *   to reflect the current state in the UI.
     */
    final override fun dataUpdate() {
        if (crudTask == CrudTask.Read) {
            item?._id?.let { id ->
                configView.commonContainer.getItemState(
                    apiItemFun = configView.apiItemFun,
                    apiItem = configView.commonContainer.apiItemQueryRead(id = id, apiFilter = apiFilter),
                ) {
                    itemObservable.value = it.item
                }
            }
        }
    }

    /**
     * Displays a default message in the container, typically used when no specific CRUD action
     * or default behavior is defined for the context.
     *
     * @param urlParams An optional parameter containing URL-specific information that may
     *                  influence the displayed default message or behavior.
     */
    open fun Container.displayDefault(urlParams: UrlParams?) {
        centeredMessage(gettext("no CRUD action ..."))
    }

    /**
     * Displays an edit button with a specified style, icon, and functionality.
     * This button includes a tooltip and an onClick event to navigate to a specific update view.
     *
     * The button:
     * - Is styled using the OUTLINESUCCESS button style.
     * - Includes a "fas fa-edit" icon.
     * - Displays a tooltip with a localized "Edit" message and a label.
     *
     * Behavior:
     * - Stops the click event from propagating further when clicked.
     * - Extracts the item's ID and navigates to the update view if the ID is available.
     *
     * This function relies on the `tr` method for translations, the `configView` for navigation configuration,
     * and expects an item and its `_id` to be accessible in the current context.
     */
    fun Nav.displayEditButton() {
        button(
            text = " ",
            icon = "fas fa-edit",
            style = ButtonStyle.OUTLINESUCCESS
        ) {
            size = ButtonSize.SMALL
            this.enableTooltip(TooltipOptions(title = "${tr("Edit")} ${configView.commonContainer.labelItem}"))
            onClick {
                it.stopPropagation()
                item?._id?.let { id ->
                    configView.navigateToViewItem(
                        apiItem = configView.commonContainer.apiItemQueryUpdate(
                            id = id,
                            apiFilter = apiFilter,
                        ),
                        vmode = ConfigViewContainer.VMode.modal
                    )
                }
            }
        }
    }

    /**
     * Displays a form in the container based on the specified CRUD operation. The form can be customized
     * to handle Create, Read, or Update tasks, and includes options for action buttons such as back, cancel,
     * and accept, depending on the provided task and the application's state.
     *
     * @param crudTask The CRUD operation context (e.g., Create, Read, Update) for which the form is displayed.
     *                 This determines the behavior and data handling of the form.
     */
    private suspend fun Container.displayForm(crudTask: CrudTask) {
        onBeforeDisplayForm(crudTask)
        formPanel = pageItemBody()
        if (!actionUpsert) {
            formPanel?.form?.fields?.forEach { entry ->
                entry.value.disabled = true
            }
        }
        flexPanel(direction = FlexDirection.ROW, justify = JustifyContent.CENTER, spacing = 20) {
            marginTop = 1.em
            if (actionUpsert) {
                buttonBack =
                    button(
                        text = gettext("Back"),
                        icon = "fas fa-reply",
                        style = ButtonStyle.OUTLINEPRIMARY
                    ) {
                        hide()
                        onClick {
                            backCloseAction()
                        }
                    }
                buttonCancel =
                    button(
                        text = gettext("Cancel"),
                        icon = "fas fa-xmark",
                        style = ButtonStyle.OUTLINEDANGER
                    ) {
                        onClick {
                            backCloseAction(confirmCancel = true)
                        }
                    }
                buttonAccept =
                    button(
                        text = gettext("Accept"),
                        icon = "fas fa-check",
                        style = ButtonStyle.OUTLINESUCCESS
                    ) {
                        onClick {
                            acceptUpsertAction()
                        }
                    }
            } else {
                if (!noBackButton) {
                    val histLength = window.history.length
                    val label = if (histLength > 1) gettext("Back") else gettext("Close")
                    button(text = label, icon = "fa-solid fa-arrow-rotate-left").onClick {
                        backCloseAction()
                    }
                }
            }
        }
        when (crudTask) {
            CrudTask.Create -> {
                item?.let {
                    formPanel?.setData(it)
                } ?: if (serverSeeds.isNotEmpty()) {
                    applyServerSeeds()
                } else Unit
            }

            CrudTask.Read -> {
                item?.let {
                    formPanel?.setData(it)
                }
                installUpdate()
            }

            CrudTask.Update -> {
                item?.let {
                    origSerialized = Json.encodeToString(
                        serializer = configView.commonContainer.itemSerializer,
                        value = it
                    )
                    formPanel?.setData(it)
                }
            }

            else -> {}
        }

        dataDisplayed = true

        onAfterDisplayForm(crudTask)
    }

    /**
     * Handles the transition from a Create action to an Update action when the item already exists.
     * Updates the URL parameters and browser history to reflect the change.
     */
    private fun handleCreateToUpdateTransition(itemResponse: ItemState<T>, itemResponseItem: T) {
        crudTask = CrudTask.Update
        urlParams.params["action"] = CrudTask.Update.name
        itemResponseItem._id.let {
            urlParams.params.set(
                propertyName = "id",
                value = Json.encodeToString(
                    configView.commonContainer.idSerializer,
                    it
                )
            )
        }
        val url = configView.url + urlParams.toEncodedUrlString()
        val stateObj =
            "{${itemResponse::class.simpleName}: \"${itemResponseItem._id}\"}".asDynamic()
        window.history.replaceState(stateObj, "createToUpdate", url)
    }

    /**
     * Displays an action denied message with a back button and a warning toast.
     */
    private fun Container.displayActionDenied(
        crudAction: CrudTask?,
        itemResponse: ItemState<T>,
        onBack: () -> Unit,
        toastOptions: ToastOptions,
    ) {
        flexPanel(
            direction = FlexDirection.COLUMN,
            justify = JustifyContent.CENTER,
            alignContent = AlignContent.CENTER,
            alignItems = AlignItems.CENTER,
            spacing = 10
        ) {
            div(
                content = "<i><b>[$crudAction]</b></i> ${gettext("action denied")}: <b>${itemResponse.msgError}</b>",
                rich = true
            ) {
                fontSize = 1.5.em
            }
            flexPanel(
                direction = FlexDirection.ROW,
                justify = JustifyContent.CENTER,
                spacing = 20
            ) {
                button(gettext("Back"), icon = "fa-solid fa-arrow-rotate-left") {
                    onClick {
                        onBack()
                    }
                }
            }
        }
        Toast.warning(
            message = itemResponse.msgError
                ?: "$crudAction ${gettext("action denied")} ...",
            options = toastOptions
        )
    }

    /**
     * Displays a page in the container by rendering a user interface based on the given URL parameters, page context, and CRUD task.
     * The method handles Create, Read, Update, and Delete actions, manages API calls, and updates the UI accordingly.
     */
    final override fun Container.displayPage() {
        vPanel(className = "showItem") {
            flexPanel(direction = FlexDirection.COLUMN, spacing = 10) {
                if (!noPageBanner) {
                    pageBanner()
                }
                crudTask?.let { crudAction ->
                    if (crudAction == CrudTask.Delete) {
                        item?.let { item ->
                            confirmDeleteView(
                                apiItemFun = configView.apiItemFun,
                                item = item,
                                apiFilter = apiFilter
                            )
                        } ?: Toast.danger("${configView.commonContainer.labelItem} ${gettext("not valid ...")}")
                    } else {
                        configView.commonContainer.callItemService(
                            apiItemFun = configView.apiItemFun,
                            crudTask = crudAction,
                            callType = CallType.Query,
                            id = urlParams.id?.let {
                                Json.decodeFromString(
                                    configView.commonContainer.idSerializer,
                                    it
                                )
                            },
                            apiFilter = apiFilter
                        ) { itemResponse ->
                            if (crudAction == CrudTask.Create) {
                                itemResponse.item?.let { itemResponseItem ->
                                    if (itemResponse.itemAlreadyOn) {
                                        handleCreateToUpdateTransition(itemResponse, itemResponseItem)
                                    }
                                } ?: itemResponse.serializedValueMap?.let { seeds ->
                                    serverSeeds = seeds.toMutableMap()
                                }
                            }
                            var alreadyBack = false
                            val toastOptions = ToastOptions(
                                position = ToastPosition.BOTTOMRIGHT,
                                stopOnFocus = true,
                                duration = TOAST_DURATION,
                                close = true,
                                callback = {
                                    if (!alreadyBack) window.history.back()
                                },
                                escapeHtml = true,
                            )
                            val crudAction1 = crudTask
                            if (itemResponse.hasError.not() && crudAction1 != null) {
                                itemObservable.value = itemResponse.item
                                AppScope.launch {
                                    displayForm(crudAction1)
                                }
                            } else {
                                displayActionDenied(
                                    crudAction = crudAction1,
                                    itemResponse = itemResponse,
                                    onBack = {
                                        alreadyBack = true
                                        window.history.back()
                                    },
                                    toastOptions = toastOptions,
                                )
                            }
                            itemResponse
                        }
                    }
                } ?: displayDefault(urlParams)
            }
        }
    }

    /**
     * Encodes the given ID into a JSON string representation using the specified serializer.
     *
     * @param id The ID to be encoded. If null, the function returns null. Defaults to the `_id` property of the `item`.
     * @return A JSON string representation of the encoded ID, or null if the input ID is null.
     */
    fun encodeId(id: ID? = item?._id): String? {
        return id?.let { Json.encodeToString(configView.commonContainer.idSerializer, id) }
    }

    /**
     * Validates the form panel data.
     *
     * @param data The data to be validated.
     * @return A SimpleState object indicating the validation result.
     */
    open fun formPanelValidate(data: T?): SimpleState =
        SimpleState(
            isOk = data != null,
            msgError = "${configView.commonContainer.labelItem} is null"
        )

    /**
     * Retrieves a TabulatorItem corresponding to the given property.
     *
     * @param property The property for which the TabulatorItem is to be retrieved.
     * @return The TabulatorItem associated with the specified property, or null if none exists.
     */
    @Suppress("UNCHECKED_CAST")
    fun <V : Any> getTabulator(property: KProperty1<in T, Collection<V>>): Tabulator<V>? =
        tabulators[property.name]?.tabulator as? Tabulator<V>?

    /**
     * Retrieves the tabulator value associated with the given property.
     *
     * @param property The property whose associated tabulator value is to be retrieved.
     * @return The tabulator value of the specified property, or null if no value is found.
     */
    @OptIn(ExperimentalSerializationApi::class)
    inline fun <reified V : Any> getTabulatorValue(
        property: KProperty1<in T, V>,
    ): V? {
        return tabulators[property.name]?.tabulator?.getData() as V?
    }

    /**
     * This method is triggered after the form associated with the given CRUD task has been displayed.
     * It can be overridden to implement additional processing or actions specific to the form display context.
     *
     * @param crudTask The CRUD operation context (e.g., Create, Read, Update, Delete) for which the form is displayed.
     */
    open fun onAfterDisplayForm(crudTask: CrudTask) {}

    /**
     * This method is triggered before the form associated with the given CRUD task is displayed.
     * It can be overridden to perform custom initialization or setup actions before the form display.
     *
     * @param crudTask The CRUD operation context (e.g., Create, Read, Update, Delete) for which the form is about to be displayed.
     */
    open suspend fun onBeforeDisplayForm(crudTask: CrudTask) {}

    /**
     * Builds and returns a FormPanel component within the current container.
     * This method is intended to be overridden in subclasses to provide custom
     * layout or UI elements for displaying or editing page items.
     *
     * @return A FormPanel of type T, which serves as the main container for the page item body.
     */
    abstract fun Container.pageItemBody(): FormPanel<T>

    /**
     * Transforms the given input data and returns the transformed result.
     *
     * @param item The input data of type T to be transformed.
     * @return The transformed data of type T.
     */
    open fun transformData(item: T): T = item

    /**
     * Creates a [FormPanel] bound to `configView.commonContainer.itemSerializer`, runs [init] against it, adds
     * it to the receiver container, and installs a [dataOverlayProvider][io.kvision.form.Form.dataOverlayProvider]
     * that merges three layers into every `getData()` payload:
     *
     * 1. [serverSeeds] residue — entries whose key has no matching form control.
     * 2. [hiddenFields] — client-added hidden values, filtered to non-form-bound keys; overrides layer 1 on
     *    key collision.
     * 3. Bound tabulators — `toPlainObj()` output keyed by the property name registered via this
     *    ViewItem's `Tabulator.bind` extension ([tabulators]); always authoritative for its key.
     *
     * Explicit [JsonNull] is preserved as `null` in the submission payload — consistent with
     * [applyServerSeeds], so a server seed of `JsonNull` means "explicit null" end-to-end.
     *
     * @param init Configuration block invoked on the freshly created [FormPanel].
     * @return The configured [FormPanel], already added to the receiver container.
     */
    fun Container.viewFormPanel(init: (FormPanel<T>).() -> Unit): FormPanel<T> {
        val panel = FormPanel(
            serializer = configView.commonContainer.itemSerializer,
        )
        init.invoke(panel)
        this.add(panel)
        panel.dataOverlayProvider = {
            buildMap {
                serverSeeds.forEach { (key, element) ->
                    if (panel.form.fields[key] == null) put(key, jsonElementToSubmitValue(element))
                }
                hiddenFields.forEach { (key, element) ->
                    if (panel.form.fields[key] == null) put(key, jsonElementToSubmitValue(element))
                }
                tabulators.forEach { (key, tabulatorItem) ->
                    put(key, tabulatorItem.toPlainObj())
                }
            }
        }
        return panel
    }

    /**
     * Registers a hidden field to be merged into every form submission without a visible control.
     *
     * The value is encoded as a [JsonElement] (primitive, nested `@Serializable` object, list, or [JsonNull])
     * and stored in the persistent [hiddenFields] bucket. On `getData()`, [viewFormPanel]'s
     * [dataOverlayProvider][io.kvision.form.Form.dataOverlayProvider] merges it into the outgoing payload —
     * use this for parent IDs, tenant IDs, or any contextual metadata the server expects but that shouldn't
     * appear as a form control.
     *
     * Keys colliding with an existing form control are **silently skipped** by the overlay; a hidden-field
     * override for a visible control is considered a mistake. Use [serverSeeds] if you want to pre-populate
     * a visible control, or reshape your form if you need a hidden-but-submittable value.
     *
     * Keys colliding with a bound tabulator are **overwritten** by that tabulator's `toPlainObj()` output —
     * tabulators are always authoritative for their key. Don't use this helper for tabulator-bound
     * properties; the value would be silently lost on submit.
     *
     * ### Polymorphic / star-projected property types
     * Values of the `IBaseId` family (`OId` / `StringId` / `IntId` / `LongId`) are encoded through their
     * real serializers via a non-reified fast path ([encodeBaseIdOrNull]), so the canonical parent-ID
     * idiom works even when the property type is star-projected — e.g. a polymorphic FK
     * `OId<out IHeader<*>>`, where reified `serializer<V>()` would otherwise throw
     * `IllegalArgumentException: Star projections in type arguments are not allowed` **at runtime**,
     * aborting the display cycle with a blank form. For star-projected types *outside* the id family,
     * use the non-reified `addSerializedValue(property, element: JsonElement)` overload and encode the
     * value yourself.
     *
     * @param property Property whose name becomes the map key.
     * @param value Value to encode and include in every subsequent submission payload.
     */
    inline fun <reified V> addSerializedValue(property: KProperty1<T, V?>, value: V) {
        hiddenFields[property.name] = encodeHiddenFieldValue(property.name, value)
    }

    /**
     * Non-reified escape hatch of [addSerializedValue]: registers a pre-encoded [element] under
     * [property]'s name in the persistent [hiddenFields] bucket, with the exact same overlay semantics
     * as the reified overload (merged into every submission; skipped if a form control shares the key;
     * overwritten by a bound tabulator).
     *
     * Use it when the property's declared type defeats reified serializer resolution and is not covered
     * by the id-family fast path — i.e. star-projected generics outside `IBaseId`. The caller encodes:
     * for an id that would be `JsonPrimitive(value.id)`; for a `@Serializable` payload,
     * `Json.encodeToJsonElement(TheSerializer, value)`.
     *
     * @param property Property whose name becomes the map key (its value type is irrelevant — only the
     *   name is used, which is why a star-projected property is accepted here).
     * @param element Pre-encoded value to include in every subsequent submission payload.
     */
    fun addSerializedValue(property: KProperty1<T, *>, element: JsonElement) {
        hiddenFields[property.name] = element
    }

    /**
     * Drains [serverSeeds] against form controls during a Create display cycle, mirroring the dispatch
     * that [io.kvision.form.Form.setData] performs in Update mode so Create and Update share semantics:
     *
     * 1. [JsonElement] seeds are unwrapped via [jsonElementToSubmitValue] — the same helper
     *    [viewFormPanel]'s overlay uses, so both paths produce identical Kotlin values for the same seed.
     * 2. Dispatch by form control type:
     *    - [DateFormControl] → parse the ISO string via the native [kotlin.js.Date] constructor (which
     *      honours ISO 8601 with fractional seconds and offset) and assign to `.value`; only runs when
     *      the seed is a [JsonPrimitive] string, otherwise falls through to `else` to surface mismatches
     *      as a typed setValue rather than a silent cast to garbage. The KVision `String.toDateF()`
     *      helper is deliberately *not* used here because it delegates to `fecha.js` with a configurable
     *      format pattern that does not match ISO 8601 (literal `T` separator fails) — a mismatch makes
     *      `toDateF()` silently return `Date()` ("now") instead of the seeded value.
     *    - [KFilesFormControl] → decode directly from the [JsonElement] via kotlinx serialization.
     *    - Otherwise → hand the decoded value to `setValue`. For `bindCustom`-backed selectors (e.g.
     *      `tomSelectRemote` bound to an `OId` property) this is the 24-char hex / raw int the widget
     *      stores internally and later sends back to its remote service as `initial`.
     *
     * Each seed is applied inside a per-iteration `try/catch`: a single malformed seed logs a warning
     * and is left in [serverSeeds] rather than aborting the whole drain and stranding earlier applied
     * keys. Only successfully-applied keys are removed from the bucket. Because a failed seed reached
     * the `try` block only after matching a form control, the overlay (which filters on "no matching
     * form control") will skip it on submit — so failures on form-bound keys are **logged and
     * dropped**, not forwarded. Failures on keys without a matching control never enter the `try` to
     * begin with; they sit in the residue and ride the overlay as JsonElement-decoded values.
     *
     * Field-level [io.kvision.form.FormFieldConverter]s registered via `bind(... converter = ...)` are
     * *not* consulted here because [io.kvision.form.Form.fieldConverters] is private — Update-mode parity
     * for custom converters would require an accessor from KVision. None of fsLib's public bindings
     * register a converter today, so the asymmetry is theoretical; revisit if that changes.
     *
     * **Limitation:** form controls added after [applyServerSeeds] has already run (e.g. conditional
     * fields revealed by a toggle) do not receive their server seed. The key is left in [serverSeeds]
     * as residue, then filtered out by the overlay once the control appears — silent loss. Rare in
     * fsLib use today; if it matters, expose a public `reapplySeeds()` hook.
     *
     * [JsonNull] seeds are handled at the top of the dispatch and always result in
     * `formControl.setValue(null)` regardless of control type, so explicit server nulls clear the
     * widget uniformly (KFiles, DateTime, selectors, etc.).
     *
     * ### TODO(upstream) — make per-control seed dispatch shareable with KVision
     *
     * **The problem.** KVision's [io.kvision.form.Form] has no entry point for "apply these JsonElement
     * values to the controls that happen to exist, using the same dispatch [io.kvision.form.Form.setData]
     * already implements internally". We re-enumerate control types ([DateFormControl],
     * [KFilesFormControl], …) here and will have to add a branch for every future `*FormControl` KVision
     * ships — each omission is a silent misapplication; regression surface grows linearly in KVision's
     * form-controls catalog. Additionally, [io.kvision.form.Form.fieldConverters] is `private`, so
     * user-registered converters passed to `bind(... converter = ...)` are not consulted here — the
     * known gap called out above.
     *
     * **Alternatives considered.**
     * - *Stub-fill + `setData(T)`.* Merge [serverSeeds] into a [kotlinx.serialization.json.JsonObject],
     *   fill sensible defaults for missing non-nullable constructor params, `decodeFromJsonElement` into a
     *   full `T`, call `setData`. Works today with no upstream changes and inherits every KVision control
     *   type for free. Downsides: (1) needs a stub policy per Kotlin primitive / `OId` / custom type; (2)
     *   the stubs then show up in the form unless explicitly cleared post-setData. The "partial `T` has
     *   no first-class representation" problem is real — but it's the same problem we already accept in
     *   [applyServerSeeds] (missing required fields leave the form partially empty), so this isn't
     *   strictly worse than the status quo.
     * - *Reflect into `fieldConverters`.* Technically possible on JVM via reflection, unreliable on JS.
     *   Fragile; rejected.
     *
     * **Pragmatic upstream asks, in merge-likelihood order** (the original "add a public `setSeeds`" ask
     * reads as a design debate; these are all-but-mechanical changes KVision maintainers can accept
     * without taking a philosophical position on sparse hydration):
     *
     * 1. **Expose [io.kvision.form.Form.fieldConverters] as `protected`** — closes the converter-parity
     *    gap on its own, ~5-line PR, no new API surface. Smallest possible ask with concrete benefit.
     * 2. **Add `protected open fun setSeeds(seeds: Map<String, JsonElement>)` on [io.kvision.form.Form]**
     *    that walks the shared `setData` dispatcher and applies values only to matching controls. Protected
     *    scope = subclasses opt in locally; KVision's public API surface is untouched. fsLib then reduces
     *    [applyServerSeeds] to `panel.form.setSeeds(serverSeeds)` plus residue bookkeeping.
     * 3. **Public `setSeeds`** — north star, not the opening ask.
     *
     * File (1) as a KVision issue first. Even if (2)/(3) never land, (1) alone removes the converter
     * asymmetry from the footnote above and is worth the PR. Until any of this happens, every new
     * KVision-provided `*FormControl` needs a corresponding branch in the `when` below.
     */
    private fun applyServerSeeds() {
        val panel = formPanel ?: return
        if (debug) console.log("applyServerSeeds: ${serverSeeds.size} seed(s) pending")
        val applied = mutableSetOf<String>()
        serverSeeds.forEach { (key, element) ->
            val formControl = panel.form.fields[key] ?: return@forEach
            try {
                when {
                    // Top-level null branch: every control type receives `null` uniformly when the
                    // server seeds an explicit JsonNull. Without this, KFilesFormControl would fall
                    // into the KFiles branch and throw on decodeFromJsonElement(ListSerializer(...),
                    // JsonNull) — caught by the try/catch, but logged+dropped instead of clearing
                    // the widget. DateFormControl used to clear "by accident" via the isString
                    // guard + else fallback; now the semantics are explicit for all controls.
                    element is JsonNull -> formControl.setValue(null)

                    formControl is DateFormControl && element is JsonPrimitive && element.isString -> {
                        // Parse the wire ISO 8601 string via the native JS Date constructor, NOT via
                        // [io.kvision.types.toDateF]. The KVision helper delegates to the `fecha.js`
                        // library with a format pattern (default `"YYYY-MM-DD HH:mm:ss"`) that does not
                        // match ISO 8601 — the literal `T` separator between date and time fails the
                        // format regex, so `fecha.parse` returns `false` and `toDateF` silently falls
                        // back to `Date()` (i.e. "now"). Prior to this fix the widget silently rendered
                        // the current browser time (or a partially-parsed garbage Date) instead of the
                        // seeded value. [kotlin.js.Date] parses ISO 8601 correctly including fractional
                        // seconds and timezone offsets — which is the wire format `OffsetDateTime`
                        // emits on serialize.
                        //
                        // Invalid ISO strings produce a Date whose `getTime()` is `NaN` — a "valid-looking"
                        // object that the widget stores without complaint and later round-trips as
                        // `"Invalid Date"`. Trip the outer try/catch with an explicit error so the seed
                        // is logged and left in the residue instead of silently corrupting the form.
                        val parsed = kotlin.js.Date(element.content)
                        if (parsed.getTime().isNaN()) {
                            error("DateFormControl seed for '$key' is not a valid ISO 8601 date: '${element.content}'")
                        }
                        formControl.value = parsed
                    }

                    formControl is KFilesFormControl -> formControl.value =
                        Json.decodeFromJsonElement(ListSerializer(KFile.serializer()), element)

                    else -> formControl.setValue(jsonElementToSubmitValue(element))
                }
                applied += key
            } catch (t: Throwable) {
                // Pass the throwable as a second argument so browser devtools expose the stack trace
                // rather than just the message string.
                console.warn("applyServerSeed: failed for key='$key'; logged and dropped", t)
            }
        }
        serverSeeds -= applied
        if (debug && applied.isNotEmpty()) {
            console.log("applyServerSeeds: applied ${applied.joinToString(prefix = "[", postfix = "]")}")
        }
    }
}
