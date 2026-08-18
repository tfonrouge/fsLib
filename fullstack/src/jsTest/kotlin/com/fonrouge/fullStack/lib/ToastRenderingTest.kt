package com.fonrouge.fullStack.lib

import com.fonrouge.base.state.SimpleState
import com.fonrouge.base.state.State
import io.kvision.toast.ToastOptions
import kotlinx.browser.document
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * Renders toasts into the real DOM and asserts what the user ends up looking at.
 *
 * These cover two couplings that fail silently: the CSS class a toast's colour comes from, and the
 * fact that a sticky toast has nothing to remove it.
 */
class ToastRenderingTest {

    private fun toastNodes() = document.getElementsByClassName("toastify")

    /** Classes of the single toast currently on screen. Asserting there is exactly one keeps the
     *  test independent of Toastify's insertion order (it prepends, so newest is *first*). */
    private fun classesOfOnlyToast(): String {
        val nodes = toastNodes()
        assertEquals(1, nodes.length, "expected exactly one toast on screen")
        return nodes.item(0)?.className ?: ""
    }

    private fun clearAllToasts() {
        val nodes = toastNodes()
        for (i in nodes.length - 1 downTo 0) nodes.item(i)?.remove()
    }

    @AfterTest
    fun clearToasts() = clearAllToasts()

    /**
     * Severity must survive the trip through the state. An `Error` rendering as a warning
     * understates a failure to the user, which is what the split toast paths used to do.
     */
    @Test
    fun severityMapsOntoKVisionToastClasses() {
        SimpleState(state = State.Ok, msgOk = "saved").toast()
        assertContains(classesOfOnlyToast(), "kv-toastify-success")
        clearAllToasts()

        SimpleState(state = State.Warn, msgError = "careful").toast()
        assertContains(classesOfOnlyToast(), "kv-toastify-warning")
        clearAllToasts()

        SimpleState(state = State.Error, msgError = "broke").toast()
        assertContains(classesOfOnlyToast(), "kv-toastify-danger")
    }

    /**
     * KVision's `Toast.show` does `opts["className"] = options.className ?: type.type`, so a
     * caller-supplied class *replaces* the type class rather than adding to it. A sticky toast
     * therefore has to re-apply the type class itself, or it renders unstyled. Nothing about that
     * is visible at compile time, hence this test.
     */
    @Test
    fun stickyToastKeepsItsSeverityClass() {
        SimpleState(state = State.Error, msgError = "broke").toast(sticky = true)

        val classes = classesOfOnlyToast()
        assertContains(classes, STICKY_TOAST_CLASS, message = "sticky toasts must be tagged so they can be cleared")
        assertContains(classes, "kv-toastify-danger", message = "a sticky toast must keep its severity colour")
    }

    /**
     * The leak this guards: toasts are appended to `document.body`, and a sticky one has no
     * timeout, so without an explicit sweep a refusal raised on one screen follows the user to the
     * next and repeated failures pile up.
     */
    @Test
    fun dismissStickyToastsClearsStickyOnesAndLeavesOthers() {
        SimpleState(state = State.Error, msgError = "first refusal").toast(sticky = true)
        SimpleState(state = State.Error, msgError = "second refusal").toast(sticky = true)
        SimpleState(state = State.Ok, msgOk = "unrelated success").toast()
        assertEquals(3, toastNodes().length, "all three toasts should be on screen")

        dismissStickyToasts()

        assertEquals(0, document.getElementsByClassName(STICKY_TOAST_CLASS).length, "no sticky toast may survive")
        assertEquals(1, toastNodes().length, "the ordinary toast must be left alone")
    }

    /**
     * A state carrying no message at all must still produce a toast. The previous `Ok` branch was
     * `msgOk?.let { ... }`, so a repository returning a success with no text showed the user
     * nothing whatsoever for an action they had just taken.
     */
    @Test
    fun stateWithoutMessageStillRendersSomething() {
        SimpleState(state = State.Ok, msgOk = null).toast()
        assertEquals(1, toastNodes().length, "a success with no message must still be announced")

        SimpleState(state = State.Error, msgError = null).toast()
        assertEquals(2, toastNodes().length, "an error with no message must still be announced")
    }

    /**
     * `rejectionToast` is the shared presentation for a refused write, used by both the upsert path
     * and the master-save path in `ViewList`. It must be sticky, must keep the severity of the
     * state it came from — a `Warn` refusal is not a hard error — and must supersede whatever
     * earlier refusal is still on screen rather than stacking on top of it.
     */
    @Test
    fun rejectionToastIsStickySupersedingAndKeepsSeverity() {
        SimpleState(state = State.Warn, msgError = "turned down").rejectionToast()

        val classes = classesOfOnlyToast()
        assertContains(classes, STICKY_TOAST_CLASS, message = "a refusal must stay until dismissed")
        assertContains(
            classes,
            "kv-toastify-warning",
            message = "a Warn refusal must not be dressed up as a hard error",
        )

        SimpleState(state = State.Error, msgError = "second attempt refused").rejectionToast()
        assertEquals(1, toastNodes().length, "a new refusal must replace the previous one, not stack")
        assertContains(classesOfOnlyToast(), "kv-toastify-danger")
    }

    /**
     * The leak the presentation split created: `rejectionToast` cleared prior refusals, but a
     * success announced through any other route did not, so a refusal from the failed attempt
     * stayed pinned next to the success message for the retry that fixed it.
     */
    @Test
    fun completionToastClearsAPriorRejection() {
        SimpleState(state = State.Error, msgError = "refused").rejectionToast()
        assertEquals(1, document.getElementsByClassName(STICKY_TOAST_CLASS).length, "refusal is on screen")

        SimpleState(state = State.Ok, msgOk = "saved on retry").completionToast()

        assertEquals(
            0,
            document.getElementsByClassName(STICKY_TOAST_CLASS).length,
            "a success must not leave the refusal it supersedes pinned to the screen",
        )
        assertContains(classesOfOnlyToast(), "kv-toastify-success")
    }

    /**
     * A caller's own `className` must be additive, not destructive. KVision replaces its type class
     * with whatever we hand it, so a caller tagging a toast would otherwise silently strip its
     * colour — contradicting the severity this function promises to preserve.
     */
    @Test
    fun callerClassNameIsAddedWithoutLosingSeverity() {
        SimpleState(state = State.Ok, msgOk = "saved").toast(
            options = ToastOptions(className = "caller-supplied"),
        )

        val classes = classesOfOnlyToast()
        assertContains(classes, "caller-supplied", message = "the caller's class must survive")
        assertContains(classes, "kv-toastify-success", message = "severity must survive the caller's class")
    }
}
