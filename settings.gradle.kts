pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "abdm-server"

// ---------------------------------------------------------------------------
// Project modules
//
//  server:app          -> Ktor bootstrap, wiring, static web assets
//  server:engine-api   -> engine abstraction (no ABDM types leak out)
//  server:engine-native-> built-in segmented HTTP/HTTPS engine (always built)
//  server:engine-abdm  -> AB Download Manager adapter (optional, see abdm.enabled)
//  server:persistence  -> SQLite storage
//  server:scheduler    -> queue / concurrency scheduling
//  server:web-api      -> REST + WebSocket surface
// ---------------------------------------------------------------------------
include("server:app")
include("server:engine-api")
include("server:engine-native")
include("server:engine-abdm")
include("server:persistence")
include("server:scheduler")
include("server:web-api")
