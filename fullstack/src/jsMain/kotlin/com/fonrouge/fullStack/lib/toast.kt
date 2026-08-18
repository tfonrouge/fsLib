package com.fonrouge.fullStack.lib

import com.fonrouge.base.state.ISimpleState
import com.fonrouge.base.state.MSG_OK
import com.fonrouge.base.state.State
import io.kvision.i18n.I18n.gettext
import io.kvision.toast.Toast
import io.kvision.toast.ToastOptions
import kotlinx.browser.document

/**
 * CSS class applied to toasts that stay on screen until the user dismisses them, so that
 * [dismissStickyToasts] can find and clear them. Toasts are appended to `document.body` rather than
 * to the view that raised them, so nothing removes them when that view goes away.
 */
const val STICKY_TOAST_CLASS = "fs-sticky-toast"

/** Avatars shown alongside each severity. Warning/error icons: https://icons8.com/icons/set/warning */
private const val AVATAR_OK = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAADAAAAAwCAYAAABXAvmHAAAACXBIWXMAAAsTAAALEwEAmpwYAAAE1ElEQVR4nO2Z20/aZxjHf7FzUUQrIOdjO2tndrlsaa+aieIJD4iclZPGZX/BFm+I1RXQFpGjHLQ6TxXQJu26XnTr9ZJtzbbUJm2autlmWYv0WnrzLC9uxk6FH/IDtsRv8l7z+T487/s+7/eHYSc60YlyV0RxShgxXxSuGocFq6Y1/ophQ7BifM1b0b/hLfe/4S7pX3MX+h7yFvrWuAu6Ye6C7gJmsZRgxdZ7N418YWTAJoqaXwgjJhCumkBwwwiCFSPwVwzAX9YDb0kPvMX+1OIu9AH3Kx1w5nXAntM8Z81prdygjldw8NqIiS6MmgPC6EBSFDVDtvCceS2w57TAvq4B5ow6yZxV+dkBTU1B4M/GBrSimDkhig5ArvCsWfXumlEBI6TaZgaU6ryBfxgYKhXFBkOiGAInFp4ZVgEzpARGSAH0oGIaCwyVEgrPvjVEOhMb+Cbf8IygAuiBXqBP995hB6QkwipfYHigT8uhxi+7h0UU7+ZsoBBtwzgA3wM1vh6gemT+nODPrA3qigVP88qA5pEBxdWlOhY8Z72fJoyZ48WEp3q6geruTJCvSrM/YtE5X3z4LvQPAGWq05sVfO3aII+ISwoXvF8ONV5Zah0BD5SpjiTFKRXgr/7ueJB/eF8PsCdl8OmtcTg7pQKaq/sQ+E6odnbA6cl2Kz56i6VEGDE9LwQ8y9ENd598D0hPtrfgvFsHVAT9L/jqSSlUOaQv0NCIo/rmiwWpvEMG957+APv14I/HUG1vOwB/2oFWO1Rda/84s4FV43Ax4PcM2FqPgoeqq21fZDQguGFcxwU/pwXOrAbY14/fNvv1OL4FdU41UCY7joKHyvHWKB4DDzPCz2rh/bABPrvrAL5fDaywKqfKP3q5CbVOVXr4iVaoHG/5NbOBZUMiE/wHYRM8iv+W+vH7mw+A71UCK6TMX+UnWneXvTme0QBv2ZBM1/MsXy/8/OfTtyC+ffYjcN0KYAYU+an8RKr6QLY172Q2sKRPptuwTO9BA/+Y4LjkwEDQ+YC3twDZKslsgLvYn0h72syooT5o2Guh/br/7CfgTMmB7pMT1zbjf8PbmoFklWRuoVR6kOmoDKugPqA/1MR3yIRTTjh8hU0CpCuSzJuYu9i3juucDyqhfvpwE6hlCGsb2y58hRUZaMx8jKZyG7yXVKAXzvv6YePV5gFYIitfkYJvAtJY4+d4DFzI6ob1y6HO25fWBCHwXzZB+Yj4I1zDHHteu5XteFDn0cLGy838wY82/o47zUOJWdaPEY8Mzrk0b5kgDH6sEcpHG67ggk+1UVDHQ4lZ1i8pVzecm9KkhjK0ctuwTfvhd8rHGrhYNkJx37GegWiStLftTpVEwI+JoWxU7MayFS+koDLCyvgx37DpRuLs4C83bJMtl46Xm6Ksspjw5aNiKB9pUGC5CGWVxYIvG/nEg+WsiOJUjb/3ZsHhLzd8jVkuvUNMuBuQkmg+2Z0CVv42ZiEo3N1TYKgUZZUFaRsLQZU/TCirpLq64nk4bV7lvGHxqtLdTUNxH8XZuUPEJVU2KnZXWSRUrNCiXmvnosSsytG+lT28eAuNB1nfsHmRxVKCQieU26DoA6UHZHtLgmxvTpJtkiTJ2pQgWZt+QfM8GolTU+V/4TPriU6E/f/1F2g37z10XXwqAAAAAElFTkSuQmCC"
private const val AVATAR_WARN = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAADAAAAAwCAYAAABXAvmHAAAACXBIWXMAAAsTAAALEwEAmpwYAAACgUlEQVR4nO1Zy2oUURBtNC5252vj4x+Enjq2EmioGoILl0ZFPyTqxo2PrENICP6BEhca/A9FP0DNRieGZO7tmLhpqRkddIL0496e7pE+UNDQiz6nbt17q04HQYsWLZyRzs8f3RO6YhkPLGPdCn2wQttG8ENDn63g/fAd7iddROnD4EhQN/bmOheNYNEIbVpBWiSM0GfDeJrEuDBx4v1rl84apjXDOChK/JAQxoFlrOwKnZ4IedOlO5bpmyvxQ8HYMtK5VRnxNAyPGcEz78RlvLSwmsbxjF/y18MTRvCmavJ2VFa0od/0mfnC5NPe9l9RSkTsYSXKlo2rADvcFyuO5Olu2TLwIkCQmi7dLEVejzUr6NUtwDK2+nF4pnj2mdZcNqI3AaJBy4XI6+3oekn5FGAY+wlH5/NnX7DoljHfKwA9lZ7kIq9NlvYpjRMgtKlNY6aAQVfp+LEqBFhBmnCITAGDlrihAmyX7uWp/5eNFSD0InsFdPBoqgCmdzlKyE+rXM0KoJddQh6GlKoEGMb+/y/ATnsJ2enfxFhvrADB8+m+yJgWMgWo6dRUAYl0KF8zx/jUNAGG8TG3m6eOmaea9RZG6HEu8r4GGq/kueBAo1BHoG7i9ncwloKi2JmLTlnG1yYM9btlfVPDl2/XLaDPuFGK/EiEYLU2AVyidMahc2iZIcfdWsRrbybvwNxl2qhsk8oYecGrNIqOeyE/EhHHMxM5mRhL3u31P6FepYvl+G/i9MV5wxY6YoWW9YJxLxf6rlnfmZ09GUwaejuqY1amd9LexggeJd2r54K6oU2Wmk7q26j1oYOHTnbajgx/4umUR29/vVvQrrIRv1lbtAimHz8BNz/RC6gTB7UAAAAASUVORK5CYII="
private const val AVATAR_ERROR = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAADAAAAAwCAYAAABXAvmHAAAACXBIWXMAAAsTAAALEwEAmpwYAAADDUlEQVR4nO1Zy2oUQRRtfC1c+lj5+ABXQs+9TlYDVW1w4XZQdOnadWICBg1ksjdhIn6CqAsTQQX/QdEPMLoyiWiqZuhxHiW3cGZ0ppOu6q7uHmEuFAx00XNO1X2ce9vzpja1qaU2Va0ebXKYkQwXJMNnksMnyeG74PiLFv2WHD/qZwwXGgGW1ZJ3xCvamrOlC4LjquDwVXJUNktw+CIY1hoVPJ878P1rl88KBo8Fw5Yt8DEiDFuCY32/4p/JBbwI4JZksJcW+NhiuCvYlZuZAVe+f1xwfOIcOB9zrQ36L7fgr/snBcdXWYOXQxJb9J8uTz438HLoUm9V9dKJ1ATycBt54E1gPSV4uF0UeDkgUbqRCPxPDqclx52iCUgGe4lSLOX5wsHzQTysW4Gn6mhVpIKyat69YwxI7w3K5m7EsNUM/Ivmp89x1QZ8+/WmUt2uCmtLsfvDh/eU6nRU+90bJWdnbEjUjMCTyCKdYgW+bzEk+uD71rYgQXqLRGMsAa0qLVzhb0DaOh0VLi+Og19ejNzbtHC9BvMxloCWxBYBFj6YHwc2chOjJz/Ys3LfLpgDmDfx/+dWL40h4Qw8pwVP42+AGg/rFx9AotfVYN2AR6oJHwxcKLlUjiThCjzXayfehVI2KZrE6Knr2+ilBa8EwzB7AuTzkQTM6oRMSyCVC0UFbERgZ+pCMmkQR4Hv9aKDOCkJZhDEidLoIanSpE5Ip2nUtpAZ5HlnJBjMxRKgodPESgleAjMxx3A7sZg7JFWO3kSbxNxVM1ktGH42nuaRdE0kpw3yfJ+EDXipCcCKEfgJbWjCBiufMyagb4Fj3SrAMl3wyLO1H7PlU5PR1ONu4rkpzSqLJiAYVhOBH5DgsFGg66x5aY36UMHhRf4nD5uqUjmWmsBwuAtbObrNS2fD3ZEhbw6ZCdacnXyU0awyk+zE8FvqgLWamzJcpwLjwF1CyvOUtr28jaojyQ5j7fQv8G2SB9YVNgsjkUVDJ5rbkGanxoM6O/0BjySJ7vLg/Z9nc6QqJ+Iz69Sm5v3/9htwCyTCs1agAgAAAABJRU5ErkJggg=="

/**
 * The CSS class KVision derives a toast's colour from.
 *
 * These mirror `io.kvision.toast.ToastType`, which is `internal` and so cannot be referenced here.
 * Re-applying the class is not optional: KVision's `Toast.show` does
 * `opts["className"] = options.className ?: type.type`, so supplying any `className` of our own
 * *replaces* the type class instead of adding to it, and the toast loses its colour. This is why
 * [toast] always composes the severity class into `className` rather than only doing so for sticky
 * toasts. Pinned by `ToastRenderingTest`.
 */
private val State.kvToastClass: String
    get() = when (this) {
        State.Ok -> "kv-toastify-success"
        State.Warn -> "kv-toastify-warning"
        State.Error -> "kv-toastify-danger"
    }

/** The avatar shown for each severity. */
private val State.avatar: String
    get() = when (this) {
        State.Ok -> AVATAR_OK
        State.Warn -> AVATAR_WARN
        State.Error -> AVATAR_ERROR
    }

/**
 * Displays a toast notification for this state — the single place where a state becomes a toast.
 *
 * Severity follows [state]: `Ok` renders as a success toast, `Warn` as a warning, `Error` as a
 * danger toast. Callers that need to react to the toast (closing a view once it fades, say) pass
 * [options]; callers with a better message than the state carries pass [message].
 *
 * Messages are translated only when they are one of the framework's own defaults, so an app with a
 * translated UI does not get a lone English toast from a repository that supplied no text of its
 * own. Text written by the server passes through untouched — see [translatedIfFrameworkDefault],
 * which documents why translating it would corrupt rather than translate it.
 *
 * @param options extra toast options; `avatar`, and — when [sticky] — `duration` and `className`,
 *        are supplied by this function.
 * @param message overrides the message derived from [ISimpleState.msgOk] / [ISimpleState.msgError].
 * @param sticky keeps the toast on screen until dismissed, instead of fading after the default
 *        duration. Use it for anything the user must be able to read and act on; such toasts are
 *        tagged with [STICKY_TOAST_CLASS] so they can be cleared with [dismissStickyToasts].
 */
fun ISimpleState.toast(
    options: ToastOptions? = null,
    message: String? = null,
    sticky: Boolean = false,
) {
    val text = message ?: when (state) {
        // A state carrying no message at all still deserves a toast: staying silent would leave the
        // user with no feedback whatsoever for an action they just took.
        State.Ok -> msgOk?.translatedIfFrameworkDefault() ?: gettext(MSG_OK)
        State.Warn, State.Error -> msgError?.translatedIfFrameworkDefault() ?: gettext("Unknown error")
    }
    val toastOptions = (options ?: ToastOptions()).copy(
        avatar = options?.avatar ?: state.avatar,
        duration = if (sticky) STICKY_DURATION else options?.duration,
        // The severity class is always first and always present: KVision *replaces* its type class
        // with whatever `className` we supply, so anything a caller adds would otherwise cost the
        // toast its colour and quietly contradict the severity promised above.
        className = listOfNotNull(
            state.kvToastClass,
            options?.className,
            if (sticky) STICKY_TOAST_CLASS else null,
        ).joinToString(" "),
    )
    when (state) {
        State.Ok -> Toast.success(message = text, options = toastOptions)
        State.Warn -> Toast.warning(message = text, options = toastOptions)
        State.Error -> Toast.danger(message = text, options = toastOptions)
    }
}

/**
 * Shows this state as the successful outcome of a user action.
 *
 * The counterpart to [rejectionToast], and the reason it exists as its own function: a sticky
 * refusal from an earlier attempt has no timeout and nothing else removes it, so an outcome that
 * supersedes it has to clear it. Announcing success while the previous failure is still pinned to
 * the screen tells the user two contradictory things at once.
 *
 * @param options extra toast options, forwarded to [toast].
 * @param message overrides the message derived from the state.
 */
fun ISimpleState.completionToast(options: ToastOptions? = null, message: String? = null) {
    dismissStickyToasts()
    toast(options = options, message = message)
}

/**
 * Shows this state as a refusal the user has to read and act on.
 *
 * Refusals are sticky — a message explaining why a write was turned down is worthless if it fades
 * before it can be read — and any earlier refusal is cleared first, since it refers to an attempt
 * this one supersedes. Severity still follows [ISimpleState.state], so a `Warn` refusal is not
 * dressed up as a hard error.
 */
fun ISimpleState.rejectionToast() {
    dismissStickyToasts()
    toast(
        options = ToastOptions(close = true, stopOnFocus = true),
        sticky = true,
    )
}

/** Toastify's sentinel for "never auto-dismiss". */
private const val STICKY_DURATION = -1

/**
 * Removes any sticky toast still on screen.
 *
 * Toastify appends toasts to `document.body`, not to the view that raised them, and a sticky toast
 * has no timeout to end it — so without this a refusal raised on one screen would follow the user
 * to the next, and repeated failures would stack up indefinitely. KVision's `Toast` API returns no
 * handle to the toast it creates, so the elements are cleared directly.
 */
fun dismissStickyToasts() {
    val nodes = document.getElementsByClassName(STICKY_TOAST_CLASS)
    // Live collection: iterate backwards so removals do not shift the elements still to be visited.
    for (i in nodes.length - 1 downTo 0) {
        nodes.item(i)?.remove()
    }
}
