package com.fonrouge.fullStack.view

import com.fonrouge.base.api.ApiFilter
import com.fonrouge.base.api.ApiList
import com.fonrouge.base.api.CrudTask
import com.fonrouge.base.common.ICommonContainer
import com.fonrouge.base.lib.iconCrud
import com.fonrouge.base.model.BaseDoc
import com.fonrouge.base.state.ItemState
import com.fonrouge.base.state.ListState
import com.fonrouge.fullStack.config.ConfigViewItem
import com.fonrouge.fullStack.config.configViewList
import com.fonrouge.fullStack.tabulator.TabulatorMenuItem
import com.fonrouge.fullStack.tabulator.menuItem
import io.kvision.core.Container
import io.kvision.form.FormPanel
import io.kvision.i18n.I18n
import io.kvision.i18n.I18nManager
import kotlinx.serialization.Serializable
import org.w3c.dom.events.Event
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ── Fixture ──────────────────────────────────────────────────────────────────────────────────────

/**
 * Every callback the row menu may consult, in the order it was consulted. Shared by the fixture
 * pieces below and reset before each test.
 */
private val callLog = mutableListOf<String>()

/** Entity for the fixture. */
@Serializable
private data class RowMenuDoc(
    override val _id: String = "",
    val name: String = "",
) : BaseDoc<String>

/** Entity metadata; `labelId` is logged so the header's consultation of it is observable. */
private object RowMenuCommon : ICommonContainer<RowMenuDoc, String, ApiFilter>(
    itemKClass = RowMenuDoc::class,
    filterKClass = ApiFilter::class,
    labelId = { item: RowMenuDoc? -> callLog += "labelId(${item?._id})"; item?._id ?: "<no-item>" },
    labelItem = "Row doc",
)

/** Never instantiated: only its class is needed as the item view of [RowMenuConfigItem]. */
private class RowMenuItemView(config: RowMenuConfigItem) : ViewItem<RowMenuDoc, String, ApiFilter>(config) {
    override fun Container.pageItemBody(): FormPanel<RowMenuDoc> = error("not rendered in tests")
}

/**
 * The item config the list menu reads. Its `contextMenuItems` logs each call and returns
 * [itemEntries], or is absent altogether when [withContextMenuItems] is `false`.
 */
private class RowMenuConfigItem(
    withContextMenuItems: Boolean = true,
    itemEntries: List<TabulatorMenuItem> = emptyList(),
) : ConfigViewItem<RowMenuDoc, String, RowMenuItemView, ApiFilter, Any>(
    commonContainer = RowMenuCommon,
    apiItemFun = { ItemState() },
    viewKClass = RowMenuItemView::class,
    contextMenuItems = if (withContextMenuItems) {
        { item: RowMenuDoc -> callLog += "contextMenuItems(${item._id})"; itemEntries }
    } else {
        null
    },
)

/**
 * The smallest real [ViewList] that builds its Tabulator options. It reaches the production row menu
 * two ways: [productionCallbackMenu] invokes the `rowContextMenu` callback that
 * `defaultTabulatorOptions()` hands Tabulator (no table is built, so the resolved row is `null`), and
 * the tests call [ViewList.contextRowMenuFor] — the body that callback runs — for a non-null row.
 *
 * Both Create flags log each read. [toolbarCreate] is what [showCreateInToolbar] returns; when
 * [rowMenuCreate] is `null` the view does not set the row-menu flag apart, so
 * [showCreateInContextRowMenu] runs the production default getter (`super`).
 */
private class RowMenuFixtureList(
    configViewItem: RowMenuConfigItem?,
    private val toolbarCreate: Boolean = true,
    private val rowMenuCreate: Boolean? = null,
) : ViewList<RowMenuDoc, String, ApiFilter, Unit>(
    configView = configViewList<RowMenuDoc, String, RowMenuFixtureList, ApiFilter, Unit, Any>(
        viewKClass = RowMenuFixtureList::class,
        commonContainer = RowMenuCommon,
        apiListFun = { _: ApiList<ApiFilter> -> ListState() },
    ),
    configViewItem = configViewItem,
) {
    /** What [contextRowMenu] returns; `null` means the view adds nothing. */
    var rowEntries: List<TabulatorMenuItem>? = null

    /** Every navigation the menu actions asked for, as (task, row). */
    val navigations = mutableListOf<Pair<CrudTask, RowMenuDoc?>>()

    override val showCreateInToolbar: Boolean
        get() {
            callLog += "showCreateInToolbar"
            return toolbarCreate
        }

    override val showCreateInContextRowMenu: Boolean
        get() {
            callLog += "showCreateInContextRowMenu"
            return rowMenuCreate ?: super.showCreateInContextRowMenu
        }

    override fun contextRowMenu(item: RowMenuDoc): List<TabulatorMenuItem>? {
        callLog += "contextRowMenu(${item._id})"
        return rowEntries
    }

    override fun goActionUrl(
        crudTask: CrudTask,
        item: RowMenuDoc?,
        configViewItem: ConfigViewItem<RowMenuDoc, String, *, ApiFilter, *>?,
    ) {
        navigations += crudTask to item
    }

    /** Invokes the callback Tabulator would call on right-click, as configured in production. */
    fun productionCallbackMenu(): Array<TabulatorMenuItem> {
        val callback: dynamic = defaultTabulatorOptions().rowContextMenu
        return callback().unsafeCast<Array<TabulatorMenuItem>>()
    }

    override fun Container.pageListBody() = Unit
}

/** A menu entry recognisable by its label. */
private fun entry(label: String) = menuItem(label = label)

/** Reads a menu as labels: `header`, `---` for a separator, otherwise the raw label. */
private fun describe(menu: Array<TabulatorMenuItem>): List<String> = menu.map {
    when {
        it.header -> "header"
        it.separator == true -> "---"
        else -> it.rawLabel ?: "?"
    }
}

/**
 * The ViewList context row menu (blueprints/view-consumer-gaps, T-6): the default-CRUD decision as a
 * pure function, and the production generator through the callback Tabulator uses — its entries,
 * groups, order, captured row, labels and the order and number of callback invocations.
 * P4.2 characterized the menu before any behaviour change; P4.3 adds the row-menu Create flag
 * (CONTRACT I-7), whose read now follows `editable` in the call logs.
 */
class ContextRowMenuTest {

    /** Resolves every key to `tx:<key>`, so labels are shown to route through i18n. */
    private class MarkingManager : I18nManager {
        override fun gettext(key: String, vararg args: Any?): String = "tx:$key"
        override fun ngettext(singularKey: String, pluralKey: String, value: Int, vararg args: Any?): String =
            "tx:$singularKey"
    }

    private lateinit var previousManager: I18nManager
    private var previousContextMenuDefault: (ConfigViewItem<*, *, *, *, *>.() -> List<TabulatorMenuItem>?)? = null

    private val row = RowMenuDoc(_id = "r1", name = "first")

    @BeforeTest
    fun setUp() {
        callLog.clear()
        previousManager = I18n.manager
        I18n.manager = MarkingManager()
        previousContextMenuDefault = ConfigViewItem.contextMenuDefault
        ConfigViewItem.contextMenuDefault = null
    }

    @AfterTest
    fun tearDown() {
        I18n.manager = previousManager
        ConfigViewItem.contextMenuDefault = previousContextMenuDefault
    }

    /** A list whose flags log each evaluation (see [RowMenuFixtureList] for the two Create flags). */
    private fun loggedList(
        config: RowMenuConfigItem? = RowMenuConfigItem(),
        showDefault: Boolean = true,
        editable: Boolean = true,
        toolbarCreate: Boolean = true,
        rowMenuCreate: Boolean? = null,
    ) = RowMenuFixtureList(config, toolbarCreate, rowMenuCreate).apply {
        showDefaultContextRowMenu = { callLog += "showDefaultContextRowMenu"; showDefault }
        this.editable = { callLog += "editable"; editable }
    }

    // ── The pure decision ────────────────────────────────────────────────────────────────────────

    @Test
    fun decisionEditableByDefaultIsDetailCreateUpdateDelete() {
        assertEquals(
            listOf(CrudTask.Read, CrudTask.Create, CrudTask.Update, CrudTask.Delete),
            defaultCrudRowTasks(showDefaultContextRowMenu = { true }, editable = { true }, showCreate = { true }),
        )
    }

    @Test
    fun decisionNotEditableIsDetailOnly() {
        assertEquals(
            listOf(CrudTask.Read),
            defaultCrudRowTasks(showDefaultContextRowMenu = { true }, editable = { false }, showCreate = { true }),
        )
    }

    @Test
    fun decisionWithoutDefaultMenuIsEmptyAndNeverAsksEditable() {
        val asked = mutableListOf<String>()
        val tasks = defaultCrudRowTasks(
            showDefaultContextRowMenu = { asked += "show"; false },
            editable = { asked += "editable"; true },
            showCreate = { asked += "create"; true },
        )
        assertEquals(emptyList(), tasks)
        assertEquals(listOf("show"), asked, "no other flag is evaluated when the default menu is off")
    }

    @Test
    fun decisionEvaluatesEachFlagOnceInOrder() {
        val asked = mutableListOf<String>()
        defaultCrudRowTasks(
            showDefaultContextRowMenu = { asked += "show"; true },
            editable = { asked += "editable"; false },
            showCreate = { asked += "create"; true },
        )
        assertEquals(listOf("show", "editable"), asked, "the Create flag is not evaluated when not editable")
        asked.clear()
        defaultCrudRowTasks(
            showDefaultContextRowMenu = { asked += "show"; true },
            editable = { asked += "editable"; true },
            showCreate = { asked += "create"; false },
        )
        assertEquals(listOf("show", "editable", "create"), asked)
    }

    /** Mutation target (P4.3): offering Create regardless of [showCreate] fails here. */
    @Test
    fun decisionWithoutCreateIsDetailUpdateDelete() {
        assertEquals(
            listOf(CrudTask.Read, CrudTask.Update, CrudTask.Delete),
            defaultCrudRowTasks(showDefaultContextRowMenu = { true }, editable = { true }, showCreate = { false }),
        )
    }

    @Test
    fun decisionCreateFlagDoesNotRestoreCreateWhenNotEditable() {
        assertEquals(
            listOf(CrudTask.Read),
            defaultCrudRowTasks(showDefaultContextRowMenu = { true }, editable = { false }, showCreate = { true }),
        )
    }

    // ── The production callback (no table, so the resolved row is null) ─────────────────────────

    @Test
    fun callbackEditableByDefaultOffersDetailCreateUpdateDelete() {
        val menu = loggedList().productionCallbackMenu()
        assertEquals(
            listOf("header", "---", "tx:Detail of", "tx:Create", "tx:Update", "tx:Delete"),
            describe(menu),
        )
        assertEquals(
            listOf(CrudTask.Read, CrudTask.Create, CrudTask.Update, CrudTask.Delete).map { iconCrud(it) },
            menu.drop(2).map { it.icon },
            "each default entry keeps its CRUD icon",
        )
        assertEquals(
            listOf(
                "labelId(null)", "showDefaultContextRowMenu", "editable",
                "showCreateInContextRowMenu", "showCreateInToolbar",
            ),
            callLog,
            "the row-menu flag delegates to the toolbar flag by default; a null row consults neither " +
                "contextMenuItems nor contextRowMenu",
        )
    }

    @Test
    fun callbackNotEditableOffersDetailOnly() {
        assertEquals(
            listOf("header", "---", "tx:Detail of"),
            describe(loggedList(editable = false).productionCallbackMenu()),
        )
    }

    @Test
    fun callbackWithoutDefaultMenuOffersNoCrudEntryAndNeverAsksEditable() {
        val menu = loggedList(showDefault = false).productionCallbackMenu()
        assertEquals(listOf("header", "---"), describe(menu))
        assertEquals(listOf("labelId(null)", "showDefaultContextRowMenu"), callLog)
    }

    @Test
    fun callbackHeaderCarriesTheItemLabel() {
        val header = loggedList().productionCallbackMenu().first()
        assertTrue(header.header)
        assertEquals(" <font size=\"+1\">Row doc</font>: <b><no-item></b>", header.rawLabel)
    }

    @Test
    fun callbackActionsCaptureTheNullRow() {
        val list = loggedList()
        list.productionCallbackMenu().drop(2).forEach { it.action(Event("click"), null) }
        assertEquals<List<Pair<CrudTask, RowMenuDoc?>>>(
            listOf(CrudTask.Read, CrudTask.Create, CrudTask.Update, CrudTask.Delete).map { it to null },
            list.navigations,
        )
    }

    @Test
    fun callbackAssignsTheNullRowBeforeTheGlobalDefaultAndCallsItOnce() {
        val config = RowMenuConfigItem().apply { item = RowMenuDoc(_id = "stale") }
        ConfigViewItem.contextMenuDefault = {
            callLog += "contextMenuDefault(item=${(item as RowMenuDoc?)?._id})"
            listOf(entry("G1"))
        }
        val menu = loggedList(config).productionCallbackMenu()
        assertEquals(
            listOf("header", "---", "tx:Detail of", "tx:Create", "tx:Update", "tx:Delete", "---", "G1"),
            describe(menu),
        )
        assertEquals(
            listOf(
                "labelId(null)", "showDefaultContextRowMenu", "editable",
                "showCreateInContextRowMenu", "showCreateInToolbar", "contextMenuDefault(item=null)",
            ),
            callLog,
        )
        assertNull(config.item, "the stale item is overwritten with the resolved (null) row")
    }

    @Test
    fun callbackWithoutItemConfigHasOnlyTheEmptyHeader() {
        ConfigViewItem.contextMenuDefault = { callLog += "contextMenuDefault"; listOf(entry("G1")) }
        val menu = loggedList(config = null).productionCallbackMenu()
        assertEquals(listOf("header", "---"), describe(menu))
        assertEquals(" <font size=\"+1\"></font>: <b></b>", menu.first().rawLabel)
        assertEquals(emptyList(), callLog, "no flag, label or global default is consulted without an item config")
    }

    @Test
    fun callbackNotEditableNeverReadsTheCreateFlags() {
        loggedList(editable = false).productionCallbackMenu()
        assertEquals(listOf("labelId(null)", "showDefaultContextRowMenu", "editable"), callLog)
    }

    // ── The row-menu Create flag through the production callback (P4.3, CONTRACT I-7) ─────────────

    @Test
    fun rowMenuCreateFollowsTheToolbarFlagByDefault() {
        val list = loggedList(toolbarCreate = false)
        val menu = list.productionCallbackMenu()
        assertEquals(listOf("header", "---", "tx:Detail of", "tx:Update", "tx:Delete"), describe(menu))
        assertEquals(
            listOf(CrudTask.Read, CrudTask.Update, CrudTask.Delete).map { iconCrud(it) },
            menu.drop(2).map { it.icon },
        )
        assertEquals(
            listOf(
                "labelId(null)", "showDefaultContextRowMenu", "editable",
                "showCreateInContextRowMenu", "showCreateInToolbar",
            ),
            callLog,
        )
        callLog.clear()
        assertFalse(list.showCreateInContextRowMenu, "the default getter returns the toolbar flag")
    }

    /** Mutation target (P4.3): a generator that reads `showCreateInToolbar` instead fails here. */
    @Test
    fun rowMenuCreateCanBeKeptWithoutTheToolbarButton() {
        val list = loggedList(toolbarCreate = false, rowMenuCreate = true)
        assertEquals(
            listOf("header", "---", "tx:Detail of", "tx:Create", "tx:Update", "tx:Delete"),
            describe(list.productionCallbackMenu()),
        )
        assertEquals(
            listOf("labelId(null)", "showDefaultContextRowMenu", "editable", "showCreateInContextRowMenu"),
            callLog,
            "an overriding view is read once, and the toolbar flag is not consulted",
        )
    }

    /** Mutation target (P4.3): a generator that reads `showCreateInToolbar` instead fails here. */
    @Test
    fun rowMenuCreateCanBeHiddenWithTheToolbarButtonOn() {
        assertEquals(
            listOf("header", "---", "tx:Detail of", "tx:Update", "tx:Delete"),
            describe(loggedList(toolbarCreate = true, rowMenuCreate = false).productionCallbackMenu()),
        )
    }

    @Test
    fun rowMenuCreateFlagDoesNotRestoreCreateWhenNotEditable() {
        assertEquals(
            listOf("header", "---", "tx:Detail of"),
            describe(loggedList(editable = false, rowMenuCreate = true).productionCallbackMenu()),
        )
    }

    // ── The same body for a resolved row ─────────────────────────────────────────────────────────

    /** A global default that logs the item it sees and returns [labels] as entries. */
    private fun loggedGlobalDefault(vararg labels: String) {
        ConfigViewItem.contextMenuDefault = {
            callLog += "contextMenuDefault(item=${(item as RowMenuDoc?)?._id})"
            labels.map { entry(it) }
        }
    }

    @Test
    fun rowMenuKeepsGroupOrderAndCallsEachSourceOnceInOrder() {
        val config = RowMenuConfigItem(itemEntries = listOf(entry("C1")))
        loggedGlobalDefault("G1", "G2")
        val list = loggedList(config).apply { rowEntries = listOf(entry("V1"), entry("V2")) }
        val menu = list.contextRowMenuFor(row)
        assertEquals(
            listOf(
                "header", "---",
                "tx:Detail of", "tx:Create", "tx:Update", "tx:Delete",
                "---", "G1", "G2",
                "C1",
                "V1", "V2",
            ),
            describe(menu),
            "config and view entries are appended with no separator of their own",
        )
        assertEquals(
            listOf(
                "labelId(r1)", "showDefaultContextRowMenu", "editable",
                "showCreateInContextRowMenu", "showCreateInToolbar",
                "contextMenuDefault(item=r1)", "contextMenuItems(r1)", "contextRowMenu(r1)",
            ),
            callLog,
        )
        assertEquals(row, config.item, "the clicked row is left on the item config")
        assertEquals(" <font size=\"+1\">Row doc</font>: <b>r1</b>", menu.first().rawLabel)
    }

    @Test
    fun rowActionsCaptureTheClickedRow() {
        val list = loggedList()
        list.contextRowMenuFor(row).drop(2).forEach { it.action(Event("click"), null) }
        assertEquals<List<Pair<CrudTask, RowMenuDoc?>>>(
            listOf(CrudTask.Read, CrudTask.Create, CrudTask.Update, CrudTask.Delete).map { it to row },
            list.navigations,
        )
    }

    @Test
    fun rowMenuWithoutItemConfigStillAddsTheViewEntries() {
        loggedGlobalDefault("G1")
        val list = loggedList(config = null).apply { rowEntries = listOf(entry("V1")) }
        assertEquals(listOf("header", "---", "V1"), describe(list.contextRowMenuFor(row)))
        assertEquals(listOf("contextRowMenu(r1)"), callLog)
    }

    @Test
    fun rowMenuWithoutConfigEntriesOrViewEntriesAddsNothingAfterTheDefaults() {
        val list = loggedList(RowMenuConfigItem(withContextMenuItems = false))
        assertEquals(
            listOf("header", "---", "tx:Detail of", "tx:Create", "tx:Update", "tx:Delete"),
            describe(list.contextRowMenuFor(row)),
        )
        assertEquals(
            listOf(
                "labelId(r1)", "showDefaultContextRowMenu", "editable",
                "showCreateInContextRowMenu", "showCreateInToolbar", "contextRowMenu(r1)",
            ),
            callLog,
        )
    }

    @Test
    fun rowMenuGlobalDefaultEmptyOrNullAddsNoSeparator() {
        ConfigViewItem.contextMenuDefault = { emptyList() }
        assertEquals(
            listOf("header", "---", "tx:Detail of"),
            describe(loggedList(editable = false).contextRowMenuFor(row)),
        )
        ConfigViewItem.contextMenuDefault = { null }
        assertEquals(
            listOf("header", "---", "tx:Detail of"),
            describe(loggedList(editable = false).contextRowMenuFor(row)),
        )
    }

    @Test
    fun rowMenuWithoutCreateKeepsEveryOtherGroupAndTheClickedRow() {
        val config = RowMenuConfigItem(itemEntries = listOf(entry("C1")))
        loggedGlobalDefault("G1")
        val list = loggedList(config, toolbarCreate = false).apply { rowEntries = listOf(entry("V1")) }
        val menu = list.contextRowMenuFor(row)
        assertEquals(
            listOf("header", "---", "tx:Detail of", "tx:Update", "tx:Delete", "---", "G1", "C1", "V1"),
            describe(menu),
        )
        menu.slice(2..4).forEach { it.action(Event("click"), null) }
        assertEquals<List<Pair<CrudTask, RowMenuDoc?>>>(
            listOf(CrudTask.Read, CrudTask.Update, CrudTask.Delete).map { it to row },
            list.navigations,
        )
    }

    /**
     * **Current defect, pinned on purpose (LEDGER L-018):** with the default entries off, the
     * separator before the global entries follows the header's separator. P4.4 changes this
     * expectation; until then it documents what consumers see today.
     */
    @Test
    fun currentlyTwoSeparatorsFollowTheHeaderWhenDefaultsAreOff() {
        loggedGlobalDefault("G1")
        assertEquals(
            listOf("header", "---", "---", "G1"),
            describe(loggedList(showDefault = false).contextRowMenuFor(row)),
        )
    }
}
