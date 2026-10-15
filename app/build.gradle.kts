plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.boksremote"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.boksremote"
        minSdk = 33 // the value-carrying BluetoothGatt callbacks and write APIs used throughout are API 33+
        targetSdk = 36
        versionCode = 4
        versionName = "0.4.0"
        // Stand-in device names for README screenshots, e.g. "<team ID>=Backyard Mix;<team ID>=Stage light",
        // so the shots don't show anyone's team ID. Set by tools/screenshots.py; empty otherwise.
        val screenshotNames = (findProperty("boksremote.screenshotNames") as String?).orEmpty()
        buildConfigField("String", "SCREENSHOT_NAMES", "\"${screenshotNames.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }

    // Published builds are signed with a fixed key so they update in place. It comes from the
    // environment (CI secrets); without it the release falls back to the local debug key.
    val releaseKeystore = System.getenv("BOKS_KEYSTORE_FILE")?.let(::file)?.takeIf { it.exists() }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("BOKS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("BOKS_KEY_ALIAS")
                keyPassword = System.getenv("BOKS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // android.util.Log in the code under test logs nothing instead of throwing.
    testOptions { unitTests.isReturnDefaultValues = true }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
