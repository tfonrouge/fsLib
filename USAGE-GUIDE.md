# FSLib Usage Guide

This guide walks through building a full-stack CRUD application with FSLib, from project setup to advanced features like master-detail views, change logging, and role-based access control. FSLib integrates [Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html), [Ktor](https://ktor.io/), [KVision](https://kvision.io/), and [Kilua RPC](https://github.com/rjaros/kilua-rpc).

---

## Table of Contents

1. [Project Setup](#1-project-setup)
2. [Defining Models](#2-defining-models)
3. [Common Containers](#3-common-containers)
4. [Filters](#4-filters)
5. [RPC Services](#5-rpc-services)
6. [MongoDB Repository (Coll)](#6-mongodb-repository-coll)
7. [SQL Repository (SqlRepository)](#7-sql-repository-sqlrepository)
8. [In-Memory Repository](#8-in-memory-repository)
9. [Backend Service Implementation](#9-backend-service-implementation)
10. [Frontend View Configuration](#10-frontend-view-configuration)
11. [List Views](#11-list-views)
12. [Item Views (Forms)](#12-item-views-forms)
13. [Master-Detail Views](#13-master-detail-views)
14. [MongoDB Lookups and Aggregation](#14-mongodb-lookups-and-aggregation)
15. [Lifecycle Hooks](#15-lifecycle-hooks)
16. [Validation](#16-validation)
17. [Dependencies (Referential Integrity)](#17-dependencies-referential-integrity)
18. [Change Logging](#18-change-logging)
19. [Role-Based Access Control](#19-role-based-access-control)
20. [State Management](#20-state-management)
21. [Annotations](#21-annotations)
22. [Custom Serializers](#22-custom-serializers)
23. [Help Documentation](#23-help-documentation)
24. [File Attachments (DataMedia)](#24-file-attachments-datamedia)
25. [Periodic Data Updates](#25-periodic-data-updates)
26. [View Navigation and Routing](#26-view-navigation-and-routing)
27. [Named Routes & API Contract](#27-named-routes--api-contract)
28. [Single Collection Inheritance](#28-single-collection-inheritance)

---

## 1. Project Setup

### build.gradle.kts

```kotlin
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.serialization)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.kilua.rpc)
    alias(libs.plugins.kvision)
}

kotlin {
    jvmToolchain(25)

    jvm { /* JVM target */ }
    js(IR) {
        browser { /* JS target */ }
    }

    sourceSets {
        commonMain {
            dependencies {
                api("com.fonrouge.fslib:fullstack:6.2.2")
            }
        }
        jvmMain {
            dependencies {
                implementation(libs.ktor.server.netty)
            }
        }
        jsMain {
            dependencies {
                implementation(libs.kvision)
                implementation(libs.kvision.bootstrap)
                // ... other KVision modules as needed
            }
        }
    }
}
```

### Local Development with SNAPSHOT

To develop and test against a local build of FSLib, publish a SNAPSHOT version to your local Maven repository:

```bash
./gradlew publishToMavenLocal -PSNAPSHOT
```

The `-PSNAPSHOT` flag automatically appends `-SNAPSHOT` to the version (e.g., `6.0.0` becomes `6.0.0-SNAPSHOT`) without modifying `libs.versions.toml`. Then in your consuming project:

```kotlin
repositories {
    mavenLocal()
}

dependencies {
    // Use the SNAPSHOT version matching what you published
    api("com.fonrouge.fslib:fullstack:6.2.2-SNAPSHOT")
}
```

You can also publish a single module: `./gradlew :fullstack:publishToMavenLocal -PSNAPSHOT`

> **Tip:** Gradle caches SNAPSHOT dependencies. Use `--refresh-dependencies` in the consuming project after republishing to pick up the latest artifacts.

> **Safety:** Running `publishToMavenLocal` without `-PSNAPSHOT` is blocked by default to prevent silently shadowing Maven Central release artifacts in `~/.m2/`. Use `-PFORCE_LOCAL` to override this check if needed.

### Application Entry Point (jvmMain)

```kotlin
fun Application.main() {
    install(Compression)

    // Initialize MongoDB
    val mongoDatabase = MongoDb.getDatabase("myapp")

    // Initialize RPC services
    initRpc {
        registerRemoteTypes()
        // Register your service implementations
    }
}
```

### KVision Application (jsMain)

```kotlin
class App : Application() {
    override fun start(state: Map<String, Any>) {
        val reg = registerEntityViews(getServiceManager<ICustomerService>()) {
            list(ViewListCustomer.configViewList, isDefault = true)
            item(ViewItemCustomer.configViewItem)
        }

        KVWebManager.initialize {
            defaultView = reg.defaultView
        }
    }
}
```

---

## 2. Defining Models

All data models implement `BaseDoc<ID>`. FSLib supports four ID types:

| ID Type | Use Case | Example |
|---------|---------|---------|
| `OId<T>` | MongoDB ObjectId (default for MongoDB) | `OId<Customer>()` |
| `IntId<T>` | Integer primary key (SQL auto-increment) | `IntId<Product>(0)` |
| `LongId<T>` | Long primary key | `LongId<Transaction>(0L)` |
| `StringId<T>` | String primary key (natural keys) | `StringId<Config>("app")` |

### MongoDB Model

```kotlin
@Serializable
@Collection("customers")
data class Customer(
    override val _id: OId<Customer> = OId(),
    val name: String = "",
    val email: String = "",
    val phone: String? = null,
    val active: Boolean = true,
    val createdAt: OffsetDateTime = offsetDateTimeNow(),
) : BaseDoc<OId<Customer>>
```

### SQL Model

```kotlin
@Serializable
data class Product(
    override val _id: IntId<Product> = IntId(0),
    val name: String = "",
    val price: Double = 0.0,

    @SqlField(name = "category_id")
    val categoryId: IntId<Category> = IntId(0),

    @SqlIgnoreField
    val categoryName: String? = null,  // Populated by JOIN, not stored
) : BaseDoc<IntId<Product>>
```

### Model Rules

- All properties **must have default values** (required for form panel deserialization).
- Use `@Serializable` on all models (kotlinx-serialization).
- Use `@Collection("name")` to specify the MongoDB collection or SQL table name.
- The `_id` property is the primary key; use the appropriate ID type for your backend.
- **Only primary constructor parameters are persisted.** Properties declared in the class body are automatically stripped before database writes by `ConstructorCopier`. Use the `@Computed` annotation on body properties to make this intent explicit:

```kotlin
@Serializable
data class Invoice(
    override val _id: String,
    val total: Double = 0.0,       // persisted (constructor parameter)
) : BaseDoc<String> {
    @Computed
    val formattedTotal: String     // NOT persisted (body property)
        get() = "$$total"
}
```

---

## 3. Common Containers

A `ICommonContainer` acts as metadata provider for an entity — it describes how to serialize, label, and create API items for the model.

```kotlin
object CommonCustomer : ICommonContainer<Customer, OId<Customer>, CustomerFilter>(
    itemKClass = Customer::class,
    filterKClass = CustomerFilter::class,
    labelItem = "Customer",
    labelList = "Customers",
    labelId = { it?.name ?: "" },
    labelItemId = { "Customer: ${it?.name ?: "New"}" },
)
```

For entities using `ApiFilter` (no custom filter), use the `simpleContainer()` factory:

```kotlin
// For entities using ApiFilter (no custom filter), use the simpleContainer factory:
val CommonCustomer = simpleContainer<Customer, OId<Customer>>(
    labelItem = "Customer",
    labelList = "Customers",
    labelId = { it?.name ?: "" },
)
```

**Key properties:**
- `itemKClass` — Kotlin class reference (used for reflection and serialization).
- `filterKClass` — Kotlin class reference for the filter; the serializer is derived from the KClass automatically.
- `labelItem` / `labelList` — Display names for the entity (singular/plural).
- `labelId` — Generates a human-readable label from an item (used in banners, breadcrumbs).

> **Note:** The `idSerializer` property has been removed in v3.1.2 — it is now auto-derived from the `_id` field's serializer. The `apiFilterSerializer` property has been replaced by `filterKClass`; the serializer is derived from the KClass automatically.

---

## 4. Filters

Filters extend `IApiFilter<MID>` where `MID` is the master item's ID type (use `Unit` if there is no master).

```kotlin
@Serializable
data class CustomerFilter(
    val nameSearch: String? = null,
    val activeOnly: Boolean = false,
) : IApiFilter<Unit>()
```

### Master-Detail Filter

When a list is a detail of another entity, the filter carries the master's ID:

```kotlin
@Serializable
data class OrderFilter(
    val dateFrom: LocalDate? = null,
    val dateTo: LocalDate? = null,
) : IApiFilter<OId<Customer>>()  // Master = Customer
```

The `masterItemId` property is inherited from `IApiFilter` and populated automatically when the view is used as a detail of a master view.

---

## 5. RPC Services

Define shared RPC interfaces in `commonMain` using [Kilua RPC](https://github.com/rjaros/kilua-rpc):

```kotlin
@KiluaRpcServiceName("ICustomerService")
interface ICustomerService {
    // Item CRUD (create, read, update, delete)
    suspend fun apiItem(
        iApiItem: IApiItem<Customer, OId<Customer>, CustomerFilter>
    ): ItemState<Customer>

    // List with pagination, filtering, sorting
    suspend fun apiList(
        apiList: ApiList<CustomerFilter>
    ): ListState<Customer>

    // Custom operations (optional)
    suspend fun deactivateCustomer(id: OId<Customer>): SimpleState
}
```

**Conventions:**
- `apiItem` handles all CRUD operations via the polymorphic `IApiItem` sealed class.
- `apiList` handles paginated list queries with filters and sorters.
- Add custom methods as needed for business-specific operations.

---

## 6. MongoDB Repository (Coll)

`Coll` is the MongoDB implementation of `IRepository`. It wraps [KMongo](https://litote.org/kmongo/)'s coroutine driver with CRUD operations, aggregation pipelines, and lifecycle hooks.

```kotlin
class CustomerColl : Coll<Customer, OId<Customer>, CustomerFilter, OId<User>>(
    commonContainer = CommonCustomer,
    mongoDatabase = MongoDb.database,
) {
    // Optional: custom match filter for list queries
    override fun findItemFilter(apiFilter: CustomerFilter): Bson? {
        val filters = mutableListOf<Bson>()

        apiFilter.nameSearch?.let {
            filters += Customer::name regex Regex(it, RegexOption.IGNORE_CASE)
        }

        if (apiFilter.activeOnly) {
            filters += Customer::active eq true
        }

        return if (filters.isEmpty()) null else and(filters)
    }

    // Optional: default sort order
    override fun sortStage(call: ApplicationCall?, apiFilter: CustomerFilter): Bson? {
        return orderBy(Customer::name)
    }

    // Optional: enable change logging
    override val changeLogCollFun = { ChangeLogColl() }

    // Required (abstract): supplies the user collection for session lookup and change-log authorship.
    // Not an RBAC step — see section 19 for RBAC wiring.
    override val userCollFun = { UserColl() }
}
```

### MongoDB Lookups (Joins)

```kotlin
class OrderColl : Coll<Order, OId<Order>, OrderFilter, OId<User>>(
    commonContainer = CommonOrder,
    mongoDatabase = MongoDb.database,
) {
    override val lookupFun: (OrderFilter) -> List<LookupPipelineBuilder> = { _ ->
        listOf(
            lookup5(
                from = collectionName<Customer>(),
                localField = Order::customerId,
                foreignField = Customer::_id,
                resultField = Order::customerName,
            )
        )
    }
}
```

---

## 7. SQL Repository (SqlRepository)

`SqlRepository` is the SQL implementation of `IRepository`, using [Exposed](https://github.com/JetBrains/Exposed) for relational database access.

```kotlin
class ProductSqlRepo : SqlRepository<Product, IntId<Product>, ProductFilter, OId<User>>(
    commonContainer = CommonProduct,
    sqlDatabase = mySqlDatabase,
) {
    // Override table name (defaults to class name without "SqlRepo" suffix)
    override val tableName = "products"

    // Custom WHERE clause building
    override fun buildWhereFromApiFilter(
        apiFilter: ProductFilter,
        whereClauses: MutableList<String>,
        whereArgs: MutableList<Pair<IColumnType<*>, Any?>>,
    ) {
        apiFilter.nameSearch?.let {
            whereClauses += "name LIKE ?"
            whereArgs += VarCharColumnType() to "%$it%"
        }
        apiFilter.categoryId?.let {
            whereClauses += "category_id = ?"
            whereArgs += IntegerColumnType() to it.id
        }
    }
}
```

### SQL Annotations

Use annotations on model properties to control SQL mapping:

```kotlin
@Serializable
data class Product(
    override val _id: IntId<Product> = IntId(0),

    val name: String = "",

    // Map to a different column name
    @SqlField(name = "unit_price")
    val price: Double = 0.0,

    // Mark nested object (stored as multiple columns with prefix)
    @SqlField(compound = true)
    val address: Address = Address(),

    // Exclude from INSERT/UPDATE (computed or joined field)
    @SqlIgnoreField
    val totalOrders: Int = 0,

    // Mark one-to-one relationship
    @SqlOneToOne
    val category: Category? = null,
) : BaseDoc<IntId<Product>>
```

### Cross-Engine Dependencies

A SQL repository can reference MongoDB collections and vice versa:

```kotlin
class ProductSqlRepo : SqlRepository<...>(...) {
    override val dependencies = {
        listOf(
            Dependency(
                common = CommonOrderLine,
                property = OrderLine::productId,
                repositoryFun = { OrderLineColl() }  // MongoDB collection checking SQL reference
            )
        )
    }
}
```

---

## 8. In-Memory Repository

`InMemoryRepository` is a lightweight `IRepository` implementation backed by `ConcurrentHashMap`. It requires no database engine, making it ideal for samples, tests, and prototyping.

```kotlin
val repo = InMemoryRepository<Task, String, TaskFilter, String>(
    commonContainer = CommonTask,
).seed(listOf(
    Task(_id = "1", title = "Setup CI/CD", priority = Priority.HIGH, status = TaskStatus.OPEN),
    Task(_id = "2", title = "Write tests", priority = Priority.MEDIUM, status = TaskStatus.IN_PROGRESS),
))
```

### Features

- Full CRUD support (create, read, update, delete) via `apiItemProcess` / `apiListProcess`
- Pagination with `tabPage` and `tabSize`
- Column-level filtering and sorting from Tabulator header filters
- All lifecycle hooks supported (no-ops by default)
- Thread-safe via `ConcurrentHashMap`

### Dependency

```kotlin
// build.gradle.kts (jvmMain)
implementation("com.fonrouge.fslib:memorydb:6.2.2")
```

See `samples/fullstack/showcase/` for a complete example using `InMemoryRepository`.

---

## 9. Backend Service Implementation

Extend `StandardCrudService` to inherit default `apiItem` and `apiList` implementations, then add only your custom methods:

```kotlin
class CustomerService(
    private val coll: CustomerColl = CustomerColl(),
) : StandardCrudService<Customer, OId<Customer>, CustomerFilter>(coll), ICustomerService {
    // apiList and apiItem are inherited from StandardCrudService.
    // Override currentCall() for permission checks in Ktor:
    // override fun currentCall(): ApplicationCall? = /* from Ktor scope */

    // Add custom methods as needed:
    override suspend fun deactivateCustomer(id: OId<Customer>): SimpleState {
        val item = coll.findById(id) ?: return simpleErrorState("Customer not found")
        coll.updateOne(item.copy(active = false))
        return SimpleState(true, msgOk = "Customer deactivated")
    }
}
```

---

## 10. Frontend View Configuration

Register entity views using the `registerEntityViews()` DSL in your [KVision](https://kvision.io/) application:

```kotlin
class App : Application() {
    override fun start(state: Map<String, Any>) {
        val reg = registerEntityViews(getServiceManager<ICustomerService>()) {
            list(ViewListCustomer.configViewList, isDefault = true)
            item(ViewItemCustomer.configViewItem)
        }

        KVWebManager.initialize {
            defaultView = reg.defaultView
        }
    }
}
```

### ConfigViewList

Define the list view configuration in the view's companion object:

```kotlin
// In ViewListCustomer companion object:
companion object {
    val configViewList = configViewList(
        viewKClass = ViewListCustomer::class,
        commonContainer = CommonCustomer,
        apiListFun = ICustomerService::apiList,
    )
}
```

### ConfigViewItem

Define the item view configuration in the view's companion object:

```kotlin
// In ViewItemCustomer companion object:
companion object {
    val configViewItem = configViewItem(
        viewKClass = ViewItemCustomer::class,
        commonContainer = CommonCustomer,
        apiItemFun = ICustomerService::apiItem,
        contextMenuItems = { item ->
            listOf(
                TabulatorMenuItem("Deactivate") {
                    // Custom action
                }
            )
        },
    )
}
```

### Non-Data Views (Landing Pages, Dashboards)

For views that don't manage a data model, use `simpleCommon()` with `configView()` and `View` directly:

```kotlin
val CommonHome = simpleCommon(label = "Home")

val configViewHome = configView(
    viewKClass = ViewHome::class,
    commonContainer = CommonHome,
    baseUrl = "Home",
)

class ViewHome : View<ApiFilter>(configView = configViewHome) {
    override fun Container.displayPage() {
        h1(content = "Welcome")
        link(label = "Customers", url = "#/ViewListCustomer")
    }
}
```

Register with the DSL using `view()`:

```kotlin
val reg = registerEntityViews(getServiceManager<ICustomerService>()) {
    view(ViewHome.configViewHome, isDefault = true)
    list(ViewListCustomer.configViewList)
    item(ViewItemCustomer.configViewItem)
}
```

See `samples/fullstack/showcase/.../ViewHome.kt` for a complete example.

---

## 11. List Views

A `ViewList` displays a paginated data grid using [Tabulator](https://tabulator.info/):

```kotlin
class ViewListCustomer : ViewList<
    Customer, OId<Customer>, CustomerFilter, Unit
>() {
    override val configView = configViewList

    override fun Container.displayPage() {
        fsTabulator(viewList = this@ViewListCustomer) {
            // Define columns
            addColumn("Name") { it.name }
            addColumn("Email") { it.email }
            addColumn("Phone") { it.phone ?: "-" }
            addColumn("Active") { if (it.active) "Yes" else "No" }

            // Optional: callback when user double-clicks a row
            onUserChooseItem = { customer ->
                ViewItemCustomer.configViewItem.openView(/* navigate to item */)
            }
        }
    }

    // Optional: toolbar buttons
    override fun Container.toolBarListButtons() {
        button("New Customer", icon = "fas fa-plus") {
            onClick {
                ViewItemCustomer.configViewItem.openView(
                    apiFilter = configView.commonContainer.apiFilterInstance(),
                    vmode = ConfigViewContainer.VMode.modal
                )
            }
        }
    }
}
```

### [Tabulator](https://tabulator.info/) Features

- **Server-side pagination** — Automatic via `TabulatorViewList`.
- **Column filtering** — Header filters map to [Tabulator](https://tabulator.info/) remote filters, translated to MongoDB match stages or SQL WHERE clauses.
- **Column sorting** — Click column headers; translated to MongoDB sort stages or SQL ORDER BY.
- **Column persistence** — Layout (widths, order, visibility) persisted to localStorage.
- **Row selection** — Bound to `selectedItemObs` observable.

---

## 12. Item Views (Forms)

A `ViewItem` displays a form for creating or editing a single item. The `viewFormPanel { }` DSL creates a KVision `FormPanel<T>` with automatic data overlay support for tabulators and serialized values:

```kotlin
class ViewItemCustomer : ViewItem<
    Customer, OId<Customer>, CustomerFilter
>() {
    override val configView = configViewItem

    override fun Container.pageItemBody(): FormPanel<Customer> = viewFormPanel {
        formRow {
            formColumn(6) {
                add(Customer::name, Text(label = "Name"))
            }
            formColumn(6) {
                add(Customer::email, Text(label = "Email"))
            }
        }
        formRow {
            formColumn(6) {
                add(Customer::phone, Text(label = "Phone"))
            }
            formColumn(6) {
                add(Customer::active, CheckBox(label = "Active"))
            }
        }
    }
}
```

### CRUD Behavior

- **Create**: Opens form with default values (from `onQueryCreateItem` hook).
- **Read**: Opens form in read-only mode. An "Edit" button switches to Update mode.
- **Update**: Opens form with existing data. "Accept" saves, "Cancel" discards.
- **Delete**: Shows confirmation dialog, then calls `deleteOne`.

The CRUD task is determined by URL parameters or by how the view is opened programmatically.

---

## 13. Master-Detail Views

Display a parent item with one or more child lists:

```kotlin
class ViewItemCustomer : ViewItem<...>() {
    override val configView = configViewItem

    override fun Container.pageItemBody(): FormPanel<Customer> = viewFormPanel {
        formRow {
            add(Customer::name, Text(label = "Name"))
        }
        // Child list: orders for this customer
        addViewList(
            viewList = ViewListOrder(),
            masterViewItem = this@ViewItemCustomer,
        ) {
            // The OrderFilter.masterItemId is automatically set to this customer's _id
        }
    }
}
```

The detail list's filter automatically receives `masterItemId` set to the parent item's `_id`. In the repository, use it to filter:

```kotlin
class OrderColl : Coll<...>(...) {
    override fun findItemFilter(apiFilter: OrderFilter): Bson? {
        val filters = mutableListOf<Bson>()
        apiFilter.masterItemId?.let {
            filters += Order::customerId eq it
        }
        return if (filters.isEmpty()) null else and(filters)
    }
}
```

---

## 14. MongoDB Lookups and Aggregation

### Simple Lookup (Join)

```kotlin
class OrderColl : Coll<...>(...) {
    override val lookupFun: (OrderFilter) -> List<LookupPipelineBuilder> = { _ ->
        listOf(
            lookup5(
                from = collectionName<Customer>(),
                localField = Order::customerId,
                foreignField = Customer::_id,
                resultField = Order::customerName,
            )
        )
    }
}
```

### Custom Match Stage

```kotlin
override fun matchStage(
    call: ApplicationCall?,
    apiFilter: OrderFilter,
    resultUnit: ResultUnit,
): Bson? {
    val filters = mutableListOf<Bson>()
    apiFilter.dateFrom?.let {
        filters += Order::orderDate gte it
    }
    apiFilter.dateTo?.let {
        filters += Order::orderDate lte it
    }
    return if (filters.isEmpty()) null else and(filters)
}
```

### Post-Lookup Filtering

Filter after lookups have populated joined fields:

```kotlin
override fun afterLookupMatchStage(): Bson? {
    return Order::customerActive eq true  // Field populated by lookup
}
```

### Custom Morphing Stage

Pre-process documents before other pipeline stages:

```kotlin
override fun morphingStage(): List<Bson>? {
    return listOf(
        addFields(Field("fullName", concat(Order::firstName, " ", Order::lastName)))
    )
}
```

---

## 15. Lifecycle Hooks

### Query Hooks (Before Database Access)

Called when the frontend requests a CRUD operation. Return an error state to block the operation.

```kotlin
override suspend fun onQueryCreate(apiItem: ApiItem.Query.Create<...>): ItemState<Customer> {
    // Check business rules before allowing creation
    val existing = findOne(CustomerFilter(emailSearch = apiItem.apiFilter.email))
    if (existing != null) return ItemState(msgError = "Email already registered")
    return ItemState(item = null)  // Allow
}

override suspend fun onQueryCreateItem(apiItem: ApiItem.Query.Create<...>): ItemState<Customer> {
    // Provide default values for new item form
    return ItemState(item = Customer(active = true, createdAt = offsetDateTimeNow()))
}
```

### Action Hooks (Before/After Database Mutation)

```kotlin
override suspend fun onBeforeUpsertAction(
    apiItem: ApiItem.Action<Customer, OId<Customer>, CustomerFilter>
): ItemState<Customer> {
    // Transform item before save (shared for create and update)
    val item = apiItem.item.copy(
        name = apiItem.item.name.trim(),
        email = apiItem.item.email.lowercase().trim(),
    )
    return ItemState(item = item)
}

override suspend fun onAfterCreateAction(
    apiItem: ApiItem.Action.Create<Customer, OId<Customer>, CustomerFilter>,
    itemState: ItemState<Customer>,
) {
    // Send welcome email, update statistics, etc.
    itemState.item?.let { sendWelcomeEmail(it.email) }
}
```

---

## 16. Validation

Override `onValidate` to check item contents before any create or update:

```kotlin
override suspend fun onValidate(
    apiItem: ApiItem<Customer, OId<Customer>, CustomerFilter>,
    item: Customer
): SimpleState {
    if (item.name.isBlank()) return simpleErrorState("Name is required")
    if (item.email.isBlank()) return simpleErrorState("Email is required")
    if (!item.email.contains("@")) return simpleErrorState("Invalid email format")
    return SimpleState(true)
}
```

Validation errors are returned to the frontend and displayed to the user automatically.

---

## 17. Dependencies (Referential Integrity)

Prevent deleting a record that is referenced by other records:

```kotlin
class CustomerColl : Coll<...>(...) {
    override val dependencies = {
        listOf(
            // Prevent deleting customer if orders exist
            Dependency(
                common = CommonOrder,
                property = Order::customerId,
            ),
            // Cross-engine: prevent deleting if SQL invoices reference this customer
            Dependency(
                common = CommonInvoice,
                property = Invoice::customerId,
                repositoryFun = { InvoiceSqlRepo() },  // SQL repository
            ),
        )
    }
}
```

When a user attempts to delete a customer, FSLib automatically checks all dependencies and returns an error message listing which collections still reference the item.

---

## 18. Change Logging

### Enable Change Logging

```kotlin
class CustomerColl : Coll<...>(...) {
    override val changeLogCollFun = { ChangeLogColl() }
}
```

### Change Log Entry Structure

Each log entry (`IChangeLog`) records:

| Field | Description |
|-------|-------------|
| `className` | Entity class name |
| `serializedId` | Item ID |
| `dateTime` | Timestamp |
| `action` | `Create`, `Update`, or `Delete` |
| `userId` | Acting user's ID |
| `userInfo` | User display info |
| `clientInfo` | Client/browser info |
| `data` | Map of field name to `Pair(oldValue, newValue)` |

### Display Change Logs (Frontend)

Use `IViewListChangeLog` from the `:utils` module to add a change log tab or context menu:

```kotlin
class ViewItemCustomer : ViewItem<...>(), IViewListChangeLog<...> {
    // Adds a "Change Log" context menu item
    init {
        initializeChangeLogMenuItem()
    }
}
```

---

## 19. Role-Based Access Control

### Architecture

```
IAppRole          — Defines permissions (per class, per CRUD task)
IRoleInUser       — Assigns permissions to individual users
IGroupOfUser      — Groups users together
IRoleInGroup      — Assigns permissions to groups
IUserGroup        — Links users to groups
```

### Enable RBAC

Two **explicit boot steps** — neither happens by itself. Skipping them is silent at compile time and
denies at runtime.

```kotlin
// Main.kt — at boot, once:
val roleInUserColl = RoleInUserColl(mongoDb)
MongoRbac.register(roleInUserColl)          // 1. wire the provider
check(MongoRbac.isRegistered) { "RBAC not registered — all remote CRUD will be denied" }

appRoleColl.ensureRoles(                    // 2. provision the roles
    crudContainers = listOf(CommonCustomer),
    singleActions = listOf("ReportService" to "exportPayroll"),
)
```

> **Since 5.0.0.** Constructing an `IRoleInUserColl` used to register the provider as a side effect, and
> roles used to be created on first permission check. Both were removed. If you are upgrading from 4.x,
> read [MIGRATION.md](MIGRATION.md#4x--500--explicit-rbac-registration) — an app that skips these steps
> is denied **all** remote CRUD, reads included, with `rootUser()` never consulted.

### How It Works

1. Each CRUD operation calls `getCrudPermission()` before proceeding.
2. If the repository declares `permissionEnforcement = PermissionEnforcement.Off`, it is allowed
   outright. Otherwise, with no provider registered it **fails closed** — denied, before any resolution.
3. Otherwise `RbacResolver` resolves the verdict, in order: root short-circuit (`rootUser()`) →
   **direct user grant** (if one exists, it decides and groups are *not* consulted) → group grants,
   combined under the role's `upVoteInGroup` bias → the role default.
4. An unprovisioned role denies — resolution never writes.
5. If the verdict is `Deny`, the operation returns an error state.

### Permission Types

| Permission | Effect |
|-----------|--------|
| `Allow` | Explicitly grants access |
| `Deny` | Explicitly blocks access |
| `Default` | Falls back to the role's `defaultPermission` |

An explicit `Allow`/`Deny` grant is never discarded — the role default applies only when every
applicable grant is `Default`.

### Group-aware membership

For a `(userId, appRoleId)` pair, never count `RoleInUser` rows directly — that is group-blind and
wrongly denies a user whose role comes only through a group:

```kotlin
roleInUserColl.hasSingleActionGrant(userId, appRoleId)   // existence: direct OR group
roleInUserColl.isAllowedSingleAction(userId, appRoleId)  // authorization: full resolution
```

Run `./gradlew :samples:rbac:run` for a database-free walkthrough.

---

## 20. State Management

FSLib uses three state types to communicate operation results:

### SimpleState

Basic success/error result:

```kotlin
val result = SimpleState(true, msgOk = "Operation successful")
val error = simpleErrorState("Something went wrong")
val warning = simpleWarnState("Check this issue")
```

### ItemState\<T\>

Result with an associated item:

```kotlin
val success = ItemState(item = customer)
val error = ItemState<Customer>(msgError = "Not found")
// State is auto-determined: Ok if item is present, Error otherwise
```

### ListState\<T\>

Paginated list result:

```kotlin
val result = listState(
    data = customers,
    last_page = totalPages,
    last_row = totalCount,
)
```

All state types implement `ISimpleState` with:
- `state`: `Ok`, `Warn`, or `Error`
- `msgOk` / `msgError`: User-facing messages
- `dateTime`: Timestamp
- `hasError`: `state == Error` — "did it break"
- `isRejected`: `state != Ok` — "did it **not succeed**" (since 6.1.0)

Use `isRejected`, not `hasError`, to decide whether a write went through. A repository can refuse a
write with `Warn` and no exception — `Coll.updateOne` and `SqlRepository` do exactly that for an
update that would change nothing — and `hasError` is `false` for those. `ItemState` adds
`isWriteComplete` for the case where a benign no-op should count as done, such as a form deciding
whether it may close.

---

## 21. Annotations

### @Computed

Mark body properties as intentionally non-persisted. This annotation is documentation-only — the constructor-only persistence stripping happens automatically regardless. But `@Computed` makes the intent explicit:

```kotlin
@Serializable
data class Product(
    override val _id: IntId<Product> = IntId(0),
    val price: Double = 0.0,
    val taxRate: Double = 0.0,
) : BaseDoc<IntId<Product>> {
    @Computed
    val priceWithTax: Double
        get() = price * (1 + taxRate)
}
```

### @SqlField

```kotlin
// Rename column
@SqlField(name = "customer_name")
val name: String = ""

// Mark as compound field (nested object stored as multiple columns)
@SqlField(compound = true)
val address: Address = Address()
```

### @SqlIgnoreField

Exclude a property from SQL INSERT/UPDATE (useful for computed or joined fields):

```kotlin
@SqlIgnoreField
val calculatedTotal: Double = 0.0
```

### @SqlOneToOne

Mark a one-to-one relationship:

```kotlin
@SqlOneToOne
val profile: UserProfile? = null
```

---

## 22. Custom Serializers

FSLib provides custom multiplatform serializers (using [kotlinx-serialization](https://github.com/Kotlin/kotlinx.serialization)) for types that need special handling:

| Serializer | Type | Usage |
|-----------|------|-------|
| `OIdSerializer` | `OId<T>` | MongoDB ObjectId |
| `IntIdSerializer` | `IntId<T>` | Integer ID |
| `LongIdSerializer` | `LongId<T>` | Long ID |
| `StringIdSerializer` | `StringId<T>` | String ID |
| `FSLocalDateSerializer` | `LocalDate` | Date without time |
| `FSLocalDateTimeSerializer` | `LocalDateTime` | Date with time |
| `FSOffsetDateTimeSerializer` | `OffsetDateTime` | Date/time with timezone (format: `yyyy-MM-dd HH:mm:ss.SSS`) |
| `FSNumberDoubleSerializer` | `Double` | Custom double handling |
| `FSNumberInt32Serializer` | `Int` | Custom int handling |

These are applied automatically through the [kotlinx-serialization](https://github.com/Kotlin/kotlinx.serialization) module registered with [Kilua RPC](https://github.com/rjaros/kilua-rpc). You generally do not need to reference them directly unless building custom serialization logic.

---

## 23. Help Documentation

### Setup

1. Copy `HELP-DOCS-GUIDE.md` to your project.
2. Create a `help-docs/` directory in your resources.
3. Optionally configure the path in `Main.kt`:

```kotlin
HelpDocsService.setHelpDocsDir("help-docs")
```

### Creating Help Files

```
help-docs/
  ViewListCustomer/
    tutorial.html     # Step-by-step guide
    context.html      # Quick reference card
  ViewItemCustomer/
    tutorial.html
    context.html
```

### Module-Scoped Help

Group views into help modules by implementing `IHelpModule`:

```kotlin
sealed class AppHelpModule(
    override val slug: String,
    override val displayName: String,
) : IHelpModule {
    data object Sales : AppHelpModule("sales", "Sales")
    data object Inventory : AppHelpModule("inventory", "Inventory")
}
```

Then in your view:

```kotlin
class ViewListCustomer : ViewList<...>() {
    override val helpModule = AppHelpModule.Sales
    override val helpEnabled = true
    // Help buttons appear automatically if tutorial.html or context.html exist
}
```

---

## 24. File Attachments (DataMedia)

The `:utils` module provides a complete file attachment system.

### Define DataMedia Model (commonMain)

```kotlin
@Serializable
@Collection("dataMedia")
data class DataMedia(
    override val _id: OId<DataMedia> = OId(),
    override val fileName: String = "",
    override val fileSize: Long = 0,
    override val contentType: String = "",
    override val contentSubtype: String = "",
    override val order: Int = 0,
    override val userId: OId<User>? = null,
    override val hasThumbnail: Boolean = false,
    // Foreign key to parent entity
    val customerId: OId<Customer> = OId(),
) : IDataMedia<User, OId<User>>, BaseDoc<OId<DataMedia>>
```

### Backend Collection (jvmMain)

```kotlin
class DataMediaColl : IDataMediaColl<DataMedia, User, OId<User>>(
    commonContainer = CommonDataMedia,
    mongoDatabase = MongoDb.database,
)
```

### Frontend View (jsMain)

```kotlin
class ViewItemCustomer : ViewItem<...>(), IViewListDataMedia<...> {
    override fun Container.displayPage() {
        // Form fields...

        // Add file attachment panel
        tabDataMedia(viewItem = this@ViewItemCustomer)
    }
}
```

Features:
- File upload with drag-and-drop
- Type filtering (images, videos, PDFs)
- Thumbnail preview
- Download/view links
- Reordering

---

## 25. Periodic Data Updates

Views can automatically refresh their data at intervals:

```kotlin
class ViewListCustomer : ViewList<...>() {
    override val periodicUpdateDataView = true  // Enable periodic refresh

    override val onPeriodicDataUpdate: (() -> Unit)? = {
        dataUpdate()  // Reload data from server
    }
}
```

The refresh interval is controlled by `UserSessionParams.inactivityUiSecsToNoRefresh`. Refreshing pauses after the user has been inactive beyond this threshold.

---

## 26. View Navigation and Routing

### Open a View Programmatically

```kotlin
// Navigate in current window
ViewListCustomer.configViewList.openView()

// Open in modal dialog
ViewItemCustomer.configViewItem.openView(
    apiFilter = CommonCustomer.apiFilterInstance(),
    vmode = ConfigViewContainer.VMode.modal,
)

// Open in new browser tab
ViewListCustomer.configViewList.openView(vmode = ConfigViewContainer.VMode._blank)
```

### URL Parameters

Views serialize their API filter to URL parameters. This enables:
- Deep linking to filtered views
- Browser back/forward navigation
- Bookmarkable filtered states

```kotlin
// In a view, update the URL with the current filter
apiFilterToPageUrl(replaceState = true)
```

### ViewRegistry Lookup

Find a view configuration by URL:

```kotlin
val config = ViewRegistry.findByUrl("ViewListCustomer")
config?.openView()
```

---

## Common Patterns

### Off-Canvas Filter Panel

Add a slide-out filter panel to a list view:

```kotlin
class ViewListCustomer : ViewList<...>() {
    override fun buildOffCanvasFilterView(): Offcanvas {
        return Offcanvas(/* filter UI */) {
            // Filter controls that update apiFilter
            button("Apply") {
                onClick {
                    apiFilter = apiFilter.copy(activeOnly = true)
                    dataUpdate()
                }
            }
        }
    }
}
```

### Custom Context Menu

Add right-click menu items to list rows:

```kotlin
// In ViewItemCustomer companion object:
companion object {
    val configViewItem = configViewItem(
        viewKClass = ViewItemCustomer::class,
        commonContainer = CommonCustomer,
        apiItemFun = ICustomerService::apiItem,
        contextMenuItems = { customer ->
            listOf(
                TabulatorMenuItem("Send Email") { sendEmail(customer.email) },
                TabulatorMenuItem("View Orders") {
                    ViewListOrder.configViewList.openView(OrderFilter().apply {
                        setMasterItemId(customer._id)
                    })
                },
            )
        },
    )
}
```

### Read-Only Repository

```kotlin
class ReportColl : Coll<...>(...) {
    override val readOnly = true  // Blocks all write operations
}
```

---

## 27. Named Routes & API Contract

FSLib provides a complete system for exposing RPC endpoints to third-party clients (Android, native apps, etc.) that don't use KSP-generated [Kilua RPC](https://github.com/rjaros/kilua-rpc) proxies.

### Named Routes

Annotate RPC service methods with `@RpcBindingRoute` to produce human-readable, order-independent route paths:

```kotlin
@RpcService
interface ITaskService {
    @RpcBindingRoute("ITaskService.apiList")
    suspend fun apiList(apiList: ApiList<TaskFilter>): ListState<Task>

    @RpcBindingRoute("ITaskService.apiItem")
    suspend fun apiItem(iApiItem: IApiItem<Task, String, TaskFilter>): ItemState<Task>
}
```

This produces routes like `/rpc/ITaskService.apiList` instead of counter-based defaults like `/rpc/routeTaskServiceManager0`.

### RouteContract

`RouteContract` reads actual routes from [Kilua RPC](https://github.com/rjaros/kilua-rpc)'s `routeMapRegistry` and serves them via an API endpoint:

```kotlin
fun Application.main() {
    // Install Kilua RPC routes first
    routing {
        getAllServiceManagers().forEach { applyRoutes(it) }
    }
    initRpc {
        registerService<ITaskService> { TaskService(repo) }
    }

    // Build and serve the API contract
    val contract = RouteContract(version = "1.0.0")   // your application's API version, not fsLib's
    contract.register(TaskServiceManager, "ITaskService")

    routing {
        apiContractEndpoint(contract)  // GET /apiContract
    }

    contract.validate(getAllServiceManagers())
}
```

### Shared Contract Library

For compile-time type safety across server and client, define models and a contract interface in a shared library module that has no server or frontend dependencies:

```kotlin
// showcase-lib/commonMain — shared between server and Android
interface ITaskServiceContract {
    suspend fun apiList(apiList: ApiList<TaskFilter>): ListState<Task>
    suspend fun apiItem(iApiItem: IApiItem<Task, String, TaskFilter>): ItemState<Task>
}
```

The server's `@RpcService` interface extends this contract:

```kotlin
// showcase-app/commonMain — server module
@RpcService
interface ITaskService : ITaskServiceContract {
    override suspend fun apiList(apiList: ApiList<TaskFilter>): ListState<Task>
    override suspend fun apiItem(iApiItem: IApiItem<Task, String, TaskFilter>): ItemState<Task>
}
```

The Android client implements it with HTTP calls:

```kotlin
// Android app — implements the same contract
class ITaskService : ITaskServiceContract {
    override suspend fun apiList(apiList: ApiList<TaskFilter>): ListState<Task> =
        call("apiList", apiList)

    override suspend fun apiItem(iApiItem: IApiItem<Task, String, TaskFilter>): ItemState<Task> =
        call("apiItem", iApiItem)
}
```

### Wire Protocol

The API contract response includes protocol documentation so third-party clients know how to construct requests:

- **Format**: JSON-RPC 2.0
- **Parameters**: Each parameter is individually JSON-serialized into a string element of the `params` array
- **Result**: The `result` field contains a JSON-serialized string that must be deserialized a second time
- **Request body**: `{"id": 1, "method": "", "params": ["<json-string>"], "jsonrpc": "2.0"}`
- **Response body**: `{"id": 1, "result": "<json-string>", "jsonrpc": "2.0"}`

### Android Client Flow

1. Fetch `GET /apiContract` → discover available services and routes
2. Cache method→route mappings (via `RouteRegistry`)
3. Call methods using `call("methodName", param)` → resolves route, builds JSON-RPC request
4. Deserialize `ListState<T>` or `ItemState<T>` response

> **Note:** When using a shared contract library with `@RpcBindingRoute` named routes, the `/apiContract` endpoint is optional. The client can construct routes directly using the `"/rpc/InterfaceName.methodName"` pattern, gaining compile-time type safety without runtime discovery.

See `samples/fullstack/showcase/` for the complete server-side example, and [showcase-android](https://github.com/tfonrouge/fslib-android/tree/main/samples/showcase-android) for a working Android client.

---

## 28. Single Collection Inheritance

When multiple entity subtypes share one MongoDB collection — differentiated by a discriminator field — use the **Single Collection Inheritance** pattern. This is common in warehouse/inventory systems, financial transactions, or any domain where several document types share a base structure but carry subtype-specific fields.

### Architecture

```
┌──────────────────────────────────┐
│  IWarehouseEntry<ID> (interface) │  ← shared field contract
│  extends BaseDoc<ID>             │
├──────────────────────────────────┤
│  dateTime, userId, docNumber     │  ← common fields
│  state, entryType, sign          │  ← discriminator + classification
│  warehouseId, itemCount          │
│  @Computed var user: User?       │  ← lookup-populated (body property)
│  @Computed var warehouse: Wh?    │
└──────┬───────┬───────┬───────────┘
       │       │       │
  ReceiptEntry  ShipmentEntry  TransferEntry   ← concrete subtypes
  (extra fields per subtype, each with fixed entryType/sign)
```

All subtypes are stored in the same MongoDB collection (e.g., `@Collection("warehouseEntries")`), queried and filtered by the discriminator field (`entryType`).

### Step 1: Define the shared interface (commonMain)

```kotlin
@Collection(name = "warehouseEntries")
interface IWarehouseEntry<ID : Any> : BaseDoc<ID> {
    @Serializable(with = FSOffsetDateTimeSerializer::class)
    val dateTime: OffsetDateTime
    val userId: StringId<User>
    val docNumber: Int
    val state: State
    val entryType: EntryType       // discriminator
    val sign: Sign                 // +1 or -1
    val warehouseId: StringId<Warehouse>
    val itemCount: Int

    // Lookup-populated fields (not persisted)
    var user: User?
    var warehouse: Warehouse?

    @Serializable
    enum class EntryType(val label: String) {
        Receipt("Receipt"),
        Shipment("Shipment"),
        Transfer("Transfer"),
    }

    @Serializable
    enum class Sign(val multiplier: Int) {
        In(1),
        Out(-1),
    }

    @Serializable
    enum class State { New, Processing, Closed }
}
```

### Step 2: Implement concrete subtypes (commonMain)

Each subtype fixes the discriminator values and may add subtype-specific fields:

```kotlin
@Serializable
data class ReceiptEntry(
    override val _id: OId<ReceiptEntry> = OId(),
    override val dateTime: OffsetDateTime = offsetDateTimeNow(),
    override val userId: StringId<User>,
    override val docNumber: Int,
    override val state: IWarehouseEntry.State = IWarehouseEntry.State.New,
    override val warehouseId: StringId<Warehouse>,
    override val itemCount: Int = 0,
    // discriminator values as constructor params with fixed defaults
    override val entryType: IWarehouseEntry.EntryType = IWarehouseEntry.EntryType.Receipt,
    override val sign: IWarehouseEntry.Sign = IWarehouseEntry.Sign.In,
    // subtype-specific fields
    val supplierId: StringId<Supplier>,
    val purchaseOrderRef: String? = null,
) : IWarehouseEntry<OId<ReceiptEntry>> {
    @Computed override var user: User? = null
    @Computed override var warehouse: Warehouse? = null
}

@Serializable
data class TransferEntry(
    override val _id: OId<TransferEntry> = OId(),
    override val dateTime: OffsetDateTime = offsetDateTimeNow(),
    override val userId: StringId<User>,
    override val docNumber: Int,
    override val state: IWarehouseEntry.State = IWarehouseEntry.State.New,
    override val warehouseId: StringId<Warehouse>,
    override val itemCount: Int = 0,
    override val entryType: IWarehouseEntry.EntryType = IWarehouseEntry.EntryType.Transfer,
    override val sign: IWarehouseEntry.Sign = IWarehouseEntry.Sign.Out,
    // subtype-specific field
    val destinationWarehouseId: StringId<Warehouse>,
) : IWarehouseEntry<OId<TransferEntry>> {
    @Computed override var user: User? = null
    @Computed override var warehouse: Warehouse? = null
    @Computed var destinationWarehouse: Warehouse? = null
}
```

**Key decisions:**
- **Discriminator fields (`entryType`, `sign`) are constructor parameters** with fixed defaults — this ensures they are persisted and queryable from raw data.
- **Lookup-populated fields are body properties** with `@Computed` — stripped by `ConstructorCopier` before writes, populated by the aggregation pipeline on reads.
- **`itemCount`** should be a body property with `@Computed` if it is always computed by the aggregation pipeline and never stored. Keep it as a constructor parameter only if you need the persisted value.

### Step 3: Common containers per subtype (commonMain)

Each subtype needs its own `ICommonContainer`:

```kotlin
val CommonReceiptEntry = simpleContainer<ReceiptEntry, OId<ReceiptEntry>>(
    labelItem = "Receipt",
    labelList = "Receipts",
    labelId = { it?.let { "Receipt #${it.docNumber}" } ?: "" },
)

val CommonTransferEntry = simpleContainer<TransferEntry, OId<TransferEntry>>(
    labelItem = "Transfer",
    labelList = "Transfers",
    labelId = { it?.let { "Transfer #${it.docNumber}" } ?: "" },
)
```

### Step 4: Abstract repository with shared logic (jvmMain)

```kotlin
abstract class IWarehouseEntryColl<
    T : IWarehouseEntry<ID>,
    ID : OId<out T>,
    FILT : IApiFilter<*>,
>(
    commonContainer: ICommonContainer<T, ID, FILT>,
    mongoDatabase: MongoDatabase,
) : Coll<T, ID, FILT, StringId<User>>(
    commonContainer = commonContainer,
    mongoDatabase = mongoDatabase,
) {
    // Shared: prevent editing closed entries
    override suspend fun onQueryUpdate(
        apiItem: ApiItem.Query.Update<T, ID, FILT>,
        orig: T,
    ): SimpleState {
        if (orig.state == IWarehouseEntry.State.Closed) {
            return SimpleState(isOk = false, msgError = "${commonContainer.labelItem} is closed")
        }
        return super.onQueryUpdate(apiItem, orig)
    }

    // Shared: lookups for user and warehouse
    override val lookupFun: (FILT) -> List<LookupPipelineBuilder<T, *, *>> = {
        listOf(
            lookupField(
                coll = UserColl,
                localField = IWarehouseEntry<*>::userId,
                foreignField = User::_id,
                resultField = IWarehouseEntry<*>::user,
            ),
            lookupField(
                coll = WarehouseColl,
                localField = IWarehouseEntry<*>::warehouseId,
                foreignField = Warehouse::_id,
                resultField = IWarehouseEntry<*>::warehouse,
            ),
        )
    }

    // Shared: auto-increment document number per entry type
    abstract val entryType: IWarehouseEntry.EntryType

    suspend fun getNextDocNumber(): Int {
        val pipeline = mutableListOf(
            match(IWarehouseEntry<*>::entryType eq entryType),
            sort(descending(IWarehouseEntry<*>::docNumber)),
            limit(1),
        )
        return aggregateLookupPublisher(
            pipeline = pipeline,
            resultUnit = ResultUnit.Single,
        ).awaitFirstOrNull()?.docNumber?.plus(1) ?: 1
    }
}
```

### Step 5: Concrete repositories per subtype (jvmMain)

```kotlin
class ReceiptEntryColl : IWarehouseEntryColl<
    ReceiptEntry, OId<ReceiptEntry>, ApiFilter,
>(
    commonContainer = CommonReceiptEntry,
    mongoDatabase = MongoDb.database,
) {
    override val entryType = IWarehouseEntry.EntryType.Receipt
    override val userCollFun = { UserColl() }

    // Match stage filters by discriminator automatically
    override fun findItemFilter(apiFilter: ApiFilter): Bson? =
        IWarehouseEntry<*>::entryType eq entryType
}

class TransferEntryColl : IWarehouseEntryColl<
    TransferEntry, OId<TransferEntry>, ApiFilter,
>(
    commonContainer = CommonTransferEntry,
    mongoDatabase = MongoDb.database,
) {
    override val entryType = IWarehouseEntry.EntryType.Transfer
    override val userCollFun = { UserColl() }

    override fun findItemFilter(apiFilter: ApiFilter): Bson? =
        IWarehouseEntry<*>::entryType eq entryType

    // Subtype-specific: additional lookup for destination warehouse
    override val lookupFun: (ApiFilter) -> List<LookupPipelineBuilder<TransferEntry, *, *>> = {
        super.lookupFun(it) + lookupField(
            coll = WarehouseColl,
            localField = TransferEntry::destinationWarehouseId,
            foreignField = Warehouse::_id,
            resultField = TransferEntry::destinationWarehouse,
        )
    }
}
```

### When to use this pattern

- Multiple document types sharing **most fields** but with a few **subtype-specific properties**
- All subtypes stored in the **same MongoDB collection** for unified querying and indexing
- Shared **lifecycle hooks**, **lookups**, and **business rules** across subtypes
- A **discriminator field** (`entryType`, `tipoRegistro`, `kind`, etc.) that partitions the collection

### When NOT to use this pattern

- Subtypes have **vastly different fields** — use separate collections instead
- You need **different indexes per subtype** that would conflict in one collection
- The collection would grow too large with mixed document shapes — consider sharding or splitting
