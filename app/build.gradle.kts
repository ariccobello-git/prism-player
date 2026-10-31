plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Shown on the opening screen so the installed build can be matched to a release. CI passes the release tag
// (or the short commit) with -PprismVersion; a local build shows "dev".
// Each CI build gets a higher version code, so a newer APK installs over the previous one.
val prismCode: Int = (project.findProperty("prismCode") as String?)?.toIntOrNull() ?: 1
val prismVersion: String = (project.findProperty("prismVersion") as String?)?.takeIf { it.isNotBlank() } ?: "dev"

android {
    namespace = "dev.red74.prismplayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.red74.prismplayer"
        // API 23 is the floor for AES-GCM keys in the Android Keystore.
        minSdk = 23
        targetSdk = 35
        versionCode = prismCode
        versionName = "0.1.0"
        buildConfigField("String", "BUILD_LABEL", "\"$prismVersion\"")
    }

    // The public test key signs CI builds. Published releases are re-signed in CI with Prism's own key
    // (signing/README.md), so this public key alone can no longer update the app on the TV.
    signingConfigs {
        getByName("debug") {
            storeFile = file("prism-debug.p12")
            storeType = "pkcs12"
            storePassword = "prismdebug"
            keyAlias = "prism"
            keyPassword = "prismdebug"
        }
    }

    buildTypes {
        // The published test builds are debug builds: make them non-debuggable all the same, so no one with a
        // cable or adb can attach a debugger or `run-as` into the app's private files and read the lists.
        // A non-debuggable app is also compiled ahead of time by Android, so it runs faster on the stick.
        debug {
            isDebuggable = false
            // The published APK is this build: R8 removes the code and resources nobody calls (unused parts of
            // Compose, Media3, OkHttp...) and optimises the rest, so the APK is smaller, starts faster and uses
            // less memory on the stick. Unit tests are not affected (they run on the unshrunk classes).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // Lint runs in CI and its report is uploaded; findings do not block the debug APK.
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.media3.common.util.UnstableApi",
            "-opt-in=androidx.tv.material3.ExperimentalTvMaterial3Api",
        )
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.tv.material)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.ui)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.zxing.core)
    implementation(libs.coil.compose)
    implementation(libs.nanohttpd)

    testImplementation(libs.junit)
    // A real XmlPullParser for the guide tests (on the device the platform provides one).
    testImplementation(libs.kxml2)
}
