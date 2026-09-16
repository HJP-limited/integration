plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// Agent_0910 모듈들이 21 툴체인이라 여기도 21 로 맞춘다.
kotlin {
    jvmToolchain(21)
    // Compile the actual production codecs/contracts, not a second desktop implementation.
    sourceSets.main {
        kotlin.srcDir("../tool-android-intents/src/main/kotlin")
        kotlin.exclude("**/AndroidIntentBackends.kt")
    }
}

dependencies {
    implementation(project(":core-ocr"))
    // 앱과 같은 에이전트 커널·도구·검색을 돌린다. 캘린더/메일은 위 sourceSets로
    // 실제 플러그인과 코덱을 공유하고, 외부 Android 화면 호출만 모의 처리한다.
    implementation(project(":agent-core"))
    implementation(project(":agent-contract"))
    implementation(project(":agent-local-gateway"))
    implementation(project(":tool-contract"))
    implementation(project(":tool-contact"))
    implementation(project(":tool-datetime"))
    implementation(project(":search-core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // :app 이 aar 로 쓰는 것과 같은 자바 API 의 데스크톱 구현.
    // 인식 코드는 :core-ocr 한 벌이고 런타임만 여기서 바꿔 낀다.
    implementation("com.microsoft.onnxruntime:onnxruntime:1.22.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-extensions:0.13.0")
    implementation("org.openpnp:opencv:4.9.0-0")

    // 안드로이드는 org.json 이 플랫폼에 내장이라 :core 가 compileOnly 로 두었다.
    // JVM 에는 없으므로 여기서 실제 구현을 넣는다.
    implementation("org.json:json:20240303")

    // Room 이 안드로이드에서 돌리는 것과 **같은 SQLite 엔진**. FTS4 MATCH 의미를 코드로
    // 흉내 내지 않고 같은 SQL 을 그대로 실행해야 노트북 지표가 앱을 대변한다.
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")

    // EmbeddingGemma 의 Gemma 토크나이저. tokenizer.json 을 그대로 읽는 구현이라
    // 토크나이즈 규칙을 코틀린으로 옮겨 적지 않아도 된다(옮기면 갈라진다).
    implementation("ai.djl.huggingface:tokenizers:0.30.0")
}

application {
    mainClass.set("com.example.hjp.desktop.MainKt")
    // 윈도우 기본 콘솔 인코딩(cp949)으로는 인식 결과의 한글·필드 이모지가 깨진다.
    // 출력이 곧 검증 수단이라 UTF-8 로 못박는다.
    applicationDefaultJvmArgs = listOf(
        "-Dfile.encoding=UTF-8",
        "-Dstdout.encoding=UTF-8",
        "-Dstderr.encoding=UTF-8",
    )
}
