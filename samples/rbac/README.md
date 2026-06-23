# RBAC walkthrough sample

A runnable, **database-free** demonstration of fsLib's role-based access control — the
`rbac-permission-resolution` blueprint, step P3.3 (closes finding R8: "no sample wires RBAC").

```bash
./gradlew :samples:rbac:run
```

## What it shows

The sample seeds the **shippable** in-memory grant port (`InMemoryRbacGrantPort`) and drives the **real**,
backend-agnostic resolver (`RbacResolver`) and membership API (`RbacMembership`) — the exact algebra the
MongoDB engine runs behind `IRoleInUserColl`. No mongod required.

1. **Group-only membership — the bypass fix (R12).** A user who holds a role *only* through a group (no
   direct row) is correctly seen by `hasSingleActionGrant` / `isAllowedSingleAction`. A raw
   `countDocuments(RoleInUser by userId+appRoleId)` would be group-blind and wrongly return `false` — the
   exact production bug this API closed.
2. **Direct-grant precedence (D1).** A direct grant outweighs the group, and `isAllowedSingleAction` **is**
   the precedence resolution, *not* a deny/allow union — so (as the walkthrough's `bob` shows) a direct
   `Deny` beats a group `Allow`, where a naive union would wrongly allow.
3. **Existence ≠ authorization (D8).** `hasSingleActionGrant` (does an edge exist?) and
   `isAllowedSingleAction` (is the user allowed?) answer deliberately different questions: a `Deny` edge
   exists yet does not authorize.
4. **Intra-group tie-break (D2).** `upVoteInGroup = Allow` opts a role into allow-override; otherwise the
   safe deny-override default applies.

## From this sample to production (MongoDB)

The sample uses `InMemoryRbacGrantPort` as a stand-in for the grant store. In a real app the same resolver
and membership API run over the MongoDB RBAC collections. Two boot steps replace the `port.seed*(...)` calls:

```kotlin
// 1) Register the RBAC provider once at boot (replaces the old construction side-effect — RBAC D10/P3.2a):
MongoRbac.register(roleInUserColl)            // roleInUserColl : your IRoleInUserColl subclass

// 2) Provision the app roles explicitly at boot (side-effect-free resolution — RBAC D4):
appRoleColl.ensureRoles(crudContainers, singleActions)
```

Then the **same** calls work, keyed by a real user id and app-role id:

```kotlin
// group-aware membership, no group-blind bypass:
roleInUserColl.hasSingleActionGrant(userId, appRoleId)     // existence (direct OR group)
roleInUserColl.isAllowedSingleAction(userId, appRoleId)    // effective authorization (the resolver)
```

With no `MongoRbac.register(...)` call, an enforcing repository **fails closed** on a protected write
(`permissionEnforcement = Enforce`, the default) — never a silent allow-all (RBAC D6). Change-log
collections are exempt by declaring `permissionEnforcement = Off`.
