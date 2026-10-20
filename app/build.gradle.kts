import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The version lives in version.properties, which publish-update.ps1 bumps for every release.
val versionProps = Properties().apply { file("version.properties").inputStream().use { load(it) } }

android {
    namespace = "dev.marck.jsyncdrive"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.marck.jsyncdrive"
        minSdk = 26
        targetSdk = 35
        versionCode = versionProps.getProperty("versionCode").toInt()
        versionName = versionProps.getProperty("versionName")
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    // The signing key lives in the project (keystore/debug.keystore, kept out of git): every build, on any
    // machine that has the file, signs with the same key, which is what lets installed apps accept updates.
    val projectKey = rootProject.file("keystore/debug.keystore")
    signingConfigs {
        if (projectKey.exists()) {
            create("project") {
                storeFile = projectKey
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            // R8 and shrunk resources: Compose is far smoother optimised than as a debug build.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Personal build: signed with the debug key so it installs without a keystore.
            signingConfig = signingConfigs.findByName("project") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        jniLibs {
            // The Go node ships as libjsyncnode.so and is executed as a program,
            // so it has to be extracted to the app's native library directory.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    // Installs the baseline profiles Compose and Material ship, so the first scrolls are not interpreted.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
