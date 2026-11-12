plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

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
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":engine"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    // Hosts Compose in an Activity (setContent, BackHandler, permission launchers); required by Compose.
    implementation(libs.activity.compose)
    implementation(libs.play.services.location)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
