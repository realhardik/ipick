import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.ipick.voicemail"

    // compileSdk and targetSdk are deliberately different, and so is minSdk.
    // Three separate decisions that must not be collapsed:
    //
    //   compileSdk 37  - what we compile and lint against. Newest installed.
    //   targetSdk  36  - which platform behaviour changes we opt into. The
    //                    device under test reports ro.build.version.sdk 36, so
    //                    36 is the newest target any measurement here covers.
    //   minSdk     31  - the run-time floor. Android 12 is where the shell-side
    //                    capture approach starts working at all.
    //
    // A FOURTH "36" exists elsewhere and is not this one: the shell-side
    // capture binary must be compiled against ANDROID_PLATFORM=36 because it
    // links @hide framework internals and has to match the platform the device
    // actually runs. That is a property of the binary, not of the app, and the
    // two happening to share the number 36 today is a coincidence.
    compileSdk = 37

    // Keep this version in sync with the d8 invocation in shellserver/build.sh.
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "app.ipick.voicemail"
        minSdk = 31
        targetSdk = 36

        versionCode = (property("ipickVersionCode") as String).toInt()
        versionName = property("ipickVersionName") as String
    }

    // Release signing comes from the environment, never from the repo.
    val keystorePath: String? = System.getenv("IPICK_KEYSTORE")
    val signingReady = !keystorePath.isNullOrBlank() && file(keystorePath).exists()

    signingConfigs {
        if (signingReady) create("release") {
            storeFile = file(keystorePath!!)
            storePassword = System.getenv("IPICK_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("IPICK_KEY_ALIAS") ?: "ipick"
            keyPassword = System.getenv("IPICK_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // With no keystore configured the APK is left UNSIGNED rather than
            // falling back to the debug key: a debug-signed "release" installs
            // once and can never be upgraded by a properly signed one.
            if (signingReady) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isDebuggable = providers.gradleProperty("developmentDebug").orNull == "true"
            // Coverage of the unit and screen tests, only when asked for:
            // ./gradlew createDebugUnitTestCoverageReport -Pcoverage. A report
            // to read, not a gate, and JaCoCo slows every test it watches -
            // so a plain build, and the release build F-Droid reproduces,
            // carry none of it.
            enableUnitTestCoverage = providers.gradleProperty("coverage").isPresent
        }
        // Use release optimizations with the local signing key for device checks.
        create("shrunk") {
            initWith(getByName("debug"))
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            matchingFallbacks += "debug"
        }
    }

    // The shrunk build is the debug build: its source set is debug's folder,
    // every kind in it. Named kind by kind, java alone left out the .kt files
    // there and the shrunk build crashed at launch on the manifest's
    // DemoProvider - and assets, or anything else added later, would have
    // gone missing the same way.
    sourceSets.named("shrunk") { setRoot("src/debug") }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    // Lint as a gate, not noise. Until 0.21 it ran on every build with its
    // warnings ignored - 108 of them, nobody reading. Now every warning is an
    // error, and there is no baseline of old ones left to hide behind: all
    // were fixed, or are allowed where they stand with the reason beside them
    // (lint.xml, tools:ignore, @SuppressLint). So any warning fails the
    // build, locally and in CI.
    lint {
        lintConfig = file("lint.xml")
        warningsAsErrors = true
        abortOnError = true
    }

    // No Google Play dependency metadata. By default AGP writes an encrypted
    // list of the app's dependencies into the APK Signing Block at signing
    // time, for the Play Console to read - and nobody else can, which is why
    // F-Droid's scanner refuses an APK that carries it. Found by their check
    // on the 0.5 release: "found extra signing block 'Dependency metadata'",
    // 5,977 bytes, inside the one part of the APK the reproducibility
    // comparison skips by design. There is no Play listing to inform.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }


    packaging {
        jniLibs {
            keepDebugSymbols += "**/*.so"

            excludes += listOf("**/x86/**", "**/x86_64/**")
        }
        resources {

            excludes += listOf(
                "org/bouncycastle/pqc/**",
                "org/bouncycastle/x509/CertPathReviewerMessages*",
                "org/bouncycastle/pkix/CertPathReviewerMessages*",
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

}

dependencies {
    // The embedded ADB client. This is the whole transport: SPAKE2 pairing
    // against the phone's own Wireless debugging, then a TLS connection to
    // loopback and a shell-privileged stream. Dual licensed GPL-3.0-or-later
    // OR Apache-2.0; we take it under Apache-2.0. See NOTICE.md.
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")


    implementation("org.bouncycastle:bcprov-jdk15to18:1.81")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")


    implementation("org.conscrypt:conscrypt-android:2.7.0")

    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.material3:material3")
    // Play and Share glyphs for the recordings list. The -core set, not
    // -extended: extended is thousands of vectors for the two we use.
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.core:core-ktx:1.19.0")

    // Android media controls for background voicemail playback.
    implementation("androidx.media3:media3-session:1.11.1")

    // WorkManager schedules helper recovery and observes USB debugging changes.
    implementation("androidx.work:work-runtime-ktx:2.12.0")

    // Compose's own pitfalls, which Android Lint does not know: Slack's rules
    // for modifiers (passed on, in the right place), state hoisting, unstable
    // parameters and the like. Only lint loads it - the release build's
    // lintVital included, which reads the sources and writes nothing into the
    // APK, so the reproducible bytes are untouched.
    lintChecks("com.slack.lint.compose:compose-lint-checks:1.6.1")

    testImplementation("junit:junit:4.13.2")

}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}


val shellServerDir = rootProject.file("shellserver")
val shellServerJar = File(shellServerDir, "ipick-capture.jar")

val buildShellServer = tasks.register<Exec>("buildShellServer") {
    description = "Compiles the shell-side capture daemon to a classes.dex jar"
    workingDir = shellServerDir
    commandLine("./build.sh")
    inputs.dir(File(shellServerDir, "src"))
    inputs.file(file("src/main/java/app/ipick/voicemail/answering/GreetingCue.java"))
    inputs.file(File(shellServerDir, "build.sh"))
    outputs.file(shellServerJar)
}

val copyShellServer = tasks.register<Copy>("copyShellServer") {
    dependsOn(buildShellServer)
    from(shellServerJar)
    into(layout.projectDirectory.dir("src/main/assets"))
}

tasks.named("preBuild") { dependsOn(copyShellServer) }
