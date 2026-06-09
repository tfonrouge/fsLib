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
// write/delete/lifecycle contract (blueprints/repository-write-lifecycle/CONTRACT.md, LEDGER D9).
// Runs engine-agnostic assertions against the memory + SQL (H2, no Docker) engines: the generic-CRUD
// gate (I5), per-action permission parity (I6), and canonical hook order (I1, assume-gated/skipped on
// SQL until P2.2). Remaining: validation side-effect (I2) + delete-exactly-once (I3) assertions, and
// Mongo participation (real-mongod decision, PLAN P1.8 / C).
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

    testImplementation(kotlin("reflect"))
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.ktor.server.core)

    // Exposed + H2 so SqlRepository can run against an in-memory SQL engine (no Docker).
    testImplementation(libs.exposed.core)
    testImplementation(libs.exposed.jdbc)
    testImplementation(libs.h2)

    // mockk supplies a non-null ApplicationCall for the per-action permission-parity test.
    testImplementation(libs.mockk)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit) // org.junit.Assume for assume-gated target tests
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
}
