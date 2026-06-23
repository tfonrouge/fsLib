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

**Status: Enforced** (P2.2/D11 — one documented Mongo gap below). All engines emit the canonical
order; memory + SQL are pinned by the conformance suite (`canonicalHookOrderOn{Create,Update}`);
Mongo is pinned against a real mongod **in CI** (Testcontainers `MongoConformanceTest`, D11 —
Docker-assume-skip locally; first CI green 2026-06-10, run 27306432870). **Known gap:** on Mongo `updateOne`'s
upsert-*insert* path (`orig == null`), the query gates stay inside `orig?.let` so neither fires, yet
`onBeforeUpsertAction` does — an I1 inconsistency on that path only, outside the P2.2 reorder diff
(LEDGER D2).

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
on the way out (`after`). This now holds in every engine; P2.2 harmonized the former outliers
(Mongo/SQL `updateOne` before-hooks, Mongo `updateFieldsById` before-hooks + query gates — F3).

**Rationale:** `onBeforeUpsertAction` and `onBefore{Create|Update}Action` may each mutate the item;
divergent order means two engines persist *different documents* for the same input. Decision: see
LEDGER **D2**.

---

## I2 — Validation gates the write; failure is side-effect-free

**Status: Enforced** (D11 — change-log caveat below). Memory + SQL are pinned by conformance
(`createValidationFailureIsSideEffectFree`, `updateValidationFailureIsSideEffectFree`); Mongo is
pinned against a real mongod **in CI** (Testcontainers, D11 — first CI green 2026-06-10). The
**driver-write-failure clause** (a write that reaches the driver and fails fires after-hooks exactly
once with `result = false`) is pinned against a real mongod by `MongoWriteFailureTest` (the D6
duplicate-key test — Docker-assume-skip locally, green in CI). The change-log positive-control stays **SQL-only** — `Coll.changeLogCollFun` is
`IChangeLogColl`-typed, incompatible with the `IChangeLogRepository` probe, so the Mongo profile sets
`writesChangeLog = false` and the duplicate-key test covers the change-log gate only indirectly via
the same `result` flag (D11).

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

**Status: Enforced** (all engines; LEDGER D1, PLAN P2.1). Every engine refuses to
delete a parent that still has children, and the check runs exactly once, owned solely by the
concrete `deleteOne`. The memory + SQL conformance pin is live; Mongo is pinned against a real mongod
**in CI** (Testcontainers `dependencyCheckRunsExactlyOncePerDelete`, D11 — skips locally; first CI
green 2026-06-10).

A parent with existing children is refused (`State.Error`), and the authoritative check lives in the
concrete `deleteOne` (action tier), **not** in `onQueryDelete`. `onQueryDelete`'s default is a plain
`isOk` gate; it must **not** be the sole owner of dependency safety (so a subclass override cannot
silently disable it). Required end-state: `findChildrenNot` runs **exactly once** on every delete in
every engine.

Decision: see LEDGER **D1**. The in-memory engine performs this check once in its concrete
`deleteOne` (P1.3); P2.1 removed the redundant prepare-phase check from Mongo/SQL defaults, so the
end state — one engine-owned check everywhere — is now in force.

> Optional, non-authoritative: the `apiItemProcess` Query.Delete "prepare" phase MAY run an
> *advisory* dependency pre-check for early UX feedback, but it never replaces the `deleteOne`
> enforcement point.

---

## I4 — generic entry points ensure init exactly once and surface failure

**Status: Enforced** (all engines; LEDGER D3/D10, PLAN P2.3). Memory + SQL are pinned
by conformance; Mongo is pinned against a real mongod **in CI** (Testcontainers `initLifecycle*`,
D11 — Docker-assume-skip locally; first CI green 2026-06-10).

The generic item/list entry points call `ensureOpen()` before serving a request. In-tree `open()`
implementations are idempotent and retryable: `onAfterOpen()` runs exactly once after the first
successful open; a failed open is returned as an error, does not mark the repository ready, and is
retried on the next generic call. Mongo also awaits `indexes()` inside
`with(coroutine) { onAfterOpen(); indexes() }`, so index failures are no longer swallowed by a
detached constructor coroutine.

`onAfterOpen()` invocation is **uniform across engines** for the generic API surface (F5). Eager
deployments may call `open()` at boot to initialize repositories before service-tier calls. Decision:
see LEDGER **D3** and **D10**.

---

## I5 — Two write tiers; `apiItemProcess` is the gated remote entry

**Status: Enforced.** `allowApiCrud` is invoked at the top of the `apiItemProcess`
Action branch in all three engines (P1.5). Memory + SQL are pinned by conformance
(`gateClosedBlocksGenericWritesButNotReadsOrService`); Mongo is pinned against a real mongod **in CI**
(Testcontainers, D11 — skips locally; first CI green 2026-06-10).

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

**Status: Behaviorally enforced** (Mongo, SQL), pinned by conformance for **both**. Since the RBAC
blueprint's P3.2b, Mongo's `getCrudPermission` routes through the same `PermissionRegistry` provider the
conformance deny-mechanism drives (the former `Coll.roleInUserColl` split-brain is gone), so the Mongo
profile sets `enforcesPermissions = true` and the suite drives Mongo's check too. The in-memory engine is
**intentionally exempt** — it is a samples/tests engine that never enforces permissions.

On the generic/remote path a non-null `call` engages the per-action CRUD permission check, run
**after** `allowApiCrud` and **before** dispatch. Mongo enforces it inside its low-level methods
(`getCrudPermission(apiItem)`), so even a direct service call carrying a non-null `call` is checked;
SQL enforces it in the `apiItemProcess` Action branch, matching its Query branch (P1.9). When
`apiItem.call == null` (the trusted service tier) the check is a no-op by design — service code
constructs trusted writes by construction. Addresses N3, N8.

> Engine notes: where no `PermissionRegistry.rolePermissionProvider` is configured, an **enforcing** engine
> (`permissionEnforcement == Enforce`, the default) **fails closed** on the protected remote path (RBAC
> blueprint D6/P2.4 — reversed from the former permissive no-op, pinned by
> `unconfiguredDefaultFailsClosedForEnforcingEngines`); an engine that declares `Off` (e.g. the in-memory
> samples/tests engine) allows by design.
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
