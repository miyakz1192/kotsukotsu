plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.kotsukotsu.kuku"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.kotsukotsu.kuku"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.0.5"
        buildConfigField("String", "GAME_TITLE_ID", "\"kuku-challenge\"")
        // 初回登録画面に最初から入っているサーバのアドレス（-PkotsuDefaultServer=... で上書き可、空にすると未入力）
        val defaultServer = (project.findProperty("kotsuDefaultServer") as String?) ?: "http://192.168.0.2:8000/"
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"$defaultServer\"")
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    sourceSets { getByName("test").resources.srcDir("../../spec") }
}
apply(from = "../release-signing.gradle")

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation(project(":core"))
}
