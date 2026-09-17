plugins { kotlin("multiplatform") version "2.4.10" }

kotlin {
    // EXPLICIT, because `getByName("nativeMain")` below runs before the template would have created
    // it and fails the configuration with a name-not-found that says nothing about hierarchies.
    applyDefaultHierarchyTemplate()

    // The second platform, because one host is one host. macOS has to be built on macOS, so this
    // target is the reason the reproducer is a Kotlin Multiplatform build rather than a script.
    macosArm64 {
        binaries.executable { entryPoint = "main" }
    }
    linuxX64 {
        binaries.executable {
            entryPoint = "main"
            // MATCH WHAT THE SERVICE SHIPS, on request. The default here is Kotlin/Native's paged
            // allocator and xyk ships `pagedAllocator=false` (B-28), so a reproducer left on the
            // default is answering about a binary nobody runs. `-Pallocator=paged-off` switches it.
            when (project.findProperty("allocator")) {
                // Still the runtime's own allocator, only without paging.
                "paged-off" -> freeCompilerArgs += listOf("-Xbinary=pagedAllocator=false")
                // The one that is NOT `CustomAllocator` at all: the system allocator. The profile
                // puts every leaked byte under `CustomAllocator::CreateObject` and `CreateArray`,
                // so this is the arm that says whether that attribution is real.
                "std" -> freeCompilerArgs += listOf("-Xallocator=std")
                else -> Unit
            }
        }
    }
    sourceSets {
        getByName("nativeMain") {
            dependencies {
                implementation(ktorLibs.client.curl)
                // The control. CIO speaks plain HTTP on native and nothing else, which is
                // enough for this loop and is exactly what makes it a control: if the growth
                // is in ktor's client core rather than in the curl engine, it appears here too.
                implementation(ktorLibs.client.cio)
            }
        }
    }
}
