import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.artin.pinch"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.artin.pinch"
        minSdk = 29
        targetSdk = 36
        versionCode = 5
        versionName = "1.0.4"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    // Only ship the languages the app speaks.
    androidResources { localeFilters += listOf("en", "fa") }

    // Release key lives in Source Code/android/ (gitignored) and is never regenerated,
    // so every build installs straight over the previous one.
    val keyProps = Properties().apply {
        rootProject.file("android/key.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    val hasReleaseKey = keyProps.getProperty("storeFile") != null
    signingConfigs {
        if (hasReleaseKey) create("release") {
            storeFile = rootProject.file("android/" + keyProps.getProperty("storeFile"))
            storePassword = keyProps.getProperty("storePassword")
            keyAlias = keyProps.getProperty("keyAlias")
            keyPassword = keyProps.getProperty("keyPassword")
            storeType = "PKCS12"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
            vcsInfo { include = false }
        }
        debug {
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    dependenciesInfo { includeInApk = false; includeInBundle = false }
    packaging {
        // Compressed dex and native code: a much smaller APK to download and share.
        dex { useLegacyPackaging = true }
        jniLibs { useLegacyPackaging = true }
        resources {
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.version", "/META-INF/**/LICENSE*", "/META-INF/*.kotlin_module",
                "/META-INF/androidx/**", "/kotlin/**", "/DebugProbesKt.bin", "/kotlin-tooling-metadata.json",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll("-Xno-param-assertions", "-Xno-call-assertions", "-Xno-receiver-assertions")
    }
}

dependencies {
    // Compose without Material: every control is drawn here, which keeps the APK small.
    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.core:core:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
}
