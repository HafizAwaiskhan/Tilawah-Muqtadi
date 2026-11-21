plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// The Quran reference DB is built by tools/ into data/build/ (not committed under app/).
// It is copied into a generated assets dir so the APK bundles it when present.
val referenceDbName = "quran_ref_v1.db"
val generatedRefAssets = layout.buildDirectory.dir("generated/referenceAssets")
val copyReferenceDb by tasks.registering(Copy::class) {
    from(rootProject.layout.projectDirectory.file("data/build/$referenceDbName")) {
        // Missing file is fine: the app shows "reference data not installed".
    }
    into(generatedRefAssets)
}

android {
    namespace = "com.qurancompanion"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.qurancompanion"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "REFERENCE_DB_ASSET", "\"$referenceDbName\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        // SQLite asset must be stored uncompressed so it can be streamed/copied cheaply.
        noCompress += "db"
    }
    sourceSets {
        getByName("main").assets.srcDir(generatedRefAssets)
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            // JVM tests that need the real reference DB read it from here (skipped when absent).
            it.systemProperty("quranRefDb", rootProject.file("data/build/$referenceDbName").absolutePath)
        }
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

tasks.named("preBuild") { dependsOn(copyReferenceDb) }

dependencies {
    implementation(project(":engine"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.onnxruntime.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(kotlin("test"))
    // JVM tests open data/build/quran_ref_v1.db directly.
    testImplementation("org.xerial:sqlite-jdbc:3.41.2.2")
}
