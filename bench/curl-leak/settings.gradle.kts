// A STANDALONE BUILD ON PURPOSE. This is the minimal reproducer for B-30, and a reproducer that
// needs the service around it reproduces the service. It is excluded from xyk's own build for the
// same reason it is worth having: whatever it shows is true of ktor-client-curl and nothing else
// in this repository.
rootProject.name = "curl-leak"

dependencyResolutionManagement {
    repositories { mavenCentral() }
    versionCatalogs {
        create("ktorLibs") { from("io.ktor:ktor-version-catalog:3.5.2") }
    }
}
