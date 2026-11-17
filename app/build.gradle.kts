import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Signing secrets stay out of the repo: they live in local.properties (gitignored).
// Must be declared after the plugins block, which has to be the first statement.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.zhiwo.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zhiwo.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 67
        versionName = "1.3.7"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("release") {
            storeFile = File(rootProject.rootDir, "cyrecho-release.jks")
            storePassword = localProps.getProperty("keystore.password").orEmpty()
            keyAlias = localProps.getProperty("keystore.alias") ?: "cyrecho"
            keyPassword = localProps.getProperty("keystore.keyPassword")
                ?: localProps.getProperty("keystore.password").orEmpty()
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
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
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("ru.noties:jlatexmath-android:0.2.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
