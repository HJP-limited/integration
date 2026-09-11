plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.hjp.agent.litert"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":agent-contract"))
    implementation(project(":tool-contract"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.litert.lm.android)
    testImplementation(libs.junit)
}
