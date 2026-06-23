# PLAN — RBAC Permission Resolution

> Ordered execution. **SAFE** = no authorization outcome observable by a downstream app/override
> changes. **BREAKING** = changes a resolution verdict or an override contract (each cites
> `CONTRACT.md` + a `LEDGER.md` entry; ships behind a major-signal bump). Type =
> `construction` | `design`. "Discharges" links each step to the findings (BRIEF R-series) and
> decisions (LEDGER) it closes.
>
> **Hard ordering rule:** no Phase-2 step lands before Phase 1 (characterization) is green, and no
> Phase-2 step lands before its governing decision (D1–D6) is **locked** in the LEDGER.

## Dependency graph (must-precede)

- **G1 (blueprint)** precedes everything — it is the first commit (D0).
- **P1.1/P1.2 (characterization)** precede **all** of Phase 2 — they freeze the C-series so each
  Phase-2 verdict change is a visible, intended diff.
- **DG (decision gate)** precedes Phase 2 — D1–D6 must be locked first.
- **P3.1 (resolution port, D5)** precedes the cross-engine pins of P2.1–P2.4 becoming *Enforced*
  across non-Mongo engines — until the port exists, only Mongo can be pinned.

---

## Phase 0 — Governance · SAFE · design

| ID | Step | Discharges | Status |
|----|------|-----------|--------|
| **G1** | This blueprint set (BRIEF, CONTRACT, LEDGER, PLAN) + INDEX row. **First commit, blueprint only.** | D0, root cause (write it down) | ☐ pending approval to commit |

## Phase 1 — Characterization · SAFE · construction (freeze current behavior; ships within a minor)

> Goal: make the current Mongo resolution behavior — **including the defects** — executable and
> green, so Phase 2's changes are diffs against a known baseline, not edits in the dark. These tests
> assert *what is*, not *what should be*; several will be **intentionally inverted** in Phase 2 (the
> red→green tripwire), and each such assertion is tagged `// CHARACTERIZATION: changes at P2.x`.

| ID | Step | File anchors | Discharges | Status |
|----|------|--------------|-----------|--------|
| **P1.0** | **Testability enabler (SAFE) → option (a), the cathedral choice (2026-06-21).** Added an optional `mongoDbBuilder: MongoDbBuilder? = null` param to the five RBAC abstract colls, forwarded to `Coll` — zero behavior change (default `null` = prior process-global resolution), mirrors `CItemMongoRepository`, removes the only `Coll` subtypes that omitted builder-injection (general-problem + clean-abstraction + established-pattern over the global mutable, R10/R11). Rejected (b) Ktor-plugin global config: novel scaffolding doubling down on the global, order-sensitive shared DB. **SemVer: minor (additive optional param).** Unblocks P1.1–P1.3. | `IAppRoleColl`/`IRoleInUserColl`/`IUserGroupColl`/`IRoleInGroupColl`/`IGroupOfUserColl` ctors | R11 | ✅ done — compile-verified; full `:conformance` suite green (memory 11/11, SQL 11/11), no regression |
| **P1.1** | **Characterization suite** for `IRoleInUserColl.permissionState` against a real mongod (Testcontainers, reuse the [[repository-write-lifecycle]] D11 fixture). Landed: `RbacPermissionResolutionCharacterizationTest` (6 tests) freezing C1 root short-circuit; C2 direct-row shadows group for `Deny` **and** `Default` (the D1-ratified statement, re-confirmed on master 2026-06-21); C3 single-group `upVote`-ignored **and** the two-group discard-denies fall-through (R4); C4 default-inversion on a `crudTaskSet` miss (R3). Compile-verified; **green-or-skip locally (no Docker), runs in CI** (D11 gate). | `conformance/.../RbacPermissionResolutionCharacterizationTest.kt`; `IRoleInUserColl.kt` | R8, C1–C4 | ◑ C1–C4 frozen; C6 cross-engine reach + the full `upVote` Allow-side rows still to add |
| **P1.2** | **Characterize the side effect (C5) — hook-invocation, not a DB write.** In-tree `insert*` are inert stubs, so characterize the real hazard: a test `IAppRoleColl` subclass overrides `insertCrudRole`/`insertSingleActionRole` to **record invocation** (a probe flag); assert that a permission check on a **missing** role **invokes the provisioning hook**. (Land this in the same slice as P2.3, ahead of the flip — red→green discipline.) | `IAppRoleColl.kt`, `IRoleInUserColl.kt`/`CollPermission.kt`/`MongoRolePermissionProvider.kt`; new probe test | R5, C5, D4 | ✅ done (committed `c5a09a6c`) |
| **P1.3** | **Permission-resolution conformance profile** scaffold in `:conformance`: engine-agnostic assertion shells for the T-series, **assume-gated** per engine until P3.1 gives non-Mongo engines a resolution path (no committed failing tests; mirrors [[repository-write-lifecycle]] P1.8 discipline). | `:conformance` | R8, T1/T2 scaffolding | ☐ |

**Recommended approval boundary:** land G1 (blueprint) as commit 1; land P1.1–P1.3 (characterization,
SAFE) as commit 2. Stop there and **lock D1–D6** before any Phase-2 code.

## Decision gate

| ID | Step | Discharges | Status |
|----|------|-----------|--------|
| **DG** | Resolve and lock the decision set in `LEDGER.md`. **D1–D6 LOCKED** 2026-06-21: D1 user precedence; D2 total deny-override + `upVote` opt-in; D3 allow-list no-inversion (D2/D3 shipped P2.1/P2.2); **D4** side-effect-free + `ensureRoles()` (shipped P2.3); **D5** split algebra + typed grant-fetch port (shipped P3.1a/P3.1b); **D6** fail-closed-unless-`Off` via `permissionEnforcement` across SQL + InMemory + Mongo (shipped P2.4; the Mongo gate moved to `Coll.getCrudPermission` in P3.2b). **D7–D9 LOCKED** 2026-06-22 (membership): D7=ship-both, D8=existence≠authz, D9=typed-port/non-materializing — **shipped P4** (`RbacMembership`). **D10 LOCKED** 2026-06-22 (registration mechanism = explicit boot registrar; impl P3.2a). | D1–D10 ✅ | ✅ all decisions locked; D2–D6 shipped, D5 shipped (P3.1a/b), D7–D9 shipped (P4); D10 shipped (P3.2a) |

> **Priority callout — DONE.** The two **security-critical** fixes shipped 2026-06-21: **P2.1 (D2, the
> deny-dropping multi-group tie-break, R4)** and **P2.2 (D3, the `crudTaskSet`-miss inversion, R3)** —
> both had verified concrete unsafe-`Allow` reproductions, now closed and pinned by the two red→green
> tests (compile-verified; runtime confirmation in CI, no Docker locally). **BREAKING** (resolution
> verdicts change for affected configs → major-signal bump at release). Done ahead of the membership work.

## Phase 2 — Semantics · BREAKING · construction (one deliberate decision per locked Dx; major-signal)

> Each step is gated on its decision being locked and may shrink/expand to match the chosen option.
> Listed assuming the recommended options; re-scope if a different option is locked.

| ID | Step | File anchors | Discharges | Status |
|----|------|--------------|-----------|--------|
| **P2.1** | **Intra-group totality + conflict rule (D2).** D1 is **user-precedence-ratified**, so the direct-row short-circuit (C2) is **kept unchanged**. Touches only the group path: total deny-override + `upVote==Allow` allow-override opt-in, single-group uses the same rule as multi-group, explicit grants never discarded (closes R4). Former foot-gun test flipped red→green (`multiGroupDeniesAreHonoredNotDiscarded`). | `IRoleInUserColl.kt` `getGroupPermission` (→ shared algebra after P3.1) | R4, D2, T1, T5 (ratified) | ✅ done (compile-verified; Mongo test runs red→green in CI) |
| **P2.2** | **`crudTaskSet`-miss unification (D3).** Removed the default-path inversion; direct and default paths now agree (allow-list: miss ⇒ `Deny`). Former foot-gun test flipped red→green (`crudTaskSetMissUnderDenyDefaultDenies`). | `IRoleInUserColl.kt` `buildDefaultAppRolePermission` | R3, D3, T1 | ✅ done (compile-verified; Mongo test runs red→green in CI) |
| **P2.3** | **Side-effect-free resolution (D4).** (1) Remove the lazy `?: insert*` from the three check sites (`IRoleInUserColl.kt:146-149`, `MongoRolePermissionProvider.kt:41-44`, `CollPermission.kt:51-54`) → missing role denies; (2) add `IAppRoleColl.ensureRoles(containers, singleActions): SimpleState` boot entry point; (3) demote `insert*` to provisioning primitives `ensureRoles()` calls. Flip the P1.2 probe red→green (missing role denies **and** hook **not** invoked). **BREAKING** for downstream overriders of `insert*` (migration: call `ensureRoles()` at boot). | `IAppRoleColl.kt`, `IRoleInUserColl.kt`, `MongoRolePermissionProvider.kt`, `CollPermission.kt`; probe test | R5, D4, T3 | ✅ done (committed `a20f28be`; `RbacEnsureRolesTest` green locally, Mongo flip red→green in CI, adversarial-verified) |
| **P2.4** | **Fail-closed default (D6).** Add core enum `PermissionEnforcement { Enforce, Off }` + `IRepository.permissionEnforcement` (default `Enforce`). Gate all three fail-open sites with it: SQL `:444`, Mongo `CollPermission.kt:38` (`roleInUserColl == null`), InMemory `:409-412` + add the missing Action-branch check (`:334-342`). `InMemoryRepository` overrides to `Off` (named non-enforcing engine mode). Conformance `enforcesPermissions` stays separate (derive from `permissionEnforcement` only where the harness can drive enforcement — SQL now). | core enum; `IRepository.kt`; `SqlRepository.kt`; `CollPermission.kt`; `InMemoryRepository.kt`; conformance Mongo repos `Off` + D6 test | R7, D6, T4 | ✅ done (committed `658a6226`; Memory 12/12, SQL 12/12 incl. `unconfiguredDefaultFailsClosedForEnforcingEngines`, SSR 53/0; Mongo in CI) |
| **P2.5** | **Cleanup (SAFE).** Collapsed the byte-identical `when (roleType)` group-dispatch arms to a single expression. | `IRoleInUserColl.kt` `permissionState` group dispatch | R9 | ✅ done (behavior-identical; suite green) |

## Phase 3 — Abstraction · mostly SAFE-additive · construction

| ID | Step | File anchors | Discharges | Risk | Status |
|----|------|--------------|-----------|------|--------|
| **P3.1** | **Resolution port (D5=c) — design checkpoint signed off 2026-06-22.** Sliced: **P3.1a** (this slice) + P3.1b. **Types (`core`):** `RoleGrant(permission, crudTaskSet?)` and `AppRolePolicy(id, roleType, defaultPermission, defaultCrudTaskSet?, upVoteInGroup)` — note **`id` carried explicitly** so the resolver can fetch grants (reviewer P1). **Port (`fullstack` jvmMain):** `IRbacGrantPort` — `isRootUser`, `fetchDirectGrant`, `fetchGroupGrants` (typed, closes R6) **+** `existsDirectGrant`/`existsGroupGrant` (the Phase-4-ready boolean fast path — interface present, only typed-fetch *wired* in P3.1a; reviewer P2). **Pure resolver (`fullstack`/`core`):** the D1–D4 algebra, **free of `ApplicationCall`** (movable/testable; reviewer decision 1). Mongo implements the port (the `$lookup` behind `fetchGroupGrants`); `permissionState`/`getSingleActionPermission` **delegate** to the resolver (behavior preserved, pinned by the characterization + conformance suites). Also fix the stale `PermissionRegistry` KDoc ("null ⇒ allowed" — false since P2.4; reviewer P3). **Registration unchanged** (keep `PermissionRegistry`; R10 deferred to P3.2). **SQL deferred** — native RBAC needs new tables (separate sub-blueprint); SQL stays fail-closed/`Off` (D6). | `core` grant/policy types; `fullstack` `IRbacGrantPort` + resolver; `mongodb` port impl + delegation; `IRolePermissionProvider.kt` KDoc | R1, R6, D5, T2 | **P3.1a SAFE** (internal refactor; behavior pinned) | ◑ **P3.1a done (`0b89b34e`)** — `RbacResolver` + `IRbacGrantPort` + `RoleGrant`/`AppRolePolicy`; Mongo delegates; group pipeline `$project`-shaped to `RoleGrant`; 13 no-DB resolver tests + suite green; closes R6. **P3.1b done** — `InMemoryRbacGrantPort` (real in-heap user→group join) + `InMemoryRbacGrantPortTest` (13/13, incl. a membership-gates-the-join proof): the resolver is now pinned over **two real ports** (Mongo in CI + InMemory locally). **Next:** P3.2 (explicit registration, R10) / native SQL (separate sub-blueprint). |
| **P3.2a** | **Explicit registration mechanism (R10 core, D10=a).** Replace the construction **side-effect** (`Coll.kt:1740-1744`, the `if (this is IRoleInUserColl)` block that sets **both** `Coll.roleInUserColl` and `PermissionRegistry.rolePermissionProvider`) with an **explicit boot registrar**: a mongodb-module entry point `MongoRbac.register(roleInUserColl)` that the app calls once at boot and which sets both globals deliberately. **Both dispatch paths unchanged** (Mongo via `Coll.roleInUserColl`/`CollPermission`; SQL/InMemory via the registered provider) — only *how the holders are populated* changes. Preserve fail-closed (D6): no registration ⇒ both null ⇒ `permissionEnforcement` governs (Enforce ⇒ deny). Update the `PermissionRegistry` KDoc (explicit registration required, no auto-wire). Migration: downstream apps + in-repo consumers (samples, media, conformance fixtures) call `MongoRbac.register(...)` at boot (analogous to D4 `ensureRoles()`). | `Coll.kt` init + companion; new `MongoRbac` (mongodb); `IRolePermissionProvider.kt` KDoc; conformance fixtures | R1, R10, D10 | BREAKING (registration API) | ✅ **done** — `MongoRbac.register`/`unregister`/`isRegistered`; `Coll.init` side-effect removed (+ unused import); `PermissionRegistry`/companion KDocs updated; D10 pin asserts registration via `isRegistered` (at P3.2a this wired the agnostic provider **and** the `Coll.roleInUserColl` companion — **P3.2b later collapsed both to the single `PermissionRegistry` handle**, and the pin with them). Fail-closed preserved (D6); in-repo blast radius = the test fixture only (no app/sample constructs an `IRoleInUserColl`) — downstream apps add one `MongoRbac.register(...)` boot call |
| **P3.2b** | **Unify the two dispatch paths (collapse the split-brain).** Mongo's own `getCrudPermission` now routes through the **same** registered `PermissionRegistry` provider SQL/InMemory use (its body is exactly SQL's), so the conformance harness drives the live Mongo path — Mongo `enforcesPermissions` flipped to **`true`**. The second global (`Coll.roleInUserColl` companion) and the duplicate `CollPermission` are **deleted** (resolution lives only in `MongoRolePermissionProvider`); `MongoRbac` collapses to the single handle. The change-log RBAC exemption is realized declaratively as `IChangeLogColl.permissionEnforcement = Off` (replacing the special-case `isSubclassOf` check). | `Coll.kt` `getCrudPermission`; `CollPermission.kt` (deleted); `IChangeLogColl.kt` (`Off`); `MongoRbac.kt`; conformance Mongo profile + exemption pin | R1, D5/T2, R10 | **SAFE-for-public-API** (proven: every changed/removed symbol — `Coll.roleInUserColl`, `CollPermission`, the companion-set — is `internal`; a registered app resolves identically, an unregistered app still fails closed) | ✅ **done** — unified dispatch; companion + `CollPermission` deleted; `IChangeLogColl` declares `Off`; Mongo `enforcesPermissions=true` (CI now drives the live Mongo permission path); `MongoChangeLogExemptionTest` pins the exemption **locally** (no Docker); full suite green/skip-clean |
| **P3.2c** | **Widen the agnostic surface to single-action + group.** Bring `RbacMembership` (and the single-action/group concepts) onto the registered, engine-agnostic surface so non-Mongo engines can answer them — gated on each engine having a port (Mongo ✅; InMemory = wire the orphan `InMemoryRbacGrantPort`; SQL = P4.4). | `IRepository`/registered surface; `InMemoryRepository`; `:conformance` | R1, R10, D5 | BREAKING (surface) | ☐ |
| **P3.3** | **Demonstrate RBAC in a sample** (none exists today): one sample wiring user + group grants end-to-end, exercising composition + conflict rule. | `samples/**` | R8 (visibility) | SAFE (additive) | ☐ |

## Phase 4 — Group-aware SingleAction membership API · mostly SAFE-additive (rides the D5 port; gated on D7–D9)

> The membership API is a **new operation** (D7), not a change to `permissionState`. Its Mongo
> implementation is the Mongo half of the D5=c grant-fetch port — **zero throwaway**: building it
> "inside" the port (P4.x) doubles as P3.1's Mongo impl.
>
> **As built (2026-06-22):** P4.1 + P4.2 + P4.3 + the conformance half of P4.5 landed as a
> **single slice** — `RbacMembership` (`fullstack` jvmMain) with `hasSingleActionGrant` (T6a) and
> `isAllowedSingleAction` (T6b = `RbacResolver.resolve(crudTask=null)`, **not** a union); a new
> `IRbacGrantPort.fetchAppRolePolicy` (non-provisioning, D4) so T6b is keyed purely by `appRoleId`; Mongo
> + InMemory port impls; Mongo entry points on `IRoleInUserColl`. Tests: `RbacMembershipTest` (6 InMemory)
> + the Mongo `groupOnlyMembershipIsSeenByBothOperationsOnMongo` (CI). **Deferred:** P4.4 (native SQL RBAC
> — the honest gap) and the P4.5 **sample** (rides P3.3).

| ID | Step | File anchors | Discharges | Risk | Status |
|----|------|--------------|-----------|------|--------|
| **P4.1** | **Membership port + two algebras (D5=c, D7–D9, T6).** In `fullstack`/`core` add the **typed** grant-fetch port (the same D5=c port): existence fast path `existsDirectGrant`/`existsGroupGrant: Boolean`, **and** typed summaries `fetchDirectGrant(userId, appRoleId): GrantSummary?` / `fetchGroupGrants(userId, appRoleId): List<GrantSummary>` (`{permission, crudTaskSet}`). Two algebras: **T6a `hasSingleActionGrant`** = `existsDirect || existsGroup`; **T6b `isAllowedSingleAction`** = the **`permissionState` precedence resolution restricted to SingleAction** over the typed port (direct authoritative; else D2 intra-group) — **same engine as P3.1**, not a deny-override union. A boolean-only port is explicitly rejected (can't carry permission/defaults/upVote for T6b). | new typed port + 2 algebras; `fullstack` surface | R12, D7, D8, D9, T6 | additive (SAFE) | ✅ done — `RbacMembership` (`hasSingleActionGrant` = `existsDirect∥existsGroup`; `isAllowedSingleAction` = `RbacResolver.resolve(crudTask=null)`); port gained `fetchAppRolePolicy`; reuses the P3.1 typed `RoleGrant` fetches |
| **P4.2** | **Mongo impl — non-materializing (D9, R13).** Existence: `existsDirectGrant` = `countDocuments(and(userId,appRoleId)) > 0`; `existsGroupGrant` = the `getGroupPermission` aggregation terminated at `limit(1)`/count. Typed: `fetchDirectGrant` projects only `{permission, crudTaskSet}` of the direct row (projection + `limit(1)`, **never** `.first()` on the full doc → can't crash on a bad `_id`, R13); `fetchGroupGrants` projects the same from the group aggregation (no `replaceRoot`/private-class decode → sidesteps R6). Keyed by `appRoleId`, no `AppRole` provisioning. **P4.2 + T6a is the consumer's fix.** | `mongodb/.../IRoleInUserColl.kt` (new queries) → port impl | R12, R13, R6, D9 | additive (SAFE) | ✅ done — `existsDirectGrant` = `countDocuments>0`; `existsGroupGrant` = shared `buildGroupGrantPipeline` + `limit(1)`; `fetchAppRolePolicy` = `findOne` + `AppRolePolicy.of` (non-provisioning). **Both ops non-materializing (R13):** `fetchDirectGrant` was changed from `find(...).first()` on the full doc to a `match → $limit(1) → $project {permission, crudTaskSet}` pipeline (also hardens the shared `permissionState` path). |
| **P4.3** | **InMemory impl (D5/D6).** Two in-heap `any { }` predicates over RoleInUser/RoleInGroup/UserGroup; requires an RBAC-aware holder (InMemory stores one entity type per repo today). Fail-closed when unconfigured (D6). | `memorydb/.../InMemoryRepository.kt` (or new RBAC holder) | R1, D5, D6 | additive (SAFE) | ✅ done — landed as `InMemoryRbacGrantPort` in P3.1b (real in-heap user→group join); P4 added `fetchAppRolePolicy`/`putAppRolePolicy`, so `RbacMembership` runs natively on it (the `RbacMembershipTest` port) |
| **P4.4** | **SQL impl — the honest gap (D5/D6).** SQL has **no** RoleInUser/RoleInGroup/UserGroup tables or abstraction today; native membership needs those tables + an `EXISTS … OR EXISTS` query first. **Interim:** SQL declares membership **unsupported / fail-closed** (D6), not silent allow-all (R7). Full native SQL RBAC may be its own sub-blueprint. | `sql/.../SqlRepository.kt`; new SQL RBAC tables (deferred) | R1, R7, D6 | interim fail-closed; native = larger | ☐ **deferred** — no SQL RBAC port; `RbacMembership` is unreachable from SQL (no `IRbacGrantPort` impl). SQL stays fail-closed/`Off` (D6). Native SQL RBAC = separate sub-blueprint |
| **P4.5** | **Membership conformance + sample.** Cross-engine membership tests (extend the `enforcesPermissions` seam) exercising **group-assigned, no-direct-row** grants on every claiming engine — the exact bypass scenario; plus the P3.3 sample wires a group-only grant and calls the membership API. | `:conformance`; `samples/**` | R8, R12 | SAFE (additive) | ◑ **conformance done** — `RbacMembershipTest` (6 InMemory, incl. the group-only bypass) + the Mongo `groupOnlyMembershipIsSeenByBothOperationsOnMongo` (CI). **Sample deferred** (rides P3.3, not yet built) |

## Immediate next action

G1 committed (`01a0084b`). **D1 locked** 2026-06-21 (user precedence — group < direct user);
direct-shadows-group **re-confirmed on master** (UPHELD, all three `PermissionType` values incl.
`Default`). **P1.0 landed** (option a, the cathedral choice): SAFE-additive `mongoDbBuilder` param on
the five RBAC colls — full `:conformance` suite green (memory 11/11, SQL 11/11), no regression.
**P1.1 landed**: `RbacPermissionResolutionCharacterizationTest` freezes C1–C4 (6 tests,
compile-verified, skip-clean locally, runs in CI; committed `5bcdbd5e`/`bf2cf035`).

**Membership review (2026-06-21)** added findings R12 (the empirical group-blind bypass — no
`(userId, appRoleId)` boolean) and R13 (direct-row materialization), corrected R5 to PARTIAL (in-tree
`insert*` are inert stubs), narrowed R8, and added decisions **D7–D9** + invariant **T6** + **Phase 4**
(membership API). The two security foot-guns (R3/R4) now have verified concrete unsafe-`Allow`
reproductions — see the Priority callout.

**D2/D3 locked and shipped (P2.1/P2.2)** — the two security foot-guns are closed in
`IRoleInUserColl.kt`, the two former characterization tests flipped red→green, the full `:conformance`
suite is green/skip-clean (memory 11/11, SQL 11/11; Mongo runs in CI), no regression. **BREAKING** —
needs a major-signal version bump at release (release/version is the user's call).

**D4/D5/D6 LOCKED (2026-06-21)** with reviewer-corrected text (D6's `permissionEnforcement` covers all
three engines incl. the Mongo gate (`CollPermission.kt:38` then; `Coll.getCrudPermission` since P3.2b); D4's `ensureRoles()` surface + hook-invocation test
story; conformance flag kept separate from product policy until P3.1).

**P2.3 (D4) committed `a20f28be`. P2.4 (D6) implemented** — core enum `PermissionEnforcement { Enforce,
Off }` + defaulted `IRepository.permissionEnforcement`; the three fail-open sites (SQL/Mongo/InMemory)
now fail closed unless `Off`; `InMemoryRepository` declares `Off` + its Action path calls the check; the
conformance Mongo repos declared `Off` (since reversed — they **enforce** after P3.2b); new
`unconfiguredDefaultFailsClosedForEnforcingEngines` pins it.
Full suite green (Memory 12/12, SQL 12/12, SSR 53/0; Mongo in CI). **BREAKING** (unconfigured deployments
flip allow-all → fail-closed).

**P2.4 (D6) committed `658a6226`; P3.1a/P3.1b (D5) shipped** — the resolver runs over two real ports
(Mongo CI + InMemory local).

**D7–D9 LOCKED 2026-06-22 and P4 (membership API) implemented — the consumer's group-blind fix.** New
`RbacMembership` (`fullstack` jvmMain) over the D5 port: `hasSingleActionGrant` (existence: direct **OR**
group) and `isAllowedSingleAction` (effective authz = `RbacResolver.resolve(crudTask=null)`, **not** a
union — a direct `Allow` beats a group `Deny`, D1/T5). `IRbacGrantPort` gained `fetchAppRolePolicy`
(non-provisioning, D4) so authz is keyed by `appRoleId` alone. Mongo + InMemory port impls + entry points.
Suite green (`RbacMembershipTest` 6/6, Memory 12/12, SQL 12/12, `RbacResolverTest` 13/13,
`InMemoryRbacGrantPortTest` 13/13; Mongo membership test skips locally, runs CI). **SAFE-additive.**

**The P4 slice comprises:** `RbacMembership.kt` (new), `RbacMembershipTest.kt` (new),
`IRbacGrantPort.kt` (+`fetchAppRolePolicy`), `InMemoryRbacGrantPort.kt` (+`fetchAppRolePolicy`/
`putAppRolePolicy`), `IRoleInUserColl.kt` (+entry points + Mongo `fetchAppRolePolicy` + the
`fetchDirectGrant` server-side projection, D9/R13), `RbacPermissionResolutionCharacterizationTest.kt`
(+Mongo membership test), `RbacResolverTest.kt` (FakeGrantPort `fetchAppRolePolicy` override), and the
blueprint status updates (LEDGER D7–D9, BRIEF R12/R13, CONTRACT T6, PLAN Phase 4/DG, INDEX row).

**Next, in order:** (1) **P3.2** explicit registration (R10, BREAKING) and/or **P3.3**
sample (which P4.5's sample rides); (2) **P4.4** native SQL RBAC port — the honest gap (separate
sub-blueprint). SQL membership stays fail-closed/`Off` until then (D6).
