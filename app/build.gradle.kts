// SPDX-License-Identifier: AGPL-3.0-only

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The nanopb generator needs a Python with the protobuf module. Use the project venv when present.
val venvPython = rootProject.file(".venv/bin/python")

// OpenSSL's makefiles break on spaces in paths, so native build output lives outside the project folder.
val nativeBuildDir = File(System.getProperty("user.home"), ".cache/farsofa/cxx")

android {
    namespace = "app.farsofa.vr"
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "app.farsofa.vr"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                targets("farsofa")
                arguments(
                    "-DCHIAKI_ENABLE_TESTS=OFF",
                    "-DCHIAKI_ENABLE_CLI=OFF",
                    "-DCHIAKI_ENABLE_GUI=OFF",
                    "-DCHIAKI_ENABLE_SETSU=OFF",
                    "-DCHIAKI_ENABLE_ANDROID=ON",
                    "-DCHIAKI_ENABLE_FFMPEG_DECODER=OFF",
                    "-DCHIAKI_ENABLE_PI_DECODER=OFF",
                    "-DCHIAKI_LIB_ENABLE_OPUS=OFF",
                    "-DCHIAKI_LIB_OPENSSL_EXTERNAL_PROJECT=ON",
                    "-DCHIAKI_LIB_JSONC_EXTERNAL_PROJECT=ON",
                    "-DCHIAKI_LIB_MINIUPNPC_EXTERNAL_PROJECT=ON",
                    "-DCHIAKI_ENABLE_STEAM_SHORTCUT=OFF",
                    "-DCHIAKI_ENABLE_STEAMDECK_NATIVE=OFF",
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
                )
                if (venvPython.exists()) {
                    arguments("-DPYTHON_EXECUTABLE=${venvPython.absolutePath}")
                }
            }
        }
    }

    buildFeatures {
        compose = true
        prefab = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
            buildStagingDirectory = nativeBuildDir
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.oboe:oboe:1.10.0")
    implementation("org.khronos.openxr:openxr_loader_for_android:1.1.65")

    testImplementation("junit:junit:4.13.2")
}
