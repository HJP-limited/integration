import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
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
    implementation(project(":core"))
    implementation(project(":core-ocr"))

    // :app 이 aar 로 쓰는 것과 같은 자바 API 의 데스크톱 구현.
    // 인식 코드는 :core-ocr 한 벌이고 런타임만 여기서 바꿔 낀다.
    implementation("com.microsoft.onnxruntime:onnxruntime:1.22.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-extensions:0.13.0")
    implementation("org.openpnp:opencv:4.9.0-0")

    // 안드로이드는 org.json 이 플랫폼에 내장이라 :core 가 compileOnly 로 두었다.
    // JVM 에는 없으므로 여기서 실제 구현을 넣는다.
    implementation("org.json:json:20240303")
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
