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
    api(project(":hud"))
    testImplementation(testFixtures(project(":hud")))
    testImplementation(testFixtures(project(":model")))
    // chỉ để sinh dữ liệu mẫu cho firmware (FirmwareGoldenDumpTest): đi qua đúng FrameEncoder/FrameDecoder
    testImplementation(project(":protocol"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // thư mục dữ liệu mẫu dùng chung (tuyến thật đã ghi lại, phản hồi API mẫu)
    systemProperty("motonav.testdata", rootProject.file("testdata").absolutePath)
    systemProperty("motonav.snapshots", layout.buildDirectory.dir("snapshots").get().asFile.absolutePath)
    systemProperty("java.awt.headless", "true")
    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
    }
}
