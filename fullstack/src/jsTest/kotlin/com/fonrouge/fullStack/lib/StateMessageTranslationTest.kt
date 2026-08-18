package com.fonrouge.fullStack.lib

import com.fonrouge.base.state.MSG_ERROR
import com.fonrouge.base.state.MSG_OK
import io.kvision.i18n.DefaultI18nManager
import io.kvision.i18n.I18n
import io.kvision.i18n.gettext as topLevelGettext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards the translation boundary for state messages.
 *
 * These tests run against a real [DefaultI18nManager] — i.e. against gettext.js — because the
 * behaviour being pinned only exists there. With KVision's default no-op manager every assertion
 * below passes trivially, which would make the suite look green while guarding nothing.
 */
class StateMessageTranslationTest {

    private var previousManager: io.kvision.i18n.I18nManager? = null
    private var previousLanguage: String = ""

    @BeforeTest
    fun installSpanishCatalog() {
        previousManager = I18n.manager
        previousLanguage = I18n.language
        // Mirrors a consumer's `messages-es.po` after po2json: the framework defaults are
        // translated, and nothing else is.
        val es = js("{}")
        es[""] = js("{}")
        es[""]["language"] = "es"
        es[""]["plural-forms"] = "nplurals=2; plural=n != 1;"
        es["Operation successful"] = "Operación exitosa"
        es["Operation Failed"] = "La operación falló"
        I18n.manager = DefaultI18nManager(mapOf("es" to es))
        I18n.language = "es"
    }

    @AfterTest
    fun restoreManager() {
        previousManager?.let { I18n.manager = it }
        I18n.language = previousLanguage
    }

    @Test
    fun frameworkDefaultsAreTranslated() {
        assertEquals("Operación exitosa", MSG_OK.translatedIfFrameworkDefault())
        assertEquals("La operación falló", MSG_ERROR.translatedIfFrameworkDefault())
    }

    /**
     * The regression this boundary exists for.
     *
     * gettext.js runs its `strfmt` substitution over the *untranslated* msgid as well, so `%`
     * followed by digits is consumed as a positional placeholder and — no arguments being supplied
     * — becomes `undefined`. Routing server-authored text through `gettext` therefore corrupted it.
     * Domain messages carry user data (`Coll.friendlyExceptionMessage` embeds the offending
     * document's own values), so this is reachable with ordinary records.
     *
     * The assertion is on fsLib's contract — the message survives — not on gettext.js still being
     * wrong. Pinning the third-party defect would turn a fix in that dependency into a CI failure
     * here. The first assertion keeps the test honest instead: it proves a real translating manager
     * is installed, so the second one cannot pass merely because translation is a no-op.
     */
    @Test
    fun serverAuthoredTextWithPercentPlaceholdersSurvivesIntact() {
        assertEquals("Operación exitosa", MSG_OK.translatedIfFrameworkDefault(), "manager must be live")

        val domainMessage = "Descuento %10 excede el limite"
        assertEquals(domainMessage, domainMessage.translatedIfFrameworkDefault())
    }

    /**
     * `io.kvision.i18n.gettext` and `io.kvision.i18n.I18n.gettext` were both in use across the
     * module. Pinning their equivalence is what makes standardising on one of them a no-op.
     */
    @Test
    fun bothGettextEntryPointsAgree() {
        assertEquals(I18n.gettext(MSG_OK), topLevelGettext(MSG_OK))
        assertEquals(I18n.gettext("untranslated text"), topLevelGettext("untranslated text"))
    }

    @Test
    fun untranslatedServerTextIsReturnedUnchanged() {
        val duplicateKey = """Duplicate key error on index: codigo_1, key: { codigo: "ABC-100" }"""
        assertEquals(duplicateKey, duplicateKey.translatedIfFrameworkDefault())
    }
}
