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
| R3 | High (security foot-gun + inconsistency) | Open | `buildDefaultAppRolePermission` **inverts** on a `crudTaskSet` miss: `Deny`-default + task-not-in-set ⇒ **Allow** (`IRoleInUserColl.kt:283-291`). The direct-row path yields **Deny** for the same miss (`:221`). Same condition, two code paths, opposite verdicts. → D3. |
| R4 | High (security foot-gun) | Open | Multi-group tie-break discards explicit grants: when 2+ group rows match but the `upVoteInGroup` bias condition is unmet (e.g. bias `Allow` but every group says `Deny`), it falls through to the **role default** rather than honoring the explicit denies — two `Deny`s can resolve to `Allow` (`IRoleInUserColl.kt:341-347`). With exactly one group row, `upVote` is ignored entirely (`:336-340`). → D2. |
| R5 | Medium-High (side-effecting read; contract tension) | Open | A permission **check** performs a DB **write**: `findOne ?: insertCrudRole/insertSingleActionRole` lazily provisions the `AppRole` doc (`MongoRolePermissionProvider.kt:39-41`, `CollPermission.kt:49-51`, `IRoleInUserColl.kt:136-138`). The insert bypasses `allowApiCrud`, change-logging, and per-action permission, and is **not** among the [[repository-write-lifecycle]] CONTRACT's catalogued raw writers (I7). → D4. |
| R6 | Medium (brittleness) | Open | `getGroupPermission` decodes aggregation output into the **file-private fixed-shape** `RoleInGroup`/`GroupOfUser` (`IRoleInUserColl.kt:326,353-366`), not the generic `GR`/`GOU`. Extra fields on a downstream `IRoleInGroup` impl are silently dropped; a shape mismatch fails at runtime, not compile time. → D5 (resolution port). |
| R7 | High (fail-open default) | Open | Unconfigured deployments **fail open**: `SqlRepository.getCrudPermission` returns `isOk = true` when no provider is registered (`SqlRepository.kt:444`); `InMemoryRepository.getCrudPermission` is a hard-coded `Ok` that never consults the registry (`:409-412`) and its Action write path doesn't even call it (`:334-342`). A security control that defaults to allow-all on a path the app believes is protected. → D6. |
| R8 | High (unverified security code) | **Partially addressed (P1.1)** | Was: the entire resolution engine was untested (`getGroupPermission` had **0** test callers; `permissionState` was never invoked). P1.1's `RbacPermissionResolutionCharacterizationTest` now invokes `permissionState` and reaches `getGroupPermission`, freezing C1 root short-circuit, C2 direct-shadows-group (`Deny`/`Default`), C3 single-group + two-group `Deny` tie-break, and C4 default-inversion. **Remaining gaps:** C5 side-effect (P1.2), C6 cross-engine reach + fail-open (P1.3), the full `upVote`/`Allow`-side + SingleAction matrix, and a sample that wires RBAC (the lone pre-existing test `perActionPermissionParity` still asserts only for **SQL**). → P1.1–P1.3, P3.3. |
| R9 | Low (cleanliness) | Open | Dead duplication: the `when (roleType)` group-dispatch arms at `IRoleInUserColl.kt:227-247` are byte-identical (both call `getGroupPermission` with the same args). → P2 cleanup. |
| R10 | Low (convention/robustness) | Open | `IRoleInUserColl`/`IUserGroupColl`/`IRoleInGroupColl`/`IGroupOfUserColl`/`IAppRoleColl` are `abstract class`es wearing the `I` interface-prefix; provider registration is a construction side effect on a global mutable (`Coll.kt:1740-1744`). → D5. |
| R11 | Medium (testability/infra) | **Fixed (P1.0)** | Was: the five RBAC abstract colls took only `commonContainer` — unlike `CItemMongoRepository` they accepted **no** `MongoDbBuilder`, so a builder-less `Coll` fell back to the **`internal`** process-global `mongoDbBuilder` (`MongoDb.kt:14`, settable only by the Ktor `MongoDbPlugin`), blocking per-test Testcontainers isolation. P1.0 added an optional `mongoDbBuilder: MongoDbBuilder? = null` to all five colls (forwarded to `Coll`, default `null` = prior global behavior), so the characterization fixture now injects a shared per-test builder. Surfaced by characterization; related to R1 (Mongo-locality) and R10 (global mutable). → P1.0 (done). |

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
