plugins {
    alias(libs.plugins.androidApplication)
}

// Build a universal APK by default; use -PtargetAbis=arm64-v8a for a single ABI.
val supportedAbis = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64", "riscv64")
val targetAbis = providers.gradleProperty("targetAbis")
    .map { value -> value.split(',').map(String::trim).distinct() }
    .getOrElse(supportedAbis)
require(targetAbis.isNotEmpty() && targetAbis.all { it in supportedAbis }) {
    "Invalid targetAbis: ${targetAbis.joinToString()}. Supported ABIs: ${supportedAbis.joinToString()}"
}

android {
    namespace = "com.tiger.usbmanager"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.tiger.usbmanager"
        // libxposed API/service 102 and the authentication backend require API 26.
        minSdk = 26
        targetSdk = 37
        versionCode = 7
        versionName = "7"
        ndk {
            abiFilters += targetAbis
        }
        externalNativeBuild {
            ndkBuild {
                arguments += "NDK_APPLICATION_MK:=src/main/jni/Application.mk"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        jniLibs.useLegacyPackaging = true
        resources {
            excludes += setOf(
                "META-INF/*.version",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/native-image/**",
                "META-INF/version-control-info.textproto",
                "kotlin-tooling-metadata.json",
                "kotlin/**",
                "DebugProbesKt.bin",
            )
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    // Built-in Kotlin inherits JVM 21 from android.compileOptions.
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xno-param-assertions",
            "-Xno-call-assertions",
            "-Xno-receiver-assertions",
        )
    }
}

configurations.all {
    exclude("org.jetbrains.kotlin", "kotlin-stdlib-jdk7")
    exclude("org.jetbrains.kotlin", "kotlin-stdlib-jdk8")
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation(platform(libs.kotlin.bom))
    implementation(libs.core)
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.gson)
}
