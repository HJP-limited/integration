plugins {
    alias(libs.plugins.kotlin.jvm)
}

/**
 * The device-run evidence contract, as its own module.
 *
 * It has to be compiled into two places at once — the host unit tests that prove the rules and the
 * instrumentation APK that obeys them — and a shared source directory could not deliver that
 * reliably here. A module can: `app` depends on it from both `testImplementation` and
 * `androidTestImplementation`, so exactly one implementation exists and the host proof is a proof
 * about the code that will run on the device.
 *
 * Deliberately dependency-free. It is plain file I/O over `java.io`, with no Android, no LiteRT and
 * no agent types, which is what lets the host exercise every rule against a temporary directory. Its
 * Java release level matches the app's so the same bytecode is valid in both.
 */
kotlin {
    jvmToolchain(21)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    testImplementation(libs.junit)
}
