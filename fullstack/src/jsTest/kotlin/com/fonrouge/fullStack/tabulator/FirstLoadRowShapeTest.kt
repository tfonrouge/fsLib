package com.fonrouge.fullStack.tabulator

import com.fonrouge.base.api.ApiFilter
import com.fonrouge.base.api.ApiList
import com.fonrouge.base.common.ICommonContainer
import com.fonrouge.base.fieldName
import com.fonrouge.base.model.BaseDoc
import com.fonrouge.base.state.ListState
import com.fonrouge.fullStack.config.ConfigViewList
import com.fonrouge.fullStack.config.ViewRegistry
import com.fonrouge.fullStack.config.configViewList
import com.fonrouge.fullStack.view.ViewList
import dev.kilua.rpc.HttpMethod
import dev.kilua.rpc.RpcSerialization
import dev.kilua.rpc.RpcServiceManager
import io.kvision.core.Container
import io.kvision.html.Span
import io.kvision.panel.Root
import io.kvision.tabulator.ColumnDefinition
import io.kvision.tabulator.TabulatorOptions
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ── Fixture ──────────────────────────────────────────────────────────────────────────────────────

/** The nested entity behind the `owner.code` column; `code` is non-null, like a consumer's `User.code`. */
@Serializable
private data class FirstLoadOwner(val code: String, val name: String)

/**
 * The row entity. [owner] is a nullable body property with a `null` default — the shape of a
 * `$lookup`-filled field — and [folio] a nullable constructor property with a `null` default.
 */
@Serializable
private data class FirstLoadDoc(
    override val _id: String = "",
    val label: String = "",
    val folio: Int? = null,
) : BaseDoc<String> {
    /** Filled by a lookup on the server; `null` when the referenced id is absent. */
    var owner: FirstLoadOwner? = null
}

/** Entity metadata for [FirstLoadList]. */
private object FirstLoadCommon : ICommonContainer<FirstLoadDoc, String, ApiFilter>(
    itemKClass = FirstLoadDoc::class,
    filterKClass = ApiFilter::class,
    labelId = { it?._id ?: "" },
    labelItem = "First-load doc",
)

/** The smallest real [ViewList]; it records what `TabulatorViewList` hands to [onReceivingData]. */
private class FirstLoadList : ViewList<FirstLoadDoc, String, ApiFilter, Unit>(
    configView = configViewList<FirstLoadDoc, String, FirstLoadList, ApiFilter, Unit, Any>(
        viewKClass = FirstLoadList::class,
        commonContainer = FirstLoadCommon,
        apiListFun = { _: ApiList<ApiFilter> -> ListState() },
    ),
) {
    /** Every `data` array the first-load path delivered, as JSON text. */
    val received = mutableListOf<String>()

    override fun onReceivingData(data: dynamic) {
        received += JSON.stringify(data)
    }

    override fun Container.pageListBody() = Unit
}

/**
 * A list service manager that resolves every function to one fixed GET route. GET skips Kilua's
 * response-id check, so the stubbed `fetch` can answer without echoing the request id.
 */
private object FirstLoadServiceManager : RpcServiceManager<Any>(Any::class) {
    override fun getCall(function: Function<*>): Pair<String, HttpMethod> = "/rpc/fsLibFirstLoadTest" to HttpMethod.GET
}

/**
 * The Root [FirstLoadRowShapeTest] mounts on, created once per browser session on an element attached
 * to the document. The suite cannot borrow `Root.getFirstRoot()` as the other Tabulator suites do:
 * another suite may leave the first Root on an element it has already removed from the document, and
 * Tabulator renders no cell — so no formatter ever runs — in a detached table.
 */
private val firstLoadRoot: Root by lazy {
    val el = document.createElement("div") as HTMLElement
    el.id = "first-load-row-shape-root"
    document.body?.appendChild(el)
    Root(el)
}

/**
 * Pins the shape of the rows the grid holds after Tabulator's own loader and after a refresh.
 *
 * Kilua RPC 0.0.46 forces `encodeDefaults = true`, so a list response now carries `"owner": null` for
 * every nullable property left at its `null` default. `TabulatorViewList`'s first load used to hand
 * those raw rows straight to Tabulator, which, building a nested-field cell (`owner.code`) over a
 * `null` parent, writes the cell value back and turns the row into `owner: {code: null}` (Tabulator
 * `Cell.build` → `Column._setNestedData`). Every later decode of that row — KVision's
 * component-formatter wrapper first — then throws, because `code` is non-null and `name` is missing.
 * That was the mppErsaPack 2.6.196 production incident (2026-11-26).
 *
 * The tests drive the PRODUCTION paths end to end. First load: Tabulator's `ajaxRequestFunc` →
 * `TabulatorViewList.promise()` → Kilua's `CallAgent.jsonRpcCall` → `fetch`. Refresh: `ViewList.dataUpdate()`
 * → `apiCall()` → the same `promise()` → `replaceData`. Only `fetch` (the network boundary) and the
 * service manager's route lookup are stubbed; the fixture wires `ViewList.tabulator` and
 * `jsTabulatorBuilt` the way `fsTabulator` does. The payloads are the wire Kilua 0.0.46 emits.
 *
 * Contract pinned for `ViewList.onReceivingData` (and, by the same input, the content hash): it keeps
 * receiving the RAW RPC rows, explicit nulls included — only what Tabulator's loader receives is
 * canonicalized.
 *
 * Not covered, by design: an undecodable row at the TABLE level. KVision's own `dataLoaded` handler
 * decodes every loaded row before Tabulator builds a cell, and one undecodable row already fails that
 * whole load (pre-existing, upstream). [undecodableRowIsKeptInPlaceAndReported] therefore pins only
 * what fsLib's canonicalization owns: it keeps such a row and reports it, never drops it.
 */
class FirstLoadRowShapeTest {

    private val tables = mutableListOf<TabulatorViewList<FirstLoadDoc, String, ApiFilter, Unit>>()
    private var savedFetch: dynamic = null
    private var savedConsoleError: dynamic = null
    private var savedRpcConfiguration: Json? = null
    private var savedListServiceManager: RpcServiceManager<*>? = null

    /** The `ViewRegistry.configViewListMap` key every [FirstLoadList] config registers under. */
    private val configKey: String = FirstLoadList::class.simpleName!!

    /** What that key held before this test, restored (or removed) after it. */
    private var savedConfigEntry: ConfigViewList<*, *, *, *, *, *>? = null

    /** The ListState body the stubbed server answers with; changed mid-test to drive a refresh. */
    private var rpcBody: String = ""

    /** How many RPC calls reached the stubbed `fetch`. */
    private var rpcCalls = 0

    /** Rows each component formatter received, decoded by KVision's own wrapper. */
    private val formatted = mutableListOf<FirstLoadDoc>()

    /** Messages written to `console.error` while a test ran. */
    private val consoleErrors = mutableListOf<String>()

    @BeforeTest
    fun setUp() {
        savedFetch = window.asDynamic().fetch
        savedConsoleError = console.asDynamic().error
        savedRpcConfiguration = RpcSerialization.customConfiguration
        savedListServiceManager = ViewRegistry.listServiceManagerOrNull
        savedConfigEntry = ViewRegistry.configViewListMap[configKey]
        val original = savedConsoleError
        console.asDynamic().error = { a: dynamic, b: dynamic, c: dynamic ->
            consoleErrors += listOf(a, b, c).filter { it != undefined }.joinToString(" ") { "$it" }
            original.call(console, a, b, c)
        }
        window.asDynamic().fetch = { _: dynamic ->
            rpcCalls++
            val envelope: dynamic = js("({})")
            envelope.result = rpcBody
            val body = JSON.stringify(envelope)
            val init: dynamic = js("({headers: {'content-type': 'application/json'}})")
            val response: dynamic = js("new Response(body, init)")
            Promise.resolve<dynamic>(response)
        }
        ViewRegistry.listServiceManager = FirstLoadServiceManager
    }

    @AfterTest
    fun tearDown() {
        // Detach before disposing: KVision's Widget.dispose() does not remove a widget from its parent,
        // so without remove() every disposed table would stay a child of the shared firstLoadRoot.
        tables.forEach {
            firstLoadRoot.remove(it)
            it.dispose()
        }
        tables.clear()
        window.asDynamic().fetch = savedFetch
        console.asDynamic().error = savedConsoleError
        RpcSerialization.customConfiguration = savedRpcConfiguration
        ViewRegistry.listServiceManagerOrNull = savedListServiceManager
        // Only this fixture's own key: every other registration in the shared map is left untouched.
        savedConfigEntry?.let { ViewRegistry.configViewListMap[configKey] = it }
            ?: ViewRegistry.configViewListMap.remove(configKey)
        formatted.clear()
        consoleErrors.clear()
        rpcCalls = 0
    }

    /** A ListState body with [rows] (raw JSON objects) on one page. */
    private fun listState(vararg rows: String): String =
        """{"data":[${rows.joinToString(",")}],"last_page":1,"last_row":${rows.size},"state":"Ok"}"""

    /**
     * Mounts a real `TabulatorViewList` with a nested `owner.code` column and, when [withFormatter],
     * a component formatter on `label` — the column mix of the incident's list — and wires it into its
     * `ViewList` as `fsTabulator` does, so `dataUpdate()` reaches it.
     */
    private fun mount(withFormatter: Boolean = true): TabulatorViewList<FirstLoadDoc, String, ApiFilter, Unit> {
        val list = FirstLoadList()
        val labelColumn = if (withFormatter) {
            ColumnDefinition<FirstLoadDoc>(
                title = "Label",
                field = fieldName(FirstLoadDoc::label),
                formatterComponentFunction = { _, _, data -> formatted += data; Span(data.label) },
            )
        } else {
            ColumnDefinition(title = "Label", field = fieldName(FirstLoadDoc::label))
        }
        val tab = firstLoadRoot.tabulatorViewList(
            viewList = list,
            apiListBlock = { ApiList(apiFilter = list.apiFilter) },
            apiListSerialize = { Json.encodeToString(ApiList.serializer(ApiFilter.serializer()), it) },
            serializer = FirstLoadDoc.serializer(),
            tabulatorOptions = TabulatorOptions(
                columns = listOf(
                    labelColumn,
                    ColumnDefinition(title = "Owner", field = fieldName(FirstLoadDoc::owner, FirstLoadOwner::code)),
                ),
                height = "200px",
            ),
        )
        list.tabulator = tab
        tables += tab
        return tab
    }

    /**
     * A real `TabulatorViewList` that is never added to the document, for tests that only exercise its
     * codec ([TabulatorViewList.canonicalRows], `toKotlinList`, `toPlainObj`). Tabulator builds — and
     * fires its AJAX load — only once the widget is inserted, so this table makes no request that could
     * outlive the test and reach a restored `fetch`.
     */
    private fun detached(): TabulatorViewList<FirstLoadDoc, String, ApiFilter, Unit> {
        val list = FirstLoadList()
        return TabulatorViewList(
            viewList = list,
            apiListBlock = { ApiList(apiFilter = list.apiFilter) },
            apiListSerialize = { Json.encodeToString(ApiList.serializer(ApiFilter.serializer()), it) },
            tabulatorOptions = TabulatorOptions(
                columns = listOf(
                    ColumnDefinition(title = "Label", field = fieldName(FirstLoadDoc::label)),
                    ColumnDefinition(title = "Owner", field = fieldName(FirstLoadDoc::owner, FirstLoadOwner::code)),
                ),
            ),
            types = setOf(),
            className = null,
            kClass = FirstLoadDoc::class,
            serializer = FirstLoadDoc.serializer(),
            module = null,
        )
    }

    private fun viewListOf(tab: TabulatorViewList<FirstLoadDoc, String, ApiFilter, Unit>): FirstLoadList =
        tab.viewList as FirstLoadList

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

    /**
     * Resolves once the first page is in the table and has had a frame to render its cells — so a
     * formatter that throws shows up as a failed assertion on the row, not as a timeout.
     */
    private fun awaitRendered(tab: TabulatorViewList<FirstLoadDoc, String, ApiFilter, Unit>): Promise<Unit> =
        awaitUntil("the first page to load") { rowsOf(tab).isNotEmpty() }.then {
            Promise<Unit> { resolve, _ -> window.setTimeout({ resolve(Unit) }, 50) }
        }.then { }

    /**
     * Asserts [row] has no `owner` key at all. A plain `assertEquals(undefined, row.owner)` cannot
     * do this: Kotlin/JS equality treats `null` and `undefined` alike, so it would also pass for an
     * explicit `owner: null`.
     */
    private fun assertNoOwnerKey(row: dynamic, message: String) {
        assertFalse(row.hasOwnProperty("owner") as Boolean, "$message; row = ${JSON.stringify(row)}")
    }

    /** The row's own keys, sorted — key presence, not value equality. */
    private fun keysOf(row: dynamic): List<String> =
        js("Object").keys(row).unsafeCast<Array<String>>().sorted()

    private fun rowsOf(tab: TabulatorViewList<FirstLoadDoc, String, ApiFilter, Unit>): Array<dynamic> =
        tab.jsTabulator.asDynamic().getData().unsafeCast<Array<dynamic>>()

    /**
     * **The regression.** A first-load row whose nested-column parent arrives as an explicit `null`
     * renders through a component formatter: the row is not rewritten into `owner: {code: null}`,
     * and it decodes, with `owner == null`. `onReceivingData` still gets the raw row.
     */
    @Test
    fun nullParentOnFirstLoadIsNotMaterializedAndDecodes(): Promise<Unit> {
        val raw = """{"_id":"a","label":"A","folio":null,"owner":null}"""
        rpcBody = listState(raw)
        val tab = mount()
        return awaitRendered(tab).then {
            val row = rowsOf(tab)[0]
            assertNoOwnerKey(row, "the null parent must not reach Tabulator, as an object or as null")
            assertNull(tab.toKotlinObj(row).owner, "the row as Tabulator holds it must still decode")
            assertEquals(1, formatted.size, "the component formatter must have decoded the row")
            assertNull(formatted[0].owner)
            assertEquals("[$raw]", viewListOf(tab).received.single(), "onReceivingData keeps the raw RPC rows")
        }
    }

    /**
     * **The invariant, through both real paths.** After a first load and then a refresh through the
     * production entry (`ViewList.dataUpdate()`) whose response changes a visible value, the refreshed
     * row is on screen and has the same keys as the first-load row — no `owner` in either. The
     * first-load row also equals the refresh path's codec output for the same payload, and
     * `onReceivingData` received the raw rows both times.
     */
    @Test
    fun firstLoadAndRefreshGiveTheSameRowShape(): Promise<Unit> {
        val first = """{"_id":"a","label":"A","folio":null,"owner":null}"""
        val second = """{"_id":"a","label":"A2","folio":null,"owner":null}"""
        rpcBody = listState(first)
        val tab = mount()
        val list = viewListOf(tab)
        var firstLoadRow = ""
        return awaitRendered(tab).then {
            val row = rowsOf(tab)[0]
            firstLoadRow = JSON.stringify(row)
            assertNoOwnerKey(row, "first load")
            assertEquals(
                JSON.stringify(tab.toPlainObj(tab.toKotlinList(JSON.parse("[$first]"))[0])),
                firstLoadRow,
                "the first-load row must equal the refresh codec's output for the same payload",
            )
            rpcBody = listState(second)
            list.jsTabulatorBuilt = true
            list.dataUpdate()
        }.then {
            awaitUntil("the refreshed label to reach the table") {
                rowsOf(tab)[0].label == "A2" && formatted.any { it.label == "A2" }
            }
        }.then {
            assertEquals(2, rpcCalls, "the refresh must have called the server")
            val row = rowsOf(tab)[0]
            assertNoOwnerKey(row, "refresh")
            assertEquals(keysOf(JSON.parse(firstLoadRow)), keysOf(row), "same keys after first load and refresh")
            assertEquals(listOf("[$first]", "[$second]"), list.received, "onReceivingData keeps the raw RPC rows")
        }
    }

    /**
     * **The JS-configuration guard.** A consumer-wide `RpcSerialization.customConfiguration` with
     * `encodeDefaults = true` must not bring the `null` defaults back into the grid rows.
     */
    @Test
    fun consumerEncodeDefaultsDoesNotLeakIntoRows(): Promise<Unit> {
        RpcSerialization.customConfiguration = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        rpcBody = listState("""{"_id":"a","label":"A","folio":null,"owner":null}""")
        val tab = mount()
        return awaitRendered(tab).then {
            assertNoOwnerKey(rowsOf(tab)[0], "a consumer encodeDefaults must not bring the null parent back")
            assertEquals(listOf("_id", "label"), keysOf(rowsOf(tab)[0]))
            assertEquals(1, formatted.size, "the component formatter must have decoded the row")
        }
    }

    /**
     * **Shape pin.** The canonical grid row omits every property at its default — non-null defaults
     * included (`label = ""`) — and equals the refresh path's codec output for the same row (asserted
     * below). The pre-0.0.46 wire omitted defaults too, but only with a server configuration that left
     * `encodeDefaults` unset; that wire is not exercised here. Only key absence is asserted: this table
     * never renders, so how a plain cell shows the missing value is not tested.
     */
    @Test
    fun defaultValuedPropertiesAreAbsentFromGridRows() {
        val tab = detached()
        val raw: dynamic = JSON.parse("""[{"_id":"d","label":"","folio":null,"owner":null}]""")
        val out = tab.canonicalRows(raw).unsafeCast<Array<dynamic>>()
        assertEquals(listOf("_id"), keysOf(out[0]))
        assertEquals(
            JSON.stringify(tab.toPlainObj(tab.toKotlinList(raw)[0])),
            JSON.stringify(out[0]),
            "same as the refresh path's codec",
        )
    }

    /**
     * **Unknown key, effective default configuration** (no consumer JS `customConfiguration`). The two
     * paths read with different decoders: the loader with `jsonHelper` (`ignoreUnknownKeys = true`,
     * lenient, Kilua + table serializer modules), the refresh with `toKotlinList` (`Json.decodeFromDynamic`
     * on the default `Json`: strict string parsing, no serializer modules). For a key the row serializer
     * does not know they agree: the dynamic decoder walks the descriptor's elements and never reads
     * extra keys, so both drop it and keep the row, and the re-encoded rows are identical.
     */
    @Test
    fun unknownKeyIsDroppedAlikeByBothDecoders() {
        val tab = detached()
        consoleErrors.clear()
        val raw: dynamic = JSON.parse("""[{"_id":"u","label":"U","extra":"X"}]""")
        val out = tab.canonicalRows(raw).unsafeCast<Array<dynamic>>()
        assertEquals("""{"_id":"u","label":"U"}""", JSON.stringify(out[0]), "loader: unknown key dropped, row kept")
        assertTrue(consoleErrors.isEmpty(), "a dropped unknown key is not a decode failure; got $consoleErrors")
        val refreshed = tab.toKotlinList(raw)
        assertEquals(1, refreshed.size, "refresh decoder: the row is kept")
        assertEquals(JSON.stringify(out[0]), JSON.stringify(tab.toPlainObj(refreshed[0])), "same re-encoded row")
    }

    /**
     * **The regression without any component formatter.** Row selection decodes every selected row
     * (KVision `getSelectedData()` → `toKotlinObjList`; `fsTabulator`'s `rowSelectionChanged` handler and
     * `ViewList.dataUpdate()` both call it), so a materialized `owner: {code: null}` broke lists with no
     * formatter at all. After the AJAX load, selecting the row and reading it back must decode.
     */
    @Test
    fun selectingARowWithANullParentDecodesWithoutAFormatter(): Promise<Unit> {
        rpcBody = listState("""{"_id":"a","label":"A","folio":null,"owner":null}""")
        val tab = mount(withFormatter = false)
        return awaitRendered(tab).then {
            tab.jsTabulator.asDynamic().selectRow()
            val selected = tab.getSelectedData()
            assertEquals(1, selected.size, "the selected row must decode")
            assertNull(selected[0].owner)
            assertNoOwnerKey(rowsOf(tab)[0], "no formatter, still no materialized parent")
        }
    }

    /** **Control.** A parent that is absent (the pre-0.0.46 wire) is never materialized. */
    @Test
    fun absentParentIsNeverMutated(): Promise<Unit> {
        rpcBody = listState("""{"_id":"a","label":"A"}""")
        val tab = mount()
        return awaitUntil("the formatter to decode the row") { formatted.size == 1 }.then {
            assertNoOwnerKey(rowsOf(tab)[0], "an absent parent must stay absent")
            assertNull(formatted[0].owner)
        }
    }

    /** **Control.** A present parent still reaches the nested cell and decodes. */
    @Test
    fun presentParentDecodes(): Promise<Unit> {
        rpcBody = listState("""{"_id":"b","label":"B","folio":7,"owner":{"code":"U1","name":"Ana"}}""")
        val tab = mount()
        return awaitUntil("the formatter to decode the row") { formatted.size == 1 }.then {
            assertEquals("U1", rowsOf(tab)[0].owner.code as String)
            assertEquals(FirstLoadOwner("U1", "Ana"), formatted[0].owner)
            assertEquals(7, formatted[0].folio)
        }
    }

    /**
     * **Control.** A row the canonicalization cannot decode is kept, in place, unchanged, and
     * reported — never dropped. The report names the view, the row index and a failure category, and
     * carries neither the row nor its values nor the exception text (which can quote the JSON).
     */
    @Test
    fun undecodableRowIsKeptInPlaceAndReported() {
        val tab = detached()
        val raw: dynamic = JSON.parse(
            """[{"_id":"a","label":"A","owner":null},{"_id":{"bad":1},"label":"SECRETVALUE"},{"_id":"c","label":"C"}]"""
        )
        consoleErrors.clear()
        val out = tab.canonicalRows(raw).unsafeCast<Array<dynamic>>()
        assertEquals(3, out.size, "no row may be dropped")
        assertEquals("""{"_id":"a","label":"A"}""", JSON.stringify(out[0]))
        assertEquals("""{"_id":{"bad":1},"label":"SECRETVALUE"}""", JSON.stringify(out[1]), "kept unchanged")
        assertEquals("""{"_id":"c","label":"C"}""", JSON.stringify(out[2]))
        assertEquals(1, consoleErrors.size, "exactly the bad row is reported; got $consoleErrors")
        val report = consoleErrors.single()
        assertTrue("FirstLoadList" in report && "row 1" in report, "view and index; got $report")
        assertTrue(Regex("""\(([A-Za-z]+)\)""").containsMatchIn(report), "a failure category; got $report")
        assertTrue(
            listOf("SECRETVALUE", "bad", "{", "\"").none { it in report },
            "no row content, values or JSON in the report; got $report",
        )
    }
}
