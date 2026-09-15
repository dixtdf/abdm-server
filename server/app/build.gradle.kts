import org.gradle.api.tasks.SourceSetContainer

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass.set("dev.abdm.server.app.MainKt")
    applicationName = "abdm-server"
}

dependencies {
    implementation(project(":server:engine-api"))
    implementation(project(":server:engine-native"))
    implementation(project(":server:engine-abdm"))
    implementation(project(":server:persistence"))
    implementation(project(":server:scheduler"))
    implementation(project(":server:web-api"))

    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.default.headers)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.compression)

    runtimeOnly(libs.slf4j.simple)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.kotlinx.coroutines.test)
}

// ---------------------------------------------------------------------------
// Single runnable jar: `:server:app:fatJar` -> build/libs/abdm-server-<v>-all.jar
//
// Written by hand on purpose: no Shadow plugin (and therefore no extra plugin
// resolution) is required, and the resulting jar is exactly what the Dockerfile
// and the GitHub release upload.
// ---------------------------------------------------------------------------
val fatJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Assembles a self contained executable jar (server + web assets)"
    archiveBaseName.set("abdm-server")
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Main-Class" to "dev.abdm.server.app.MainKt",
            "Implementation-Title" to "abdm-server",
            "Implementation-Version" to project.version.toString(),
        )
    }
    from(sourceSets.main.get().output)
    from({
        configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/INDEX.LIST", "module-info.class")
}

// Embed the built frontend when it exists, so the jar is self contained.
// CI builds `web/dist` before calling Gradle; a backend-only build simply skips it.
val webDist = rootProject.layout.projectDirectory.dir("web/dist")

tasks.named<ProcessResources>("processResources") {
    if (webDist.asFile.isDirectory) {
        from(webDist) {
            into("web")
        }
        inputs.dir(webDist).withPropertyName("embeddedWebAssets")
    }
}

tasks.named("build") {
    dependsOn(fatJar)
}

// One naming scheme everywhere: abdm-server-<version>.jar / abdm-server-<version>-all.jar
// (the Dockerfile, the release workflow and the README all refer to that name).
tasks.withType<Jar>().configureEach {
    archiveBaseName.set("abdm-server")
}

// CI/README alias: some docs refer to `:server:app:shadowJar`.
tasks.register("shadowJar") {
    group = "build"
    description = "Alias of fatJar (kept for documentation compatibility)"
    dependsOn(fatJar)
}
