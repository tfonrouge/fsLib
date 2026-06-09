# CONTRACT — IRepository Write / Delete / Lifecycle Invariants

> The durable spec. Every invariant below must be (a) stated in `IRepository` KDoc and (b) asserted
> by the cross-engine conformance suite against `Coll`, `SqlRepository`, and `InMemoryRepository`.
>
> Each invariant carries a **Status**:
> - *Enforced* — true in all engines **and** pinned by the conformance suite.
> - *Behaviorally enforced* — the code is correct in the named engines, but the cross-engine suite
>   does not yet guard it against regression (an in-memory pin may exist; the full cross-engine pin is
>   PLAN P1.8).
> - *Partially enforced* — true in some engines; convergence pending.
> - *Target* — agreed end-state, rollout pending.
>
> Targets and pending pins cite their PLAN step. An invariant is only **fully in force** once the
> suite pins it. The spec must neither lead nor lag the construction.

Line references are to the state verified on 2026-06-08 and are anchors, not guarantees — re-locate
by symbol when implementing.

---

## I1 — Canonical hook order (symmetric, engine-identical)

**Status: Target** (LEDGER D2, PLAN P2.2). Enforced today for all create paths, all after-hooks, all
query gates, and the in-memory engine's update; Mongo/SQL `updateOne` before-hooks and Mongo
`updateFieldsById` query gates still diverge.

For every write, lifecycle hooks fire in this order, **identically across engines** and **symmetric
between create and update**:

```
gate    : allowApiCrud            (generic/remote entry only — see I5)
query   : onQueryUpsert  → onQuery{Create|Update|Delete}      (shared BEFORE specific)
before  : onBeforeUpsertAction → onBefore{Create|Update}Action (shared BEFORE specific)
write   : onValidate → driver write
after   : onAfter{Create|Update}Action → onAfterUpsertAction   (specific BEFORE shared)
```

**Rule: the shared `Upsert` hook is the outermost wrapper** — first on the way in (`before`), last
on the way out (`after`). This already holds for all create paths, all after-hooks, all query
gates, and the in-memory engine's update; the deviations to fix are Mongo/SQL `updateOne`
before-hooks and Mongo `updateFieldsById` query gates (F3).

**Rationale:** `onBeforeUpsertAction` and `onBefore{Create|Update}Action` may each mutate the item;
divergent order means two engines persist *different documents* for the same input. Decision: see
LEDGER **D2**.

---

## I2 — Validation gates the write; failure is side-effect-free

**Status: Behaviorally enforced** (all engines); cross-engine pin pending PLAN P1.8. The in-memory
pin is `validationFailureFiresNoAfterHooksAndNoWrite`.

`onValidate` runs **after** all before-hooks and **before** the driver write. A validation failure
(`hasError`) — or a no-op/no-change skip — returns *before the write is attempted* and fires **no**
after-hooks and writes **no** changelog entry. Once a write is *attempted*, its after-hooks fire
**exactly once** with a success flag (they run even when the driver write fails, with `result = false`);
the changelog is written **only on success** (`if (result == true)` / `if (state != State.Error)`).

**Structural rule:** `onValidate` and the constructor-strip live **outside** the write `try`, so
this invariant is guaranteed by structure, not by where a `catch` boundary happens to sit. All Mongo
write paths comply — `Coll.insertOne` (P1.1) and `Coll.updateOne`/`updateFieldsById` (P1.2) — as do
`SqlRepository` and `InMemoryRepository`.

---

## I3 — Dependency safety: exactly once, unbypassable, owned by `deleteOne`

**Status: Partially enforced** (LEDGER D1, PLAN P2.1). Every engine now refuses to delete a parent
that still has children (the in-memory engine via P1.3); the "exactly once, owned solely by the
concrete `deleteOne`" end-state is pending Mongo/SQL de-duplication.

A parent with existing children is refused (`State.Error`), and the authoritative check lives in the
concrete `deleteOne` (action tier), **not** in `onQueryDelete`. `onQueryDelete`'s default is a plain
`isOk` gate; it must **not** be the sole owner of dependency safety (so a subclass override cannot
silently disable it). Target end-state: `findChildrenNot` runs **exactly once** on every delete in
every engine.

Decision: see LEDGER **D1**. The in-memory engine now performs this check once in its concrete
`deleteOne` (P1.3); Mongo/SQL currently check twice (via the `onQueryDelete` default *and* the direct
call in `deleteOne`). The end state — one engine-owned check everywhere — lands when Mongo/SQL drop
the redundant prepare-phase check (P2.1).

> Optional, non-authoritative: the `apiItemProcess` Query.Delete "prepare" phase MAY run an
> *advisory* dependency pre-check for early UX feedback, but it never replaces the `deleteOne`
> enforcement point.

---

## I4 — `onAfterOpen` runs once before first use and surfaces failure

**Status: Target** (LEDGER D3, PLAN P2.3). Today only the Mongo engine auto-invokes
`onAfterOpen`/indexes (fire-and-forget, errors swallowed); SQL and in-memory never auto-invoke it.

`onAfterOpen()` (and index creation) runs **exactly once before the repository serves its first
operation**, and any failure is **observable** — never swallowed by a detached coroutine. A
repository whose initialization failed must not present itself as ready. The hook's invocation is
**uniform across engines** (F5). Decision: see LEDGER **D3**.

---

## I5 — Two write tiers; `apiItemProcess` is the gated remote entry

**Status: Behaviorally enforced.** `allowApiCrud` is invoked at the top of the `apiItemProcess`
Action branch in all three engines (P1.5); the in-memory pin is
`gateClosedBlocksGenericWritesButNotReadsOrService`. Cross-engine pin pending PLAN P1.8.

The repository has two write tiers with a **one-directional** dependency:

| Tier | Entry points | Gated by `allowApiCrud`? | Hooks? |
|------|--------------|--------------------------|--------|
| **Generic / remote** | `apiItemProcess` → `actionCreate/Update/Delete` | **Yes** | Yes |
| **Trusted service** | `insertOne(item)`, `updateOne(item)`, `updateFieldsById`, `deleteOne(id)` | **No (intentional bypass)** | Yes |
| **Raw escape hatch** | `updateMany`, `bulkWrite` | No | **No** (see I7) |

- `apiItemProcess` calls the low-level methods; the low-level methods **never** call
  `apiItemProcess` (a back-edge would be circular). This direction is a guaranteed invariant.
- **`allowApiCrud(apiItem: ApiItem.Action): SimpleState`** (default `isOk`) is invoked **once** at
  the top of the `apiItemProcess` Action branch in every engine — after `asApiItem` and the
  `readOnly` gate, before action dispatch, any applicable per-action CRUD permission check, and the
  write lifecycle hooks. It is the supported seam for origin-scoped write policy ("writable only via domain
  services"): a subclass overrides `allowApiCrud`, **not** the whole dispatcher. Decision: see
  LEDGER **D4** (supersedes the whole-method override; addresses N1, N2).
- The remote surface (`StandardCrudService` + Kilua RPC) routes **only** through
  `apiItemProcess`/`apiListProcess`. Closing `allowApiCrud` therefore closes the entire remote
  write surface; it does **not** affect the trusted service tier or the raw escape hatches.

---

## I6 — Per-action CRUD permission on the remote path; `call == null` is trusted

**Status: Behaviorally enforced** (Mongo, SQL); cross-engine pin pending PLAN P1.8. The in-memory
engine is **intentionally exempt** — it is a samples/tests engine that never enforces permissions.

On the generic/remote path a non-null `call` engages the per-action CRUD permission check, run
**after** `allowApiCrud` and **before** dispatch. Mongo enforces it inside its low-level methods
(`getCrudPermission(apiItem)`), so even a direct service call carrying a non-null `call` is checked;
SQL enforces it in the `apiItemProcess` Action branch, matching its Query branch (P1.9). When
`apiItem.call == null` (the trusted service tier) the check is a no-op by design — service code
constructs trusted writes by construction. Addresses N3, N8.

> Engine notes: where no `PermissionRegistry.rolePermissionProvider` is configured the check returns
> OK (permissive), so it is a no-op for unconfigured deployments and only enforces where a provider
> exists. The in-memory engine never invokes the check at all (by design).
>
> `call`-nullness currently doubles as both *origin marker* and *trust boundary*. If that coupling
> ever needs to break, see the deferred `WriteOrigin` option (LEDGER **D5**, PLAN P3.2).

---

## I7 — `updateMany` / `bulkWrite` are raw, ungated escape hatches; `updateFieldsById` is Mongo-only

**Status: Behaviorally enforced** (explanatory KDoc added — P1.7).

`updateMany` and `bulkWrite` bypass **all** hooks, permissions, changelog, and `allowApiCrud`,
writing directly to the driver (the latter from a detached background scope). They are intentional
performance escape hatches and provide **no** part of the write-control story (stated in their KDoc).
`updateFieldsById` exists **only** on `Coll` (Mongo) — service code that uses it is engine-coupled
(documented at the method). Addresses N6, N7.

---

## Naming rule (N4)

The "generic surface closed, service tier open" state (`allowApiCrud` returning an error) is a
**distinct concept** from `readOnly` ("nothing writes"). It must use its own vocabulary and error
message (e.g. *"not writable via the generic API"*), never the `readOnly` message — the two must not
be conflated. `IRepository` provides `apiCrudDisabledErrorMsg` and the `denyApiCrud()` helper (P1.6)
so overrides default to the correct vocabulary.
