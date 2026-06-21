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

**Status: Characterized (current)** — the `Deny`-side rows pinned by P1.1 (single-group `Deny` applies
with `upVote` ignored — `singleGroupDenyApplies_upVoteIgnored`; two-group `Deny` discards into the role
default — `twoGroupDeniesResolveToRoleDefault_theFootgun`). The full `upVote`/`Allow`-side matrix (the
`≥2, upVote==Allow & any Allow` and `upVote==Deny` rows, and the size-1 `Allow`/`Default` rows) remains
to add. `getGroupPermission` (`:302-348`) runs a Mongo aggregation: match `IUserGroup.userId == user` →
`$lookup` `roleInGroupColl` on
`groupOfUserId` where `appRoleId == appRole._id` → unwind → `replaceRoot` → `List<RoleInGroup>`
(decoded into the **file-private fixed-shape** class, not generic `GR` — `:326,353-366`, R6). For
`CrudTask`, the list is filtered to rows whose `crudTaskSet` contains the task (`:331-333`). Then:

| Matching group rows | Verdict | Anchor |
|---|---|---|
| 0 | role default (C4) | `:335` |
| 1 | that row's `permission` (`Default` ⇒ role default); **`upVote` ignored** | `:336-340` |
| ≥2, `upVote==Allow` & any `Allow` | `Allow` | `:341-343` |
| ≥2, `upVote==Deny` & any `Deny` | `Deny` | `:344-346` |
| ≥2, bias condition unmet | **role default — explicit grants discarded** | `:347` |

The last row is the **R4 foot-gun**: 2+ explicit `Deny`s under an `Allow`-biased role resolve to the
role default (possibly `Allow`). **Changed by D2.**

### C4 — `crudTaskSet`-miss default inversion

**Status: Characterized (current)** — pinned by P1.1 (`denyDefaultInvertsToAllowOnCrudTaskMiss_theFootgun`:
a `Deny`-default role denies the in-set task `Read` but **inverts to `Allow`** for the not-in-set task
`Update`). The `Allow`-default side of the inversion table remains to add. `buildDefaultAppRolePermission`
(`:279-292`) for `CrudTask`: when the task **is not** in `defaultCrudTaskSet`, the default permission
is **inverted** — `Allow`-default ⇒ `Deny`, `Deny`-default ⇒ **`Allow`** (`:285-290`). The direct-row
`CrudTask` path returns plain `Deny` for the same miss (`:221`). The two paths **disagree** on the
identical "task not in set" condition (R3). **Changed by D3.**

### C5 — Permission checks lazily provision `AppRole` rows (side-effecting read)

**Status: Characterized (current)** — Phase-1 pin pending (P1.2). The `appRoleBlock` passed to
`permissionState` is `appRoleColl.findOne(match) ?: appRoleColl.insertCrudRole(…)` (and the
single-action sibling `:136-138`). A permission **check** therefore performs a DB **write** the first
time a given role is queried. The insert does not route through `apiItemProcess`, so `allowApiCrud`,
change-logging, and per-action permission never run for it; it is **not** in [[repository-write-lifecycle]]
CONTRACT I7's catalogue of ungated raw writers. **Changed by D4.**

### C6 — Backend reach and fail-open default

**Status: Characterized (current)** — **not yet pinned**; the landed P1.1 covers only the Mongo
resolution algebra (C1–C4). The cross-engine reach + fail-open table below is documented from the
audit (R1/R7 upheld) and is pinned later by the cross-engine conformance profile (P1.3). Native
group/single-action resolution exists **only** in Mongo. Cross-engine reach (verified, R1/R7 upheld):

| Engine | Resolves groups? | No provider registered | Mongo coll booted in-process |
|---|---|---|---|
| Mongo | Yes (native) | n/a | full user→group→`upVote` |
| SQL | Only via the borrowed Mongo provider | **allow-all** (`SqlRepository.kt:444`) | group-aware via Mongo's docs |
| InMemory | Never (ignores registry) | **allow-all** (`InMemoryRepository.kt:412`) | **still allow-all**; Action path never calls the check |
| SSR | Delegates to backing repo (`SsrAuth.kt:35`) | follows backing engine | follows backing engine |

The agnostic `IRolePermissionProvider` exposes only `getCrudPermission` — no group, no single-action.
**Changed by D5 (reach) and D6 (fail-open default).**

---

## Layer T — Target properties (option chosen in the LEDGER)

### T1 — Resolution is total and never discards explicit grants

**Status: Target (pending D1, D2).** Every `(user, action[, crudTask])` query resolves
**deterministically to exactly `Allow` or `Deny`**. The role default applies **only** when there are
**zero** applicable explicit grants (direct or group). No path may silently fall through to a default
*after* explicit grants exist (closes R4), and the direct-row and group/default paths must agree on
the `crudTaskSet`-miss rule (closes R3, via D3). Pinned by the conformance suite across all claiming
engines.

### T2 — One resolution algebra, specified once, asserted everywhere

**Status: Target (pending D5).** The decision tree (precedence, conflict rule, `crudTaskSet`
semantics, single-action vs CRUD) is specified **once, engine-agnostically**, with each engine
supplying only a thin grant-fetch port. The same conformance assertions run against every engine that
claims to enforce. No engine carries a private copy of the policy (closes R1, R6, the C1/C2/C3/C4
Mongo-locality).

### T3 — Permission resolution is side-effect-free

**Status: Target (pending D4).** Evaluating a permission performs **no writes**. `AppRole`
provisioning, if retained at all, happens through an explicit, modeled, gated path (registration or
migration), never as a side effect of a check (closes R5; aligns with [[repository-write-lifecycle]]
I2's side-effect-free principle and I5/I7's "every write is a deliberate, modeled event").

### T4 — Safe default: fail closed or declare non-enforcement

**Status: Target (pending D6).** An engine/deployment that cannot resolve permissions either **fails
closed** on protected paths or **explicitly declares non-enforcement** (so the app cannot mistake
allow-all for "checked and allowed"). Silent allow-all on a path the application believes is protected
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
