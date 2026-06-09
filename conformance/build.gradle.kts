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

// Test-only module (not published): scaffold for the cross-engine conformance suite that will pin
// the IRepository write/delete/lifecycle contract (blueprints/repository-write-lifecycle/CONTRACT.md,
// LEDGER D9). Currently a SQL/H2 smoke test proving SqlRepository runs without Docker. The
// engine-agnostic assertion harness (memory + SQL), the permission-parity test (which adds mockk),
// and Mongo participation (real-mongod decision, PLAN P1.8 / C) are pending follow-ups.
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
    testImplementation("com.h2database:h2:2.2.224")

    // NOTE: mockk (for a non-null ApplicationCall in permission-parity tests) is added when that
    // test lands — see PLAN P1.8 / LEDGER D9.

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
}
