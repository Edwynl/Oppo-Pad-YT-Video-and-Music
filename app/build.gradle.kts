import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val appVersionName = "1.7.4"

// Signing secrets live in keystore.properties (git-ignored); see
// keystore.properties.example. Without it, release builds are left unsigned.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val hasReleaseSigning = keystorePropertiesFile.exists()

android {
    namespace = "com.edwyn.padtube"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.edwyn.padtube"
        minSdk = 26
        targetSdk = 36
        versionCode = 21
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            val keystoreProperties = Properties().apply {
                keystorePropertiesFile.inputStream().use(::load)
            }
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
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

// A separate task: Copy skips itself (doFirst included) when the signed APK is
// absent, which would make a missing keystore look like a successful build.
val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    val signingConfigured = hasReleaseSigning
    doLast {
        if (!signingConfigured) {
            throw GradleException("keystore.properties is missing; see keystore.properties.example.")
        }
    }
}

tasks.register<Copy>("archivePadTubeRelease") {
    group = "build"
    description = "Build the signed release APK and archive it into release/ with a versioned filename."
    dependsOn(verifyReleaseSigning, "assembleRelease")
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    into(rootProject.layout.projectDirectory.dir("release"))
    rename("app-release.apk", "PadTube-$appVersionName.apk")
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
