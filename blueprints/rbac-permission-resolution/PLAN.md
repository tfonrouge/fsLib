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
| **P1.2** | **Characterize the side effect (C5):** a permission check on an unprovisioned role inserts an `AppRole` row (assert the write happens today). Tag for D4. | `MongoRolePermissionProvider.kt`, `CollPermission.kt`, `IAppRoleColl.kt` | R5, C5 | ☐ |
| **P1.3** | **Permission-resolution conformance profile** scaffold in `:conformance`: engine-agnostic assertion shells for the T-series, **assume-gated** per engine until P3.1 gives non-Mongo engines a resolution path (no committed failing tests; mirrors [[repository-write-lifecycle]] P1.8 discipline). | `:conformance` | R8, T1/T2 scaffolding | ☐ |

**Recommended approval boundary:** land G1 (blueprint) as commit 1; land P1.1–P1.3 (characterization,
SAFE) as commit 2. Stop there and **lock D1–D6** before any Phase-2 code.

## Decision gate

| ID | Step | Discharges | Status |
|----|------|-----------|--------|
| **DG** | Resolve and lock the decision set in `LEDGER.md` (pick option per decision; set APPROVED/REJECTED; non-empty falsification each). **D1/D2/D3 LOCKED** (D1 user precedence; D2 = deny-override + `upVote` opt-in, total; D3 = allow-list no-inversion) — D2/D3 shipped P2.1/P2.2. Remaining recommendations on record: D4=b, D5=c, D6=b; **membership** D7=ship-both, D8=as-stated (existence ≠ authz; effective authz **is** the D1/T5 precedence resolution, not a deny-override union), D9=as-stated (typed port, non-materializing). | D1/D2/D3 ✅ · D4–D9 | ◑ D1/D2/D3 done; D4–D9 pending lock-down |

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
| **P2.3** | **Side-effect-free resolution (D4).** Remove lazy `AppRole` provisioning from the check path; add the explicit provisioning path (registration/migration/`ensureRoles()`); flip the P1.2 characterization assertion. | `MongoRolePermissionProvider.kt`, `CollPermission.kt`, `IAppRoleColl.kt`, registration site | R5, D4, T3 | ☐ |
| **P2.4** | **Fail-closed default (D6).** SQL/InMemory fail closed on protected paths unless an explicit permission-free mode is declared; InMemory Action path actually invokes the check. | `SqlRepository.kt`, `InMemoryRepository.kt`, `IRolePermissionProvider.kt` | R7, D6, T4 | ☐ |
| **P2.5** | **Cleanup (SAFE).** Collapse the byte-identical `when (roleType)` group-dispatch arms. | `IRoleInUserColl.kt:227-247` | R9 | ☐ |

## Phase 3 — Abstraction · mostly SAFE-additive · construction

| ID | Step | File anchors | Discharges | Risk | Status |
|----|------|--------------|-----------|------|--------|
| **P3.1** | **Resolution port (D5=c).** Extract the pure resolution algebra (D1–D4) into `core`/`fullstack`; define a per-engine grant-fetch port returning **typed** grants (closes the R6 generic-type loss); Mongo's `$lookup` becomes one port impl behind the algebra. Flip the P1.3 conformance shells to live assertions across every claiming engine. | new agnostic resolver + port; `IRolePermissionProvider` surface; `Coll`/`SqlRepository`/`InMemory` port impls | R1, R6, R10, D5, T2 | additive (SAFE) except the SQL move | ☐ |
| **P3.2** | **Explicit registration (R10).** Replace the construction-side-effect, global-mutable provider registration with explicit registration; single-action + group concepts join the agnostic surface. | `Coll.kt:1740-1744`, `PermissionRegistry` | R1, R10, D5 | BREAKING (registration API) | ☐ |
| **P3.3** | **Demonstrate RBAC in a sample** (none exists today): one sample wiring user + group grants end-to-end, exercising composition + conflict rule. | `samples/**` | R8 (visibility) | SAFE (additive) | ☐ |

## Phase 4 — Group-aware SingleAction membership API · mostly SAFE-additive (rides the D5 port; gated on D7–D9)

> The membership API is a **new operation** (D7), not a change to `permissionState`. Its Mongo
> implementation is the Mongo half of the D5=c grant-fetch port — **zero throwaway**: building it
> "inside" the port (P4.x) doubles as P3.1's Mongo impl.

| ID | Step | File anchors | Discharges | Risk | Status |
|----|------|--------------|-----------|------|--------|
| **P4.1** | **Membership port + two algebras (D5=c, D7–D9, T6).** In `fullstack`/`core` add the **typed** grant-fetch port (the same D5=c port): existence fast path `existsDirectGrant`/`existsGroupGrant: Boolean`, **and** typed summaries `fetchDirectGrant(userId, appRoleId): GrantSummary?` / `fetchGroupGrants(userId, appRoleId): List<GrantSummary>` (`{permission, crudTaskSet}`). Two algebras: **T6a `hasSingleActionGrant`** = `existsDirect || existsGroup`; **T6b `isAllowedSingleAction`** = the **`permissionState` precedence resolution restricted to SingleAction** over the typed port (direct authoritative; else D2 intra-group) — **same engine as P3.1**, not a deny-override union. A boolean-only port is explicitly rejected (can't carry permission/defaults/upVote for T6b). | new typed port + 2 algebras; `fullstack` surface | R12, D7, D8, D9, T6 | additive (SAFE) | ☐ |
| **P4.2** | **Mongo impl — non-materializing (D9, R13).** Existence: `existsDirectGrant` = `countDocuments(and(userId,appRoleId)) > 0`; `existsGroupGrant` = the `getGroupPermission` aggregation terminated at `limit(1)`/count. Typed: `fetchDirectGrant` projects only `{permission, crudTaskSet}` of the direct row (projection + `limit(1)`, **never** `.first()` on the full doc → can't crash on a bad `_id`, R13); `fetchGroupGrants` projects the same from the group aggregation (no `replaceRoot`/private-class decode → sidesteps R6). Keyed by `appRoleId`, no `AppRole` provisioning. **P4.2 + T6a is the consumer's fix.** | `mongodb/.../IRoleInUserColl.kt` (new queries) → port impl | R12, R13, R6, D9 | additive (SAFE) | ☐ |
| **P4.3** | **InMemory impl (D5/D6).** Two in-heap `any { }` predicates over RoleInUser/RoleInGroup/UserGroup; requires an RBAC-aware holder (InMemory stores one entity type per repo today). Fail-closed when unconfigured (D6). | `memorydb/.../InMemoryRepository.kt` (or new RBAC holder) | R1, D5, D6 | additive (SAFE) | ☐ |
| **P4.4** | **SQL impl — the honest gap (D5/D6).** SQL has **no** RoleInUser/RoleInGroup/UserGroup tables or abstraction today; native membership needs those tables + an `EXISTS … OR EXISTS` query first. **Interim:** SQL declares membership **unsupported / fail-closed** (D6), not silent allow-all (R7). Full native SQL RBAC may be its own sub-blueprint. | `sql/.../SqlRepository.kt`; new SQL RBAC tables (deferred) | R1, R7, D6 | interim fail-closed; native = larger | ☐ |
| **P4.5** | **Membership conformance + sample.** Cross-engine membership tests (extend the `enforcesPermissions` seam) exercising **group-assigned, no-direct-row** grants on every claiming engine — the exact bypass scenario; plus the P3.3 sample wires a group-only grant and calls the membership API. | `:conformance`; `samples/**` | R8, R12 | SAFE (additive) | ☐ |

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

**Next, in order:** (1) lock **D4–D6** (side-effect-free checks, the backend-agnostic port, fail-closed
default); (2) build the membership API as the D5=c port's first concrete artifact (P4.1/P4.2 =
the consumer's group-blind fix, Mongo-first, zero throwaway); (3) round out characterization (C5/C6,
the `Allow`-side `upVote` rows). **Uncommitted**: the P2.1/P2.2 code change (`IRoleInUserColl.kt`),
the two flipped tests, and the blueprint status updates (BRIEF R3/R4, LEDGER D2/D3 locked, CONTRACT
C3/C4/T1, PLAN) — awaiting commit approval.
