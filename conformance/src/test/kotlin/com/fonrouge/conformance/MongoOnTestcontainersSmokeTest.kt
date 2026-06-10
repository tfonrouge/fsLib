package com.fonrouge.conformance

import com.fonrouge.base.api.ApiFilter
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * Smoke test proving [com.fonrouge.fullStack.mongoDb.Coll] runs a real CRUD round-trip against a Mongo
 * started by Testcontainers (decision C). Skipped where Docker is absent locally; fails loudly in CI
 * (see [MongoTestSupport.requireDocker]).
 */
class MongoOnTestcontainersSmokeTest {

    @BeforeTest
    fun requireDocker() {
        MongoTestSupport.requireDocker()
    }

    @Test
    fun mongoRepositoryRunsCrudAgainstTestcontainers() = runTest {
        val repo = CItemMongoRepository(MongoTestSupport.freshBuilder())

        val inserted = repo.insertOne(CItem(_id = "s1", name = "Widget", price = 9.99), ApiFilter())
        assertFalse(inserted.hasError, "insert failed: ${inserted.msgError}")

        val found = repo.findById("s1", ApiFilter())
        assertNotNull(found, "row not found after insert")
        assertEquals("Widget", found.name)
        assertEquals(9.99, found.price)
    }
}
