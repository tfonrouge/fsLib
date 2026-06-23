plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

group = "com.fonrouge.fslib"
version = libs.versions.fsLib.get()

repositories {
    mavenCentral()
    mavenLocal()
}

kotlin {
    jvmToolchain(25)
}

// Runnable RBAC walkthrough (blueprint rbac-permission-resolution, P3.3). A console app — no database —
// that drives the REAL backend-agnostic resolver (RbacResolver) and membership API (RbacMembership) over
// the shippable in-memory grant port (InMemoryRbacGrantPort), demonstrating the group-only membership
// bypass fix (R12), the direct-grant precedence (D1), existence != authorization (D8), and the intra-group
// tie-break (D2). See README.md for the production MongoDB wiring (MongoRbac.register + ensureRoles).
application {
    mainClass.set("com.example.rbac.MainKt")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":fullstack")) {
        attributes {
            attribute(
                org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.attribute,
                org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.jvm
            )
        }
    }
    implementation(project(":memorydb"))
    implementation(libs.kotlinx.coroutines.core)
}
