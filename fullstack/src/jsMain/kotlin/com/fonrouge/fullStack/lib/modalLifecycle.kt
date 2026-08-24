package com.fonrouge.fullStack.lib

import io.kvision.modal.Modal

/**
 * Disposes this modal once Bootstrap reports it closed, so that closing it actually releases it.
 *
 * `Modal.hide()` does not dispose. A modal registers itself in KVision's `Root` modal registry in
 * its constructor and is removed from it only by `dispose()`, so a modal that is built per use and
 * merely hidden is retained for the lifetime of the page — along with everything it contains:
 * grids, date pickers, observable subscriptions. KVision renders only the *visible* modals, so the
 * DOM node does go away on close; what stays is the object graph behind it.
 *
 * Call this immediately after `show()`. The element only exists once the modal is shown, and
 * KVision's rendering is synchronous at that point, so the listener attaches. Bootstrap fires
 * `hidden.bs.modal` after KVision's own handler for the same event, and disposing from inside that
 * handler is safe — no deferral to a timeout or microtask is needed. A second `dispose()` is
 * tolerated, so this is safe alongside any other disposal path.
 *
 * This deliberately does not cover modals built elsewhere in the framework — `withProgress`,
 * `showManualModal`, `userSessionInfoModal`, `IViewListChangeLog` all construct modals the same
 * throwaway way and are not routed through here yet.
 */
internal fun Modal.disposeOnHidden() {
    getElement()?.addEventListener("hidden.bs.modal", { _ -> dispose() })
}
