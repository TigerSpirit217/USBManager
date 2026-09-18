import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
}

android {
    namespace = "com.tiger.usbmanager"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.tiger.usbmanager"
        minSdk = 26
        targetSdk = 37
        versionCode = 6
        versionName = "6"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
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

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            freeCompilerArgs.addAll(
                "-Xno-param-assertions",
                "-Xno-call-assertions",
                "-Xno-receiver-assertions",
                "-language-version=2.0",
            )
        }
    }
}

configurations.all {
    exclude("org.jetbrains.kotlin", "kotlin-stdlib-jdk7")
    exclude("org.jetbrains.kotlin", "kotlin-stdlib-jdk8")
}

dependencies {
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.gson)
}
