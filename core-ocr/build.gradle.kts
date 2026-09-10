import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    api(project(":core"))

    // ONNX Runtime 과 OpenCV 의 자바 API 는 안드로이드용(aar)과 데스크톱용(jar)이 같은
    // 패키지·시그니처를 쓴다. 그래서 여기서는 **컴파일용으로만** 데스크톱 jar 를 걸고,
    // 실제 구현은 소비하는 쪽이 제공한다 — :app 은 aar, :desktop 은 jar.
    // 이 덕분에 검출 임계값·CTC 디코드 같은 인식 로직이 폰과 노트북에서 한 벌만 존재한다.
    compileOnly("com.microsoft.onnxruntime:onnxruntime:1.22.0")
    compileOnly("com.microsoft.onnxruntime:onnxruntime-extensions:0.13.0")
    compileOnly("org.openpnp:opencv:4.9.0-0")

    compileOnly("org.json:json:20240303")

    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.22.0")
    testImplementation("org.openpnp:opencv:4.9.0-0")
}

tasks.test {
    useJUnit()
}
