import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":model"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(testFixtures(project(":model")))
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // thư mục dữ liệu mẫu dùng chung (tuyến thật đã ghi lại, phản hồi API mẫu)
    systemProperty("motonav.testdata", rootProject.file("testdata").absolutePath)
    systemProperty("java.awt.headless", "true")
    // LiveApiSmokeTest (gọi Vietmap và Overpass thật) chỉ chạy khi đặt MOTONAV_LIVE=1. Khai báo biến này là đầu vào
    // để bật/tắt nó thì Gradle chạy lại test, và kết quả gọi mạng thật không bao giờ bị lấy lại từ bộ nhớ đệm.
    val live = providers.environmentVariable("MOTONAV_LIVE").orElse("")
    inputs.property("motonav.live", live)
    outputs.upToDateWhen { live.get() != "1" }
    outputs.cacheIf { live.get() != "1" }
    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
    }
}
