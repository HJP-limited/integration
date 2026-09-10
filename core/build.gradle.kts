import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Android(:app)와 데스크톱(:desktop)이 같은 바이트코드를 쓴다. :app 의 compileOptions 가 11 이므로
// 여기서도 11 로 맞춘다 — 더 높이면 :app 이 이 모듈을 읽지 못한다.
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
    // Room 애너테이션만 쓴다(순수 jar). 엔티티가 여기 있어도 :app 의 Room 컴파일러가 읽는다.
    api("androidx.room:room-common:2.6.1")

    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // org.json 은 안드로이드 플랫폼에 내장돼 있다. implementation 으로 넣으면 APK 에 중복
    // 포장되므로 compileOnly 로 두고, 소비하는 쪽(:desktop·테스트)이 실제 구현을 제공한다.
    compileOnly("org.json:json:20240303")

    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
}

tasks.test {
    useJUnit()
}
