# BRIEF — RBAC Permission Resolution (LIBRARY)

> Generated: 2026-06-21 · Premise: cathedral · Mode: business-blueprint LIBRARY

## Goal

Turn RBAC permission resolution from an **undocumented, Mongo-local, untested decision tree** into a
**single written contract** with a **total, engine-agnostic resolution algebra**, decide the five
open semantic questions deliberately (LEDGER D1–D6), and pin every rule with a cross-engine
conformance suite — **after** characterization tests freeze the current Mongo behavior so no semantic
change ships silently.

## Motivation

An RBAC audit (two verification workflows, claims adversarially refuted against current `master`)
established that the model already supports assigning actions to **groups** — `IRoleInGroup` binds
`(appRoleId, permission, crudTaskSet)` to a group exactly as `IRoleInUser` binds it to a user, and
`IUserGroup` carries membership — and that the resolution actually works. But it works in **one
engine only**, behind a **side-effecting** code path, with **two security foot-guns**, and with
**zero test coverage**.

> The resolution semantics live entirely inside `mongodb/IRoleInUserColl.permissionState`. The
> backend-agnostic seam (`IRolePermissionProvider.getCrudPermission`) carries only a collapsed
> CRUD-by-user *result* — no group concept, no single-action concept — so the policy that produced
> the verdict is invisible and unreachable from any other engine. An undocumented, untested,
> single-engine authorization decision tree is exactly how plausible-but-wrong outcomes (two explicit
> denies resolving to *allow*) survive unnoticed.

This blueprint does **not** patch the foot-guns ad hoc. The defects are contract-level and
cross-engine; the resolution algebra is the product, so the algebra must be specified, decided, and
test-pinned before code changes — the same discipline the [[repository-write-lifecycle]] work used.

## Findings register

Severity and status as verified by the RBAC audit workflows (claims R1/R7 adversarially **upheld**).
All line anchors are to the state verified 2026-06-21; re-locate by symbol when implementing.

| ID | Severity | Status | Summary |
|----|----------|--------|---------|
| R1 | High (abstraction leak) | **Partially addressed (P3.1a/P3.1b)** | Was: group + single-action resolution lived only in `mongodb/IRoleInUserColl`. **P3.1a** extracted the resolution **algebra** into the backend-agnostic `RbacResolver` over `IRbacGrantPort` (Mongo is one port impl); the policy is no longer Mongo-private. **P3.1b** added the **InMemory** native port (`InMemoryRbacGrantPort`) — the resolver now runs over two real ports. **Remaining R1 gap:** (a) **reach** — native **SQL** RBAC port (needs RBAC tables; a separate sub-blueprint); (b) **registration** — SQL still borrows Mongo's provider via the global-mutable `PermissionRegistry`, a construction side effect (`Coll.kt:1740-1744`, R10) — deferred to P3.2. → D5 (P3.2). |
| R2 | Medium-High (surprising semantics) | Open | A direct `IRoleInUser` row for `(userId, appRoleId)` short-circuits resolution and **groups are never consulted — even when the direct row's permission is `Default`** (`IRoleInUserColl.kt:197-226`, early `return` inside `roleInUser?.let`). Groups cannot *compose with* / *add to* a user who holds any direct row. → D1. |
| R3 | High (security foot-gun + inconsistency) | **Fixed (P2.2, D3)** | Was: `buildDefaultAppRolePermission` **inverted** on a `crudTaskSet` miss (`Deny`-default + task-not-in-set ⇒ **Allow**), disagreeing with the direct-row path (miss ⇒ `Deny`). Verified repro: `CrudTask`, `defaultPermission=Deny`, task ∉ set, zero-grant user → `Allow`. **Fixed:** allow-list semantics — a miss is uncovered → `Deny`; direct and default paths now agree. Pinned by `crudTaskSetMissUnderDenyDefaultDenies` (red→green, runs in CI). → D3 (locked). |
| R4 | High (security foot-gun) | **Fixed (P2.1, D2)** | Was: the multi-group tie-break discarded explicit grants — 2+ group rows whose `upVoteInGroup` bias was unmet fell through to the **role default**, so two `Deny`s under an `Allow`-biased role resolved to `Allow`; the single-group case ignored `upVote`. Verified repro: `SingleAction`, `upVote=Allow`, `defaultPermission=Allow`, two `Deny` groups → `Allow` (the fix is in the shared group-conflict path, so the red→green pin exercises it via a `CrudTask` role; a `SingleAction` row may be added later for a sharper trail). **Fixed:** total deny-override default + `upVote=Allow` allow-override opt-in, uniform across single/multi-group; explicit grants never discarded. Pinned by `multiGroupDeniesAreHonoredNotDiscarded` + `mixedGroupGrantsAllowWinUnderAllowOverride` (red→green, runs in CI). → D2 (locked). |
| R5 | Medium-High (side-effecting read; contract tension) | **Fixed (P2.3, D4)** | Was: the lazy-provision call site `findOne ?: insertCrudRole/insertSingleActionRole` sat on the ungated check path at three sites, inviting an ungated write on the read path (latent in-tree, where `insert*` are inert stubs). **Fixed:** removed the `?: insert*` from all three sites — a missing role now denies; roles are provisioned explicitly via `IAppRoleColl.ensureRoles(...)` at boot. Resolution is side-effect-free (T3). Pinned by `missingSingleActionRoleDeniesWithoutProvisioning` (red→green vs P1.2 `c5a09a6c`) + `RbacEnsureRolesTest`. **BREAKING** for downstream `insert*` overriders (migration: `ensureRoles()` at boot). → D4 (locked). |
| R6 | Medium (brittleness) | **Fixed (P3.1a)** | Was: `getGroupPermission` decoded aggregation output into the file-private fixed-shape `RoleInGroup`/`GroupOfUser`, not the generic `GR`/`GOU`. **Fixed:** the D5 grant-fetch port (`IRbacGrantPort.fetchGroupGrants`) returns the typed `RoleGrant`; the private decode classes are deleted and the aggregation is `$project`-shaped to exactly `{permission, crudTaskSet}` (no reliance on decoder leniency). → D5/P3.1a. |
| R7 | High (fail-open default) | **Fixed (P2.4, D6)** | Was: unconfigured deployments **failed open** at three sites — SQL null-provider, InMemory hard-`Ok` (+ its Action path never calling the check), and Mongo `Coll.roleInUserColl == null`. **Fixed:** new core enum `PermissionEnforcement { Enforce, Off }` + defaulted `IRepository.permissionEnforcement` (default `Enforce`) gates all three — an enforcing engine with no resolver now **fails closed** on a remote write; `InMemoryRepository` declares `Off` (named non-enforcing engine), and InMemory's Action path now invokes the check. Pinned by `unconfiguredDefaultFailsClosedForEnforcingEngines` (SQL fail-closed + Memory-allows run green; Mongo in CI). **BREAKING** (unconfigured deployments flip allow-all → fail-closed). → D6 (locked). |
| R8 | High (unverified security code) | **Partially addressed (P1.1)** | Was: the entire resolution engine was untested (`getGroupPermission` had **0** test callers; `permissionState` was never invoked). P1.1's `RbacPermissionResolutionCharacterizationTest` now invokes `permissionState` and reaches `getGroupPermission`, freezing C1 root short-circuit, C2 direct-shadows-group (`Deny`/`Default`), C3 single-group + two-group `Deny` tie-break, and C4 default-inversion. **Remaining gaps:** C6 cross-engine reach + fail-open (P1.3), the full `upVote`/`Allow`-side + SingleAction matrix, and a sample that wires RBAC (the lone pre-existing test `perActionPermissionParity` still asserts only for **SQL**). (C5 side-effect is now pinned — P1.2 `c5a09a6c` + the P2.3 flip.) → P1.3, P3.3. |
| R9 | Low (cleanliness) | **Fixed (P2.5)** | Was: the `when (roleType)` group-dispatch arms were byte-identical (both called `getGroupPermission` with the same args). **Fixed:** collapsed to a single expression (the role-type divergence lives inside `getGroupPermission`/`buildDefaultAppRolePermission`, not at the dispatch). Behavior-identical (SAFE); suite green. → P2.5. |
| R10 | Low (convention/robustness) | Open | `IRoleInUserColl`/`IUserGroupColl`/`IRoleInGroupColl`/`IGroupOfUserColl`/`IAppRoleColl` are `abstract class`es wearing the `I` interface-prefix; provider registration is a construction side effect on a global mutable (`Coll.kt:1740-1744`). → D5. |
| R11 | Medium (testability/infra) | **Fixed (P1.0)** | Was: the five RBAC abstract colls took only `commonContainer` — unlike `CItemMongoRepository` they accepted **no** `MongoDbBuilder`, so a builder-less `Coll` fell back to the **`internal`** process-global `mongoDbBuilder` (`MongoDb.kt:14`, settable only by the Ktor `MongoDbPlugin`), blocking per-test Testcontainers isolation. P1.0 added an optional `mongoDbBuilder: MongoDbBuilder? = null` to all five colls (forwarded to `Coll`, default `null` = prior global behavior), so the characterization fixture now injects a shared per-test builder. Surfaced by characterization; related to R1 (Mongo-locality) and R10 (global mutable). → P1.0 (done). |
| R12 | **High (API gap — the empirical motivation)** | **Fixed (P4, D7–D9)** | Was: no backend-agnostic, group-aware **boolean membership** query keyed by `(userId/User, appRoleId)` — fsLib's only group-aware entries (`getSingleActionPermission`, `permissionState`) took an `ApplicationCall`/`UserSession<UID>` (never a plain `userId`), keyed the `AppRole` by `classOwner`/`funcName` (never a caller `appRoleId`), and returned a `SimpleState` (never a `Boolean`), so a real Mongo consumer bypassed fsLib with a raw `countDocuments(RoleInUser by userId+appRoleId) > 0` and went **group-blind** (a group-only role, no direct row, was invisible → wrong deny). **Fixed:** `RbacMembership` exposes two `(userId, appRoleId)`→`Boolean` ops over the D5 port — `hasSingleActionGrant` (existence: direct **OR** group) and `isAllowedSingleAction` (effective authz = the resolver) — both **group-aware**, so a group-only grant is now seen. Pinned by `groupOnlyUserIsSeenByBothOperations` (the exact bypass) + the Mongo `groupOnlyMembershipIsSeenByBothOperationsOnMongo` (CI). → D7/D8/D9. |
| R13 | Medium-High (robustness) | **Fixed (P4, D9)** | Was: the direct-row branch **materialized** (`coroutine.find(and(userId eq, appRoleId eq)).first()`), deserializing the **whole** `RoleInUser` doc, so an undecodable row threw at the check — the second reason the consumer moved to a server-side `countDocuments`. **Fixed for both ops:** the **existence** op (`hasSingleActionGrant`) never decodes a grant doc — Mongo `existsDirectGrant` = `countDocuments > 0`, `existsGroupGrant` = the group pipeline + `limit(1)`; and the **authz** op (`isAllowedSingleAction`) consumes the resolver's typed fetches, now **server-side projections** — `fetchDirectGrant` was changed from `find(...).first()` on the full doc to a `match → $limit(1) → $project {permission, crudTaskSet}` pipeline (the same proven shape as `fetchGroupGrants`). So no path `.first()`s a full grant doc, and the change **also hardens the shared `permissionState` resolver path** (P3.1a) against an undecodable grant row. → D9. |

## Scope

In scope: the permission-resolution model in `core/commonMain` (`IAppRole`, `IRoleInUser`,
`IRoleInGroup`, `IUserGroup`, `IGroupOfUser`, `PermissionType`), its Mongo implementation
(`IRoleInUserColl.permissionState` / `getGroupPermission`, `MongoRolePermissionProvider`,
`CollPermission`, `IAppRoleColl`), the backend-agnostic seam (`IRolePermissionProvider` /
`PermissionRegistry`) and its SQL/in-memory consumers, the SSR delegation (`SsrAuth`), and a
permission-resolution conformance suite hosted in `:conformance`.

## Non-goals

- Authentication, session issuance, or the `UserSession` shape — only **authorization** resolution.
- The Tabulator/KVision permission-driven UI affordances (button hiding, etc.) — resolution only;
  the UI consumes the same verdict.
- Reworking `ChangeLog` or the `IChangeLogColl` vs `IChangeLogRepository` split (tracked by
  [[repository-write-lifecycle]] D11), beyond not regressing it.
- Multi-tenant / row-level / attribute-based access control — out of scope; this is role/group RBAC.

## Blast radius / SemVer

- **Phase 1 (SAFE)** — characterization tests freezing current Mongo behavior + extending the
  conformance harness with a permission-resolution profile. No production behavior change → tests
  only, ships within a **minor** release.
- **Decision gate** — D1–D6 locked in LEDGER before any Phase-2 code.
- **Phase 2 (BREAKING)** — changing resolution outcomes (composition, total conflict rule, removing
  the default-inversion, side-effect-free checks, fail-closed default) changes verdicts observed by
  downstream apps and overrides → **major-signal** bump; each step cites `CONTRACT.md` + a
  `LEDGER.md` entry.
- **Phase 3 (mostly SAFE-additive)** — the engine-agnostic resolution port lands additively; moving
  SQL off the borrowed Mongo provider is the one BREAKING piece.

## Definition of done

1. Current Mongo resolution behavior is frozen by characterization tests (Phase 1) **before** any
   semantic change — so every Phase-2 verdict change is a visible, intended diff.
2. D1–D6 are locked in `LEDGER.md`, each with a non-empty falsification condition.
3. Every CONTRACT target invariant (T-series) is stated in the relevant KDoc and asserted by the
   permission-resolution conformance suite, green across every engine that claims to enforce.
4. The two foot-guns (R3 default-inversion, R4 discarded-denies) are closed and pinned; resolution is
   **total** (no silent fall-through past explicit grants) and **side-effect-free** (R5 closed).
5. The fail-open default (R7) is resolved: unconfigured/unsupported engines fail closed or declare
   non-enforcement explicitly — never silent allow-all on a protected path.
