import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.happwner"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.happwner"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "1.3.4-api102"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    androidResources {
        localeFilters += listOf("ru", "en")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    val keystorePropertiesFile = rootProject.file("local.properties")
    val keystoreProperties = Properties()
    if (keystorePropertiesFile.exists()) {
        keystoreProperties.load(keystorePropertiesFile.inputStream())
    }

    signingConfigs {
        create("release") {
            val storePath = keystoreProperties["RELEASE_STORE_FILE"]?.toString()
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = keystoreProperties["RELEASE_STORE_PASSWORD"]?.toString()
                keyAlias = keystoreProperties["RELEASE_KEY_ALIAS"]?.toString()
                keyPassword = keystoreProperties["RELEASE_KEY_PASSWORD"]?.toString()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingConfigs.getByName("release").storeFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            // Disable shrinking for faster development
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
    
    buildFeatures {
        compose = false
        buildConfig = true
    }

    testOptions.unitTests.isReturnDefaultValues = true
    testOptions.unitTests.all {
        // The 500-config fixture exceeds this heap size: the test must stream it.
        it.maxHeapSize = "64m"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    compileOnly("de.robv.android.xposed:api:82")
    compileOnly("io.github.libxposed:api:102.0.0")
    testImplementation("io.github.libxposed:api:102.0.0")
    testImplementation(libs.junit)
    testImplementation("org.json:json:20250517")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
