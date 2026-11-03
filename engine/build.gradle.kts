//
// Native C++17 / Oboe audio engine module for Futura Audio.
// Ships the JNI bridge classes (NativeEngine, NativePlayerBridge, DspBridge,
// MlBridge, NativeUsbAudio, NativeFlacScanner, NativeWaveform, etc.) together
// with the CMake-built libfutura_engine.so and its prebuilt third_party
// shared libraries (liboboe, libFLAC, libav*, libsamplerate, libSoundTouch,
// libusb1.0, libtensorflowlite_c, libonnxruntime).
//
// Every dependency and plugin version is resolved from
// gradle/libs.versions.toml (declared in root build.gradle.kts).

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.futura.engine"
    compileSdk = 34
    ndkVersion = "26.3.11579264"

    defaultConfig {
        minSdk = 26
        targetSdk = 34

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("proguard-rules.pro")

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf(
                    "-std=c++17",
                    "-fexceptions",
                    "-frtti",
                    "-O3",
                    "-ffast-math",
                    "-fvisibility=hidden",
                    "-fvisibility-inlines-hidden",
                    "-Wall",
                    "-Wextra",
                    "-Wno-unused-parameter"
                )
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            externalNativeBuild {
                cmake {
                    cppFlags += listOf("-O0", "-g", "-DDEBUG")
                    arguments += listOf("-DCMAKE_BUILD_TYPE=Debug")
                }
            }
        }
        release {
            // A library does not shrink its own classes: JNI-only classes such as
            // DspBridge and NativeEngine get removed and the app's R8 then fails.
            // Shrinking happens in the app module, guided by the consumer rules below.
            isMinifyEnabled = false
            consumerProguardFiles("proguard-rules.pro")
            externalNativeBuild {
                cmake {
                    cppFlags += listOf("-O3", "-DNDEBUG")
                    arguments += listOf("-DCMAKE_BUILD_TYPE=Release")
                }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
            keepDebugSymbols += setOf(
                "**/arm64-v8a/libfutura_engine.so",
                "**/armeabi-v7a/libfutura_engine.so",
                "**/x86_64/libfutura_engine.so"
            )
            pickFirsts += setOf(
                "**/libc++_shared.so",
                "**/liboboe.so",
                "**/libFLAC.so",
                "**/libavcodec.so",
                "**/libavformat.so",
                "**/libavutil.so",
                "**/libswresample.so",
                "**/libsamplerate.so",
                "**/libSoundTouch.so",
                "**/libusb1.0.so",
                "**/libtensorflowlite_c.so",
                "**/libonnxruntime.so"
            )
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE",
                "/META-INF/LICENSE.txt",
                "/META-INF/NOTICE",
                "/META-INF/NOTICE.txt",
                "/META-INF/INDEX.LIST",
                "META-INF/*.kotlin_module"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-Xjvm-default=all",
            "-opt-in=kotlin.RequiresOptIn"
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
