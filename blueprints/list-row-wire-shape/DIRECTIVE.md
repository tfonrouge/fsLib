# Directive — list-row-wire-shape

**Authority:** APPROVED
**Drafted by:** @claude · **Approved by:** @teo (Owner)
**Effective:** 2026-11-26 · **Adoption decision:** LEDGER L-001 · **Amendments:** R2 — LEDGER L-005
**Revision:** 2

## Owner Directive
Make the ViewList grid hold the same row shape after Tabulator's AJAX loader as after a refresh, for
pages both paths can decode, so Kilua RPC 0.0.46's wire change cannot make Tabulator materialize
partial objects that break row decoding — minimal scope, `onReceivingData` and the content hash left on
the raw rows; target release 6.5.1, pending separate authorisation.

## Outcome Criteria
- OC-01: A first-load row whose nested-column parent arrives as an explicit `null` (nullable property
  with a `null` default) is not materialized by Tabulator and decodes through KVision's component
  formatter — verifiable: jsTest through `ajaxRequestFunc` → `promise()` → Kilua `CallAgent` → stubbed
  `fetch`, red before the fix.
- OC-02: For pages both decoders accept (loader: `jsonHelper`, row by row; refresh: `toKotlinList`, the
  whole page at once), re-encoded by the table codec, a refresh through `ViewList.dataUpdate()` that changes a visible value shows it with the
  same row keys as the load; a JS `customConfiguration` with `encodeDefaults = true` does not change the
  grid shape; `onReceivingData` receives the raw rows on both paths — verifiable: jsTests by key
  presence; each guard mutation-checked.
- OC-03: A row that cannot be re-encoded is kept in place, unchanged, and reported with view, index and
  failure category only — no row content — verifiable: jsTest with console capture; mutation-checked.

## Non-Goals
- The pre-existing `val x: X?` (no default) case (L-003); `explicitNulls = false` (L-002).
- Changing what `onReceivingData` or the content hash receive, or the refresh decoder (L-002).
- Consumer edits, bumps or releases; upstream fixes in Kilua, Tabulator or KVision (L-004).

## Governing Constraints
- Canonicalize only what Tabulator's AJAX loader receives; grid shape = defaults absent — binding: L-002.
- No row is ever dropped by the canonicalization — binding: L-002.
- Version bump, publication, tag and GitHub Release only on explicit Owner authorisation.

## Amendment Rule
Only an explicit Owner decision may change this file. Each amendment increments
Revision and records provenance in LEDGER (`DIRECTIVE R<N>:` rows).

---
[← Index](../INDEX.md) · Map · **DIRECTIVE** · [BRIEF](BRIEF.md) · [CONTRACT](CONTRACT.md) · [LEDGER](LEDGER.md) · [PLAN](PLAN.md)
