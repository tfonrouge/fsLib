# Changelog

All notable changes to this project will be documented in this file.

## [3.3.0] - 2026-04-20

### Changed
- **Breaking**: `ItemState.serializedValueMap` value type changed from `Map<String, String?>?` to `Map<String, JsonElement>?`. Seed values now ride as native `JsonElement` inside the outer `ItemState` payload instead of a JSON-in-JSON quoted string, preserving types and removing double-encoding. `JsonNull` represents an explicit null; omit the key to signal "no value".
- `serializedValueMapEntry(property, value)` moved from `:mongodb` (package `com.fonrouge.fullStack.mongoDb`) to `:core` (package `com.fonrouge.base.state`). Now returns `Map<String, JsonElement>`. The `OffsetDateTime` special case is retained because `OffsetDateTime` is not itself `@Serializable`.
- `ViewItem.addSerializedValue(property, value)` now stores a `JsonElement` (built via `Json.encodeToJsonElement`).
- `ViewItem` applies `JsonNull` consistently: explicit nulls are preserved on both form-control assignment and the submission overlay (previous behavior dropped nulls from the overlay).
- `ViewItem` now splits the single `_serializedValueMap` bucket into two with clear ownership:
  - `serverSeeds` — transient, populated from the wire `ItemState.serializedValueMap`, drained against form controls during Create display via the private `applyServerSeeds()`; unmatched keys stay as residue and feed the submission overlay. Public `var` with a `private set` — callers read and mutate the map but cannot swap the reference.
  - `hiddenFields` — persistent, fed by `addSerializedValue(...)`; merged into the submission payload via `dataOverlayProvider`. Keys that collide with an existing form control are skipped in the overlay (unchanged behavior, now explicit). `@PublishedApi internal val` — reachable from the inline `addSerializedValue`.
- Client-side seed decode in `ViewItem` now unwraps `JsonPrimitive` via kotlinx accessors (`intOrNull`, `doubleOrNull`, `booleanOrNull`) instead of the browser's `JSON.parse`, so integer seeds no longer box into Kotlin/JS `Long` objects that break `FormPanel.getData` submission with a `JsonDecodingException: Expected numeric literal` error. Values up to `2^53` round-trip exactly; beyond that, the browser's own limit applies.
- **Fixed**: `DateFormControl` seeds now parse via the native `kotlin.js.Date` constructor (ISO 8601-aware) instead of KVision's `String.toDateF()` helper. The latter delegates to `fecha.js` with a format pattern that doesn't match the ISO wire format emitted by `FSOffsetDateTimeSerializer` — the `T` date/time separator fails the format regex and `toDateF` silently returned `Date()` ("now"), so `fechaCreacion` / `fechaEsperada` widgets displayed the current browser time (or a partially-parsed garbage date) instead of the seeded value, and then persisted that wrong value on submit.
- `IRepository.onQueryCreateItem` KDoc now documents the two idiomatic return shapes (full `item` vs. sparse `serializedValueMap`).

### Migration Guide
- Update imports: `import com.fonrouge.fullStack.mongoDb.serializedValueMapEntry` → `import com.fonrouge.base.state.serializedValueMapEntry`.
- Server code calling `serializedValueMapEntry(prop, value)` compiles unchanged; only the return type is now `Map<String, JsonElement>`.
- Any non-Kotlin consumer that reads `ItemState` JSON manually must expect seed values as embedded JSON values (not quoted strings).
- Subclasses of `ViewItem` that referenced the private `_serializedValueMap` bucket must migrate to `hiddenFields` (for `addSerializedValue`-style hidden data; `@PublishedApi internal`) or `serverSeeds` (for wire-ingested Create defaults; public `var` with a `private set`).

## [3.2.1] - 2026-04-07

### Added
- `View.fabExtensions` companion property — a mutable list of FAB (Floating Action Button) extension factories invoked in `startDisplayPage` for all main, non-modal views. Register at app startup to inject custom floating buttons into any view.

### Changed
- Refactored help buttons block in `View.startDisplayPage` for clarity.
- Cleaned up unused imports (`ICommon`, `ICommonContainer`) in `View.kt`.

## [3.2.0] - 2026-03-28

### Removed
- **`ViewFormPanel`** class deleted. `ViewItem.pageItemBody()` now returns KVision's `FormPanel<T>` directly. Consumer code that used `viewFormPanel { }` DSL continues to work — the function now lives on `ViewItem` and returns `FormPanel<T>`.
- `CustomMapValue`, `customBindings`, `bindCustomValue()`, `getCustomValue()`, `setCustomValue()`, `getControlValue()` removed from the form panel API.
- `addToSerializedValueMap()` removed from the form panel. Replaced by `ViewItem.addSerializedValue()`.

### Changed
- `ViewItem.formPanel` property type changed from `ViewFormPanel<T>?` to `FormPanel<T>?`.
- Serialized value map lifecycle (server-provided Create defaults) moved from the form panel into `ViewItem`.
- Tabulator data overlay and remaining serialized values now injected via KVision's `dataOverlayProvider` (set up automatically by `ViewItem.viewFormPanel {}`).
- `bindCustom(key = Model::field)` calls resolve to KVision's built-in `FormPanel.bindCustom()` — no wrapper needed.

### Dependencies
- Kotlin 2.3.20, KVision 9.5.0, Kilua RPC 0.0.43.

### Migration Guide
- Change `pageItemBody()` return type from `ViewFormPanel<T>` to `FormPanel<T>`.
- Replace `import com.fonrouge.fullStack.view.ViewFormPanel` with `import io.kvision.form.FormPanel`.
- Replace `addToSerializedValueMap(prop, value)` with `this@YourViewItem.addSerializedValue(prop, value)`.
- `viewFormPanel { }` DSL, `bindCustom()`, `bind()`, `add()`, `getData()`, `setData()` — no changes needed.

## [3.1.2] - 2026-03-18

### Added
- `@Computed` annotation (`com.fonrouge.base.annotations.Computed`) — marks body properties as intentionally non-persisted, making the constructor-only persistence convention explicit and self-documenting.
- `ConstructorCopier` utility (`com.fonrouge.fullStack.repository.ConstructorCopier`) — shared, cached reflection-based copier that reconstructs instances using only primary constructor parameters. Replaces duplicated logic across MongoDB, SQL, and InMemory repositories.
- `InMemoryRepository` now strips body properties before store writes (`insertOne`, `updateOne`), matching the behavior of `Coll` and `SqlRepository`.
- `BaseDoc` KDoc now documents the constructor-only persistence convention with code examples.
- `FSNumberDoubleSerializer` registered in `MongoDb` codec configuration for robust numeric type coercion from BSON.

### Changed
- `Coll.copyItemWithPrimaryConstructorParameters()` now delegates to `ConstructorCopier` instead of inline reflection. Public API is unchanged; the `:media` module and subclasses are unaffected.
- `SqlRepository` internal `copyWithPrimaryConstructor()` replaced by `copyCtorOnly()` delegating to `ConstructorCopier`.
- Reduced redundant constructor copies in `Coll.insertOne` (4 → 1), `Coll.updateOne` (5 → 2), `Coll.updateFieldsById` (5 → 3), `SqlRepository.insertOne` (3 → 1), and `SqlRepository.updateOne` (5 → 2). Hooks can now transform items freely; a single copy-to-constructor-params happens once before validation and database write.
- `ConstructorCopier.copyWithConstructorParams()` includes a fail-fast guard: passing a body-property name as a field override throws `IllegalArgumentException`, catching `AssignTo` misuse at runtime.

## [3.1.1] - 2026-03-15

### Added
- `simpleCommon()` and `simpleCommonWithFilter()` factory functions for non-data views (landing pages, dashboards, settings) — creates lightweight `ICommon` instances without requiring a full `ICommonContainer`
- `view()` method in `EntityRegistrationBuilder` for registering non-data views that use `ICommon` instead of `ICommonContainer`
- Reference-based `list()` and `item()` overloads in `EntityRegistrationBuilder` to avoid double-registration of existing configs
- `StandardCrudService.currentCall()` protected hook — override to supply `ApplicationCall` for role-based permission checks in Ktor services
- `ViewHome` showcase sample demonstrating the `View` + `ICommon` + `configView()` pattern for non-data views

### Changed
- `StandardCrudService.apiList` and `apiItem` are now `open`, allowing subclasses to override default behavior
- `ICommon.name` is now `open`, allowing `simpleContainer` and `simpleCommon` factories to provide meaningful names for anonymous objects
- Warnings emitted on multiple `isDefault` registrations and service manager overwrites in `registerEntityViews()`

## [3.1.0] - 2026-03-15

### Added
- `simpleContainer()` and `simpleContainerWithFilter()` factory functions to create `ICommonContainer` instances with reified generics — eliminates `itemKClass`/`filterKClass` boilerplate
- `StandardCrudService` abstract class for service implementations that delegate standard `apiList`/`apiItem` to an `IRepository`
- `registerEntityViews()` DSL for declarative view registration — replaces manual `ViewRegistry` setup and companion object force-references. Supports reference-based (existing configs) and inline creation modes, with `view()` for non-data views, `list()` and `item()` for data-bound views
- `simpleCommon()` and `simpleCommonWithFilter()` factory functions to create lightweight `ICommon` instances for non-data views (landing pages, dashboards, settings)
- `view()` method in `EntityRegistrationBuilder` for registering non-data views that use `ICommon` instead of `ICommonContainer`
- `StandardCrudService.currentCall()` protected hook — override to supply `ApplicationCall` for role-based permission checks in Ktor services
- `ICommon.name` is now `open`, allowing `simpleContainer` and `simpleCommon` factories to provide meaningful names for anonymous objects
- Showcase sample: `ViewHome` — non-data landing page demonstrating `View` + `ICommon` + `configView()` pattern
- `MIGRATION.md` guide for adopting the new Entity Registration DSL

### Changed
- **Breaking:** `ICommonContainer` now derives `idSerializer` automatically from the item's `_id` field via `GeneratedSerializer.childSerializers()` — the `idSerializer` constructor parameter has been removed
- **Breaking:** `ICommon` and `ICommonContainer` now derive `apiFilterSerializer` from a required `filterKClass: KClass<FILT>` parameter — the `apiFilterSerializer` constructor parameter has been removed
- **Breaking:** `ICommonChangeLog` and `ICommonDataMedia` no longer accept an `idSerializer` constructor parameter
- **Breaking:** Removed redundant `CC` type parameter from all generic chains — `Coll<CC, T, ID, FILT, UID>` → `Coll<T, ID, FILT, UID>`, `ViewList<CC, T, ID, FILT, MID>` → `ViewList<T, ID, FILT, MID>`, etc. The `commonContainer` property is now typed as `ICommonContainer<T, ID, FILT>` directly. Affects: `IRepository`, `Coll`, `InMemoryRepository`, `SqlRepository`, `View`, `ViewDataContainer`, `ViewItem`, `ViewList`, `ConfigView`, `ConfigViewContainer`, `ConfigViewItem`, `ConfigViewList`, `TabulatorViewList`, `PageDef`, and all MongoDB/media interfaces.
- Samples and tests migrated to use `ApiFilter` directly instead of defining empty custom filter classes (e.g., `TaskFilter`, `ContactFilter`)

### Migration guide
Replace:
```kotlin
data object CommonFoo : ICommonContainer<Foo, StringId<Foo>, FooFilter>(
    itemKClass = Foo::class,
    idSerializer = StringId.serializer(Foo.serializer()),
    apiFilterSerializer = FooFilter.serializer(),
    labelItem = "Foo",
)
```
With:
```kotlin
data object CommonFoo : ICommonContainer<Foo, StringId<Foo>, FooFilter>(
    itemKClass = Foo::class,
    filterKClass = FooFilter::class,
    labelItem = "Foo",
)
```
If the filter class is empty (no custom properties), use `ApiFilter` directly and delete the filter class.

For the CC removal, drop the first `Common...` type argument from all generic references:
```kotlin
// Before:
class MyColl : Coll<CommonFoo, Foo, OId<Foo>, FooFilter, UserId>(...)
class MyViewList : ViewList<CommonFoo, Foo, OId<Foo>, FooFilter, Unit>(...)
// After:
class MyColl : Coll<Foo, OId<Foo>, FooFilter, UserId>(...)
class MyViewList : ViewList<Foo, OId<Foo>, FooFilter, Unit>(...)
```

## [3.0.3] - 2026-03-14

### Added
- `-PSNAPSHOT` flag for `publishToMavenLocal` — automatically appends `-SNAPSHOT` to the version without editing `libs.versions.toml`
- Safety guard that blocks `publishToMavenLocal` without `-PSNAPSHOT` to prevent shadowing Maven Central release artifacts (override with `-PFORCE_LOCAL`)
- Documentation clarifying that `/apiContract` is optional when using a shared contract library with `@RpcBindingRoute` named routes

### Changed
- Maven groupId changed from `io.github.tfonrouge.fslib` to `com.fonrouge.fslib`
- Replace `fslib-named-routes` Gradle plugin with Kilua RPC's built-in `@RpcBindingRoute` annotation
- Update documentation with Android sample link
- Signing tasks are now disabled for local/snapshot publishes (configuration cache compatible)

### Removed
- `fslib-named-routes.gradle.kts` convention plugin (no longer needed)
- Migration guides (`MIGRATION-GUIDE-2.0.md`, `MIGRATION-GUIDE-3.0.md`)

## [3.0.2] - 2026-03-13

### Added
- Repository and website links for external dependencies in docs
- Updated README.md and USAGE-GUIDE.md

## [3.0.1] - 2026-03-12

### Added
- Named routes for Kilua RPC via `fslib-named-routes` Gradle plugin
- `RouteContract` class for API contract endpoint (`/apiContract`)
- `InMemoryRepository` for samples, tests, and prototyping (`:memorydb` module)
- Showcase sample with shared contract library pattern

## [3.0.0] - 2026-03-10

### Changed
- Module renames: `:base` to `:core`, `:fullStack` to `:fullstack`, `:utils` to `:media`
- Extracted MongoDB and SQL into independent engine modules (`:mongodb`, `:sql`)
- Decoupled permission system via `IRolePermissionProvider` / `PermissionRegistry`
- Migrated from KVision RPC to Kilua RPC

### Added
- `:sql` module with `SqlRepository` implementation using Exposed
- `:memorydb` module for in-memory storage
- `:ssr` module for server-side rendering with Ktor HTML builder
- Cross-engine dependency checking between MongoDB and SQL repositories
