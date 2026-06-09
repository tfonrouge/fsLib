package com.fonrouge.fullStack.memoryDb

import com.fonrouge.base.api.ApiFilter
import com.fonrouge.base.api.ApiItem
import com.fonrouge.base.api.ApiList
import com.fonrouge.base.api.IApiItem
import com.fonrouge.base.state.ItemState
import com.fonrouge.base.state.SimpleState
import com.fonrouge.base.state.State
import com.fonrouge.fullStack.repository.IRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [InMemoryRepository] verifying CRUD operations, pagination, and lifecycle behavior.
 */
class InMemoryRepositoryTest {

    private fun createRepo() = InMemoryRepository<TestItem, String, ApiFilter, String>(
        commonContainer = CommonTestItem,
    )

    private fun seededRepo() = createRepo().seed(
        listOf(
            TestItem(_id = "1", name = "Alpha", price = 10.0, category = "A"),
            TestItem(_id = "2", name = "Beta", price = 20.0, category = "B"),
            TestItem(_id = "3", name = "Gamma", price = 30.0, category = "A"),
        )
    )

    // ── CRUD ────────────────────────────────────────────────────

    @Test
    fun insertAndFindById() = runTest {
        val repo = createRepo()
        val item = TestItem(_id = "x1", name = "Widget", price = 9.99)
        val filter = ApiFilter()

        val result = repo.insertOne(item, filter)
        assertFalse(result.hasError)
        assertNotNull(result.item)

        val found = repo.findById("x1", filter)
        assertNotNull(found)
        assertEquals("Widget", found.name)
    }

    @Test
    fun findByIdReturnsNullForMissing() = runTest {
        val repo = createRepo()
        val found = repo.findById("nonexistent", ApiFilter())
        assertNull(found)
    }

    @Test
    fun updateModifiesItem() = runTest {
        val repo = seededRepo()
        val filter = ApiFilter()

        val updated = TestItem(_id = "1", name = "Alpha Updated", price = 15.0)
        val result = repo.updateOne(updated, filter)

        assertFalse(result.hasError)
        val found = repo.findById("1", filter)
        assertEquals("Alpha Updated", found?.name)
        assertEquals(15.0, found?.price)
    }

    @Test
    fun deleteRemovesItem() = runTest {
        val repo = seededRepo()
        val filter = ApiFilter()

        val result = repo.deleteOne("2", filter)
        assertFalse(result.hasError)

        val found = repo.findById("2", filter)
        assertNull(found)
    }

    @Test
    fun deleteNonexistentReturnsError() = runTest {
        val repo = createRepo()
        val result = repo.deleteOne("nonexistent", ApiFilter())
        assertTrue(result.hasError)
    }

    // ── Seed ────────────────────────────────────────────────────

    @Test
    fun seedPopulatesStore() = runTest {
        val repo = seededRepo()
        val filter = ApiFilter()

        val list = repo.findList(filter)
        assertEquals(3, list.size)
    }

    // ── Pagination ──────────────────────────────────────────────

    @Test
    fun apiListProcessPaginates() = runTest {
        val repo = createRepo()
        for (i in 1..25) {
            repo.insertOne(TestItem(_id = "p$i", name = "Product $i", price = i.toDouble()), ApiFilter())
        }

        val page1 = repo.apiListProcess(null, ApiList(tabPage = 1, tabSize = 10, apiFilter = ApiFilter()))
        assertEquals(10, page1.data.size)
        assertEquals(3, page1.last_page)
        assertEquals(25, page1.last_row)

        val page3 = repo.apiListProcess(null, ApiList(tabPage = 3, tabSize = 10, apiFilter = ApiFilter()))
        assertEquals(5, page3.data.size)
    }

    @Test
    fun apiListProcessEmptyRepo() = runTest {
        val repo = createRepo()
        val result = repo.apiListProcess(null, ApiList(tabPage = 1, tabSize = 10, apiFilter = ApiFilter()))
        assertEquals(0, result.data.size)
    }

    // ── FindList / FindOne ──────────────────────────────────────

    @Test
    fun findListReturnsAllItems() = runTest {
        val repo = seededRepo()
        val list = repo.findList(ApiFilter())
        assertEquals(3, list.size)
    }

    @Test
    fun findOneReturnsFirstMatch() = runTest {
        val repo = seededRepo()
        val item = repo.findOne(ApiFilter())
        assertNotNull(item)
    }

    @Test
    fun findOneReturnsNullOnEmpty() = runTest {
        val repo = createRepo()
        val item = repo.findOne(ApiFilter())
        assertNull(item)
    }

    // ── Dependency safety (F2 / CONTRACT.md I3) ─────────────────

    /** A child repository keyed by ChildItem._id, referencing parents via [ChildItem.parentId]. */
    private fun childRepo() = InMemoryRepository<ChildItem, String, ApiFilter, String>(CommonChildItem)

    /**
     * A parent repository that declares [child] as a dependent collection (children reference the
     * parent through [ChildItem.parentId]).
     */
    private fun parentRepoDependentOn(
        child: InMemoryRepository<ChildItem, String, ApiFilter, String>,
    ) = object : InMemoryRepository<ParentItem, String, ApiFilter, String>(CommonParentItem) {
        override val dependencies: (() -> List<IRepository.Dependency<*, String>>) = {
            listOf(IRepository.Dependency(CommonChildItem, ChildItem::parentId) { child })
        }
    }

    @Test
    fun deleteBlockedWhenChildrenExist() = runTest {
        val children = childRepo().seed(listOf(ChildItem(_id = "c1", parentId = "p1")))
        val parents = parentRepoDependentOn(children).seed(listOf(ParentItem(_id = "p1", name = "HasChild")))

        val result = parents.deleteOne("p1", ApiFilter())

        assertTrue(result.hasError, "deleting a parent that still has children must be refused")
        assertNotNull(parents.findById("p1", ApiFilter()), "the parent must not have been removed")
    }

    @Test
    fun deleteAllowedWhenNoChildren() = runTest {
        val children = childRepo().seed(listOf(ChildItem(_id = "c1", parentId = "p1")))
        val parents = parentRepoDependentOn(children).seed(
            listOf(ParentItem(_id = "p1", name = "HasChild"), ParentItem(_id = "p2", name = "NoChild"))
        )

        val result = parents.deleteOne("p2", ApiFilter())

        assertFalse(result.hasError, "deleting a parent with no children must succeed")
        assertNull(parents.findById("p2", ApiFilter()))
    }

    // ── Generic-CRUD gate (CONTRACT.md I5) ──────────────────────

    /** Repository whose generic-CRUD gate is closed (rejects every generic write via [denyApiCrud]). */
    private fun gateClosedRepo() = object : InMemoryRepository<TestItem, String, ApiFilter, String>(CommonTestItem) {
        override suspend fun allowApiCrud(apiItem: ApiItem.Action<TestItem, String, ApiFilter>): SimpleState =
            denyApiCrud()
    }

    @Test
    fun gateClosedBlocksGenericWritesButNotReadsOrService() = runTest {
        val repo = gateClosedRepo()
        repo.seed(listOf(TestItem(_id = "g1", name = "Seed")))
        val filterJson = Json.encodeToString(CommonTestItem.apiFilterSerializer, ApiFilter())

        // Generic write (apiItemProcess Action) is rejected by the closed gate, and nothing persists.
        val createReq = IApiItem.Action.Create<TestItem, String, ApiFilter>(
            serializedItem = Json.encodeToString(CommonTestItem.itemSerializer, TestItem(_id = "g2", name = "Blocked")),
            serializedApiFilter = filterJson,
        )
        val writeRes = repo.apiItemProcess(null, createReq)
        assertTrue(writeRes.hasError, "a closed gate must reject generic writes")
        assertNull(repo.findById("g2", ApiFilter()), "a rejected generic write must not persist")

        // Generic read (apiItemProcess Query.Read) is NOT gated.
        val readReq = IApiItem.Query.Read<TestItem, String, ApiFilter>(
            serializedId = Json.encodeToString(CommonTestItem.idSerializer, "g1"),
            serializedApiFilter = filterJson,
        )
        assertFalse(repo.apiItemProcess(null, readReq).hasError, "reads must not be gated")

        // Generic update is gated too, and a rejected update does not apply.
        val updateReq = IApiItem.Action.Update<TestItem, String, ApiFilter>(
            serializedItem = Json.encodeToString(CommonTestItem.itemSerializer, TestItem(_id = "g1", name = "ChangedViaGeneric")),
            serializedApiFilter = filterJson,
        )
        assertTrue(repo.apiItemProcess(null, updateReq).hasError, "a closed gate must reject generic updates")
        assertEquals("Seed", repo.findById("g1", ApiFilter())?.name, "a rejected generic update must not apply")

        // Generic delete is gated too, and a rejected delete does not remove the item.
        val deleteReq = IApiItem.Action.Delete<TestItem, String, ApiFilter>(
            serializedItem = Json.encodeToString(CommonTestItem.itemSerializer, TestItem(_id = "g1", name = "Seed")),
            serializedApiFilter = filterJson,
        )
        assertTrue(repo.apiItemProcess(null, deleteReq).hasError, "a closed gate must reject generic deletes")
        assertNotNull(repo.findById("g1", ApiFilter()), "a rejected generic delete must not remove the item")

        // Service tier (low-level insertOne) bypasses the gate.
        assertFalse(
            repo.insertOne(TestItem(_id = "g3", name = "Service"), ApiFilter()).hasError,
            "service-tier writes must bypass the gate",
        )
        assertNotNull(repo.findById("g3", ApiFilter()))
    }

    // ── Validation side-effect freedom (CONTRACT.md I2) ─────────

    /** Repository that rejects every item in [onValidate] and records whether after-hooks fired. */
    private class RejectingRepo : InMemoryRepository<TestItem, String, ApiFilter, String>(CommonTestItem) {
        var afterCreateFired = false
        var afterUpsertFired = false
        override suspend fun onValidate(
            apiItem: ApiItem.Action<TestItem, String, ApiFilter>,
            item: TestItem,
        ): SimpleState = SimpleState(state = State.Error, msgError = "invalid")

        override suspend fun onAfterCreateAction(
            apiItem: ApiItem.Action.Create<TestItem, String, ApiFilter>,
            result: Boolean,
        ) {
            afterCreateFired = true
        }

        override suspend fun onAfterUpsertAction(
            apiItem: ApiItem.Action<TestItem, String, ApiFilter>,
            orig: TestItem?,
            result: Boolean,
        ) {
            afterUpsertFired = true
        }
    }

    @Test
    fun validationFailureFiresNoAfterHooksAndNoWrite() = runTest {
        val repo = RejectingRepo()
        val res = repo.insertOne(TestItem(_id = "v1", name = "X"), ApiFilter())
        assertTrue(res.hasError, "a validation failure must surface as an error")
        assertNull(repo.findById("v1", ApiFilter()), "a validation failure must not persist anything")
        assertFalse(repo.afterCreateFired, "a validation failure must not fire onAfterCreateAction")
        assertFalse(repo.afterUpsertFired, "a validation failure must not fire onAfterUpsertAction")
    }

    // ── Canonical hook order (CONTRACT.md I1) ───────────────────

    /**
     * Records the order in which lifecycle hooks fire. The in-memory engine already satisfies the
     * canonical order (shared `Upsert` hook outermost, symmetric create/update); this is the
     * executable spec and the red→green tripwire for the Mongo/SQL convergence in P2.2.
     */
    private class RecordingRepo : InMemoryRepository<TestItem, String, ApiFilter, String>(CommonTestItem) {
        val calls = mutableListOf<String>()
        override suspend fun onQueryUpsert(apiItem: ApiItem.Query<TestItem, String, ApiFilter>, orig: TestItem?): SimpleState {
            calls += "onQueryUpsert"; return super.onQueryUpsert(apiItem, orig)
        }

        override suspend fun onQueryCreate(apiItem: ApiItem.Query.Create<TestItem, String, ApiFilter>): SimpleState {
            calls += "onQueryCreate"; return super.onQueryCreate(apiItem)
        }

        override suspend fun onQueryUpdate(apiItem: ApiItem.Query.Update<TestItem, String, ApiFilter>, orig: TestItem): SimpleState {
            calls += "onQueryUpdate"; return super.onQueryUpdate(apiItem, orig)
        }

        override suspend fun onBeforeUpsertAction(apiItem: ApiItem.Action<TestItem, String, ApiFilter>, orig: TestItem?): ItemState<TestItem> {
            calls += "onBeforeUpsertAction"; return super.onBeforeUpsertAction(apiItem, orig)
        }

        override suspend fun onBeforeCreateAction(apiItem: ApiItem.Action.Create<TestItem, String, ApiFilter>): ItemState<TestItem> {
            calls += "onBeforeCreateAction"; return super.onBeforeCreateAction(apiItem)
        }

        override suspend fun onBeforeUpdateAction(apiItem: ApiItem.Action.Update<TestItem, String, ApiFilter>, orig: TestItem): ItemState<TestItem> {
            calls += "onBeforeUpdateAction"; return super.onBeforeUpdateAction(apiItem, orig)
        }

        override suspend fun onValidate(apiItem: ApiItem.Action<TestItem, String, ApiFilter>, item: TestItem): SimpleState {
            calls += "onValidate"; return super.onValidate(apiItem, item)
        }

        override suspend fun onAfterCreateAction(apiItem: ApiItem.Action.Create<TestItem, String, ApiFilter>, result: Boolean) {
            calls += "onAfterCreateAction"; super.onAfterCreateAction(apiItem, result)
        }

        override suspend fun onAfterUpdateAction(apiItem: ApiItem.Action.Update<TestItem, String, ApiFilter>, orig: TestItem, result: Boolean) {
            calls += "onAfterUpdateAction"; super.onAfterUpdateAction(apiItem, orig, result)
        }

        override suspend fun onAfterUpsertAction(apiItem: ApiItem.Action<TestItem, String, ApiFilter>, orig: TestItem?, result: Boolean) {
            calls += "onAfterUpsertAction"; super.onAfterUpsertAction(apiItem, orig, result)
        }
    }

    @Test
    fun createHookOrderIsUpsertOutermost() = runTest {
        val repo = RecordingRepo()
        repo.insertOne(TestItem(_id = "h1", name = "X"), ApiFilter())
        assertEquals(
            listOf(
                "onQueryUpsert", "onQueryCreate",
                "onBeforeUpsertAction", "onBeforeCreateAction",
                "onValidate",
                "onAfterCreateAction", "onAfterUpsertAction",
            ),
            repo.calls,
            "create: shared Upsert hook outermost — before upsert→specific, after specific→upsert",
        )
    }

    @Test
    fun updateHookOrderIsUpsertOutermost() = runTest {
        val repo = RecordingRepo()
        repo.seed(listOf(TestItem(_id = "h1", name = "X")))
        repo.calls.clear()
        repo.updateOne(TestItem(_id = "h1", name = "Y"), ApiFilter())
        assertEquals(
            listOf(
                "onQueryUpsert", "onQueryUpdate",
                "onBeforeUpsertAction", "onBeforeUpdateAction",
                "onValidate",
                "onAfterUpdateAction", "onAfterUpsertAction",
            ),
            repo.calls,
            "update: shared Upsert hook outermost — symmetric with create",
        )
    }

    // ── ReadOnly ────────────────────────────────────────────────

    @Test
    fun readOnlyBlocksInsert() = runTest {
        val repo = InMemoryRepository<TestItem, String, ApiFilter, String>(
            commonContainer = CommonTestItem,
            readOnly = true,
        )
        val result = repo.insertOne(TestItem(_id = "x", name = "Test"), ApiFilter())
        assertTrue(result.hasError)
    }
}
