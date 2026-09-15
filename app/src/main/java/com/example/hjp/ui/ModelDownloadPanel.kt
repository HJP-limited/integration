package com.example.hjp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.example.hjp.models.ModelDownloads
import com.example.hjp.models.ModelInstaller
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

@Composable
internal fun ModelDownloadPanel() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    Column {
        Text("필수 모델 직접 다운로드 · 약 2.8GB (Wi-Fi 권장)")
        Text("EmbeddingGemma는 Hugging Face 이용 동의와 읽기 권한 토큰이 필요합니다. 토큰은 저장하지 않습니다.")
        TextButton(onClick = { uriHandler.openUri("https://huggingface.co/litert-community/embeddinggemma-300m") }) {
            Text("모델 이용 조건 확인·동의")
        }
        OutlinedTextField(value = token, onValueChange = { token = it }, enabled = !running,
            label = { Text("Hugging Face 읽기 토큰") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation())
        Button(enabled = !running, onClick = {
            running = true
            val accessToken = token
            token = ""
            scope.launch {
                try {
                    val installer = ModelInstaller(context.getExternalFilesDir("models") ?: File(context.filesDir, "models"))
                    for (model in ModelDownloads.required) {
                        status = "${model.fileName}: 기존 파일 확인 중"
                        installer.download(model, accessToken) { received, total ->
                            // Installer reports from IO; marshal Compose state changes to the UI.
                            withContext(Dispatchers.Main) {
                                status = "${model.fileName}: ${received * 100 / total}% (100% 이후 해시 검증)"
                            }
                        }
                    }
                    status = "필수 모델 3개 다운로드·해시 검증 완료. 앱을 강제 종료한 뒤 다시 열어 주세요."
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // Avoid displaying network exception URLs (which may contain signed CDN credentials).
                    status = if (error is IllegalStateException || error is IllegalArgumentException)
                        error.message ?: "다운로드 실패" else "네트워크 또는 저장 오류. 다시 누르면 이어받습니다."
                } finally {
                    running = false
                }
            }
        }) { Text(if (running) "다운로드 중…" else "필수 모델 다운로드 / 이어받기") }
        Text("다운로드 중에는 이 화면을 유지해 주세요. 화면을 나가면 중단되며 다음 실행 시 이어받습니다.")
        if (status.isNotBlank()) Text(status)
    }
}
