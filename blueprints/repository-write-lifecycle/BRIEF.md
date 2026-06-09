# BRIEF — Repository Write / Delete / Lifecycle Contract (LIBRARY)

> Generated: 2026-06-08 · Premise: cathedral · Mode: business-blueprint LIBRARY

## Goal

Turn the `IRepository` write/delete/lifecycle behavior from a set of **undocumented, per-engine
conventions** into a **single written contract**, converge the three engines (`Coll`,
`SqlRepository`, `InMemoryRepository`) onto it, add a first-class seam for origin-scoped write
policy, and pin every invariant with a cross-engine conformance test.

## Motivation

Two independent investigations (a cathedral-premise audit of `Coll.kt`, and a write-seam analysis
prompted by an external stakeholder review of the `apiItemProcess`-override lockdown) converged on
one root cause:

> **`IRepository` documents *when* each hook fires but never *what* it guarantees** — no ordering,
> no invocation guarantee, no success-conditioning, no lifecycle, no write-tier/origin contract.
> An undocumented contract cannot be violated, which is exactly how three plausible-but-incompatible
> engine implementations all passed review.

The symptoms include one true correctness violation, two cross-engine behavioral divergences, and a
write-control pattern whose correctness rests on facts nobody wrote down.

## Findings register

Severity and status as verified by the two analysis workflows. **F-series** = cathedral audit;
**N-series** = write-seam analysis.

| ID | Severity | Status | Summary |
|----|----------|--------|---------|
| F1 | High | Fixed (P1.1, P1.2) | `Coll.insertOne` wrote a phantom changelog on failed/validation-rejected insert (fixed P1.1). `updateOne`/`updateFieldsById` now hoist `onValidate`+strip above the write `try` (P1.2) — safe by structure, not coincidence. |
| F2 | **High (the one true Violation)** | Fixed (P1.3, P2.1) | `InMemoryRepository.deleteOne` now calls `findChildrenNot` (P1.3) — a parent-with-children can no longer be deleted silently; pinned by `deleteBlockedWhenChildrenExist`. P2.1 removes the redundant Mongo/SQL prepare-phase check so `deleteOne` owns dependency safety exactly once. |
| F3 | High | Open | Create vs update before-hook order diverges: Mongo/SQL `updateOne` = specific→upsert; memory = upsert→specific; Mongo `updateFieldsById` query gates inverted. Hooks mutate the item, so order is behaviorally load-bearing. |
| F4 | Non-issue | **Refuted** | The update-path validation return is safe today (non-local return exits before any after-hook/changelog). Not a live bug; do not treat as one. |
| F5 | High | Open | `Coll` init runs `onAfterOpen()`/`indexes()` fire-and-forget with swallowed errors and no readiness signal; `SqlRepository`/`InMemoryRepository` `onAfterOpen()` is never auto-invoked. |
| F6 | Low (perf) | Optional | Redundant constructor-strip at action level; functional-only, no behavior risk. |
| N1 | High | Addressed (P1.5, I5/I7) | The first-class `allowApiCrud` gate (P1.5) replaces the whole-dispatcher override; the two-tier routing and the low-level/escape-hatch bypasses are now **documented as intentional** (CONTRACT I5/I7), not an undocumented gap. |
| N2 | High (missing abstraction) | Addressed (P1.5) | Hooks are origin-blind (they converge), so origin-scoped write policy was inexpressible — now provided by the first-class `allowApiCrud` gate (P1.5, LEDGER D4); subclasses no longer override the whole dispatcher. |
| N3 | Medium | Addressed (P1.9) | The low-level/service path is implicitly **permission-trusted** when `call == null` (`CollPermission.kt:22-25`). SQL now enforces the per-action CRUD permission on the remote path (P1.9); memory is intentionally permission-free (samples/tests). Documented in CONTRACT I6. |
| N4 | Low | Addressed (P1.6) | `readOnly` blocks **both** tiers, so it can't express "generic closed, service open" — the developer correctly avoided it. `IRepository` now provides `apiCrudDisabledErrorMsg` + `denyApiCrud()` as distinct vocabulary. |
| N5 | Medium (cross-link) | Partly addressed | The blessed service tier writes exclusively through `updateFieldsById`/`updateOne`. F1's residue on those methods is now fixed (P1.2); F3's hook-order divergence on them remains and is prioritized for **P2.2**. |
| N6 | Medium | Documented (P1.7) | `updateFieldsById` is **Mongo-only**; services built on it are engine-coupled and cannot move to SQL/memory without rewrite. Now noted in its KDoc. |
| N7 | Medium | Documented (P1.7) | `updateMany`/`bulkWrite` bypass all hooks, permissions, changelog, and the gate — by design; now documented as raw escape hatches in their KDoc. |
| N8 | High | Fixed (P1.9) | SQL remote Action writes skipped the per-action CRUD permission check that Mongo enforces — a SQL repo behind the same RPC surface was less protected. Now checked in `SqlRepository.apiItemProcess` Action branch (no-op without a configured provider). In-memory stays intentionally permission-free. |

## Scope

In scope: `IRepository` (fullstack jvmMain), `Coll` (mongodb), `SqlRepository` (sql),
`InMemoryRepository` (memorydb), the `ApiItem`/permission surface they share, and a new
cross-engine conformance test module.

## Non-goals

- Changing the RPC/remote wire surface (`StandardCrudService`, Kilua RPC) — only documenting that
  it is the sole remote write route.
- Introducing an explicit `WriteOrigin` enum now (deferred — see LEDGER D5 / PLAN P3.2).
- Adopting cathedral governance project-wide (optional one-line CLAUDE.md follow-up; PLAN G2).

## Blast radius / SemVer

- **Phase 1 (SAFE)** — purely additive (new default-OK hook), bug fixes that restore documented
  behavior, docs, and tests. No subclass observes a behavior change → **minor** release.
- **Phase 2 (BREAKING)** — changes the order in which override hooks fire and the
  `onQueryDelete`/`onAfterOpen` contract for downstream subclasses → **major-signal** bump; each
  step cites `CONTRACT.md` and a `LEDGER.md` entry.

## Definition of done

1. Every invariant in `CONTRACT.md` is stated in `IRepository` KDoc and asserted by the conformance
   suite, green across all three engines.
2. F2 closed (memory enforces dependency safety); F1 residue removed; F3 and F5 converged.
3. `allowApiCrud` exists and `PiezaPasoColl`-style lockdowns no longer need to override the whole
   dispatcher.
4. `LEDGER.md` reflects the final decisions; no Phase-2 change shipped without its ledger entry.
