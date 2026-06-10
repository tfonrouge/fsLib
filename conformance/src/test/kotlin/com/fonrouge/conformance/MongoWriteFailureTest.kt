package com.fonrouge.conformance

import com.fonrouge.base.api.ApiFilter
import com.fonrouge.base.api.ApiItem
import com.fonrouge.fullStack.mongoDb.MongoDbBuilder
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The D6-required **real-mongod write-failure test**: drives the F1 duplicate-key path of
 * `Coll.insertOne` against a Testcontainers Mongo — a real driver write failure that no
 * in-memory/H2 fixture can reproduce (LEDGER D6/D11, BRIEF F1, CONTRACT I2).
 *
 * On a real `MongoWriteException` (duplicate `_id`, code 11000) this pins:
 * - the failure surfaces as an error `ItemState` with the friendly duplicate-key message
 *   (never an unhandled exception),
 * - the after-hooks fire **exactly once** each with `result = false`, specific before shared (I1/I2),
 * - the previously stored document is untouched and the repository stays usable.
 *
 * The change-log clause of F1 (`if (result == true)`) is covered here only indirectly, via the same
 * `result = false` flag the after-hooks observe — `Coll.changeLogCollFun` is `IChangeLogColl`-typed,
 * so the engine-agnostic [ChangeLogProbe] cannot be wired (D11). The direct change-log pins live on
 * SQL and cover the validation-failure path (negative: `createValidationFailureIsSideEffectFree`;
 * positive control: the seeded write in `updateValidationFailureIsSideEffectFree`); no engine
 * directly probes the change-log gate after a failed driver write.
 *
 * Skipped where Docker is absent locally; fails loudly in CI (see [MongoTestSupport.requireDocker]).
 */
class MongoWriteFailureTest {

    /** [CItemMongoRepository] recording each after-hook invocation together with its `result` flag. */
    private class AfterHookRecordingMongoRepository(mongoDbBuilder: MongoDbBuilder) :
        CItemMongoRepository(mongoDbBuilder) {
        val afterHookCalls = mutableListOf<String>()

        override suspend fun onAfterCreateAction(apiItem: ApiItem.Action.Create<CItem, String, ApiFilter>, result: Boolean) {
            afterHookCalls += "onAfterCreateAction:$result"; super.onAfterCreateAction(apiItem, result)
        }

        override suspend fun onAfterUpsertAction(apiItem: ApiItem.Action<CItem, String, ApiFilter>, orig: CItem?, result: Boolean) {
            afterHookCalls += "onAfterUpsertAction:$result"; super.onAfterUpsertAction(apiItem, orig, result)
        }
    }

    @BeforeTest
    fun requireDocker() {
        MongoTestSupport.requireDocker()
    }

    @Test
    fun duplicateKeyInsertFailureSurfacesErrorAndIsSideEffectFree() = runTest {
        val repo = AfterHookRecordingMongoRepository(MongoTestSupport.freshBuilder())

        val seeded = repo.insertOne(CItem(_id = "dup-1", name = "First", price = 1.0), ApiFilter())
        assertFalse(seeded.hasError, "seed insert failed: ${seeded.msgError}")
        assertEquals(
            listOf("onAfterCreateAction:true", "onAfterUpsertAction:true"),
            repo.afterHookCalls,
            "successful insert must fire each after-hook exactly once with result=true, specific before shared (I1)",
        )
        repo.afterHookCalls.clear()

        // Same _id → a real duplicate-key write failure raised by mongod on the _id_ index (F1 path).
        val duplicate = repo.insertOne(CItem(_id = "dup-1", name = "Second", price = 2.0), ApiFilter())
        assertTrue(duplicate.hasError, "duplicate-key insert must return an error state, not succeed")
        assertNotNull(duplicate.msgError, "duplicate-key failure must carry an error message")
        assertTrue(
            duplicate.msgError!!.contains("Duplicate key"),
            "duplicate-key failure must surface the friendly 11000 message, got: ${duplicate.msgError}",
        )
        assertEquals(
            listOf("onAfterCreateAction:false", "onAfterUpsertAction:false"),
            repo.afterHookCalls,
            "failed insert must fire each after-hook exactly once with result=false (I2), specific before shared (I1)",
        )

        val stored = repo.findById("dup-1", ApiFilter())
        assertNotNull(stored, "original document must survive the failed duplicate insert")
        assertEquals("First", stored.name, "failed insert must not overwrite the stored document")

        val next = repo.insertOne(CItem(_id = "dup-2", name = "Third", price = 3.0), ApiFilter())
        assertFalse(next.hasError, "repository must stay usable after a failed insert: ${next.msgError}")
    }
}
