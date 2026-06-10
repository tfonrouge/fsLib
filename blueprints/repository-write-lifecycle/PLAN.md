# PLAN — Repository Write / Delete / Lifecycle

> Ordered execution. **SAFE** = no behavior change observable by a downstream subclass.
> **BREAKING** = changes hook order / contract for subclasses (each cites `CONTRACT.md` + a
> `LEDGER.md` entry, ships behind a major-signal bump). Type = `construction` | `design`.
> "Discharges" links each step to the findings (BRIEF register) and decisions (LEDGER) it closes.

## Dependency graph (must-precede)

- **P1.4 (contract KDoc)** precedes all of Phase 2 — the breaking steps cite it.
- **P1.3 (memory gains `findChildrenNot`)** precedes **P2.1** — so removing the Mongo/SQL
  double-check never leaves memory at zero checks.
- **P1.8 (conformance suite)** is scaffolded in Phase 1; its target assertions are
  **assume-gated (reported skipped) per engine** until their Phase-2 convergence step lands, then
  flip to live assertions — the intended red→green tripwire, with no committed failing tests.

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
| **P1.4** | Write the I1–I7 invariants into `IRepository` KDoc, referencing `CONTRACT.md`. | `IRepository.kt` | F7/root cause, I1–I7, D7 | ✅ done — corrected per review: after-hook semantics (fire on *attempted* writes incl. failures; only changelog is success-gated), `(target)` tags for unconverged invariants (hook order P2.2, init P2.3), accurate `allowApiCrud` placement |
| **P1.5** | **`allowApiCrud`** — add `suspend fun allowApiCrud(apiItem: ApiItem.Action<T,ID,FILT>): SimpleState = SimpleState(isOk = true)` to `IRepository` (default in interface); invoke once at the top of the `apiItemProcess` Action branch in all three engines. | `IRepository.kt`, `Coll.kt`, `SqlRepository.kt`, `InMemoryRepository.kt` | N1, N2, D4, I5 | ✅ done |
| **P1.6** | Distinct vocabulary/message for the generic-only lockdown, never the `readOnly` message. Added `IRepository.apiCrudDisabledErrorMsg` (default-overridable) + `denyApiCrud()` helper; the gate conformance test uses it. | `IRepository.kt`, `InMemoryRepositoryTest.kt` | N4, I5 naming rule | ✅ done |
| **P1.7** | KDoc `updateMany` + `bulkWrite` as ungated/unhooked escape hatches; KDoc `updateFieldsById` as Mongo-only/engine-coupling. | `Coll.kt` `bulkWrite`/`updateMany`/`updateFieldsById` | N6, N7, I7 | ✅ done |
| **P1.8** | **Cross-engine conformance suite** in a dedicated `:conformance` module (D9): engine-agnostic assertions run against InMemory + SQL (via H2, no Docker); target invariants not yet converged in an engine are skipped via JUnit `Assume` until P2.x (no committed failing tests). Plus a real-mongod write-failure test (C — resolved by D11, `MongoWriteFailureTest`). Asserts: gate (Action rejected when closed / Read allowed / low-level `insertOne` succeeds / `call==null` passes permission), validation-failure ⇒ no changelog + no success after-hooks, exactly-once delete check, canonical hook order. | `:conformance` module | D6, D9, D11, locks F1/F2/N1, I1–I6 | ✅ done — landed: `:conformance` scaffold + SQL/H2 smoke; **engine-agnostic harness** across memory + SQL pinning the gate (I5), per-action permission parity (I6), validation side-effect freedom (I2 — create + update: no persistence, no after-hooks, no change-log entry; change-log pinned on SQL via a `ChangeLogProbe`), delete safety (I3 — block/allow parent-with-children universal; exactly-once live on memory + SQL after P2.1), canonical hook order (I1 — live on memory + SQL after P2.2), and init lifecycle (I4 — exactly-once + failure surfacing/retry live on memory + SQL after P2.3). The portable **memory + SQL** suite is complete; **Mongo participation (C) is now added** via a Testcontainers `MongoConformanceFixture`/`MongoConformanceTest` + smoke (Docker-assume-skip — green-or-skip locally, runs the Mongo engine for real in CI; D11). Compile-verified + skip-clean locally; **first real Mongo green achieved in CI 2026-06-10** (run 27306432870, commit `66ceda79`). Mongo profile sets `enforcesPermissions`/`writesChangeLog` `false` (engine-specific mechanisms outside the harness — D11); the convergence invariants I1/I3/I4 are exercised. The **D6 real-mongod write-failure test** (`MongoWriteFailureTest`) drives the F1 duplicate-key path: error state + friendly 11000 message surfaced, after-hooks exactly once with `result = false`, store untouched, repo still usable. Its change-log control is indirect (the same `result` flag); the direct change-log pins stay on SQL's validation-failure path — no engine directly probes the change-log gate after a failed driver write (D11). |
| **P1.9** | Close the SQL remote-write permission gap (N8): run the per-action CRUD permission check in `SqlRepository.apiItemProcess` Action branch, matching its Query branch + Mongo. Document the in-memory engine's intentional permission exemption (CONTRACT I6). | `SqlRepository.kt` apiItemProcess Action | N8 (new), I6, D8 | ✅ done — no-op without a configured `rolePermissionProvider`; cross-engine permission pin pending P1.8 |

**Recommended approval boundary:** the full SAFE batch — P1.1–P1.7 and P1.9 — is now landed (commits
`b196ae64` + `94ac0471`): it closes the one true violation (F2) and the SQL permission gap (N8),
durably closes F1, lands the clean gate (P1.5) and its vocabulary (P1.6), documents the escape hatches
(P1.7), and writes the honest contract (P1.4). **P1.8 is now complete across all three engines**
(memory + SQL live; Mongo via Testcontainers incl. the D6 write-failure test — D11, first real run in
CI). The BREAKING Phase 2 lands as separate, ledger-cited increments:
**P2.1 (delete de-dup), P2.2 (hook-order convergence), and P2.3 (`onAfterOpen` lifecycle) are done**
— their memory + SQL conformance tripwires are now live green; the Mongo runtime pins are live via
the Testcontainers suite (D11 — first CI green 2026-06-10).

## Phase 2 — BREAKING batch · construction (one deliberate decision; major-signal bump)

| ID | Step | File anchors | Discharges | Status |
|----|------|--------------|-----------|--------|
| **P2.1** | **D-Delete → Option B.** `onQueryDelete` default → plain `isOk` in all engines; remove the redundant prepare-phase `findChildrenNot` from Mongo/SQL defaults; `deleteOne` is the single owner (memory already covered by P1.3). | `Coll.kt` `onQueryDelete`, `SqlRepository.kt` `onQueryDelete`, `InMemoryRepository.kt` `onQueryDelete` | F2 (double-check), D1, I3 | ✅ done — SQL exactly-once conformance tripwire is live; remote Query.Delete no longer performs the advisory dependency pre-check |
| **P2.2** | **D-HookOrder → Option A.** Reorder Mongo `updateOne` before-hooks + Mongo `updateFieldsById` before-hooks & query gates + SQL `updateOne` before-hooks to `upsert→specific`. | `Coll.kt` `updateOne`/`updateFieldsById`, `SqlRepository.kt` `updateOne` | F3, D2, I1 · **priority raised by N5** | ✅ done — SQL hook-order tripwire (`canonicalHookOrderOn{Create,Update}`) live green (SQL 9/2 → 9/0); Mongo runtime pin live via the Testcontainers suite (D11 — CI green 2026-06-10). Known upsert-insert I1 gap logged in CONTRACT I1. |
| **P2.3** | **F5 lifecycle.** Add explicit retryable `open()` + lazy `ensureOpen()` gates on generic item/list entry points; replace `Coll`'s fire-and-forget constructor init with awaited `with(coroutine) { onAfterOpen(); indexes() }`; make `onAfterOpen()` invocation uniform across engines per I4. | `IRepository.kt` `open`/`ensureOpen`, `Coll.kt` `open` + generic gates, `SqlRepository.kt` `open` + generic gates, `InMemoryRepository.kt` `open` + generic gates | F5, D3/D10, I4 | ✅ done — memory + SQL init lifecycle tripwires live green; Mongo runtime pin live via the Testcontainers suite (D11 — CI green 2026-06-10); failed open retries on the next generic call |

## Phase 3 — Optional

| ID | Step | Discharges | Risk | Status |
|----|------|-----------|------|--------|
| **P3.1** | Remove redundant action-level constructor-strips (`Coll.kt:192,205`) + comment marking `insertOne`/`updateOne` as the sole strip enforcement point (only after confirming no path persists the action-level item without passing through them). | F6 | SAFE | ☐ |
| **P3.2** | Explicit `WriteOrigin { Remote, Service }` on `ApiItem` — only if `allowApiCrud` proves insufficient (D5 reopened). | N3 (full), D5 | BREAKING · deferred | ☐ |

## Immediate next action

P1.1–P1.9 are **done and verified**. The P1.8 conformance suite is **complete and green across all
three engines**: memory + SQL run live (gate I5, permission parity I6, validation side-effect I2,
delete safety I3, hook order I1, init lifecycle I4), and Mongo runs against a real mongod **in CI**
via Testcontainers (D11) — **first real Mongo green achieved 2026-06-10** (run 27306432870, commit
`66ceda79`), pinning the P2.1/P2.2/P2.3 Mongo convergence (I1/I3/I4) plus the D6 real-mongod
write-failure path (F1 duplicate-key, `MongoWriteFailureTest`). Locally (no Docker) the Mongo leg
Assume-skips; in CI a missing Docker daemon fails loudly (D11). D12 closed the `MongoClient`-per-repo
leak the suite surfaced. Phase 2 is complete in code (P2.1/P2.2/P2.3). **Released as `4.0.0`**
(2026-06-10): version bump in `gradle/libs.versions.toml`, CHANGELOG entry with migration guide,
README coordinates refreshed. The blueprint's in-scope work is **complete**; only the optional
Phase 3 items (P3.1, P3.2) and G2 remain open. (Repo infra: the explicit `:conformance:test` CI
step is pushed and green — commit `e330456f`, run 27307306966.)
