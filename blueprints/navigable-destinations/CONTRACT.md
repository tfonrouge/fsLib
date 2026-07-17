# CONTRACT — Navigable Destinations Invariants

Two layers, following this repo's convention: **Layer C** is what the code does *today*
(characterized, to be pinned by tests **before** anything changes); **Layer T** is the target, whose
options are decided in the [LEDGER](LEDGER.md).

Everything in Layer C was verified on 2026-07-16 against code or a live browser. Nothing here is
inferred from naming.

---

## Layer C — Characterized current behavior

### C1 — A view's URL is `#/` + `baseUrl`, and it navigates

`ConfigView.url` is `"#/" + baseUrl` (`ConfigView.kt:52`). Opening it drives the app: verified live
for `#/ViewListCavidadMolde`, `#/ViewTableroMaquinas`, `#/ViewCapturaQA`.

The app **normalizes** the URL on arrival, appending the filter: `#/ViewListCavidadMolde` becomes
`#/ViewListCavidadMolde?apiFilter=%7B%7D`.

### C2 — `baseUrl` derivation is **not uniform**

| Class | `baseUrl` | Equals the view class name? |
|---|---|---|
| `ConfigViewList` | `_baseUrl ?: viewKClass.simpleName!!` | **guaranteed** |
| `ConfigViewItem` | `_baseUrl ?: viewKClass.simpleName!!` | **guaranteed** |
| `ConfigView` (plain) | `_baseUrl ?: "View${commonContainer.name}"` | **no** — coincidence when it does |

`ICommon.name` is `this::class.simpleName?.removePrefix("Common")` (`ICommon.kt:35`). So
`#/ViewTableroMaquinas` resolves only because `CommonTableroMaquinas` happens to share the stem with
`ViewTableroMaquinas`. **A document that cites a plain `ConfigView` by its class name is citing an
assumption.**

### C3 — `ViewRegistry` already indexes every view by `baseUrl`

Three maps (`configViewMap`, `configViewItemMap`, `configViewListMap`) plus
`findByUrl(baseUrl)` resolving across all three (`ViewRegistry.kt:17-27,63`). Registration happens in
each `ConfigView`'s `init`. There is **no** global registry of `ICommonContainer`.

### C4 — `commonContainer` is the RBAC identity

`MongoRolePermissionProvider.getCrudPermission(commonContainer, call, crudTask)` resolves the role by
`IAppRole::classOwner eq commonContainer.name`, and a missing role **denies** (`MongoRolePermissionProvider.kt:32-46`;
D4 of [rbac-permission-resolution](../rbac-permission-resolution/LEDGER.md) made resolution
side-effect-free — it no longer provisions).

**Consequence, and the reason this contract exists:** `ICommonContainer` is **overloaded**. It carries
the data model's authorization identity *and* its presentation labels. Giving a purposed destination
its own container — the obvious way to satisfy "labels come from the container" — would mint a new
`classOwner` and either deny the destination outright or fork its permissions away from the entity's.

### C5 — `ConfigView.label` is polymorphic and already canonical

`ConfigView.label` → `commonContainer.label`; `ConfigViewList` overrides to `labelList`;
`ConfigViewItem` overrides to `labelItem`. Delegating to `configView.label` therefore yields the right
container label per class **without the caller knowing which**.

Empirically, the default is already what consumers want: of `ViewHome`'s 4 fixed-item destinations, 3
already render `ConfigViewItemX.label`; only "Datos Generales" hardcodes a string its container calls
"Datos Empresa".

### C6 — A destination is view **plus parameters**; launchability is not a view kind

- `ViewItem` **without** id → *"no CRUD action"*, an empty shell (verified: `#/ViewItemCavidadMolde`).
- `ViewItem` **with a fixed id** → a legitimate destination. `ViewHome` has 4: three
  `navigateToQueryRead(id = …)` and one `navigateToQueryUpdate(id = "1")`.

`ConfigViewItem` can compute these URLs **without navigating**: `urlRead(id)`, `urlUpdate(id)`,
`urlDelete(id)` (`ConfigViewItem.kt:323-350`). `ConfigView.viewUrl(apiFilter)` does the same for lists
(`ConfigView.kt:184`).

### C7 — A view may **impose** its filter, in which case its URL under-promises

`ViewCapturaQA.pageListBody()` runs `if (apiFilter.soloPendientesQa != true) apiFilter = PiezaPasoFilter(soloPendientesQa = true)`
— it rewrites even an **explicit `false`**. Verified: `#/ViewCapturaQA` (no filter) lands on
`?apiFilter={"soloPendientesQa":true}`.

Two properties follow, both load-bearing:

1. This is a **scope**, not a default. A default applies when nothing was passed; this overrides what
   *was* passed.
2. The imposition lives in the render body, so **two different URLs can be the same destination**, and
   the filter cannot be read without rendering.

### C8 — `apiFilterInit()` is an instance hook, invisible to a `ConfigView`

`View.apiFilter`'s getter applies `apiFilterInit()` on first access (`View.kt:266-269,498`). It is a
method on the **view instance**. Relocating C7's rewrite into it would make the behavior declarative
*for the view* and still leave it unreadable to anything holding only the `ConfigView`.

### C9 — Help documents render on two paths, with no common construction point

| Path | Built by | Surface |
|---|---|---|
| A | `createBlobUrl(injectThemeAttribute(rawHtml, theme))` — `helpButtons.kt:260,289` | modal `<iframe src="blob:…">`, **same-origin, no `sandbox`**; and "Ventana separada" = `window.open(currentBlobUrl, "_blank")` |
| B | `detachToWindow(title, rawContent)` — `helpButtons.kt:346-408` | `window.open("", "_blank")` + `document.write` of its **own** HTML template |

Path B never calls `createBlobUrl` or `injectThemeAttribute`. Changing the theme in the modal rebuilds
the blob and **re-creates the iframe** (`bind(iframeUrl) { tag(TAG.IFRAME) … }`, `helpButtons.kt:316-323`),
discarding anything attached to the previous document.

### C10 — In-document app links are dead

Inside a path-A document, `<a href="#/ViewListCavidadMolde">` resolves to
`blob:http://…/uuid#/ViewListCavidadMolde` — a hash on the blob, navigating nothing. Verified.

A parent-installed delegated click listener does make it navigate the app (verified live), but is lost
on theme rebuild (C9) and is absent in the detached window and in path B — where `window.top` is the
detached document itself.

### C11 — Theme injection is `<body>`-dependent

`injectThemeAttribute` is `replaceFirst(Regex("<body([^>]*)>"))` and returns fragments **unchanged**
(`helpButtons.kt:209-214`). 17 of mppArel's 368 help docs have no `<body>` (all `_fields.html`). They
do not reach path A today; whether fragments are in scope for instrumentation is undecided (D5).

---

## Layer T — Target properties

Each is gated on a LEDGER decision and is **not** yet settled.

### T1 — A destination's target URL is computable without rendering *(gated on D2, D4)*

Given a declared destination, its exact URL — filter, id and action included — is readable without
instantiating a view. This is the property that makes a documentation-link CI check possible, and the
one C7/C8 currently deny for imposing views.

**Proven reachable**: mppArel's `LaunchSpec` prototype computes `targetUrl` for lists, filtered lists
and fixed items (`viewUrl` / `urlRead` / `urlUpdate`), with 6 tests green in Chrome Headless — but only
for views that **respect** their filter.

### T2 — Destination identity is the destination, not the view *(gated on D2)*

Two destinations differing only by filter are two destinations. `key = targetUrl` satisfies this and
doubles as the citable link — **provided T1 holds**. Under C7 it does not: two URLs, one destination.

### T3 — The container label is canonical by construction *(gated on D3)*

A destination's label defaults to `configView.label` (C5) — so the owner's rule holds without
discipline — and an override is a declared, greppable exception rather than a literal at a call site.
Whether a consumer needs a second, shorter form for space-constrained surfaces (the home card's
"OT Taller" vs "Ordenes de Trabajo de Taller") is an **open empirical question**, to be answered by
rendering, not by adding a `shortLabel` up front.

### T4 — Filter semantics are declared, not discovered *(gated on D4)*

A `ConfigView`-level filter states whether it is a **replaceable default** or an **imposed scope**.
Modeling C7 as a default would be a **regression**: `#/ViewCapturaQA?apiFilter={"soloPendientesQa":false}`
would show every step, and "Control de Calidad" would stop being a pending queue by editing a URL.

### T5 — The navigation bridge travels with the document *(gated on D5)*

A help document instrumented at construction carries its own bridge, so
`(window.opener || window.parent || window).location.hash = href` covers the modal iframe, the detached
blob window and the `document.write` popup alike, and a theme rebuild re-instruments rather than
breaks. Requires **both** construction paths of C9 to instrument (there is no single choke point), and
degrades silently when `opener` is null (a reloaded detached window).

**Invariant this creates, and which must itself be pinned:** the blob iframe stays same-origin and
unsandboxed. Adding a `sandbox` attribute later would break every in-document link **silently** — the
same rot, relocated into the fix.

---
[← Index](../INDEX.md) · [BRIEF](BRIEF.md) · **CONTRACT** · [LEDGER](LEDGER.md) · [PLAN](PLAN.md)
