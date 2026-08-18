package com.fonrouge.conformance

import com.fonrouge.base.api.ApiFilter
import com.fonrouge.base.api.ApiList
import com.fonrouge.base.api.CrudTask
import com.fonrouge.base.api.IApiItem
import com.fonrouge.base.common.ICommonContainer
import com.fonrouge.base.state.SimpleState
import com.fonrouge.base.state.State
import com.fonrouge.fullStack.repository.IRolePermissionProvider
import com.fonrouge.fullStack.repository.PermissionRegistry
import io.ktor.server.application.ApplicationCall
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assume
import kotlin.test.BeforeTest
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
 * - **Universal** — asserted on every engine: the generic-CRUD gate (I5), validation side-effect
 *   freedom on create + update (I2), and delete block/allow for parents-with-children (I3).
 * - **Profile-divergent** — behavior branches on the [EngineProfile]: per-action permission parity
 *   (I6, `enforcesPermissions`) and the change-log positive control (I2, `writesChangeLog`).
 * - **Profile-gated** — skipped on engines that have not converged yet, so future engines can join
 *   without committed failures: canonical hook order (I1, `enforcesCanonicalHookOrder`),
 *   delete-exactly-once (I3, `enforcesDeleteExactlyOnce`), and init lifecycle (I4,
 *   `enforcesInitLifecycle`) are live for memory + SQL and remain profile-gated for future engines.
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

    // ── D6: fail-closed default when enforcement is unconfigured (permissionEnforcement) ──

    /**
     * D6: with **no** permission provider registered, an *enforcing* engine (`permissionEnforcement ==
     * Enforce`) must **fail closed** on a remote (`call != null`) write — closing the former fail-open
     * hole (R7) where an unconfigured registry silently allowed. An explicitly `Off` engine still allows.
     * The `enforcesPermissions` profile flag marks the engines whose enforcement the harness can drive
     * (SQL and, since RBAC P3.2b, Mongo): those are `Enforce`; the others (in-memory) declare `Off`.
     */
    @Test
    fun unconfiguredDefaultFailsClosedForEnforcingEngines() = runTest {
        val name = fixture.profile.name
        val repo = fixture.freshRepo()
        val remoteCall = mockk<ApplicationCall>(relaxed = true)
        val previousProvider = PermissionRegistry.rolePermissionProvider
        try {
            PermissionRegistry.rolePermissionProvider = null // unconfigured

            val remote = repo.apiItemProcess(remoteCall, actionCreate(CItem("d6", "X", 1.0)))
            if (fixture.profile.enforcesPermissions) {
                assertTrue(remote.hasError, "$name: an enforcing engine with no provider must fail closed on a remote write")
                assertNull(repo.findById("d6", ApiFilter()), "$name: a fail-closed write must not persist")
            } else {
                assertFalse(remote.hasError, "$name: an explicitly Off engine allows when unconfigured")
            }
        } finally {
            PermissionRegistry.rolePermissionProvider = previousProvider
        }
    }

    // ── I4: init lifecycle (profile-gated per engine) ──

    @Test
    fun initLifecycleRunsExactlyOnceBeforeGenericUse() = runTest {
        Assume.assumeTrue(
            "I4 init lifecycle not enforced for ${fixture.profile.name}",
            fixture.profile.enforcesInitLifecycle,
        )
        val name = fixture.profile.name
        val repo = fixture.initCountingRepo()
        val probe = repo as InitProbe

        assertEquals(0, probe.openCalls, "$name: construction must not invoke onAfterOpen eagerly")
        assertFalse(repo.apiItemProcess(null, actionCreate(CItem("o1", "Opened", 1.0))).hasError)
        assertEquals(1, probe.openCalls, "$name: first generic item use must invoke onAfterOpen")
        assertFalse(repo.apiListProcess(null, ApiList(apiFilter = ApiFilter())).hasError)
        assertEquals(1, probe.openCalls, "$name: subsequent generic list use must not invoke onAfterOpen again")
    }

    @Test
    fun initFailureSurfacesBlocksWriteAndRetries() = runTest {
        Assume.assumeTrue(
            "I4 init lifecycle not enforced for ${fixture.profile.name}",
            fixture.profile.enforcesInitLifecycle,
        )
        val name = fixture.profile.name
        val repo = fixture.failingInitRepo()
        val probe = repo as InitProbe

        val first = repo.apiItemProcess(null, actionCreate(CItem("of1", "Blocked", 1.0)))
        assertTrue(first.hasError, "$name: init failure must surface on the generic write")
        assertNull(repo.findById("of1", ApiFilter()), "$name: a write blocked by init failure must not persist")
        assertEquals(1, probe.openCalls, "$name: first failed open attempt must be recorded")

        val second = repo.apiItemProcess(null, actionCreate(CItem("of2", "Blocked", 2.0)))
        assertTrue(second.hasError, "$name: init failure must surface again on retry")
        assertNull(repo.findById("of2", ApiFilter()), "$name: retry failure must still block persistence")
        assertEquals(2, probe.openCalls, "$name: failed open must retry on the next generic call")
    }

    // ── I2: validation gates the write, side-effect-free (universal) ──

    @Test
    fun createValidationFailureIsSideEffectFree() = runTest {
        val name = fixture.profile.name
        val repo = fixture.validatingRepo()
        val probe = repo as ValidationProbe

        val result = repo.insertOne(CItem("v1", VALIDATION_REJECT_NAME, 1.0), ApiFilter())

        assertTrue(result.hasError, "$name: a create validation failure must surface an error")
        assertNull(repo.findById("v1", ApiFilter()), "$name: nothing must persist")
        assertTrue(probe.afterHookCalls.isEmpty(), "$name: no after-hooks (got ${probe.afterHookCalls})")
        assertEquals(0, probe.changeLogProbe.calls, "$name: no change-log entry on validation failure")
    }

    @Test
    fun updateValidationFailureIsSideEffectFree() = runTest {
        val name = fixture.profile.name
        val repo = fixture.validatingRepo()
        val probe = repo as ValidationProbe

        // Seed a valid row via the normal write path (this DOES fire after-hooks + change-log).
        assertFalse(repo.insertOne(CItem("u1", "Valid", 1.0), ApiFilter()).hasError, "$name: seed must succeed")
        if (fixture.profile.writesChangeLog) {
            // Positive control: proves the probe is actually wired and fires on a real write.
            assertEquals(1, probe.changeLogProbe.calls, "$name: a successful write must log a change")
        }
        probe.afterHookCalls.clear()
        probe.changeLogProbe.calls = 0

        // A validation-failing update must not change the row, fire after-hooks, or log.
        val result = repo.updateOne(CItem("u1", VALIDATION_REJECT_NAME, 2.0), ApiFilter())

        assertTrue(result.hasError, "$name: an update validation failure must surface an error")
        assertEquals("Valid", repo.findById("u1", ApiFilter())?.name, "$name: the row must be unchanged")
        assertTrue(probe.afterHookCalls.isEmpty(), "$name: no after-hooks (got ${probe.afterHookCalls})")
        assertEquals(0, probe.changeLogProbe.calls, "$name: no change-log entry on validation failure")
    }

    // ── No-op update classification ─────────────────────────────

    /**
     * An update that would change nothing is refused with [State.Warn], and that refusal carries
     * `noDataModified = true` to mark it as the benign kind.
     *
     * The flag is what separates "nothing needed saving" from "the server turned this down". A
     * client that cannot tell them apart either reports a refused write as a success — `msgOk`
     * defaults to `MSG_OK`, so it literally announces "Operation successful" — or keeps the form
     * open on a harmless no-op. This exercises the real engines rather than a hand-built
     * [com.fonrouge.base.state.ItemState], so removing the flag from a repository fails here.
     */
    @Test
    fun noOpUpdateIsWarnFlaggedAsNoDataModified() = runTest {
        Assume.assumeTrue(
            "${fixture.profile.name} does not short-circuit no-op updates",
            fixture.profile.shortCircuitsNoOpUpdates,
        )
        val name = fixture.profile.name
        val repo = fixture.freshRepo()
        val seeded = CItem("n1", "Unchanged", 1.0)
        assertFalse(repo.insertOne(seeded, ApiFilter()).hasError, "$name: seed must succeed")

        // Re-submit byte-for-byte identical data: the engine must refuse to write.
        val result = repo.updateOne(seeded, ApiFilter())

        assertEquals(State.Warn, result.state, "$name: a no-op update must be refused with Warn")
        assertEquals(true, result.noDataModified, "$name: a no-op refusal must set noDataModified")
        assertFalse(result.hasError, "$name: a no-op is not an error")

        // The two properties a client actually branches on, pinned together: not a success, but
        // also not something the user has to come back and fix.
        assertTrue(result.isRejected, "$name: a refused write must not read as succeeded")
        assertTrue(result.isWriteComplete, "$name: a no-op still leaves the store as asked")

        // A real change on the same row must still go through, so the short-circuit is not just
        // rejecting every update.
        val changed = repo.updateOne(seeded.copy(name = "Changed"), ApiFilter())
        assertFalse(changed.hasError, "$name: a genuine update must still succeed")
        assertEquals("Changed", repo.findById("n1", ApiFilter())?.name, "$name: the row must change")
    }

    // ── I1: canonical hook order (target — assume-gated) ────────

    @Test
    fun canonicalHookOrderOnCreate() = runTest {
        Assume.assumeTrue(
            "I1 canonical hook order not enforced for ${fixture.profile.name}",
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
            "I1 canonical hook order not enforced for ${fixture.profile.name}",
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

    // ── I3: dependency safety — blocks parents-with-children, exactly once ──

    @Test
    fun deleteBlockedWhenChildrenExist() = runTest {
        val name = fixture.profile.name
        val dep = fixture.dependencyFixture()
        assertFalse(dep.parent.insertOne(CItem("d1", "Parent", 1.0), ApiFilter()).hasError, "$name: seed parent")
        assertFalse(dep.child.insertOne(CChild("c1", "d1"), ApiFilter()).hasError, "$name: seed child")

        val result = dep.parent.deleteOne("d1", ApiFilter())

        assertTrue(result.hasError, "$name: deleting a parent with children must be refused")
        assertNotNull(dep.parent.findById("d1", ApiFilter()), "$name: the parent must not be removed")
    }

    @Test
    fun deleteAllowedWhenNoChildren() = runTest {
        val name = fixture.profile.name
        val dep = fixture.dependencyFixture()
        assertFalse(dep.parent.insertOne(CItem("d2", "Parent", 1.0), ApiFilter()).hasError, "$name: seed parent")

        val result = dep.parent.deleteOne("d2", ApiFilter())

        assertFalse(result.hasError, "$name: deleting a parent with no children must succeed")
        assertNull(dep.parent.findById("d2", ApiFilter()), "$name: the parent must be removed")
    }

    @Test
    fun dependencyCheckRunsExactlyOncePerDelete() = runTest {
        Assume.assumeTrue(
            "I3 single-owner delete not enforced for ${fixture.profile.name}",
            fixture.profile.enforcesDeleteExactlyOnce,
        )
        val dep = fixture.dependencyFixture()
        assertFalse(dep.parent.insertOne(CItem("d3", "Parent", 1.0), ApiFilter()).hasError)

        dep.parent.deleteOne("d3", ApiFilter())

        assertEquals(
            1,
            (dep.parent as DependencyProbe).findChildrenNotCalls,
            "${fixture.profile.name}: findChildrenNot must run exactly once per delete",
        )
    }
}

/** Runs the conformance suite against the in-memory engine. */
class MemoryConformanceTest : RepositoryConformanceTests() {
    override val fixture: ConformanceFixture = MemoryConformanceFixture()
}

/** Runs the conformance suite against the SQL engine (H2-backed). */
class SqlConformanceTest : RepositoryConformanceTests() {
    override val fixture: ConformanceFixture = SqlConformanceFixture()
}

/**
 * Runs the conformance suite against the Mongo engine (Testcontainers, decision C). The shared
 * [MongoTestSupport.requireDocker] gate `Assume`-skips every test when Docker is absent locally,
 * fails loudly in CI, and pre-starts the container outside `runTest`'s whole-test timeout. Pins the
 * P2.1/P2.2/P2.3 convergence invariants against a real mongod.
 */
class MongoConformanceTest : RepositoryConformanceTests() {
    @BeforeTest
    fun requireDocker() {
        MongoTestSupport.requireDocker()
    }

    override val fixture: ConformanceFixture = MongoConformanceFixture()
}
