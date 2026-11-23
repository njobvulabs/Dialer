plugins {
    id("com.android.library")
}

android {
    namespace = "top.niunaijun.blackbox"
    compileSdk = 37
    ndkVersion = "27.1.12297006"

    buildFeatures {
        aidl = true
    }

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/cpp/Android.mk")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    lint {
        abortOnError = false
        disable += listOf("UnusedResources", "RestrictedApi")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:-deprecation", "-Xlint:-unchecked", "-Xlint:-rawtypes"))
}

dependencies {
    implementation(project(":black-reflection"))
    annotationProcessor(project(":compiler"))
    implementation("androidx.appcompat:appcompat:1.2.0")
    implementation("com.moandjiezana.toml:toml4j:0.7.2")
    implementation("com.github.tiann:FreeReflection:3.2.2")
}
