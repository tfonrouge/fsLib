# LEDGER — Navigable Destinations

Decisions with rationale and falsification conditions. Both-directions discipline: a rejected option
stays written down, so it is not silently re-proposed later (rejection amnesia), and an approved one
stays falsifiable (approval calcification).

**Status: D1–D5 are OPEN.** Nothing is locked. The spike that produced the evidence is complete; the
decisions are the owner's.

---

## D0 — Process (settled)

This blueprint was opened **after** the spike, not before, deliberately. During design, five of my own
claims were falsified by verification rather than by reasoning:

| Claim | Reality |
|---|---|
| "A filterless link and the menu land in different places" | `ViewCapturaQA` self-imposes; same place (C7) |
| "Give purposed destinations their own container" | Would fork or deny RBAC (C4) |
| "The filter is applied via `apiFilterInit()`" | It is applied in `pageListBody()` (C7) |
| "`ViewItem`s are not launchable" | With a fixed id they are; 4 exist (C6) |
| "`createBlobUrl` is the common instrumentation point" | Path B never calls it (C9) |

A `CONTRACT.md` written before that spike would have inherited all five. **Layer C is characterization,
not design** — and it is the part of this blueprint that is already trustworthy.

## D1 — `baseUrl` derivation: unify, or declare the divergence?

**Question.** `ConfigViewList`/`ConfigViewItem` guarantee `baseUrl == viewKClass.simpleName`; plain
`ConfigView` derives `"View" + commonContainer.name` and merely *happens* to match (C2). Once
documents cite URLs, this stops being cosmetic.

**Options.**
- **(a) Unify** on `viewKClass.simpleName` for all three. Honest and greppable — but **moves the URL of
  every plain `ConfigView` whose container stem differs from its class**, breaking existing bookmarks
  silently. BREAKING.
- **(b) Declare the divergence** and forbid citing a class name: the authority is `configView.url`,
  never an assumption about it. Costs nothing today; leaves a trap for anyone reading class names.
- **(c) Assert the coincidence** with a test (`baseUrl == viewKClass.simpleName` for every registered
  view) and fix violators individually, keeping the derivation as-is.

**Recommendation: (c), then (b) as the written rule.** It converts a coincidence into a checked
invariant without moving any URL, and (a) remains available once the test says how many violators
exist. Note (a) and the CI documentation check are in tension: the check would go green while every
citation silently pointed elsewhere.

**Two properties, not one — do not conflate them:**

| Property | Depends on D1? |
|---|---|
| **`configView.url` is a stable identity for a destination** (T2) | **No.** The URL is whatever the derivation yields; it is stable and citable either way. |
| **The view class name can be *inferred* to be the URL** | **Yes.** This is the only thing C2's divergence breaks. |

**Falsification.** If P1.1's violator list contains views that cannot be renamed (public bookmarks,
motor integration), option (a) dies and (b) is the only survivor — a written rule that the authority is
`configView.url`, never an assumption about a class name. **T2 is unaffected**: identity was never the
class name. What is lost is only the shortcut of citing one.

## D2 — What is an `ICommonContainer`?

**Question.** It is today both the **RBAC identity** (`classOwner`, C4) and the **presentation labels**
(`labelItem`/`labelList`, C5). That overload is what made "give the destination its own container" look
reasonable and made it dangerous.

**Options.**
- **(a) Accept the overload, document it.** The container is the data model's identity, labels
  included; presentation for anything that is *not* the entity's own list/item lives elsewhere (D3).
- **(b) Split** authorization identity from presentation. Correct in principle, invasive in practice:
  `classOwner` values are persisted in `AppRole` rows in every consumer's database.

**Recommendation: (a).** (b) is a data migration across consumers' RBAC tables to fix a naming concern.
The cost is not proportionate. But the overload must be **written down** — its discovery cost me a
design and would cost the next person the same.

**Falsification.** If a consumer legitimately needs two distinct permission scopes over one entity
(e.g. "QA queue" authorized separately from "all steps"), (a) is wrong and (b) — or a dedicated
per-destination scope — becomes necessary. This is the boundary between D2 and the rejected per-view
RBAC below.

## D3 — Where does a destination's label live?

**Question.** The container is canonical for the entity (C5) — but "Control de Calidad" is a *purposed*
destination, not `PiezaPaso`'s list, and calling it "Pasos de Piezas" would be worse for users.

**Options.**
- **(a) On the destination**, defaulting to `configView.label`. The rule holds by construction; the
  override is declared data.
- **(b) An optional label on `ConfigView`.** Rejected below.
- **(c) Add `shortLabel` for space-constrained surfaces** (the home card's "OT Taller").

**Recommendation: (a); (c) deferred pending evidence.** Adding `shortLabel` up front re-introduces two
labels that can drift. Render the canonical label in all three consumers first; add it only if the card
actually breaks.

**Falsification.** If more than a handful of the ~92 destinations need overrides, the default is not
carrying its weight and the container labels themselves are wrong — a different, larger fix.

## D4 — Is a `ConfigView`-level filter a default or an imposed scope?

**Question.** C7 shows `ViewCapturaQA` **imposes**: it rewrites an explicit `false` to `true`. A
conventional "default" applies only when nothing was passed.

**Options.**
- **(a) Default only.** Simple; **regresses** `ViewCapturaQA`: a URL could show every step and "Control
  de Calidad" would stop being a queue.
- **(b) Imposed scope only.** Preserves today's behavior; forbids the legitimate "start here, user may
  widen" pattern.
- **(c) Both, explicitly declared** (`default` vs `scope`).

**Recommendation: (c).** They are different features and the difference is user-visible. Whichever is
chosen, T1 and T2 depend on it: only a filter the `ConfigView` declares is readable without rendering
(C8 shows `apiFilterInit()` does not help).

**Falsification.** If no consumer ever needs a replaceable default, (c) is over-built and (b) is the
whole feature.

## D5 — The help navigation bridge

**Question.** In-document app links are dead (C10), and there is no single instrumentation point (C9).

**Options.**
- **(a) Instrument at construction, on both paths.** The document carries its own bridge; theme rebuild
  re-instruments; covers modal, detached blob window and `document.write` popup alike.
- **(b) Parent-side interceptor.** Verified to work — and verified to break on theme rebuild and to be
  absent in both detached surfaces. Rejected below.
- **(c) Absolute URLs + `target="_top"`.** Works, but hardcodes the host into documents that must be
  identical in dev and prod.

**Recommendation: (a).** Open sub-questions: are `<body>`-less fragments (C11, 17 docs) instrumented,
wrapped, or declared out of scope? And how does the bridge degrade when `window.opener` is null (a
reloaded detached window) — silently, or visibly?

**Falsification.** If the blob iframe ever needs `sandbox`, (a)'s bridge dies silently. That invariant
must be pinned by a test, or (a) is a trap.

---

## Refuted / non-decisions (kept to prevent rejection amnesia)

**Per-view RBAC — REJECTED (owner, 2026-07-16).** A user can type `#/ViewListOTrabTaller` today and the
app obeys. That is **not a hole**: authorization is per data model and enforced server-side on every
list (`Coll.getCrudPermission`, C4). Adding view-level permissions would create a second authorization
model answering the same question as the first — two sources of truth — and a frontend route check is
cosmetic and bypassable regardless. A palette **may** consult the existing per-data-model permission
for UX (so it does not offer dead ends); that is *reading* the existing model, not adding one, and it
is deferrable: the menu is ungated today, so a palette widens no exposure.

**A second `ICommonContainer` per purposed destination — REFUTED (C4).** `CommonCapturaQA` would yield
`classOwner = "CapturaQA"`, no matching `AppRole`, and — since resolution denies on a missing role —
kill Control de Calidad outright. Provisioning the role instead would fork the permission: Read on
`PiezaPaso` would not imply Read on `CapturaQA`. This looked like the clean way to honor "labels come
from the container"; it is the trap that D2 exists to document.

**Moving `ViewCapturaQA`'s rewrite to `apiFilterInit()` as a fix for inspectability — REFUTED (C8).**
It is a better home for the behavior, but `apiFilterInit()` is an instance method; a `ConfigView`
holder still cannot read the filter. It relocates the problem.

**A lambda-only destination (`launch: () -> Unit`) — REFUTED by the mppArel spike.** It compiled and
appeared to cover all three launch classes, because the type modeled **none** of them: filter, id and
action were sealed inside an opaque closure. The generics did not explode because the thing that would
make them explode had been removed — along with the inspection that is the entire point (T1). The
resolution is the standard one: generics in the subclasses, erased base for consumers.

**`key = configView.baseUrl` — REFUTED by the same spike.** It collapses two destinations that differ
only by filter, contradicting the definition of a destination.

---
[← Index](../INDEX.md) · [BRIEF](BRIEF.md) · [CONTRACT](CONTRACT.md) · **LEDGER** · [PLAN](PLAN.md)
