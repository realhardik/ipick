pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()

        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "IPick"
include(":app")

// The shell-side daemon's plain-Java parts, under test on the JVM. The daemon
// itself is built by shellserver/build.sh; see shellserver/tests.
include(":daemon-tests")
project(":daemon-tests").projectDir = file("shellserver/tests")

