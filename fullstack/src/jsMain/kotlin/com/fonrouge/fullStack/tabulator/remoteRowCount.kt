package com.fonrouge.fullStack.tabulator

/**
 * Refreshes the row-count estimate that Tabulator's pagination counter reads, after a response that
 * did not travel through Tabulator's own remote loader.
 *
 * In remote pagination mode the footer counter (`paginationCounter = "rows"`) takes its total from
 * exactly one field — `table.modules.page.remoteRowCountEstimate` — which Tabulator assigns in
 * exactly one place: `Page._parseRemoteData`, the handler for data arriving as a
 * `{last_page, last_row, data}` envelope through the ajax pipeline. Data pushed in as a bare row
 * array (`replaceData`/`setData`, which is how [TabulatorViewList.apiCall] refreshes) never runs
 * that handler, so the rows on screen update while the counter keeps announcing the previous
 * total. `setMaxPage()` — the patch that has long sat next to this call — updates the page *count*
 * but not the row estimate, so it never covered the counter. All four behaviours are pinned
 * empirically by `PaginationCounterTest`.
 *
 * Must be called **before** the data push: the counter re-renders as part of the push's redraw, and
 * reads whatever the estimate holds at that moment.
 *
 * @param jsTabulator the native Tabulator instance (may be `null` before initialization).
 * @param lastPage the response's `last_page`, or `null` when absent.
 * @param lastRow the response's `last_row` — the authoritative total — or `null` when the backend
 *        skipped the count; Tabulator's own fallback formula is applied then, mirroring
 *        `_parseRemoteData`.
 * @param page the page that was requested.
 * @param size the page size that was requested.
 * @param dataLength the number of rows in the response.
 */
internal fun refreshRemoteRowCountEstimate(
    jsTabulator: dynamic,
    lastPage: Int?,
    lastRow: Int?,
    page: Int,
    size: Int,
    dataLength: Int,
) {
    // NB: the parameter is already `dynamic` — calling .asDynamic() on it would compile to a
    // literal (nonexistent) JS method call and throw at runtime.
    val pageModule = jsTabulator?.modules?.page ?: return
    val estimate = lastRow
        ?: lastPage?.let { lp -> lp * size - (if (page == lp) size - dataLength else 0) }
        ?: return
    pageModule.remoteRowCountEstimate = estimate
}
