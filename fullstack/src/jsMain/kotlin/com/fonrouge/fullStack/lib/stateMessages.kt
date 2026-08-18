package com.fonrouge.fullStack.lib

import com.fonrouge.base.state.MSG_ERROR
import com.fonrouge.base.state.MSG_OK
import io.kvision.i18n.I18n.gettext

/**
 * The framework's own default state messages — the only state text this module is entitled to
 * translate. Every other message reaching a toast was written by a repository or a domain hook.
 */
private val frameworkDefaultMessages = setOf(MSG_OK, MSG_ERROR)

/**
 * Translates a state message, but only when it is one of the framework's own defaults.
 *
 * [MSG_OK] and [MSG_ERROR] are written in English in `:core` and are what every repository surfaces
 * when it does not supply its own text, so an app with a fully translated UI still showed a lone
 * English toast. They are fixed, known constants, so translating them is both safe and extractable.
 *
 * **Server-authored text is returned unchanged, deliberately.** Passing arbitrary strings through
 * `gettext` is not the no-op it appears to be. KVision's `DefaultI18nManager` delegates to
 * gettext.js, which runs its `strfmt` substitution pass over the *untranslated* msgid just as it
 * does over a real translation. Any `%` followed by digits is taken as a positional placeholder
 * and — with no arguments supplied — replaced by `undefined`:
 *
 * ```
 * gettext("Descuento %10 excede el limite")  // -> "Descuento undefined excede el limite"
 * ```
 *
 * Domain messages are exactly the strings that must not reach a formatter, because they routinely
 * embed user data: `Coll.friendlyExceptionMessage` builds its duplicate-key message out of the
 * offending document's own values. Such messages are also invisible to gettext extraction, being
 * runtime values rather than source literals, so routing them through a catalog was never going to
 * translate them in the first place — it could only corrupt them.
 *
 * @return the translated message when it is a framework default, otherwise the receiver unchanged.
 */
internal fun String.translatedIfFrameworkDefault(): String =
    if (this in frameworkDefaultMessages) gettext(this) else this
