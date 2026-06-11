# Blueprints Index

> Design-artifact root for fsLib. Proportionate blueprinting under the **cathedral premise**
> (business-blueprint **LIBRARY** mode). Artifacts are the written, test-pinnable contract that
> code KDoc and the conformance suite reference — not ceremony.

| Blueprint | Mode | Status | Summary |
|---|---|---|---|
| [repository-write-lifecycle](repository-write-lifecycle/) | LIBRARY | Complete · shipped in 4.0.0 · optional P3.x/G2 open | Documented and unified the `IRepository` write / delete / lifecycle contract across the Mongo (`Coll`), SQL (`SqlRepository`), and in-memory (`InMemoryRepository`) engines; added a first-class generic-CRUD gate (`allowApiCrud`); pinned every invariant with a cross-engine conformance suite (green in CI, including real Mongo). Released as `4.0.0` (tag `v4.0.0`, Maven Central). |

## Artifacts per blueprint

- `BRIEF.md` — goal, motivation, findings register, scope, blast radius, definition of done.
- `CONTRACT.md` — the durable `IRepository` invariants (the spec the code and tests must satisfy).
- `LEDGER.md` — decisions with rationale + falsification conditions (both-directions discipline).
- `PLAN.md` — the ordered, SAFE/BREAKING-labeled implementation steps and their dependencies.
