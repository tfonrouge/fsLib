package com.fonrouge.fullStack.tabulator

import com.fonrouge.base.api.ApiFilter
import com.fonrouge.base.api.ApiList
import com.fonrouge.base.common.simpleContainer
import com.fonrouge.base.model.BaseDoc
import com.fonrouge.base.state.ListState
import com.fonrouge.fullStack.config.configViewList
import com.fonrouge.fullStack.view.ViewList
import com.fonrouge.fullStack.view.persistenceIdOf
import io.kvision.core.Container
import kotlinx.serialization.Serializable
import io.kvision.panel.Root
import io.kvision.tabulator.ColumnDefinition
import io.kvision.tabulator.PaginationMode
import io.kvision.tabulator.Tabulator
import io.kvision.tabulator.TabulatorOptions
import io.kvision.utils.obj
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins what a stored Tabulator layout may and may not override.
 *
 * A consumer reported that renaming a column in code never reached anyone who had already opened
 * that list: Tabulator's `persistence = true` expands to `columns: ["title", "width", "visible"]`,
 * and on load `mergeDefinition` copies every configured key from the stored layout over the
 * definition written in code — so a title stored once outlived every later edit, and a view whose
 * title depends on context showed whichever variant was opened last.
 *
 * [storedTitleDoesNotOverrideTheCodeTitle] is the fix. [persistenceTrueLetsTheStoredTitleWin] is its
 * control: it drives the identical scenario with `persistence = true` and asserts the OLD title, so
 * the pair shows the key list is what decides, not some incidental difference in how the test builds
 * the table.
 */
class TabulatorPersistenceTest {

    private val tables = mutableListOf<Tabulator<dynamic>>()
    private val requestedPages = mutableListOf<Int>()
    private val usedIds = mutableListOf<String>()

    @BeforeTest
    fun setUp() {
        // One Root for the whole browser session: adding to a freshly created second Root does not
        // render (KVision patches the first root's tree), so every suite shares the first one.
        if (Root.getFirstRoot() == null) {
            val el = document.createElement("div") as HTMLElement
            el.id = "tabulator-persistence-root"
            document.body?.appendChild(el)
            Root(el)
        }
    }

    @AfterTest
    fun tearDown() {
        tables.forEach { it.dispose() }
        tables.clear()
        requestedPages.clear()
        usedIds.forEach { id ->
            listOf("columns", "sort", "filter", "headerFilter", "group", "page")
                .forEach { localStorage.removeItem("tabulator-$id-$it") }
        }
        usedIds.clear()
    }

    /**
     * Resolves when [predicate] holds, instead of sleeping a fixed interval: the table is built
     * asynchronously and a timed guess is both slower than it needs to be and flaky under load.
     */
    private fun awaitUntil(what: String, timeoutMs: Int = 1500, predicate: () -> Boolean): Promise<Unit> =
        Promise { resolve, reject ->
            var waited = 0
            fun poll() {
                val ready = try { predicate() } catch (_: Throwable) { false }
                when {
                    ready -> resolve(Unit)
                    waited >= timeoutMs -> reject(IllegalStateException("timed out waiting for $what"))
                    else -> { waited += 10; window.setTimeout({ poll() }, 10) }
                }
            }
            poll()
        }

    /** Seeds one persistence entry exactly as Tabulator's own local writer does. */
    private fun seed(id: String, type: String, json: String) {
        if (id !in usedIds) usedIds.add(id)
        localStorage.setItem("tabulator-$id-$type", json)
    }

    /** Rows as a raw JS array: KVision needs a serializer for typed data, and these tests have none. */
    private fun rows(n: Int): Array<dynamic> {
        val a = js("[]")
        for (i in 0 until n) a.push(obj { this.name = "row $i" })
        return a.unsafeCast<Array<dynamic>>()
    }

    private fun buildTable(
        id: String,
        persistence: dynamic,
        columns: List<ColumnDefinition<dynamic>>,
        rows: Array<dynamic>? = null,
        pagination: Boolean = false,
        paginationSize: Int? = null,
        remote: Boolean = false,
    ): Tabulator<dynamic> {
        if (id !in usedIds) usedIds.add(id)
        val tab: Tabulator<dynamic> = Tabulator(
            // An empty list, not null: KVision's `dataLoaded` handler runs `toKotlinObjList` on
            // whatever Tabulator loaded initially, and that throws on a missing data array.
            // `data` wins over ajax in Tabulator, so rows-by-ajax must pass null here — and with no
            // ajax either, KVision's `dataLoaded` handler runs `toKotlinObjList` on a missing array
            // and throws, so the no-rows case needs an empty list.
            data = if (rows == null) emptyList<dynamic>() else null,
            dataUpdateOnEdit = false,
            options = TabulatorOptions(
                columns = columns,
                persistence = persistence,
                persistenceID = id,
                pagination = pagination,
                paginationSize = paginationSize,
                height = "200px",
                ajaxURL = rows?.let { "/fake" },
                paginationMode = if (remote) PaginationMode.REMOTE else null,
                ajaxRequestFunc = rows?.let { data ->
                    { _, _, params ->
                        requestedPages.add((params.asDynamic()?.page as? Int) ?: 1)
                        Promise { resolve, _ ->
                            // The envelope a remote loader must return; `data` is one page's worth.
                            resolve(if (remote) obj { this.last_page = 3; this.last_row = 75; this.data = data } else data)
                        }
                    }
                },
            ),
            kClass = null,
        )
        Root.getFirstRoot()!!.add(tab)
        tables.add(tab)
        return tab
    }

    private fun jsOf(tab: Tabulator<dynamic>): dynamic = tab.jsTabulator.asDynamic()
    private fun columnsOf(tab: Tabulator<dynamic>): Array<dynamic> = jsOf(tab).getColumns() as Array<dynamic>
    private fun builtWith(tab: Tabulator<dynamic>, n: Int) = { columnsOf(tab).size >= n }
    private fun fieldsOf(tab: Tabulator<dynamic>): List<String> =
        columnsOf(tab).map { it.getField() as String }
    private fun widthOf(tab: Tabulator<dynamic>, index: Int): Int =
        (columnsOf(tab)[index].getWidth() as Number).toInt()

    /**
     * **The fix.** A layout stored with the old title does not override the title written in code,
     * while the stored width and visibility still apply — the whole point of keeping persistence on.
     */
    @Test
    fun storedTitleDoesNotOverrideTheCodeTitle(): Promise<Unit> {
        val id = "FsPersistNewTitle"
        seed(id, "columns", """[{"field":"name","title":"OLD TITLE","width":222,"visible":true}]""")
        val tab = buildTable(
            id = id,
            persistence = ViewList.tabulatorInfoPersistenceConfig,
            columns = listOf(ColumnDefinition(title = "NEW TITLE", field = "name")),
        )
        return awaitUntil("the table to build", predicate = builtWith(tab, 1)).then {
            assertEquals("NEW TITLE", columnsOf(tab)[0].getDefinition().title as String, "the code title must win")
            assertEquals(222, widthOf(tab, 0), "the stored width must still be applied")
        }
    }

    /**
     * **The control.** Same stored layout, same code title, `persistence = true` — and the stored
     * title wins. Without this, the test above could pass for reasons unrelated to the key list.
     */
    @Test
    fun persistenceTrueLetsTheStoredTitleWin(): Promise<Unit> {
        val id = "FsPersistOldTitle"
        seed(id, "columns", """[{"field":"name","title":"OLD TITLE","width":222,"visible":true}]""")
        val tab = buildTable(
            id = id,
            persistence = true,
            columns = listOf(ColumnDefinition(title = "NEW TITLE", field = "name")),
        )
        return awaitUntil("the table to build", predicate = builtWith(tab, 1)).then {
            assertEquals(
                "OLD TITLE",
                columnsOf(tab)[0].getDefinition().title as String,
                "with persistence = true the stored title overrides code — the defect this change fixes",
            )
        }
    }

    /** A stored `visible: false` still hides the column under the fsLib default. */
    @Test
    fun storedVisibilityStillApplies(): Promise<Unit> {
        val id = "FsPersistVisible"
        seed(id, "columns", """[{"field":"a","width":120,"visible":true},{"field":"b","width":130,"visible":false}]""")
        val tab = buildTable(
            id = id,
            persistence = ViewList.tabulatorInfoPersistenceConfig,
            columns = listOf(
                ColumnDefinition(title = "A", field = "a"),
                ColumnDefinition(title = "B", field = "b"),
            ),
        )
        return awaitUntil("the table to build", predicate = builtWith(tab, 2)).then {
            val cols = columnsOf(tab)
            assertEquals(true, cols[0].isVisible() as Boolean, "column a stays visible")
            assertEquals(false, cols[1].isVisible() as Boolean, "stored visible=false must still hide b")
        }
    }

    /** The stored column ORDER is restored, even though the key list names neither order nor title. */
    @Test
    fun storedColumnOrderIsRestored(): Promise<Unit> {
        val id = "FsPersistOrder"
        seed(
            id,
            "columns",
            """[{"field":"c","width":90,"visible":true},{"field":"a","width":90,"visible":true},{"field":"b","width":90,"visible":true}]""",
        )
        val tab = buildTable(
            id = id,
            persistence = ViewList.tabulatorInfoPersistenceConfig,
            columns = listOf(
                ColumnDefinition(title = "A", field = "a"),
                ColumnDefinition(title = "B", field = "b"),
                ColumnDefinition(title = "C", field = "c"),
            ),
        )
        return awaitUntil("the table to build", predicate = builtWith(tab, 3)).then {
            assertEquals(listOf("c", "a", "b"), fieldsOf(tab), "the stored order must be restored")
        }
    }

    /**
     * Sorting, filtering and the current page keep persisting: only the column title was dropped.
     *
     * Driven in **remote** pagination mode, which is what `defaultTabulatorOptions` uses, with the
     * envelope Tabulator expects from a remote loader — a local-pagination variant would exercise a
     * different code path from the one fsLib's views run.
     */
    @Test
    fun sortFilterAndPageStillPersist(): Promise<Unit> {
        val id = "FsPersistSortFilterPage"
        seed(id, "sort", """[{"column":"name","dir":"desc"}]""")
        seed(id, "filter", """[{"field":"name","type":"!=","value":"nothing matches this"}]""")
        seed(id, "page", """{"paginationSize":25,"paginationInitialPage":3}""")
        val tab = buildTable(
            id = id,
            persistence = ViewList.tabulatorInfoPersistenceConfig,
            columns = listOf(ColumnDefinition(title = "Name", field = "name")),
            rows = rows(25),
            pagination = true,
            paginationSize = 10,
            remote = true,
        )
        return awaitUntil("rows to load", predicate = { (jsOf(tab).getDataCount() as Number).toInt() > 0 }).then {
            val jsT = jsOf(tab)
            assertEquals(1, (jsT.getSorters() as Array<dynamic>).size, "the stored sorter must be restored")
            assertEquals("desc", (jsT.getSorters() as Array<dynamic>)[0].dir as String, "and keep its direction")
            assertEquals(1, (jsT.getFilters() as Array<dynamic>).size, "the stored filter must be restored")
            assertEquals(25, (jsT.getPageSize() as Number).toInt(), "the stored page size must win over code")
            assertEquals(3, (jsT.getPage() as Number).toInt(), "and the stored current page must be restored")
            assertEquals(
                listOf(3),
                requestedPages.distinct(),
                "in remote mode the restored page is what gets requested from the server",
            )
        }
    }

    /**
     * Variant isolation, end to end: two variants of one view read their own stored layout rather
     * than inheriting each other's widths — the contamination a context-dependent list suffers while
     * both contexts share a single key.
     */
    @Test
    fun twoVariantsReadDistinctStoredLayouts(): Promise<Unit> {
        val fab = persistenceIdOf("FsPersistVariantView", "FAB")!!
        val rep = persistenceIdOf("FsPersistVariantView", "REP")!!
        assertTrue(fab != rep, "the two variants must not share a key")
        seed(fab, "columns", """[{"field":"a","width":111,"visible":true}]""")
        seed(rep, "columns", """[{"field":"a","width":222,"visible":true}]""")
        val columns = listOf(ColumnDefinition<dynamic>(title = "Acciones", field = "a"))
        val tabFab = buildTable(id = fab, persistence = ViewList.tabulatorInfoPersistenceConfig, columns = columns)
        return awaitUntil("the FAB table", predicate = builtWith(tabFab, 1)).then {
            assertEquals(111, widthOf(tabFab, 0), "the FAB variant reads its own stored width")
        }.then {
            val tabRep = buildTable(id = rep, persistence = ViewList.tabulatorInfoPersistenceConfig, columns = columns)
            awaitUntil("the REP table", predicate = builtWith(tabRep, 1)).then {
                assertEquals(222, widthOf(tabRep, 0), "the REP variant reads its own, not the FAB width")
            }
        }.then { }
    }

    /**
     * **The wiring, end to end through a real [ViewList].** Overriding `persistenceVariant` must reach
     * the `persistenceID` that `defaultTabulatorOptions()` hands Tabulator. The other tests cover the
     * pieces — the key join, and two keys reading distinct layouts — but only this one fails if
     * `defaultTabulatorOptions` stops consulting the variant.
     */
    @Test
    fun defaultTabulatorOptionsCarriesTheVariantIntoPersistenceId() {
        assertEquals(
            "PersistenceFixtureViewList",
            PersistenceFixtureViewList(null).optionsPersistenceId(),
            "no variant: the key is the view's class name",
        )
        assertEquals(
            "PersistenceFixtureViewList-FAB-OPERABLE",
            PersistenceFixtureViewList("FAB-OPERABLE").optionsPersistenceId(),
            "an overridden variant must reach defaultTabulatorOptions().persistenceID",
        )
    }

    /**
     * The shipped default itself: every key `persistence = true` enables except the column title.
     * Cheap, and it fails if the default is ever reverted to `true` or loses one of its keys.
     */
    @Test
    fun defaultPersistenceConfigKeepsEverythingButTheTitle() {
        val cfg = ViewList.tabulatorInfoPersistenceConfig
        listOf("sort", "filter", "headerFilter", "group", "page").forEach { key ->
            assertEquals(true, cfg[key] as? Boolean, "`$key` must stay enabled, as with persistence = true")
        }
        assertEquals(
            listOf("width", "visible"),
            (cfg.columns as Array<String>).toList(),
            "columns must persist width and visibility only — never the title",
        )
    }

    /** The variant key join in isolation, including the blank cases an override can produce. */
    @Test
    fun variantKeyJoin() {
        assertEquals("ViewListPaso", persistenceIdOf("ViewListPaso", null), "no variant, no suffix")
        assertEquals("ViewListPaso", persistenceIdOf("ViewListPaso", ""), "a blank variant is no variant")
        assertEquals("ViewListPaso", persistenceIdOf("ViewListPaso", "   "), "nor is a blank-ish one")
        assertEquals("ViewListPaso-FAB", persistenceIdOf("ViewListPaso", "FAB"), "a variant suffixes the key")
        assertNull(persistenceIdOf(null, "FAB"), "no base id, no persistence")
    }
}

/** Minimal persistable document for the fixture view. */
@Serializable
private data class PersistenceFixtureDoc(override val _id: String = "id") : BaseDoc<String>

/**
 * The smallest real [ViewList] that can build options: it exists so the variant wiring is exercised
 * through the production path (`persistenceVariant` → `persistenceId()` →
 * `defaultTabulatorOptions().persistenceID`) rather than through its parts.
 */
private class PersistenceFixtureViewList(private val variant: String?) : ViewList<PersistenceFixtureDoc, String, ApiFilter, Unit>(
    configView = configViewList<PersistenceFixtureDoc, String, PersistenceFixtureViewList, ApiFilter, Unit, Any>(
        viewKClass = PersistenceFixtureViewList::class,
        commonContainer = simpleContainer<PersistenceFixtureDoc, String>(),
        apiListFun = { _: ApiList<ApiFilter> -> ListState() },
    ),
) {
    override val persistenceVariant: String? get() = variant

    /** Reads the id off the options the view would actually hand Tabulator. */
    fun optionsPersistenceId(): String? = defaultTabulatorOptions().persistenceID

    override fun Container.pageListBody() = Unit
}
