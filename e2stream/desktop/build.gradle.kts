plugins {
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.json:json:20240303")
    implementation("net.java.dev.jna:jna:5.17.0")
    implementation("net.java.dev.jna:jna-platform:5.17.0")
    implementation("uk.co.caprica:vlcj:4.8.4")
    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "pl.jardol.e2stream.desktop.MainKt"
        // Napisy z libVLC (opisy ścieżek dźwięku) są w UTF-8.
        jvmArgs += listOf("-Djna.encoding=UTF-8")

        buildTypes.release.proguard {
            isEnabled.set(false)
        }

        nativeDistributions {
            // jdk.net: TCP keep-alive klienta WinLIRC/LIRC (ExtendedSocketOptions).
            // jdk.localedata: polskie nazwy języków ścieżek dźwięku (Locale.getDisplayLanguage).
            // jdk.unsupported: vlcj rezerwuje bufory klatek przez sun.misc.Unsafe (bez niego VLC: „video format setup failure”)
            modules("jdk.net", "jdk.localedata", "jdk.unsupported")
            // VLC wewnętrzny: CI rozpakowuje VLC 3.x win64 do vlc-bundle/windows/vlc (bez katalogu — tylko VLC z systemu).
            project.file("vlc-bundle").takeIf { it.isDirectory }?.let { appResourcesRootDir.set(it) }
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe,
            )
            packageName = "E2Stream"
            packageVersion = "0.5.0"
        }
    }
}
