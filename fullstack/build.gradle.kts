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
    jvmToolchain(21)
    jvm {
        compilerOptions {
            freeCompilerArgs = listOf(
                "-Xjsr305=strict",
                "-Xallow-kotlin-package",
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
            api(project(":core"))
            implementation(libs.kilua.rpc.ktor)
            implementation(libs.kvision.common.remote)
            implementation(libs.kotlinx.datetime)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        jvmMain.dependencies {
            implementation(kotlin("reflect"))
            // Ktor client
            api(libs.ktor.client.core)
            api(libs.ktor.client.cio)
            api(libs.ktor.client.auth)
            api(libs.ktor.client.content.negotiation)
            api(libs.ktor.client.encoding)
            api(libs.ktor.serialization.kotlinx.json)
            api(libs.ktor.client.serialization)
            // Ktor server
            api(libs.ktor.server.auth)
            api(libs.ktor.server.auth.jwt)
            api(libs.ktor.server.call.logging)
            api(libs.ktor.server.compression)
            api(libs.ktor.server.core)
            api(libs.ktor.server.default.headers)
            api(libs.ktor.server.forwarded.header)
            api(libs.ktor.server.netty)
            api(libs.ktor.server.sessions)
            api(libs.ktor.network.tls.certificates)
            api(libs.ktor.server.auto.head.response)
            api(libs.ktor.server.http.redirect)
            api(libs.ktor.server.partial.content)
            api(libs.ktor.server.content.negotiation)
            api(libs.ktor.server.status.pages)
        }

        jsMain.dependencies {
            // KVision UI stack (moved from :core)
            api(libs.kvision)
            api(libs.kvision.bootstrap)
            api(libs.kvision.bootstrap.icons)
            api(libs.kvision.bootstrap.upload)
            api(libs.kvision.datetime)
            api(libs.kvision.chart)
            api(libs.kvision.fontawesome)
            api(libs.kvision.imask)
            api(libs.kvision.i18n)
            api(libs.kvision.jquery)
            api(libs.kvision.pace)
            api(libs.kvision.print)
            api(libs.kvision.react)
            api(libs.kvision.redux.kotlin)
            api(libs.kvision.rest)
            api(libs.kvision.richtext)
            api(libs.kvision.routing.navigo.ng)
            api(libs.kvision.state)
            api(libs.kvision.tabulator)
            api(libs.kvision.tabulator.remote)
            api(libs.kvision.toastify)
            api(libs.kvision.tom.select)
            api(libs.kvision.tom.select.remote)
        }
    }
}

