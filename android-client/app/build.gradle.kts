// app/build.gradle.kts — Afet Mesh Android uygulaması
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.protobuf)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "org.afet.mesh"
    compileSdk = 36  // Android 16

    defaultConfig {
        applicationId = "org.afet.mesh"
        minSdk = 26              // Android 8.0 Oreo — Türkiye'deki cihazların %96'sı
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-alpha"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isDebuggable = true
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }
}

// Protobuf kod üretimi — Java Lite + Kotlin DSL uzantıları
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.31.1"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") { option("lite") }
                create("kotlin") { option("lite") }
            }
        }
    }
}

dependencies {
    // ── Android Core ──
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel)

    // ── Jetpack Compose (AMOLED Pure Black SOS Arayüzü) ──
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.activity)
    implementation(libs.compose.preview)
    debugImplementation(libs.compose.tooling)

    // ── Room Database (Gecikmeye Dayanıklı Depolama) ──
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // ── Hilt (Dependency Injection) ──
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // ── Google Nearby Connections (BLE + Wi-Fi Direct P2P_CLUSTER) ──
    implementation(libs.nearby.connections)

    // ── Protobuf (İkili Paket Serileştirme) ──
    implementation(libs.protobuf.kotlin)
    implementation(libs.protobuf.java)

    // ── Coroutines ──
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play)
}
