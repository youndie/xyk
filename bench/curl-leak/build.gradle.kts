plugins { kotlin("multiplatform") version "2.4.10" }

kotlin {
    linuxX64 {
        binaries.executable { entryPoint = "main" }
    }
    sourceSets {
        getByName("linuxX64Main") {
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
