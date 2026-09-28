import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// Single source of truth for the project version: gradle/libs.versions.toml
// `-Pproject.version=X.Y.Z` overrides it (used by the manual release workflow so the
// jar name, the manifest and the API report the released version).
val projectVersion: String = providers.gradleProperty("project.version").orNull
    ?: extensions
        .getByType(VersionCatalogsExtension::class.java)
        .named("libs")
        .findVersion("project")
        .get()
        .requiredVersion

// ---------------------------------------------------------------------------
// The pinned ABDM sources are exported with `-Pjvm.toolchain=17`.
// ---------------------------------------------------------------------------
val javaVersion: Int = 17
require(providers.gradleProperty("abdm.enabled").orNull != "false") {
    "-Pabdm.enabled=false is no longer supported: every build uses upstream ABDM."
}

allprojects {
    group = "dev.abdm.server"
    version = projectVersion
}

logger.lifecycle("abdm-server: pinned ABDM engine, jvm toolchain=$javaVersion")

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension>("kotlin") {
            jvmToolchain(javaVersion)
            compilerOptions {
                freeCompilerArgs.add("-Xjsr305=strict")
            }
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging {
                events("passed", "skipped", "failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.release.set(javaVersion)
        }
        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
            compilerOptions {
                jvmTarget.set(JvmTarget.fromTarget(javaVersion.toString()))
            }
        }
    }
}
