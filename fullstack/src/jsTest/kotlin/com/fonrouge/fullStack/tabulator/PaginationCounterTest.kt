package com.fonrouge.fullStack.tabulator

import io.kvision.panel.Root
import io.kvision.tabulator.PaginationMode
import io.kvision.tabulator.Tabulator
import io.kvision.tabulator.TabulatorOptions
import io.kvision.utils.obj
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Pins the pagination-counter contract for programmatic refreshes.
 *
 * In remote pagination mode the footer counter's total comes exclusively from
 * `modules.page.remoteRowCountEstimate`, which only Tabulator's own remote loader assigns. fsLib's
 * `TabulatorViewList.apiCall()` refreshes by fetching out-of-band and pushing a bare row array
 * through `replaceData` — a path that never updates the estimate, so the rows changed on screen
 * while the footer kept announcing the previous total (the stale "1-5 / 5" bug). The fix,
 * [refreshRemoteRowCountEstimate], is called by `promise()` before the push; this suite drives the
 * same native calls against a real Tabulator in a real browser.
 *
 * The suite deliberately also pins the three things that do NOT fix the counter — `replaceData`,
 * `setData`, and `setMaxPage` — because each was a plausible fix (one of them shipped for years as
 * the partial `setMaxPage` patch, another was the leading hypothesis when this bug was filed), and
 * the next person to touch this code should find the dead ends already marked.
 */
class PaginationCounterTest {

    private var tab: Tabulator<dynamic>? = null

    @BeforeTest
    fun setUp() {
        // One Root for the whole browser session: adding to a freshly created second Root does not
        // render (KVision patches the first root's tree), so every suite shares the first one.
        if (Root.getFirstRoot() == null) {
            val el = document.createElement("div") as HTMLElement
            el.id = "pagination-test-root-${counter++}"
            document.body?.appendChild(el)
            Root(el)
        }
    }

    /** Disposing the table removes its footer, so the next test's counter query cannot see it. */
    @AfterTest
    fun tearDown() {
        tab?.dispose()
        tab = null
    }

    private fun rows(n: Int, from: Int = 0): Array<dynamic> {
        val a = js("[]")
        for (i in from until from + n) a.push(obj { this._id = "id$i"; this.name = "row $i" })
        return a.unsafeCast<Array<dynamic>>()
    }

    private fun envelope(total: Int, page: Int, size: Int): dynamic {
        val lastPage = kotlin.math.max(1, (total + size - 1) / size)
        val count = if (page == lastPage) total - (page - 1) * size else size
        return obj {
            this.last_page = lastPage
            this.last_row = total
            this.data = rows(count, from = (page - 1) * size)
        }
    }

    private fun delay(ms: Int): Promise<Unit> =
        Promise { resolve, _ -> window.setTimeout({ resolve(Unit) }, ms) }

    /** Scoped to this test's own table — other suites in the session may have left tables behind. */
    private fun counterText(): String =
        tab?.getElement()?.querySelector(".tabulator-page-counter")?.textContent?.trim() ?: "<no counter>"

    /** Builds a remote-pagination Tabulator wired the way `TabulatorViewList` wires one. */
    private fun buildTable(size: Int, served: () -> Int): Tabulator<dynamic> {
        var requestedPage = 1
        val tab: Tabulator<dynamic> = Tabulator(
            data = null,
            dataUpdateOnEdit = false,
            options = TabulatorOptions(
                selectableRows = 1, // matches defaultTabulatorOptions; selectRow is inert without it
                index = "_id", // matches defaultTabulatorOptions; row lookup by _id fails without it
                pagination = true,
                paginationMode = PaginationMode.REMOTE,
                paginationSize = size,
                paginationCounter = "rows",
                height = "300px",
                ajaxURL = "/fake",
                ajaxRequestFunc = { _, _, params ->
                    requestedPage = params.asDynamic()?.page as? Int ?: 1
                    Promise { resolve, _ -> resolve(envelope(served(), requestedPage, size)) }
                },
            ),
            kClass = null,
        )
        Root.getFirstRoot()!!.add(tab)
        this.tab = tab
        return tab
    }

    /** Reproduces `apiCall()`'s push: KVision's replaceData body, post-serialization. */
    private fun pushLikeApiCall(tab: Tabulator<dynamic>, data: Array<dynamic>) {
        val jsT = tab.jsTabulator.asDynamic()
        val oldPagination = jsT.options.pagination
        jsT.options.pagination = false
        jsT.replaceData(data, null, null)
        jsT.options.pagination = oldPagination
    }

    /**
     * The bug, kept failing-if-fixed-upstream: a bare-array push updates the rows but not the
     * counter, and neither `setData` nor `setMaxPage` repairs it. If a Tabulator upgrade makes any
     * of these assertions fail, the workaround in `promise()` can likely be removed — that is a
     * finding, not a regression.
     */
    @Test
    fun barePushesLeaveTheCounterStale(): Promise<Unit> {
        val tab = buildTable(size = 100) { 5 }
        return delay(300).then {
            assertEquals("1-5 / 5", counterText(), "initial remote load must set the counter")
        }.then {
            pushLikeApiCall(tab, rows(6))
            delay(300)
        }.then {
            assertEquals("1-5 / 5", counterText(), "replaceData alone must NOT update the counter (documented Tabulator behaviour this workaround exists for)")
        }.then {
            tab.jsTabulator.asDynamic().setData(rows(7), null, null)
            delay(300)
        }.then {
            assertEquals("1-5 / 5", counterText(), "setData does not update it either — it was the leading wrong hypothesis")
        }.then {
            tab.jsTabulator.asDynamic().setMaxPage(1)
            delay(200)
        }.then {
            assertEquals("1-5 / 5", counterText(), "setMaxPage patches the page count, not the row counter")
            Unit
        }
    }

    /** The fix: refreshing the estimate before the push brings the counter to the real total. */
    @Test
    fun refreshedEstimateUpdatesTheCounter(): Promise<Unit> {
        val tab = buildTable(size = 100) { 5 }
        return delay(300).then {
            assertEquals("1-5 / 5", counterText())
        }.then {
            // exactly what promise() now does before apiCall pushes
            refreshRemoteRowCountEstimate(
                jsTabulator = tab.jsTabulator,
                lastPage = 1,
                lastRow = 6,
                page = 1,
                size = 100,
                dataLength = 6,
            )
            pushLikeApiCall(tab, rows(6))
            delay(300)
        }.then {
            assertEquals("1-6 / 6", counterText(), "the counter must show the refreshed total")
            Unit
        }
    }

    /** Without `last_row`, the fallback formula (mirroring Tabulator's own) still yields the total. */
    @Test
    fun fallbackFormulaCoversAMissingLastRow(): Promise<Unit> {
        val tab = buildTable(size = 100) { 5 }
        return delay(300).then {
            refreshRemoteRowCountEstimate(
                jsTabulator = tab.jsTabulator,
                lastPage = 1,
                lastRow = null,
                page = 1,
                size = 100,
                dataLength = 8,
            )
            pushLikeApiCall(tab, rows(8))
            delay(300)
        }.then {
            assertEquals("1-8 / 8", counterText(), "last_page * size - (size - dataLength) = 8")
            Unit
        }
    }

    /** Native page navigation still works and still maintains its own counter. */
    @Test
    fun nativePageNavigationIsUntouched(): Promise<Unit> {
        val tab = buildTable(size = 100) { 250 }
        return delay(300).then {
            assertEquals("1-100 / 250", counterText(), "page 1 of a 250-row set")
        }.then {
            tab.jsTabulator.asDynamic().nextPage()
            delay(300)
        }.then {
            assertEquals("101-200 / 250", counterText(), "page 2 via native navigation")
        }.then {
            tab.jsTabulator.asDynamic().setPage(3)
            delay(300)
        }.then {
            assertEquals("201-250 / 250", counterText(), "last page via native navigation")
            Unit
        }
    }

    // ── the redraw-gate hole (external review finding) ──────────

    /**
     * The gate's input must treat a metadata-only change as a change. A created row can sort onto
     * another page: the current page's rows stay byte-identical while `last_row` moves, and a
     * data-only hash skipped the push — and with it the redraw that repaints the counter.
     */
    @Test
    fun contentHashChangesWhenOnlyTheTotalChanges() {
        fun response(lastRow: Int): dynamic {
            val o = obj { }
            o.data = rows(5)
            o.last_page = 1
            o.last_row = lastRow
            return o
        }
        assertEquals(
            listContentHash(response(5)),
            listContentHash(response(5)),
            "identical responses must hash identically — the no-op optimisation depends on it",
        )
        assertNotEquals(
            listContentHash(response(5)),
            listContentHash(response(6)),
            "a total change with identical rows must count as a change, or the counter goes stale",
        )
    }

    /**
     * The same scenario at the DOM: the page's five rows are unchanged, only the total grew.
     * After the (now no longer skipped) push, the footer must read the new total.
     */
    @Test
    fun sameRowsWithNewTotalRepaintTheCounter(): Promise<Unit> {
        val tab = buildTable(size = 5) { 5 }
        return delay(300).then {
            assertEquals("1-5 / 5", counterText())
        }.then {
            // a sixth row exists but sorts onto page 2: page 1's rows are identical
            refreshRemoteRowCountEstimate(
                jsTabulator = tab.jsTabulator,
                lastPage = 2,
                lastRow = 6,
                page = 1,
                size = 5,
                dataLength = 5,
            )
            pushLikeApiCall(tab, rows(5))
            delay(300)
        }.then {
            assertEquals("1-5 / 6", counterText(), "the footer must show the new total for identical page rows")
            Unit
        }
    }

    // ── behaviours the fix must not break ───────────────────────

    /** Row selection survives a refresh, through the shipped helper `apiCall` itself uses. */
    @Test
    fun selectionSurvivesARefresh(): Promise<Unit> {
        val tab = buildTable(size = 100) { 5 }
        return delay(300).then {
            tab.jsTabulator.asDynamic().selectRow(arrayOf("id2"))
            assertEquals(1, selectedIds(tab).size, "row id2 selected before the refresh")
        }.then {
            pushPreservingSelection(tab.jsTabulator) {
                pushLikeApiCall(tab, rows(6))
            }
            delay(300)
        }.then {
            assertEquals(listOf("id2"), selectedIds(tab), "the same row must be selected after the refresh")
            Unit
        }
    }

    /** The page-size selector still drives a remote reload with a correct counter. */
    @Test
    fun pageSizeChangeReloadsWithACorrectCounter(): Promise<Unit> {
        val tab = buildTable(size = 100) { 250 }
        return delay(300).then {
            assertEquals("1-100 / 250", counterText())
        }.then {
            tab.jsTabulator.asDynamic().setPageSize(50)
            delay(300)
        }.then {
            assertEquals("1-50 / 250", counterText(), "size 50 must reload page 1 with the same total")
            Unit
        }
    }

    private fun selectedIds(tab: Tabulator<dynamic>): List<String> =
        tab.jsTabulator.asDynamic().getSelectedData()
            .unsafeCast<Array<dynamic>>()
            .map { "${it["_id"]}" }

    companion object {
        private var counter = 0
    }
}
