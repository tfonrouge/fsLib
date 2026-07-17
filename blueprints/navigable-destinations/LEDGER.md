# LEDGER — Navigable Destinations

Decisions with rationale and falsification conditions. Both-directions discipline: a rejected option
stays written down, so it is not silently re-proposed later (rejection amnesia), and an approved one
stays falsifiable (approval calcification).

**Status: ALL DECISIONS LOCKED (2026-07-17) — D1, D1-sub, D2, D3, D3b, D4, D5.** The decision phase is
closed; the blueprint moves to Phase 2 (build), gated only by D1-sub's mppErsaPack collision sweep.

## Recommendations at a glance

> **Digest, not authority.** Each row condenses the `Recommendation:` line inside the decision it
> points to; the decision body is the source of truth (rationale, options, falsification). If the two
> ever disagree, the body wins and this table is stale. Written by the assistant; the owner decides.
> **D1 is now LOCKED; the rest remain the recommendation only.**

| Decision | Question | Status / recommendation |
|---|---|---|
| **D1** | `baseUrl` derivation | ✅ **LOCKED 2026-07-17 — (b)**: authority is `configView.url`, never a class-name assumption. (a) *unify* refused (would move app root); (c) *assert* shipped as P1.1. |
| **D1-sub** | reject duplicate `baseUrl` at insertion? | ✅ **LOCKED 2026-07-17 — YES**: reject at insertion across all three registry maps (closes overwrite-within-family + shadow-across-families). Fail-fast at startup ⇒ **rollout gated on sweeping mppErsaPack for an existing collision first**. SemVer major. |
| **D2** | what is an `ICommonContainer`? | ✅ **LOCKED 2026-07-17 — (a)**: accept the RBAC-identity + labels overload as intentional contract; a purposed destination never gets its own container (would fork `classOwner`), its label lives on the destination (D3). (b) split refused. |
| **D3** | where does the destination label live? | ✅ **LOCKED 2026-07-17 — (a)**: on the destination, default = `configView.label` (container label holds by construction; override = declared data). **`shortLabel` deferred** — added only if rendering all three consumers proves the card breaks. |
| **D3b** | which `ViewItem` actions are catalog destinations? | ✅ **LOCKED 2026-07-17**: **Create + Read + Update in, Delete out** (written policy). `Create` is a distinct id-less variant, and builds its URL the way it navigates (never `urlCreate`, F14/C6b). Delete's mechanism stays; only its catalog exclusion is policy. |
| **D4** | filter: replaceable default or imposed scope? | ✅ **LOCKED 2026-07-17 — (c), T1 narrowed**: per-field, three rules (pass-through baseline; static `default`/`scope` declared). Computed per-opening defaults are **not** static metadata ⇒ the declared URL omits them and the view fills them — **T1 holds for declarable fields, not the whole filter**, marked per destination. T2 unaffected. |
| **D5** | help-doc navigation bridge | ✅ **LOCKED 2026-07-17 — (a)**: instrument the document at construction on **both** C9 paths; route to the app window (`opener`, or `parent` when `!== window`), **not** a bare `(opener \|\| parent \|\| window)` chain — no-app-window must hit a visible observable (P3.3), not self-navigate. Parent-side interceptor refused. Binds P1.4: blob iframe stays same-origin + unsandboxed. |

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

## D1 — `baseUrl` derivation: unify, or declare the divergence? (**LOCKED 2026-07-17: option (b)**)

**DECISION (owner, 2026-07-17): LOCKED on (b).** The authority for a destination's URL is
`configView.url` — never an assumption inferred from a view's class name. Option (a) *unify* is
**refused** (it would move the app root `#/` → `#/ViewHome`); the (c) *assert* half already shipped as
P1.1 (`BaseUrlCharacterizationTest`), which turns any future URL move red. The two deliberate
exceptions (`ViewHome` → `""`, `ViewBolsaTrabajo` → `"bolsaDeTrabajo"`) stand as-is.

**What this binds going forward:** documentation, help-docs and any CI link-check cite the **URL**
(`#/…`), not the class name; P4.3's check compares full URLs against declared destinations, never
resolves a class name. **Note:** the D1-sub — whether fsLib *rejects a duplicate `baseUrl` at
insertion* — is a separate mechanism (a URL being *taken*, not *derived*), not part of D1's lock; it
was locked separately (**D1-sub = YES**, see below).

**Question.** `ConfigViewList`/`ConfigViewItem` guarantee `baseUrl == viewKClass.simpleName`; plain
`ConfigView` derives `"View" + commonContainer.name` and merely *happens* to match (C2). Once
documents cite URLs, this stops being cosmetic.

**Measured 2026-07-16 (P1.1), and it narrows this decision sharply.** Across mppArel's **196
registered views** (15 plain / 79 item / 102 list), the divergence I feared is **largely theoretical**:

| | |
|---|---|
| Derive from `viewKClass.simpleName` | **194** |
| Explicit `baseUrl`, deliberate | **2** — `ViewHome` → `""` (the root), `ViewBolsaTrabajo` → `"bolsaDeTrabajo"` |
| Plain views where `"View" + container.name` **failed** to match the class name | **0** |

So the 13 remaining plain views coincide — by **naming convention, not guarantee**. The hazard is real
but latent: renaming a `Common*` moves its view's URL without touching the view class, and nothing
would say so. P1.1 now makes that event red.

**This also kills option (a) outright**: unifying on `viewKClass.simpleName` would move `ViewHome`
from `#/` to `#/ViewHome` — the application root. Not a trade-off; a non-starter.

**Options.**
- **(a) Unify** on `viewKClass.simpleName` for all three. Honest and greppable — but **moves the URL of
  every plain `ConfigView` whose container stem differs from its class**, breaking existing bookmarks
  silently. BREAKING.
- **(b) Declare the divergence** and forbid citing a class name: the authority is `configView.url`,
  never an assumption about it. Costs nothing today; leaves a trap for anyone reading class names.
- **(c) Assert the coincidence** with a test (`baseUrl == viewKClass.simpleName` for every registered
  view) and fix violators individually, keeping the derivation as-is.

**Recommendation: (c) + (b), and (a) is now refuted by measurement.** P1.1 shipped the (c) half: the
coincidence is a checked invariant, no URL moved. What remains for the owner is the (b) half — writing
the rule that **the authority is `configView.url`, never an assumption about a class name** — plus
whether the exception map's two entries stay as they are.

**Two properties, not one — do not conflate them:**

| Property | Depends on D1? |
|---|---|
| **`configView.url` is a stable identity for a destination** (T2) | **No.** The URL is whatever the derivation yields; it is stable and citable either way. |
| **The view class name can be *inferred* to be the URL** | **Yes.** This is the only thing C2's divergence breaks. |

**Falsification — already triggered, in the strongest form.** The condition was "an exception exists
that cannot be renamed". `ViewHome` → `""` **is the application root**: unifying it to `#/ViewHome`
is not a trade-off, it is a non-starter. Option (a) is dead; **(b) is the surviving rule** — the
authority is `configView.url`, never an assumption about a class name. **T2 is unaffected**: identity
was never the class name; what is lost is only the shortcut of citing one.

**Open sub-question raised by P1.1-collision — a `baseUrl` can be *taken*, not just moved.** Two
distinct mechanisms, neither of which the "does the URL move?" framing covers:

1. **Overwrite within a family.** Registration is direct assignment into a `MutableMap`, so two
   `ConfigView`s sharing a `baseUrl` silently overwrite — one destination vanishes with no error, and
   post-hoc map inspection cannot see the loser.
2. **Shadowing across families.** The three maps are independent; a plain + item + list on the same
   `baseUrl` all register successfully, and `findByUrl`'s precedence (`plain ?: item ?: list`) makes
   the lower two unreachable by URL. No count changes; nothing is lost; two destinations are simply
   unroutable.

Neither occurs in mppArel today (measured 2026-07-16). **(2) is pinned** by a self-deriving
consumer-side test. **(1) is not, and cannot be** — and that is the argument for deciding this here.

A count-vs-literal test for (1) was written and then deleted: **it passed green in the exact scenario
it targeted.** Adding a second `ConfigViewList` on an existing view class kept the map at 102, the
literal said 102, and every test stayed green with a destination unreachable. The loser leaves **no
runtime trace**, so no post-hoc inspection of the registry can see it; a colliding *addition* keeps
cardinality constant by construction. It was removed rather than weakened, on the principle that a
check certifying a safety that does not exist is worse than none.

**So: whether fsLib rejects a duplicate `baseUrl` at insertion is not a nice-to-have — it is the only
place the property can be enforced at all.** It is the same property as D1's — a published URL keeps
its meaning — and (1) is the one attack the consumer is structurally blind to. Note the collision is
not exotic: two `ConfigViewList`s over the same view class is exactly what "a second destination on
the same view, differently filtered" looks like, which is the case T2 exists for.

**DECISION (owner, 2026-07-17): LOCKED — D1-sub = YES.** `ConfigView` registration **rejects a
duplicate `baseUrl` at insertion** (loud error, not silent overwrite). The check spans **all three
registry maps** (`configViewMap` / `configViewItemMap` / `configViewListMap`), so it closes **both**
mechanisms above — overwrite-within-a-family (1) *and* shadow-across-families (2). This is the total,
at-source fix the consumer-side tests can only approximate after the fact; on it landing, mppArel's
two consumer-side collision tests become belt-and-suspenders, not the primary guard.

**Implementation caveat (must gate the PLAN step, not the lock):** the reject is **fail-fast at
startup** — any consumer that *today* silently overwrites on a duplicate `baseUrl` would fail to boot
after upgrading. **mppArel is clean** (measured 2026-07-16: 15/79/102 declared = registered, zero
collision). **mppErsaPack is NOT yet verified** — the second known consumer must be swept for an
existing collision *before* this ships, or the same fail-fast that protects them breaks their build.
The decision is locked; the rollout is conditional on that sweep. SemVer: **major** (a previously
tolerated call now throws).

## D2 — What is an `ICommonContainer`? (**LOCKED 2026-07-17: option (a)**)

**DECISION (owner, 2026-07-17): LOCKED on (a).** The overload is **accepted as intentional contract**:
an `ICommonContainer` is the data model's identity — its RBAC `classOwner` (C4) **and** its labels
(C5), together. It is **not** split. Consequences now binding: (i) a purposed destination never gets
its own container — that would mint a new `classOwner` and fork or deny its permissions; its label
lives on the destination instead (see D3); (ii) the overload must be stated where it will be read, not
left to rediscovery — C4 characterizes it and this lock elevates it to contract. (b) *split* is
refused: it is a data migration of `classOwner` across every consumer's RBAC tables to fix a naming
concern — not proportionate.

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

## D3 — Where does a destination's label live? (**LOCKED 2026-07-17: option (a)**)

**DECISION (owner, 2026-07-17): LOCKED on (a).** A destination's label lives **on the destination**,
defaulting to `configView.label` (which is already the container label, polymorphic per class — C5).
So the owner's rule "the container label is canonical" holds **by construction**: a destination that
says nothing gets the container's label for free; an override is **declared data** on the destination
(e.g. "Control de Calidad" over `CommonPiezaPaso`'s "Pasos de Piezas"), greppable, not a literal buried
at a call site. (b) *a label on `ConfigView`* is refused (it is the wrong home — the label belongs to
the destination, not the view config; see the refuted list). **(c) `shortLabel` is deferred, not
adopted:** adding a second label up front re-introduces exactly the drift this decision removes.
Whether the home card needs a shorter form ("OT Taller" vs "Ordenes de Trabajo de Taller") is settled
by **rendering the canonical label in all three consumers first** (a P4 step), and adding `shortLabel`
only if the card actually breaks — evidence, not anticipation.

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

## D3b — Which `ViewItem` actions are catalog destinations? (**LOCKED 2026-07-17: Create in, Delete out**)

**DECISION (owner, 2026-07-17): LOCKED.** Catalog destinations over a `ViewItem` are **`Create`,
`Read`, `Update`** — **`Delete` is excluded, by written policy, not by omission.** `Create` is in
because it is the natural palette query ("create an OT"); `Delete` is out because a one-keystroke path
from a search box to a destruction form is a hazard the catalog should not manufacture, and nothing in
the app reaches Delete by URL today (verified: mppArel's `CrudTask.Delete` goes through
`ViewList.goActionUrl()` → `confirmDeleteView(...)` locally, never a URL). The mechanism for Delete
stays (`urlDelete` exists, C6) — the exclusion is a **catalog/palette policy**, not a claim that Delete
is unroutable. Two binding notes carried from the analysis: (i) `Create` is a **distinct variant**, not
a third `ItemAction` enum value — it takes no id (`navigateToQueryCreate(id: ID? = null)`) while
`ItemFijo` requires one; (ii) a Create destination builds its URL the way it **navigates**
(`navigateToQueryCreate(apiFilter=…)`, which serializes the filter), **never** via `urlCreate` (which
omits it) — else the declared URL disagrees with where it goes and a doc CI check certifies a lie
(F14/C6b). `Create` does **not** collapse `key = targetUrl` (two contextualized creates differ by their
serialized filter).

**Question.** A data model has exactly two views: `ViewList` (the listing) and `ViewItem` (the
Create/Read/Update form). The route supports **four** actions — `Create`, `Read`, `Update`, **and
`Delete`** (C6). The spike's prototype models only `Read`/`Update`, so:

- a **"create an OT"** destination is **unrepresentable**, though it is exactly what someone would
  type into a palette (`ViewHome` has no Create entry today only because creation is reached from a
  list's button);
- **`Delete` is absent by oversight, not by decision** — I enumerated the actions I had happened to
  see rather than reading the rule, twice in a row.

**Two different questions, do not collapse them:** *what the route supports* (a characterized fact,
C6) versus *what a catalog may offer* (this decision). A palette that never offers a direct
destruction is a perfectly good policy — but it must be **chosen and written**, not the accidental
result of nobody noticing `urlDelete` exists.

**It is not a third enum value.** The prototype's `ItemFijo` requires `val id: ID` (non-null) while
`navigateToQueryCreate` takes `id: ID? = null`: adding `ItemAction.Create` would either leave the
valid id-less state unrepresentable or force an invented id. `Create` carries **different data**, so
it is a different variant — the same "make illegal states unrepresentable" reasoning that produced
`ItemAction` in the first place, applied one level further.

**Trap to write into whatever ships (F14/C6b).** `urlCreate` omits the filter; the query path
serializes it. Declaring `urlCreate` as the destination's URL while launching via
`navigateToQueryCreate(apiFilter = …)` makes the two disagree — and a documentation CI check would
pass on a URL that goes somewhere else. Whatever variant is chosen must build its URL the way it
navigates.

**Note it does *not* break T2:** two contextualized creates differ by their serialized `apiFilter`, so
`key = targetUrl` still distinguishes them (an earlier claim that Create collapses keys was wrong).

**Recommendation (owner decides).** `Create` in — it is the natural palette query. `Delete` **out**,
explicitly: a one-keystroke path to a destruction form is a hazard a search box should not create, and
nothing in the app reaches Delete by URL today. Writing "out" is the point; leaving it unmentioned is
how it comes back.

**Falsification.** If no consumer ever wants a create destination reachable other than from a list's
button, this is over-built and `ViewItem` destinations are Read/Update-only. Conversely, if a
legitimate flow ever needs a Delete-by-URL destination (a confirmation link in a notification, say),
the "out" policy is wrong and the exclusion must move from the catalog to the palette's filter — the
mechanism would still be there.

## D4 — Is a `ConfigView`-level filter a default or an imposed scope? (**LOCKED 2026-07-17: (c), T1 narrowed**)

**DECISION (owner, 2026-07-17): LOCKED on (c), accepting that T1 narrows.** Filter semantics are
declared **per field**, in three rules: **pass-through** (the un-declared baseline — the field is not
touched), and two declarable ones, **default** (fill if absent) and **scope** (impose regardless). A
`ConfigView` carries the declarable rules as **static metadata where the value is static** — a fixed
scope like `soloPendientesQa=true`, a constant default — and those fields keep T1 (their contribution
to the URL is computable without rendering).

**T1 is consciously narrowed, not saved.** A **per-opening computed** default (`Date().minusWeeks(1)`)
is not static metadata; expressing it would need a lambda over the incoming filter — which is
`apiFilterInit()` by another name (C8), unreadable from a `ConfigView`. Rather than force that shape,
the decision **accepts the honest limit** the falsification already named: for a field whose rule is a
computed default, the destination's **declared URL omits it**; the view fills it on render. So:

- **A destination's declared URL is authoritative for the fields it can declare** (pass-through +
  static default/scope), and **silent about computed-default fields** — it does not pretend to predict
  them. T1 holds for the declarable part, not the whole filter.
- **The catalog marks this per destination**, not globally: a destination over an imposing/computing
  view says "URL authoritative for declared fields"; most destinations (no computed defaults) are
  fully authoritative as before.
- **P4.3's link-check compares a citation against the *declared* URL**, tolerating a computed-default
  gap — that gap is a view-filled field, **not a broken link**. The check never asserts the cited URL
  equals the rendered filter for such destinations.

**T2 (identity = target URL) is unaffected:** destinations differ by their *declared* params, and a
computed default is a view property, not a per-destination one. **Rejected:** modeling scope as a plain
default (regresses `ViewCapturaQA`), or forcing all defaults into a lambda surface just to make the
computed ones declarable (that is `apiFilterInit`, C8 — the cure is the disease).

**Question.** C7 shows `ViewCapturaQA` **imposes**: it rewrites an explicit `false` to `true`. A
conventional "default" applies only when nothing was passed.

**Options.**
- **(a) Default only.** Simple; **regresses** `ViewCapturaQA`: a URL could show every step and "Control
  de Calidad" would stop being a queue.
- **(b) Imposed scope only.** Preserves today's behavior; forbids the legitimate "start here, user may
  widen" pattern.
- **(c) Both, explicitly declared** (`default` vs `scope`).

**Both semantics already exist in mppArel — this is not a hypothetical (measured 2026-07-16, ROAR).**

| Semantics | Where | How |
|---|---|---|
| **Imposed scope** | `ViewCapturaQA.pageListBody():90-93` | rewrites even an explicit `soloPendientesQa=false` to `true` |
| **Replaceable default** | `ViewListMonitoringData.apiFilterInit():150-156` | `apiFilter.copy(fecha1 = apiFilter.fecha1 ?: …)` — the `?:` fills **only when absent** |

*(I briefly recommended "scope only" on the premise that nobody needed a replaceable default. The
premise was false and unverified — both live in the app today. Recommendation restored to (c).)*

**And it is not a per-view property — it is PER FIELD (ROAR, browser-verified 2026-07-16), and there
are THREE rules, not two.** `ViewListAnalisisEficOrdenTrabajo.apiFilterInit()` mixes rules field by
field. **Cross-repo note (ROAR 2026-07-17): this view was since fixed** — mppArel `7b0c5e47`
(AUDIT §5 F1) changed `endDate` from a scope-erase to pass-through — so the block below is **historical
evidence**, superseded in code, kept because it is what surfaced the per-field taxonomy:

```kotlin
// BEFORE (7b0c5e47) — the erase that surfaced the whole point:
startDate        = apiFilter.startDate ?: Date().minusWeeks(1),         // DEFAULT: fill if absent
endDate          = null,                                                 // SCOPE: erases even explicit
areaTrabajoIdSet = apiFilter.areaTrabajoIdSet.ifEmpty { setOf("00002") } // DEFAULT: fill if empty
// AFTER (current) — still per-field, milder illustration:
endDate          = apiFilter.endDate,                                    // PASS-THROUGH: take as-is
```

**What survives the fix, and matters more for D4 than the erase did:** the live taxonomy is not
default-vs-scope — it is **three per-field rules**: **default** (fill if absent — `startDate`,
`areaTrabajoIdSet`, `MonitoringData`), **pass-through** (take exactly what arrived — `endDate` now),
and **scope** (impose regardless — `ViewCapturaQA`). The D4 options (a)/(b)/(c) framed it as two; the
AnalisisEfic fix revealed the third. So two whole-filter fields (`defaultFilter` / `scopeFilter`)
**still cannot express this** — worse than before, since even *three* whole-filter fields wouldn't say
which rule wins **per attribute**. **This is why D4 locked on per-field granularity (see the DECISION
at the top of this section); the per-field, three-rule shape is the live fact, not a hypothetical
about one view.**

**On the specific `AnalisisEfic` case (resolved, for the record):** the erase was a real deep-link
divergence, fixed in `7b0c5e47`; whether `Fecha Fin` should mean an inclusive local day is a separate
open semantics question (mppArel AUDIT §5 **F2**), not this blueprint's. Author intent was never
proven (`git blame` pointed only at a comma-adding commit; the line predated it) — recorded as
behavior, not inference.

**Recommendation: (c), refined by the three-rule finding — pass-through is the un-declared baseline;
`default` and `scope` are the two declarable rules.** A field the destination says nothing about is
**pass-through** (identity — the fixed `AnalisisEfic.endDate` is exactly this, and needs no metadata).
The two that carry meaning and must be declared are **default** (fill if absent) and **scope** (impose
regardless). So "declare both" stands, now precise about *which* two. Modeling scope as default is the
regression named above; modeling default as scope would freeze `MonitoringData`'s date filter against
the user; treating scope's imposition as pass-through would silently drop `ViewCapturaQA`'s guarantee.

**Reframed, which narrows the real cost (ROAR):** the question is *not whether* replaceable behavior
exists — it does — but **whether it is elevated to declarable metadata on the `ConfigView`**. Those are
different asks, and only one is urgent:

- **Scope must be elevated.** T1 fails otherwise: `ViewCapturaQA`'s filter is unreadable without
  rendering, so its destination cannot state where it goes.
- **Defaults need not be, and may not even be expressible as data.** Both live defaults are
  **computed per opening** (`Date().toJodaLocalDate.minusWeeks(1)`), not constants. A `ConfigView`
  field would have to take a **lambda**, not a value — a materially bigger surface than scope needs.
  So "declare both" does not mean "declare both the same way".

**The granularity tension — and how the lock resolved it.** Per-field rules plus per-field computed
values point the same way — whatever `ConfigView` accepts is closer to *a function of the incoming
filter* than to *a filter value*. That is very nearly `apiFilterInit()` itself, which C8 already
rejected as unreadable from a `ConfigView`. There was **no shape both per-field-expressive and readable
without rendering** for a *computed* default — the two requirements pull apart. **The lock did not
force one: it accepted the limit.** Static default/scope are declared metadata (readable); a computed
default is left to the view and omitted from the declared URL, narrowing T1 (see the DECISION above).

**Falsification.** If elevating scope turns out to require the same lambda-shaped surface as defaults,
the two collapse into one feature and (c)'s distinction buys nothing. Sharpened: if the only
per-field-expressive shape *is* a lambda over the incoming filter, then it is `apiFilterInit` by
another name and T1 is unreachable for imposing views — at which point the honest answer is that a
destination declares its URL **only when the view respects it**, and the catalog says so per
destination instead of pretending otherwise.

## D5 — The help navigation bridge (**LOCKED 2026-07-17: option (a)**)

**DECISION (owner, 2026-07-17): LOCKED on (a).** The bridge is **instrumented into the document at
construction, on both paths of C9** — the `createBlobUrl(injectThemeAttribute(...))` path (modal iframe
+ "Ventana separada") and `detachToWindow`'s own `document.write` template. The instrumented script
routes an in-document `#/` link to the **app window** — `window.opener` for the detached blob window
and the popup, `window.parent` for the modal iframe — and a theme rebuild **re-instruments** rather
than breaks. (b) *parent-side interceptor* is
refused — proven to work on one surface and proven to fail on theme rebuild and in both detached
windows (see refuted list); (c) *absolute URLs* refused — hardcodes the host into docs that must be
identical in dev and prod.

**Two things this lock binds into the PLAN, not settled here** (they are implementation, not the
approach): (i) the `<body>`-less fragments (C11, 17 `_fields.html`) are **out of the bridge's scope**
until proven otherwise — they do not reach path A today; if one ever does, it is wrapped before
instrumenting, never silently skipped; (ii) **"degrades visibly" needs an explicit guard, not the bare
`(opener || parent || window)` chain (ROAR 2026-07-17).** In a reloaded detached window `opener` is
null *and* `parent` is the window itself, so that chain would set the document's **own** hash — a click
that looks like it worked and went nowhere, i.e. the exact silent failure we are avoiding. The bridge
must therefore **detect "no app window to reach"** (`opener` null and `parent === window`) and emit a
**concrete observable** — disable the link / show a visible "abrí esto desde la app" notice — never
fall through to self. The observable is defined and tested in P3.3, not left as intent.

**Invariant this lock makes load-bearing (P1.4):** the blob iframe stays **same-origin and
unsandboxed**. Adding a `sandbox` attribute later kills every in-document link **silently** — the same
rot, relocated into the fix — so a test must pin it, or (a) is a trap.

**Question.** In-document app links are dead (C10), and there is no single instrumentation point (C9).

**Options.**
- **(a) Instrument at construction, on both paths.** The document carries its own bridge; theme rebuild
  re-instruments; covers modal, detached blob window and `document.write` popup alike.
- **(b) Parent-side interceptor.** Verified to work — and verified to break on theme rebuild and to be
  absent in both detached surfaces. Rejected below.
- **(c) Absolute URLs + `target="_top"`.** Works, but hardcodes the host into documents that must be
  identical in dev and prod.

**Recommendation: (a).** *(Both sub-questions this recommendation once left open are now settled by the
LOCKED DECISION above: `<body>`-less fragments are out of scope until one reaches path A, then wrapped;
and the no-app-window case degrades **visibly** via the P3.3 observable, never silently. Kept here as
the pre-decision reasoning, not as live open choices.)*

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
