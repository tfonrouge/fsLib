package com.fonrouge.conformance

import com.fonrouge.base.api.ApiFilter
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * Minimal smoke test proving `SqlRepository` runs a real CRUD round-trip against an in-memory H2
 * database (no Docker). The shared model/plumbing ([CItem], [CItemSqlRepository],
 * [createH2CItemDatabase]) lives in `ConformanceHarness.kt`.
 */
class SqlOnH2SmokeTest {

    @Test
    fun sqlRepositoryRunsCrudAgainstH2() = runTest {
        val repo = CItemSqlRepository(createH2CItemDatabase())

        val inserted = repo.insertOne(CItem(_id = "s1", name = "Widget", price = 9.99), ApiFilter())
        assertFalse(inserted.hasError, "insert failed: ${inserted.msgError}")

        val found = repo.findById("s1", ApiFilter())
        assertNotNull(found, "row not found after insert")
        assertEquals("Widget", found.name)
        assertEquals(9.99, found.price)
    }
}
