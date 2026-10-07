plugins {
    alias(libs.plugins.androidApplication)
}

android {
    namespace = "com.tiger.usbmanager"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.tiger.usbmanager"
        // libxposed API 102 requires API 26. Scheme packages set their own minimum.
        minSdk = 26
        targetSdk = 37
        versionCode = 7
        versionName = "7"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        buildConfig = false
    }

    androidResources {
        // Keep the Chinese defaults and the English/Chinese resources used by libraries.
        localeFilters += listOf(
            "en", "en-rUS", "en-rGB", "en-rAU", "en-rCA", "en-rIN",
            "zh", "zh-rCN", "zh-rTW", "zh-rHK",
        )
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
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
    implementation(platform(libs.kotlin.bom))
    implementation(libs.core)
    compileOnly(libs.libxposed.api)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
}
