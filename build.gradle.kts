plugins {
    alias(wip.plugins.kotlinMultiplatform) apply false
    alias(wip.plugins.kotlinSerialization) apply false
    alias(libs.plugins.sborkaKmp) apply false
    alias(libs.plugins.sborkaLint) apply false
    alias(libs.plugins.koreBuild) apply false
}

// Deliberately empty otherwise. The group, the version and the lint wiring are `gradle.properties`
// keys applied per module by the shared conventions; the repositories are in `settings.gradle.kts`,
// where a single declaration means every module resolves a coordinate from the same place.
