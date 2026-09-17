enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "xyk"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        // Written out by hand, and it has to be: `pluginManagement` is evaluated before any settings
        // plugin is applied — including the sborka one, which is fetched through it. kore lives here
        // too and is not on Central.
        maven("https://reposilite.kotlin.website/snapshots") {
            name = "wip-snapshots"
            content {
                includeGroupByRegex("io\\.github\\.youndie.*")
            }
        }
    }
}

plugins {
    // Lets Gradle fetch the JDK the toolchain asks for instead of demanding it be installed first.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    // Repositories with content filters, the shared catalog, the shared lint configuration, and the
    // check that this repository's `.editorconfig` is the one the rest of the portfolio uses.
    id("io.github.youndie.sborka.settings") version "0.4.0.86"
}

dependencyResolutionManagement {
    versionCatalogs {
        // Ktor's own catalog: one version for the engine, the client and every plugin, so a bump is
        // one number rather than eleven. It is also how the OpenSSL inside `ktor-client-curl` gets
        // patched — the version is inside the klib and nothing in the image shows it.
        create("ktorLibs") {
            from("io.ktor:ktor-version-catalog:3.6.0")
        }
    }
}

include(":server")
