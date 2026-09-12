# 실기기(갤럭시 S21급) 디버그 설치 스크립트.
#
# 모델은 **폰에 이미 있으면 다시 밀지 않는다.** 3.1GB 를 USB 로 매번 보내면 몇 분씩
# 걸리는데, 대부분은 바뀌지 않는다. 크기가 같으면 같은 파일로 보고 건너뛴다.
# 강제로 다시 보내려면 -ForceModels 를 준다.
param(
    [switch]$ForceModels,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo

$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "C:\Users\babie\AppData\Local\Android\Sdk"
$adb = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"

$deviceModelDir = "/sdcard/Android/data/com.example.hjp/files/models"

# 온디바이스 모델(3.1GB)은 .gitignore 대상이라 저장소 안에 없다. 어디에 두든 상관없게
# 순서대로 찾는다 — 환경변수 > 이 저장소의 models/ > 같이 두는 구 저장소의 models/.
# **못 찾으면 여기서 멈춘다.** 예전에는 경고만 하고 APK 설치까지 진행해서, 모델이 하나도
# 없는 앱이 폰에 깔린 채 "왜 대답을 안 하지" 를 폰에서 디버깅하게 됐다.
$modelDir = @(
    $env:HJP_MODEL_DIR,
    (Join-Path $repo "models"),
    (Join-Path (Split-Path -Parent $repo) "HJP_limitededition-main\models")
) | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1

if (-not $modelDir) {
    throw "온디바이스 모델 폴더를 못 찾았습니다. HJP_MODEL_DIR 에 경로를 주거나 $repo\models 에 두세요."
}
"모델 폴더: $modelDir"

if (-not $SkipBuild) {
    & .\gradlew.bat :app:assembleDebug --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "빌드 실패" }
}

$apk = "app\build\outputs\apk\debug\app-debug.apk"
"APK: {0:N1} MB" -f ((Get-Item $apk).Length / 1MB)

# 네이티브 라이브러리가 빠진 APK 는 LLM 이 안 뜬다 — OneDrive 파일 잠금으로
# mergeDebugNativeLibs 가 조용히 실패한 전력이 있다.
# **총 크기로 판단하지 않는다.** 시드 데이터(카드 수)에 따라 총량이 크게 달라져서
# 임계값이 금방 낡는다(5000장 -> 1000장으로 줄이자 16.9MB -> 3.4MB). 대신 결정적인
# 파일이 실제로 들어갔는지 이름으로 확인한다.
# KIE 분류기는 APK 에 넣는 자산이라 여기서 같이 본다 — 없으면 정규식 폴백으로
# 조용히 내려가서(라인정확도 98.0% -> 85.3%) 폰에서는 눈치채기 어렵다.
$required = @("liblitertlm_jni.so", "libgemma_embedding_model_jni.so", "kie_minilm_int8.onnx")
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path $apk))
try {
    $names = $zip.Entries | ForEach-Object { Split-Path $_.FullName -Leaf }
    foreach ($lib in $required) {
        if ($names -notcontains $lib) {
            throw "$lib 가 APK 에 없습니다. app/build 를 지우고 다시 빌드하세요(OneDrive 잠금 의심)."
        }
    }
    "APK 자산 확인: $($required -join ', ')"
} finally { $zip.Dispose() }

& $adb devices -l
& $adb install -r $apk
if ($LASTEXITCODE -ne 0) { throw "설치 실패" }

& $adb shell mkdir -p $deviceModelDir

# 네 파일 전부 우리가 민다. embeddinggemma 는 AI Edge RAG SDK 가 쓰는데 토크나이저
# (sentencepiece.model)와 **한 세트**라 둘 중 하나만 있으면 임베더가 안 뜬다.
# 새 폰에는 아무것도 없으므로 "폰에 있겠지" 를 전제하지 않는다.
# FunctionGemma 는 빼 둔다. Agent_0910 은 대화/도구호출 모델을 나누지 않고 생성 모델
# **하나**를 쓰므로, 289MB 를 밀어도 아무도 읽지 않는다(옛 스택의 저메모리 폴백 잔재).
#
# 생성 모델은 이름을 바꾸지 않고 그대로 민다. 앱이 모델 폴더의 .litertlm 중에서 골라
# 쓰고(AppContainer.resolveGenerativeArtifact), 고른 파일이 진짜인지는 바이트 크기와
# SHA-256 으로 확인한다.
$models = @(
    "gemma-4-E2B-it.litertlm",
    "embeddinggemma-300m.tflite",
    "sentencepiece.model"
)

$missing = @()
foreach ($name in $models) {
    $path = Join-Path $modelDir $name
    if (-not (Test-Path $path)) {
        $missing += $name
        continue
    }
    $localSize = (Get-Item $path).Length
    $remote = "$deviceModelDir/$name"
    $remoteSize = (& $adb shell "stat -c %s '$remote' 2>/dev/null").Trim()

    if (-not $ForceModels -and $remoteSize -eq "$localSize") {
        "건너뜀 (동일): $name  {0:N0} bytes" -f $localSize
        continue
    }
    "전송: $name  {0:N0} bytes" -f $localSize
    & $adb push $path $remote
    if ($LASTEXITCODE -ne 0) { throw "$name 전송 실패" }
}

# 없는 파일은 기능 단위로 무엇이 죽는지 말해 준다. 이름만 나열하면 폰에서 다시 헤맨다.
foreach ($name in $missing) {
    switch ($name) {
        "gemma-4-E2B-it.litertlm"     { Write-Warning "$name 없음 -> 대화 답변이 안 됩니다(검색 결과만 뜸)." }
        "embeddinggemma-300m.tflite"  { Write-Warning "$name 없음 -> 벡터 검색이 빠지고 키워드 전용이 됩니다." }
        "sentencepiece.model"         { Write-Warning "$name 없음 -> embeddinggemma 토크나이저가 없어 임베더가 안 뜹니다." }
    }
}

& $adb shell am start -n com.example.hjp/.MainActivity
Write-Host "앱을 열고 설정 탭 > 모델 섹션에서 상태를 확인하세요."
