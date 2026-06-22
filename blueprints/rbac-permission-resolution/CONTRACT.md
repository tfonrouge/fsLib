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

### C1 — Resolution order

**Status: Characterized (P1.1) + extracted to the shared algebra (P3.1a).** Pinned by P1.1
(`RbacPermissionResolutionCharacterizationTest`, CI) and, since P3.1a, by the no-DB `RbacResolverTest`
(13 cases). The Mongo entry point `IRoleInUserColl.permissionState` keeps the root short-circuit and the
`appRoleBlock` AppRole resolution, then **delegates to the backend-agnostic `RbacResolver.resolve(...)`
over the Mongo `IRbacGrantPort`**, wrapping the verdict with `buildSimpleState` (output unchanged). The
resolution order (now in `RbacResolver`):

```
1. port.isRootUser(userId)                 -> Allow
2. (entry point) resolve appRole via appRoleBlock: findOne ?: deny  (no provisioning, D4/P2.3 — see C5)
3. port.fetchDirectGrant(userId, appRoleId) != null?
      yes -> its verdict; GROUPS NOT CONSULTED          (see C2 / D1)
4. else -> port.fetchGroupGrants(...) resolved by the D2 tie-break   (see C3)
5. no applicable grant -> role default                  (see C4 / D3)
```

The former byte-identical `when (roleType)` arms (R9) were collapsed (P2.5) and then extracted into
`RbacResolver` — the real role-type divergence lives in `RbacResolver`'s group/default helpers.

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
branch), and `singleGroupDenyResolvesToDeny` (uniform single-group rule), all in CI. Since P3.1a this
group resolution lives behind `IRbacGrantPort.fetchGroupGrants` (the same `match` → `$lookup` on
`roleInGroupColl` where `appRoleId == appRole._id` → unwind → `replaceRoot` pipeline) — now with a
`$project` to `{permission, crudTaskSet}` decoding into the typed **`RoleGrant`**; the file-private
`RoleInGroup`/`GroupOfUser` decode classes are deleted (**R6 closed by P3.1a**, not P4.2). The D2
tie-break itself now lives in `RbacResolver`.

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

**Status: Superseded by P2.3 (D4 locked) — the lazy provisioning is removed.** Pre-fix, the
`appRoleBlock` did `findOne(match) ?: insertCrudRole/insertSingleActionRole` on the check path — an
ungated write invited on the read path (latent in-tree, where `insert*` were inert stubs). P1.2
characterized the hazard as **hook invocation** (`missingSingleActionRoleInvokesProvisioningHook`,
`c5a09a6c`). **Current behavior** (P2.3): the `?: insert*` is removed from all three sites — a missing
role denies, and the provisioning hook is **not** invoked; roles are provisioned explicitly via
`IAppRoleColl.ensureRoles(...)`. Pinned by `missingSingleActionRoleDeniesWithoutProvisioning` (red→green)
+ `RbacEnsureRolesTest` (delegation, runs locally).

### C6 — Backend reach and fail-open default

**Fail-open half: Superseded by P2.4 (D6) — fixed.** **Reach half: Characterized (current); pending
D5/P3.1.** The table below documented the pre-fix **fail-open** default (allow-all when no resolver was
wired) at all three engines plus the still-Mongo-only resolution reach (R1).

| Engine | Resolves groups? | No provider registered — *pre-fix* | Mongo coll booted in-process |
|---|---|---|---|
| Mongo | Yes (native) | ~~allow-all (`CollPermission.kt:38`)~~ → **D6 fail-closed (or `Off`)** | full user→group→`upVote` |
| SQL | Only via the borrowed Mongo provider | ~~allow-all (`SqlRepository.kt:444`)~~ → **D6 fail-closed (or `Off`)** | group-aware via Mongo's docs |
| InMemory | Never (ignores registry) | ~~allow-all~~ → declares **`Off`** (named non-enforcing engine); Action path now calls the check | non-enforcing |
| SSR | Delegates to backing repo (`SsrAuth.kt:35`) | follows backing engine | follows backing engine |

**Current behavior (P2.4):** the `permissionEnforcement` declaration (default `Enforce`) gates all three
sites — an enforcing engine with no resolver **fails closed** on a remote write; `Off` opts out. Pinned
by `unconfiguredDefaultFailsClosedForEnforcingEngines` (SQL fail-closed + Memory-allows green; Mongo in
CI). The **reach** half (group/single-action resolution being Mongo-only; the agnostic provider exposing
only `getCrudPermission`) is unchanged and still tracked by D5/P3.1.

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

**Status: Partially enforced (P3.1a) — pending cross-engine pin (P3.1b).** The decision tree
(precedence, conflict rule, `crudTaskSet` semantics, single-action vs CRUD) is now specified **once,
engine-agnostically** in `RbacResolver` over the `IRbacGrantPort`, and pinned by 13 no-DB
`RbacResolverTest` unit tests. Mongo delegates to it (its `$lookup` is one port impl; closes R6, the
Mongo private-decode locality). **Remaining:** InMemory port impl + the cross-engine conformance pin
(same resolver assertions per engine) land in P3.1b; SQL native RBAC + explicit registration (R10) stay
deferred (P3.2 / a separate SQL sub-blueprint).

### T3 — Permission resolution is side-effect-free

**Status: Enforced on the Mongo engine (P2.3, D4); cross-engine pin pending P3.1.** Evaluating a
permission performs **no writes**. The lazy `findOne ?: insert*` provisioning is removed from all three
check sites (a missing role denies); `AppRole` provisioning happens through the explicit
`IAppRoleColl.ensureRoles(...)` boot path, never as a side effect of a check (closes R5; aligns with
[[repository-write-lifecycle]] I2's side-effect-free principle and I5/I7's "every write is a deliberate,
modeled event"). Pinned on Mongo by the P1.2→P2.3 hook-invocation red→green flip
(`missingSingleActionRoleDeniesWithoutProvisioning`) plus `RbacEnsureRolesTest`.

### T4 — Safe default: fail closed or declare non-enforcement (`permissionEnforcement`)

**Status: Enforced (P2.4, D6).** An engine/deployment that cannot resolve permissions either **fails
closed** on protected paths or **explicitly declares** non-enforcement via the `permissionEnforcement`
member (default `Enforce`; `Off` opts out) — so the app cannot mistake allow-all for "checked and
allowed". The declaration gates **all three** former fail-open sites (SQL `getCrudPermission`, InMemory
`getCrudPermission` + Action branch, Mongo `CollPermission`). Silent allow-all on a protected path is
forbidden (closes R7). The samples/tests "permission-free" mode is now an *explicit* declaration
(`InMemoryRepository` declares `Off`; the conformance Mongo repos declare `Off`). Pinned by
`unconfiguredDefaultFailsClosedForEnforcingEngines` (SQL fail-closed + Memory-allows green; Mongo in CI).

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
