# PLAN — Navigable Destinations

> Ordered execution. **SAFE** = no URL, label, filter outcome or navigation behavior observable by a
> downstream app changes. **BREAKING** = moves a URL, changes a rendered label, or alters what a
> destination shows (each cites its LEDGER decision).

**ALL DECISIONS LOCKED (2026-07-17): D1, D1-sub, D2, D3, D3b, D4, D5.** The decision gate is passed;
Phase 2 may begin (gated only by D1-sub's mppErsaPack collision sweep). No Phase-2/3 code is started
yet;
those phases remain gated on locking the rest. **D1-sub adds a shippable Phase-2 item:** `ConfigView` registration
rejects a duplicate `baseUrl` (all three maps; fail-fast) — **gated on first sweeping mppErsaPack for
an existing collision** (mppArel already clean). SemVer major.

## Dependency graph (must-precede)

```
P0 (this blueprint)
  └─ P1 characterization (SAFE) ──┬─ decision gate: lock D1–D5
                                  │
     ┌────────────────────────────┴──────────────┐
     │                                           │
  P2 destination surface (D2,D3,D4)         P3 help bridge (D5)
     │                                           │
     └─────────────┬─────────────────────────────┘
                   │
        P4 consumer adoption (mppArel: ViewHome + palette; then mppErsaPack)
```

P3 depends on P1 only, not on P2 — the bridge needs URLs to be stable, not destinations to be
declared. It can ship first if Track A is the priority.

## Phase 0 — Governance · SAFE · design

| | What | Status |
|---|---|---|
| **P0.1** | BRIEF / CONTRACT / LEDGER / PLAN; findings register from the mppArel spike (13 verified findings). | ✅ done 2026-07-16 |
| **P0.2** | Register in `blueprints/INDEX.md`. | ✅ done 2026-07-16 |

## Phase 1 — Characterization · SAFE · construction

Freeze Layer C **before** changing any of it. Every test here must pass against **today's** code — a
test that needs a change to go green is a Phase-2 test.

| | What | Touches | Pins |
|---|---|---|---|
| **P1.1** | ✅ **done 2026-07-16** (mppArel `BaseUrlCharacterizationTest`, **4/4**, mutation-checked: moving a URL turns it red, and only it). **Measured first — and the measurement changed the design.** Of **196 registered views (15 plain / 79 item / 102 list), 194 derive `baseUrl` from `viewKClass.simpleName`**; only **2** carry an explicit one (`ViewHome` → `""`, the root; `ViewBolsaTrabajo` → `"bolsaDeTrabajo"`, a hand-picked friendly URL — `ApiConfigViewImpl.kt:204,210`). A 196-line snapshot as originally planned would break on **every legitimately added view** ⇒ regenerated unread ⇒ rubber stamp. Anchoring on `simpleName` with a 2-entry exception map covers new views for free, turns red when a conforming view loses its anchor, **and** turns red when an exception moves from one divergent value to another — the case a violator-*set* test misses. | mppArel `jsBrowserTest` | C2 |
| **P1.1-fsLib** | ✅ **done 2026-07-16** (`BaseUrlDerivationTest`, 4/4, **mutation-checked**: rewriting the derivation to `viewKClass.simpleName` turns exactly the two rule tests red). Pins the **rule**, which the consumer cannot: mppArel anchors on `viewKClass.simpleName`, and **zero** of its 15 plain views exercise C2's divergent branch — so changing plain `ConfigView`'s derivation would leave every mppArel test green while its URLs moved. The fixture forces the divergence (`CommonZzzDivergente` → `name = "ZzzDivergente"` vs class `ViewSintetico`), so the two candidate rules give different answers and the test can say which is live. Also pins `_baseUrl` precedence and that `""` is the legal app root — the `ViewHome` case that refutes D1(a). | fsLib `jsTest` | C2 |
| **P1.1-enabler** | ✅ **done 2026-07-16 · SAFE · test-only.** `:fullstack:jsTest` **did not run at all**: the test bundle links the whole module, KVision drags in `toastify-js/src/toastify.css`, and `fullstack` had **no `webpack.config.d/`** ⇒ `Module parse failed: Unexpected token` on `.toastify { … }`. Pre-existing (reproduced with the new test removed), not caused by this work. Added `fullstack/webpack.config.d/css.js`, mirroring `arel/webpack.config.d/css.js`. **Side effect worth naming: it resurrected 17 fsLib tests that had been silently not executing** — the suite now runs 21. | fsLib | — |
| **P1.1-shadow** | ✅ **done 2026-07-16** (in `BaseUrlCharacterizationTest`). **A published URL can be *taken*, not just moved — and one of the two ways is coverable from the consumer.** **Shadowing across families:** the three maps are independent, so a plain + item + list sharing a `baseUrl` all register successfully — no count changes, nothing is lost — while `findByUrl`'s precedence (`plain ?: item ?: list`, `ViewRegistry.kt:63`) makes the lower two **unreachable by URL**. Pinned by a **self-deriving** uniqueness assertion across the union of the three maps: no maintenance, new views covered for free. Measured: does not occur today. | mppArel `jsBrowserTest` | C3 |
| **P1.1-overwrite** | ⛔ **NOT coverable from the consumer — gap recorded, no test.** **Overwrite within a family:** registration is direct assignment (`configViewMap[this.baseUrl] = this`), so a second `ConfigView` on the same `baseUrl` silently replaces the first; **the loser leaves no runtime trace**. A count-vs-literal test was written and then **deleted: it passed green in the exact scenario it targeted.** Proven empirically — adding a second `ConfigViewList` on `ViewListCavidadMolde::class` kept the map at 102, the literal said 102, and all 5 tests stayed green with a destination unreachable. It only caught a loss when no other change compensated the cardinality — i.e. never, for the case that matters (a *new* colliding view). Removed rather than kept, on the same principle as P4.3: **a check that certifies a safety that does not exist is worse than no check** — it invites trust. **The only place this closes is fsLib, rejecting a duplicate `baseUrl` at insertion**; hence the open sub-question under D1. | — | C3 |
| **P1.2** | **A `ViewItem` without id is not launchable; with a fixed id it is.** Pins C6, the criterion that a destination is view + parameters. **Note:** the *testable* half (a fixed id and its action reach the URL) is already covered by `LaunchSpecTest`; the *"no CRUD action"* dead-end is a runtime fact needing render scaffolding — same cost class as P1.3. | mppArel | C6 |
| **P1.3** | **`ViewCapturaQA` imposes its scope** (consumer-side, mppArel): render it and assert an explicit `soloPendientesQa=false` still resolves to `true`. **This is the test the spike could not write**, and it is what makes D4 a real choice rather than a guess — without it, (a) "default only" would look free. Needs view-render scaffolding in Karma; if that proves disproportionate, say so here and mark C7 characterized-by-browser-only. | mppArel `jsBrowserTest` | C7 |
| **P1.4** | **The blob iframe is same-origin and unsandboxed.** Pins the precondition every bridge option depends on. Must fail loudly if a `sandbox` attribute ever appears. | new test | C9, T5 |

## Decision gate

**Lock the remaining decisions before any Phase-2 or Phase-3 code.** **D1 is LOCKED (2026-07-17,
(b))** — P1.1 fed it (194/196 derive; the 2 exceptions deliberate; (a) refuted, would move the app
root). **D1-sub is LOCKED (YES — reject duplicate `baseUrl` at insertion)**; its rollout is gated on
the mppErsaPack collision sweep. **D2 is LOCKED ((a) — the `ICommonContainer` overload stays; label
lives on the destination).** **D3 is LOCKED ((a) — label on the destination, default `configView.label`;
`shortLabel` deferred).** **D3b is LOCKED (Create/Read/Update in, Delete out by policy).** **D5 is
LOCKED ((a), instrument at construction on both C9 paths).** **D4 is LOCKED ((c), per field, T1
consciously narrowed — computed-per-opening defaults are left to the view and omitted from the declared
URL; T1 holds for declarable fields only).** **All decisions are locked; the gate is passed.** Phase 2
begins, with D1-sub's rollout still gated on the mppErsaPack collision sweep.

## Phase 2 — Destination surface · additive, then BREAKING for consumers · construction

| | What | Gated on | SemVer |
|---|---|---|---|
| **P2.1** | Filter semantics on `ConfigView`: `default` vs `scope`, explicitly declared. | D4 | minor (additive optional param); **consumers recompile** |
| **P2.2** | The destination type itself — label defaulting to `configView.label` (C5), target URL computable without rendering (T1), identity = target URL (T2). Generics in subclasses, erased base for consumers; the mppArel `LaunchSpec` prototype (6 tests green) is the reference shape. | D2, D3 | minor |
| **P2.3** | Whatever D1 chooses. If (a) *unify*: **BREAKING** — moves URLs; must precede any documentation that cites links, and needs a redirect story for existing bookmarks. If (c) *assert*: SAFE. | D1 | per option |

## Phase 3 — Help navigation bridge · SAFE · construction

| | What | Gated on | Notes |
|---|---|---|---|
| **P3.1** | Instrument the document at construction on **both** paths of C9 — `createBlobUrl(injectThemeAttribute(...))` **and** `detachToWindow`'s own template (two call sites, no single choke point). Route to the **app window**: `window.opener` (detached blob window, popup) or `window.parent` when it is not the doc itself (modal iframe). **Not** the bare `(opener \|\| parent \|\| window)` chain — see P3.3: when there is no app window (`opener` null and `parent === window`) it must hit the P3.3 observable, never set its own hash. | D5 | ~15 lines |
| **P3.2** | Decide and implement the `<body>`-less fragment case (C11, 17 docs): instrument, wrap, or declare out of scope. | D5 | contract, not discovery |
| **P3.3** | **Concrete visible degradation when there is no app window to reach** (`opener` null *and* `parent === window` — a reloaded detached window). The bare `(opener \|\| parent \|\| window)` chain fails here: it sets the doc's **own** hash, a click that looks like it worked and went nowhere (ROAR 2026-07-17). Define and **test one observable** — e.g. the link renders disabled with an "open this from the app" notice — not "degrade visibly" as intent. | D5 | |
| **P3.4** | Browser-verify all three surfaces **and** a theme rebuild. The parent-side interceptor already passed on one surface, one load — and was still the wrong design (C10). One green surface is not evidence. | | |

## Phase 4 — Consumer adoption · BREAKING for the consumer · construction

Belongs to the consuming apps; listed so the dependency is visible.

| | What | Where |
|---|---|---|
| **P4.1** | 🟡 **first slice done (mppArel)** — the Taller home card reads the catalog (`LaunchSpec.asLabeledAction()`): "OT Taller" → canonical "Ordenes de Trabajo de Taller", "Control de Calidad" → declared override, both single-sourced. **D3(c) answered in the browser: the canonical label FITS** — the card's links are wrap chips, the longer label just takes a wider chip, no overflow/truncation ⇒ **`shortLabel` not needed** (confirms D3). D4 proven live: "Control de Calidad" lands on `?apiFilter={soloPendientesQa:true}` though the catalog declares no filter (view imposes it). **Remaining:** the rest of `ViewHome` (menu "Datos Generales" → "Datos Empresa", etc.) and growing the catalog toward ~92. | mppArel |
| **P4.2** | ✅ **done in the consumer (mppArel)** — the invariant test: **no destination label literals** — scoped to *navigable leaves*, not the whole tree. Structural nodes (`Ejecución`, `Catálogos`, `Reportes`) have no container and must be exempt by construction. Realized as a **source-lint test** over the consumer's home view: a navigable leaf with a hardcoded label fails the build unless its **(label, destination)** pair is on a documented allowlist of deliberate contextual exceptions — keyed on the pair, not the text, so reusing an allowlisted label for a different destination is still caught. Recognizes both internal-navigation families the catalog introduced (direct view nav and the launch-spec `.go()` path). Pattern-rule, not a snapshot: stays green as catalog-sourced leaves are added, red on a new literal leaf; mutation-checked. Detail lives in the consumer's history. | mppArel |
| **P4.3** | CI check: every app link cited in `help-docs/` matches a **declared destination**, compared as a **full URL** — filter, id and action included. **The point of the whole exercise**: it turns a convention into a failing build. Lives in `jsBrowserTest` (the registry is `jsMain`; a JVM test cannot see it). **Not** `ViewRegistry.findByUrl`: it takes only `baseUrl` and ignores filter/id/action, so `#/ViewItemCfdiExternal?id=999&action=Read` would resolve to a real view and pass while pointing nowhere. A check that goes green on wrong links is worse than no check — it certifies the rot. | mppArel |
| **P4.4** | ✅ **done in the consumer (mppArel)** — the search palette over the consumer's destination catalog: fuzzy over label + keywords, launchable destinations only. Not AI — fuzzy handles typos and partial input; an LLM is a fallback for zero-lexical-overlap intent, if usage shows the need. Adds no new RBAC surface — the palette only navigates; authorization stays at the existing gates. Implementation detail and verification live in the consumer's history. | mppArel |
| **P4.5** | mppErsaPack adoption, once the shape has proven itself on one consumer. | mppErsaPack |

## Known adjacent debt (surfaced by the spike; not this blueprint's scope)

- **`ConfigViewListCavidadMolde` is declared with the generic `ApiFilter`**, whose `masterItemId` is
  `Unit?` ⇒ **no filtered cavity destination is expressible at all**. mppArel's "F1 / typed filter"
  debt, with a newly concrete consequence.
- ~~**`:arel:jsBrowserTest` dies with `OutOfMemoryError`** at the committed `-Xmx2g`; needs `-Xmx6g`.
  Blocks P1.3/P4.2/P4.3 in CI.~~ → **DOES NOT REPRODUCE (measured 2026-07-22).** `./gradlew build`
  **passes at the committed `-Xmx2g`** — full run with `--rerun-tasks` (59 tasks executed, including
  the complete JS compile + 6.59 MiB webpack bundle): **`jsBrowserTest` 17/17** and **`jvmTest`
  402 tests, 0 failures**, in 1m 09s. Passing `-Xmx6g` is cargo-cult inherited from this line, not a
  requirement of today's baseline.
  **What the measurement does and does not establish — the debt is NARROWED, not deleted:**
  - ✅ **P4.2 is measured, not projected:** its invariant test exists and **ran inside that 2 GB build**
    (`ViewHomeNoLiteralLeavesTest`, 2/2, 0 failures). For P4.2 the blocker is disproven.
  - ⚠️ **P1.3 and P4.3 are NOT measured — they do not exist yet.** P1.3 adds Karma **render
    scaffolding** and P4.3 sweeps **help-docs × destinations**; neither memory profile is exercised by
    today's 17-test JS baseline. The honest claim is that *the blocker stated in this line is absent
    from the current baseline*, **not** that those two are cleared in advance. **When the first P1.3 or
    P4.3 test lands, run its exact task at the committed `-Xmx2g`**: if it passes, the claim stops
    being prospective; if it OOMs, the debt was never resolved — only absent from the baseline.
  - ⚠️ **The live path is NOT verified:** 79 `jvmTest` cases **skipped** (the live-Mongo suite, which
    skips visibly without `AREL_TEST_MONGO_USER`/`PASSWORD` — D-M7-2, no false-green). If the
    historical OOM originated there, this run does not rule it out.
- **`CommonUserSessionParams` defines no `labelItem`**, so it falls back to `itemKClass.simpleName` and
  the menu shows users the raw class name **"UserSessionParams"**. A declared catalog surfaces every
  such gap.

---
[← Index](../INDEX.md) · [BRIEF](BRIEF.md) · [CONTRACT](CONTRACT.md) · [LEDGER](LEDGER.md) · **PLAN**
