pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "HJP"
include(":app")
include(":core-ocr")
// 노트북 러너. **저장소에는 없다** — 실기기에서 도는 것만 올린다는 규칙이라 추적을 끊었다
// (.gitignore 참고). 로컬에 폴더가 있으면 그때만 빌드에 낀다. 조건 없이 include 하면
// 새로 clone 한 사람의 빌드가 없는 모듈을 찾다가 깨진다.
if (file("desktop").isDirectory) include(":desktop")

// Agent_0910 (HJP_dataset_gen_by_v1) 의 에이전트/도구 계층. 도구 호출 루프와 턴 라우팅이
// 여기 있다 — 검색만 직접 부르던 기존 runChat 을 이쪽이 대체한다.
include(":agent-contract")
include(":tool-contract")
include(":agent-core")
include(":search-core")
include(":tool-contact")
include(":tool-android-intents")
include(":tool-datetime")
include(":llm-litert")
include(":agent-local-gateway")
include(":device-evidence")
