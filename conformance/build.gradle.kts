plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.serialization)
}

group = "com.fonrouge.fslib"
version = libs.versions.fsLib.get()

repositories {
    mavenCentral()
    mavenLocal()
}

kotlin {
    jvmToolchain(21)
}

// Test-only module (not published): cross-engine conformance suite pinning the IRepository
// write/delete/lifecycle contract (blueprints/repository-write-lifecycle/CONTRACT.md, LEDGER D9/D11).
// Runs engine-agnostic assertions against memory + SQL (H2, no Docker) and Mongo (Testcontainers): the
// generic-CRUD gate (I5), per-action permission parity (I6), validation side-effect freedom (I2),
// delete safety (I3), canonical hook order (I1), and init lifecycle (I4) — plus the D6 real-mongod
// write-failure test (F1 duplicate-key path, MongoWriteFailureTest). The Mongo tests Assume-skip
// when Docker is absent (green-or-skip locally) and run for real in CI (decision C, D11).
dependencies {
    testImplementation(project(":core"))
    testImplementation(project(":fullstack")) {
        attributes {
            attribute(
                org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.attribute,
                org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.jvm
            )
        }
    }
    testImplementation(project(":memorydb"))
    testImplementation(project(":sql"))
    testImplementation(project(":mongodb"))

    testImplementation(kotlin("reflect"))
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.ktor.server.core)

    // Exposed + H2 so SqlRepository can run against an in-memory SQL engine (no Docker).
    testImplementation(libs.exposed.core)
    testImplementation(libs.exposed.jdbc)
    testImplementation(libs.h2)

    // mockk supplies a non-null ApplicationCall for the per-action permission-parity test.
    testImplementation(libs.mockk)

    // Testcontainers Mongo so Coll runs against a real mongod. Needs Docker → the Mongo conformance
    // tests Assume-skip when Docker is absent (local dev) and run for real in CI (decision C).
    testImplementation(libs.testcontainers.mongodb)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit) // org.junit.Assume for assume-gated target tests
    testImplementation(libs.kotlinx.coroutines.test)
}
