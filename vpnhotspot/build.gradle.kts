// ZyBox 集成模块：VPNHotspot 原样搬入（Tethering 页 + wlan 热点 + VPN 共享 + 系统管理共享 + 硬件加速）
// 版本适配 ZyBox 矩阵：Kotlin 2.0.21 / AGP 8.8.1 / Room 2.6.1 / Compose 1.7.6
plugins {
    id("com.android.library")
    id("kotlin-android")
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21"
    id("com.google.devtools.ksp")
    id("kotlin-kapt")
    id("kotlin-parcelize")
}

val javaVersion = 11

configurations.all {
    resolutionStrategy {
        force(
            "org.jetbrains.kotlin:kotlin-stdlib:2.3.21",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.3.21",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.3.21",
            "org.jetbrains.kotlin:kotlin-parcelize-runtime:2.3.21",
            "io.ktor:ktor-io-jvm:3.4.3",
            "org.jetbrains.kotlinx:kotlinx-collections-immutable:0.5.0",
            "org.jetbrains.kotlinx:kotlinx-collections-immutable-jvm:0.5.0",
            "be.mygod.librootkotlinx:librootkotlinx:2.0.0-beta02",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}
val hiddenApiStubAnnotations = configurations.create("hiddenApiStubAnnotations")
val compileHiddenApiStubs = tasks.register<JavaCompile>("compileHiddenApiStubs") {
    source("src/hiddenApiStubs/java")
    classpath = files(androidComponents.sdkComponents.bootClasspath) + hiddenApiStubAnnotations
    destinationDirectory.set(layout.buildDirectory.dir("intermediates/hiddenApiStubs/classes"))
    sourceCompatibility = javaVersion.toString()
    targetCompatibility = javaVersion.toString()
    options.release.set(javaVersion)
}
val hiddenApiStubsClasses = files(compileHiddenApiStubs.flatMap { it.destinationDirectory })
    .builtBy(compileHiddenApiStubs)
val hiddenApiStubsJar = tasks.register<Jar>("hiddenApiStubsJar") {
    from(hiddenApiStubsClasses)
    archiveFileName.set("hidden-api-stubs.jar")
}

android {
    namespace = "zy.hotspot.app"
    compileSdk = 37  // VPNHotspot 源码使用 SDK 36/37 API

    defaultConfig {
        minSdk = 29  // VPNHotspot 最低要求
    }

    compileOptions {
        sourceCompatibility(JavaVersion.VERSION_11)
        targetCompatibility(JavaVersion.VERSION_11)
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging.resources.excludes += listOf(
        "**/*.kotlin_*",
        "META-INF/versions/**",
    )
}

kapt {
    arguments {
        arg("room.expandProjection", "true")
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    // Room（与 ZyBox 主模块同版本，避免冲突）
    implementation("androidx.room:room-runtime:2.8.5")
    kapt("androidx.room:room-compiler:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")

    // Compose（Kotlin 2.0.21 兼容）
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3.adaptive:adaptive:1.0.0")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // 其余 VPNHotspot 依赖（版本适配 Kotlin 2.0.21）
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.collection:collection:1.6.0")
    implementation("androidx.browser:browser:1.8.0")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.ktor:ktor-io-jvm:3.4.3")
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.5.0")
    implementation("be.mygod.librootkotlinx:librootkotlinx:2.0.0-beta02")
    implementation("com.jakewharton.timber:timber:5.0.1")
    implementation("com.squareup.wire:wire-runtime:6.1.0")
    implementation("com.google.zxing:core:3.5.4")

    // hidden API stubs（VPNHotspot 源码用 hidden API 编译）
    compileOnly(files(hiddenApiStubsJar))
    debugImplementation("androidx.compose.ui:ui-tooling")
    hiddenApiStubAnnotations("androidx.annotation:annotation-jvm:1.9.1")
}
