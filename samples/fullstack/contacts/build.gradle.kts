import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.serialization)
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.kilua.rpc)
    alias(libs.plugins.kvision)
}

version = "1.0.0-SNAPSHOT"
group = "com.example"

val mainClassName = "io.ktor.server.netty.EngineMain"

val copyJsBundleToAssets by tasks.registering(Copy::class) {
    group = "application"
    description = "Copies the webpack development bundle and index.html into the JVM classpath under /assets"
    dependsOn("jsBrowserDevelopmentWebpack", "jsProcessResources")
    from(tasks.named("jsBrowserDevelopmentWebpack").map { it.outputs.files })
    from(layout.buildDirectory.dir("processedResources/js/main"))
    into(layout.buildDirectory.dir("processedResources/jvm/main/assets"))
}

tasks.matching { it.name == "jvmRun" }.configureEach {
    dependsOn(copyJsBundleToAssets)
}

tasks.register("run") {
    group = "application"
    description = "Builds the JS frontend and starts the Ktor backend on http://localhost:8080"
    dependsOn(copyJsBundleToAssets)
    finalizedBy("jvmRun")
}

kotlin {
    jvmToolchain(25)
    jvm {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            freeCompilerArgs = listOf("-Xjsr305=strict")
        }
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        mainRun {
            mainClass.set(mainClassName)
        }
    }
    js(IR) {
        browser {
            useEsModules()
            commonWebpackConfig {
                outputFileName = "main.bundle.js"
                sourceMaps = false
            }
            testTask {
                useKarma {
                    useChromeHeadless()
                }
            }
        }
        binaries.executable()
        compilerOptions {
            target.set("es2015")
        }
    }
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":fullstack"))
                implementation(project(":core"))
                implementation(libs.kilua.rpc.ktor)
                implementation(libs.kvision.common.remote)
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(libs.ktor.server.netty)
                implementation(libs.ktor.server.compression)
            }
        }
        val jsMain by getting {
            dependencies {
                implementation(libs.kvision)
                implementation(libs.kvision.bootstrap)
                implementation(libs.kvision.fontawesome)
                implementation(libs.kvision.toastify)
                implementation(libs.kvision.tom.select)
                implementation(libs.kvision.tom.select.remote)
                implementation(libs.kvision.tabulator.remote)
                implementation(libs.kvision.material)
            }
        }
        val jsTest by getting {
            dependencies {
                implementation(kotlin("test-js"))
                implementation(libs.kvision.testutils)
            }
        }
    }
}
