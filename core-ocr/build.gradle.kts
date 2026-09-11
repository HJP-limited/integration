plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Agent_0910 모듈(:tool-contact 등)이 전부 21 툴체인이라 여기도 21 로 맞춘다.
// 11 로 두면 Gradle 이 "compatible with JVM runtime version 11" 을 못 찾아 해석에 실패한다.
// :app 의 compileOptions 는 11 그대로다 — Agent_0910 의 :app 도 같은 조합으로 빌드된다.
kotlin {
    jvmToolchain(21)
}

dependencies {
    // 명함 레코드 타입(BusinessCardRecord)이 여기 있다. 예전에는 :core 의 Room 엔티티를
    // 만들었는데, 그 자리를 Agent_0910 의 플랫폼 중립 레코드가 대신한다.
    api(project(":tool-contact"))

    // ONNX Runtime 과 OpenCV 의 자바 API 는 안드로이드용(aar)과 데스크톱용(jar)이 같은
    // 패키지·시그니처를 쓴다. 그래서 여기서는 **컴파일용으로만** 데스크톱 jar 를 걸고,
    // 실제 구현은 소비하는 쪽이 제공한다 — :app 은 aar, :desktop 은 jar.
    // 이 덕분에 검출 임계값·CTC 디코드 같은 인식 로직이 폰과 노트북에서 한 벌만 존재한다.
    compileOnly("com.microsoft.onnxruntime:onnxruntime:1.22.0")
    compileOnly("com.microsoft.onnxruntime:onnxruntime-extensions:0.13.0")
    compileOnly("org.openpnp:opencv:4.9.0-0")

    compileOnly("org.json:json:20240303")

    testImplementation(libs.junit)
    // OCR->저장->검색 배선 시험이 suspend 함수를 부른다.
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation("org.json:json:20240303")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.22.0")
    testImplementation("org.openpnp:opencv:4.9.0-0")
}

tasks.test {
    useJUnit()
}
