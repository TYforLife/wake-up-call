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
        // Test builds only target 64-bit ARM phones (the owner's Samsung): keeps MapLibre's native code ~9 MB, not ~35.
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
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("debug") }
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
