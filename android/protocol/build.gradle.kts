import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
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
    testImplementation(testFixtures(project(":model")))
    testImplementation(kotlin("test"))
    // chỉ để test đọc testdata/golden_frames.json
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}

tasks.test {
    useJUnitPlatform()
    // thư mục dữ liệu mẫu dùng chung (tuyến thật đã ghi lại, phản hồi API mẫu)
    systemProperty("motonav.testdata", rootProject.file("testdata").absolutePath)
    systemProperty("java.awt.headless", "true")
    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
    }
}
