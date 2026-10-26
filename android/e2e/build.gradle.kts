import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Module chỉ chứa test: chạy cả dây chuyền trên JVM, từ chuyến đi giả lập tới ảnh màn hình,
// đi qua đúng những byte sẽ gửi cho màn hình thật.
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
    testImplementation(project(":model"))
    testImplementation(project(":protocol"))
    testImplementation(project(":nav"))
    testImplementation(project(":planner"))
    testImplementation(project(":sources"))
    testImplementation(project(":display"))
    testImplementation(testFixtures(project(":hud")))
    testImplementation(testFixtures(project(":model")))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    systemProperty("motonav.testdata", rootProject.file("testdata").absolutePath)
    systemProperty("motonav.snapshots", layout.buildDirectory.dir("snapshots").get().asFile.absolutePath)
    systemProperty("java.awt.headless", "true")
    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
    }
}
