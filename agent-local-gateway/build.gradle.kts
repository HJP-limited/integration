plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

/**
 * 모델 없이 도구를 고르는 규칙 기반 게이트웨이.
 *
 * Agent_0910 에서는 이 파일이 :app 안에 있어 에뮬레이터 전용이었다. 안드로이드 의존이
 * 하나도 없는 순수 JVM 코드라 여기로 옮겨, 노트북 러너(:desktop)도 **같은 라우팅**을 쓴다 —
 * 폰에서 잰 것이 노트북을 대변해야 한다는 이 프로젝트의 제약이 라우팅에도 적용된다.
 */
dependencies {
    api(project(":agent-contract"))
    // 대상 인물 해소(TurnContactTargetResolver)·이름 마스킹을 그대로 쓴다.
    api(project(":agent-core"))
    // RepositoryContactDirectory 가 명함 저장소 계약을 쓴다. 앱과 노트북이
    // **같은 이름 인덱스**를 돌게 하려고 여기 둔다.
    api(project(":tool-contact"))
    api(project(":tool-contract"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // 게이트웨이의 internal 파서를 시험한다 — :app 에 두면 internal 이 안 보인다.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}
