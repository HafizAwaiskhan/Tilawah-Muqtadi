plugins {
    alias(libs.plugins.kotlin.jvm)
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencies {
    api(project(":engine"))
    // Only the shared `ai.onnxruntime` Java API is used. The consumer supplies the runtime:
    // :app -> libs.onnxruntime.android, JVM tests -> libs.onnxruntime.jvm.
    compileOnly(libs.onnxruntime.jvm)
    testImplementation(libs.onnxruntime.jvm)
    testImplementation(libs.sqlite.jdbc)
    testImplementation(libs.kotlinx.coroutines.core) // engine exposes StateFlow (engine has it as implementation)
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}
tasks.test {
    // Real-audio tests read models/ (gitignored) and data/build/quran_ref_v1.db; they skip when absent.
    systemProperty("repo.root", rootDir.absolutePath)
    maxHeapSize = "2g"
    testLogging { showStandardStreams = true; events("passed", "skipped", "failed") }
}
