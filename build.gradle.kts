plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.serialization) apply false
    alias(libs.plugins.google.devtools.ksp) apply false
    alias(libs.plugins.kilua.rpc) apply false
}

// ---------------------------------------------------------------------------
// Central Portal bundle upload task
// ---------------------------------------------------------------------------
// Publishes all staged artifacts to Maven Central via the Central Portal API.
//
// Full workflow:
//   1. ./gradlew publishAllPublicationsToStagingRepository
//   2. ./gradlew publishToCentralPortal
// ---------------------------------------------------------------------------

// Staging accumulates across releases (module publications APPEND into staging-deploy), and the
// portal upload zips the WHOLE directory — so a leftover prior release gets re-submitted and every
// one of its components is rejected by Central as already existing, failing the entire deployment
// (including the genuinely new version riding in the same bundle). This bit twice as a forgotten
// manual step (6.2.2 → 6.2.3 residue, then 6.2.3 → 6.2.4); these two guards retire it:
// `cleanStagingDeploy` runs before any staging publication, and the upload refuses a mixed bundle.
val cleanStagingDeploy = tasks.register("cleanStagingDeploy", Delete::class) {
    description = "Empties staging-deploy so a release bundle can only contain the version being staged"
    group = "publishing"
    delete(layout.buildDirectory.dir("staging-deploy"), layout.buildDirectory.file("central-bundle.zip"))
}

tasks.register("publishToCentralPortal", Exec::class) {
    description = "Uploads the staging-deploy bundle to Maven Central Portal"
    group = "publishing"

    val stagingDir = layout.buildDirectory.dir("staging-deploy")
    val bundleFile = layout.buildDirectory.file("central-bundle.zip")
    val username = providers.gradleProperty("ossrhUsername")
    val password = providers.gradleProperty("ossrhPassword")

    inputs.dir(stagingDir)
    outputs.file(bundleFile)

    doFirst {
        val staging = stagingDir.get().asFile
        if (!staging.exists() || staging.listFiles()?.isEmpty() != false) {
            error("No staged artifacts found. Run publishAllPublicationsToStagingRepository first.")
        }

        // Refuse a mixed bundle: Central rejects any component whose version already exists, and
        // one rejected component fails the whole deployment — taking the new release down with it.
        val stagedVersions = staging.walkTopDown()
            .filter { it.isFile && it.extension == "pom" }
            .mapNotNull { it.parentFile?.name }
            .toSortedSet()
        if (stagedVersions.size != 1) {
            error(
                "staging-deploy contains ${stagedVersions.size} versions: $stagedVersions. " +
                    "A bundle must carry exactly one. Run ./gradlew cleanStagingDeploy " +
                    "publishAllPublicationsToStagingRepository and retry."
            )
        }

        // Create ZIP bundle from staging directory
        ant.withGroovyBuilder {
            "zip"("destfile" to bundleFile.get().asFile, "basedir" to staging)
        }

        val user = username.getOrElse("")
        val pass = password.getOrElse("")
        if (user.isBlank() || pass.isBlank()) {
            error("ossrhUsername/ossrhPassword not set in ~/.gradle/gradle.properties")
        }

        val authToken = java.util.Base64.getEncoder()
            .encodeToString("$user:$pass".toByteArray())

        commandLine(
            "curl", "-s", "-w", "\n%{http_code}",
            "--fail-with-body",
            "-X", "POST",
            "https://central.sonatype.com/api/v1/publisher/upload?publishingType=AUTOMATIC",
            "-H", "Authorization: UserToken $authToken",
            "-F", "bundle=@${bundleFile.get().asFile.absolutePath}"
        )
    }
}
