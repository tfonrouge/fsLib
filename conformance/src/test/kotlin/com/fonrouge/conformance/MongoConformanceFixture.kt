package com.fonrouge.conformance

import com.fonrouge.base.api.ApiFilter
import com.fonrouge.base.api.ApiItem
import com.fonrouge.base.api.PermissionEnforcement
import com.fonrouge.base.state.ItemState
import com.fonrouge.base.state.SimpleState
import com.fonrouge.fullStack.mongoDb.Coll
import com.fonrouge.fullStack.mongoDb.IUserColl
import com.fonrouge.fullStack.mongoDb.MongoDbBuilder
import com.fonrouge.fullStack.repository.IRepository
import org.junit.Assume
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.MongoDBContainer
import java.util.concurrent.atomic.AtomicLong

/**
 * Shared Testcontainers Mongo for the conformance suite (decision C). [requireDocker] is the single
 * test gate for the Mongo leg: locally it `Assume`-skips before the container is ever touched (no
 * Docker → green-or-skip, never red), while in CI (`CI=true`) it **fails loudly** instead — the Mongo
 * leg discharges D6/D11, so a silent skip there would be indistinguishable from success. When Docker
 * is present the gate also starts the shared container right there in the `@BeforeTest`, outside
 * kotlinx-coroutines-test's 60s whole-test timeout (a cold image pull + mongod boot can exceed it).
 */
object MongoTestSupport {
    /** Whether a Docker daemon is reachable; checked without starting a container, never throws. */
    val dockerAvailable: Boolean by lazy {
        runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
    }

    private val container: MongoDBContainer by lazy {
        MongoDBContainer("mongo:7.0").also { it.start() }
    }

    /**
     * The `@BeforeTest` gate for every Mongo conformance test. Skips (locally) or fails (CI) when
     * Docker is absent, then forces the shared container to start **here**, outside `runTest`'s
     * 60s whole-test timeout, so the first Mongo test never pays image pull + boot inside its budget.
     */
    fun requireDocker() {
        if (System.getenv("CI") == "true") {
            check(dockerAvailable) {
                "CI=true but no Docker daemon — the Mongo conformance leg must not silently skip (D11)"
            }
        } else {
            Assume.assumeTrue(
                "Docker not available — Mongo conformance is skipped locally and runs in CI (decision C)",
                dockerAvailable,
            )
        }
        check(container.isRunning) { "Testcontainers Mongo failed to start" }
    }

    private val dbSeq = AtomicLong(0)

    /** A [MongoDbBuilder] pointing at a fresh, uniquely-named database in the shared container. */
    fun freshBuilder(): MongoDbBuilder = MongoDbBuilder(
        serverUrl = container.host,
        serverPort = container.firstMappedPort,
        database = "conf_${dbSeq.incrementAndGet()}",
    )
}

/** Plain [Coll] for [CItem], backed by the Testcontainers Mongo. Non-RBAC test repo → declares [PermissionEnforcement.Off] (D6). */
open class CItemMongoRepository(mongoDbBuilder: MongoDbBuilder) :
    Coll<CItem, String, ApiFilter, String>(CommonCItem, mongoDbBuilder) {
    override val userCollFun: () -> IUserColl<*, String, *>? = { null }
    override val permissionEnforcement: PermissionEnforcement get() = PermissionEnforcement.Off
}

/** Plain [Coll] for [CChild] (the dependent entity in I3 tests). Non-RBAC test repo → [PermissionEnforcement.Off] (D6). */
open class CChildMongoRepository(mongoDbBuilder: MongoDbBuilder) :
    Coll<CChild, String, ApiFilter, String>(CommonCChild, mongoDbBuilder) {
    override val userCollFun: () -> IUserColl<*, String, *>? = { null }
    override val permissionEnforcement: PermissionEnforcement get() = PermissionEnforcement.Off
}

private class GateClosedMongoRepository(mongoDbBuilder: MongoDbBuilder) : CItemMongoRepository(mongoDbBuilder) {
    override suspend fun allowApiCrud(apiItem: ApiItem.Action<CItem, String, ApiFilter>): SimpleState = denyApiCrud()
}

private class RecordingMongoRepository(mongoDbBuilder: MongoDbBuilder) : CItemMongoRepository(mongoDbBuilder), HookLog {
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

// NOTE: unlike the SQL/memory validating repos, this does NOT wire `changeLogProbe` into
// `changeLogCollFun` — `Coll.changeLogCollFun` is typed `() -> IChangeLogColl<*,*,*>?` (Mongo-specific),
// incompatible with the `IChangeLogRepository`-based probe. So the Mongo profile sets
// `writesChangeLog = false`; the probe is present (to satisfy [ValidationProbe]) but stays at 0.
private class ValidatingMongoRepository(mongoDbBuilder: MongoDbBuilder) : CItemMongoRepository(mongoDbBuilder), ValidationProbe {
    override val afterHookCalls = mutableListOf<String>()
    override val changeLogProbe = ChangeLogProbe()

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

private class DependencyMongoParent(
    mongoDbBuilder: MongoDbBuilder,
    private val child: IRepository<CChild, String, ApiFilter, String>,
) : CItemMongoRepository(mongoDbBuilder), DependencyProbe {
    override var findChildrenNotCalls = 0
    override val dependencies: (() -> List<IRepository.Dependency<*, String>>) = {
        listOf(IRepository.Dependency(CommonCChild, CChild::parentId) { child })
    }

    override suspend fun findChildrenNot(item: CItem): ItemState<CItem> {
        findChildrenNotCalls++
        return super.findChildrenNot(item)
    }
}

private class InitCountingMongoRepository(mongoDbBuilder: MongoDbBuilder) : CItemMongoRepository(mongoDbBuilder), InitProbe {
    override var openCalls = 0
    override suspend fun onAfterOpen() {
        openCalls++
    }
}

private class FailingInitMongoRepository(mongoDbBuilder: MongoDbBuilder) : CItemMongoRepository(mongoDbBuilder), InitProbe {
    override var openCalls = 0
    override suspend fun onAfterOpen() {
        openCalls++
        error("init failed")
    }
}

/**
 * Mongo engine fixture (Testcontainers, decision C). Pins the convergence invariants P2.1/P2.2/P2.3
 * (hook order, delete-exactly-once, init lifecycle) against a real mongod.
 *
 * Two flags are `false` because the harness's engine-agnostic mechanisms don't reach Mongo's
 * engine-specific ones — NOT because Mongo lacks the behavior:
 * - `enforcesPermissions = false`: Mongo enforces CRUD permission via `Coll.roleInUserColl`, not the
 *   `PermissionRegistry` the conformance deny-provider uses (CONTRACT I6).
 * - `writesChangeLog = false`: `Coll.changeLogCollFun` is `IChangeLogColl`-typed, incompatible with
 *   the `IChangeLogRepository` `ChangeLogProbe` (I2 change-log clause is pinned on SQL).
 */
class MongoConformanceFixture : ConformanceFixture {
    override val profile = EngineProfile(
        name = "Mongo",
        enforcesPermissions = false,
        enforcesCanonicalHookOrder = true,
        writesChangeLog = false,
        enforcesDeleteExactlyOnce = true,
        enforcesInitLifecycle = true,
    )

    override fun freshRepo(): IRepository<CItem, String, ApiFilter, String> = CItemMongoRepository(MongoTestSupport.freshBuilder())
    override fun gateClosedRepo(): IRepository<CItem, String, ApiFilter, String> = GateClosedMongoRepository(MongoTestSupport.freshBuilder())
    override fun recordingRepo(): IRepository<CItem, String, ApiFilter, String> = RecordingMongoRepository(MongoTestSupport.freshBuilder())
    override fun validatingRepo(): IRepository<CItem, String, ApiFilter, String> = ValidatingMongoRepository(MongoTestSupport.freshBuilder())
    override fun dependencyFixture(): DependencyFixture {
        val builder = MongoTestSupport.freshBuilder()
        val child = CChildMongoRepository(builder)
        return DependencyFixture(parent = DependencyMongoParent(builder, child), child = child)
    }
    override fun initCountingRepo(): IRepository<CItem, String, ApiFilter, String> = InitCountingMongoRepository(MongoTestSupport.freshBuilder())
    override fun failingInitRepo(): IRepository<CItem, String, ApiFilter, String> = FailingInitMongoRepository(MongoTestSupport.freshBuilder())
}
