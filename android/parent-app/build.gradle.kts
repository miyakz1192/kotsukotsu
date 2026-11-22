plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// 親用管理アプリ（フェーズ4、docs/06 2章）。外出先（サーバ不通）での引き出し・調整が主目的
android {
    // core の通知（Reminders）はゲームだけが使う。親用アプリは通知を出さないので、権限なしの警告は対象外
    lint { disable += "NotificationPermission" }
    namespace = "com.kotsukotsu.parent"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.kotsukotsu.parent"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
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
}
apply(from = "../release-signing.gradle")

dependencies {
    implementation(project(":core")) // ApiResult, ApiJson, normalizeBaseUrl、WorkManager
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
