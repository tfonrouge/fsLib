package com.fonrouge.conformance

import com.fonrouge.base.api.ApiFilter
import com.fonrouge.base.api.CrudTask
import com.fonrouge.base.api.IApiItem
import com.fonrouge.base.common.ICommonContainer
import com.fonrouge.base.state.SimpleState
import com.fonrouge.fullStack.repository.IRolePermissionProvider
import com.fonrouge.fullStack.repository.PermissionRegistry
import io.ktor.server.application.ApplicationCall
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Engine-agnostic conformance suite pinning the `IRepository` write/delete/lifecycle contract
 * (blueprints/repository-write-lifecycle/CONTRACT.md). Each concrete subclass supplies a
 * [ConformanceFixture] for one engine; JUnit runs every test below against it.
 *
 * Test categories:
 * - **Universal** (gate, I5) — asserted on every engine.
 * - **Profile-divergent** (permission parity, I6) — branches on [EngineProfile.enforcesPermissions].
 * - **Target / assume-gated** (canonical hook order, I1) — skipped (reported "pending P2.x") on
 *   engines that have not converged yet, so no failing tests are committed before Phase 2.
 */
abstract class RepositoryConformanceTests {

    abstract val fixture: ConformanceFixture

    private fun actionCreate(item: CItem) = IApiItem.Action.Create<CItem, String, ApiFilter>(
        serializedItem = Json.encodeToString(CommonCItem.itemSerializer, item),
        serializedApiFilter = Json.encodeToString(CommonCItem.apiFilterSerializer, ApiFilter()),
    )

    private fun queryRead(id: String) = IApiItem.Query.Read<CItem, String, ApiFilter>(
        serializedId = Json.encodeToString(CommonCItem.idSerializer, id),
        serializedApiFilter = Json.encodeToString(CommonCItem.apiFilterSerializer, ApiFilter()),
    )

    // ── I5: generic-CRUD gate (universal) ───────────────────────

    @Test
    fun gateClosedBlocksGenericWritesButNotReadsOrService() = runTest {
        val name = fixture.profile.name
        val repo = fixture.gateClosedRepo()

        // Service tier (low-level) intentionally bypasses the gate.
        assertFalse(
            repo.insertOne(CItem("g1", "Seed", 1.0), ApiFilter()).hasError,
            "$name: service-tier write must bypass the closed gate",
        )
        // Generic Action write goes through the gate → rejected, nothing persisted.
        assertTrue(
            repo.apiItemProcess(null, actionCreate(CItem("g2", "Blocked", 2.0))).hasError,
            "$name: a closed gate must reject generic writes",
        )
        assertNull(repo.findById("g2", ApiFilter()), "$name: a rejected generic write must not persist")
        // Generic reads are never gated.
        assertFalse(
            repo.apiItemProcess(null, queryRead("g1")).hasError,
            "$name: reads must not be gated",
        )
    }

    // ── I6: per-action CRUD permission parity (profile-divergent) ──

    @Test
    fun perActionPermissionParity() = runTest {
        val name = fixture.profile.name
        val repo = fixture.freshRepo()
        val denyAll = object : IRolePermissionProvider {
            override suspend fun getCrudPermission(
                commonContainer: ICommonContainer<*, *, *>,
                call: ApplicationCall,
                crudTask: CrudTask,
            ): SimpleState = SimpleState(isOk = false, msgError = "denied")
        }
        // The provider ignores the call, so a relaxed mock is never dereferenced.
        val remoteCall = mockk<ApplicationCall>(relaxed = true)
        val previousProvider = PermissionRegistry.rolePermissionProvider
        try {
            PermissionRegistry.rolePermissionProvider = denyAll

            // Remote generic write (call != null): enforcing engines block; exempt engines allow.
            val remote = repo.apiItemProcess(remoteCall, actionCreate(CItem("p1", "X", 1.0)))
            if (fixture.profile.enforcesPermissions) {
                assertTrue(remote.hasError, "$name: a deny provider must block a remote (call != null) write")
                assertNull(repo.findById("p1", ApiFilter()), "$name: a denied remote write must not persist")
            } else {
                assertFalse(remote.hasError, "$name: a permission-free engine must ignore the provider")
            }

            // Generic write through apiItemProcess with call == null: the per-action permission check
            // is skipped (trusted tier), so it succeeds and persists even while the deny provider is
            // active — this pins the documented "call == null passes permission" generic path.
            val trustedGeneric = repo.apiItemProcess(null, actionCreate(CItem("p2", "Y", 2.0)))
            assertFalse(trustedGeneric.hasError, "$name: a call==null generic write must skip the permission check")
            assertNotNull(repo.findById("p2", ApiFilter()), "$name: the trusted generic write must persist")

            // Service tier (low-level) bypasses apiItemProcess (gate + permission) entirely.
            assertFalse(
                repo.insertOne(CItem("p3", "Z", 3.0), ApiFilter()).hasError,
                "$name: service-tier write must pass regardless of provider",
            )
        } finally {
            PermissionRegistry.rolePermissionProvider = previousProvider
        }
    }

    // ── I1: canonical hook order (target — assume-gated) ────────

    @Test
    fun canonicalHookOrderOnCreate() = runTest {
        Assume.assumeTrue(
            "I1 hook-order convergence pending P2.2 for ${fixture.profile.name}",
            fixture.profile.enforcesCanonicalHookOrder,
        )
        val repo = fixture.recordingRepo()
        repo.insertOne(CItem("h1", "X", 1.0), ApiFilter())
        assertEquals(
            listOf(
                "onQueryUpsert", "onQueryCreate",
                "onBeforeUpsertAction", "onBeforeCreateAction",
                "onValidate",
                "onAfterCreateAction", "onAfterUpsertAction",
            ),
            (repo as HookLog).calls,
            "create: shared Upsert hook outermost",
        )
    }

    @Test
    fun canonicalHookOrderOnUpdate() = runTest {
        Assume.assumeTrue(
            "I1 hook-order convergence pending P2.2 for ${fixture.profile.name}",
            fixture.profile.enforcesCanonicalHookOrder,
        )
        val repo = fixture.recordingRepo()
        repo.insertOne(CItem("h1", "X", 1.0), ApiFilter())
        (repo as HookLog).calls.clear()
        repo.updateOne(CItem("h1", "Y", 2.0), ApiFilter())
        assertEquals(
            listOf(
                "onQueryUpsert", "onQueryUpdate",
                "onBeforeUpsertAction", "onBeforeUpdateAction",
                "onValidate",
                "onAfterUpdateAction", "onAfterUpsertAction",
            ),
            (repo as HookLog).calls,
            "update: shared Upsert hook outermost, symmetric with create",
        )
    }
}

/** Runs the conformance suite against the in-memory engine. */
class MemoryConformanceTest : RepositoryConformanceTests() {
    override val fixture: ConformanceFixture = MemoryConformanceFixture()
}

/** Runs the conformance suite against the SQL engine (H2-backed). Hook-order tests are skipped until P2.2. */
class SqlConformanceTest : RepositoryConformanceTests() {
    override val fixture: ConformanceFixture = SqlConformanceFixture()
}
