# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

## [6.2.1] - 2026-08-25

### Fixed
- The pagination counter under every `fsTabulator` list no longer goes stale after a programmatic
  refresh. In remote pagination mode the footer counter (`paginationCounter = "rows"`) takes its
  total from a single Tabulator-internal value that only Tabulator's own remote loader assigns; a
  refresh through `ViewList.dataUpdate()` — the 5s periodic update, or any explicit call such as the
  list behind a just-closed create modal — pushes a bare row array through `replaceData`, which
  bypasses that loader entirely. The rows on screen updated while the footer kept announcing the
  previous total ("1-5 / 5" after the sixth row appeared). `promise()` now refreshes the estimate
  from the response's `last_row` (falling back to Tabulator's own formula when the backend skipped
  the count) before the data is pushed, next to the long-standing `setMaxPage` patch — which
  covered the page count but never this counter.
  Pinned by a browser suite that also documents the dead ends: neither `replaceData`, `setData`,
  nor `setMaxPage` updates the counter, and native page navigation is unaffected by the fix.

### Migration Guide
- Nothing required. The fix is internal to `TabulatorViewList`; no API changed.

## [6.2.0] - 2026-08-24

### Fixed
- Modal views no longer leak. `ConfigView.openView` and `ConfigViewItem.openViewItem` build a fresh
  `Modal` per call in their `VMode.modal` branch, and `Modal.hide()` does not dispose: KVision
  registers every modal in its `Root` registry at construction and removes it only in `dispose()`.
  Closing a modal view therefore retained the whole `View` behind it — grids, date pickers,
  observable subscriptions — for the life of the page. Both now dispose on `hidden.bs.modal`.
  (The DOM node was always released; KVision renders only visible modals. What leaked was the object
  graph.)
- Cancelling a form with unsaved changes no longer freezes the tab. The prompt used the native
  blocking `window.confirm()`, which halts the whole page — including an automated browser session —
  until it is dismissed. It now uses KVision's async `Confirm`, the same modal already used for
  delete confirmations. Declining leaves the view untouched, as before.
- The `DataMedia` row-delete column can now actually delete. `columnDefinitionDeleteItem()`'s
  zero-arg overload resolves its `apiItemFun` through `configViewItem()`, which looks a config up by
  name convention (`ViewListX` -> `ViewItemX`); an app with no `ViewItem` registered for its
  `DataMedia` type got `null` there and the button reported `"No configViewItem found"` to the user
  instead of deleting.

### Added
- `IViewListDataMedia.initializeViewListDataMedia(dataMediaService, deleteApiItemFun, buildViewListDataMedia)`
  — the new optional `deleteApiItemFun` routes the delete column through the explicit
  `columnDefinitionDeleteItem(visible, apiItemFun)` overload, bypassing the name-convention lookup.
  Declared **before** `buildViewListDataMedia`: that parameter is a function type passed as a
  trailing lambda at call sites, so a parameter added after it would capture the lambda and break
  every existing caller at compile time. Omitting it preserves the previous behaviour exactly.

### Known issues
- Four other framework modals are built the same throwaway way and are **not** yet disposed on
  close: `withProgress`, `helpButtons.showManualModal`, `userSessionInfoModal`, and
  `media`'s `IViewListChangeLog`. Same leak, tracked separately.
- Every displayed `View` registers anonymous `window` `mousemove`/`keydown` listeners that capture
  the view, and nothing removes them. Disposing a modal releases its widget subtree but not the
  `View` object itself, and the handlers keep firing for every view ever opened.

### Migration Guide
- **No migration required.** `deleteApiItemFun` is optional and declared before
  `buildViewListDataMedia` precisely so existing trailing-lambda call sites keep compiling; omitting
  it preserves the previous delete-column behaviour exactly. The modal and confirmation fixes are
  internal to the framework.
- Docs refreshed in this release: `README.md` and `USAGE-GUIDE.md` still published `6.0.0`
  coordinates, and `USAGE-GUIDE.md` still taught `hasError` as the way to check a write result
  without mentioning `isRejected` — the exact mistake `6.1.0` exists to prevent.

## [6.1.1] - 2026-08-19

### Fixed
- After a completed write, `ViewItem` republishes the accepted item through `itemObservable`.
  Everything derived from `item` — the title, the context menu, bindings — previously kept rendering
  the pre-write state, and during a Create that state was `item == null`. Null-guarded: a write that
  completes without echoing the item back no longer blanks a form that is showing one.
- The unsaved-changes baseline is derived from what the form holds after that republish, so a server
  that normalises a submitted value no longer leaves the view looking dirty.

### Added
- `ViewItem.onUpsertResult(itemState)` — `protected open`, the seam for a view whose *structure*
  depends on the saved record. Bindings refresh values; a section gated by a plain `if (item?...)` in
  `pageItemBody()` was settled while the form was built and no observable re-runs it. Such a view
  overrides this and navigates to the saved record. Only completed writes reach it — refusals are
  presented by the framework, so an override cannot suppress them.

### Migration Guide
- Nothing required. Additive; existing overrides and call sites are unaffected.

## [6.1.0] - 2026-08-18

> **Versioning note (recorded retroactively).** This release carries a `feat(state)!` commit — a
> behavioral break — and by this project's own rule (`CONTRIBUTING.md` step 1: any `!` since the last
> tag means a major) it should have been `7.0.0`. It was published as a minor, and Maven Central
> versions are immutable, so the number cannot be corrected. Treat `6.0.0 -> 6.1.0` as a **breaking**
> upgrade and read the Migration Guide below. This entry and the `6.1.1` one were both written after
> publication, which is why neither shipped with its release.

### Changed
- **Breaking (runtime, silent at compile time)**: a repository can refuse a write with `State.Warn`
  and no exception — `Coll.updateOne` and `SqlRepository` both do for a no-op update. Every client
  branched on `hasError`, which is true only for `State.Error`, so those refusals were reported as
  **successes**: `ItemState.msgOk` defaults to `MSG_OK`, so the UI announced "Operation successful"
  for a write the server had turned down, then closed the form and discarded what the user captured.
  A refusal now keeps the form open, keeps the save buttons available, and shows a sticky message.
- **Breaking (visual)**: one state-to-toast path. Severity follows the state, so `State.Error` renders
  as a danger (red) toast rather than a warning (yellow), a successful save renders as a success
  (green) toast rather than an info (blue) one, and a state carrying no message is still announced
  instead of being silently dropped.
- **Breaking (binary)**: `ISimpleState.toast()` gained parameters and `Coll.apiListProcess`'s
  `postProcessList` became a suspending function type, so post-processing can do its own I/O without
  blocking the database dispatcher. Both are source-compatible; consumers must rebuild rather than
  drop in the jar.
- Only the framework's own default messages (`MSG_OK`, `MSG_ERROR`) are translated. gettext.js runs
  its `strfmt` pass over untranslated msgids too, so a `%` followed by digits became `undefined` —
  and domain messages embed user data, as `Coll.friendlyExceptionMessage` does with duplicate-key
  values.

### Added
- `ISimpleState.isRejected` — "did it not succeed", covering `Warn` and `Error`, alongside
  `hasError`'s narrower "did it break".
- `ItemState.isWriteComplete` — success, or a benign no-op. `noDataModified` is honoured only for
  `Warn`: alone it means "nothing was written", which is equally true of an outright failure.
- `ISimpleState.completionToast()` / `rejectionToast()`, `dismissStickyToasts()`,
  `STICKY_TOAST_CLASS`, and `ViewItem.defaultUpsertToast()`.

### Fixed
- `ViewItem.addSerializedValue` no longer throws `IllegalArgumentException` ("Star projections in type
  arguments are not allowed") at runtime for a star-projected property type — the real case being a
  polymorphic foreign key such as `OId<out IRutaProceso<*>>`. The failure surfaced inside
  `onBeforeDisplayForm` as a **blank form with no compile-time signal**. `IBaseId` types
  (`OId`/`StringId`/`IntId`/`LongId`) now take a non-reified fast path that dispatches on the concrete
  class and delegates to the real id serializers, so the canonical parent-ID idiom works for
  polymorphic FKs unchanged. The residual reified path now wraps the error with an actionable message
  naming the property.

### Added
- `ViewItem.addSerializedValue(property, element: JsonElement)` — a non-reified overload for
  star-projected types outside the id family. Previously `hiddenFields` was `internal` with no public
  door, forcing downstream workarounds through `serverSeeds` (which has different drain semantics).

### Migration Guide
- **Audit every `hasError` check on a write result.** It answers "did it break", not "did it not
  succeed". Use `isRejected`, or `ItemState.isWriteComplete` where a benign no-op should count as
  done. Code left on `hasError` keeps compiling and keeps reporting refused writes as successes.
- **Expect different toast colours.** Nothing to change; described above so the shift is not
  mistaken for a regression.
- **Rebuild against the new artifacts** rather than swapping the jar — `toast()` and
  `postProcessList` changed signature.
- **Add `MSG_OK` / `MSG_ERROR` to your catalogue** if your UI is translated; only those two
  framework defaults are routed through `gettext`.

## [6.0.0] - 2026-06-23

Toolchain release. Upgrades the frontend/build stack to KVision 9.6.0 + Kotlin 2.4, which moves the
project to a **Java 25** toolchain. No library behavior changed — every breaking aspect is a build and
runtime requirement inherited from the upgraded dependencies.

### Changed
- **Breaking**: the JVM toolchain is now **25** (was 21), project-wide. KVision 9.6.0's Gradle plugin
  requires a Java 25 build, and its runtime artifacts (`kvision-common-remote` date types, consumed by
  `:core`) are Java 25 bytecode. Consumers of `6.0.0` must **build with Kotlin 2.4 and deploy on
  Java 25** — an earlier JRE fails at class-load with `UnsupportedClassVersionError` (class file
  version 69.0).
- **Breaking**: dependency upgrades — KVision 9.5.0 → 9.6.0, Kotlin 2.3.20 → 2.4.0, KSP 2.3.5 → 2.3.9,
  Kilua RPC 0.0.43 → 0.0.45, kotlinx-coroutines 1.10.2 → 1.11.0, kotlinx-serialization 1.10.0 → 1.11.0.
- The fullstack JS client migrated from `io.kvision.remote.KVCallAgent` to `dev.kilua.rpc.CallAgent`
  (Kilua RPC 0.0.45 removed `KVCallAgent`; the `jsonRpcCall` signature is identical). Internal — no
  fsLib signature changed.

### Migration Guide
- **Deploy on Java 25** and build with Kotlin 2.4. This is the whole migration; there is no source
  change on the fsLib API surface. If either is not an option, stay on `5.0.0` — it carries the same
  library behavior on KVision 9.5.0 / Kotlin 2.3.20 / Java 21.
- Building **fsLib itself** additionally requires the Gradle daemon to run on JDK 25
  (`-Dorg.gradle.java.home=<jdk25-home>`); see `CONTRIBUTING.md`.

## [5.0.0] - 2026-06-22

The RBAC permission-resolution release. Permission resolution (user **and** group action assignment) is
now a total, engine-agnostic algebra (`blueprints/rbac-permission-resolution/`, decisions D1–D10):
extracted out of the Mongo collection into a pure `RbacResolver` over an `IRbacGrantPort`, proven over
two real ports, with two unsafe-Allow foot-guns closed, the fail-open default replaced by fail-closed
enforcement, resolution made side-effect-free, and a group-aware membership API added for consumers.

Three of the breaking changes are **silent-to-compile, loud-at-runtime**: an app that does not adopt
them keeps compiling and starts denying. See the Migration Guide — in particular
`MongoRbac.register(...)`.

### Changed
- **Breaking**: RBAC provider registration is now **explicit** (LEDGER D10). Constructing an
  `IRoleInUserColl` no longer wires the process RBAC state as a `Coll.init` side-effect. Applications
  must call `MongoRbac.register(roleInUserColl)` **once at boot**; `MongoRbac.isRegistered` is the boot
  diagnostic, `MongoRbac.unregister()` the test-isolation hook. Until registered, an enforcing
  repository fails closed (D6) — remote CRUD is denied, including for a user your `rootUser()` override
  would have allowed, because the gate precedes resolution.
- **Breaking**: RBAC roles are no longer **lazily provisioned** on first permission check (D4, R5).
  Resolution is side-effect-free: an unprovisioned role now denies instead of self-inserting on a
  read-shaped check. Provisioning moved to an explicit `IAppRoleColl.ensureRoles(crudContainers,
  singleActions)` boot entry point, which aggregates its primitives' results and returns an error-free
  state only if every role was provisioned (no false success).
- **Breaking**: enforcement **fails closed** (D6, R7). A repository left at the default
  `permissionEnforcement = Enforce` with no permission provider registered now **denies all** remote
  (`call != null`) CRUD — **reads and lists included, not only writes** — where it previously allowed
  everything silently. `InMemoryRepository` and `IChangeLogColl` declare `PermissionEnforcement.Off` —
  a deliberately non-enforcing engine and the declarative change-log exemption respectively.
- **Breaking**: group resolution **verdicts** changed, in **both** directions — see Fixed and the
  Migration Guide.
- Mongo's `getCrudPermission` now routes through the same registered `PermissionRegistry` provider that
  SQL and in-memory consume (R1). The Mongo-only `Coll.roleInUserColl` companion and `CollPermission`
  are gone, resolving the split-brain where Mongo enforced through a handle the conformance harness
  could not drive; Mongo now runs the conformance permission suite with `enforcesPermissions = true`.

### Added
- `RbacResolver` (`:fullstack` jvmMain) — the pure, backend-agnostic, `ApplicationCall`-free resolution
  algebra (root short-circuit → direct-grant precedence → group tie-break → role default), driving all
  data access through `IRbacGrantPort` (D5). Proven over two real ports: Mongo and an in-memory one.
- `RbacMembership` — the consumer-facing `(userId, appRoleId)` **group-aware** SingleAction membership
  API (D7–D9), closing a real group-blind bypass: a role held only through a group (no direct row) was
  invisible to a raw `countDocuments(RoleInUser by userId + appRoleId)` and the user was wrongly denied.
  Two ops with deliberately separate semantics, never blended — `hasSingleActionGrant` (edge existence,
  direct **or** group) and `isAllowedSingleAction` (authorization = the resolver, **not** a
  deny-override union, so a direct Allow beats a group Deny).
- `MongoRbac` — the explicit boot registrar (`register` / `unregister` / `isRegistered`).
- `IAppRoleColl.ensureRoles(crudContainers, singleActions)` — explicit boot-time role provisioning.
- `PermissionEnforcement { Enforce, Off }` (`:core`) + a defaulted `IRepository.permissionEnforcement`
  member (source-compatible: defaulted getter).
- `InMemoryRbacGrantPort` (`:memorydb`) — a no-DB grant port for samples and tests.
- `samples/rbac` — a runnable, database-free RBAC walkthrough (`./gradlew :samples:rbac:run`)
  demonstrating the resolver and the membership API over the in-memory port.
- Characterization + conformance tests pinning D1–D10, including the group-only membership bypass, the
  direct-Allow-over-group-Deny precedence case, the D10 registration pin, and the change-log exemption.

### Fixed
- **Discarded-grants tie-break** (D2, R4): `getGroupPermission`'s multi-grant branch fell through to
  `buildDefaultAppRolePermission` whenever the `upVoteInGroup` bias was unmet, **discarding explicit
  Allow/Deny votes into the role default**. Two Deny groups under an Allow-biased role therefore
  resolved to whatever that default said — Allow, if the default was Allow. Replaced with a total rule
  applied uniformly to single- and multi-grant sets: deny-override by default, `upVoteInGroup == Allow`
  as the explicit per-role allow-override opt-in. An explicit Allow/Deny grant is never discarded — the
  role default applies only when every applicable grant is `Default`.
  **This changes verdicts in both directions** (see the Migration Guide): explicit votes now decide
  where the role default used to.
- **`defaultCrudTaskSet`-miss inversion** (D3, R3): `buildDefaultAppRolePermission` **inverted** the
  role default — it returned **Allow** for a Deny-default role on a task *not* in `defaultCrudTaskSet`,
  disagreeing with the direct-grant path. Replaced with allow-list semantics: a task in the set takes
  `defaultPermission`, a miss is uncovered → Deny. (The direct grant's own `crudTaskSet` path was never
  affected and is preserved verbatim.)
- `fetchDirectGrant` no longer decodes a full `RoleInUser` document (D9, R13): it projects
  `{permission, crudTaskSet}` server-side, which also hardens the shared resolver path against an
  undecodable grant row.

### Migration Guide
- **Register the RBAC provider at boot** — the one change every enforcing MongoDB app must make:
  ```kotlin
  val roleInUserColl = RoleInUserColl(mongoDb)   // your IRoleInUserColl
  MongoRbac.register(roleInUserColl)             // NEW in 5.0.0 — previously a construction side-effect
  check(MongoRbac.isRegistered)                  // optional boot assertion
  ```
  Without it, `getCrudPermission` fails closed **before** any resolution — so `rootUser()` overrides and
  every grant are bypassed, and remote CRUD is denied wholesale. This is silent at compile time.
- **Provision roles at boot** — if you overrode `insertCrudRole` / `insertSingleActionRole` to provision
  lazily, first touch of an unknown role now denies. Call
  `appRoleColl.ensureRoles(crudContainers = listOf(...), singleActions = listOf("Class" to "func"))`
  after `open()`. Re-run idempotency is the override's responsibility (find-or-insert, or tolerate the
  unique-index duplicate-key error).
- **Non-enforcing repositories must say so** — a repository with no permission provider wired now denies
  *all* remote CRUD (reads included) under the default `Enforce`. Either register a provider or declare
  `override val permissionEnforcement = PermissionEnforcement.Off`.
- **Re-check group-based verdicts — in both directions.** Explicit group votes now decide where they
  used to be discarded into the role default:
  - **Allow → Deny**: two or more applicable group grants containing a Deny and no Allow (any others
    being `Default`) under an `upVoteInGroup = Allow` role whose role default was Allow. Previously the
    default won; now the explicit Deny does.
  - **Deny → Allow**: two or more applicable group grants containing an Allow and no Deny (any others
    being `Default`) under an `upVoteInGroup = Deny` role — **the default, safe bias** — whose role
    default was Deny. Previously the default won; now the explicit Allow does.
  - **Allow → Deny**: a `defaultCrudTaskSet` miss under a Deny-default role, which used to invert to
    Allow. This one reaches **every** path that falls through to the role default, including
    single-grant and zero-grant roles — the tie-break table is not the whole story.

  Audit for roles that become **more** permissive, not just less.
- **Replace raw membership counts** — a group-blind `countDocuments(RoleInUser by userId + appRoleId)`
  should become `roleInUserColl.hasSingleActionGrant(userId, appRoleId)` (existence) or
  `roleInUserColl.isAllowedSingleAction(userId, appRoleId)` (authorization). Pick deliberately: they are
  not interchangeable. Engine-agnostic callers can use `RbacMembership` with their own
  `IRbacGrantPort`.

## [4.0.0] - 2026-06-10

The repository write/delete/lifecycle contract release. `IRepository`'s write, delete, and
init behavior is now a written, test-pinned contract (`blueprints/repository-write-lifecycle/`,
invariants I1–I7), converged across all three engines (`Coll`, `SqlRepository`,
`InMemoryRepository`) and enforced by a cross-engine conformance suite — including the Mongo
engine running against a real mongod in CI.

### Changed
- **Breaking**: canonical write hook order is now **shared-`Upsert`-outermost, symmetric across
  create and update, identical across engines** (CONTRACT I1, LEDGER D2): before-hooks run
  `onBeforeUpsertAction` → `onBefore{Create|Update}Action`, after-hooks run
  `onAfter{Create|Update}Action` → `onAfterUpsertAction`. Reordered the former outliers — Mongo/SQL
  `updateOne` before-hooks and Mongo `updateFieldsById` before-hooks + query gates.
- **Breaking**: delete dependency safety is owned by the concrete `deleteOne`, exactly once, in
  every engine (CONTRACT I3, LEDGER D1). `onQueryDelete`'s default is now a plain `isOk` gate and
  no longer performs the `findChildrenNot` pre-check; overriding it can no longer bypass
  referential protection.
- **Breaking**: repository init lifecycle (CONTRACT I4, LEDGER D3/D10). `Coll` no longer launches
  fire-and-forget init from its constructor; explicit, idempotent, retryable `open()` awaits
  `onAfterOpen()` + `indexes()` and **surfaces failures** (previously swallowed by a detached
  coroutine). Generic item/list entry points lazily `ensureOpen()`; a failed open returns an error
  and retries on the next generic call.
- After-hook semantics clarified and pinned (CONTRACT I2): after-hooks fire **exactly once per
  attempted write** — including failed driver writes, with `result = false`; the change-log is
  written only on success.

### Added
- `IRepository.allowApiCrud(apiItem): SimpleState` — first-class gate at the top of the
  `apiItemProcess` Action branch in all engines (CONTRACT I5, LEDGER D4). Origin-scoped write
  policy ("writable only via domain services") now overrides this gate instead of the whole
  dispatcher. Companion `apiCrudDisabledErrorMsg` + `denyApiCrud()` give the lockdown its own
  vocabulary, distinct from `readOnly`.
- The I1–I7 contract written into `IRepository` KDoc, with `CONTRACT.md` as the durable spec.
- `:conformance` test module (not published): engine-agnostic assertions run against
  `InMemoryRepository`, `SqlRepository` (H2, no Docker), and `Coll` (Testcontainers Mongo —
  Assume-skips locally without Docker, fails loudly in CI if Docker is missing), covering the
  generic-CRUD gate, per-action permission parity, validation side-effect freedom, delete safety,
  canonical hook order, and init lifecycle — plus a real-mongod duplicate-key write-failure test
  (`MongoWriteFailureTest`).
- `InMemoryRepository.deleteOne` now performs the `findChildrenNot` dependency check — deleting a
  parent with children is refused (`State.Error`), matching Mongo/SQL.

### Fixed
- `Coll.insertOne` no longer writes a phantom change-log entry for failed or validation-rejected
  inserts; `onValidate` + constructor-strip are hoisted above the write `try` in `insertOne`,
  `updateOne`, and `updateFieldsById`, making validation-failure side-effect freedom structural.
- `SqlRepository.apiItemProcess` now runs the per-action CRUD permission check in its Action
  branch, matching its Query branch and Mongo (no-op without a configured
  `rolePermissionProvider`). The in-memory engine remains intentionally permission-free.
- `MongoDbBuilder.getMongoDb()` reuses a process-wide `MongoClient` cached per connection string
  instead of constructing a new, never-closed client per repository (LEDGER D12).
- `updateMany`/`bulkWrite` documented as raw, ungated escape hatches; `updateFieldsById`
  documented as Mongo-only (engine-coupling) in KDoc.

### Migration Guide
- **Update-path hook overrides**: `onBeforeUpsertAction` now runs **before**
  `onBeforeUpdateAction` (previously after, on Mongo/SQL `updateOne`). Overrides that depended on
  observing the specific hook's mutations first must move that logic.
- **`onQueryDelete` overrides**: the default no longer pre-checks dependencies. If you overrode it
  and relied on `super` for the check, nothing changes at the action tier — `deleteOne` enforces
  it unconditionally. Re-add an advisory pre-check only if you need early UX feedback.
- **Mongo init**: constructors no longer create indexes. Call `open()` eagerly at boot (or let the
  generic API surface `ensureOpen()` lazily). `onAfterOpen`/`indexes()` failures now surface —
  deployments that silently tolerated failing index builds will now see the error.
- **Remote CRUD lockdowns**: replace `apiItemProcess` overrides with an `allowApiCrud` override;
  use `denyApiCrud()` for the canonical refusal message.

## [3.3.0] - 2026-04-20

### Changed
- **Breaking**: `ItemState.serializedValueMap` value type changed from `Map<String, String?>?` to `Map<String, JsonElement>?`. Seed values now ride as native `JsonElement` inside the outer `ItemState` payload instead of a JSON-in-JSON quoted string, preserving types and removing double-encoding. `JsonNull` represents an explicit null; omit the key to signal "no value".
- `serializedValueMapEntry(property, value)` moved from `:mongodb` (package `com.fonrouge.fullStack.mongoDb`) to `:core` (package `com.fonrouge.base.state`). Now returns `Map<String, JsonElement>`. The `OffsetDateTime` special case is retained because `OffsetDateTime` is not itself `@Serializable`.
- `ViewItem.addSerializedValue(property, value)` now stores a `JsonElement` (built via `Json.encodeToJsonElement`).
- `ViewItem` applies `JsonNull` consistently: explicit nulls are preserved on both form-control assignment and the submission overlay (previous behavior dropped nulls from the overlay).
- `ViewItem` now splits the single `_serializedValueMap` bucket into two with clear ownership:
  - `serverSeeds` — transient, populated from the wire `ItemState.serializedValueMap`, drained against form controls during Create display via the private `applyServerSeeds()`; unmatched keys stay as residue and feed the submission overlay. Public `var` with a `private set` — callers read and mutate the map but cannot swap the reference.
  - `hiddenFields` — persistent, fed by `addSerializedValue(...)`; merged into the submission payload via `dataOverlayProvider`. Keys that collide with an existing form control are skipped in the overlay (unchanged behavior, now explicit). `@PublishedApi internal val` — reachable from the inline `addSerializedValue`.
- Client-side seed decode in `ViewItem` now unwraps `JsonPrimitive` via kotlinx accessors (`intOrNull`, `doubleOrNull`, `booleanOrNull`) instead of the browser's `JSON.parse`, so integer seeds no longer box into Kotlin/JS `Long` objects that break `FormPanel.getData` submission with a `JsonDecodingException: Expected numeric literal` error. Values up to `2^53` round-trip exactly; beyond that, the browser's own limit applies.
- **Fixed**: `DateFormControl` seeds now parse via the native `kotlin.js.Date` constructor (ISO 8601-aware) instead of KVision's `String.toDateF()` helper. The latter delegates to `fecha.js` with a format pattern that doesn't match the ISO wire format emitted by `FSOffsetDateTimeSerializer` — the `T` date/time separator fails the format regex and `toDateF` silently returned `Date()` ("now"), so `fechaCreacion` / `fechaEsperada` widgets displayed the current browser time (or a partially-parsed garbage date) instead of the seeded value, and then persisted that wrong value on submit.
- `IRepository.onQueryCreateItem` KDoc now documents the two idiomatic return shapes (full `item` vs. sparse `serializedValueMap`).

### Migration Guide
- Update imports: `import com.fonrouge.fullStack.mongoDb.serializedValueMapEntry` → `import com.fonrouge.base.state.serializedValueMapEntry`.
- Server code calling `serializedValueMapEntry(prop, value)` compiles unchanged; only the return type is now `Map<String, JsonElement>`.
- Any non-Kotlin consumer that reads `ItemState` JSON manually must expect seed values as embedded JSON values (not quoted strings).
- Subclasses of `ViewItem` that referenced the private `_serializedValueMap` bucket must migrate to `hiddenFields` (for `addSerializedValue`-style hidden data; `@PublishedApi internal`) or `serverSeeds` (for wire-ingested Create defaults; public `var` with a `private set`).

## [3.2.1] - 2026-04-07

### Added
- `View.fabExtensions` companion property — a mutable list of FAB (Floating Action Button) extension factories invoked in `startDisplayPage` for all main, non-modal views. Register at app startup to inject custom floating buttons into any view.

### Changed
- Refactored help buttons block in `View.startDisplayPage` for clarity.
- Cleaned up unused imports (`ICommon`, `ICommonContainer`) in `View.kt`.

## [3.2.0] - 2026-03-28

### Removed
- **`ViewFormPanel`** class deleted. `ViewItem.pageItemBody()` now returns KVision's `FormPanel<T>` directly. Consumer code that used `viewFormPanel { }` DSL continues to work — the function now lives on `ViewItem` and returns `FormPanel<T>`.
- `CustomMapValue`, `customBindings`, `bindCustomValue()`, `getCustomValue()`, `setCustomValue()`, `getControlValue()` removed from the form panel API.
- `addToSerializedValueMap()` removed from the form panel. Replaced by `ViewItem.addSerializedValue()`.

### Changed
- `ViewItem.formPanel` property type changed from `ViewFormPanel<T>?` to `FormPanel<T>?`.
- Serialized value map lifecycle (server-provided Create defaults) moved from the form panel into `ViewItem`.
- Tabulator data overlay and remaining serialized values now injected via KVision's `dataOverlayProvider` (set up automatically by `ViewItem.viewFormPanel {}`).
- `bindCustom(key = Model::field)` calls resolve to KVision's built-in `FormPanel.bindCustom()` — no wrapper needed.

### Dependencies
- Kotlin 2.3.20, KVision 9.5.0, Kilua RPC 0.0.43.

### Migration Guide
- Change `pageItemBody()` return type from `ViewFormPanel<T>` to `FormPanel<T>`.
- Replace `import com.fonrouge.fullStack.view.ViewFormPanel` with `import io.kvision.form.FormPanel`.
- Replace `addToSerializedValueMap(prop, value)` with `this@YourViewItem.addSerializedValue(prop, value)`.
- `viewFormPanel { }` DSL, `bindCustom()`, `bind()`, `add()`, `getData()`, `setData()` — no changes needed.

## [3.1.2] - 2026-03-18

### Added
- `@Computed` annotation (`com.fonrouge.base.annotations.Computed`) — marks body properties as intentionally non-persisted, making the constructor-only persistence convention explicit and self-documenting.
- `ConstructorCopier` utility (`com.fonrouge.fullStack.repository.ConstructorCopier`) — shared, cached reflection-based copier that reconstructs instances using only primary constructor parameters. Replaces duplicated logic across MongoDB, SQL, and InMemory repositories.
- `InMemoryRepository` now strips body properties before store writes (`insertOne`, `updateOne`), matching the behavior of `Coll` and `SqlRepository`.
- `BaseDoc` KDoc now documents the constructor-only persistence convention with code examples.
- `FSNumberDoubleSerializer` registered in `MongoDb` codec configuration for robust numeric type coercion from BSON.

### Changed
- `Coll.copyItemWithPrimaryConstructorParameters()` now delegates to `ConstructorCopier` instead of inline reflection. Public API is unchanged; the `:media` module and subclasses are unaffected.
- `SqlRepository` internal `copyWithPrimaryConstructor()` replaced by `copyCtorOnly()` delegating to `ConstructorCopier`.
- Reduced redundant constructor copies in `Coll.insertOne` (4 → 1), `Coll.updateOne` (5 → 2), `Coll.updateFieldsById` (5 → 3), `SqlRepository.insertOne` (3 → 1), and `SqlRepository.updateOne` (5 → 2). Hooks can now transform items freely; a single copy-to-constructor-params happens once before validation and database write.
- `ConstructorCopier.copyWithConstructorParams()` includes a fail-fast guard: passing a body-property name as a field override throws `IllegalArgumentException`, catching `AssignTo` misuse at runtime.

## [3.1.1] - 2026-03-15

### Added
- `simpleCommon()` and `simpleCommonWithFilter()` factory functions for non-data views (landing pages, dashboards, settings) — creates lightweight `ICommon` instances without requiring a full `ICommonContainer`
- `view()` method in `EntityRegistrationBuilder` for registering non-data views that use `ICommon` instead of `ICommonContainer`
- Reference-based `list()` and `item()` overloads in `EntityRegistrationBuilder` to avoid double-registration of existing configs
- `StandardCrudService.currentCall()` protected hook — override to supply `ApplicationCall` for role-based permission checks in Ktor services
- `ViewHome` showcase sample demonstrating the `View` + `ICommon` + `configView()` pattern for non-data views

### Changed
- `StandardCrudService.apiList` and `apiItem` are now `open`, allowing subclasses to override default behavior
- `ICommon.name` is now `open`, allowing `simpleContainer` and `simpleCommon` factories to provide meaningful names for anonymous objects
- Warnings emitted on multiple `isDefault` registrations and service manager overwrites in `registerEntityViews()`

## [3.1.0] - 2026-03-15

### Added
- `simpleContainer()` and `simpleContainerWithFilter()` factory functions to create `ICommonContainer` instances with reified generics — eliminates `itemKClass`/`filterKClass` boilerplate
- `StandardCrudService` abstract class for service implementations that delegate standard `apiList`/`apiItem` to an `IRepository`
- `registerEntityViews()` DSL for declarative view registration — replaces manual `ViewRegistry` setup and companion object force-references. Supports reference-based (existing configs) and inline creation modes, with `view()` for non-data views, `list()` and `item()` for data-bound views
- `simpleCommon()` and `simpleCommonWithFilter()` factory functions to create lightweight `ICommon` instances for non-data views (landing pages, dashboards, settings)
- `view()` method in `EntityRegistrationBuilder` for registering non-data views that use `ICommon` instead of `ICommonContainer`
- `StandardCrudService.currentCall()` protected hook — override to supply `ApplicationCall` for role-based permission checks in Ktor services
- `ICommon.name` is now `open`, allowing `simpleContainer` and `simpleCommon` factories to provide meaningful names for anonymous objects
- Showcase sample: `ViewHome` — non-data landing page demonstrating `View` + `ICommon` + `configView()` pattern
- `MIGRATION.md` guide for adopting the new Entity Registration DSL

### Changed
- **Breaking:** `ICommonContainer` now derives `idSerializer` automatically from the item's `_id` field via `GeneratedSerializer.childSerializers()` — the `idSerializer` constructor parameter has been removed
- **Breaking:** `ICommon` and `ICommonContainer` now derive `apiFilterSerializer` from a required `filterKClass: KClass<FILT>` parameter — the `apiFilterSerializer` constructor parameter has been removed
- **Breaking:** `ICommonChangeLog` and `ICommonDataMedia` no longer accept an `idSerializer` constructor parameter
- **Breaking:** Removed redundant `CC` type parameter from all generic chains — `Coll<CC, T, ID, FILT, UID>` → `Coll<T, ID, FILT, UID>`, `ViewList<CC, T, ID, FILT, MID>` → `ViewList<T, ID, FILT, MID>`, etc. The `commonContainer` property is now typed as `ICommonContainer<T, ID, FILT>` directly. Affects: `IRepository`, `Coll`, `InMemoryRepository`, `SqlRepository`, `View`, `ViewDataContainer`, `ViewItem`, `ViewList`, `ConfigView`, `ConfigViewContainer`, `ConfigViewItem`, `ConfigViewList`, `TabulatorViewList`, `PageDef`, and all MongoDB/media interfaces.
- Samples and tests migrated to use `ApiFilter` directly instead of defining empty custom filter classes (e.g., `TaskFilter`, `ContactFilter`)

### Migration guide
Replace:
```kotlin
data object CommonFoo : ICommonContainer<Foo, StringId<Foo>, FooFilter>(
    itemKClass = Foo::class,
    idSerializer = StringId.serializer(Foo.serializer()),
    apiFilterSerializer = FooFilter.serializer(),
    labelItem = "Foo",
)
```
With:
```kotlin
data object CommonFoo : ICommonContainer<Foo, StringId<Foo>, FooFilter>(
    itemKClass = Foo::class,
    filterKClass = FooFilter::class,
    labelItem = "Foo",
)
```
If the filter class is empty (no custom properties), use `ApiFilter` directly and delete the filter class.

For the CC removal, drop the first `Common...` type argument from all generic references:
```kotlin
// Before:
class MyColl : Coll<CommonFoo, Foo, OId<Foo>, FooFilter, UserId>(...)
class MyViewList : ViewList<CommonFoo, Foo, OId<Foo>, FooFilter, Unit>(...)
// After:
class MyColl : Coll<Foo, OId<Foo>, FooFilter, UserId>(...)
class MyViewList : ViewList<Foo, OId<Foo>, FooFilter, Unit>(...)
```

## [3.0.3] - 2026-03-14

### Added
- `-PSNAPSHOT` flag for `publishToMavenLocal` — automatically appends `-SNAPSHOT` to the version without editing `libs.versions.toml`
- Safety guard that blocks `publishToMavenLocal` without `-PSNAPSHOT` to prevent shadowing Maven Central release artifacts (override with `-PFORCE_LOCAL`)
- Documentation clarifying that `/apiContract` is optional when using a shared contract library with `@RpcBindingRoute` named routes

### Changed
- Maven groupId changed from `io.github.tfonrouge.fslib` to `com.fonrouge.fslib`
- Replace `fslib-named-routes` Gradle plugin with Kilua RPC's built-in `@RpcBindingRoute` annotation
- Update documentation with Android sample link
- Signing tasks are now disabled for local/snapshot publishes (configuration cache compatible)

### Removed
- `fslib-named-routes.gradle.kts` convention plugin (no longer needed)
- Migration guides (`MIGRATION-GUIDE-2.0.md`, `MIGRATION-GUIDE-3.0.md`)

## [3.0.2] - 2026-03-13

### Added
- Repository and website links for external dependencies in docs
- Updated README.md and USAGE-GUIDE.md

## [3.0.1] - 2026-03-12

### Added
- Named routes for Kilua RPC via `fslib-named-routes` Gradle plugin
- `RouteContract` class for API contract endpoint (`/apiContract`)
- `InMemoryRepository` for samples, tests, and prototyping (`:memorydb` module)
- Showcase sample with shared contract library pattern

## [3.0.0] - 2026-03-10

### Changed
- Module renames: `:base` to `:core`, `:fullStack` to `:fullstack`, `:utils` to `:media`
- Extracted MongoDB and SQL into independent engine modules (`:mongodb`, `:sql`)
- Decoupled permission system via `IRolePermissionProvider` / `PermissionRegistry`
- Migrated from KVision RPC to Kilua RPC

### Added
- `:sql` module with `SqlRepository` implementation using Exposed
- `:memorydb` module for in-memory storage
- `:ssr` module for server-side rendering with Ktor HTML builder
- Cross-engine dependency checking between MongoDB and SQL repositories
