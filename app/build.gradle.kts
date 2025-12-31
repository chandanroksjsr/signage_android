plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20"
    id("com.google.devtools.ksp") version "2.2.20-2.0.3"   // <— add
}

android {
    namespace = "com.ebani.sinage"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ebani.sinage"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    androidResources {
        noCompress += setOf("tflite")
    }

    packaging {
        // (usually not required, but helpful if you hit duplicate license files)
        resources {
            excludes += setOf(
                "META-INF/LICENSE*",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1"
            )
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
//        freeCompilerArgs.add("-Xjvm-default=all")

    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
    }
}
kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}
dependencies {
    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // Room (kapt)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.play.services.mlkit.face.detection)
    ksp(libs.androidx.room.compiler)

    // UI
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.google.material)

    // Networking: Retrofit + OkHttp + Kotlinx Serialization
    implementation(libs.retrofit)
    implementation(libs.converter.kotlinx.serialization) // keep
    implementation(libs.okhttp)
    implementation(libs.logging.interceptor)              // <— add

    implementation(libs.socket.io.client) // or 2.1.1
    implementation(libs.java.websocket) // transitively used

    // Moshi (JSON) — replace kotlinx-serialization pieces with these:
    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)
    ksp(libs.moshi.kotlin.codegen)
    implementation(libs.converter.moshi)


    // Kotlinx Serialization runtime (matches your plugin)
    implementation(libs.kotlinx.serialization.json)

    // WorkManager (background sync)
    implementation(libs.androidx.work.runtime.ktx)

    // Media / images
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.effect)
    implementation(libs.coil)
    implementation(libs.coil.gif)
    implementation(libs.coil.svg)
    // Lifecycle / Activity
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.ktx)

    // Optional but helpful for socket/sync lifecycle (foreground/background)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.nanohttpd)
// only if you need WebSocket support:
    implementation(libs.nanohttpd.websocket)
    implementation(libs.core)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    // Optional if you want to migrate DevicePrefs to DataStore
    // implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation(libs.gson)
    implementation(libs.circularprogressbar)
    // Logging
    implementation(libs.timber)

    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    // Optional accelerators:
     implementation("org.tensorflow:tensorflow-lite-gpu:2.14.0")
     implementation("org.tensorflow:tensorflow-lite-select-tf-ops:2.14.0")

    // --- CameraX (if not already in your project) ---
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // --- ML Kit Face Detection (for face boxes; optional but recommended) ---
//    implementation("com.google.mlkit:face-detection:17.1.0")

}

