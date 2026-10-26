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
    api(project(":protocol"))
    api(project(":nav"))
    api(project(":planner"))
    api(project(":sources"))
    api(project(":display"))
    testImplementation(testFixtures(project(":hud")))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    // đọc file chuyến mẫu (DemoTrips); :sources dùng thư viện này nhưng không lộ ra cho module khác
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(testFixtures(project(":model")))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // thư mục dữ liệu mẫu dùng chung (tuyến thật đã ghi lại, phản hồi API mẫu)
    systemProperty("motonav.testdata", rootProject.file("testdata").absolutePath)
    systemProperty("java.awt.headless", "true")
    // Phát lại bản ghi chuyến đi thật (RideReplayFilesTest): -Pride=đường/dẫn/ride-….jsonl, hoặc chép vào testdata/rides/.
    // Khai báo làm đầu vào để đổi file thì Gradle chạy lại test thay vì báo "đã cập nhật".
    val ride = providers.gradleProperty("ride").orElse("")
    systemProperty("motonav.ride", ride.get())
    inputs.property("motonav.ride", ride)
    ride.get().takeIf { it.isNotBlank() }?.let { inputs.file(it) }
    inputs.files(rootProject.fileTree("testdata/rides"))
    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
    }
}
