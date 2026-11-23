# Directive — view-consumer-gaps

**Authority:** APPROVED
**Drafted by:** @claude · **Approved by:** @teo (Owner)
**Effective:** 2026-09-08 · **Adoption decision:** LEDGER L-001
**Revision:** 4

## Owner Directive
Close in fsLib the generic ViewList-layer gaps, measured in consumers, that force them to replace
standard behaviour with their own — T-3, T-1, T-4 (2026-09-08), T-2 (R3) and T-6 (R4) — keeping
the standard order, extensibility and governance of a shared library; T-5 stays a recorded design
decision.

## Outcome Criteria
- OC-01: With `fsTomSelectRemote` in place of `tomSelectRemote`, a `refreshState()` call while a
  load for the same value is already in flight fires no additional RPC and adds no duplicate
  options; a failed load clears the guard so a later call can retry — verifiable: jsTest pinning
  the guard state machine; end-to-end target (1 options RPC per selector on form open, measured
  via `performance.getEntriesByType('resource')`) attested by a consumer (mppArel).
- OC-02: The unsaved-changes cancel dialog resolves caption, text, Yes and No through KVision
  i18n (immediate `gettext`) with English source keys, so a consumer catalog translates all four
  — verifiable: jsTest over the extracted text provider; no raw dialog literal outside i18n
  resolution.
- OC-03: The help offcanvas caption, manual modal caption, and detached-window title are computed
  from the view label at open/click time (a label change after FAB construction is reflected —
  including fsLib's own `View.startDisplayPage` caller, wired to a live provider), and every
  user-facing string in `helpButtons.kt` routes through KVision i18n — `I18n.tr` lazy markers
  where a component's whole text is the key; immediate `gettext` for every interpolated string
  (a mid-string marker mistranslates or leaks) — verifiable: jsTest over the caption provider;
  grep (literals AND `.label` interpolations) shows no raw user-facing string in the file.
- OC-04: T-5 carries a LEDGER design entry (options, recommendation, falsification condition) and
  no implementation item exists for it in PLAN — verifiable: LEDGER L-007 present; PLAN carries it
  only as a deferred row. (L-006 stays as the record of the options T-2 was chosen from; T-2's
  implementation is OC-05.)
- OC-05: A stored Tabulator column layout never overrides a column title written in code, while the
  stored width, visibility and column order — and sorting, filters, header filters, grouping and
  pagination — keep being restored; layouts stored by earlier versions stay readable and lose their
  stale title on the next save; and a view whose columns depend on context can key its stored layout
  per variant — verifiable: jsTest seeding a stored layout that carries the old title and asserting
  the code title renders with the stored width applied, with a `persistence = true` control that
  asserts the stored title wins (so the key list is shown to be the cause); jsTest that sort, filter
  and page still restore; jsTest over the variant key join.
- OC-06: A ViewList can suppress Create from the default context row menu without losing the
  default CRUD menu or altering its order: `showCreateInContextRowMenu` (default: follows
  `showCreateInToolbar`) governs only that entry, the two surfaces can be set apart, `editable()`
  and `showDefaultContextRowMenu()` keep their current meaning, and the row menu never holds
  consecutive separators nor ends in one (CONTRACT I-7, I-8) — verifiable: jsTest over the pure
  default-CRUD decision and separator normalization, plus jsTest through a real ViewList fixture
  proving the production row-menu generator applies them; each new guard mutation-checked.

## Non-Goals
- Full jsMain string sweep; `:core` enum label changes; upstream patch submission; T-5 build.
- Per-operation visibility for Update/Delete; header menus or any menu generator other than the
  ViewList row menu; edits to consumer repositories; version bump or release (separately authorised).

## Governing Constraints
- Additive public API only — no removal or signature change of existing symbols (SemVer minor).
- `FsTomSelectRemote*` is a workaround and carries an explicit retirement condition — binding: L-003.
- New i18n keys use English sources; existing Spanish enum labels wrap at point of use — binding: L-005.
- Column-layout persistence keeps everything `persistence = true` persists except the column title — binding: L-012.
- Row-menu Create follows the toolbar by default and is separable per view — binding: L-017.
- Separator normalization removes only redundant separators, in the ViewList row menu only — binding: L-018.

## Amendment Rule
Only an explicit Owner decision may change this file. Each amendment increments
Revision and records provenance in LEDGER (`DIRECTIVE R<N>:` rows).
