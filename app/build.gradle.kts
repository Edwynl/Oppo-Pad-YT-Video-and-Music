import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val appVersionName = "1.7.0"

android {
    namespace = "com.edwyn.padtube"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.edwyn.padtube"
        minSdk = 26
        targetSdk = 36
        versionCode = 17
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    lint {
        // These versions are intentionally pinned to the Android 36 compatible line.
        disable += "GradleDependency"
    }
}

tasks.register<Copy>("archivePadTubeDebug") {
    group = "build"
    description = "Build the debug APK and archive it into release/ with a versioned filename."
    dependsOn("assembleDebug")
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(rootProject.layout.projectDirectory.dir("release"))
    rename("app-debug.apk", "PadTube-$appVersionName.apk")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    // Latest stable line that still targets Android API 36 / AGP 8.9.
    implementation("androidx.activity:activity-compose:1.12.3")
    implementation("androidx.browser:browser:1.9.0")
    implementation("androidx.core:core-ktx:1.17.0")
    // Lets us strip the X-Requested-With header so Google's WebView sign-in
    // detection does not reject in-app login.
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    // MediaSessionCompat for the PadMusic (YouTube Music) lock-screen controls.
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
