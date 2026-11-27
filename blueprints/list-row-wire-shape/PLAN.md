# PLAN — list-row-wire-shape

Patch (SemVer 6.5.1): no public API change.

| ID | Step | Advances | File anchors | Status |
|----|------|----------|--------------|--------|
| **P1.1** | Characterization test first, through the production loader path (stub `fetch` + a route-only `RpcServiceManager`); red reproduces the incident (`owner: {"code":null}`). | OC-01..03 | `fullstack/src/jsTest/.../tabulator/FirstLoadRowShapeTest.kt` (new) | ✅ done (first draft: red 4 of 6, controls green) |
| **P1.2** | `canonicalRows` applied in the `ajaxRequestFunc` wrapper (not in `promise()`); `encodeDefaults = false` in `jsonHelper`; bounded report. | OC-01..03 | `fullstack/src/jsMain/.../tabulator/TabulatorViewList.kt` | ✅ done (6 mutants, each red) |
| **P1.3** | Real refresh via `ViewList.dataUpdate()` with a changed visible value; hook contract by key presence; exact restore of `ViewRegistry`'s list service manager via `internal listServiceManagerOrNull`. | OC-02 | test + `fullstack/src/jsMain/.../config/ViewRegistry.kt` | ✅ done |
| **P1.4** | Full verification with JDK 25: `./gradlew build`, then `./gradlew :fullstack:jsBrowserTest --rerun` separately, on the final code; mutation check of every guard. | OC-01..03 | — | ✅ 2026-11-26 run 1 (pre-R1 harness): build green; `--rerun` 144 tests, 0 failed, 0 skipped; 6 mutants red. Run 2 (final harness: adds the unknown-key test and the `configViewListMap` restore; production files unchanged, `TabulatorViewList.kt` blob `854eb63e`): build green; `--rerun` 145 tests, 0 failed, 0 skipped; the same 6 mutants red; post-mutation full rerun green. Run 3 (after ROAR round 1 dispositions, L-005; `TabulatorViewList.kt` KDoc only, blob `4df96be5`; harness: selection test, detached codec table, detach-before-dispose): build green; `--rerun` 146 tests, 0 failed, 0 skipped; no stray load errors in the suite's output; the 6 mutants red (anchors as executed, not as first registered — ROAR round 2 N-1). Run 4 (after round 2 dispositions, L-005 addendum; production file unchanged, blob `4df96be5`; test KDoc only): the register below replayed literally by a parser over this file — every anchor matched exactly one whole line, all 6 red sets equal the register, file restored to the same blob; build green; `--rerun` 146 tests, 0 failed, 0 skipped; no stray load errors. Run 5 (ROAR round 3 P-1: test KDoc only; production unchanged, blob `4df96be5`): build green; `--rerun` 146 tests, 0 failed, 0 skipped; no stray load errors |
| **P1.5** | CHANGELOG `[Unreleased]` (Fixed + Known limitation) and MIGRATION 6.5.0 → 6.5.1 (table row + section). | OC-01..03 | `CHANGELOG.md`, `MIGRATION.md` | ✅ done |
| **P1.6** | ROAR review (read-only) on the fingerprinted diff (authorised by the Owner, chat 2026-11-26). | — | — | Round 1: 1 Blocking (R-1) + 5 Non-blocking, all confirmed in scope and fixed (L-005). Round 2: 0 Blocking, 5 Non-blocking (N-1..N-5), all confirmed and fixed; N-3 resolved by Owner confirmation (L-005). Round 3: 0 Blocking, 1 Non-blocking (P-1, a test KDoc claim), confirmed and fixed (Owner, chat 2026-11-26: "corrige P-1 y pide la cuarta ronda de ROAR"). Round 4: REVIEW COMPLETE — no claims to verify. |
| **P1.7** | On Owner authorisation (chat 2026-11-26: "directo a master, push y hagamos un publish to sonatype"): fix commit `b12cd5c2` (the ROAR round-4 content) and release commit (6.5.1 bump, CHANGELOG, MIGRATION, docs sweep) on `master`, push, Central Portal publication. Tag `v6.5.1` and GitHub Release: not yet authorised. | — | `gradle/libs.versions.toml` | ✅ committed; publication status recorded in LEDGER L-006 / chat |
| **P1.8** | Consumer verification on the published artifact (outside the OC set): mppErsaPack harness and sweep, mppDulce tests and sweep — recorded, not driven, by fsLib. | — | LEDGER L-007 | ✅ recorded 2026-11-26 |

## Mutation register (replayable)

Target file: `fullstack/src/jsMain/kotlin/com/fonrouge/fullStack/tabulator/TabulatorViewList.kt`.
**Rule:** each mutant replaces exactly one **whole line** — the line equal, character for character
(leading spaces included), to the `find` block — with the whole `replace` block. Before applying, the
`find` line must occur exactly once as a whole line (`grep -cxF -- '<find>' <file>` returns `1`). Run
`./gradlew :fullstack:jsBrowserTest --tests 'com.fonrouge.fullStack.tabulator.FirstLoadRowShapeTest'`
(JDK 25), then restore the file and check `git hash-object` returns the pre-mutation blob.
Baseline: 9 tests, none red. The blocks below are fenced so their whitespace is exact.

### M1 — no canonicalization on the AJAX loader path

find:
```text
                    response.data = canonicalRows(response.data)
```
replace:
```text
                    // M1
```
Red: `nullParentOnFirstLoadIsNotMaterializedAndDecodes`, `firstLoadAndRefreshGiveTheSameRowShape`, `consumerEncodeDefaultsDoesNotLeakIntoRows`, `selectingARowWithANullParentDecodesWithoutAFormatter`.

### M1b — canonicalize inside `promise()`, before the hash and the hook (the rejected design)

find:
```text
                jsonObj.contentHashCode = listContentHash(jsonObj)
```
replace:
```text
                jsonObj.data = canonicalRows(jsonObj.data); jsonObj.contentHashCode = listContentHash(jsonObj)
```
Red: `nullParentOnFirstLoadIsNotMaterializedAndDecodes`, `firstLoadAndRefreshGiveTheSameRowShape`.

### M2 — `encodeDefaults` pin removed (the line becomes empty)

find:
```text
        encodeDefaults = false
```
replace:
```text

```
Red: `consumerEncodeDefaultsDoesNotLeakIntoRows`.

### M3 — undecodable rows dropped

find:
```text
            out.push(canonical)
```
replace:
```text
            if (canonical !== row) out.push(canonical)
```
Red: `undecodableRowIsKeptInPlaceAndReported`.

### M4 — no report

find:
```text
                console.error(
```
replace:
```text
                listOf<Any?>(
```
Red: `undecodableRowIsKeptInPlaceAndReported`.

### M5 — the report leaks the row

find:
```text
                        "for the grid (${e::class.simpleName ?: "error"}); kept as received."
```
replace:
```text
                        "for the grid (${e::class.simpleName ?: "error"}); kept as received.", JSON.stringify(row)
```
Red: `undecodableRowIsKeptInPlaceAndReported`.

## Directive Completeness

DIRECTIVE R2 APPROVED 2026-11-26 (R1: L-001; R2: L-005).

| OC | Coverage set | State | Assurance |
|----|--------------|-------|-----------|
| OC-01 | P1.1, P1.2, P1.4 | MET | REPLAYABLE |
| OC-02 | P1.2, P1.3, P1.4 | MET | REPLAYABLE |
| OC-03 | P1.2, P1.4 | MET | REPLAYABLE |

---
[← Index](../INDEX.md) · Map · [DIRECTIVE](DIRECTIVE.md) · [BRIEF](BRIEF.md) · [CONTRACT](CONTRACT.md) · [LEDGER](LEDGER.md) · **PLAN**
