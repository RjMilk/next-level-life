plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.nextlevellife.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.nextlevellife.app"
        minSdk = 29
        targetSdk = 37
        versionCode = 6
        versionName = "0.6.1"

        // Оба телефона (S25, Z Fold 8) — arm64; остальные архитектуры только раздувают APK на ~75 МБ.
        ndk {
            abiFilters += "arm64-v8a"
            // Сборка для эмулятора на ПК: gradle -Pemu … добавляет x86_64. В обычную сборку не попадает.
            if (project.hasProperty("emu")) abiFilters += "x86_64"
        }
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

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.core:core-ktx:1.19.1")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Камера и распознавание позы: всё работает на устройстве, без интернета.
    val camerax = "1.6.2"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("com.google.mlkit:pose-detection-accurate:18.0.0-beta5")

    // Шаги и пробежки с часов Garmin и телефона: только чтение из Health Connect, данные не покидают телефон.
    implementation("androidx.health.connect:connect-client:1.1.0")

    testImplementation("junit:junit:4.13.2")
}
