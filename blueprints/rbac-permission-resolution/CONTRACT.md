# CONTRACT — RBAC Permission Resolution Invariants

> The durable spec. It has **two layers**:
>
> - **C-series (Characterized — current).** A precise description of what the code does **today**.
>   Phase 1 characterization tests freeze these so that every Phase-2 verdict change is a visible,
>   intended diff. A C-invariant is *not* an endorsement — several encode the very defects this
>   blueprint exists to fix; each names the decision (LEDGER Dx) that will change it.
> - **T-series (Target).** The properties the resolved design must satisfy **regardless of which
>   option each decision picks**. These are committed directions; the *option* that realizes them is
>   chosen in the LEDGER. A T-invariant is only **in force** once a decision is locked and the
>   conformance suite pins it.
>
> Each invariant carries a **Status**: *Characterized (current)* — pinned, or pending the Phase-1
> pin; *Target (pending Dx)* — agreed property, option/rollout pending; *Enforced* — true across all
> claiming engines and pinned by the suite.
>
> Line references are anchors to the state verified 2026-06-21 — re-locate by symbol when implementing.

---

## Layer C — Characterized current behavior (to be frozen by Phase 1)

### C1 — Resolution order (Mongo)

**Status: Characterized (current)** — root short-circuit and the overall direct→group→default order
pinned by P1.1 (`RbacPermissionResolutionCharacterizationTest`, Testcontainers / CI; skip-clean
locally). The `SingleAction`-vs-`CrudTask` duplication row and the full ordering matrix remain to add.
For a `(userSession, appRole, crudTask?)` query, `IRoleInUserColl.permissionState` (`:184-248`)
resolves in this order:

```
1. rootUser(userId) == true            -> Allow            (:190)
2. resolve appRole via appRoleBlock    (findOne ?: insert) (:191-196 — see C5)
3. direct IRoleInUser(userId, appRoleId) row exists?
      yes -> return its verdict, GROUPS NOT CONSULTED      (:197-226 — see C2)
4. else -> getGroupPermission(...)                          (:227-247 — see C3)
5. no group rows -> role default                            (see C4)
```

The `SingleAction` and `CrudTask` arms of the step-4 `when (roleType)` are **byte-identical** dead
duplication (`:227-247`, R9) — the real role-type divergence happens inside `getGroupPermission` and
`buildDefaultAppRolePermission`.

### C2 — Direct user row short-circuits groups (including `Default`)

**Status: Characterized (current)** — pinned by P1.1 for a `CrudTask` direct `Deny` row and a direct
`Default` row (`directDenyRowShadowsGroupAllow`, `directDefaultRowShadowsGroupAllow`); the direct
`Allow` row and the `SingleAction` role type remain to add. Any direct `IRoleInUser` row for
`(userId, appRoleId)` terminates resolution in every branch — `Allow`/`Deny`/`Default` all `return`
inside `roleInUser?.let { … }` (`:203-226`). A `Default` direct row resolves against
`appRole.defaultPermission`, **not** against groups. Consequence: groups are a fallback for users with
*no* direct row, never a compositional layer. **Ratified by D1 (user precedence) — this is the target
behavior (T5); the user-vs-group axis is unchanged in Phase 2, only frozen by characterization.**

### C3 — Group aggregation and `upVoteInGroup` tie-break (Mongo)

**Status: Superseded by P2.1 (D2 locked) — the R4 foot-gun is fixed.** The table below described the
**pre-fix** tie-break; the last row (≥2, bias unmet → role default, discarding explicit grants) was the
R4 foot-gun. **Current behavior** is now the **D2 total rule**: over the applicable group set, default
bias is **deny-override** and `upVote==Allow` is the per-role **allow-override** opt-in, applied
uniformly to single- and multi-group sets; the role default applies **only** when every grant is
`Default` — an explicit `Allow`/`Deny` is never discarded. Pinned by `multiGroupDeniesAreHonoredNotDiscarded`
(deny-override, red→green), `mixedGroupGrantsAllowWinUnderAllowOverride` (the `upVote==Allow` allow-override
branch), and `singleGroupDenyResolvesToDeny` (uniform single-group rule), all in CI. The aggregation shape
is unchanged: `getGroupPermission` matches `IUserGroup.userId == user` → `$lookup` `roleInGroupColl` on
`groupOfUserId` where `appRoleId == appRole._id` → unwind → `replaceRoot` → `List<RoleInGroup>` (still
decoded into the file-private fixed-shape class, R6 — untouched here, addressed by the P4.2 port).

Pre-fix table (historical):

| Matching group rows | Pre-fix verdict |
|---|---|
| 0 | role default (C4) |
| 1 | that row's `permission`; **`upVote` ignored** |
| ≥2, `upVote==Allow` & any `Allow` | `Allow` |
| ≥2, `upVote==Deny` & any `Deny` | `Deny` |
| ≥2, bias condition unmet | **role default — explicit grants discarded (R4)** |

### C4 — `crudTaskSet`-miss default inversion

**Status: Superseded by P2.2 (D3 locked) — the R3 inversion is fixed.** Pre-fix,
`buildDefaultAppRolePermission` for `CrudTask` **inverted** on a `crudTaskSet` miss (`Allow`-default ⇒
`Deny`, `Deny`-default ⇒ **`Allow`**), disagreeing with the direct-row path (miss ⇒ `Deny`).
**Current behavior** (D3 allow-list, no inversion): a task **in** `defaultCrudTaskSet` takes
`defaultPermission`; a task **not** in the set is uncovered → safe **`Deny`**. Direct and default paths
now agree on the "task not in set" condition. Pinned by `crudTaskSetMissUnderDenyDefaultDenies`
(red→green), runs in CI.

### C5 — Permission checks lazily provision `AppRole` rows (side-effecting read)

**Status: Characterized (current)** — Phase-1 pin pending (P1.2). The `appRoleBlock` passed to
`permissionState` is `appRoleColl.findOne(match) ?: appRoleColl.insertCrudRole(…)` (and the
single-action sibling `:146-149`). The insert call site is on the check path and does not route through
`apiItemProcess`, so `allowApiCrud`, change-logging, and per-action permission never run for it; it is
**not** in [[repository-write-lifecycle]] CONTRACT I7's catalogue of ungated raw writers. **Nuance
(verified 2026-06-21):** in-tree the `insertSingleActionRole`/`insertCrudRole` are **inert stubs**
returning `ItemState(isOk=false)` (`IAppRoleColl.kt:35-43`, no override), so a check performs **no
write** unless a downstream consumer overrides them — the hazard is the *mechanism* (an ungated write
invited on the read path), latent in-tree (R5 PARTIAL). **Changed by D4.**

### C6 — Backend reach and fail-open default

**Status: Characterized (current)** — **not yet pinned**; the landed P1.1 covers only the Mongo
resolution algebra (C1–C4). The cross-engine reach + fail-open table below is documented from the
audit (R1/R7 upheld) and is pinned later by the cross-engine conformance profile (P1.3). Native
group/single-action resolution exists **only** in Mongo. Cross-engine reach (verified, R1/R7 upheld):

| Engine | Resolves groups? | No provider registered | Mongo coll booted in-process |
|---|---|---|---|
| Mongo | Yes (native) | **allow-all** (`CollPermission.kt:38`, `roleInUserColl == null`) | full user→group→`upVote` |
| SQL | Only via the borrowed Mongo provider | **allow-all** (`SqlRepository.kt:444`) | group-aware via Mongo's docs |
| InMemory | Never (ignores registry) | **allow-all** (`InMemoryRepository.kt:412`) | **still allow-all**; Action path never calls the check |
| SSR | Delegates to backing repo (`SsrAuth.kt:35`) | follows backing engine | follows backing engine |

The agnostic `IRolePermissionProvider` exposes only `getCrudPermission` — no group, no single-action.
**Changed by D5 (reach, locked) and D6 (fail-open, locked).** D6 gates **all three** fail-open cells
above — SQL `:444`, InMemory `:412`, and **Mongo `CollPermission.kt:38`** — with the new
`permissionEnforcement` declaration (default `Enforce` ⇒ deny when no resolver is wired; `Off` ⇒ allow).

---

## Layer T — Target properties (option chosen in the LEDGER)

### T1 — Resolution is total and never discards explicit grants

**Status: Enforced on the Mongo engine (P2.1/P2.2, D2/D3); cross-engine pin pending P3.1.** Every
`(user, action[, crudTask])` query resolves **deterministically to exactly `Allow` or `Deny`**. The
role default applies **only** when there are **zero** applicable grants **or only `Default` grants** —
an explicit `Allow`/`Deny` grant is **never discarded** (a `Default` grant intentionally defers to the
role default: a direct `Default` row → `appRole.defaultPermission`, an all-`Default` group set → role
default). No path silently falls through to a default *after* an explicit `Allow`/`Deny` grant exists
(R4 closed by P2.1), and the direct-row and group/default paths agree on the `crudTaskSet`-miss rule
(R3 closed by P2.2). Pinned on Mongo by `multiGroupDeniesAreHonoredNotDiscarded` +
`crudTaskSetMissUnderDenyDefaultDenies` (CI); the cross-engine pin lands when the resolution algebra
moves to the shared layer (P3.1).

### T2 — One resolution algebra, specified once, asserted everywhere

**Status: Target (decided D5; pending P3.1 pin).** The decision tree (precedence, conflict rule,
`crudTaskSet` semantics, single-action vs CRUD) is specified **once, engine-agnostically**, with each
engine supplying only a thin typed grant-fetch port. The same conformance assertions run against every
engine that claims to enforce. No engine carries a private copy of the policy (closes R1, R6, the
C1/C2/C3/C4 Mongo-locality).

### T3 — Permission resolution is side-effect-free

**Status: Target (decided D4; pending P2.3 pin).** Evaluating a permission performs **no writes**. The
lazy `findOne ?: insert*` provisioning is removed from the check path (a missing role denies); `AppRole`
provisioning happens through the explicit `IAppRoleColl.ensureRoles(...)` boot path, never as a side
effect of a check (closes R5; aligns with [[repository-write-lifecycle]] I2's side-effect-free principle
and I5/I7's "every write is a deliberate, modeled event"). Pinned by the P1.2→P2.3 hook-invocation
red→green flip.

### T4 — Safe default: fail closed or declare non-enforcement (`permissionEnforcement`)

**Status: Target (decided D6; pending P2.4 pin).** An engine/deployment that cannot resolve permissions
either **fails closed** on protected paths or **explicitly declares** non-enforcement via the
`permissionEnforcement` member (default `Enforce`; `Off` opts out) — so the app cannot mistake allow-all
for "checked and allowed". The declaration gates **all three** fail-open sites (SQL `:444`, InMemory
`:412`, Mongo `CollPermission.kt:38`). Silent allow-all on a path the application believes is protected
is forbidden (closes R7). The samples/tests "permission-free" mode remains valid only as an
*explicit* declaration, matching the conformance harness's `enforcesPermissions=false` profile.

### T5 — Direct user grant outweighs group grants (LOCKED — D1)

**Status: Target (decided D1, pending pin P2.x parity).** A direct `IRoleInUser` grant for
`(userId, appRoleId)` is **authoritative**: it resolves the query for that user and role without
consulting groups, for `Allow`, `Deny`, **and** `Default` (a `Default` direct row resolves to the
role default). Group grants apply **only** to users with no direct row for that role. This ratifies
C2 — the user-vs-group axis is unchanged from current behavior; it is frozen by characterization
(P1.1) and asserted by the cross-engine suite once the resolution port lands (P3.1). The remaining
multi-grant conflict resolution (D2) is therefore **intra-group only**.

### T6 — Two distinct group-aware SingleAction membership queries (existence ≠ authz)

**Status: Target (pending D7–D9).** fsLib exposes **two** first-class queries keyed by
`(userId/User, appRoleId)` → `Boolean` — with **separate, non-overlapping semantics** that must never
be blended into one verdict:

- **T6a — `hasSingleActionGrant` (pure existence).** ∃ *any* grant edge — a direct `IRoleInUser` row
  **or** a group grant (`UserGroup`→`RoleInGroup`) — **ignoring** `permission`/defaults/`upVote`. A
  commutative **union** that resolves no verdict; it reads no `AppRole`. It is **not** an authorization
  decision and must not be presented as one. D1 precedence is N/A (it never asks "who wins").

- **T6b — `isAllowedSingleAction` (effective authz).** The **D1/T5 precedence resolution restricted to
  SingleAction**, re-keyed and returning a `Boolean`: a direct grant is **authoritative** (resolved to
  `Allow`/`Deny`/`Default`→default); groups are consulted **only when there is no direct row**, then by
  the D2 intra-group rule. It is the **same engine/semantics as `permissionState`** (T2) — so a direct
  `Allow` + group `Deny` is **`Allow`**, identical to `permissionState`. It is explicitly **not** a flat
  deny-override union (that would contradict T5). Any future hard-deny-over-direct layer is a separate,
  explicit decision amending T5/D1 — never folded in here.

Both queries: (i) **never materialize** grant docs — project only needed fields (`countDocuments`/
`limit(1)`/`$exists` for T6a; a typed `{permission, crudTaskSet}` projection for T6b), never `.first()`
on the full `RoleInUser` doc (R13); (ii) perform **no `AppRole` provisioning** (T3/D4); (iii) live in the
**backend-agnostic** layer over the **D5 typed grant-fetch port** (a boolean-only port cannot carry the
permission/defaults/upVote that T6b needs) so SQL and InMemory answer them natively; (iv) obey the
**fail-closed** default (T4/D6) when no RBAC backend is configured. Pinned by the cross-engine
membership conformance coverage (P4).
