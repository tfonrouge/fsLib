# CONTRACT — list-row-wire-shape

## Invariants

| ID | Invariant | Pinned by |
|----|-----------|-----------|
| **I-1** | The rows Tabulator's own loader receives (`ajaxRequestFunc`: first load and remote page / sort / filter navigation) are re-encoded through the table codec (`toPlainObj(toKotlinObj(row))`) after `promise()` resolves. | `nullParentOnFirstLoadIsNotMaterializedAndDecodes`, `firstLoadAndRefreshGiveTheSameRowShape` |
| **I-2** | `ViewList.onReceivingData` and `listContentHash` keep receiving the raw RPC rows, on the loader path and on the `apiCall()` refresh; the refresh is re-encoded only by `replaceData`, only when its content changed. | Hook: `nullParentOnFirstLoadIsNotMaterializedAndDecodes`, `firstLoadAndRefreshGiveTheSameRowShape` (mutant "canonicalize inside `promise()`" turns both red). Hash: by reading — `listContentHash(jsonObj)` runs inside `promise()`, before the loader wrapper re-encodes. |
| **I-3** | For a page both decoders accept — the loader decodes with `jsonHelper` (the consumer JS configuration, else `ignoreUnknownKeys` + `isLenient`, plus Kilua and table serializer modules), the refresh with `toKotlinList` (`Json.decodeFromDynamic` on the default `Json`, no serializer modules, the whole page in one call; any failure empties the page) — the table codec re-encodes it to the same keys on both paths; a property at its default value — non-null defaults included — is absent. | `firstLoadAndRefreshGiveTheSameRowShape`, `defaultValuedPropertiesAreAbsentFromGridRows`, `unknownKeyIsDroppedAlikeByBothDecoders` |
| **I-4** | `TabulatorViewList.jsonHelper` has `encodeDefaults = false` whatever the consumer's JS `RpcSerialization.customConfiguration` / `Serialization.customConfiguration` says. | `consumerEncodeDefaultsDoesNotLeakIntoRows` |
| **I-5** | A row that fails to re-encode is kept as received, at its index; one `console.error` per such row names the view, the index and the exception class, and carries no row content, values, JSON or exception message. | `undecodableRowIsKeptInPlaceAndReported` |
| **I-6** | A parent that is absent stays absent; a present parent still decodes and reaches the nested cell. | `absentParentIsNeverMutated`, `presentParentDecodes` (controls) |

## Known boundaries (not guaranteed)

- A nullable property **without** a default holding `null` is still encoded as `null` (`explicitNulls`)
  and still materialized by Tabulator under a nested column (L-003).
- An undecodable row still fails KVision's own `dataLoaded` decode (pre-existing, upstream) — I-5 only
  guarantees fsLib does not make it worse.
- Remote page / sort / filter navigation goes through the same `ajaxRequestFunc` (code reading); no test
  drives a page change.
- A key the row serializer does not know is dropped by both paths (tested, default configuration).
- Pages with a row only one decoder accepts are outside I-3: e.g. a `@Contextual` type served by the Kilua/table
  modules decodes on the loader path but not with the module-less default `Json`, whose swallow-all
  `catch` in `toKotlinList` turns the refresh page into an empty list (pre-existing; by reading, not
  tested; not changed by this patch).

## Public API

None added or changed. `canonicalRows` and `ViewRegistry.listServiceManagerOrNull` (test save/restore)
are `internal`.

## Consumer-visible effects (rows Tabulator's AJAX loader receives)

| Effect | vs 6.4.0/6.5.0 loads | vs ≤ 6.3.0 loads | Evidence |
|---|---|---|---|
| A nested column over a `null` parent no longer materializes `{c: null}`; formatters, editors, selection, `dataUpdate()`, row menu and `CellComponent.item` decode the row | fixed | not affected before 0.0.46 | tested (formatter, `getSelectedData`); row menu and `CellComponent.item` confirmed in a consumer harness (L-007); `dataUpdate()` by reading |
| A property at its default has no key (plain cell no longer shows a non-empty default) | changed | same, with a server config that left `encodeDefaults` unset | key absence tested; cell appearance not tested |
| A key the row serializer does not know is dropped | changed | changed | tested |
| Values are re-encoded by the client serializers | changed | changed | by reading |
| Unchanged: `onReceivingData` and the hash get raw rows; the refresh path | — | — | hook tested; hash by reading |

---
[← Index](../INDEX.md) · Map · [DIRECTIVE](DIRECTIVE.md) · [BRIEF](BRIEF.md) · **CONTRACT** · [LEDGER](LEDGER.md) · [PLAN](PLAN.md)
