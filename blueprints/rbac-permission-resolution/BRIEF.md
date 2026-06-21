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
| R1 | High (abstraction leak) | Open | Group **and** single-action resolution live only in `mongodb/IRoleInUserColl` (`getGroupPermission` is `private`, `:302`). The agnostic `IRolePermissionProvider` exposes only `getCrudPermission(container, call, crudTask)` → `SimpleState`; no group/single-action surface. SQL borrows Mongo's provider via the global mutable `PermissionRegistry.rolePermissionProvider`, registered as a **construction side effect** of opening a Mongo `IRoleInUserColl` (`Coll.kt:1740-1744`, last-writer-wins). So a SQL CRUD check is gated by **Mongo-resident** role docs iff a Mongo coll happened to boot in-process. → D5. |
| R2 | Medium-High (surprising semantics) | Open | A direct `IRoleInUser` row for `(userId, appRoleId)` short-circuits resolution and **groups are never consulted — even when the direct row's permission is `Default`** (`IRoleInUserColl.kt:197-226`, early `return` inside `roleInUser?.let`). Groups cannot *compose with* / *add to* a user who holds any direct row. → D1. |
| R3 | High (security foot-gun + inconsistency) | **Fixed (P2.2, D3)** | Was: `buildDefaultAppRolePermission` **inverted** on a `crudTaskSet` miss (`Deny`-default + task-not-in-set ⇒ **Allow**), disagreeing with the direct-row path (miss ⇒ `Deny`). Verified repro: `CrudTask`, `defaultPermission=Deny`, task ∉ set, zero-grant user → `Allow`. **Fixed:** allow-list semantics — a miss is uncovered → `Deny`; direct and default paths now agree. Pinned by `crudTaskSetMissUnderDenyDefaultDenies` (red→green, runs in CI). → D3 (locked). |
| R4 | High (security foot-gun) | **Fixed (P2.1, D2)** | Was: the multi-group tie-break discarded explicit grants — 2+ group rows whose `upVoteInGroup` bias was unmet fell through to the **role default**, so two `Deny`s under an `Allow`-biased role resolved to `Allow`; the single-group case ignored `upVote`. Verified repro: `SingleAction`, `upVote=Allow`, `defaultPermission=Allow`, two `Deny` groups → `Allow` (the fix is in the shared group-conflict path, so the red→green pin exercises it via a `CrudTask` role; a `SingleAction` row may be added later for a sharper trail). **Fixed:** total deny-override default + `upVote=Allow` allow-override opt-in, uniform across single/multi-group; explicit grants never discarded. Pinned by `multiGroupDeniesAreHonoredNotDiscarded` + `mixedGroupGrantsAllowWinUnderAllowOverride` (red→green, runs in CI). → D2 (locked). |
| R5 | Medium-High (side-effecting read; contract tension) | Open — **PARTIAL (verified 2026-06-21)** | The lazy-provision **call site** is on the ungated check path: `findOne ?: insertCrudRole/insertSingleActionRole` (`MongoRolePermissionProvider.kt:41-44`, `CollPermission.kt:51-54`, `IRoleInUserColl.kt:146-149`), bypassing `allowApiCrud`/change-log/permission. **But the in-tree `insertSingleActionRole`/`insertCrudRole` are inert stubs returning `ItemState(isOk=false)` (`IAppRoleColl.kt:35-43`, no override in-tree)** — so a check performs **no write** unless a downstream consumer overrides them. The hazard is real (the mechanism invites an ungated write on the read path) but latent in-tree. → D4. |
| R6 | Medium (brittleness) | Open | `getGroupPermission` decodes aggregation output into the **file-private fixed-shape** `RoleInGroup`/`GroupOfUser` (`IRoleInUserColl.kt:326,353-366`), not the generic `GR`/`GOU`. Extra fields on a downstream `IRoleInGroup` impl are silently dropped; a shape mismatch fails at runtime, not compile time. → D5 (resolution port). |
| R7 | High (fail-open default) | **Decision locked (D6); impl P2.4** | Unconfigured deployments **fail open** at **three** sites: `SqlRepository.getCrudPermission` null-provider `isOk = true` (`SqlRepository.kt:444`); `InMemoryRepository.getCrudPermission` hard-`Ok` + its Action path never calling the check (`:409-412`, `:334-342`); **and Mongo `CollPermission.kt:38` `Coll.roleInUserColl ?: isOk = true`** (the third site, caught in review). D6 (locked) gates all three with the new `permissionEnforcement` declaration (default `Enforce` ⇒ deny when no resolver wired; `Off` opts out). → D6, P2.4. |
| R8 | High (unverified security code) | **Partially addressed (P1.1)** | Was: the entire resolution engine was untested (`getGroupPermission` had **0** test callers; `permissionState` was never invoked). P1.1's `RbacPermissionResolutionCharacterizationTest` now invokes `permissionState` and reaches `getGroupPermission`, freezing C1 root short-circuit, C2 direct-shadows-group (`Deny`/`Default`), C3 single-group + two-group `Deny` tie-break, and C4 default-inversion. **Remaining gaps:** C5 side-effect (P1.2), C6 cross-engine reach + fail-open (P1.3), the full `upVote`/`Allow`-side + SingleAction matrix, and a sample that wires RBAC (the lone pre-existing test `perActionPermissionParity` still asserts only for **SQL**). → P1.1–P1.3, P3.3. |
| R9 | Low (cleanliness) | Open | Dead duplication: the `when (roleType)` group-dispatch arms at `IRoleInUserColl.kt:227-247` are byte-identical (both call `getGroupPermission` with the same args). → P2 cleanup. |
| R10 | Low (convention/robustness) | Open | `IRoleInUserColl`/`IUserGroupColl`/`IRoleInGroupColl`/`IGroupOfUserColl`/`IAppRoleColl` are `abstract class`es wearing the `I` interface-prefix; provider registration is a construction side effect on a global mutable (`Coll.kt:1740-1744`). → D5. |
| R11 | Medium (testability/infra) | **Fixed (P1.0)** | Was: the five RBAC abstract colls took only `commonContainer` — unlike `CItemMongoRepository` they accepted **no** `MongoDbBuilder`, so a builder-less `Coll` fell back to the **`internal`** process-global `mongoDbBuilder` (`MongoDb.kt:14`, settable only by the Ktor `MongoDbPlugin`), blocking per-test Testcontainers isolation. P1.0 added an optional `mongoDbBuilder: MongoDbBuilder? = null` to all five colls (forwarded to `Coll`, default `null` = prior global behavior), so the characterization fixture now injects a shared per-test builder. Surfaced by characterization; related to R1 (Mongo-locality) and R10 (global mutable). → P1.0 (done). |
| R12 | **High (API gap — the empirical motivation)** | Open | No backend-agnostic, group-aware **boolean membership** query keyed by `(userId/User, appRoleId)`. fsLib's only group-aware entries — `getSingleActionPermission` (`IRoleInUserColl.kt:75/99/129`) and `permissionState` (`:166/192`) — take an `ApplicationCall` or `UserSession<UID>` (never a plain `userId`), identify the `AppRole` by `classOwner`/`funcName` (never a caller-supplied `appRoleId`), and return a `SimpleState` (never a bare `Boolean`). A consumer holding `userId` + `roleId` cannot ask "does U hold R", so a real Mongo consumer bypassed fsLib with a raw `countDocuments(RoleInUser by userId+appRoleId) > 0` and went **group-blind** — a role assigned via group (RoleInGroup + UserGroup, no direct row) is invisible and the user is wrongly denied. This is the review's empirical motivation. → D7/D8/D9. |
| R13 | Medium-High (robustness) | Open | The direct-row branch **materializes**: `coroutine.find(and(userId eq, appRoleId eq)).first()` (`IRoleInUserColl.kt:205-210`) deserializes the **whole** `RoleInUser` document, so a row whose `_id` (or any field) is not decodable throws at the check — the second reason the consumer moved to a non-materializing server-side `countDocuments`. A boolean existence query must use `countDocuments`/`limit(1)`/`$exists`, never `.first()`. → D9. |

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
