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
// :desktop 은 아직 옛 :core 를 보고 있어 빌드에서 잠시 뺀다. 다음 커밋에서 같은
// AgentKernel 스택으로 다시 붙인다 — 노트북과 폰이 같은 코드를 돌아야 한다는 제약은
// 그대로다.
// include(":desktop")

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
