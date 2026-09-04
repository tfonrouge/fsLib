// ---------------------------------------------------------------------------
// Convention plugin: Maven Central publishing for FSLib modules
// ---------------------------------------------------------------------------
// Apply this plugin in any module that should be published to Maven Central.
//
// Credentials are read from ~/.gradle/gradle.properties:
//   ossrhUsername=<your Sonatype Central Portal token username>
//   ossrhPassword=<your Sonatype Central Portal token password>
//
// GPG signing uses the system gpg command (supports ed25519 keys).
// Configure in ~/.gradle/gradle.properties:
//   signing.gnupg.keyName=<GPG key ID, e.g. A80F810E>
//   signing.gnupg.passphrase=<GPG key passphrase>
//
// Usage:
//   ./gradlew publishAllPublicationsToStagingRepository  (all modules)
//   ./gradlew :core:publishAllPublicationsToStagingRepository  (single module)
//   Then from root: ./gradlew publishToCentralPortal
//
// Local development:
//   ./gradlew publishToMavenLocal -PSNAPSHOT
//   Appends "-SNAPSHOT" to the version automatically.
//   The -PSNAPSHOT flag is required — publishing a release version to mavenLocal
//   is blocked to prevent silently shadowing Maven Central artifacts.
//   To override this safety check: ./gradlew publishToMavenLocal -PFORCE_LOCAL
// ---------------------------------------------------------------------------

plugins {
    `maven-publish`
    signing
}

// Maven Central requires javadoc and sources JARs for all publications.
// KMP modules produce these automatically; JVM-only modules need explicit config.
val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

// For JVM-only modules (kotlin("jvm")), register a sources JAR from the main source set.
plugins.withId("org.jetbrains.kotlin.jvm") {
    val sourcesJar by tasks.registering(Jar::class) {
        archiveClassifier.set("sources")
        from(project.the<SourceSetContainer>()["main"].allSource)
    }
    publishing {
        publications.configureEach {
            if (this is MavenPublication && name == "maven") {
                artifact(sourcesJar)
            }
        }
    }
}

publishing {
    publications.configureEach {
        if (this is MavenPublication && (name == "jvm" || name == "maven")) {
            artifact(javadocJar)
        }
    }
}

val isSnapshotPublish = hasProperty("SNAPSHOT")
val isForceLocal = hasProperty("FORCE_LOCAL")

// Append "-SNAPSHOT" to the version when the -PSNAPSHOT flag is passed.
// Runs in afterEvaluate so it executes AFTER the module's build.gradle.kts
// has set its version from libs.versions.toml.
if (isSnapshotPublish) {
    afterEvaluate {
        if (!version.toString().endsWith("-SNAPSHOT")) {
            version = "${version}-SNAPSHOT"
        }
    }
}

// Prevent publishing release versions to mavenLocal — this would silently shadow
// Maven Central artifacts for every project on the machine that uses mavenLocal().
// Fail at configuration time (not execution time) to stay compatible with the
// configuration cache, which cannot serialize script object references in doFirst lambdas.
if (gradle.startParameter.taskNames.any { it.contains("publishToMavenLocal", ignoreCase = true) }) {
    if (!isSnapshotPublish && !isForceLocal) {
        error(
            "Publishing a release version to mavenLocal is blocked to prevent " +
                "shadowing Maven Central artifacts.\n" +
                "  Use: ./gradlew publishToMavenLocal -PSNAPSHOT  (recommended)\n" +
                "  Or:  ./gradlew publishToMavenLocal -PFORCE_LOCAL  (override safety check)"
        )
    }
}

publishing {
    publications.withType<MavenPublication> {
        pom {
            name.set("FSLib ${project.name}")
            description.set(
                "Kotlin Multiplatform library for full-stack web applications " +
                    "with MongoDB/SQL backends and KVision frontend"
            )
            url.set("https://github.com/tfonrouge/FSLib")

            licenses {
                license {
                    name.set("MIT License")
                    url.set("https://opensource.org/licenses/MIT")
                }
            }

            developers {
                developer {
                    id.set("tfonrouge")
                    name.set("Teo Fonrouge")
                    url.set("https://github.com/tfonrouge")
                }
            }

            scm {
                connection.set("scm:git:git://github.com/tfonrouge/FSLib.git")
                developerConnection.set("scm:git:ssh://github.com/tfonrouge/FSLib.git")
                url.set("https://github.com/tfonrouge/FSLib")
            }
        }
    }

    // Every publication into Staging first empties it (root `cleanStagingDeploy`, executed at most
    // once per build), so a release bundle can never accumulate a previous release. See the root
    // build script for the incident history this encodes.
    tasks.withType<PublishToMavenRepository>().configureEach {
        if (name.contains("ToStagingRepository")) {
            dependsOn(":cleanStagingDeploy")
        }
    }

    repositories {
        maven {
            name = "Staging"
            url = uri(rootProject.layout.buildDirectory.dir("staging-deploy"))
        }
    }
}

signing {
    // Use system gpg command (required for ed25519 keys)
    useGpgCmd()
    // Only require signing when publishing to staging (not for publishToMavenLocal)
    isRequired = findProperty("signing.gnupg.keyName") != null
    sign(publishing.publications)
}

// Disable signing tasks for local/snapshot publishes. The signing plugin's internal
// actions access Task.project at execution time, which is incompatible with the
// configuration cache. Signing is unnecessary for mavenLocal artifacts.
if (isSnapshotPublish || isForceLocal) {
    tasks.withType<Sign> {
        enabled = false
    }
}