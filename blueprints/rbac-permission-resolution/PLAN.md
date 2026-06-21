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
| **P1.1** | **Characterization suite** for `IRoleInUserColl.permissionState` against a real mongod (Testcontainers, reuse the [[repository-write-lifecycle]] D11 fixture). Freeze: C1 order (root → direct → group → default); C2 direct-row short-circuit incl. `Default`; C3 group aggregation + `upVote` table (all five rows, **including** the discard-explicit-denies fall-through); C4 default-inversion **and** the direct-vs-default disagreement; C6 Mongo-only reach + fail-open for SQL/InMemory. | new `:conformance` permission-resolution profile; `IRoleInUserColl.kt` | R8, C1–C4, C6 | ☐ |
| **P1.2** | **Characterize the side effect (C5):** a permission check on an unprovisioned role inserts an `AppRole` row (assert the write happens today). Tag for D4. | `MongoRolePermissionProvider.kt`, `CollPermission.kt`, `IAppRoleColl.kt` | R5, C5 | ☐ |
| **P1.3** | **Permission-resolution conformance profile** scaffold in `:conformance`: engine-agnostic assertion shells for the T-series, **assume-gated** per engine until P3.1 gives non-Mongo engines a resolution path (no committed failing tests; mirrors [[repository-write-lifecycle]] P1.8 discipline). | `:conformance` | R8, T1/T2 scaffolding | ☐ |

**Recommended approval boundary:** land G1 (blueprint) as commit 1; land P1.1–P1.3 (characterization,
SAFE) as commit 2. Stop there and **lock D1–D6** before any Phase-2 code.

## Decision gate

| ID | Step | Discharges | Status |
|----|------|-----------|--------|
| **DG** | Resolve and lock **D1–D6** in `LEDGER.md` (pick option per decision; set APPROVED/REJECTED; non-empty falsification each). Recommendations on record: D1=c, D2=d, D3=a, D4=b, D5=c, D6=b. | D1–D6 | ☐ blocked on user lock-down |

## Phase 2 — Semantics · BREAKING · construction (one deliberate decision per locked Dx; major-signal)

> Each step is gated on its decision being locked and may shrink/expand to match the chosen option.
> Listed assuming the recommended options; re-scope if a different option is locked.

| ID | Step | File anchors | Discharges | Status |
|----|------|--------------|-----------|--------|
| **P2.1** | **Totality + conflict rule (D1, D2).** Replace the direct-row short-circuit with grant **composition**; resolve the applicable set by the locked conflict rule; make the single-group case use the same rule; **never** fall through to the role default after explicit grants. Invert the matching P1.1 characterization assertions (red→green). | `IRoleInUserColl.kt` `permissionState`/`getGroupPermission` (→ shared algebra after P3.1) | R2, R4, D1, D2, T1 | ☐ |
| **P2.2** | **`crudTaskSet`-miss unification (D3).** Remove the default-path inversion; make direct and default paths agree (allow-list semantics, or explicit deny-list flag if D3=c). | `IRoleInUserColl.kt` `buildDefaultAppRolePermission` (→ shared algebra) | R3, D3, T1 | ☐ |
| **P2.3** | **Side-effect-free resolution (D4).** Remove lazy `AppRole` provisioning from the check path; add the explicit provisioning path (registration/migration/`ensureRoles()`); flip the P1.2 characterization assertion. | `MongoRolePermissionProvider.kt`, `CollPermission.kt`, `IAppRoleColl.kt`, registration site | R5, D4, T3 | ☐ |
| **P2.4** | **Fail-closed default (D6).** SQL/InMemory fail closed on protected paths unless an explicit permission-free mode is declared; InMemory Action path actually invokes the check. | `SqlRepository.kt`, `InMemoryRepository.kt`, `IRolePermissionProvider.kt` | R7, D6, T4 | ☐ |
| **P2.5** | **Cleanup (SAFE).** Collapse the byte-identical `when (roleType)` group-dispatch arms. | `IRoleInUserColl.kt:227-247` | R9 | ☐ |

## Phase 3 — Abstraction · mostly SAFE-additive · construction

| ID | Step | File anchors | Discharges | Risk | Status |
|----|------|--------------|-----------|------|--------|
| **P3.1** | **Resolution port (D5=c).** Extract the pure resolution algebra (D1–D4) into `core`/`fullstack`; define a per-engine grant-fetch port returning **typed** grants (closes the R6 generic-type loss); Mongo's `$lookup` becomes one port impl behind the algebra. Flip the P1.3 conformance shells to live assertions across every claiming engine. | new agnostic resolver + port; `IRolePermissionProvider` surface; `Coll`/`SqlRepository`/`InMemory` port impls | R1, R6, R10, D5, T2 | additive (SAFE) except the SQL move | ☐ |
| **P3.2** | **Explicit registration (R10).** Replace the construction-side-effect, global-mutable provider registration with explicit registration; single-action + group concepts join the agnostic surface. | `Coll.kt:1740-1744`, `PermissionRegistry` | R1, R10, D5 | BREAKING (registration API) | ☐ |
| **P3.3** | **Demonstrate RBAC in a sample** (none exists today): one sample wiring user + group grants end-to-end, exercising composition + conflict rule. | `samples/**` | R8 (visibility) | SAFE (additive) | ☐ |

## Immediate next action

Author complete (G1). **Awaiting approval to commit the blueprint** (memory: no commit without an
explicit OK). On approval: commit 1 = this blueprint set + INDEX row; then begin P1.1–P1.3
(characterization, SAFE) as commit 2; then **lock D1–D6** at the decision gate before any Phase-2
semantic change.
