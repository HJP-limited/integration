# 실기기(갤럭시 S21급) 디버그 설치 스크립트.
#
# 모델은 **폰에 이미 있으면 다시 밀지 않는다.** 3.1GB 를 USB 로 매번 보내면 몇 분씩
# 걸리는데, 대부분은 바뀌지 않는다. 크기가 같으면 전송만 건너뛰고 SHA-256은 다시 확인한다.
# 강제로 다시 보내려면 -ForceModels 를 준다.
param(
    [switch]$ForceModels,
    [switch]$SkipBuild,
    [switch]$RunMultiturn165
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

# 필수 모델은 경고 후 폴백하는 대상이 아니다. 설치를 시작하기 전에 세 파일의 존재와
# 공식 해시를 모두 확인한다. 잘못된 파일을 3GB 전송한 뒤에야 알아차리지 않게 하기 위함이다.
$models = @(
    @{
        Name = "gemma-4-E2B-it.litertlm"
        Sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
    },
    @{
        Name = "embeddinggemma-300m.tflite"
        Sha256 = "37115ef7bff76cd37dd86abe503ff511b1032bf85fc624a85c49c84899e92bc5"
    },
    @{
        Name = "sentencepiece.model"
        Sha256 = "d6daa52d93d7aad10e8388bd526c4e501d914b47177398d1d9621f1fe48438c7"
    }
)

foreach ($model in $models) {
    $path = Join-Path $modelDir $model.Name
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "필수 모델 없음: $($model.Name). 모델 없는 폴백 상태로는 테스트하지 않습니다."
    }
    $actualHash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne $model.Sha256) {
        throw "필수 모델 해시 불일치: $($model.Name) expected=$($model.Sha256) actual=$actualHash"
    }
}
"로컬 모델 3개 SHA-256 확인 완료"

if (-not $SkipBuild) {
    & .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "빌드 실패" }
}

$apk = "app\build\outputs\apk\debug\app-debug.apk"
$testApk = "app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) { throw "앱 APK 없음: $apk" }
if (-not (Test-Path -LiteralPath $testApk -PathType Leaf)) { throw "테스트 APK 없음: $testApk" }
"APK: {0:N1} MB" -f ((Get-Item $apk).Length / 1MB)

# 네이티브 라이브러리가 빠진 APK 는 LLM 이 안 뜬다 — OneDrive 파일 잠금으로
# mergeDebugNativeLibs 가 조용히 실패한 전력이 있다.
# **총 크기로 판단하지 않는다.** 시드 데이터(카드 수)에 따라 총량이 크게 달라져서
# 임계값이 금방 낡는다(5000장 -> 1000장으로 줄이자 16.9MB -> 3.4MB). 대신 결정적인
# 파일이 실제로 들어갔는지 이름으로 확인한다.
# KIE 분류기는 APK 에 넣는 자산이라 여기서 같이 본다 — 없으면 정규식 폴백으로
# 조용히 내려가서(라인정확도 98.0% -> 85.3%) 폰에서는 눈치채기 어렵다.
$requiredEntries = @(
    "lib/arm64-v8a/liblitertlm_jni.so",
    "lib/arm64-v8a/libgemma_embedding_model_jni.so",
    "assets/ocr/det.onnx",
    "assets/ocr/rec.onnx",
    "assets/ocr/textline_ori.onnx",
    "assets/ocr/kie_minilm_int8.onnx",
    "assets/ocr/kie_tokenizer.onnx",
    "assets/ocr/korean_dict.txt",
    "assets/ocr/kie_labels.json",
    "assets/cards/cards_embeddings.bin",
    "assets/cards/cards_embeddings_ids.json",
    "assets/cards/cards_embeddings_fingerprint.json"
)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path $apk))
try {
    $entryNames = $zip.Entries.FullName
    foreach ($entry in $requiredEntries) {
        if ($entryNames -notcontains $entry) {
            throw "$entry 가 APK 에 없습니다. app/build 를 지우고 다시 빌드하세요(OneDrive 잠금 의심)."
        }
    }
    "APK 필수 모델/런타임 $($requiredEntries.Count)개 확인"
} finally { $zip.Dispose() }

# 에뮬레이터가 함께 떠 있어도 실기기 하나를 명시적으로 고른다. 완료 게이트를 AVD에서
# 실행하거나 adb가 "more than one device"로 중단되는 일을 막는다.
$connectedSerials = @(
    (& $adb devices) | ForEach-Object {
        if ($_ -match '^(\S+)\s+device$') { $Matches[1] }
    }
)
$physicalArm64Serials = @(
    $connectedSerials | Where-Object {
        if ($_ -like 'emulator-*') { return $false }
        $abi = ([string](& $adb -s $_ shell getprop ro.product.cpu.abi)).Trim()
        return $abi -like 'arm64*'
    }
)
if ($physicalArm64Serials.Count -ne 1) {
    throw "arm64 실기기가 정확히 1대 연결돼야 합니다. 감지: $($physicalArm64Serials -join ', ')"
}
$deviceSerial = $physicalArm64Serials[0]
$adbTarget = @("-s", $deviceSerial)
"대상 실기기: $deviceSerial"

& $adb devices -l
& $adb @adbTarget install -r $apk
if ($LASTEXITCODE -ne 0) { throw "설치 실패" }

# 첫 실행으로 앱 전용 외부 폴더를 Android가 만들게 한 뒤 모델을 넣는다.
& $adb @adbTarget shell am start -W -n com.example.hjp/.MainActivity | Out-Host
if ($LASTEXITCODE -ne 0) { throw "앱 첫 실행 실패" }
& $adb @adbTarget shell am force-stop com.example.hjp
& $adb @adbTarget shell mkdir -p $deviceModelDir
if ($LASTEXITCODE -ne 0) { throw "기기 모델 폴더 생성 실패" }

# 세 파일 전부 우리가 민다. embeddinggemma 는 AI Edge RAG SDK 가 쓰는데 토크나이저
# (sentencepiece.model)와 **한 세트**라 둘 중 하나만 있으면 임베더가 안 뜬다.
# 새 폰에는 아무것도 없으므로 "폰에 있겠지" 를 전제하지 않는다.
# FunctionGemma 는 빼 둔다. Agent_0910 은 대화/도구호출 모델을 나누지 않고 생성 모델
# **하나**를 쓰므로, 289MB 를 밀어도 아무도 읽지 않는다(옛 스택의 저메모리 폴백 잔재).
#
# 생성 모델은 이름을 바꾸지 않고 그대로 민다. 앱이 모델 폴더의 .litertlm 중에서 골라
# 쓰고(AppContainer.resolveGenerativeArtifact), 고른 파일이 진짜인지는 바이트 크기와
# SHA-256 으로 확인한다.
foreach ($model in $models) {
    $name = $model.Name
    $path = Join-Path $modelDir $name
    $localSize = (Get-Item $path).Length
    $remote = "$deviceModelDir/$name"
    # 새 설치에서는 원격 파일이 아직 없어서 stat 이 아무 출력도 내지 않는다. 명령 결과에
    # 바로 .Trim() 을 부르면 $null.Trim() 이 되어 첫 모델을 밀기도 전에 스크립트가 멈춘다.
    $remoteSize = ([string](& $adb @adbTarget shell "stat -c %s '$remote' 2>/dev/null")).Trim()

    if (-not $ForceModels -and $remoteSize -eq "$localSize") {
        "건너뜀 (동일): $name  {0:N0} bytes" -f $localSize
    } else {
        "전송: $name  {0:N0} bytes" -f $localSize
        & $adb @adbTarget push $path $remote
        if ($LASTEXITCODE -ne 0) { throw "$name 전송 실패" }
    }

    $remoteHashLine = ([string](& $adb @adbTarget shell "sha256sum '$remote' 2>/dev/null")).Trim()
    $remoteHash = ($remoteHashLine -split '\s+')[0].ToLowerInvariant()
    if ($remoteHash -ne $model.Sha256) {
        throw "기기 모델 해시 불일치: $name expected=$($model.Sha256) actual=$remoteHash"
    }
    "기기 SHA-256 확인: $name"
}

& $adb @adbTarget install -r $testApk
if ($LASTEXITCODE -ne 0) { throw "계측 테스트 APK 설치 실패" }

$requiredTests = @(
    "com.example.hjp.RequiredModelsInstrumentedTest",
    "com.example.hjp.OcrAssetsInstrumentedTest",
    "com.example.hjp.LiteRtGatewayToolCallInstrumentedTest",
    "com.example.hjp.EmbeddingGemmaArm64InstrumentedTest"
)
if ($RunMultiturn165) {
    $requiredTests += "com.example.hjp.CurrentMultiturn165InstrumentedTest"
    Write-Host "현재 165개/435턴 전체 앱 재생을 실행합니다. 장시간이 걸리며 테스트 기기를 사용하세요."
}
$requiredTests = $requiredTests -join ','
$testOutput = & $adb @adbTarget shell am instrument -w -r `
    -e class $requiredTests `
    com.example.hjp.test/androidx.test.runner.AndroidJUnitRunner 2>&1
$instrumentExit = $LASTEXITCODE
$testOutput | Out-Host
$evidenceExit = 0
if ($RunMultiturn165) {
    # Preserve partial evidence even when the evaluator fails. Never remove device reports here.
    & $adb @adbTarget pull "/sdcard/Android/data/com.example.hjp/files/evaluation" "build/multiturn-165-evidence"
    $evidenceExit = $LASTEXITCODE
}
if ($instrumentExit -ne 0 -or $evidenceExit -ne 0 -or -not ($testOutput -match 'OK \(')) {
    throw "필수 모델 통합 테스트 실패. 누락 모델을 폴백/skip으로 통과시키지 않습니다."
}

& $adb @adbTarget shell am start -n com.example.hjp/.MainActivity
Write-Host "필수 모델 3개 전송·해시·로드 및 생성/검색/OCR 테스트를 모두 통과했습니다."
