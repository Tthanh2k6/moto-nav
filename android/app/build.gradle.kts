import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Khoá Vietmap lấy từ file .env ở thư mục gốc dự án (moto-nav/.env), không ghi vào mã nguồn.
val vietmapApiKey: String = rootProject.file("../.env").takeIf { it.exists() }
    ?.readLines()
    ?.firstOrNull { it.startsWith("VIETMAP_API_KEY=") }
    ?.substringAfter("=")?.trim()?.trim('"', '\'')
    ?: ""
// Thiếu khoá thì vẫn build được (để chạy demo), nhưng phải báo rõ: không thì trên máy chỉ thấy tìm kiếm lỗi mà không hiểu vì sao.
if (vietmapApiKey.isBlank()) {
    logger.warn("VIETMAP_API_KEY missing in moto-nav/.env: search and routing will not work")
}

android {
    namespace = "dev.motonav.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.motonav.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        // Khoá được chép vào một chuỗi Java, nên phải thoát dấu \ và dấu " kẻo hỏng file BuildConfig.
        buildConfigField(
            "String",
            "VIETMAP_API_KEY",
            "\"" + vietmapApiKey.replace("\\", "\\\\").replace("\"", "\\\"") + "\"",
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":platform"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation(platform("androidx.compose:compose-bom:2025.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    // Bộ "core" đã có đủ bốn biểu tượng app dùng (Search, Close, Settings, ArrowBack); bộ "extended" có hàng nghìn
    // biểu tượng và làm APK debug (không rút gọn mã) nặng thêm hàng chục MB.
    implementation("androidx.compose.material:material-icons-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Chỉ để AppGraph tạo MỘT OkHttpClient dùng chung cho Vietmap và Overpass (cùng phiên bản với module sources).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
