package com.fonrouge.fullStack.view

import com.fonrouge.base.api.CrudTask
import com.fonrouge.base.api.IApiFilter
import com.fonrouge.base.lib.iconCrud
import com.fonrouge.base.model.BaseDoc
import com.fonrouge.base.model.UserSessionParams
import com.fonrouge.base.state.ItemState
import com.fonrouge.fullStack.config.ConfigView
import com.fonrouge.fullStack.help.IHelpModule
import com.fonrouge.fullStack.layout.helpButtons
import com.fonrouge.fullStack.lib.UrlParams
import com.fonrouge.fullStack.lib.dismissStickyToasts
import com.fonrouge.fullStack.lib.toEncodedUrlString
import com.fonrouge.fullStack.lib.toast
import com.fonrouge.fullStack.tabulator.TabulatorMenuItem
import com.fonrouge.fullStack.view.KVWebManager.frontEndAppName
import io.kvision.core.*
import io.kvision.dropdown.dropDown
import io.kvision.html.*
import io.kvision.modal.Modal
import io.kvision.navbar.nav
import io.kvision.navbar.navbar
import io.kvision.offcanvas.Offcanvas
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.state.ObservableValue
import io.kvision.state.bind
import io.kvision.utils.em
import io.kvision.utils.perc
import io.kvision.utils.px
import kotlinx.browser.window
import kotlinx.coroutines.launch
import web.dom.document
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Represents an abstract base class for creating views with configurable display elements.
 *
 * This class provides functionality to manage URL parameters, display labels,
 * periodic updates, API filters, banners, and navigation buttons.
 *
 * @param FILT The type of the API filter that implements IApiFilter.
 * @property configView The configuration view instance providing necessary configurations.
 */
abstract class View<FILT : IApiFilter<*>>(
    open val configView: ConfigView<*, FILT>,
) {
    companion object {
        /**
         * A variable that holds the parameters related to a user's session.
         * It can store a nullable instance of the UserSessionParams class, which may contain data
         * such as session tokens, user authentication details, or configuration settings specific
         * to the user's session.
         */
        var userSessionParams: UserSessionParams? = null

        /**
         * A nullable variable that holds a suspending function to update user session parameters.
         * This function, when defined, is expected to return an [ItemState] containing the updated [UserSessionParams].
         * If set to null, no action is performed for updating session parameters.
         */
        var updateUserSessionParams: (suspend () -> ItemState<UserSessionParams>)? = null

        /**
         * Represents the default layout configuration for banners.
         * The layout determines the orientation or arrangement style.
         * This variable is initialized with `BannerLayout.Vertical` as its default value.
         */
        var defaultBannerLayout: BannerLayout = BannerLayout.Vertical

        /**
         * Registered FAB (Floating Action Button) extension factories invoked in
         * [startDisplayPage] for all main, non-modal views — after [helpButtons].
         *
         * Each factory receives: the container to add widgets to, the view's class
         * name, and the view's display label. Register at app startup:
         * ```kotlin
         * View.fabExtensions.add { container, viewClassName, viewLabel ->
         *     container.tktButton(viewName = viewClassName, viewLabel = viewLabel)
         * }
         * ```
         */
        val fabExtensions: MutableList<(Container, String, String) -> Unit> = mutableListOf()
    }

    /**
     * Represents the layout style for displaying a banner.
     *
     * The `bannerLayout` property specifies the orientation or configuration of the banner within a UI.
     * It defines the structure in which banner components are arranged, allowing customization such as
     * vertical or horizontal layouts.
     *
     * This property is initialized with a default layout style of `BannerLayout.Vertical`.
     */
    open val bannerLayout: BannerLayout = defaultBannerLayout

    /**
     * Retrieves the CSS class name for the banner title based on the banner layout type.
     *
     * This property determines the class name used to style the banner title, depending on
     * whether the banner layout is horizontal or vertical. If the layout is horizontal,
     * it returns a specific class name. If the layout is vertical, it returns null.
     */
    open val bannerTitleClass: String?
        get() = when (bannerLayout) {
            BannerLayout.Horizontal -> "col-4"
            BannerLayout.Vertical -> null
        }

    /**
     * Represents the CSS class for the banner legend based on the banner layout.
     * The value is determined dynamically depending on whether the banner layout
     * is horizontal or vertical.
     *
     * @return A CSS class string ("col-8") if the banner layout is horizontal;
     *         otherwise, it returns null for a vertical banner layout.
     */
    open val bannerLegendClass: String?
        get() = when (bannerLayout) {
            BannerLayout.Horizontal -> "col-8"
            BannerLayout.Vertical -> null
        }

    /**
     * Represents the layout orientation options for a banner component.
     *
     * BannerLayout defines how the content of a banner should be displayed, either horizontally or vertically.
     */
    enum class BannerLayout {
        Horizontal,
        Vertical
    }

    /**
     * Represents a set of URL parameters associated with the `View` class.
     *
     * This variable provides access to the `UrlParams` object, enabling the retrieval, management,
     * and manipulation of query parameters within the associated view. The `UrlParams` object allows
     * operations such as obtaining specific parameters, adding or updating parameters, and performing
     * CRUD-related tasks (e.g., creating or updating an entry).
     *
     * It may also include logic for interpreting and handling parameters to determine specific actions
     * or states related to the view, such as CRUD operations or entity identification.
     *
     * Nullable, as URL parameters might not always be initialized or required for a given view.
     */
    var urlParams: UrlParams = UrlParams()

    /**
     * A computed property that determines whether the current CRUD task is either a creation or an update operation.
     *
     * This property evaluates to `true` if the assigned `crudTask` value is either `CrudTask.Create` or `CrudTask.Update`.
     * It is specifically used to identify scenarios where modifications (creation or update) are being performed.
     */
    val actionUpsert: Boolean
        get() {
            return crudTask in listOf(CrudTask.Create, CrudTask.Update)
        }

    /**
     * A nullable property representing the current CRUD task for the view.
     *
     * This property lazily resolves a `CrudTask` value based on the `action` parameter
     * obtained from `urlParams`. If the property is accessed for the first time and its value
     * is null, it attempts to find a corresponding entry in the `CrudTask` enum class using
     * the `action` parameter value and assigns it to the property.
     *
     * @see CrudTask
     */
    var crudTask: CrudTask? = null
        get() {
            if (field == null) {
                field = CrudTask.entries.find { it.name == urlParams.params["action"] }
            }
            return field
        }

    open val label: String get() = configView.label

    /**
     * Controls whether automatic help discovery is enabled for this view.
     * When true (default), the view auto-discovers available help (tutorial/context)
     * via RPC based on the view's class name.
     */
    open val helpEnabled: Boolean = true

    /**
     * The help module this view belongs to, or `null` if it is not grouped.
     *
     * When set, help files are looked up under `help-docs/{module.slug}/{ViewClassName}/`
     * instead of `help-docs/{ViewClassName}/`. Override in subclasses to assign a module:
     *
     * ```kotlin
     * override val helpModule: IHelpModule = AppModule.Importaciones
     * ```
     */
    open val helpModule: IHelpModule? = null

    @OptIn(ExperimentalTime::class)
    internal var lastUiActivity: Instant = Clock.System.now()
    var mainView: Boolean = false
    var navButtonCancel: Button? = null
    var navButtonAccept: Button? = null
    var navButtonBack: Button? = null
    val navigoUrlWithParams: String
        get() {
            return configView.url + urlParams
        }

    /**
     * Set to true to don't display the [pageBanner]
     */
    var noPageBanner = false

    /**
     * Indicates whether the data view should perform periodic updates.
     *
     * This boolean flag is used to enable or disable automatic periodic updates
     * for the data view. When set to true, the data view will periodically
     * refresh its content according to the specified interval.
     *
     * It can be particularly useful in scenarios where real-time data
     * synchronization is required or to ensure that the information displayed
     * is always up-to-date.
     *
     * The default value is null, which means periodic updates are not configured.
     */
    open val periodicUpdateDataView: Boolean? = null

    /**
     * The interval, in seconds, at which the view will be periodically updated.
     * This variable determines how often the view's data will be refreshed.
     *
     * Default value is set to 5 seconds.
     */
    var periodicUpdateViewInterval = 5
    private val pageBannerUpdateObservable = ObservableValue(0)

    /**
     * Observable representation of the API filter used by the view.
     *
     * This property lazily initializes an observable value for the API filter, allowing
     * the system to react to changes in the filter state dynamically. It is tied to the
     * `apiFilterInstance` method for the default filter configuration.
     *
     * The observable value is primarily used within the view to monitor and respond to
     * updates to the filtering logic, which may impact the displayed data or UI components.
     */
    val apiFilterObservable: ObservableValue<FILT> by lazy {
        ObservableValue(configView.commonContainer.apiFilterInstance())
    }

    private var apiFilterInitialized = false

    /**
     * A variable representing the current API filter used within the view.
     *
     * The `apiFilter` is observable and can be set or retrieved programmatically. It's primarily used
     * for filtering API data based on specific parameters defined by the `FILT` type.
     */
    var apiFilter: FILT
        get() {
            if (!apiFilterInitialized) {
                apiFilterInitialized = true
                apiFilterInit()?.let { apiFilterObservable.value = it }
            }
            return apiFilterObservable.value
        }
        set(value) {
            if (apiFilterObservable.value != value) apiFilterObservable.value = value
        }

    val apiFilterFromUrl: FILT?
        get() = urlParams.pullUrlParam(
            serializer = configView.commonContainer.apiFilterSerializer,
            key = "apiFilter"
        )

    /**
     * assignable var that contains a defined [Offcanvas] filter area, if any
     */
    @Suppress("MemberVisibilityCanBePrivate")
    var offCanvasFilter: Offcanvas? = null

    /**
     * assignable var that indicates if the filter button in the tabulator's toolbar will be displayed
     */
    var hasOffCanvasFilterView: Boolean = false

    /**
     * Represents whether the current view is in modal state or not.
     * A value of `true` indicates that the view is modal, `false` indicates it is not modal,
     * and `null` implies the state is undefined or not set.
     */
    var viewModal: Modal? = null

    /**
     * Adds a page list body to the container.
     *
     * This method integrates the `pageListBody` of the provided `viewList` into the container,
     * allowing for the display of a list-based UI structure. An optional initialization
     * block can be applied to further configure the `viewList`.
     *
     * @param viewList The view list whose page list body will be added to the container.
     * @param init An optional initialization block to configure the `viewList`. Defaults to null.
     */
    @Suppress("unused")
    fun <T : BaseDoc<ID>, ID : Any, FILT : IApiFilter<MID>, MID : Any> Container.addPageListBody(
        viewList: ViewList<T, ID, FILT, MID>,
        init: (ViewList<T, ID, FILT, MID>.() -> Unit)? = null,
    ) {
        with(viewList) {
            pageListBody()
        }
        init?.invoke(viewList)
    }

    /**
     * Adds a list of views to the container and initializes them.
     *
     * This method invokes the `startDisplayPage` function of the provided view list to prepare
     * the content for display. Optionally, a master view item can be associated with the view
     * list, and an initialization block can be applied to perform additional configuration.
     *
     * @param viewList The view list to be added and displayed within the container.
     * @param masterViewItem An optional master view item associated with the view list. Defaults to null.
     * @param init An optional initialization block to configure the view list. Defaults to null.
     */
    @Suppress("unused")
    fun <FILT : IApiFilter<MID>, MID : Any> Container.addViewList(
        viewList: ViewList<*, *, FILT, MID>,
        masterViewItem: ViewItem<out BaseDoc<MID>, MID, *>? = null,
        init: ((ViewList<*, *, FILT, MID>).() -> Unit)? = null,
    ) {
        viewList.apply { startDisplayPage() }
        masterViewItem?.let {
            viewList.masterViewItem = it
        }
        init?.invoke(viewList)
    }

    /**
     * Converts the current API filter to a URL string and optionally updates the browser's history state.
     *
     * This method serializes the current API filter to a URL parameter, appends it to the base URL,
     * and optionally replaces the browser's history state with the updated URL.
     *
     * @param replaceState A boolean indicating whether the browser's history state should be updated
     *                     with the new URL. If true, the history state is replaced with the modified URL.
     * @return A string representing the encoded URL with the API filter parameter appended.
     */
    fun apiFilterToPageUrl(replaceState: Boolean): String {
        configView.pairParam("apiFilter", configView.commonContainer.apiFilterSerializer, apiFilter)
            .let { pair ->
                urlParams.params[pair.first] = pair.second
            }
        val url = (configView.url + urlParams.toEncodedUrlString()).asDynamic()
        if (replaceState) {
            @Suppress("UNUSED_VARIABLE", "unused")
            val stateObj = "{apiFilter: toUrl}".asDynamic()
            js("""history.replaceState(stateObj,"createToUpdate",url)""")
        }
        return url
    }

    /**
     * Creates a banner legend component inside the container.
     *
     * This method is used to add a banner legend to the container, typically
     * for displaying important information or notices in a prominent way.
     * The styling and content of the banner legend can be customized as needed.
     */
    open fun Container.bannerLegend() {}

    /**
     * Creates and returns a container panel configured as a banner title layout
     * based on the specified `bannerLayout`. The layout can be either horizontal
     * or vertical, determined by the `BannerLayout` property.
     *
     * @return A `SimplePanel` instance configured according to the `bannerLayout`.
     */
    open fun Container.bannerTitle(): SimplePanel = when (bannerLayout) {
        BannerLayout.Horizontal -> vPanel(
            justify = JustifyContent.CENTER,
            alignItems = AlignItems.CENTER,
        )

        BannerLayout.Vertical -> hPanel(
            justify = JustifyContent.CENTER,
            alignItems = AlignItems.CENTER,
        )
    }

    /**
     * Creates and returns a `Container` component styled and populated
     * based on the `bannerLayout` property. The class name of the resulting
     * container is determined dynamically: "row" if the layout is horizontal
     * and "col" if the layout is vertical. Additionally, the container has
     * a flexGrow property set to 1 for layout flexibility.
     *
     * @return A styled `Container` instance with layout-related configurations.
     */
    open fun Container.bannerTitleLegendContainer(): Container =
        div(className = if (bannerLayout == BannerLayout.Horizontal) "row" else "col") {
            flexGrow = 1
        }

    /**
     * Renders the main content of the view within the specified container.
     *
     * This function serves as an abstract method to be implemented by subclasses, defining
     * the logic for displaying the view's content in the given `Container`. It is typically
     * called in the context of setting up or updating the UI.
     */
    abstract fun Container.displayPage()

    /**
     * Constructs and returns a label for the banner based on the provided API filter.
     * This function is designed to create a string label specific to the given filter configuration.
     *
     * @param apiFilter The API filter instance that will be used to generate the banner's label.
     * @return A string representing the label for the banner.
     */
    open suspend fun labelBanner(apiFilter: FILT): String = label

    /**
     * Initiates the display of a page within the container.
     *
     * The method sets up the necessary hooks and bindings to ensure the page is properly rendered
     * and updated. It begins by attaching a pre-dispose hook using `addBeforeDisposeHook` to call
     * `onBeforeDispose` for cleanup tasks. The `onBeforeDisplayPage` method is called to allow
     * any pre-rendering logic or UI configuration. It then invokes the `displayPage` method
     * to render the main content of the page.
     *
     * A binding to `apiFilterObservable` is established to monitor changes in the API filter.
     * This triggers the `onApiFilterChange` method, which can be used to handle any updates,
     * and `apiFilterToUrl` to update the browser's URL appropriately. After the initial rendering
     * and setup, the `onAfterDisplayPage` method is called to perform any post-rendering actions.
     *
     * @param mainView A boolean indicating whether the current view should be treated as the main view.
     *                 If true, the API filter will be added to the URL parameters upon changes.
     */
    @OptIn(ExperimentalTime::class)
    fun Container.startDisplayPage(mainView: Boolean = false) {
        this@View.mainView = mainView
        div {
            addBeforeDisposeHook {
                // Sticky toasts are appended to document.body and have no timeout, so a refusal
                // raised by this view would otherwise follow the user to the next screen. This sits
                // in the hook rather than in `onBeforeDispose` so that a subclass overriding that
                // method cannot drop it, and covers every teardown path — modal close control and
                // browser navigation included, not just `backCloseAction`.
                dismissStickyToasts()
                onBeforeDispose()
            }
            window.addEventListener("mousemove", {
                lastUiActivity = Clock.System.now()
            })
            window.addEventListener("keydown", {
                lastUiActivity = Clock.System.now()
            })
            onBeforeDisplayPage(this@startDisplayPage)
            this@startDisplayPage.displayPage()
            val viewClassName = this@View::class.simpleName ?: ""
            if (helpEnabled && viewClassName.isNotEmpty()) {
                this@startDisplayPage.helpButtons(
                    viewClassName = viewClassName,
                    viewLabel = label,
                    moduleSlug = helpModule?.slug,
                    showTutorial = crudTask == null || actionUpsert
                )
            }
            if (mainView && viewModal == null && viewClassName.isNotEmpty()) {
                fabExtensions.forEach { ext -> ext(this@startDisplayPage, viewClassName, label) }
            }
            bind(apiFilterObservable) {
                onApiFilterChange()
                apiFilterToPageUrl(mainView)
            }
            onAfterDisplayPage()
        }
    }

    /**
     * This method is called immediately after rendering the page's content.
     *
     * Override this method to execute any post-rendering logic or to update the UI components
     * that depend on the page's content.
     */
    open fun onAfterDisplayPage() {}

    /**
     * Initializes and returns the default or custom API filter for the view.
     *
     * This method can be overridden in subclasses to provide a specific implementation
     * for initializing an API filter. The returned filter is typically used
     * to constrain or define the scope of API requests.
     *
     * @return The initialized API filter of type FILT, or null if no filter is specified.
     */
    open fun apiFilterInit(): FILT? = null

    /**
     * This method is called when there is an update to the API filter.
     *
     * The primary function of this method is to invoke the `updateBanner` method,
     * which refreshes the content of the banner legend tied to the current view.
     *
     * Override this method to implement additional logic that should be executed
     * when the API filter is updated.
     */
    open fun onApiFilterChange() {
        updateBanner()
    }

    /**
     * This method is called before rendering the page's content.
     *
     * Override this method to execute any pre-rendering logic or to configure the UI components
     * that should be displayed on the page.
     *
     * @param container The DSL container in which the view will be rendered.
     */
    open fun onBeforeDisplayPage(container: Container) {}

    /**
     * This method is called before the disposal of the view.
     *
     * Override this method to execute any logic that needs to run before the view is disposed of.
     * It can be used to clean up resources, save state, or perform other teardown operations.
     */
    open fun onBeforeDispose() {}

    /**
     * Constructs a page banner UI component typically used for displaying titles, legends,
     * and navigation elements of a page within a container.
     *
     * This method adds a banner to a `Container` with the following elements:
     * - A title and navigation section.
     * - An optional dropdown menu or context menu for additional actions.
     * - Navigation buttons for certain CRUD operations.
     * - An optional off-canvas filter view for additional filtering capability.
     *
     * The page banner adapts dynamically based on the state of the implementing `View`. It subscribes
     * to observables to update its content and behavior accordingly. Examples include updating the URL
     * or label dynamically and conditionally displaying menu items or buttons.
     *
     * Behavior:
     * - The banner supports a title with optional navigation links and an icon based on the CRUD task.
     * - Legend or auxiliary content can be displayed below the title.
     * - When used with a `ViewItem`, the banner conditionally shows buttons for actions such as
     *   cancel, accept, or back navigation, based on the CRUD task and upsert state.
     * - A dropdown menu for contextual actions can be displayed if the `View` provides menu items.
     * - If an off-canvas filter is provided, clicking on the legend triggers the display of the filter.
     *
     * Modifies:
     * - Dynamically binds content to observables to ensure real-time updates.
     * - Adjusts layout, visibility, and interactivity of various elements based on the state of the `View`.
     *
     * Dependencies:
     * - Relies on observables like `pageBannerUpdateObservable`, `dropDownElementsObs`,
     *   and `apiFilterObservable` for dynamic updates and state changes.
     * - Assumes the `Container` is compatible with the structure and UI elements involved.
     */
    fun Container.pageBanner() {
        navbar(bgColor = BsBgColor.LIGHT, className = "view-banner").bind(
            observableState = pageBannerUpdateObservable,
            removeChildren = true
        ) {
            if (this@View is ViewItem<*, *, FILT> && this@View.crudTask == CrudTask.Read) {
                nav {
                    dropDown(
                        text = "",
                        icon = "fas fa-ellipsis-vertical",
                        style = ButtonStyle.OUTLINEDARK,
                        arrowVisible = false,
                        className = "tabulator-menu"
                    ) {
                        enableTooltip(options = TooltipOptions("Context Menu", triggers = listOf(Trigger.HOVER)))
                        size = ButtonSize.XSMALL
                        marginRight = 0.5.em
                        this@View.dropDownElementsObs.subscribe { elements ->
                            removeAll()
                            if (elements.isNullOrEmpty()) {
                                hide()
                            } else {
                                show()
                                elements.forEachIndexed { index: Int, tabulatorMenuItem: TabulatorMenuItem ->
                                    if (tabulatorMenuItem.header) {
                                        header(tabulatorMenuItem.rawLabel)
                                    } else if (tabulatorMenuItem.separator != true) {
                                        label(
                                            content = " ${tabulatorMenuItem.rawLabel}",
                                            className = "tabulator-menu-item"
                                        ) {
                                            paddingLeft = 1.em
                                            paddingRight = 1.em
                                            width = 100.perc
                                            tabulatorMenuItem.icon?.let { icon ->
                                                icon(icon) {
                                                    marginRight = 0.5.em
                                                }
                                            }
                                            onClick {
                                                tabulatorMenuItem.action(it, null)
                                            }
                                        }
                                    } else if (index < elements.lastIndex)
                                        div(className = "tabulator-menu-separator")
                                }
                            }
                        }
                    }
                }
            }
            bannerTitleLegendContainer().apply {
                div(className = "view-banner-title ${bannerTitleClass ?: ""}") {
                    alignContent = AlignContent.CENTER
                    bannerTitle().apply {
                        link(
                            label = this@View.label,
                            url = navigoUrlWithParams,
                            icon = if (this@View is ViewItem<*, *, FILT>) iconCrud(crudTask) else null,
                        ) {
                            setStyle("text-decoration", "none")
                            apiFilterObservable.subscribe {
                                url = apiFilterToPageUrl(replaceState = false)
                                AppScope.launch {
                                    label = labelBanner(it)
                                }
                            }
                            if (this@View is ViewItem<*, *, FILT>) {
                                (this@View as ViewItem<*, *, FILT>).itemObservable.subscribe {
                                    AppScope.launch {
                                        label = labelBanner(apiFilter)
                                    }
                                }
                            }
                        }
                    }
                }
                div(className = "view-banner-legend ${bannerLegendClass ?: ""}") {
                    bannerLegend().apply {
                        offCanvasFilter?.let { offCanvasFilter ->
                            cursor = Cursor.POINTER
                            onClick { offCanvasFilter.show() }
                        }
                    }
                }
            }
            nav(rightAlign = true) {
                if (this@View is ViewItem<*, *, FILT>) {
                    if (actionUpsert) {
                        navButtonBack = button(
                            text = " ",
                            icon = "fas fa-reply",
                            style = ButtonStyle.OUTLINEPRIMARY
                        ) {
                            hide()
                            onClick {
                                this@View.backCloseAction()
                            }
                        }
                        navButtonCancel = button(
                            text = " ",
                            icon = "fas fa-xmark",
                            style = ButtonStyle.OUTLINEDANGER
                        ) {
                            fontSize = 0.5.em
                            onClick {
                                this@View.backCloseAction(confirmCancel = true)
                            }
                        }
                        navButtonAccept =
                            button(
                                text = " ",
                                icon = "fas fa-check",
                                style = ButtonStyle.OUTLINESUCCESS
                            ) {
                                fontSize = 0.5.em
                                marginLeft = 5.px
                                onClick {
                                    this@View.acceptUpsertAction()
                                }
                            }
                    } else if (crudTask == CrudTask.Read) {
                        displayEditButton()
                    }
                }
            }
            marginBottom = 1.em
        }
        hasOffCanvasFilterView = buildOffCanvasFilterView()?.let {
            offCanvasFilter = it
            true
        } == true
    }

    /**
     * Increments the value of the `pageBannerUpdateObservable` to trigger an update for the page banner.
     * This function is typically invoked when the banner's content needs to be refreshed.
     */
    fun updateBanner() {
        pageBannerUpdateObservable.value++
    }

    /**
     * Updates the document's title to match the current value of the `label` property.
     *
     * This method sets the `document.title` to the current value of the `label` field,
     * which serves as the label or title associated with the view. It is typically used
     * to ensure the browser's title reflects the current view's context or purpose.
     */
    fun updateTitle() {
        document.title = "$frontEndAppName - $label"
    }

    /**
     * Builds and returns an off-canvas filter view within the current container.
     *
     * This method is designed to create an off-canvas filter component which can
     * be used for applying filters within the UI. If no specific filter view is
     * defined, it returns null.
     *
     * @return An instance of Offcanvas representing the filter view, or null if none is defined.
     */
    open fun Container.buildOffCanvasFilterView(): Offcanvas? = null

    init {
        if (userSessionParams == null) {
            AppScope.launch {
                updateUserSessionParams?.invoke()?.let { it ->
                    it.item?.let {
                        userSessionParams = it
                    } ?: it.toast()
                }
            }
        }
    }
}
