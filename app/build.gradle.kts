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

    // Pinned because shellserver/build.sh reads d8 out of build-tools/36.0.0 by
    // path, and until now that worked only because 36.0.0 happens to be AGP's
    // default. Nothing declared it, so nothing would have caught AGP moving on:
    // the daemon build would fail on a machine that had never been told which
    // build-tools to install - F-Droid's, for one, which installs only what the
    // Gradle build asks for plus what its metadata names. Stating it here makes
    // AGP install the version build.sh expects. Keep the two in step; build.sh
    // takes BUILD_TOOLS from the environment, so it can be told otherwise.
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "app.ipick.voicemail"
        minSdk = 31
        targetSdk = 36

        // From gradle.properties, which is the one place the version is
        // written - see the comment there. property() rather than
        // findProperty() so a missing key fails with its name instead of
        // shipping a fallback: a fallback here once meant every release
        // would have been versionCode 1.
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
            // R8, on. Measured on 0.4 before this: 38 MB on disk, of which
            // 28.5 MB was dex and 0.2 MB of that was this app - the rest was
            // libraries shipped whole, and 12.8 MB was nothing but the names
            // of their classes, methods and fields. proguard-rules.pro says
            // what has to survive and why. Debug stays unshrunk: its
            // diagnostic receivers are reached by name from tools/, and a
            // build that takes a minute longer is a measurement that does
            // not get made.
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
            // Coverage of the unit and screen tests, only when asked for:
            // ./gradlew createDebugUnitTestCoverageReport -Pcoverage. A report
            // to read, not a gate, and JaCoCo slows every test it watches -
            // so a plain build, and the release build F-Droid reproduces,
            // carry none of it.
            enableUnitTestCoverage = providers.gradleProperty("coverage").isPresent
        }
        // The debug build through R8 with the release's rules, for the
        // emulator checks (tools/emulator_checks.py installs this one).
        // Until 0.25.3 they installed debug, which is not shrunk, so nothing
        // ever ran what R8 makes: a library rule that let R8 remove a
        // constructor WorkManager reflects on failed every one-time work in
        // every release from 0.5 to 0.25.2, and no check could see it (issue
        // #69). This keeps debug's diagnostic receiver, which the checks
        // drive, and debug's key.
        //
        // Not debuggable, unlike debug: AGP runs R8 in its debug mode for a
        // debuggable build, which removes what the same rules let go but
        // optimises far less. Measured 2026-10-31: debuggable, R8 removed
        // 49,827 items to the release's 70,575 and left 35 classes without a
        // constructor to the release's 725. The checks read the app's files
        // as root instead of with run-as.
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

    // Playing a recording with the screen off: a media session in a service,
    // Android's documented route, under the system's media controls. The
    // session alone, not ExoPlayer: recordings still play with MediaPlayer
    // (capture/SessionPlayer.kt). Measured on the release APK, 2026-10-23:
    // 9.19 MB -> 9.75 MB with it; ExoPlayer as well would have made 10.73.
    implementation("androidx.media3:media3-session:1.11.1")

    // Keeps the shell-side recorder alive without a permanent foreground
    // service: a periodic job revives it when it is down and Wi-Fi is around.
    //
    // 2.10 is the floor. 2.9.1's keep rule for input mergers had no member
    // clause, so R8 kept OverwritingInputMerger and removed the constructor
    // WorkManager reflects on for every one-time work: in every shrunk
    // release from 0.5 to 0.25.2, each kick of the keep-alive and every USB
    // debugging watch ended FAILED without running (issue #69). 2.10 ships
    // the rule with the constructor. 2.12 also brings Room 2.7, whose own
    // rule keeps WorkDatabase_Impl's constructor the same way.
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
    inputs.file(File(shellServerDir, "build.sh"))
    outputs.file(shellServerJar)
}

val copyShellServer = tasks.register<Copy>("copyShellServer") {
    dependsOn(buildShellServer)
    from(shellServerJar)
    into(layout.projectDirectory.dir("src/main/assets"))
}

tasks.named("preBuild") { dependsOn(copyShellServer) }
