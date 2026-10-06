package com.fonrouge.fullStack.panel

import io.kvision.core.Container
import io.kvision.panel.SideTabSize
import io.kvision.panel.TabPanel
import io.kvision.panel.TabPosition

/**
 * A [TabPanel] kept only for source compatibility — **since fsLib 6.4.0 it adds nothing.**
 *
 * It was introduced in 6.2.3 because KVision's `TabPanel` kept its tabs outside `children` and
 * overrode `disposeAll()` but not `dispose()`, so disposing a panel never reached its tabs and no
 * dispose hook registered inside a tab ever ran. In fsLib that surfaced as a list embedded in a tab
 * that kept refreshing every 5 s after its view was closed.
 *
 * The fix went upstream as rjaros/kvision#584 and ships in **KVision 9.7.0**, which fsLib 6.4.0
 * requires. The plain `TabPanel` now disposes its tabs itself, so this class no longer overrides
 * anything and behaves exactly like `TabPanel`. Its retirement condition was met as designed: the
 * characterization test in `FsTabPanelDisposeTest`, which pinned the upstream defect, failed on
 * the upgrade and now pins the upstream fix instead.
 *
 * It is deprecated rather than removed so that 6.4.0 stays source-compatible; removal is deferred
 * to the next major version.
 */
@Deprecated(
    message = "KVision 9.7.0 disposes TabPanel tabs itself; use io.kvision.panel.TabPanel. " +
        "FsTabPanel will be removed in the next major version.",
    replaceWith = ReplaceWith("TabPanel", "io.kvision.panel.TabPanel"),
)
open class FsTabPanel(
    tabPosition: TabPosition = TabPosition.TOP,
    sideTabSize: SideTabSize = SideTabSize.SIZE_3,
    scrollableTabs: Boolean = false,
    draggableTabs: Boolean = false,
    className: String? = null,
    init: (TabPanel.() -> Unit)? = null,
) : TabPanel(tabPosition, sideTabSize, scrollableTabs, draggableTabs, className, init) {

    /**
     * How many tabs the panel still holds. Exists so tests can assert that disposal emptied the
     * `tabs` list — it is `protected` and absent from `getChildren()`, so without this the
     * assertion would be vacuous.
     */
    internal val pestanasVivas: Int get() = tabs.size
}

/**
 * DSL builder for [FsTabPanel]. Takes the same parameters as `io.kvision.panel.tabPanel`, which
 * now replaces it directly: swapping the call and the import is all a migration needs.
 */
@Deprecated(
    message = "KVision 9.7.0 disposes TabPanel tabs itself; use io.kvision.panel.tabPanel. " +
        "fsTabPanel will be removed in the next major version.",
    replaceWith = ReplaceWith(
        "tabPanel(tabPosition, sideTabSize, scrollableTabs, draggableTabs, className, init)",
        "io.kvision.panel.tabPanel",
    ),
)
@Suppress("DEPRECATION")
fun Container.fsTabPanel(
    tabPosition: TabPosition = TabPosition.TOP,
    sideTabSize: SideTabSize = SideTabSize.SIZE_3,
    scrollableTabs: Boolean = false,
    draggableTabs: Boolean = false,
    className: String? = null,
    init: (TabPanel.() -> Unit)? = null,
): FsTabPanel {
    val panel = FsTabPanel(tabPosition, sideTabSize, scrollableTabs, draggableTabs, className, init)
    this.add(panel)
    return panel
}
