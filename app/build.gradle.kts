plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    id("com.google.devtools.ksp")
}

import java.util.Properties
import java.io.FileInputStream

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.vault.srd"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vault.srd"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            enableUnitTestCoverage = false
            enableAndroidTestCoverage = false
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Disable logging in release
            buildConfigField("boolean", "ENABLE_LOGGING", "false")
        }
        debug {
            isDebuggable = true
            enableUnitTestCoverage = true
            enableAndroidTestCoverage = false
            buildConfigField("boolean", "ENABLE_LOGGING", "true")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "DebugProbesKt.bin",
                "win/**",
                "darwin/**"
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.bouncycastle)
    implementation(libs.zstd)
    implementation(libs.androidx.work.runtime)
    implementation("io.insert-koin:koin-android:3.5.6")
    implementation("io.insert-koin:koin-androidx-compose:3.5.6")

    // CameraX for Intruder Selfie
    val camerax_version = "1.3.4"
    implementation("androidx.camera:camera-camera2:$camerax_version")
    implementation("androidx.camera:camera-lifecycle:$camerax_version")
    implementation("androidx.camera:camera-view:$camerax_version")

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
}

tasks.matching { it.name.startsWith("assemble") }.configureEach {
    doLast {
        val apkOutputDir = layout.buildDirectory.dir("outputs/apk").get().asFile
        if (!apkOutputDir.exists()) {
            logger.lifecycle("No APK output directory found to copy from.")
            return@doLast
        }

        val apkFiles = apkOutputDir.walkTopDown()
            .filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
            .toList()
        if (apkFiles.isEmpty()) {
            logger.lifecycle("No APK files found to copy.")
            return@doLast
        }

        val destinations = linkedSetOf(
            file(System.getProperty("user.dir")),
            rootProject.projectDir.parentFile ?: rootProject.projectDir
        )

        destinations.forEach { destinationDir ->
            apkFiles.forEach { apk ->
                copy {
                    from(apk)
                    into(destinationDir)
                    rename { apk.name }
                    duplicatesStrategy = DuplicatesStrategy.INCLUDE
                }
            }
        }
    }
}
