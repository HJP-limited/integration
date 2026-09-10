package com.example.hjp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hjp.ui.theme.Slate200
import com.example.hjp.ui.theme.Slate500

/**
 * SCR-09 설정.
 *
 * 목업의 계정/OAuth/시그니처 항목은 아직 붙일 백엔드가 없어 **상태를 그대로 표시**한다 —
 * 눌러도 아무 일이 없는 행을 진짜처럼 두면 어디까지 되는지 알 수 없게 된다.
 */
@Composable
fun SettingsScreen(
    engineStatus: String,
    toolLlmStatus: String,
    chatLlmStatus: String,
    cardCount: Int?,
    onOpenModels: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader("설정", "모델과 데이터 상태를 확인합니다")

        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(56.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        HjpIcons.USER,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 22.sp,
                    )
                }
                Column(Modifier.padding(start = 14.dp)) {
                    Text(
                        "온디바이스 사용자",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "명함 ${cardCount?.let { "${it}장" } ?: "…"} · 계정 연동 없음",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        SettingsSection("모델") {
            SettingsRow(HjpIcons.AGENT, "대화 모델", statusLabel(chatLlmStatus))
            SettingsRow(HjpIcons.LINK, "도구 호출 모델", statusLabel(toolLlmStatus))
            SettingsRow(HjpIcons.SEARCH, "검색 엔진", engineStatus)
            SettingsRow(
                HjpIcons.EDIT,
                "모델 파일 관리",
                "가져오기·진단·임베딩 색인",
                onClick = onOpenModels,
            )
        }

        SettingsSection("아직 없는 기능") {
            SettingsRow(HjpIcons.LOCK, "계정 · 로그인", "미구현 (SCR-01)")
            SettingsRow(HjpIcons.MAIL, "Gmail 연동 · 메일 초안", "미구현 (SCR-08)")
            SettingsRow(HjpIcons.STAR, "즐겨찾기 · 태그 편집", "미구현")
        }
    }
}

/** `"missing: …"` 로 시작하면 그 역할은 쓸 수 없다 — 경로를 다 보여줄 필요는 없다. */
private fun statusLabel(status: String): String =
    if (status.startsWith("missing:")) "모델 없음" else status.substringAfterLast('/').ifBlank { status }

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Black,
            color = Slate500,
            modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
        )
        SectionCard(padding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)) {
            content()
        }
    }
}

@Composable
private fun SettingsRow(
    icon: String,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(icon, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp)
        ) {
            Text(
                title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                subtitle,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (onClick != null) {
            Text(HjpIcons.RIGHT, color = Slate200, fontSize = 20.sp)
        }
    }
}
