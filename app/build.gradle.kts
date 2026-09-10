plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    androidResources {
        noCompress += "onnx"
        noCompress += "tflite"
        noCompress += "task"
        noCompress += "litertlm"
    }
    namespace = "com.example.hjp"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.hjp"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    // 검색·멀티턴 로직 본체. :desktop 러너도 같은 모듈을 쓰므로 규칙이 한 곳에만 있다.
    implementation(project(":core"))
    // OCR 검출·인식·KIE. 같은 이유로 코드는 :core-ocr 한 곳에 있고, 여기서는 안드로이드용
    // 런타임(aar)만 제공한다 — :desktop 은 같은 코드에 데스크톱 jar 를 물린다.
    implementation(project(":core-ocr"))
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-extensions-android:0.13.0")
    implementation("org.opencv:opencv:4.12.0")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    // 안드로이드의 org.json 은 단위 테스트에서 스텁("not mocked")이라 그대로는 못 쓴다.
    // 검색 규칙 테스트가 실제 명함 JSON(data/cards_test.json)을 읽어야 해서 실제 구현을 넣는다.
    testImplementation("org.json:json:20240303")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation("androidx.room:room-runtime:2.6.1")
    annotationProcessor("androidx.room:room-compiler:2.6.1")
    // EmbeddingGemma 실행용 공식 SDK — MediaPipe TextEmbedder는 이 모델을 못 읽는다 (메타데이터 없음)
    implementation("com.google.ai.edge.localagents:localagents-rag:0.3.0")
    // localagents-rag의 내부 proto 클래스가 이걸 필요로 하는데 SDK의 POM에 선언이 빠져 있어 직접 추가해야 한다
    // (NoClassDefFoundError: Lcom/google/protobuf/GeneratedMessageLite;)
    implementation("com.google.protobuf:protobuf-javalite:4.35.1")
    // **버전을 고정한다 — `latest.release` 는 쓰지 않는다.**
    // 2026-09-04 에 0.17.0 이 올라오면서 아무도 코드를 건드리지 않았는데 빌드가 깨졌다:
    // 0.17.0 은 Kotlin 2.4 로 빌드돼 metadata 버전이 2.4.0 인데, 이 프로젝트의 컴파일러
    // (Kotlin 2.2.10)는 2.3.0 까지만 읽는다 →
    //   "Class 'com.google.ai.edge.litertlm.Engine' was compiled with an incompatible version of Kotlin"
    // 부동 버전은 어제 되던 빌드가 오늘 깨지게 만들고, 출시 빌드를 재현 불가능하게 한다.
    // 최신(0.17.0 이상)으로 올리려면 Kotlin 플러그인을 2.4.x 로 함께 올려야 한다(카탈로그의
    // kotlin-compose 가 version.ref = "kotlin" 이라 Compose 컴파일러도 같이 움직인다).
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.16.1")
}
