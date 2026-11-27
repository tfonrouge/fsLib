# BRIEF — list-row-wire-shape

| | |
|---|---|
| Mode | LIBRARY |
| Owner | @teo |
| Opened | 2026-11-26 |
| Origin | Production incident in mppErsaPack 2.6.196 (fsLib 6.5.0, KVision 9.7.0, Kilua RPC 0.0.46, Tabulator 6.5.2), 2026-11-26; rolled back to 2.6.195 (fsLib 6.3.0, Kilua 0.0.45) at 16:55 CST. Placement as its own blueprint by Owner decision, chat 2026-11-26 (L-001). |

## What happened

`ViewListMailboxEvidenciaFile` threw on load in production:
`Unexpected JSON token … Expected string literal but 'null' literal was found at path: $.user.code`,
input ending `…"folioRemisionEntrega":null,"user":{"code":null}}`. `user` is a `$lookup`-filled body
property (`var user: User? = null`); the list has a nested column `fieldName(MailboxEvidenciaFile::user,
User::code)` and component formatters/editors on other columns.

## Causal chain (verified in source, bytecode and a red characterization test)

1. **Kilua RPC 0.0.46** (brought in by fsLib 6.4.0) forces `encodeDefaults = true` in
   `RpcSerialization.plain` and `getCustomJson(…)`, also over a consumer `customConfiguration`
   (JS: compiled `RpcSerialization$plain$lambda` / `$getCustomJson$lambda`; JVM: 2 `setEncodeDefaults`
   calls in `RpcSerialization.class`). 0.0.45 set nothing. Rows now carry `"prop": null` for every
   nullable property at its `null` default.
2. **fsLib `TabulatorViewList`** fed the first load from the raw RPC JSON (`promise()` → `JSON.parse`),
   while the refresh path re-encodes through `toKotlinList` → `replaceData` → `toPlainObj` (defaults
   absent). The two shapes only diverged once the wire started carrying defaults.
3. **Tabulator** (`Cell.build` → `Column._setNestedData`, identical in 6.4.0, 6.5.2, 6.7.1) writes each
   cell value back into the row; over a `null` parent it creates the parent: `user: {code: null}`.
4. **KVision** (`Options.kt` formatter/editor wrappers, `Tabulator.toKotlinObj`) decodes that row and
   throws, uncaught. fsLib's `contextRowMenuGenerator` (toast) and `CellComponent.item` (silent `null`)
   see the same broken row.

No published upgrade fixes any link (KVision 10.0.0-RC, Kilua 0.0.47-RC, tabulator-tables 6.7.1 —
checked by reading code, 2026-11-26).

## Blast radius

mppErsaPack: 33 views with nested `fieldName(A::b, B::c)` columns; 17 of them also use component
formatters/editors, where the load itself throws. All 33 are exposed regardless of formatters: row
selection (`selectableRows = 1` by default) and `ViewList.dataUpdate()` decode the row through
`getSelectedData()` (corrected after ROAR round 1, R-1). Which rows hold `null` parents in real data:
not checked. mppDulce `origin/master` (fsLib 6.5.0): 21 nested `fieldName(A::b, B::c)` sites in 7
jsMain files (`git grep`, 2026-11-26). mppArel: dormant until it ships fsLib ≥ 6.4.0 with Kilua 0.0.46. mppDulce, three distinct states
(read 2026-11-26):
- `origin/master` (local remote-tracking ref) = `17dc90e`, fsLib 6.5.0 (adoption PR #2, merged). This ref
  moved from `ccb5350` because this session ran `git fetch` in mppDulce at 17:01 CST — the only write made
  there: a remote-tracking ref fast-forward; no branch, index or working-tree change.
- local `master` = `d486450`, fsLib 6.3.0 (not updated).
- deployment: not observed here. The adoption session reported production runs rc8 from the
  `analisis-ventas-*` branches; those refs (`origin/analisis-ventas-rc` `51441a5`, `-pical` `58dec25`,
  `-perfiles` `12e4e35`) all pin fsLib 6.3.0. No 6.5.0 deploy is known.

## Scope

The fsLib fix (canonicalize first-load rows; pin the row codec's `encodeDefaults`), its tests,
CHANGELOG/MIGRATION, and the 6.5.1 patch release when the Owner authorises it. Consumer bumps belong to
each consumer's session.

---
[← Index](../INDEX.md) · Map · [DIRECTIVE](DIRECTIVE.md) · **BRIEF** · [CONTRACT](CONTRACT.md) · [LEDGER](LEDGER.md) · [PLAN](PLAN.md)
