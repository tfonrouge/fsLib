plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.serialization)
    id("fslib-publishing")
    id("org.jetbrains.dokka") version "2.0.0"
}

group = "com.fonrouge.fslib"
version = libs.versions.fsLib.get()

repositories {
    mavenCentral()
    mavenLocal()
}

kotlin {
    jvmToolchain(25)
    jvm {
        compilerOptions {
            freeCompilerArgs = listOf(
                "-Xjsr305=strict",
            )
        }
    }
    js(IR) {
        browser {
            useEsModules()
            commonWebpackConfig {
                outputFileName = "main.bundle.js"
                sourceMaps = false
            }
        }
        binaries.library()
        compilerOptions {
            target.set("es2015")
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kvision.common.remote)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        jvmMain.dependencies {
            implementation(kotlin("reflect"))
            // ApplicationCall actual typealias
            api(libs.ktor.server.core)
            // BSON serializer actuals (OIdSerializer, FSNumber*, FSOffsetDateTime*)
            implementation(libs.kmongo.coroutine.serialization)
            implementation(libs.kotlinx.datetime.jvm)
        }

        jsMain.dependencies {
            // No UI dependencies — just kvision-common-remote transitively
        }
    }
}
