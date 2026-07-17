# BRIEF — Navigable Destinations (LIBRARY)

**Created**: 2026-07-16 · **Status**: PLANNING (no fsLib code yet) · **Mode**: LIBRARY
**Driver**: mppArel (first consumer) · **Second known consumer**: mppErsaPack

## Goal

Make a **navigable destination** a first-class, **inspectable** concept in fsLib: something an app can
declare once and that the menu, the home cards, a search palette, and a documentation link can all
read — **without rendering the view** to find out where it goes or what it shows.

Today a destination is implicit: it exists only at the call site that navigates to it, and its label,
filter and identity are re-stated (and drift) at every consumer.

## Motivation

Two failures in mppArel, both traced to the same absence.

**1. Documentation rot.** Instructivos cite menu paths. Menu paths move silently — a class rename
breaks the compile, a menu move breaks nothing. On 2026-07-16 a freshly written prod document told
users to find OTs at `Taller ▸ Ejecución ▸ OT Taller`; that path does not exist (the real one is
`Taller ▸ Órdenes de Trabajo`). The stable identity was available all along — `ConfigView.url` is
`#/ViewListOTrabTaller` — but nothing let a document cite it *and* be checkable.

**2. Label drift, already in production.** `ViewHome` writes "Control de Calidad" in the menu **and**
again in a home card, while the container calls it "Pasos de Piezas"; `ConfigViewListOTrabTaller`
answers to three names ("OT Taller" in the card, "Ordenes de Trabajo de Taller" in the container).
mppErsaPack repeats the pattern independently — evidence this is a library-level gap, not an mppArel
habit.

The owner's rule — *the `commonContainer` label is canonical and must drive menu entries* — is right
and 70 of 97 menu leaves already follow it. It cannot be **enforced** today because there is no
declared destination for a test to check, and no home for the legitimate exceptions.

## Findings register

Every entry below was verified against code or in a live browser on 2026-07-16, not inferred.

| # | Finding | Evidence |
|---|---|---|
| F1 | `ConfigView.url` = `"#/" + baseUrl`, and deep links **work**: `#/ViewListCavidadMolde` and `#/ViewTableroMaquinas` render their views in a live session. | browser, dev v2.5.10 |
| F2 | **`baseUrl` is not uniformly the view class name.** `ConfigViewList`/`ConfigViewItem` override it to `viewKClass.simpleName!!` (guaranteed); plain `ConfigView` uses `"View" + commonContainer.name`. `ViewTableroMaquinas` matching its class name is a **coincidence** of naming. | `ConfigViewList.kt:53`, `ConfigView.kt:35` |
| F3 | A registry already exists: `ViewRegistry` holds three maps keyed by `baseUrl` plus `findByUrl()`. | `ViewRegistry.kt:17-27,63` |
| F4 | **`commonContainer` is the RBAC key**, not a label bag: `IAppRole::classOwner eq commonContainer.name`, and a missing role **denies** (D4, side-effect-free resolution). Minting a second container for the same entity would fork or deny its permissions. | `MongoRolePermissionProvider.kt:32-46` |
| F5 | `ConfigView.label` is already polymorphic and already correct per class: base → `commonContainer.label`, List → `labelList`, Item → `labelItem`. | `ConfigView.kt:53`, `ConfigViewList.kt:56`, `ConfigViewItem.kt:98` |
| F6 | **Launchability is not a view kind.** A `ViewItem` **without** id dead-ends in *"no CRUD action"*; a `ViewItem` **with a fixed id** is a legitimate destination — `ViewHome` has 4 (3 `navigateToQueryRead`, 1 `navigateToQueryUpdate`). The criterion is whether the destination carries its parameters. | browser + `ViewHome.kt:419,443,450,490` |
| F7 | 197 `ConfigView`s are declared; 92 are referenced from `ViewHome`. "Declared" and "referenced" are both **upper bounds** on "launchable". | grep, `ApiConfigViewImpl.kt` / `ViewHome.kt` |
| F8 | **`ViewCapturaQA` imposes a scope, not a default**: `pageListBody()` rewrites even an explicit `soloPendientesQa=false` to `true`. Its filter is therefore **not readable without rendering**, and its URL under-promises. | `ViewCapturaQA.kt:90-93`; browser: `#/ViewCapturaQA` → `?apiFilter={"soloPendientesQa":true}` |
| F9 | `apiFilterInit()` is an **instance** method on `View`, run on first access to `View.apiFilter`. Moving F8's rewrite there relocates the behavior but leaves it **invisible** to anything holding only a `ConfigView`. | `View.kt:266-269,498` |
| F10 | Help docs render on **two** document-construction paths, and `createBlobUrl` is **not** common to them: the manual modal builds a `blob:` (same-origin, **no `sandbox`**), while `detachToWindow` `document.write`s its own HTML template. | `helpButtons.kt:260,289,346-380,408` |
| F11 | A plain `<a href="#/ViewX">` inside a help doc resolves against the blob base (`blob:…#/ViewX`) — **a dead link**. A parent-side click interceptor makes it navigate the app (verified), but is lost when the theme rebuilds the iframe and never reaches the detached window. | browser + `helpButtons.kt:285-323` |
| F12 | `injectThemeAttribute` transforms only the first `<body>` and returns fragments unchanged; 17 help-docs (all `_fields.html`) have no `<body>`. | `helpButtons.kt:209-214`, sweep of 368 docs |
| F13 | mppErsaPack — an independent fsLib consumer — hand-writes destination labels next to `ConfigViewX.navigateToView()`, exactly as `ViewHome` does. | `mppErsaPack/.../MarketPlaceSidebar.kt:92-145` |

## Scope

- What a **destination** is, and what its **URL guarantees** (F1, F2, F6).
- Where a destination's **label** lives, so the container stays canonical *and* purposed destinations
  are expressible (F4, F5).
- Whether a `ConfigView`-level filter is a **replaceable default** or an **imposed scope** (F8, F9).
- A **navigation bridge** for documents rendered by fsLib's help surfaces (F10–F12).

## Non-goals

- **Per-view RBAC.** Authorization is per data model and enforced server-side on every list
  (`Coll.getCrudPermission`). A second, view-level permission model would be a second source of
  truth for the same question — and a frontend route check is cosmetic anyway. *(Owner decision
  2026-07-16, backed by F4.)*
- **The search palette**, and the destination **instances**. Both belong to the consuming app: a real
  instance carries `PiezaPasoFilter(...)` and `DatosEmpresa.defaultId` — Arel domain types fsLib
  cannot know. A prototype (`LaunchSpec`/`LaunchCatalog`, 6 tests green) already lives in mppArel and
  is the evidence base for this blueprint.
- **Fixing `ViewCapturaQA`.** Consumer-side; blocked on the default-vs-scope decision (D4).

## Blast radius / SemVer

`ConfigView` and its subclasses are the public surface every consumer instantiates. Optional
constructor parameters are source-compatible but **not** binary-compatible for Kotlin callers —
consumers recompile. Two known consumers (mppArel, mppErsaPack). Any change to `baseUrl` derivation
(D1) would silently **move every URL** of the affected views, breaking bookmarks and any document
that cited them — the exact rot this blueprint exists to end, so it must be decided before, not after,
documents start citing links.

## Definition of done

1. D1–D5 locked in the LEDGER with falsification conditions.
2. The contract's Layer C characterized behavior pinned by tests before any of it changes.
3. A destination's label, filter and target URL are readable **without instantiating a view**, proven
   by a test that cannot pass against today's code.
4. The help bridge works on **both** document paths and survives a theme rebuild, proven in a browser.
5. mppArel can delete its hand-written destination labels from `ViewHome` — menu, home cards and
   palette all reading one source — with a test that fails if a literal returns.

---
[← Index](../INDEX.md) · **BRIEF** · [CONTRACT](CONTRACT.md) · [LEDGER](LEDGER.md) · [PLAN](PLAN.md)
