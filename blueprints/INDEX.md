# Blueprints Index

> Design-artifact root for fsLib. Proportionate blueprinting under the **cathedral premise**
> (business-blueprint **LIBRARY** mode). Artifacts are the written, test-pinnable contract that
> code KDoc and the conformance suite reference — not ceremony.

| Blueprint | Mode | Status | Summary |
|---|---|---|---|
| [repository-write-lifecycle](repository-write-lifecycle/) | LIBRARY | Complete · shipped in 4.0.0 · optional P3.x/G2 open | Documented and unified the `IRepository` write / delete / lifecycle contract across the Mongo (`Coll`), SQL (`SqlRepository`), and in-memory (`InMemoryRepository`) engines; added a first-class generic-CRUD gate (`allowApiCrud`); pinned every invariant with a cross-engine conformance suite (green in CI, including real Mongo). Released as `4.0.0` (tag `v4.0.0`, Maven Central). |
| [navigable-destinations](navigable-destinations/) | LIBRARY | ACTIVE · **D1 + D1-sub + D2 LOCKED; D3 + D3b + D4 + D5 OPEN** · Layer C characterized (13 verified findings); P1 characterization shipped (`BaseUrlDerivationTest` + `BaseUrlCharacterizationTest`, and the css-loader that resurrected 17 dormant fsLib tests) · next: lock the rest, then Phase 2 (incl. D1-sub's duplicate-`baseUrl` rejection, gated on an mppErsaPack collision sweep) | Make a **navigable destination** a first-class, inspectable concept: label, filter, id/action and target URL readable **without rendering the view** — so a menu, a home card, a search palette and a documentation link can all read one declaration. Drivers: documentation citing menu paths that move silently (a prod doc shipped with a path that does not exist), and label drift already in production ("Control de Calidad" duplicated across menu and card while its container says "Pasos de Piezas"). Decides four contracts the spike surfaced: `baseUrl` derivation (not uniform — plain `ConfigView` matching its class name is a coincidence), the `ICommonContainer` overload (it is the **RBAC identity**, `classOwner`, *and* the labels), filter **default vs imposed scope**, and a help-doc navigation bridge (in-document `#/` links are dead — they resolve against the `blob:` base). |
| [rbac-permission-resolution](rbac-permission-resolution/) | LIBRARY | D1–D10 locked · foot-guns + fail-open closed (P2) · resolver over two real ports (P3.1a/b) · membership API shipped (P4) · P3.2a/b shipped (explicit `MongoRbac.register` registrar + unified Mongo dispatch; split-brain resolved, Mongo `enforcesPermissions=true`) · P3.3 RBAC walkthrough sample shipped (`samples/rbac`) · open: P3.2c surface-widening, native SQL RBAC port | Specify, decide, and unify RBAC permission resolution (user **and** group action assignment) into a total, engine-agnostic algebra; close the default-inversion and discarded-deny foot-guns; make permission checks side-effect-free; replace the fail-open default; add a group-aware `(userId, appRoleId)` membership API; pin with characterization + cross-engine conformance tests. |

## Artifacts per blueprint

- `BRIEF.md` — goal, motivation, findings register, scope, blast radius, definition of done.
- `CONTRACT.md` — the durable invariants / current-vs-target contract the code and tests must satisfy.
- `LEDGER.md` — decisions with rationale + falsification conditions (both-directions discipline).
- `PLAN.md` — the ordered, SAFE/BREAKING-labeled implementation steps and their dependencies.
