import java.util.Properties
import java.io.FileInputStream

// 讀取 local.properties
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {

    namespace = "com.champion.king"          // ← 改成你原本的 namespace
    compileSdk = 34                           // ← 依你原本設定（34/35 皆可，建議與專案一致）

    signingConfigs {
        create("release") {
            storeFile = file(localProperties.getProperty("KEYSTORE_FILE") ?: "")
            storePassword = localProperties.getProperty("KEYSTORE_PASSWORD") ?: ""
            keyAlias = localProperties.getProperty("KEY_ALIAS") ?: ""
            keyPassword = localProperties.getProperty("KEY_PASSWORD") ?: ""
        }
    }

    defaultConfig {
        applicationId = "com.champion.king"
        minSdk = 24
        targetSdk = 34
        versionCode = 91
        versionName = "1.1.47"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String",
            "APP_SECRET",
            "\"${localProperties.getProperty("app.secret", "")}\""
        )
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // 需要可自行加上 debug 設定
        }
    }

    buildFeatures {
        viewBinding = true   // 你專案有用 viewBinding，建議開啟
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        disable.add("NullSafeMutableLiveData")
    }
}

dependencies {
    // 固定 Firebase BoM（避免浮動 33.+）
    implementation(platform("com.google.firebase:firebase-bom:33.9.0"))

    // Firebase—依你現況最少需要的套件
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-database-ktx")

    // 協程（與 Kotlin 2.1 相容）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // AndroidX 常用套件（可與你原本版本對齊）
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation(libs.androidx.gridlayout)

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")

    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")

    // App Check：Play Integrity（正式使用）
    //implementation("com.google.firebase:firebase-appcheck-playintegrity")

    // App Check：Debug Provider（僅 debug 版用；用於模擬器/開發機）
    //debugImplementation("com.google.firebase:firebase-appcheck-debug")

    // ========== 新增：網路相關套件 ==========
    // Retrofit - HTTP Client
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")

    // OkHttp - 底層網路庫 + 日誌攔截器（方便除錯）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Gson - JSON 解析（Retrofit 需要）
    implementation("com.google.code.gson:gson:2.10.1")
}
