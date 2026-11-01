import java.util.zip.ZipFile

plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.omni.downloader.releasesmoke"
    compileSdk = 34
    defaultConfig {
        minSdk = 26
        targetSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        create("release") {
            isMinifyEnabled = true
            proguardFiles("proguard-rules.pro")
            testProguardFiles("proguard-rules.pro")
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    // A separate APK bundles its own test runtime, which must not depend on
    // original class names from the R8-optimized application.
    targetProjectPath = ":app"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// AGP excludes module dependencies already present in the tested APK, even when
// R8 removed their classes there. Stage these two harness APIs as local jars so
// the black-box runner has its own runtime without changing production keeps.
val harnessApi = configurations.create("isolatedHarnessApi") {
    isTransitive = false
    isCanBeConsumed = false
}
val harnessDir = layout.buildDirectory.dir("isolated-harness-api")
val stageHarnessApi = tasks.register("stageHarnessApi") {
    inputs.files(harnessApi)
    outputs.dir(harnessDir)
    doLast {
        val dir = harnessDir.get().asFile.apply { mkdirs() }
        harnessApi.resolve().forEach { artifact ->
            if (artifact.extension == "aar") {
                ZipFile(artifact).use { archive ->
                    archive.getInputStream(archive.getEntry("classes.jar")).use { input ->
                        dir.resolve("tracing.jar").outputStream().use { input.copyTo(it) }
                    }
                }
            } else artifact.copyTo(dir.resolve("kotlin-stdlib.jar"), overwrite = true)
        }
    }
}

dependencies {
    add(harnessApi.name, "androidx.tracing:tracing:1.2.0@aar")
    add(harnessApi.name, "org.jetbrains.kotlin:kotlin-stdlib:1.9.24@jar")
    implementation(files(harnessDir.map { it.file("tracing.jar") }, harnessDir.map { it.file("kotlin-stdlib.jar") }).builtBy(stageHarnessApi))
    implementation("androidx.test.ext:junit:1.1.5")
    implementation("androidx.test:runner:1.5.2")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
