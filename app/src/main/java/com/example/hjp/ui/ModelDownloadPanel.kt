package com.example.hjp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.hjp.HjpApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ModelDownloadPanel() {
    val context = LocalContext.current
    val setup = (context.applicationContext as HjpApplication).modelSetup
    val state by setup.state.collectAsState()
    var accepted by remember { mutableStateOf(setup.termsAccepted) }
    var mobile by remember { mutableStateOf(false) }
    var legalDocument by remember { mutableStateOf<String?>(null) }
    var legalText by remember { mutableStateOf("") }
    LaunchedEffect(legalDocument) {
        legalText = legalDocument?.let { name -> withContext(Dispatchers.IO) {
            context.assets.open("legal/$name").bufferedReader().use { it.readText() }
        } }.orEmpty()
    }
    if (legalDocument != null) AlertDialog(
        onDismissRequest = { legalDocument = null }, title = { Text("모델 이용 약관 및 고지") },
        text = { Text(legalText, Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { legalDocument = null }) { Text("닫기") } },
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("AI 기능 준비", style = MaterialTheme.typography.headlineSmall)
        Text("명함 인식·검색 모델은 앱에 포함되어 있습니다. 대화 모델 약 2.59GB만 최초 1회 다운로드합니다. 회원가입이나 토큰은 필요하지 않습니다.")
        TextButton(onClick = { legalDocument = "Gemma-Terms.txt" }) { Text("검색 모델 이용 약관") }
        TextButton(onClick = { legalDocument = "Gemma-Prohibited-Use.txt" }) { Text("모델 사용 제한 정책") }
        TextButton(onClick = { legalDocument = "Apache-2.0.txt" }) { Text("대화 모델 라이선스") }
        TextButton(onClick = { legalDocument = "NOTICE.txt" }) { Text("모델 출처 및 고지") }
        if (!setup.termsAccepted) Row {
            Checkbox(checked = accepted, onCheckedChange = { accepted = it }, enabled = !state.busy)
            Text("모델 약관과 사용 제한 정책을 확인했으며 이에 동의합니다.")
        }
        if (!state.ready && !state.busy) Row {
            Checkbox(checked = mobile, onCheckedChange = { mobile = it })
            Text("모바일 데이터 사용 허용 (데이터 요금이 발생할 수 있습니다)")
        }
        Text(state.message)
        if (state.busy) {
            if (state.phase == "downloading" || state.phase == "waiting") {
                LinearProgressIndicator(progress = { (state.downloaded.toFloat() / state.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text("${state.downloaded * 100 / state.total}% · ${state.downloaded / 1_000_000} / ${state.total / 1_000_000} MB")
                TextButton(onClick = { setup.cancelDownload() }) { Text("다운로드 취소") }
            } else LinearProgressIndicator(Modifier.fillMaxWidth())
        } else if (state.phase != "broken" && (!state.ready || !setup.termsAccepted)) {
            Button(enabled = accepted, onClick = { setup.acceptAndDownload(mobile) }) {
                Text(if (state.ready) "동의하고 시작" else if (state.phase == "error") "다시 시도" else "동의하고 다운로드")
            }
        }
        Text("다운로드는 백그라운드에서도 진행됩니다. 완료 후 앱에서 검증하고 자동으로 활성화합니다. 명함·대화 내용은 모델 다운로드 서버로 보내지 않습니다.")
    }
}
