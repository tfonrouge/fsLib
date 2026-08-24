# FSLib

**[Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html) full-stack CRUD library for [MongoDB](https://www.mongodb.com/) and SQL backends with [KVision](https://kvision.io/) frontend.**

FSLib provides a backend-agnostic repository pattern, declarative view configuration, Tabulator-based data grids, role-based access control, change logging, and shared data models across JVM/JS targets. It eliminates repetitive CRUD boilerplate so you can focus on business logic.

---

## Key Features

- **Modular Database Engines** — MongoDB (via [KMongo](https://litote.org/kmongo/)) and SQL (via [Exposed](https://github.com/JetBrains/Exposed)) are independent, optional modules. Use one, the other, or both through a single `IRepository` interface with cross-engine dependency checking.
- **Full-Stack Type Safety** — Shared Kotlin models, serializers, and RPC service definitions between server and browser via [Kilua RPC](https://github.com/rjaros/kilua-rpc).
- **Declarative View System** — Configure list and item views with `ConfigViewList` / `ConfigViewItem`. The framework handles routing, pagination, forms, and CRUD operations.
- **Entity Registration DSL** — `simpleContainer()` factories for data entities, `simpleCommon()` for non-data views (landing pages, dashboards), `StandardCrudService` for zero-boilerplate service delegation, and `registerEntityViews()` for declarative view wiring.
- **[Tabulator](https://tabulator.info/) Integration** — Server-side pagination, filtering, and sorting out of the box with `TabulatorViewList`.
- **Lifecycle Hooks** — `onQueryCreate`, `onBeforeUpdateAction`, `onAfterDeleteAction`, `onValidate`, and many more hooks on the repository for validation, transformation, and side effects.
- **Role-Based Access Control** — Built-in permission system with users, groups, roles, and per-CRUD-task permissions. Decoupled from any specific database engine via `IRolePermissionProvider`.
- **Change Logging** — Automatic audit trail recording before/after snapshots on create, update, and delete operations.
- **File Attachments** — `DataMedia` support (via the `:media` module) for managing file uploads with thumbnails and metadata.
- **Help Documentation** — Module-scoped contextual help with tutorial and quick-reference HTML pages, auto-discovered per view.
- **Multiple ID Types** — `OId` (MongoDB ObjectId), `IntId`, `LongId`, `StringId` — all with custom serializers.
- **Single Collection Inheritance** — Store multiple entity subtypes in one MongoDB collection with a shared interface, discriminator field, abstract `Coll`, and subtype-specific repositories. Shared lookups, hooks, and indexes are defined once in the abstract base.
- **In-Memory Repository** — The `:memorydb` module provides an `InMemoryRepository` for samples, tests, and prototyping without any database engine.
- **Named Routes & API Contract** — Use Kilua RPC's `@RpcBindingRoute` annotation for human-readable route paths (`/rpc/ITaskService.apiList`). The `RouteContract` class exposes a `/apiContract` endpoint for third-party client (Android, etc.) route discovery.
- **Server-Side Rendering** — The `:ssr` module provides SSR support using [Ktor](https://ktor.io/) HTML builder.

---

## Architecture

```
your-app  ──>  fullstack  ──>  core
               mongodb    ──>  fullstack, core
               sql        ──>  fullstack, core
               memorydb   ──>  fullstack, core
               media      ──>  fullstack, core, mongodb
               ssr        ──>  fullstack, core, mongodb
```

| Module | Purpose |
|--------|---------|
| **`:core`** | Platform-independent foundation: `BaseDoc<ID>`, ID types, annotations, serializers, state management, user/role models, API framework, date/math utilities. |
| **`:fullstack`** | Core library. **jvmMain**: `IRepository` interface, `IRolePermissionProvider`, `PermissionRegistry`, permissions, change logging, `RouteContract` for API contract discovery, [Ktor](https://ktor.io/) server stack. **jsMain**: View system, configuration, [Tabulator](https://tabulator.info/) wrappers, layout helpers, `ViewRegistry`. **commonMain**: Shared RPC interfaces via [Kilua RPC](https://github.com/rjaros/kilua-rpc). |
| **`:mongodb`** | MongoDB engine (JVM-only). `Coll` implementation with aggregation pipelines, lookups, filtering, change logging, and role-based access via [KMongo](https://litote.org/kmongo/) coroutine driver. |
| **`:sql`** | SQL engine (JVM-only). `SqlRepository` implementation using [Exposed](https://github.com/JetBrains/Exposed) for relational database access with type-aware filtering and identifier quoting. |
| **`:memorydb`** | In-memory database engine (JVM-only). `InMemoryRepository` using `ConcurrentHashMap` for storage. Designed for samples, tests, and prototyping — no database engine required. |
| **`:media`** | Extensions: `DataMedia` (file attachments) and `ChangeLog` views built on top of `:fullstack`. |
| **`:ssr`** | Server-side rendering with [Ktor](https://ktor.io/) HTML builder. |

### Technology Stack

| Component | Technology | Version |
|-----------|-----------|---------|
| Language | [Kotlin](https://kotlinlang.org/) (Multiplatform) | 2.4.x |
| Backend | [Ktor](https://ktor.io/) (Netty) | 3.4.x |
| MongoDB | [KMongo](https://litote.org/kmongo/) (coroutine) | 5.6.x |
| SQL | [Exposed](https://github.com/JetBrains/Exposed) | 0.61.x |
| Frontend | [KVision](https://kvision.io/) | 9.6.x |
| RPC | [Kilua RPC](https://github.com/rjaros/kilua-rpc) | 0.0.45 |
| Serialization | [kotlinx-serialization](https://github.com/Kotlin/kotlinx.serialization) | 1.11.x |
| JVM | Toolchain 25 | |

> **Java 25 is required since 6.0.0** — build *and* runtime. KVision 9.6.0's runtime artifacts are
> Java 25 bytecode, so an earlier JRE fails at class-load. Need Java 21? Use `5.0.0`, which carries the
> same library behavior on KVision 9.5.0 / Kotlin 2.3.20.

---

## Installation

FSLib is available on [Maven Central](https://central.sonatype.com/namespace/com.fonrouge.fslib).

### Gradle (Kotlin DSL)

Add the dependency to your module's `build.gradle.kts`:

```kotlin
// Version catalog (gradle/libs.versions.toml)
[versions]
fslib = "6.2.0"

[libraries]
fslib-core = { module = "com.fonrouge.fslib:core", version.ref = "fslib" }
fslib-fullstack = { module = "com.fonrouge.fslib:fullstack", version.ref = "fslib" }
fslib-mongodb = { module = "com.fonrouge.fslib:mongodb", version.ref = "fslib" }
fslib-sql = { module = "com.fonrouge.fslib:sql", version.ref = "fslib" }
fslib-memorydb = { module = "com.fonrouge.fslib:memorydb", version.ref = "fslib" }
fslib-media = { module = "com.fonrouge.fslib:media", version.ref = "fslib" }
fslib-ssr = { module = "com.fonrouge.fslib:ssr", version.ref = "fslib" }
```

```kotlin
// build.gradle.kts — In-memory (prototyping/samples, no database required)
kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api("com.fonrouge.fslib:fullstack:6.2.0")
            }
        }
        jvmMain {
            dependencies {
                implementation("com.fonrouge.fslib:memorydb:6.2.0")
            }
        }
    }
}
```

```kotlin
// build.gradle.kts — MongoDB application
kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api("com.fonrouge.fslib:fullstack:6.2.0")
            }
        }
        jvmMain {
            dependencies {
                implementation("com.fonrouge.fslib:mongodb:6.2.0")
            }
        }
    }
}
```

```kotlin
// build.gradle.kts — SQL application
kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api("com.fonrouge.fslib:fullstack:6.2.0")
            }
        }
        jvmMain {
            dependencies {
                implementation("com.fonrouge.fslib:sql:6.2.0")
            }
        }
    }
}
```

```kotlin
// build.gradle.kts — Hybrid (MongoDB + SQL)
kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api("com.fonrouge.fslib:fullstack:6.2.0")
            }
        }
        jvmMain {
            dependencies {
                implementation("com.fonrouge.fslib:mongodb:6.2.0")
                implementation("com.fonrouge.fslib:sql:6.2.0")
            }
        }
    }
}
```

### Publishing to Local Maven

```bash
./gradlew publishToMavenLocal -PSNAPSHOT
```

This publishes `:core`, `:fullstack`, `:mongodb`, `:sql`, `:memorydb`, `:media`, and `:ssr` to your local Maven repository (`~/.m2/repository`) as `6.0.0-SNAPSHOT`.

The `-PSNAPSHOT` flag is **required**: publishing a release version to `~/.m2/` would silently shadow the official Maven Central artifact for every project on the machine, so a bare `publishToMavenLocal` is blocked and fails at configuration time. Use `-PFORCE_LOCAL` only if you genuinely need to override that. See [Local Development with SNAPSHOT](#local-development-with-snapshot).

---

## Quick Start

### 1. Define a Model (commonMain)

```kotlin
@Serializable
@Collection("customers")
data class Customer(
    override val _id: OId<Customer> = OId(),
    val name: String = "",
    val email: String = "",
    val active: Boolean = true,
) : BaseDoc<OId<Customer>>
```

### 2. Define a Common Container (commonMain)

```kotlin
object CommonCustomer : ICommonContainer<Customer, OId<Customer>, CustomerFilter>(
    itemKClass = Customer::class,
    filterKClass = CustomerFilter::class,
    labelItem = "Customer",
    labelList = "Customers",
    labelId = { it?.name ?: "" },
)
```

```kotlin
// Or use the simpleContainer factory (when using ApiFilter):
val CommonCustomer = simpleContainer<Customer, OId<Customer>>(
    labelItem = "Customer",
    labelList = "Customers",
    labelId = { it?.name ?: "" },
)
```

### 3. Define an RPC Service (commonMain)

```kotlin
@KiluaRpcServiceName("ICustomerService")
interface ICustomerService {
    suspend fun apiItem(iApiItem: IApiItem<Customer, OId<Customer>, CustomerFilter>): ItemState<Customer>
    suspend fun apiList(apiList: ApiList<CustomerFilter>): ListState<Customer>
}
```

### 4. Implement the Repository (jvmMain — MongoDB)

```kotlin
class CustomerColl : Coll<Customer, OId<Customer>, CustomerFilter, OId<User>>(
    commonContainer = CommonCustomer,
    mongoDatabase = MongoDb.database,
) {
    override fun findItemFilter(apiFilter: CustomerFilter): Bson? {
        // Custom filtering logic
        return apiFilter.nameSearch?.let {
            Customer::name regex Regex(it, RegexOption.IGNORE_CASE)
        }
    }
}
```

### 5. Implement the Repository (jvmMain — SQL Alternative)

```kotlin
class CustomerSqlRepo : SqlRepository<Customer, OId<Customer>, CustomerFilter, OId<User>>(
    commonContainer = CommonCustomer,
    sqlDatabase = mySqlDatabase,
) {
    override fun buildWhereFromApiFilter(
        apiFilter: CustomerFilter,
        whereClauses: MutableList<String>,
        whereArgs: MutableList<Pair<IColumnType<*>, Any?>>,
    ) {
        apiFilter.nameSearch?.let {
            whereClauses += "name LIKE ?"
            whereArgs += VarCharColumnType() to "%$it%"
        }
    }
}
```

### 6. Configure Views (jsMain)

```kotlin
// List view configuration (in ViewListCustomer companion)
companion object {
    val configViewList = configViewList(
        viewKClass = ViewListCustomer::class,
        commonContainer = CommonCustomer,
        apiListFun = ICustomerService::apiList,
    )
}

// Item view configuration (in ViewItemCustomer companion)
companion object {
    val configViewItem = configViewItem(
        viewKClass = ViewItemCustomer::class,
        commonContainer = CommonCustomer,
        apiItemFun = ICustomerService::apiItem,
    )
}

// Register views in App.start() using the DSL:
val reg = registerEntityViews(getServiceManager<ICustomerService>()) {
    list(ViewListCustomer.configViewList, isDefault = true)
    item(ViewItemCustomer.configViewItem)
}
KVWebManager.initialize { defaultView = reg.defaultView }
```

### 7. Implement Views (jsMain)

```kotlin
class ViewListCustomer : ViewList<Customer, OId<Customer>, CustomerFilter, Unit>() {
    override val configView = ConfigViewListCustomer

    override fun Container.displayPage() {
        fsTabulator(viewList = this@ViewListCustomer) {
            addColumn("Name") { it.name }
            addColumn("Email") { it.email }
            addColumn("Active") { if (it.active) "Yes" else "No" }
        }
    }
}

class ViewItemCustomer : ViewItem<Customer, OId<Customer>, CustomerFilter>() {
    override val configView = ConfigViewItemCustomer

    override fun Container.pageItemBody(): FormPanel<Customer> = viewFormPanel {
        formRow {
            add(Customer::name, Text(label = "Name"))
            add(Customer::email, Text(label = "Email"))
        }
    }
}
```

---

## Repository Lifecycle Hooks

The `IRepository` interface provides hooks at every stage of CRUD operations:

```
Query Phase (validation)          Action Phase (mutation)
─────────────────────            ──────────────────────
onQueryCreate                    onBeforeCreateAction  →  DB INSERT  →  onAfterCreateAction
onQueryRead
onQueryUpdate                    onBeforeUpdateAction  →  DB UPDATE  →  onAfterUpdateAction
onQueryDelete                    onBeforeDeleteAction  →  DB DELETE  →  onAfterDeleteAction
onQueryCreateItem                onBeforeUpsertAction (shared create/update)
onQueryUpsert (shared)           onAfterUpsertAction  (shared create/update)
                                 onValidate (content validation)
```

Override any hook in your repository class:

```kotlin
class CustomerColl : Coll<...>(...) {
    override suspend fun onValidate(apiItem: ApiItem<...>, item: Customer): SimpleState {
        if (item.email.isBlank()) return simpleErrorState("Email is required")
        return SimpleState(true)
    }

    override suspend fun onBeforeCreateAction(apiItem: ApiItem.Action.Create<...>): ItemState<Customer> {
        // Transform item before insert
        return ItemState(item = apiItem.item.copy(name = apiItem.item.name.trim()))
    }

    override suspend fun onAfterCreateAction(apiItem: ApiItem.Action.Create<...>, itemState: ItemState<Customer>) {
        // Side effects after insert (send email, update cache, etc.)
    }
}
```

---

## Annotations

Located in `com.fonrouge.base.annotations`:

| Annotation | Target | Purpose |
|-----------|--------|---------|
| `@Collection(name)` | Class | Maps class to MongoDB collection or SQL table name |
| `@Computed` | Property | Marks a body property as intentionally non-persisted (see [Constructor-Only Persistence](#constructor-only-persistence)) |
| `@SqlField(name, compound)` | Property | Maps property to a specific SQL column name or marks it as a compound (nested) field |
| `@SqlIgnoreField` | Property | Excludes property from SQL INSERT/UPDATE statements |
| `@SqlOneToOne` | Property | Marks a one-to-one relationship for SQL mapping |
| `@PreLookupField` | Property | Indicates a pre-lookup field for initial filtering |

### Constructor-Only Persistence

FSLib enforces a convention: **only primary constructor parameters** of `BaseDoc` subclasses are persisted to the database. Properties declared in the class body are automatically stripped before writes. This is handled by `ConstructorCopier`, a shared utility used by all repository engines (MongoDB, SQL, InMemory).

```kotlin
@Serializable
data class Product(
    override val _id: String,   // persisted (constructor parameter)
    val name: String = "",      // persisted
    val price: Double = 0.0,    // persisted
) : BaseDoc<String> {
    @Computed
    val displayPrice: String    // NOT persisted (body property)
        get() = "$$price"
}
```

Use the `@Computed` annotation on body properties to make the non-persisted intent explicit and self-documenting.

---

## Role-Based Access Control

FSLib includes a built-in RBAC system:

- **`IAppRole`** — Defines available roles (per class, per CRUD task)
- **`IRoleInUser`** — Assigns roles to individual users (Allow / Deny / Default)
- **`IGroupOfUser`** — Groups users together
- **`IRoleInGroup`** — Assigns roles to groups
- **`IUserGroup`** — Links users to groups with inherited roles

Permissions are checked automatically on every CRUD operation via `getCrudPermission()`, and resolved by
`RbacResolver` — a pure, engine-agnostic algebra: root short-circuit → direct-grant precedence → group
tie-break → role default. Resolution is **side-effect-free**: a permission check never writes.

### Wiring RBAC (required since 5.0.0)

Registration and role provisioning are **explicit boot steps**. Both were implicit before 5.0.0 — if you
are upgrading, read [MIGRATION.md](MIGRATION.md#4x--500--explicit-rbac-registration).

```kotlin
val roleInUserColl = RoleInUserColl(mongoDb)
MongoRbac.register(roleInUserColl)        // required — otherwise every remote CRUD op is denied
check(MongoRbac.isRegistered)             // optional boot assertion

appRoleColl.ensureRoles(                  // roles are not created on first access
    crudContainers = listOf(CommonTask),
    singleActions = listOf("ReportService" to "exportPayroll"),
)
```

Enforcement **fails closed**: a repository at the default `permissionEnforcement = Enforce` with no
provider registered denies **all** remote CRUD — reads and lists included — rather than allowing it.
Declare `permissionEnforcement = PermissionEnforcement.Off` on repositories that are deliberately not
permission-governed.

### Group-aware membership

For a `(userId, appRoleId)` pair, use the membership API rather than querying `RoleInUser` directly — a
raw count is group-blind and wrongly denies a user whose role comes only from a group:

```kotlin
roleInUserColl.hasSingleActionGrant(userId, appRoleId)   // does an edge exist (direct OR group)?
roleInUserColl.isAllowedSingleAction(userId, appRoleId)  // is the user allowed (full resolution)?
```

Existence is not authorization — pick deliberately. `./gradlew :samples:rbac:run` walks through both
without a database.

The permission system is decoupled from the database engine through `IRolePermissionProvider` and
`PermissionRegistry`. Every engine — MongoDB, SQL, and in-memory — routes through the same registered
provider when enforcing, so `SqlRepository` enforces without importing MongoDB types.

> **The only provider fsLib ships is the MongoDB one** (registered by `MongoRbac.register`). There is no
> native SQL RBAC backend yet. A SQL-only app that wants enforcement must implement
> `IRolePermissionProvider` and assign it to `PermissionRegistry.rolePermissionProvider`; otherwise
> declare `permissionEnforcement = PermissionEnforcement.Off`. Note that a SQL-only app which never
> configured RBAC **silently allowed everything on 4.x and is denied on 5.0.0+** — see
> [MIGRATION.md](MIGRATION.md#3-declare-non-enforcing-repositories).

---

## Change Logging

Enable audit trails by providing a `changeLogCollFun` on your repository:

```kotlin
class CustomerColl : Coll<...>(...) {
    override val changeLogCollFun = { ChangeLogColl() }
}
```

Every create, update, and delete operation automatically records:
- Action type (Create / Update / Delete)
- Timestamp
- User ID and info
- Before/after field values (for updates)
- Client info

View change logs with the `IViewListChangeLog` interface from the `:media` module.

---

## File Attachments (DataMedia)

The `:media` module provides file attachment support:

```kotlin
// Define your DataMedia model implementing IDataMedia<User, OId<User>>
// Use IDataMediaColl for the MongoDB collection
// Use IViewListDataMedia for the frontend view with upload, thumbnail preview, and download
```

Features: file upload with type filtering, thumbnail generation, ordering, metadata tracking (size, content type, user, date).

---

## Help Documentation

FSLib supports module-scoped contextual help. See `HELP-DOCS-GUIDE.md` for the complete guide.

**Directory structure:**
```
help-docs/
  ViewListCustomer/
    tutorial.html     # Step-by-step guide
    context.html      # Quick reference
  ViewItemCustomer/
    tutorial.html
    context.html
```

Help buttons appear automatically when documentation files exist for a view.

---

## Named Routes & API Contract

FSLib includes a system for exposing RPC endpoints to third-party clients (Android, native, etc.) that don't use KSP-generated Kilua RPC proxies.

### Named Routes

Annotate RPC service methods with `@RpcBindingRoute` to produce human-readable, order-independent route paths instead of counter-based defaults:

```kotlin
@RpcService
interface ITaskService {
    @RpcBindingRoute("ITaskService.apiList")
    suspend fun apiList(apiList: ApiList<TaskFilter>): ListState<Task>

    @RpcBindingRoute("ITaskService.apiItem")
    suspend fun apiItem(iApiItem: IApiItem<Task, String, TaskFilter>): ItemState<Task>
}
```

This produces routes like `/rpc/ITaskService.apiList` instead of `/rpc/routeTaskServiceManager0`.

### API Contract Endpoint

`RouteContract` reads actual routes from Kilua RPC's registry and serves them at `/apiContract`:

```kotlin
// Main.kt (jvmMain)
val contract = RouteContract(version = "1.0.0")   // your application's API version, not fsLib's
contract.register(TaskServiceManager, "ITaskService")

routing {
    apiContractEndpoint(contract)
}
```

> **Note:** The `/apiContract` endpoint is optional when using a [shared contract library](#shared-contract-library) with `@RpcBindingRoute` named routes. Since routes follow the `"/rpc/InterfaceName.methodName"` pattern, clients that share the contract library can construct routes at compile time without runtime discovery.

Third-party clients fetch the contract at startup to discover available services:

```json
{
  "version": "1.0.0",
  "protocol": {
    "format": "json-rpc-2.0",
    "contentType": "application/json",
    "paramEncoding": "each parameter is individually JSON-serialized into a string element of the params array",
    "resultEncoding": "the result field contains a JSON-serialized string that must be deserialized a second time"
  },
  "services": [
    {
      "service": "ITaskService",
      "methods": {
        "apiList": { "route": "/rpc/ITaskService.apiList", "method": "POST" },
        "apiItem": { "route": "/rpc/ITaskService.apiItem", "method": "POST" }
      }
    }
  ]
}
```

### Shared Contract Library

For compile-time type safety between server and client, split your models and service contract into a shared library module:

```kotlin
// showcase-lib (shared, no server/frontend dependencies)
interface ITaskServiceContract {
    suspend fun apiList(apiList: ApiList<TaskFilter>): ListState<Task>
    suspend fun apiItem(iApiItem: IApiItem<Task, String, TaskFilter>): ItemState<Task>
}

// showcase-app (server) — extends the contract with @RpcService
@RpcService
interface ITaskService : ITaskServiceContract { ... }

// Android client — implements the contract with HTTP calls
class ITaskService : ITaskServiceContract {
    override suspend fun apiList(apiList: ApiList<TaskFilter>): ListState<Task> =
        call("apiList", apiList)
}
```

See `samples/fullstack/showcase/` for a complete working example with `showcase-lib` and `showcase-app`.

### Android Sample

A standalone Android client that consumes the showcase API contract is available at [showcase-android](https://github.com/tfonrouge/fslib-android/tree/main/samples/showcase-android). It demonstrates both approaches: runtime route discovery via `/apiContract`, and compile-time route construction using the shared `showcase-lib` contract with `@RpcBindingRoute` named routes.

---

## Build Commands

> **Requires the Gradle daemon on JDK 25** (since 6.0.0 — KVision 9.6.0's plugin needs it). If your
> default JDK is older, pass it per invocation:
> `./gradlew -Dorg.gradle.java.home=<jdk25-home> <task>`

```bash
./gradlew build                    # Build all modules
./gradlew :core:build              # Build only the core module
./gradlew :fullstack:build         # Build only the fullstack module
./gradlew :mongodb:build           # Build only the mongodb module
./gradlew :sql:build               # Build only the sql module
./gradlew :media:build             # Build only the media module
./gradlew :ssr:build               # Build only the ssr module
./gradlew publishToMavenLocal -PSNAPSHOT  # Publish SNAPSHOT to local Maven (~/.m2/)
```

### Local Development with SNAPSHOT

To publish a SNAPSHOT version to your local Maven repository for development and testing:

```bash
./gradlew publishToMavenLocal -PSNAPSHOT   # Publishes as 6.0.0-SNAPSHOT to ~/.m2/
./gradlew :core:publishToMavenLocal -PSNAPSHOT  # Single module only
```

The `-PSNAPSHOT` flag automatically appends `-SNAPSHOT` to the version defined in `libs.versions.toml` — no manual version editing required. In your consuming project, add `mavenLocal()` and reference the snapshot:

```kotlin
repositories {
    mavenLocal()
}

dependencies {
    implementation("com.fonrouge.fslib:fullstack:6.2.0-SNAPSHOT")
}
```

> **Tip:** Gradle caches SNAPSHOT dependencies. If you republish the same snapshot version, use `--refresh-dependencies` in the consuming project to pick up the latest artifacts.

> **Safety:** Running `publishToMavenLocal` without `-PSNAPSHOT` is blocked by default. Publishing a release version (e.g., `6.0.0`) to `~/.m2/` would silently shadow the official Maven Central artifact for every project on the machine. If you need to override this check, use `-PFORCE_LOCAL`.

### Sample Applications

```bash
# Fullstack samples (KVision + Ktor)
./gradlew :samples:fullstack:rpc-demo:run          # RPC demo
./gradlew :samples:fullstack:greeting:run           # Simple greeting
./gradlew :samples:fullstack:contacts:run           # Contacts grid
./gradlew :samples:fullstack:showcase:showcase-app:run  # Showcase (InMemoryRepository + API contract)

# SSR samples (Ktor HTML builder)
./gradlew :samples:ssr:basic:run
./gradlew :samples:ssr:catalog:run
./gradlew :samples:ssr:advanced:run

# RBAC walkthrough (console, no database)
./gradlew :samples:rbac:run
```

---

## Project Structure

```
FSLib/
  core/                            # :core module (formerly :base)
    src/
      commonMain/                  # BaseDoc, ID types, annotations, serializers, state, API
      jvmMain/                     # BSON serializers, JVM utilities
      jsMain/                      # Browser utilities, JS serializers
  fullstack/                       # :fullstack module (formerly :fullStack)
    src/
      commonMain/                  # Shared RPC interfaces
      jvmMain/                     # IRepository, IRolePermissionProvider, PermissionRegistry
      jsMain/                      # Views, config, Tabulator, layout helpers
  mongodb/                         # :mongodb module (JVM-only)
    src/main/kotlin/               # Coll, aggregation pipelines, BSON helpers
  sql/                             # :sql module (JVM-only)
    src/main/kotlin/               # SqlRepository, SqlDatabase
  memorydb/                        # :memorydb module (JVM-only)
    src/main/kotlin/               # InMemoryRepository
  media/                           # :media module (formerly :utils)
    src/
      commonMain/                  # DataMedia, ChangeLog interfaces
      jvmMain/                     # DataMedia MongoDB collection
      jsMain/                      # DataMedia and ChangeLog views
  ssr/                             # :ssr module
    src/main/kotlin/               # Server-side rendering with Ktor HTML builder
  buildSrc/                        # Gradle convention plugins
    src/main/kotlin/
      fslib-publishing.gradle.kts  # Maven Central publishing
  samples/                         # Sample applications
    fullstack/
      rpc-demo/                    # Full-stack KVision + Ktor sample
      greeting/                    # Simple greeting sample
      contacts/                    # Contacts sample
      showcase/
        showcase-lib/              # Shared models + contract (publishable)
        showcase-app/              # Full-stack app with API contract endpoint
    ssr/
      basic/                       # Basic SSR sample
      catalog/                     # Catalog SSR sample
      advanced/                    # Advanced SSR sample
    rbac/                          # RBAC walkthrough (console, no database)
  blueprints/                      # Design artifacts: BRIEF / CONTRACT / LEDGER / PLAN per blueprint
  CHANGELOG.md                     # Release history
  MIGRATION.md                     # Version-scoped upgrade notes
  CLAUDE.md                        # AI assistant instructions
  HELP-DOCS-GUIDE.md               # Help documentation guide
```

---

## Requirements

- **JDK 25** — required for both building and running since 6.0.0 (KVision 9.6.0 ships Java 25
  bytecode). On 5.0.0 and earlier, JDK 21 or higher.
- **Kotlin 2.4** in the consuming project (since 6.0.0)
- **MongoDB** (if using the `:mongodb` module)
- **SQL Server** (if using the `:sql` module — MSSQL via jTDS or JDBC driver)
- **Chrome** (for JS tests via Karma)

---

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes with KDoc comments on all public APIs
4. Run `./gradlew build` to verify
5. Submit a pull request

---

## License

See the project repository for license information.
