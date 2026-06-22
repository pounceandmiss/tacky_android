import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.example.tackyapk"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.tackyapk"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    // A committed debug keystore so every build (host or container) signs with the
    // same key - otherwise the Docker build mints a fresh key each run and you
    // can't update-install over the previous APK. Debug keys are not secret.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    // The daemon binary is not built here: cross-build it in tacky_t (`make
    // android`) and copy the .so pair into app/src/main/jniLibs/arm64-v8a/ (the
    // default jniLibs location, gitignored). See README.
    //
    // The daemon is exec'd as a subprocess from nativeLibraryDir, so the .so must
    // be extracted to a real on-disk path: useLegacyPackaging = true is the modern
    // equivalent of extractNativeLibs="true". keepDebugSymbols skips AGP's native
    // strip pass (the libs are already final), so this project needs no NDK.
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/*.so"
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // TackyClient references android.util.Log; let the android.jar stubs return
    // defaults (no-op) under plain JVM unit tests instead of throwing.
    // includeAndroidResources lets Robolectric-backed Compose UI tests render.
    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

// Robolectric fetches its android-all runtime into a dir derived from user.home,
// which is "?" for the build container's passwd-less UID (download lock fails).
// Point it at the persisted Gradle home so it's writable and cached across runs.
tasks.withType<Test>().configureEach {
    systemProperty("user.home", gradle.gradleUserHomeDir.absolutePath)
}

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.2")

    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // App icons are local vector drawables in res/drawable (see ic_*.xml), so neither
    // material-icons-core nor the ~11k-class material-icons-extended is pulled in.
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    // Headless Compose UI tests on the JVM (the analog of the Tk wish tests):
    // Robolectric renders the composables, ui-test drives/queries the tree.
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation(platform("androidx.compose:compose-bom:2024.09.00"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
