import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.groovebox"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.groovebox"
        minSdk = 31
        targetSdk = 36
        // CI run number, so every installed build upgrades the previous one.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "0.4.0"

        ndk {
            // The Galaxy S25+ is the only v1 target.
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    signingConfigs {
        // Shared, committed key so builds from any machine or CI run install over each other.
        // Not a secret and not for the Play Store.
        create("dev") {
            storeFile = rootProject.file("keystore/dev.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("dev")
        }
        // The build the owner installs: optimised Kotlin/Compose and native code, so UI
        // jank does not distort latency measurements.
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("dev")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../engine/CMakeLists.txt")
        }
    }

    buildFeatures {
        compose = true
        prefab = true
        // BuildConfig.VERSION_CODE: the build number in the debug panel (build-20 fix 5).
        buildConfig = true
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.oboe)

    testImplementation(libs.junit)
}
