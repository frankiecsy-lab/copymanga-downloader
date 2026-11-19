// MangaCopy Android — app 模組
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mangacopy.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mangacopy.app"
        minSdk = 26          // Android 8.0 — WebView 現代 API + 靜態 WEBP 原生支援
        targetSdk = 35
        versionCode = 12
        versionName = "0.3.9"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
        )
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")

    // Material 3（手機/平板自適應導航用 NavigationSuite）
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // 圖片加載（書架封面、閱讀器；原生支援 WEBP）
    implementation("io.coil-kt:coil-compose:2.7.0")

    // 網絡（漫畫列表 + 圖片下載，純 HTTP 路徑）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 協程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 單元測試（純 JVM 邏輯：HTML 解析、CBZ 命名、章節 diff）
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")   // JVM 測試環境冇內置 org.json
}
