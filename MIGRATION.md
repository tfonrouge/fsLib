# Migration Guide

Version-scoped upgrade notes, listed **in the order you should apply them**. Each section lists what a
**consuming application** must change to move onto that release. `CHANGELOG.md` carries the full change
list and the rationale.

| Upgrade | Required work |
|---------|---------------|
| [→ 3.1.0](#entity-registration-dsl-added-in-310--opt-in) | Nothing — the Entity Registration DSL is opt-in. |
| 3.x → 4.0.0 | Call `open()` at boot (constructors no longer build indexes), plus hook-order and `onQueryDelete` changes. No section here — see the [4.0.0 Migration Guide in CHANGELOG.md](CHANGELOG.md). |
| [4.x → 5.0.0](#4x--500--explicit-rbac-registration) | **Call `MongoRbac.register(...)` at boot**, provision roles explicitly, or declare enforcement off. Skipping this compiles fine and denies at runtime. |
| [5.x → 6.0.0](#5x--600--kotlin-24--java-25) | Build with Kotlin 2.4, **deploy on Java 25**. No source changes. |
| [6.0.0 → 6.1.0](#600--610--refused-writes-are-no-longer-successes) | **Audit every `hasError` check on a write result**, and rebuild rather than swapping the jar. Compiles fine either way; misreports refused writes at runtime. |
| 6.1.0 → 6.1.1 | Nothing — additive. |
| 6.1.1 → 6.2.0 | Nothing — additive; the new `deleteApiItemFun` parameter is optional and call-site compatible. |
| 6.2.0 → 6.2.1 | Nothing — internal fix (stale pagination counter after programmatic list refreshes). |
| 6.2.1 → 6.2.2 | Nothing — completes the 6.2.1 counter fix (metadata-only changes now repaint). Skip 6.2.1 and go straight here. |

Skipping releases? Apply **every** section between your version and your target — a migration is not
optional just because you skipped the release that introduced it.

---

## 6.0.0 → 6.1.0 — refused writes are no longer successes

*Written retroactively: this section did not ship with the release. Note also that 6.1.0 contains a
breaking change despite its minor version — see the CHANGELOG entry.*

**Symptom if you skip this.** Everything compiles. At runtime, a write the server refused is reported
to the user as *"Operation successful"*, the form closes, and whatever they had captured is gone.

**Why.** A repository can refuse a write with `State.Warn` and no exception — `Coll.updateOne` and
`SqlRepository` both do when an update would change nothing. `hasError` is `state == State.Error`, so
it is `false` for those refusals, and `ItemState.msgOk` defaults to `MSG_OK`.

**What to change.** Anywhere you branch on the result of a write:

```kotlin
// Before — treats a Warn refusal as success
if (itemState.hasError.not()) { onSaved() } else { showError() }

// After — "did it not succeed"
if (itemState.isRejected.not()) { onSaved() } else { showError() }

// Or, where a no-op should count as done (a form deciding whether it may close):
if (itemState.isWriteComplete) { close() } else { stayOpenForCorrection() }
```

`hasError` is unchanged and still correct for "did it break". The two new properties are additive.

**Also required.**

- **Rebuild against 6.1.0**; do not drop the jar in. `ISimpleState.toast()` gained parameters and
  `Coll.apiListProcess`'s `postProcessList` became a suspending function type. Both are
  source-compatible, so a rebuild is all that is needed.
- **Add `MSG_OK` and `MSG_ERROR` to your gettext catalogue** if your UI is translated. Only those two
  framework defaults are translated; server-authored text is passed through untouched by design.
- **Expect different toast colours** — errors are red rather than yellow, successful saves green
  rather than blue. Nothing to change; listed so it is not mistaken for a regression.

---

## 4.x → 5.0.0 — explicit RBAC registration

**Breaking, and silent at compile time.** 5.0.0 removed three implicit RBAC behaviors. An application
that does not adopt the replacements still compiles, still starts, and then **denies**.

| Symptom after upgrading | Cause | Fix |
|-------------------------|-------|-----|
| Every remote CRUD op denied — reads and lists too — for every user, including one your `rootUser()` override should allow | No provider registered; the fail-closed gate runs **before** resolution, so `rootUser()` is never consulted | [1. Register at boot](#1-register-the-rbac-provider-at-boot) |
| One role denies; the rest work | The role was never provisioned — roles are no longer created on first check | [2. Provision roles at boot](#2-provision-roles-at-boot) |
| A repository with no RBAC wiring at all now denies everything | Default is `Enforce`, which fails closed instead of allowing | [3. Declare non-enforcing repos](#3-declare-non-enforcing-repositories) |
| A group-based verdict flips — in **either** direction | Explicit group votes now decide where they used to be discarded into the role default | [4. Re-check group verdicts](#4-re-check-group-based-verdicts) |

### 1. Register the RBAC provider at boot

Constructing an `IRoleInUserColl` used to wire the process RBAC state as a side effect of
`Coll.init`. That side effect is gone (LEDGER D10) — registration is now an explicit, ordered,
app-owned boot step.

**Before (4.x)** — implicit; merely constructing the collection registered it:

```kotlin
val roleInUserColl = RoleInUserColl(mongoDb)
// ...RBAC was live from here, invisibly.
```

**After (5.0.0+):**

```kotlin
import com.fonrouge.fullStack.mongoDb.MongoRbac

val roleInUserColl = RoleInUserColl(mongoDb)
MongoRbac.register(roleInUserColl)      // ← the one line every enforcing app must add

check(MongoRbac.isRegistered) { "RBAC provider not registered — all remote CRUD will be denied" }
```

Call it **once**, at boot, after constructing the collection and before serving traffic. Last call
wins. `MongoRbac.unregister()` exists for test isolation.

> **Why this is worth an explicit assertion:** with no provider registered, `getCrudPermission` fails
> closed *before* it reaches the resolver. That is upstream of everything — the root short-circuit,
> direct grants, group grants. A superadmin `rootUser()` override will look broken, but the hook is
> intact and never reached. `MongoRbac.isRegistered` distinguishes the two in one line.

### 2. Provision roles at boot

Permission resolution is now side-effect-free (D4): a **read-shaped permission check no longer writes**.
An unprovisioned role denies instead of self-inserting.

If you overrode `insertCrudRole` / `insertSingleActionRole` to provision lazily, replace that reliance
with an explicit call after `open()`:

```kotlin
val state = appRoleColl.ensureRoles(
    crudContainers = listOf(CommonTask, CommonContact),        // roles of CRUD type
    singleActions = listOf("ReportService" to "exportPayroll"), // (classOwner, funcName) pairs
)
check(!state.hasError) { state.msgError ?: "role provisioning failed" }
```

`ensureRoles` aggregates its primitives' results — the returned `SimpleState` is error-free only if
**every** role was provisioned, else it carries an error naming the ones that were not. (Read the result
via `hasError` or `state`; `isOk` is a constructor parameter, not a readable property.) It delegates to your `insert*` overrides, so **re-run
idempotency is your responsibility**: find-or-insert, or tolerate the unique-index duplicate-key error.
The in-tree primitives are inert stubs, so this is effective only once a subclass implements them.

### 3. Declare non-enforcing repositories

`IRepository.permissionEnforcement` defaults to `Enforce`, and `Enforce` with no provider **denies all**
remote (`call != null`) CRUD — **reads and lists included, not just writes**; `apiList` and every
`ApiItem.Query` run the same gate. An unregistered enforcing repository goes fully dark. 4.x silently
allowed everything.

```kotlin
override val permissionEnforcement = PermissionEnforcement.Off
```

Use this for repositories that are deliberately not permission-governed. `InMemoryRepository` and
`IChangeLogColl` already declare it (the change-log exemption is now declarative rather than a
dispatch-time special case).

> **Not using `:mongodb`?** This is the step that affects you most. `SqlRepository` inherits the default
> `Enforce`, and 4.x let it silently allow everything when no provider was wired — so a SQL-only app
> that never configured RBAC allowed all remote CRUD on 4.x and **denies all of it** on 5.0.0. The only
> `IRolePermissionProvider` fsLib ships lives in `:mongodb` and is wired by `MongoRbac.register`; there
> is **no native SQL RBAC backend yet** (no RoleInUser/RoleInGroup/UserGroup tables). Your options are
> to declare `Off`, or to implement `IRolePermissionProvider` yourself and assign it:
> ```kotlin
> PermissionRegistry.rolePermissionProvider = MyProvider()
> ```

### 4. Re-check group-based verdicts

Group resolution used to **discard explicit votes into the role default** whenever the `upVoteInGroup`
bias wasn't matched. It now decides on the votes themselves — the role default applies only when every
applicable grant is `Default`.

> **This moves verdicts in both directions.** Auditing only for newly-denied roles will miss the case
> where a role became **more permissive**.

| Configuration (2+ applicable group grants) | Before | After |
|---|---|---|
| **Deny present, no Allow** (others may be `Default`), `upVoteInGroup = Allow`, role default **Allow** | Allow *(denies discarded)* | **Deny** |
| **Allow present, no Deny** (others may be `Default`), `upVoteInGroup = Deny` *(the default bias)*, role default **Deny** | Deny *(allow discarded)* | **Allow** ⚠️ |
| Every applicable grant is `Default` | role default | role default — *the rule is unchanged, but the default's own value may differ; see below* |

The **tie-break rule** is unchanged for a single applicable grant: an explicit Allow/Deny still decides,
and a `Default` grant still falls through to the role default. So the table above does not apply to
single-grant roles — but they are **not** exempt from the change below.

### The role default itself no longer inverts

`defaultCrudTaskSet` is now an allow-list. A Deny-default role on a task *not* in the set used to
resolve to **Allow**; it now resolves to **Deny**. (A direct grant's own `crudTaskSet` is a different
field on a different path, and is unchanged.)

This reaches **every** path that falls through to the role default — including a single `Default` grant
and no applicable grant at all. Concretely: a CrudTask role with `defaultPermission = Deny` and
`defaultCrudTaskSet = {Read}`, where the user's only group grant is `Default` with
`crudTaskSet = {Update}`, resolved `Update` to **Allow** in 4.x and resolves it to **Deny** now. Audit
single-grant and zero-grant roles for this case too — the multi-grant table is not the whole story.

Audit both directions. The ⚠️ row is the one that grants access you did not previously grant.

### 5. Replace group-blind membership counts

If you queried membership directly — `countDocuments(RoleInUser by userId + appRoleId)` — that is
**group-blind**: a role held only through a group has no direct row, so the user was wrongly denied.
Use the group-aware API instead, and pick the operation deliberately (existence ≠ authorization):

```kotlin
// Does an edge exist at all — direct OR via a group? (An explicit Deny edge still returns true.)
roleInUserColl.hasSingleActionGrant(userId, appRoleId)

// Is the user actually allowed? Full precedence resolution — a direct Allow beats a group Deny.
roleInUserColl.isAllowedSingleAction(userId, appRoleId)
```

Engine-agnostic callers can use `RbacMembership` with their own `IRbacGrantPort`.
`./gradlew :samples:rbac:run` is a runnable, database-free walkthrough of both.

---

## 5.x → 6.0.0 — Kotlin 2.4 + Java 25

**Toolchain-only.** No fsLib API changed; nothing to rewrite.

1. **Build with Kotlin 2.4** (KVision 9.6.0 requires it).
2. **Deploy on Java 25.** This is not optional and not just a build setting: KVision 9.6.0's runtime
   artifacts — `kvision-common-remote` date types, consumed by `:core` — are Java 25 bytecode. An
   earlier JRE fails at class-load:
   ```
   UnsupportedClassVersionError: io/kvision/types/DateKt ... class file version 69.0
   ```
3. Set `jvmToolchain(25)` in your build. Mixing targets fails the compile with
   *"Cannot inline bytecode built with JVM target 25 into bytecode that is being built with JVM target 21"*.

If Java 25 is not available in your deployment, **stay on 5.0.0** — it carries identical library
behavior on KVision 9.5.0 / Kotlin 2.3.20 / Java 21.

---

## Entity Registration DSL (added in 3.1.0 — opt-in)

Three convenience APIs that reduce per-entity boilerplate: `simpleContainer()`, `StandardCrudService`,
and `registerEntityViews()`.

All three are **opt-in** — existing code continues to work without changes.

---

### 1. `simpleContainer()` — shorter CommonContainer declarations

**Package:** `com.fonrouge.base.common`

Replaces verbose `object ... : ICommonContainer(...)` declarations by inferring
`itemKClass` and `filterKClass` from reified generics.

**Before:**

```kotlin
import com.fonrouge.base.api.ApiFilter
import com.fonrouge.base.common.ICommonContainer

object CommonTask : ICommonContainer<Task, String, ApiFilter>(
    itemKClass = Task::class,
    filterKClass = ApiFilter::class,
    labelItem = "Task",
    labelList = "Tasks",
    labelId = { it?.let { "${it.title} (${it._id})" } ?: "<no-task>" },
)
```

**After:**

```kotlin
import com.fonrouge.base.common.simpleContainer

val CommonTask = simpleContainer<Task, String>(
    labelItem = "Task",
    labelList = "Tasks",
    labelId = { it?.let { "${it.title} (${it._id})" } ?: "<no-task>" },
)
```

**Notes:**

- `simpleContainer<T, ID>()` uses `ApiFilter` by default. For custom filter types,
  use `simpleContainerWithFilter<T, ID, FILT>()`.
- The result is a `val` instead of an `object`. All existing call sites that reference
  the container by name continue to work — only the declaration changes.
- The `ICommon.name` property is overridden to return the entity class name (e.g.,
  `"Task"`), so URL generation works correctly despite the anonymous object.
- All parameters (`labelItem`, `labelList`, `labelId`, `labelItemId`) have sensible
  defaults, so you only need to specify what you want to customize.

---

### 2. `StandardCrudService` — eliminate service delegation boilerplate

**Package:** `com.fonrouge.fullStack.services`
**Module:** `:fullstack` (jvmMain)

Replaces manual `apiList` / `apiItem` method implementations that simply forward
to the repository.

**Before:**

```kotlin
import com.fonrouge.base.api.ApiFilter
import com.fonrouge.base.api.ApiList
import com.fonrouge.base.api.IApiItem
import com.fonrouge.base.state.ItemState
import com.fonrouge.base.state.ListState
import com.fonrouge.fullStack.memoryDb.InMemoryRepository

class TaskService(
    private val repo: InMemoryRepository<Task, String, ApiFilter, String>,
) : ITaskService {
    override suspend fun apiList(apiList: ApiList<ApiFilter>): ListState<Task> =
        repo.apiListProcess(apiList = apiList)
    override suspend fun apiItem(iApiItem: IApiItem<Task, String, ApiFilter>): ItemState<Task> =
        repo.apiItemProcess(call = null, iApiItem = iApiItem)
}
```

**After:**

```kotlin
import com.fonrouge.base.api.ApiFilter
import com.fonrouge.fullStack.memoryDb.InMemoryRepository
import com.fonrouge.fullStack.services.StandardCrudService

class TaskService(
    repo: InMemoryRepository<Task, String, ApiFilter, String>,
) : StandardCrudService<Task, String, ApiFilter>(repo), ITaskService
```

**Notes:**

- Works with any `IRepository` implementation (`Coll`, `SqlRepository`,
  `InMemoryRepository`).
- Both `apiList` and `apiItem` are `open` — override them to add pre/post
  processing (logging, validation, etc.) without abandoning the base class.
- The `repository` property is `protected`, so subclasses can access it for
  custom queries.
- **Permission checks:** By default, `currentCall()` returns `null`, which skips
  role-based permission checks. Override `currentCall()` in services running
  inside Ktor to enable permission enforcement:

```kotlin
class TaskService(repo: Coll<Task, OId<Task>, ApiFilter, UserId>) :
    StandardCrudService<Task, OId<Task>, ApiFilter>(repo), ITaskService {
    override fun currentCall(): ApplicationCall? = /* from Ktor scope */
}
```

---

### 3. `registerEntityViews()` — declarative view registration

**Package:** `com.fonrouge.fullStack.config`
**Module:** `:fullstack` (jsMain)

Replaces manual `ViewRegistry` setup and force-referenced companion objects with
a single DSL block.

**Before:**

```kotlin
import com.fonrouge.fullStack.config.ViewRegistry
import dev.kilua.rpc.getServiceManager

// In App.start():
val serviceManager = getServiceManager<ITaskService>()
ViewRegistry.itemServiceManager = serviceManager
ViewRegistry.listServiceManager = serviceManager

// Force-reference companions so ConfigView registrations execute
ViewListTask.configViewList
ViewItemTask.configViewItem

KVWebManager.initialize {
    defaultView = ViewListTask.configViewList
}
```

**After (reference-based — recommended when views have companion configs):**

```kotlin
import com.fonrouge.fullStack.config.registerEntityViews
import dev.kilua.rpc.getServiceManager

// In App.start():
val reg = registerEntityViews(getServiceManager<ITaskService>()) {
    list(ViewListTask.configViewList, isDefault = true)
    item(ViewItemTask.configViewItem)
}

KVWebManager.initialize {
    defaultView = reg.defaultView
}
```

**After (inline creation — when views don't have companion configs):**

```kotlin
val reg = registerEntityViews(getServiceManager<ITaskService>()) {
    list(ViewListTask::class, CommonTask, ITaskService::apiList, isDefault = true)
    item(ViewItemTask::class, CommonTask, ITaskService::apiItem)
}
```

**For projects with separate item/list service managers** (e.g., Arel pattern):

```kotlin
val reg = registerEntityViews(
    itemServiceManager = getServiceManager<IItemService>(),
    listServiceManager = getServiceManager<IListService>(),
) {
    list(ViewListOrder.configViewList, isDefault = true)
    item(ViewItemOrder.configViewItem)
}
```

**Notes:**

- **Two registration modes:** Pass an existing config instance (reference-based)
  or pass a KClass + container + function (inline creation). Reference-based is
  recommended when view classes already have companion-object configs, to avoid
  double registration.
- The `isDefault = true` parameter marks that view as the default. Access it via
  `reg.defaultView`. Setting `isDefault = true` on multiple views logs a warning
  and uses the last one.
- Calling `registerEntityViews()` multiple times with different service managers
  logs a warning — consolidate into a single call when possible.
- You can register multiple entities in a single block:

```kotlin
val reg = registerEntityViews(getServiceManager<IMyService>()) {
    list(ViewListTask.configViewList, isDefault = true)
    item(ViewItemTask.configViewItem)
    list(ViewListProject.configViewList)
    item(ViewItemProject.configViewItem)
}
```

---

### 4. `simpleCommon()` — non-data views (landing pages, dashboards)

**Package:** `com.fonrouge.base.common`
**Module:** `:core` (commonMain)

For views that don't manage a data model (no `BaseDoc`, no CRUD), use `simpleCommon()`
to create a lightweight `ICommon` instance instead of `ICommonContainer`:

```kotlin
import com.fonrouge.base.common.simpleCommon
import com.fonrouge.fullStack.config.configView
import com.fonrouge.fullStack.view.View

// Lightweight metadata — label and filter only, no data model
val CommonHome = simpleCommon(label = "Home")

// View configuration
val configViewHome = configView(
    viewKClass = ViewHome::class,
    commonContainer = CommonHome,
    baseUrl = "Home",
)

// The view extends View<ApiFilter> directly (not ViewDataContainer)
class ViewHome : View<ApiFilter>(configView = configViewHome) {
    override fun Container.displayPage() {
        h1(content = "Welcome")
    }
}
```

Register non-data views in the DSL with `view()`:

```kotlin
val reg = registerEntityViews(getServiceManager<ITaskService>()) {
    view(ViewHome.configViewHome, isDefault = true)   // non-data landing page
    list(ViewListTask.configViewList)                  // data-bound list
    item(ViewItemTask.configViewItem)                  // data-bound form
}
```

For custom filter types (e.g., dashboard state), use `simpleCommonWithFilter<FILT>()`.

See `samples/fullstack/showcase/.../ViewHome.kt` for a complete example.

---

### Entity Registration DSL checklist

- [ ] Replace `ICommonContainer` object declarations with `simpleContainer()` /
      `simpleContainerWithFilter()` calls
- [ ] Replace pass-through service classes with `StandardCrudService` inheritance
- [ ] If using `StandardCrudService` with Ktor auth, override `currentCall()`
- [ ] Replace manual `ViewRegistry` setup + companion force-references with
      `registerEntityViews()` DSL
- [ ] Verify build: `./gradlew build`
- [ ] Verify runtime: confirm views load and CRUD operations work
