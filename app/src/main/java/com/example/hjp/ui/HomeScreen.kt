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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hjp.tool.contact.BusinessCardRecord
import com.example.hjp.CardDirectory
import com.example.hjp.ui.theme.BluePrimary
import com.example.hjp.ui.theme.BlueSoft
import com.example.hjp.ui.theme.SkyOnSoft
import com.example.hjp.ui.theme.SkySoft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 화면 상단 제목. 뒤로 가기가 필요한 화면은 [onBack] 을 준다. */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            Text(
                "‹",
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(end = 12.dp),
            )
        }
        Column {
            Text(
                "HJP",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                title,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** SCR-02 홈 — 누적 명함 수, 빠른 액션, 최근 명함. */
@Composable
fun HomeScreen(
    directory: CardDirectory,
    onCapture: () -> Unit,
    onAgent: () -> Unit,
    onSeeAll: () -> Unit,
    onCardClick: (BusinessCardRecord) -> Unit,
    modifier: Modifier = Modifier,
) {
    var total by remember { mutableStateOf<Int?>(null) }
    var recent by remember { mutableStateOf<List<BusinessCardRecord>>(emptyList()) }

    // 카드 수는 시드 로딩을 유발할 수 있어 IO 로 뺀다.
    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            val count = directory.totalCardCount()
            // 최근 추가 = updatedAtMillis 내림차순. OCR 로 방금 넣은 카드가 맨 위에 온다.
            count to directory.recentCards(3)
        }
        total = loaded.first
        recent = loaded.second
    }

    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader("홈", "저장한 명함을 한눈에 봅니다")

        StatCard(
            label = "저장된 명함 수",
            value = total?.let { "${it}장" } ?: "…",
            description = "촬영하면 검색·대화에 바로 반영됩니다",
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QuickAction(
                icon = HjpIcons.CAMERA,
                title = "명함 촬영",
                subtitle = "카메라로 등록",
                container = BlueSoft,
                content = BluePrimary,
                onClick = onCapture,
                modifier = Modifier.weight(1f),
            )
            QuickAction(
                icon = HjpIcons.AGENT,
                title = "AI Agent",
                subtitle = "자연어로 찾기",
                container = SkySoft,
                content = SkyOnSoft,
                onClick = onAgent,
                modifier = Modifier.weight(1f),
            )
        }

        SectionCard {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionTitle("최근 추가 명함")
                Text(
                    "전체보기",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onSeeAll),
                )
            }
            if (recent.isEmpty()) {
                Text(
                    "아직 명함이 없어요. 촬영으로 첫 명함을 등록해 보세요.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            } else {
                recent.forEach { card ->
                    ContactRow(card = card, onClick = { onCardClick(card) })
                }
            }
        }
    }
}

@Composable
private fun QuickAction(
    icon: String,
    title: String,
    subtitle: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(modifier = modifier, onClick = onClick) {
        Box(
            Modifier
                .size(44.dp)
                .background(container, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(icon, color = content, fontSize = 20.sp)
        }
        Text(
            title,
            fontWeight = FontWeight.Black,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 14.dp),
        )
        Text(
            subtitle,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}
