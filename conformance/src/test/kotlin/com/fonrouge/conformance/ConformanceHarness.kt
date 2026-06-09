package com.fonrouge.conformance

import com.fonrouge.base.api.ApiFilter
import com.fonrouge.base.api.ApiItem
import com.fonrouge.base.common.ICommonContainer
import com.fonrouge.base.model.BaseDoc
import com.fonrouge.base.sqlDb.SqlDatabase
import com.fonrouge.base.state.ItemState
import com.fonrouge.base.state.SimpleState
import com.fonrouge.fullStack.memoryDb.InMemoryRepository
import com.fonrouge.fullStack.repository.IRepository
import com.fonrouge.fullStack.repository.IUserRepository
import com.fonrouge.fullStack.repository.SqlRepository
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction

// ── Shared test entity ───────────────────────────────────────

/** Conformance test entity. */
@Serializable
data class CItem(
    override val _id: String = "",
    val name: String = "",
    val price: Double = 0.0,
) : BaseDoc<String>

/** Entity metadata for [CItem]. */
object CommonCItem : ICommonContainer<CItem, String, ApiFilter>(
    itemKClass = CItem::class,
    filterKClass = ApiFilter::class,
    labelItem = "CItem",
    labelList = "CItems",
)

// ── SQL / H2 plumbing ────────────────────────────────────────

/** Concrete [SqlDatabase] wrapping an Exposed [Database] (here, H2 in-memory). */
class H2SqlDatabase(database: Database) : SqlDatabase(database)

/** Creates a fresh, isolated in-memory H2 database with the `citem` table. */
fun createH2CItemDatabase(): SqlDatabase {
    // Unique DB name so tests sharing the JVM never collide; DB_CLOSE_DELAY=-1 keeps the in-memory
    // DB alive across Exposed's per-transaction connections.
    val database = Database.connect(
        url = "jdbc:h2:mem:conf_${System.nanoTime()};DB_CLOSE_DELAY=-1",
        driver = "org.h2.Driver",
    )
    transaction(database) {
        exec("""CREATE TABLE "citem" ("_id" VARCHAR(255) PRIMARY KEY, "name" VARCHAR(255), "price" DOUBLE)""")
    }
    return H2SqlDatabase(database)
}

/**
 * Plain [SqlRepository] for [CItem]. An explicit lowercase [tableName] is passed because the default
 * lowercases only the first char (`CItem` → `cItem`), and H2 quoted identifiers are case-sensitive.
 */
open class CItemSqlRepository(sqlDatabase: SqlDatabase) :
    SqlRepository<CItem, String, ApiFilter, String>(CommonCItem, sqlDatabase, tableName = "citem") {
    override val userCollFun: () -> IUserRepository<*, String>? = { null }
}

/** Plain [InMemoryRepository] for [CItem]. */
open class CItemMemoryRepository : InMemoryRepository<CItem, String, ApiFilter, String>(CommonCItem)

// ── Engine profile + fixtures ────────────────────────────────

/**
 * Declares which contract invariants a given engine currently enforces. Conformance tests assert
 * universal invariants on every engine, branch on profile-divergent ones, and `Assume`-skip target
 * invariants (reported "pending P2.x") so no failing tests are committed before convergence.
 */
data class EngineProfile(
    val name: String,
    /** I6: per-action CRUD permission enforced on the remote path. memory=false (exempt), sql=true (P1.9). */
    val enforcesPermissions: Boolean,
    /** I1: canonical hook order (shared Upsert outermost). memory=true, sql=false (pending P2.2). */
    val enforcesCanonicalHookOrder: Boolean,
)

/** Exposes the recorded lifecycle-hook call order from an instrumented repository. */
interface HookLog {
    val calls: MutableList<String>
}

/** Supplies engine-specific repositories + profile to the engine-agnostic conformance tests. */
interface ConformanceFixture {
    val profile: EngineProfile

    /** A fresh, empty repository for [CItem]. */
    fun freshRepo(): IRepository<CItem, String, ApiFilter, String>

    /** A repository whose generic-CRUD gate is closed (`allowApiCrud` denies). */
    fun gateClosedRepo(): IRepository<CItem, String, ApiFilter, String>

    /** A repository that also implements [HookLog], recording lifecycle-hook call order. */
    fun recordingRepo(): IRepository<CItem, String, ApiFilter, String>
}

// ── Hook-recording overrides (shared shape, per engine base) ──

private class GateClosedMemoryRepository : CItemMemoryRepository() {
    override suspend fun allowApiCrud(apiItem: ApiItem.Action<CItem, String, ApiFilter>): SimpleState = denyApiCrud()
}

private class RecordingMemoryRepository : CItemMemoryRepository(), HookLog {
    override val calls = mutableListOf<String>()
    override suspend fun onQueryUpsert(apiItem: ApiItem.Query<CItem, String, ApiFilter>, orig: CItem?): SimpleState {
        calls += "onQueryUpsert"; return super.onQueryUpsert(apiItem, orig)
    }

    override suspend fun onQueryCreate(apiItem: ApiItem.Query.Create<CItem, String, ApiFilter>): SimpleState {
        calls += "onQueryCreate"; return super.onQueryCreate(apiItem)
    }

    override suspend fun onQueryUpdate(apiItem: ApiItem.Query.Update<CItem, String, ApiFilter>, orig: CItem): SimpleState {
        calls += "onQueryUpdate"; return super.onQueryUpdate(apiItem, orig)
    }

    override suspend fun onBeforeUpsertAction(apiItem: ApiItem.Action<CItem, String, ApiFilter>, orig: CItem?): ItemState<CItem> {
        calls += "onBeforeUpsertAction"; return super.onBeforeUpsertAction(apiItem, orig)
    }

    override suspend fun onBeforeCreateAction(apiItem: ApiItem.Action.Create<CItem, String, ApiFilter>): ItemState<CItem> {
        calls += "onBeforeCreateAction"; return super.onBeforeCreateAction(apiItem)
    }

    override suspend fun onBeforeUpdateAction(apiItem: ApiItem.Action.Update<CItem, String, ApiFilter>, orig: CItem): ItemState<CItem> {
        calls += "onBeforeUpdateAction"; return super.onBeforeUpdateAction(apiItem, orig)
    }

    override suspend fun onValidate(apiItem: ApiItem.Action<CItem, String, ApiFilter>, item: CItem): SimpleState {
        calls += "onValidate"; return super.onValidate(apiItem, item)
    }

    override suspend fun onAfterCreateAction(apiItem: ApiItem.Action.Create<CItem, String, ApiFilter>, result: Boolean) {
        calls += "onAfterCreateAction"; super.onAfterCreateAction(apiItem, result)
    }

    override suspend fun onAfterUpdateAction(apiItem: ApiItem.Action.Update<CItem, String, ApiFilter>, orig: CItem, result: Boolean) {
        calls += "onAfterUpdateAction"; super.onAfterUpdateAction(apiItem, orig, result)
    }

    override suspend fun onAfterUpsertAction(apiItem: ApiItem.Action<CItem, String, ApiFilter>, orig: CItem?, result: Boolean) {
        calls += "onAfterUpsertAction"; super.onAfterUpsertAction(apiItem, orig, result)
    }
}

/** Memory engine fixture — permission-free (I6 exempt), canonical hook order already enforced (I1). */
class MemoryConformanceFixture : ConformanceFixture {
    override val profile = EngineProfile(name = "InMemory", enforcesPermissions = false, enforcesCanonicalHookOrder = true)
    override fun freshRepo(): IRepository<CItem, String, ApiFilter, String> = CItemMemoryRepository()
    override fun gateClosedRepo(): IRepository<CItem, String, ApiFilter, String> = GateClosedMemoryRepository()
    override fun recordingRepo(): IRepository<CItem, String, ApiFilter, String> = RecordingMemoryRepository()
}

private class GateClosedSqlRepository(sqlDatabase: SqlDatabase) : CItemSqlRepository(sqlDatabase) {
    override suspend fun allowApiCrud(apiItem: ApiItem.Action<CItem, String, ApiFilter>): SimpleState = denyApiCrud()
}

private class RecordingSqlRepository(sqlDatabase: SqlDatabase) : CItemSqlRepository(sqlDatabase), HookLog {
    override val calls = mutableListOf<String>()
    override suspend fun onQueryUpsert(apiItem: ApiItem.Query<CItem, String, ApiFilter>, orig: CItem?): SimpleState {
        calls += "onQueryUpsert"; return super.onQueryUpsert(apiItem, orig)
    }

    override suspend fun onQueryCreate(apiItem: ApiItem.Query.Create<CItem, String, ApiFilter>): SimpleState {
        calls += "onQueryCreate"; return super.onQueryCreate(apiItem)
    }

    override suspend fun onQueryUpdate(apiItem: ApiItem.Query.Update<CItem, String, ApiFilter>, orig: CItem): SimpleState {
        calls += "onQueryUpdate"; return super.onQueryUpdate(apiItem, orig)
    }

    override suspend fun onBeforeUpsertAction(apiItem: ApiItem.Action<CItem, String, ApiFilter>, orig: CItem?): ItemState<CItem> {
        calls += "onBeforeUpsertAction"; return super.onBeforeUpsertAction(apiItem, orig)
    }

    override suspend fun onBeforeCreateAction(apiItem: ApiItem.Action.Create<CItem, String, ApiFilter>): ItemState<CItem> {
        calls += "onBeforeCreateAction"; return super.onBeforeCreateAction(apiItem)
    }

    override suspend fun onBeforeUpdateAction(apiItem: ApiItem.Action.Update<CItem, String, ApiFilter>, orig: CItem): ItemState<CItem> {
        calls += "onBeforeUpdateAction"; return super.onBeforeUpdateAction(apiItem, orig)
    }

    override suspend fun onValidate(apiItem: ApiItem.Action<CItem, String, ApiFilter>, item: CItem): SimpleState {
        calls += "onValidate"; return super.onValidate(apiItem, item)
    }

    override suspend fun onAfterCreateAction(apiItem: ApiItem.Action.Create<CItem, String, ApiFilter>, result: Boolean) {
        calls += "onAfterCreateAction"; super.onAfterCreateAction(apiItem, result)
    }

    override suspend fun onAfterUpdateAction(apiItem: ApiItem.Action.Update<CItem, String, ApiFilter>, orig: CItem, result: Boolean) {
        calls += "onAfterUpdateAction"; super.onAfterUpdateAction(apiItem, orig, result)
    }

    override suspend fun onAfterUpsertAction(apiItem: ApiItem.Action<CItem, String, ApiFilter>, orig: CItem?, result: Boolean) {
        calls += "onAfterUpsertAction"; super.onAfterUpsertAction(apiItem, orig, result)
    }
}

/** SQL engine fixture (H2-backed) — enforces permissions (I6/P1.9); hook order pending P2.2 (I1). */
class SqlConformanceFixture : ConformanceFixture {
    override val profile = EngineProfile(name = "SQL", enforcesPermissions = true, enforcesCanonicalHookOrder = false)
    override fun freshRepo(): IRepository<CItem, String, ApiFilter, String> = CItemSqlRepository(createH2CItemDatabase())
    override fun gateClosedRepo(): IRepository<CItem, String, ApiFilter, String> = GateClosedSqlRepository(createH2CItemDatabase())
    override fun recordingRepo(): IRepository<CItem, String, ApiFilter, String> = RecordingSqlRepository(createH2CItemDatabase())
}
