package com.fonrouge.conformance

import com.fonrouge.base.api.ApiFilter
import com.fonrouge.base.common.ICommonContainer
import com.fonrouge.base.model.BaseDoc
import com.fonrouge.base.sqlDb.SqlDatabase
import com.fonrouge.fullStack.repository.IUserRepository
import com.fonrouge.fullStack.repository.SqlRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * Minimal smoke test proving `SqlRepository` runs a real CRUD round-trip against an in-memory H2
 * database (no Docker). This de-risks the SQL participation in the cross-engine conformance suite.
 */
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

/** Concrete [SqlDatabase] wrapping an Exposed [Database] (here, H2 in-memory). */
class H2SqlDatabase(database: Database) : SqlDatabase(database)

/**
 * Concrete [SqlRepository] for [CItem]. An explicit lowercase [tableName] is passed because the
 * default lowercases only the first char (`CItem` → `cItem`), and H2 quoted identifiers are
 * case-sensitive.
 */
class CItemSqlRepository(sqlDatabase: SqlDatabase) :
    SqlRepository<CItem, String, ApiFilter, String>(CommonCItem, sqlDatabase, tableName = "citem") {
    override val userCollFun: () -> IUserRepository<*, String>? = { null }
}

class SqlOnH2SmokeTest {

    @Test
    fun sqlRepositoryRunsCrudAgainstH2() = runTest {
        // Unique in-memory DB per test so tests sharing the JVM never collide on "table exists".
        // DB_CLOSE_DELAY=-1 keeps the DB alive across Exposed's per-transaction connections.
        val database = Database.connect(
            url = "jdbc:h2:mem:conf_${System.nanoTime()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
        transaction(database) {
            exec("""CREATE TABLE "citem" ("_id" VARCHAR(255) PRIMARY KEY, "name" VARCHAR(255), "price" DOUBLE)""")
        }
        val repo = CItemSqlRepository(H2SqlDatabase(database))

        val inserted = repo.insertOne(CItem(_id = "s1", name = "Widget", price = 9.99), ApiFilter())
        assertFalse(inserted.hasError, "insert failed: ${inserted.msgError}")

        val found = repo.findById("s1", ApiFilter())
        assertNotNull(found, "row not found after insert")
        assertEquals("Widget", found.name)
        assertEquals(9.99, found.price)
    }
}
