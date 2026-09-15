import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// Single source of truth for the project version: gradle/libs.versions.toml
val projectVersion: String = extensions
    .getByType(VersionCatalogsExtension::class.java)
    .named("libs")
    .findVersion("project")
    .get()
    .requiredVersion

// ---------------------------------------------------------------------------
// Language level follows the engine.
//
// The built-in engine is plain JVM 17. The AB Download Manager bridge is compiled
// against upstream's desktop artifacts, which upstream builds with `jvm.toolchain=25`
// (class file version 69), so an abdm-enabled build needs JDK 25 for both compiling
// and running. This is why `-Pabdm.enabled=true` also raises the toolchain here.
// ---------------------------------------------------------------------------
val abdmEnabled: Boolean = providers.gradleProperty("abdm.enabled").getOrElse("false").toBoolean()
val javaVersion: Int = if (abdmEnabled) 25 else 17

allprojects {
    group = "dev.abdm.server"
    version = projectVersion
}

logger.lifecycle("abdm-server: abdm.enabled=$abdmEnabled, jvm toolchain=$javaVersion")

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
