# PLAN — Navigable Destinations

> Ordered execution. **SAFE** = no URL, label, filter outcome or navigation behavior observable by a
> downstream app changes. **BREAKING** = moves a URL, changes a rendered label, or alters what a
> destination shows (each cites its LEDGER decision).

**Nothing here is started.** D1–D5 are OPEN; Phase 2 onward is gated on locking them.

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
| **P1.1** | **Pin the `view → baseUrl` map itself.** Walk `ViewRegistry`'s three maps and assert the full mapping equals a snapshot written into the test. Green today by construction; **red the moment any URL moves** — which is the property D1 actually exists to protect: a published URL must not move, or every bookmark and every cited link silently points elsewhere. The D1 violator list (`baseUrl != viewKClass.simpleName`) is *derived* from this snapshot, not asserted separately. *(Two weaker framings rejected: "assert none diverge" is permanently red and pins nothing; "assert the violator **set**" passes when a violator moves from one divergent `baseUrl` to another — the set is unchanged, the URL moved.)* | new test | C2 |
| **P1.2** | **A `ViewItem` without id is not launchable; with a fixed id it is.** Pins C6, the criterion that a destination is view + parameters. | new test | C6 |
| **P1.3** | **`ViewCapturaQA` imposes its scope** (consumer-side, mppArel): render it and assert an explicit `soloPendientesQa=false` still resolves to `true`. **This is the test the spike could not write**, and it is what makes D4 a real choice rather than a guess — without it, (a) "default only" would look free. Needs view-render scaffolding in Karma; if that proves disproportionate, say so here and mark C7 characterized-by-browser-only. | mppArel `jsBrowserTest` | C7 |
| **P1.4** | **The blob iframe is same-origin and unsandboxed.** Pins the precondition every bridge option depends on. Must fail loudly if a `sandbox` attribute ever appears. | new test | C9, T5 |

## Decision gate

**Lock D1–D5 before any Phase-2 or Phase-3 code.** P1.1's violator list feeds D1; P1.3's outcome feeds
D4. Do not start P2 with D4 open: modeling an imposed scope as a default is a **user-visible
regression** (T4), and it is the kind of thing that is cheap to decide now and expensive to unpick
after two consumers depend on it.

## Phase 2 — Destination surface · additive, then BREAKING for consumers · construction

| | What | Gated on | SemVer |
|---|---|---|---|
| **P2.1** | Filter semantics on `ConfigView`: `default` vs `scope`, explicitly declared. | D4 | minor (additive optional param); **consumers recompile** |
| **P2.2** | The destination type itself — label defaulting to `configView.label` (C5), target URL computable without rendering (T1), identity = target URL (T2). Generics in subclasses, erased base for consumers; the mppArel `LaunchSpec` prototype (6 tests green) is the reference shape. | D2, D3 | minor |
| **P2.3** | Whatever D1 chooses. If (a) *unify*: **BREAKING** — moves URLs; must precede any documentation that cites links, and needs a redirect story for existing bookmarks. If (c) *assert*: SAFE. | D1 | per option |

## Phase 3 — Help navigation bridge · SAFE · construction

| | What | Gated on | Notes |
|---|---|---|---|
| **P3.1** | Instrument the document at construction on **both** paths of C9 — `createBlobUrl(injectThemeAttribute(...))` **and** `detachToWindow`'s own template. `(window.opener \|\| window.parent \|\| window).location.hash = href`. There is no single choke point; this is two call sites, not one. | D5 | ~15 lines |
| **P3.2** | Decide and implement the `<body>`-less fragment case (C11, 17 docs): instrument, wrap, or declare out of scope. | D5 | contract, not discovery |
| **P3.3** | Degrade visibly when `window.opener` is null (reloaded detached window) rather than silently doing nothing. | D5 | |
| **P3.4** | Browser-verify all three surfaces **and** a theme rebuild. The parent-side interceptor already passed on one surface, one load — and was still the wrong design (C10). One green surface is not evidence. | | |

## Phase 4 — Consumer adoption · BREAKING for the consumer · construction

Belongs to the consuming apps; listed so the dependency is visible.

| | What | Where |
|---|---|---|
| **P4.1** | `ViewHome` menu + home cards read the catalog. Deletes the hand-written labels — including the "Control de Calidad" duplicated across both, and "OT Taller" as a third alias. Answers D3(c) empirically: does the canonical label fit the card? | mppArel |
| **P4.2** | The invariant test: **no destination label literals** — scoped to *navigable leaves*, not the whole tree. Structural nodes (`Ejecución`, `Catálogos`, `Reportes`) have no container and must be exempt by construction. | mppArel |
| **P4.3** | CI check: every app link cited in `help-docs/` matches a **declared destination**, compared as a **full URL** — filter, id and action included. **The point of the whole exercise**: it turns a convention into a failing build. Lives in `jsBrowserTest` (the registry is `jsMain`; a JVM test cannot see it). **Not** `ViewRegistry.findByUrl`: it takes only `baseUrl` and ignores filter/id/action, so `#/ViewItemCfdiExternal?id=999&action=Read` would resolve to a real view and pass while pointing nowhere. A check that goes green on wrong links is worse than no check — it certifies the rot. | mppArel |
| **P4.4** | The search palette: fuzzy over label + keywords, launchable destinations only. Not AI — fuzzy handles typos and partial input; an LLM is a fallback for zero-lexical-overlap intent, if usage shows the need. | mppArel |
| **P4.5** | mppErsaPack adoption, once the shape has proven itself on one consumer. | mppErsaPack |

## Known adjacent debt (surfaced by the spike; not this blueprint's scope)

- **`ConfigViewListCavidadMolde` is declared with the generic `ApiFilter`**, whose `masterItemId` is
  `Unit?` ⇒ **no filtered cavity destination is expressible at all**. mppArel's "F1 / typed filter"
  debt, with a newly concrete consequence.
- **`:arel:jsBrowserTest` dies with `OutOfMemoryError`** at the committed `-Xmx2g`; needs `-Xmx6g`.
  Blocks P1.3/P4.2/P4.3 in CI. mppArel decision (affects everyone).
- **`CommonUserSessionParams` defines no `labelItem`**, so it falls back to `itemKClass.simpleName` and
  the menu shows users the raw class name **"UserSessionParams"**. A declared catalog surfaces every
  such gap.

---
[← Index](../INDEX.md) · [BRIEF](BRIEF.md) · [CONTRACT](CONTRACT.md) · [LEDGER](LEDGER.md) · **PLAN**
