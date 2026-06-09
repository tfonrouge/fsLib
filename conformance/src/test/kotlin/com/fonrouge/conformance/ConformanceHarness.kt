package com.fonrouge.conformance

import com.fonrouge.base.api.ApiFilter
import com.fonrouge.base.api.ApiItem
import com.fonrouge.base.common.ICommonContainer
import com.fonrouge.base.model.BaseDoc
import com.fonrouge.base.sqlDb.SqlDatabase
import com.fonrouge.base.state.ItemState
import com.fonrouge.base.state.SimpleState
import com.fonrouge.fullStack.memoryDb.InMemoryRepository
import com.fonrouge.fullStack.repository.IChangeLogRepository
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

/** Child entity referencing a [CItem] via [parentId]; the dependent entity in I3 tests. */
@Serializable
data class CChild(
    override val _id: String = "",
    val parentId: String = "",
) : BaseDoc<String>

/** Entity metadata for [CChild]. */
object CommonCChild : ICommonContainer<CChild, String, ApiFilter>(
    itemKClass = CChild::class,
    filterKClass = ApiFilter::class,
    labelItem = "CChild",
    labelList = "CChildren",
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

/** Creates a fresh in-memory H2 database with the `citem` (parent) and `cchild` tables for I3 tests. */
fun createH2ParentChildDatabase(): SqlDatabase {
    val database = Database.connect(
        url = "jdbc:h2:mem:conf_${System.nanoTime()};DB_CLOSE_DELAY=-1",
        driver = "org.h2.Driver",
    )
    transaction(database) {
        exec("""CREATE TABLE "citem" ("_id" VARCHAR(255) PRIMARY KEY, "name" VARCHAR(255), "price" DOUBLE)""")
        exec("""CREATE TABLE "cchild" ("_id" VARCHAR(255) PRIMARY KEY, "parentId" VARCHAR(255))""")
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

/** Plain [SqlRepository] for [CChild] (the dependent entity in I3 tests). */
open class CChildSqlRepository(sqlDatabase: SqlDatabase) :
    SqlRepository<CChild, String, ApiFilter, String>(CommonCChild, sqlDatabase, tableName = "cchild") {
    override val userCollFun: () -> IUserRepository<*, String>? = { null }
}

/** Plain [InMemoryRepository] for [CChild]. */
open class CChildMemoryRepository : InMemoryRepository<CChild, String, ApiFilter, String>(CommonCChild)

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
    /** I2: does the engine call `buildChangeLog` at all? memory=false (no changelog), sql=true. */
    val writesChangeLog: Boolean,
    /** I3: findChildrenNot runs exactly once per delete. memory=true (P1.3), sql=false (pending P2.1). */
    val enforcesDeleteExactlyOnce: Boolean,
)

/** Exposes the recorded lifecycle-hook call order from an instrumented repository. */
interface HookLog {
    val calls: MutableList<String>
}

/** Sentinel name that an instrumented [ValidationProbe] repository rejects in `onValidate`. Using a
 *  sentinel (rather than rejecting everything) lets a valid row be seeded via the normal write path
 *  so the *update* validation case can be exercised too. */
const val VALIDATION_REJECT_NAME = "INVALID"

/** Exposes the after-hook firing + change-log probe of a repository whose `onValidate` rejects items
 *  named [VALIDATION_REJECT_NAME]. */
interface ValidationProbe {
    val afterHookCalls: MutableList<String>
    val changeLogProbe: ChangeLogProbe
}

/** Fake [IChangeLogRepository] that counts `buildChangeLog` invocations. */
class ChangeLogProbe : IChangeLogRepository {
    var calls = 0
    override suspend fun <CC : ICommonContainer<T, ID, *>, T : BaseDoc<ID>, ID : Any> buildChangeLog(
        cc: CC,
        apiItem: ApiItem.Action<T, ID, *>,
        orig: T?,
    ): SimpleState {
        calls++
        return SimpleState(isOk = true)
    }
}

/** Exposes how many times `findChildrenNot` ran during a delete, for the I3 exactly-once check. */
interface DependencyProbe {
    val findChildrenNotCalls: Int
}

/** A parent repository (also a [DependencyProbe]) wired to depend on its [child] repository. */
class DependencyFixture(
    val parent: IRepository<CItem, String, ApiFilter, String>,
    val child: IRepository<CChild, String, ApiFilter, String>,
)

/** Supplies engine-specific repositories + profile to the engine-agnostic conformance tests. */
interface ConformanceFixture {
    val profile: EngineProfile

    /** A fresh, empty repository for [CItem]. */
    fun freshRepo(): IRepository<CItem, String, ApiFilter, String>

    /** A repository whose generic-CRUD gate is closed (`allowApiCrud` denies). */
    fun gateClosedRepo(): IRepository<CItem, String, ApiFilter, String>

    /** A repository that also implements [HookLog], recording lifecycle-hook call order. */
    fun recordingRepo(): IRepository<CItem, String, ApiFilter, String>

    /** A repository whose `onValidate` rejects the validation sentinel ([VALIDATION_REJECT_NAME]);
     *  also implements [ValidationProbe]. */
    fun validatingRepo(): IRepository<CItem, String, ApiFilter, String>

    /** A parent (counting `findChildrenNot`) + child repo wired via a dependency, for I3 tests. */
    fun dependencyFixture(): DependencyFixture
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

private class ValidatingMemoryRepository : CItemMemoryRepository(), ValidationProbe {
    override val afterHookCalls = mutableListOf<String>()
    override val changeLogProbe = ChangeLogProbe()
    override val changeLogCollFun: () -> IChangeLogRepository? = { changeLogProbe }

    override suspend fun onValidate(apiItem: ApiItem.Action<CItem, String, ApiFilter>, item: CItem): SimpleState =
        if (item.name == VALIDATION_REJECT_NAME) SimpleState(isOk = false, msgError = "invalid") else SimpleState(isOk = true)

    override suspend fun onAfterCreateAction(apiItem: ApiItem.Action.Create<CItem, String, ApiFilter>, result: Boolean) {
        afterHookCalls += "onAfterCreateAction"; super.onAfterCreateAction(apiItem, result)
    }

    override suspend fun onAfterUpdateAction(apiItem: ApiItem.Action.Update<CItem, String, ApiFilter>, orig: CItem, result: Boolean) {
        afterHookCalls += "onAfterUpdateAction"; super.onAfterUpdateAction(apiItem, orig, result)
    }

    override suspend fun onAfterUpsertAction(apiItem: ApiItem.Action<CItem, String, ApiFilter>, orig: CItem?, result: Boolean) {
        afterHookCalls += "onAfterUpsertAction"; super.onAfterUpsertAction(apiItem, orig, result)
    }
}

private class DependencyMemoryParent(
    private val child: IRepository<CChild, String, ApiFilter, String>,
) : CItemMemoryRepository(), DependencyProbe {
    override var findChildrenNotCalls = 0
    override val dependencies: (() -> List<IRepository.Dependency<*, String>>) = {
        listOf(IRepository.Dependency(CommonCChild, CChild::parentId) { child })
    }

    override suspend fun findChildrenNot(item: CItem): ItemState<CItem> {
        findChildrenNotCalls++
        return super.findChildrenNot(item)
    }
}

/** Memory engine fixture — permission-free (I6 exempt), canonical hook order already enforced (I1). */
class MemoryConformanceFixture : ConformanceFixture {
    override val profile = EngineProfile(name = "InMemory", enforcesPermissions = false, enforcesCanonicalHookOrder = true, writesChangeLog = false, enforcesDeleteExactlyOnce = true)
    override fun freshRepo(): IRepository<CItem, String, ApiFilter, String> = CItemMemoryRepository()
    override fun gateClosedRepo(): IRepository<CItem, String, ApiFilter, String> = GateClosedMemoryRepository()
    override fun recordingRepo(): IRepository<CItem, String, ApiFilter, String> = RecordingMemoryRepository()
    override fun validatingRepo(): IRepository<CItem, String, ApiFilter, String> = ValidatingMemoryRepository()
    override fun dependencyFixture(): DependencyFixture {
        val child = CChildMemoryRepository()
        return DependencyFixture(parent = DependencyMemoryParent(child), child = child)
    }
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

private class ValidatingSqlRepository(sqlDatabase: SqlDatabase) : CItemSqlRepository(sqlDatabase), ValidationProbe {
    override val afterHookCalls = mutableListOf<String>()
    override val changeLogProbe = ChangeLogProbe()
    override val changeLogCollFun: () -> IChangeLogRepository? = { changeLogProbe }

    override suspend fun onValidate(apiItem: ApiItem.Action<CItem, String, ApiFilter>, item: CItem): SimpleState =
        if (item.name == VALIDATION_REJECT_NAME) SimpleState(isOk = false, msgError = "invalid") else SimpleState(isOk = true)

    override suspend fun onAfterCreateAction(apiItem: ApiItem.Action.Create<CItem, String, ApiFilter>, result: Boolean) {
        afterHookCalls += "onAfterCreateAction"; super.onAfterCreateAction(apiItem, result)
    }

    override suspend fun onAfterUpdateAction(apiItem: ApiItem.Action.Update<CItem, String, ApiFilter>, orig: CItem, result: Boolean) {
        afterHookCalls += "onAfterUpdateAction"; super.onAfterUpdateAction(apiItem, orig, result)
    }

    override suspend fun onAfterUpsertAction(apiItem: ApiItem.Action<CItem, String, ApiFilter>, orig: CItem?, result: Boolean) {
        afterHookCalls += "onAfterUpsertAction"; super.onAfterUpsertAction(apiItem, orig, result)
    }
}

private class DependencySqlParent(
    sqlDatabase: SqlDatabase,
    private val child: IRepository<CChild, String, ApiFilter, String>,
) : CItemSqlRepository(sqlDatabase), DependencyProbe {
    override var findChildrenNotCalls = 0
    override val dependencies: (() -> List<IRepository.Dependency<*, String>>) = {
        listOf(IRepository.Dependency(CommonCChild, CChild::parentId) { child })
    }

    override suspend fun findChildrenNot(item: CItem): ItemState<CItem> {
        findChildrenNotCalls++
        return super.findChildrenNot(item)
    }
}

/** SQL engine fixture (H2-backed) — enforces permissions (I6/P1.9); hook order pending P2.2 (I1). */
class SqlConformanceFixture : ConformanceFixture {
    override val profile = EngineProfile(name = "SQL", enforcesPermissions = true, enforcesCanonicalHookOrder = false, writesChangeLog = true, enforcesDeleteExactlyOnce = false)
    override fun freshRepo(): IRepository<CItem, String, ApiFilter, String> = CItemSqlRepository(createH2CItemDatabase())
    override fun gateClosedRepo(): IRepository<CItem, String, ApiFilter, String> = GateClosedSqlRepository(createH2CItemDatabase())
    override fun recordingRepo(): IRepository<CItem, String, ApiFilter, String> = RecordingSqlRepository(createH2CItemDatabase())
    override fun validatingRepo(): IRepository<CItem, String, ApiFilter, String> = ValidatingSqlRepository(createH2CItemDatabase())
    override fun dependencyFixture(): DependencyFixture {
        val db = createH2ParentChildDatabase()
        val child = CChildSqlRepository(db)
        return DependencyFixture(parent = DependencySqlParent(db, child), child = child)
    }
}
