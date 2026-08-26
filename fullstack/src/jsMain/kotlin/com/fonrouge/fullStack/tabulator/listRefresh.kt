package com.fonrouge.fullStack.tabulator

/**
 * Hash of everything in a list response that, when changed, must reach the screen.
 *
 * This is the input to `TabulatorViewList`'s redraw gate: a response whose hash matches the
 * previous one is not pushed to Tabulator at all. Hashing only `data` left a hole — the footer
 * counter renders `last_row`, and a write can change the total while leaving the current page's
 * rows byte-identical (a created row that sorts onto another page). The estimate was then updated
 * internally but nothing triggered the redraw that repaints the counter, so the footer stayed on
 * the old total. Including the pagination metadata closes that: a metadata-only change now counts
 * as a change, while the no-op optimisation is preserved when neither rows nor totals moved.
 *
 * The hash also travels to the server in `ApiList.contentHashCode`; no engine or known consumer
 * reads it there (verified across mongodb/sql/memorydb and the consumer repos), so widening its
 * input is a client-local decision.
 */
internal fun listContentHash(jsonObj: dynamic): Int =
    "${JSON.stringify(jsonObj.data)}|${jsonObj.last_page}|${jsonObj.last_row}".hashCode()

/**
 * Runs [push] with the current row selection captured and restored around it, by `_id`.
 *
 * Extracted from `TabulatorViewList.apiCall()` so the selection-preserving refresh is the same
 * shipped code the browser tests drive, instead of a sequence each duplicates.
 *
 * @param jsTabulator the native Tabulator instance (may be `null` before initialization).
 * @param push the data push to wrap — typically a `replaceData` call.
 */
internal fun pushPreservingSelection(jsTabulator: dynamic, push: () -> Unit) {
    val table: dynamic = jsTabulator
    val selectedIds: Array<dynamic>? =
        (table?.getSelectedData()?.unsafeCast<Array<dynamic>>())?.map { it["_id"] }?.toTypedArray()
    push()
    if (selectedIds != null && selectedIds.isNotEmpty()) {
        table.selectRow(selectedIds)
    }
}
