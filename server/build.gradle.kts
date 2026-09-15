plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.sborkaKmp)
    alias(libs.plugins.sborkaLint)
    alias(libs.plugins.pluginSerialization)
    // Generates `KoreBuildIdentity` — the version, the commit and the build time, as compiled-in
    // source. Kotlin/Native has neither resources nor a manifest, so `/version` has no other way to
    // know what it is serving. `commit` reads `unknown` wherever the build context has no `.git`,
    // which on this project is the mutagen replica and the docker context both.
    alias(libs.plugins.koreBuild)
}

// NOT A LIBRARY: nothing resolves this module, so there is no consumer for a spelled-out public API.
kotlin {
    explicitApi = null
}

// TWO BUILD-TIME SWITCHES, AND BOTH EXIST TO ANSWER B-05 RATHER THAN TO BE CONFIGURATION.
//
// The brief carries a kill condition: if the curl dependency makes it impossible to link even the
// incoming half statically, that is a result. Answering it means four builds of the same source —
// engine present or absent, linked dynamically or statically — so both are properties rather than
// edits, and the four runs are `dev/static-probe.sh`.
//
// `xyk.httpClient` also decides which `actual fun httpEngineMarker()` is compiled in, because a
// dependency nothing calls is a dependency `--gc-sections` removes: without a reachable call site
// the "with curl" arm would measure a binary that contains no curl.
val withHttpClient = (project.findProperty("xyk.httpClient") as String?)?.toBoolean() ?: false
val staticLinkRequested = (project.findProperty("xyk.staticLink") as String?)?.toBoolean() ?: false

// WHICH ALLOCATOR THE BINARY IS LINKED WITH, as a property rather than an edit, because B-21 has to
// compare four arms and a measurement whose variants are produced by editing a file is a measurement
// nobody can repeat. `fixed16` is what ships; the others exist to be measured against it.
//
//   -Pxyk.allocator=fixed16   binaryOption("fixedBlockPageSize", "16")   (default here)
//   -Pxyk.allocator=default   the Kotlin/Native default, 256 KiB pages
//   -Pxyk.allocator=std       -Xallocator=std
//
// The fourth arm, `MALLOC_ARENA_MAX=2`, is an environment variable on the runtime image and needs no
// build of its own.
val allocator = (project.findProperty("xyk.allocator") as String?) ?: "fixed16"

// Printed at configuration time, and it stays: the variant a binary was linked with is the one fact
// a size measurement of it cannot be read without, and it is not visible in the artefact.
logger.lifecycle("xyk build: httpClient=$withHttpClient staticLink=$staticLinkRequested allocator=$allocator")

kotlin {
    jvm()

    // ONE NATIVE TARGET, NAMED BY THE HOST, and the name is `native` on purpose: the source set is
    // then `nativeMain` on every machine, so `expect/actual` and the Dockerfile's COPY path do not
    // change when the build moves between the Mac and the Linux box. The shipping target is
    // linuxX64; macosArm64 exists so the suite runs where the editor is.
    val hostOs = System.getProperty("os.name")
    val arch = System.getProperty("os.arch")

    // chronik's native variant is `linuxX64` ONLY. Naming that here, once, keeps the reason next to
    // the consequence: on the Mac the native suite compiles without the delivery half rather than
    // failing to resolve, and the loss is stated rather than discovered.
    val chronikOnThisHost = hostOs == "Linux" && (arch == "x86_64" || arch == "amd64")
    val nativeTarget =
        when {
            hostOs == "Mac OS X" && arch == "aarch64" -> macosArm64("native")
            hostOs == "Linux" && (arch == "x86_64" || arch == "amd64") -> linuxX64("native")
            hostOs == "Linux" && arch == "aarch64" -> linuxArm64("native")
            else -> throw GradleException("Host $hostOs/$arch is not one this service is built on.")
        }

    // STATIC ONLY ON A LINUX HOST, and only when asked. The recipe pins five `konan.properties`
    // keys, which JetBrains reserve the right to change in any patch release — it breaks loudly, as
    // a link error, but only where it is used, which is why it is a flag and not the default until
    // B-18 decides the image.
    //
    // `linkerKonanFlags` below is THE STOCK VALUE WITH `-Bdynamic` REMOVED and nothing else. It has
    // to be read out of `konan.properties` rather than rewritten from meaning: its value continues
    // onto a second line, and a version written from scratch loses `--gc-sections` and costs
    // 316 488 bytes for nothing. That mistake has been made once already, elsewhere.
    val staticLinux =
        staticLinkRequested &&
            nativeTarget.name == "native" &&
            System.getProperty("os.name") == "Linux"

    nativeTarget.apply {
        binaries {
            executable {
                entryPoint = "io.github.youndie.xyk.main"

                // 16 KiB PAGES INSTEAD OF THE DEFAULT 256, SET NOW RATHER THAN WHEN THINGS START
                // DYING. Kotlin/Native's allocator keeps a page per size class PER THREAD for as
                // long as that thread lives, so resident memory follows the thread count rather than
                // the live heap, and no GC setting bounds it — these are pages, not objects.
                // Measured on katcher under `--memory=192m --cpus=1`: the default is killed 0/8
                // with peaks of 252–329 MB, this survives 8/8 at 47–62 MB.
                //
                // It is NOT evidence about this service. B-21 measures four arms on this binary,
                // with a positive control, because the arm that fits 64 MiB on a service with no
                // database is the arm that was worse on a service with SQLite on the request path.
                // Whatever that measurement says replaces this line and quotes its own numbers.
                when (allocator) {
                    "fixed16" -> binaryOption("fixedBlockPageSize", "16")
                    "std" -> freeCompilerArgs += "-Xallocator=std"
                    "default" -> Unit
                    else -> throw GradleException("xyk.allocator must be fixed16, std or default")
                }

                if (staticLinux) {
                    linkerOpts("-static", "--no-dynamic-linker", "-L/usr/lib/x86_64-linux-gnu")
                    // The one thing the curl engine needs from the system: its klib carries
                    // libcurl.a, libnghttp2.a, libssl.a and libcrypto.a inside itself, and asks for
                    // zlib. Harmless when the engine is absent.
                    if (withHttpClient) linkerOpts("-lz")
                    freeCompilerArgs +=
                        "-Xoverride-konan-properties=" +
                        "targetSysRoot.linux_x64=/;" +
                        "crtFilesLocation.linux_x64=usr/lib/x86_64-linux-gnu;" +
                        "libGcc.linux_x64=usr/lib/gcc/x86_64-linux-gnu/13;" +
                        "linkerGccFlags=-lgcc -lgcc_eh -lc;" +
                        "linkerKonanFlags.linux_x64=-Bstatic -lstdc++ -ldl -lm -lpthread " +
                        "--defsym __cxa_demangle=Konan_cxa_demangle --gc-sections"
                }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)

            implementation(libs.kotlincrypto.hash.sha2)
            implementation(libs.kotlincrypto.hmac.sha2)
            implementation(libs.kotlincrypto.random)

            implementation(libs.kore.core)
            implementation(libs.kore.ktor)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.okio)
            implementation(libs.sqlx4k.sqlite)

            implementation(ktorLibs.server.cio)
            implementation(ktorLibs.server.core)
            implementation(ktorLibs.server.contentNegotiation)
            implementation(ktorLibs.server.resources)
            implementation(ktorLibs.server.statusPages)
            implementation(ktorLibs.serialization.kotlinx.json)
        }
        // The engine variant is a source directory rather than an `if` in the code: the two actuals
        // have different dependencies, and only one of them may reach the linker.
        getByName("nativeMain") {
            kotlin.srcDir(
                if (withHttpClient) "src/variants/with-curl/kotlin" else "src/variants/no-curl/kotlin",
            )
            if (withHttpClient) {
                dependencies {
                    implementation(ktorLibs.client.curl)
                }
            }
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.koin.test)
        }

        // CHRONIK IS A TEST DEPENDENCY HERE AND NOTHING MORE, until the delivery half lands.
        // It publishes `jvm` and `linuxX64` and no `macosArm64`, so where it is declared decides
        // which hosts can still build this repository at all.
        jvmTest.dependencies {
            // The jvm variant resolves on every host, which is why the check that xyk's copy of
            // chronik's DDL still matches chronik's own lives in the JVM suite: it is the one
            // place that guard can run on the Mac as well as on the Linux box.
            implementation(libs.chronik.core)
            implementation(libs.chronik.sqlx4k.sqlite)
        }

        // The conformance kit runs against the driver that SHIPS, which is the Rust one — sqlx4k is
        // two drivers, and a kit green on Xerial says nothing about the binary in the image
        // (research §1.11). So it is declared on the native suite, and only where chronik has a
        // variant for it.
        if (chronikOnThisHost) {
            getByName("nativeTest") {
                kotlin.srcDir("src/variants/with-chronik/kotlin")
                dependencies {
                    implementation(libs.chronik.core)
                    implementation(libs.chronik.sqlx4k.sqlite)
                    implementation(libs.chronik.conformance)
                }
            }
        }
    }
}
