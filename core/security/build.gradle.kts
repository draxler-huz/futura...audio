//
// Security module: vault, decoy PIN, biometric gate and the unlock proof.
//
// Kept separate from :app so that `internal` is a hard boundary. Only the
// public API (VaultUnlockProof, VaultUnlockLedger, VaultManager,
// BiometricWakeUpLock) is visible to the app. Everything else, including
// VaultCrypto and DecoyPinStore, cannot be referenced from :app.
//
// Versions come from gradle/libs.versions.toml.

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.futura.core.security"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.biometric)
}
