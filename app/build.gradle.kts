plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    // Screenshots of the screens on the JVM (Robolectric), reviewable from a phone (PLAN U0).
    alias(libs.plugins.roborazzi)
}

/** `-Pscreenshots`: also build and run the screenshot tests in src/screenshotTest (CI does). */
val screenshots = providers.gradleProperty("screenshots").isPresent

val runNumber = providers.environmentVariable("GITHUB_RUN_NUMBER").map { it.toInt() }.getOrElse(1)
val releaseKeystore: String? = providers.environmentVariable("RELEASE_KEYSTORE_FILE").orNull
val releaseKeyPassword: String? = providers.environmentVariable("RELEASE_KEYSTORE_PASSWORD").orNull
val gitSha: String = providers.environmentVariable("GIT_SHA").map { it.take(7) }.orElse(
    providers.exec {
        commandLine("git", "rev-parse", "--short=7", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.map { it.trim().ifEmpty { "dev" } }
).getOrElse("dev")

android {
    namespace = "app.nodstop"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.nodstop"
        minSdk = 31
        targetSdk = 36
        versionCode = runNumber
        versionName = "0.1.$runNumber"
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        buildConfigField("int", "BUILD_NUMBER", "$runNumber")
        // 64-bit ARM only (the owner's Samsung), in the debug APK and the Play build alike: keeps MapLibre's native code
        // ~9 MB, not ~35. A public release adds the other ABIs (DESIGN §8).
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        getByName("debug") {
            // Committed, debug-only key so every CI build installs over the last (see keystore/README.md).
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // The Play upload key (DESIGN §6): only ever in GitHub secrets. CI decodes it to a temporary file and passes its
        // path and password here; anywhere else the release build is unsigned.
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = releaseKeyPassword
                keyAlias = "upload"
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            // The debug APK installs next to the Play build: its own ID ("Nodstop dev"), so neither replaces the other.
            applicationIdSuffix = ".debug"
        }
        release { signingConfig = signingConfigs.findByName("release") }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
        unitTests.isIncludeAndroidResources = true
    }

    sourceSets {
        getByName("test") { if (screenshots) java.srcDir("src/screenshotTest/kotlin") }
    }

    // CLAUDE.md "Code quality": a new lint warning fails CI. The known ones are in lint-baseline.xml, each with its
    // reason in the PR that added the baseline; fix one and it drops out.
    lint {
        warningsAsErrors = true
        abortOnError = true
        baseline = file("lint-baseline.xml")
        disable += setOf(
            "LogNotTimber", // Timber isn't used: android.util.Log is the app's logger.
            "ChromeOsAbiSupport", // A phone app, built for arm64 only (see abiFilters).
            "UseKtx", // Style only, and explicit edit()…commit() keeps the must-be-on-disk writes visible.
            // Its answer depends on the newest SDK installed on the machine, not on this code: a runner image update
            // would turn CI red overnight. Raising targetSdk is its own change, tested on the phone.
            "OldTargetApi",
        )
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":brief"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    // Hosts Compose in an Activity (setContent, BackHandler, permission launchers); required by Compose.
    implementation(libs.activity.compose)
    implementation(libs.play.services.location)
    implementation(libs.kotlinx.serialization.json)
    // The map (pins, you or your bus): open-source, no account or API key, free OpenFreeMap tiles.
    implementation(libs.maplibre)
    // On-device Gemma for While You Slept (DESIGN §10, pre-approved in docs/PLAN.md).
    implementation(libs.litertlm)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    // Screenshot tests (PLAN U0), only with -Pscreenshots (CI): Robolectric's native renderer comes from a
    // host cloud sessions can't reach. Robolectric + Roborazzi are JUnit 4, run through the vintage engine.
    if (screenshots) {
        testImplementation(libs.junit4)
        testRuntimeOnly(libs.junit.vintage)
        testImplementation(libs.robolectric)
        testImplementation(libs.roborazzi)
        testImplementation(libs.roborazzi.compose)
        testImplementation(platform(libs.compose.bom))
        testImplementation(libs.compose.ui.test.junit4)
        debugImplementation(libs.compose.ui.test.manifest)
    }
    testRuntimeOnly(libs.junit.launcher)
}

// ArchitectureTest scans the whole repo (key files, manifests, workflows), not only the source sets. Declaring the repo as
// an input means a cached test result can never hide a file committed since.
tasks.withType<Test>().configureEach {
    inputs.files(
        rootProject.fileTree(rootDir) { exclude("**/build/**", ".gradle/**", ".git/**", ".kotlin/**", ".idea/**", "app/src/main/assets/gtfs/**") }
    ).withPropertyName("repoScannedByArchitectureTest").withPathSensitivity(PathSensitivity.RELATIVE)
}
