plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// ---------------------------------------------------------------------------
// The AB Download Manager adapter.
//
// Two mutually exclusive implementations of the same entry point are compiled:
//
//   -Pabdm.enabled=true   src/abdm/kotlin against third_party/abdm-dist
//                         (the upstream desktop runtime exported by
//                          scripts/build-abdm-bridge.sh)
//   -Pabdm.enabled=false  src/stub/kotlin, which reports ENGINE_UNAVAILABLE and
//                         keeps the default build (and the Docker image) free of
//                         any Android/KMP dependency.
// ---------------------------------------------------------------------------
val abdmEnabled = providers.gradleProperty("abdm.enabled").getOrElse("false").toBoolean()
val abdmDist = rootProject.layout.projectDirectory.dir("third_party/abdm-dist")
val abdmJars = fileTree(abdmDist) { include("*.jar") }

sourceSets {
    named("main") {
        kotlin.srcDir(if (abdmEnabled) "src/abdm/kotlin" else "src/stub/kotlin")
    }
    named("test") {
        // AbdmCompatibilityTest compiles against real upstream classes, so the test
        // source set only participates when the bridge is present. `build` stays green
        // (with zero tests here) in the default, bridge-less configuration.
        kotlin.setSrcDirs(if (abdmEnabled) listOf("src/test/kotlin") else emptyList())
    }
}

dependencies {
    api(project(":server:engine-api"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)

    if (abdmEnabled) {
        implementation(abdmJars)
    }

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
}

if (abdmEnabled && abdmJars.isEmpty) {
    throw GradleException(
        "abdm.enabled=true but third_party/abdm-dist is empty. " +
            "Run scripts/build-abdm-bridge.sh (or .ps1) first, or build with -Pabdm.enabled=false.",
    )
}

tasks.withType<Test>().configureEach {
    // Nothing to run without the bridge, and the compatibility test is network bound,
    // so it is the `abdm-compat` CI job that runs it on purpose.
    onlyIf { abdmEnabled }
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Implementation-Title" to "abdm-server engine-abdm",
            "Engine-Mode" to if (abdmEnabled) "abdm" else "stub",
        )
    }
}
