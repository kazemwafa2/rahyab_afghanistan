plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ksFile: String? = System.getenv("KEYSTORE_FILE")

// نشانی مخزن — یک بار در gradle.properties تغییر دهید (rahyabGithubRepo=کاربر/مخزن)
val ghRepo: String = (project.findProperty("rahyabGithubRepo") as String? ?: "kazemwafa2/rahyab_afghanistan")

android {
    namespace = "af.rahyab.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "af.rahyab.app"
        minSdk = 21          // Android 5.0+
        targetSdk = 35
        versionCode = 16
        versionName = "0.9.6"

        // نشانی مخزن در BuildConfig قرار می‌گیرد تا User-Agent و نشانی manifest
        // بسته‌ها به‌صورت متمرکز ساخته شوند (Config.kt).
        buildConfigField("String", "RAHYAB_REPO_URL", "\"https://github.com/$ghRepo\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (ksFile != null) {
            create("release") {
                storeFile = file(ksFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (ksFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    testOptions {
        // آزمون‌های واحد از android.util.Log و… استفاده می‌کنند (مقدار پیش‌فرض برگردانده شود)
        unitTests.isReturnDefaultValues = true
    }

    androidResources {
        // فایل‌های صدای دری باید بدون فشرده‌سازی ذخیره شوند تا MediaPlayer
        // بتواند مستقیم از داخل APK بخواند (openFd)
        noCompress.addAll(listOf("ogg", "wav", "mp3", "zip", "pmtiles", "rafgraph", "rafidx"))
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.android.material:material:1.12.0")
    // PMTiles از نسخه ۱۱٫۷ پشتیبانی می‌شود (نقشه آفلاین تک‌فایلی)
    implementation("org.maplibre.gl:android-sdk:11.11.0")

    testImplementation("junit:junit:4.13.2")
    // org.json واقعی برای آزمون‌های واحد (شبیه‌ساز اندروید خروجی خالی می‌دهد)
    testImplementation("org.json:json:20240303")
}
