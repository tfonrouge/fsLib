# CONTRACT — view-consumer-gaps

Durable invariants this wave establishes, and the new public API surface (this file carries the
API_SURFACE duty per LEDGER L-002). KDoc on the symbols references this contract.

## Invariants

- **I-1 (in-flight guard).** `FsTomSelectRemoteInput.refreshState()` never starts a second
  options load for a value while a load for that same value is in flight; the dropped calls'
  outcome is delivered by the in-flight load's completion callback (which runs the base refresh).
- **I-2 (failure clears the guard).** A load that fails (RPC error) clears the in-flight marker,
  so a later `refreshState()` can retry — the guard never turns a transient failure into a
  permanently stale selector. This preserves upstream's retry-on-next-refresh semantics.
- **I-3 (behavioral mirror + retirement).** Apart from I-1/I-2, `FsTomSelectRemote` /
  `FsTomSelectRemoteInput` behave identically to KVision's `TomSelectRemote` /
  `TomSelectRemoteInput` (same constructor surface, same FormControl contract), so a consumer
  swap is import + builder-name only. The wrapper carries L-003's retirement condition:
  **at every KVision version bump, re-read `TomSelectRemoteInput.refreshState` sources; when
  upstream guards in-flight loads, retire these classes.**
- **I-4 (translatable strings).** Every user-facing string introduced or touched by this wave
  (`ViewItem` cancel dialog; `helpButtons.kt`) resolves through KVision i18n — **`I18n.tr` lazy
  markers only where a component's whole text is exactly the key; immediate `gettext` for every
  interpolated or raw-DOM string** (a mid-string marker either swallows the suffix into the
  lookup key or leaks raw — L-008/ACS-05). English source keys for new strings; enum labels wrapped at
  point of use — `HelpType`'s are Spanish, `HelpTheme`'s are English (L-005; the "both Spanish"
  wording was corrected in L-010).
- **I-5 (late-bound help titles).** The help offcanvas caption, manual modal caption, and
  detached-window title are computed when the user opens them, never captured at FAB
  construction; the `String` overload of `helpButtons` delegates to the provider overload, and
  **fsLib's own caller (`View.startDisplayPage`) passes a live provider** so standard views get
  the fix without any consumer change (L-008/ACS-04).

- **I-6 (the code owns column titles).** A stored Tabulator layout may restore a column's `width`,
  `visible` and position, and the table's sort, filters, header filters, grouping and pagination —
  but never its `title`. The title is authored in code and changes with the code, for every browser,
  without a *Reset*. Layouts stored by earlier versions keep working and shed their stale title on
  the next save. Views whose columns depend on context key their storage per
  `persistenceVariant` (read when the table is built — a live table is never re-keyed; L-012), which
  is also the knob that retires a stored layout on purpose, **composed with the context rather than
  replacing it** (L-013/L-014). Changing that suffix re-keys the view's whole persistence namespace —
  `columns`, `sort`, `filter`, `headerFilter`, `group`, `page` — not only its columns.
- **I-7 (row-menu Create is its own surface; T-6 — implemented, unreleased).** The Create entry of the default
  ViewList context row menu is governed by `showCreateInContextRowMenu`, which by default returns
  `showCreateInToolbar`, so both surfaces stay aligned unless a view sets them apart. Detail, Update
  and Delete keep their positions relative to each other whether or not Create is present.
  `editable() == false` still suppresses Create, Update and Delete; `showDefaultContextRowMenu()
  == false` still suppresses every default CRUD entry. The toolbar reads its flag once, when it is
  built; the row menu reads its own each time it opens and passes the clicked row (the toolbar uses
  the current selection) — so a state-dependent getter can make the two diverge visually (L-017).
- **I-8 (row-menu separators; target — T-6).** The generated ViewList context row menu never
  contains two consecutive separators and never ends in one; no non-separator entry is reordered or
  removed; where two groups were separated, exactly one separator remains between them. This
  applies to redundant separators contributed by consumer extensions too (two contiguous
  separators are no extra group in Tabulator); no separator is ever added. Header menus and every other menu generator are outside this invariant (L-018).

## New public API (additive — SemVer minor)

| Symbol | Kind | Module / package | Notes |
|---|---|---|---|
| `FsTomSelectRemoteInput<T>` | open class (extends `TomSelectRemoteInput<T>`) | `fullstack` jsMain · `com.fonrouge.fullStack.form` | I-1/I-2 guard; constructor mirrors KVision's |
| `FsTomSelectRemote<T>` | open class (`StringFormControl`, mirror of `TomSelectRemote<T>`) | `fullstack` jsMain · `com.fonrouge.fullStack.form` | KVision hard-wires its input (`final override val`), hence a mirror, not a subclass |
| `Container.fsTomSelectRemote(...)` | DSL builder | same | drop-in for `tomSelectRemote(...)` |
| `Container.fsTomSelectRemoteInput(...)` | DSL builder | same | drop-in for `tomSelectRemoteInput(...)` |
| `Container.helpButtons(viewClassName, viewLabelProvider: () -> String, moduleSlug, showTutorial)` | overload | `fullstack` jsMain · `com.fonrouge.fullStack.layout` | carries the implementation; `String` overload delegates |
| `ViewList.persistenceVariant` | `protected open val` (default `null`) | `fullstack` jsMain · `com.fonrouge.fullStack.view` | subclass surface: a view suffixes its own stored-layout key; read when the table is built |
| `ViewList.tabulatorInfoPersistenceConfig` | companion `var` — **default value changed** | same | now an explicit object equal to `persistence = true` minus the column title |
| `ViewList.showCreateInContextRowMenu` | `open val Boolean` (default `get() = showCreateInToolbar`) | same | **T-6 — implemented, unreleased (planned for 6.5.0)**; governs only the row-menu Create entry (I-7) |

`persistenceId()` is **private** and `persistenceIdOf` **internal** — the key composition and its
testable join are fsLib's, not surface a consumer calls; `persistenceID` on `defaultTabulatorOptions`
remains the supported way to supply a key fsLib would not build.
fsLib ships **no automatic layout invalidation**: bumping [ViewList.persistenceVariant] is the explicit
mechanism (L-013), and a consumer that wants it derived from its own columns can compute the suffix
itself, as ErsaPack's `persistenciaPorFirma` already does.

`InFlightValueGuard` is **internal** — an implementation detail of `FsTomSelectRemoteInput`
(unit-tested in-module), deliberately not part of the public surface (L-008/ACS-06).

## Consumer migration notes (for the release's CHANGELOG/MIGRATION entry)

- Swap `tomSelectRemote(...)` → `fsTomSelectRemote(...)` (and the `Input` variant) to get I-1/I-2.
  **Qualified (L-010/ACS-03)**: inferred-type DSL call sites change import + builder name only; a
  variable, parameter, or receiver explicitly typed as KVision's `TomSelectRemote` needs adapting —
  `FsTomSelectRemote` is a sibling class, not a subclass.
- **Runtime-visible for existing consumers even without the swap**: previously hardcoded help-UI
  strings become English i18n keys; without catalog entries the help UI shows English. Exact
  15-key checklist (shipped in MIGRATION.md 6.2.4 → 6.3.0): `Please Confirm`,
  `Cancel and forget current changes?`, `Yes`, `No`, `Help`, `View Help`,
  `Open in separate window`, `Separate window`,
  `Browse the manual here or open it in a separate window.`, `Tutorial`, `Ayuda Contextual`,
  `Manual del Módulo`, `Auto (OS)`, `Dark`, `Light`.
- `helpButtons`: fsLib's standard views are already fixed (`View.startDisplayPage` passes a live
  provider). A consumer calling `helpButtons` directly with a label that loads after construction
  should use the provider overload; the `String` overload necessarily keeps a fixed label
  (unchanged behavior).
- **T-6 (target, 6.5.0):** a list with `showCreateInToolbar = false` stops offering Create on
  right-click by default. To keep a row-contextual Create while hiding the toolbar button, override
  `showCreateInContextRowMenu = true`. A workaround that turns the default menu off
  (`showDefaultContextRowMenu = { false }`) only to rebuild Detail/Update/Delete without Create can
  go — the switch and the rebuilt entries only; the list's own entries, `showCreateInToolbar = false`
  and anything else the same files carry stay — and the list returns to the standard menu and order
  (LEDGER L-019 lists the known ones and exactly what each keeps).
