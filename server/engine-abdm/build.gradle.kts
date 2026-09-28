plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// The only download engine: the pinned upstream desktop runtime exported by
// scripts/build-abdm-bridge.sh. The submodule source stays untouched.
val abdmDist = rootProject.layout.projectDirectory.dir("third_party/abdm-dist")
val abdmJars = fileTree(abdmDist) { include("*.jar") }

dependencies {
    api(project(":server:engine-api"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)

    implementation(abdmJars)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
}

if (abdmJars.isEmpty) {
    throw GradleException(
        "ABDM bridge is missing from third_party/abdm-dist. " +
            "Run scripts/build-abdm-bridge.sh before building.",
    )
}

val upstreamDoc = rootProject.layout.projectDirectory.file("docs/upstream.md").asFile.readText()
val expectedCommit = Regex("(?m)^UPSTREAM_COMMIT=([a-f0-9]{40})$")
    .find(upstreamDoc)?.groupValues?.get(1)
val bridgeCommit = abdmDist.file(".abdm-pin").asFile
    .takeIf { it.isFile }?.readText()?.trim()
if (expectedCommit == null || bridgeCommit != expectedCommit) {
    throw GradleException(
        "ABDM bridge pin mismatch (expected $expectedCommit, got $bridgeCommit). " +
            "Run scripts/build-abdm-bridge.sh before building.",
    )
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Implementation-Title" to "abdm-server engine-abdm",
            "Engine-Mode" to "abdm",
        )
    }
}
