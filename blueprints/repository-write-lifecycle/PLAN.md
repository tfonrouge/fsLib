# PLAN — Repository Write / Delete / Lifecycle

> Ordered execution. **SAFE** = no behavior change observable by a downstream subclass.
> **BREAKING** = changes hook order / contract for subclasses (each cites `CONTRACT.md` + a
> `LEDGER.md` entry, ships behind a major-signal bump). Type = `construction` | `design`.
> "Discharges" links each step to the findings (BRIEF register) and decisions (LEDGER) it closes.

## Dependency graph (must-precede)

- **P1.4 (contract KDoc)** precedes all of Phase 2 — the breaking steps cite it.
- **P1.3 (memory gains `findChildrenNot`)** precedes **P2.1** — so removing the Mongo/SQL
  double-check never leaves memory at zero checks.
- **P1.8 (conformance suite)** is scaffolded in Phase 1; its hook-order and single-owner-delete
  assertions are **assume-gated (reported skipped) per engine** until **P2.1/P2.2** land, then flip
  to live assertions — the intended red→green tripwire, with no committed failing tests.

---

## Phase 0 — Governance · SAFE · design

| ID | Step | Discharges | Status |
|----|------|-----------|--------|
| **G1** | This blueprint set (BRIEF, CONTRACT, LEDGER, PLAN). | D7, root-cause (write it down) | ✅ done |
| **G2** | *(optional)* Add a `## Premise: cathedral` config block to `CLAUDE.md` to institutionalize governance project-wide. | D7 | ⏸ deferred (user call) |

## Phase 1 — SAFE batch · construction (ship as one minor release)

| ID | Step | File anchors | Discharges | Status |
|----|------|--------------|-----------|--------|
| **P1.1** | `Coll.insertOne(Action.Create)` — strip + `onValidate` hoisted above the `try`; changelog guarded by `if (result == true)`. | `Coll.kt:985-1011` | F1 (create path), I2 | ✅ done |
| **P1.2** | Hoist `onValidate` + constructor-strip above the write `try` in `updateFieldsById` and the `updateOne(Action.Update)` overload, mirroring P1.1. Pure structural normalization (the early-return moves with it). | `Coll.kt` `updateFieldsById` (~1390-1405), `updateOne` (~1525-1540) | F1 residue, F4 (coincidence→structure), I2, **N5** | ✅ done |
| **P1.3** | Add the `findChildrenNot` check to `InMemoryRepository.deleteOne` (after `onBeforeDeleteAction`, before `store.remove`) + regression test: deleting a parent-with-children returns `State.Error`. | `InMemoryRepository.kt:227-253` | **F2 (the violation)**, I3 | ✅ done (tests `deleteBlockedWhenChildrenExist`, `deleteAllowedWhenNoChildren`) |
| **P1.4** | Write the I1–I7 invariants into `IRepository` KDoc, referencing `CONTRACT.md`. | `IRepository.kt` | F7/root cause, I1–I7, D7 | ✅ done — corrected per review: after-hook semantics (fire on *attempted* writes incl. failures; only changelog is success-gated), `(target)` tags for unconverged invariants (hook order P2.2, exactly-once delete P2.1, init P2.3), accurate `allowApiCrud` placement |
| **P1.5** | **`allowApiCrud`** — add `suspend fun allowApiCrud(apiItem: ApiItem.Action<T,ID,FILT>): SimpleState = SimpleState(isOk = true)` to `IRepository` (default in interface); invoke once at the top of the `apiItemProcess` Action branch in all three engines. | `IRepository.kt`, `Coll.kt`, `SqlRepository.kt`, `InMemoryRepository.kt` | N1, N2, D4, I5 | ✅ done |
| **P1.6** | Distinct vocabulary/message for the generic-only lockdown, never the `readOnly` message. Added `IRepository.apiCrudDisabledErrorMsg` (default-overridable) + `denyApiCrud()` helper; the gate conformance test uses it. | `IRepository.kt`, `InMemoryRepositoryTest.kt` | N4, I5 naming rule | ✅ done |
| **P1.7** | KDoc `updateMany` + `bulkWrite` as ungated/unhooked escape hatches; KDoc `updateFieldsById` as Mongo-only/engine-coupling. | `Coll.kt` `bulkWrite`/`updateMany`/`updateFieldsById` | N6, N7, I7 | ✅ done |
| **P1.8** | **Cross-engine conformance suite** in a dedicated `:conformance` module (D9): engine-agnostic assertions run against InMemory + SQL (via H2, no Docker); target invariants not yet converged in an engine are skipped via JUnit `Assume` until P2.x (no committed failing tests). Plus a real-mongod write-failure test (C, deferred). Asserts: gate (Action rejected when closed / Read allowed / low-level `insertOne` succeeds / `call==null` passes permission), validation-failure ⇒ no changelog + no success after-hooks, exactly-once delete check, canonical hook order. | `:conformance` module | D6, D9, locks F1/F2/N1, I1–I6 | ⏳ in progress — landed: `:conformance` scaffold + SQL/H2 smoke; **engine-agnostic harness** across memory + SQL pinning the gate (I5), per-action permission parity (I6), and validation side-effect freedom (I2 — create + update: no persistence, no after-hooks, no change-log entry; change-log pinned on SQL via a `ChangeLogProbe`); canonical hook order (I1) asserted on memory, assume-skipped on SQL until P2.2; delete safety (I3 — block/allow parent-with-children universal; exactly-once assume-skipped on SQL until P2.1); memory hook-order pins also in memorydb. The portable **memory + SQL** suite is complete; only the Mongo real-server test (C) remains deferred. |
| **P1.9** | Close the SQL remote-write permission gap (N8): run the per-action CRUD permission check in `SqlRepository.apiItemProcess` Action branch, matching its Query branch + Mongo. Document the in-memory engine's intentional permission exemption (CONTRACT I6). | `SqlRepository.kt` apiItemProcess Action | N8 (new), I6, D8 | ✅ done — no-op without a configured `rolePermissionProvider`; cross-engine permission pin pending P1.8 |

**Recommended approval boundary:** the full SAFE batch — P1.1–P1.7 and P1.9 — is now landed (commits
`b196ae64` + `94ac0471`): it closes the one true violation (F2) and the SQL permission gap (N8),
durably closes F1, lands the clean gate (P1.5) and its vocabulary (P1.6), documents the escape hatches
(P1.7), and writes the honest contract (P1.4). Only the cross-engine + real-mongod remainder of
**P1.8** remains in SAFE scope; the BREAKING Phase-2 batch (P2.1–P2.3) is separate and needs
deliberate, ledger-cited approval.

## Phase 2 — BREAKING batch · construction (one deliberate decision; major-signal bump)

| ID | Step | File anchors | Discharges | Status |
|----|------|--------------|-----------|--------|
| **P2.1** | **D-Delete → Option B.** `onQueryDelete` default → plain `isOk` in all engines; remove the redundant prepare-phase `findChildrenNot` from `Coll.deleteOne` and `SqlRepository.deleteOne`; `deleteOne` is the single owner (memory already covered by P1.3). | `Coll.kt:603` + `onQueryDelete` (~1171), `SqlRepository.kt:252` + `onQueryDelete` (~488), `InMemoryRepository.kt` `onQueryDelete` (~443) | F2 (double-check), D1, I3 | ☐ |
| **P2.2** | **D-HookOrder → Option A.** Reorder Mongo `updateOne` before-hooks + SQL `updateOne` before-hooks + Mongo `updateFieldsById` query gates to `upsert→specific`. | `Coll.kt:1517-1529` + `1376-1383`, `SqlRepository.kt:189-202` | F3, D2, I1 · **priority raised by N5** | ☐ |
| **P2.3** | **F5 lifecycle.** Replace `Coll`'s fire-and-forget init with an awaitable `open()`/`initialize()` (minimum: a `CoroutineExceptionHandler` marking the repository unready on failure); make `onAfterOpen()` invocation uniform across engines per I4. | `Coll.kt:1680-1687`, `SqlRepository.kt:512`, `InMemoryRepository.kt:470` | F5, D3, I4 | ☐ |

## Phase 3 — Optional

| ID | Step | Discharges | Risk | Status |
|----|------|-----------|------|--------|
| **P3.1** | Remove redundant action-level constructor-strips (`Coll.kt:192,205`) + comment marking `insertOne`/`updateOne` as the sole strip enforcement point (only after confirming no path persists the action-level item without passing through them). | F6 | SAFE | ☐ |
| **P3.2** | Explicit `WriteOrigin { Remote, Service }` on `ApiItem` — only if `allowApiCrud` proves insufficient (D5 reopened). | N3 (full), D5 | BREAKING · deferred | ☐ |

## Immediate next action

P1.1–P1.7 and P1.9 are **done and verified**. The portable **memory + SQL** P1.8 conformance suite is
**complete** — gate (I5), permission parity (I6), validation side-effect (I2), and delete safety (I3)
asserted on both engines, with the target invariants (hook order I1, delete-exactly-once I3) assume-
skipped on SQL until P2.1/P2.2. The only P1.8 remainder is **Mongo participation (C)** — the
real-mongod write-failure/cross-engine coverage — pending the Docker (Testcontainers) vs flapdoodle
decision. Phase 2 (BREAKING — P2.1 delete de-dup, P2.2 hook-order convergence, P2.3 `onAfterOpen`) is
**unblocked** and should be approved as one deliberate batch citing CONTRACT.md + LEDGER; it flips the
remaining **(target)** statuses to **Enforced** and turns the assume-skipped SQL tripwires green.
