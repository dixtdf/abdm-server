// ---------------------------------------------------------------------------
// Superseded: use scripts/abdm-bridge.init.gradle (Groovy) instead.
//
// The Kotlin DSL init script could not express the export task reliably
// (`doLast`/`Copy` typing inside `gradle.projectsEvaluated`), and init scripts are
// evaluated for every included build as well - see the `g.parent != null` guard in
// the Groovy version. This file is kept only as a pointer.
//
//   cd third_party/ab-download-manager
//   ./gradlew :downloader:core:desktopJar \
//       --init-script ../../scripts/abdm-bridge.init.gradle
//
// or simply: ./scripts/build-abdm-bridge.sh
// ---------------------------------------------------------------------------
