# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

FSLib is a Kotlin Multiplatform library (`com.fonrouge.fsLib`) for building full-stack web applications with MongoDB and/or SQL backends and KVision frontend. It provides CRUD scaffolding via a backend-agnostic `IRepository` interface, view management, Tabulator integration, and shared data models across JVM/JS targets.

## Build Commands

```bash
./gradlew build                    # Build all modules
./gradlew :core:build              # Build the core module
./gradlew :fullstack:build         # Build the fullstack module
./gradlew :mongodb:build           # Build the MongoDB engine module
./gradlew :sql:build               # Build the SQL engine module
./gradlew :media:build             # Build the media module
./gradlew :ssr:build               # Build the SSR module
./gradlew :memorydb:build          # Build the in-memory DB engine module
./gradlew :ssr:test                # Run SSR tests
./gradlew publishToMavenLocal -PSNAPSHOT  # Publish SNAPSHOT to local Maven (~/.m2/)
```

### Sample Applications

```bash
./gradlew :samples:ssr:basic:run           # Run SSR basic sample
./gradlew :samples:ssr:catalog:run         # Run SSR catalog sample
./gradlew :samples:ssr:advanced:run        # Run SSR advanced sample
./gradlew :samples:fullstack:greeting:run      # Run greeting sample (builds JS + starts Ktor)
./gradlew :samples:fullstack:contacts:run      # Run contacts sample
./gradlew :samples:fullstack:rpc-demo:run      # Run fullstack RPC demo
./gradlew :samples:fullstack:showcase:run      # Run showcase sample (ViewList, ViewItem, InMemoryRepository)
./gradlew :samples:rbac:run                    # Run RBAC walkthrough (resolver + membership API over the in-memory grant port; no DB)
```

## Architecture

### Module Dependency Graph

```
samples:fullstack:* → fullstack → core
                      mongodb ↗
samples:ssr:*       → ssr → fullstack → core
                         → mongodb ↗
media               → mongodb → fullstack → core
sql                 → fullstack → core
memorydb            → fullstack → core
```

- **`:core`** — Platform-independent foundation (commonMain/jvmMain/jsMain). Contains `BaseDoc<ID>` (the document interface all models implement), common interfaces (`ICommon`, `ICommonContainer`), `simpleContainer()` / `simpleContainerWithFilter()` factory functions for concise container creation, date/math utilities, custom BSON-aware serializers (ObjectId, dates, numeric types), SQL annotations (`@SqlField`, `@SqlIgnoreField`, `@SqlOneToOne`), `@Computed` annotation for marking non-persisted body properties, coroutine helpers, user session/role models, state management, and API interfaces. Uses `kmongo-coroutine-serialization` for BSON serializer actuals, `ktor-server-core` for `ApplicationCall` typealias, and `kvision-common-remote` for shared date types. Source packages: `com.fonrouge.base.*`.

- **`:fullstack`** — Core framework module (commonMain/jvmMain/jsMain). Uses Kilua RPC plugin for frontend-backend communication. Database-engine-agnostic.
  - **jvmMain**: `IRepository` — backend-agnostic interface for CRUD, list queries, lifecycle hooks, permissions, and dependencies. `ConstructorCopier` — shared utility with cached reflection for reconstructing instances using only primary constructor parameters (used by all repository engines). `StandardCrudService` — abstract base class for service implementations that delegate `apiList`/`apiItem` to an `IRepository` (eliminates pass-through boilerplate). `IRolePermissionProvider` / `PermissionRegistry` — abstraction for role-based permission checks. `IUserRepository`, `IChangeLogRepository` — backend-agnostic interfaces. `HelpDocsService` — help documentation service. Full Ktor client/server stack.
  - **jsMain**: View system — `View`, `ViewItem`, `ViewList`, `ViewDataContainer` for rendering CRUD views (forms use KVision's `FormPanel<T>` directly — `ViewFormPanel` was removed in 3.2.0). `ConfigView`/`ConfigViewItem`/`ConfigViewList`/`ConfigViewContainer` for declarative view configuration. `registerEntityViews()` DSL for declarative view registration (sets service managers, creates/references configs, and tracks default view). Tabulator wrappers (`TabulatorViewList`, `fsTabulator`) for data grids. Layout helpers (`formRow`, `formColumn`, `toolBarList`, etc.). `ViewRegistry` — centralized registry for view configurations and RPC service managers. Full KVision UI stack (Bootstrap, FontAwesome, Tabulator, etc.). Also includes UI utility helpers (`toast`, `buttonMenu`, form control helpers, etc.).
  - **commonMain**: Shared RPC service interfaces, API definitions. Source packages: `com.fonrouge.fullStack.*`.

- **`:mongodb`** — MongoDB database engine (JVM-only). `Coll<T: BaseDoc>` — MongoDB implementation of `IRepository` providing aggregation pipelines, lookups, filtering, change logging, and role-based access (built on KMongo coroutine driver). `MongoDb` — database connection management. `FieldPath` — nested property path builder. Registers `MongoRolePermissionProvider` with `PermissionRegistry` for cross-engine permission checks.

- **`:sql`** — SQL database engine (JVM-only). `SqlRepository` — SQL implementation of `IRepository` using Exposed for relational database access. `SqlDatabase` — SQL connection management with MSSQL/jTDS JDBC drivers. Uses `PermissionRegistry` for role-based access control.

- **`:memorydb`** — In-memory database engine (JVM-only). `InMemoryRepository` — in-memory implementation of `IRepository` using `ConcurrentHashMap` for storage. Designed for samples, tests, and prototyping — no database engine required. Supports CRUD, pagination, column-level filtering/sorting (from Tabulator header filters), and the full `apiItemProcess` lifecycle with hooks. Strips body properties before store writes (via `ConstructorCopier`), matching `Coll` and `SqlRepository` behavior. `seed()` methods intentionally skip this step for test flexibility. All lifecycle hooks are no-ops by default. Source packages: `com.fonrouge.fullStack.memoryDb`.

- **`:ssr`** — Server-Side Rendering module (JVM-only). Provides `PageDef`, `FormContext`, `ColumnDef`, layout builders, and Ktor HTML integration for building server-rendered CRUD pages without a JS frontend.

- **`:media`** — Extension module (commonMain/jvmMain/jsMain) adding DataMedia (file/attachment handling) and ChangeLog views on top of fullstack and mongodb.

- **`samples/`** — Top-level directory containing sample applications:
  - `samples/ssr/basic/` — Minimal Todo CRUD app
  - `samples/ssr/catalog/` — Product + Customer catalog
  - `samples/ssr/advanced/` — Project/Task tracker with advanced features
  - `samples/fullstack/rpc-demo/` — KVision + Kilua RPC demo
  - `samples/fullstack/greeting/` — Minimal RPC greeting app
  - `samples/fullstack/contacts/` — Tabulator grid with contacts

### Key Patterns

- **Kotlin Multiplatform with `expect`/`actual`**: Source sets are `commonMain`, `jvmMain`, `jsMain`. Shared interfaces in commonMain; platform implementations in jvmMain (MongoDB/Ktor) and jsMain (KVision/browser). Expect/actual pairs must reside in the same module.
- **KMongo + coroutines**: Server-side MongoDB access via `CoroutineCollection` from KMongo. The `Coll` class wraps this with CRUD operations, aggregation pipelines, and BSON manipulation.
- **KVision**: Frontend UI framework. Views extend KVision components. Tabulator is used for data grids with remote data loading.
- **Kilua RPC**: Used in `:fullstack` for type-safe RPC service definitions shared between client and server.
- **Kotlinx Serialization**: All models use `@Serializable`. Custom serializers exist for BSON ObjectId, LocalDate, LocalDateTime, OffsetDateTime, and numeric types — these live in `:core` alongside the models that reference them.
- **Repository abstraction**: `IRepository` in `:fullstack` defines the backend-agnostic contract for CRUD, list queries, lifecycle hooks, permissions, and dependency checking. `Coll` in `:mongodb` and `SqlRepository` in `:sql` both implement it. `IRolePermissionProvider` / `PermissionRegistry` decouple permission checks from any specific database engine. Related interfaces: `IUserRepository`, `IChangeLogRepository`.
- **Single Collection Inheritance**: Multiple entity subtypes sharing one MongoDB collection, differentiated by a discriminator field. A shared interface extends `BaseDoc<ID>` with generic `ID`, concrete `data class` subtypes add subtype-specific constructor params and fix discriminator values as constructor defaults, an abstract `Coll` subclass centralises shared lookups/hooks/indexes, and concrete `Coll` per subtype adds the discriminator match in `findItemFilter`. Lookup-populated fields are body `var` properties with `@Computed`. Discriminator fields should be constructor parameters (not body properties) to ensure they are persisted and queryable.
- **Constructor-only persistence**: Only primary constructor parameters of `BaseDoc` subclasses are persisted. Body properties are stripped before database writes via `ConstructorCopier` (shared across all engines). The `@Computed` annotation marks body properties as intentionally non-persisted. `ConstructorCopier` caches reflection metadata per `KClass` in a `ConcurrentHashMap` and includes a fail-fast guard against `AssignTo` overrides targeting body properties.
- **Entity Registration DSL**: `simpleContainer()` / `simpleContainerWithFilter()` in `:core` for concise `ICommonContainer` creation. `simpleCommon()` / `simpleCommonWithFilter()` for lightweight `ICommon` instances (non-data views like landing pages, dashboards). `StandardCrudService` in `:fullstack` jvmMain for zero-boilerplate service delegation to `IRepository` (with `currentCall()` hook for permission checks). `registerEntityViews()` in `:fullstack` jsMain for declarative view wiring — supports `view()` for non-data views, `list()` / `item()` for data-bound views, both reference-based and inline creation modes.
- **State management**: `State`, `ItemState`, `ListState`, `SimpleState` in core module for managing UI/data state.

### Technology Stack

- Kotlin 2.3.x, Gradle with version catalogs (`gradle/libs.versions.toml`)
- Backend: Ktor (Netty), MongoDB (KMongo), Exposed (SQL), JWT auth
- Frontend: KVision 9.x, Bootstrap, Tabulator, FontAwesome
- JVM toolchain: 21

### Coding Conventions

- Always add KDoc comments to any created or updated class, function, struct, interface, or any other code construct.
- **Tabulator column `field` parameter**: Use `fieldName(Model::property)` (from `com.fonrouge.base.fieldName`) instead of raw dot-prefix strings like `"._id"`. The `fieldName()` helper generates the correct field path from a Kotlin property reference, ensuring type-safety and avoiding mismatches with the serialized data. Example: `field = fieldName(Task::_id)` instead of `field = "._id"`.

### Annotations

Located in `core/src/commonMain/kotlin/com/fonrouge/base/annotations/`:

- `@Computed` — Marks a body property as intentionally non-persisted (documentation annotation for the constructor-only persistence convention).
- `@SqlField(name, compound)` — Maps a property to a specific SQL column name or marks it as compound.
- `@SqlIgnoreField` — Excludes the property from SQL INSERT/UPDATE statements.
- `@SqlOneToOne` — Marks a one-to-one relationship for SQL mapping.

### Help Documentation

The file `HELP-DOCS-GUIDE.md` contains the full guide for the help documentation system. It must be copied to any project that intends to use the help docs functionality provided by this library.

**Key concepts:**
- Three help types: **Tutorial** (`tutorial.html`) — step-by-step teaching guide for a specific task; **Context Help** (`context.html`) — comprehensive view reference card; **Module Manual** (`manual.html`) — full module documentation.
- Files live under `help-docs/{module-slug}/{ViewClassName}/` (tutorial/context) or `help-docs/{module-slug}/manual.html` (manual).
- Views declare their module via `override val helpModule: IHelpModule`. Discovery can be disabled with `override val helpEnabled: Boolean = false`.
- The UI auto-discovers help files and shows a floating "?" dropdown: view help opens in an offcanvas panel (with tabs if multiple types exist), module manual opens in a modal with iframe.
- Content tone should be **enjoyable and fun** — friendly, conversational, with light humor where appropriate.
- Content language is determined by each downstream project (language-agnostic).

### Language

Code comments, KDoc, and user-facing strings should be written in **English**. The project uses KVision's i18n module for internationalization, allowing downstream applications to provide translations for any target language.
