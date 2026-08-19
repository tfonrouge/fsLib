package com.fonrouge.fullStack.view

import com.fonrouge.base.api.ApiFilter
import com.fonrouge.base.api.CrudTask
import com.fonrouge.base.api.IApiItem
import com.fonrouge.base.common.ICommonContainer
import com.fonrouge.base.model.BaseDoc
import com.fonrouge.base.state.ItemState
import com.fonrouge.base.state.State
import com.fonrouge.fullStack.config.ConfigViewItem
import com.fonrouge.fullStack.lib.STICKY_TOAST_CLASS
import io.kvision.core.Container
import io.kvision.form.FormPanel
import kotlinx.browser.document
import kotlinx.serialization.Serializable
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ── Minimal real ViewItem ────────────────────────────────────

/** Entity for the fixture below. */
@Serializable
private data class UItem(
    override val _id: String = "",
    val name: String = "",
) : BaseDoc<String>

/** Entity metadata for [UItem]. */
private object UCommon : ICommonContainer<UItem, String, ApiFilter>(
    itemKClass = UItem::class,
    filterKClass = ApiFilter::class,
)

/** Config for [UView]; `apiItemFun` is never reached — these tests drive the outcome directly. */
private class UConfig : ConfigViewItem<UItem, String, UView, ApiFilter, Any>(
    commonContainer = UCommon,
    apiItemFun = { ItemState() },
    viewKClass = UView::class,
)

/**
 * A concrete [ViewItem] that is never rendered. The outcome handling under test runs entirely off
 * the view's own state, so no container, form, or RPC round trip is needed to exercise it.
 */
private class UView(config: UConfig = UConfig()) : ViewItem<UItem, String, ApiFilter>(config) {

    /** Records what [onUpsertResult] was handed, so routing can be asserted. */
    val handled = mutableListOf<ItemState<UItem>>()

    /** Never called: these tests do not render the page. */
    override fun Container.pageItemBody(): FormPanel<UItem> = error("not rendered in tests")

    override fun onUpsertResult(itemState: ItemState<UItem>) {
        handled += itemState
    }
}

/**
 * Covers what an upsert outcome does to the view's own state — the item it republishes and where it
 * routes — without rendering anything.
 */
class UpsertOutcomeTest {

    private fun stickyToasts() = document.getElementsByClassName(STICKY_TOAST_CLASS)

    @AfterTest
    fun clearToasts() {
        val nodes = document.getElementsByClassName("toastify")
        for (i in nodes.length - 1 downTo 0) nodes.item(i)?.remove()
    }

    // ── republishing the accepted item ──────────────────────────

    /**
     * The gap this closes: everything derived from `item` — title, context menu, bindings — kept
     * rendering the pre-write world after a save, and on a Create that world had `item == null`.
     */
    @Test
    fun completedWriteRepublishesTheAcceptedItem() {
        val view = UView()
        val saved = UItem("u1", "saved name")

        view.applyUpsertOutcome(
            itemState = ItemState(item = saved),
            crudTask = CrudTask.Create,
            data = UItem("u1", "submitted name"),
        )

        assertEquals(saved, view.item, "the accepted item must become the view's item")
    }

    /**
     * A refusal must leave the view showing exactly what the user still has in front of them.
     * Republishing a refused write would swap the form out from under a correction in progress.
     */
    @Test
    fun refusedWriteDoesNotRepublish() {
        val view = UView()
        val onScreen = UItem("u1", "what the user is editing")
        view.item = onScreen

        view.applyUpsertOutcome(
            itemState = ItemState(item = UItem("u1", "not accepted"), state = State.Warn, msgError = "refused"),
            crudTask = CrudTask.Update,
            data = onScreen,
        )

        assertEquals(onScreen, view.item, "a refusal must not replace the item on screen")
    }

    /**
     * The null-guard. A write can complete without echoing the item back; blanking the form in that
     * case would erase what the user is looking at as a *reward* for a successful save.
     */
    @Test
    fun completedWriteWithoutAnItemLeavesTheCurrentOneAlone() {
        val view = UView()
        val onScreen = UItem("u1", "still here")
        view.item = onScreen

        view.applyUpsertOutcome(
            itemState = ItemState(item = null, state = State.Ok),
            crudTask = CrudTask.Update,
            data = onScreen,
        )

        assertEquals(onScreen, view.item, "a completed write with no item must not blank the view")
    }

    /** A no-op update is a completed write, so it republishes like any other. */
    @Test
    fun benignNoOpUpdateIsTreatedAsCompleted() {
        val view = UView()
        val unchanged = UItem("u1", "unchanged")

        view.applyUpsertOutcome(
            itemState = ItemState(item = unchanged, state = State.Warn, noDataModified = true),
            crudTask = CrudTask.Update,
            data = unchanged,
        )

        assertEquals(unchanged, view.item, "a no-op update left the store as asked; it is complete")
    }

    // ── routing ─────────────────────────────────────────────────

    /**
     * `onUpsertResult` promises a completed write. Routing refusals away from it is what makes that
     * promise true — an override that only knows how to handle success must not be able to swallow
     * a refusal, which is how the framework's sticky refusal toast used to go missing.
     */
    @Test
    fun onlyCompletedWritesReachTheOverridableHook() {
        val view = UView()

        view.dispatchUpsertResult(ItemState(item = UItem("u1", "saved"), state = State.Ok))
        assertEquals(1, view.handled.size, "a completed write must reach the hook")
        assertTrue(view.handled.single().isWriteComplete)

        view.dispatchUpsertResult(ItemState<UItem>(state = State.Warn, msgError = "refused"))
        view.dispatchUpsertResult(ItemState<UItem>(state = State.Error, msgError = "broke"))
        assertEquals(1, view.handled.size, "refusals must not reach the hook")
    }

    /**
     * The other half: a refusal is still presented, by the framework, even though the view's
     * override never sees it. `UView.onUpsertResult` deliberately shows nothing, so any toast here
     * came from the dispatcher.
     */
    @Test
    fun refusalIsStillPresentedEvenWhenTheHookShowsNothing() {
        val view = UView()

        view.dispatchUpsertResult(ItemState<UItem>(state = State.Error, msgError = "refused"))

        assertEquals(1, stickyToasts().length, "a refusal must be announced by the framework")
        assertTrue(view.handled.isEmpty(), "and must not have gone through the override")
    }

    // ── unsaved-changes baseline ────────────────────────────────

    /**
     * With no form attached the baseline falls back to the accepted item rather than the submitted
     * data, so a server that normalised a value does not leave the view looking dirty.
     *
     * Note this covers the fallback only. The primary path derives the baseline from the live form,
     * which needs a rendered `FormPanel`; that remains uncovered here.
     */
    @Test
    fun baselineFallsBackToTheAcceptedItemNotTheSubmittedData() {
        val view = UView()
        val accepted = UItem("u1", "NORMALISED")

        view.applyUpsertOutcome(
            itemState = ItemState(item = accepted, state = State.Ok),
            crudTask = CrudTask.Update,
            data = UItem("u1", "normalised"),
        )

        assertEquals(accepted, view.item)
    }

    /** A refusal must not record a baseline at all — the edits are still unsaved. */
    @Test
    fun refusedWriteRecordsNoBaseline() {
        val view = UView()

        view.applyUpsertOutcome(
            itemState = ItemState<UItem>(state = State.Error, msgError = "refused"),
            crudTask = CrudTask.Update,
            data = UItem("u1", "rejected edits"),
        )

        assertNull(view.item, "nothing was accepted, so nothing was published")
    }
}
